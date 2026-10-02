package ai.autovideo.app.work

import ai.autovideo.app.AutoVideoApplication
import ai.autovideo.app.data.local.ProjectEntity
import ai.autovideo.app.data.local.SceneEntity
import ai.autovideo.app.data.model.ApiProvider
import ai.autovideo.app.data.model.MediaKind
import ai.autovideo.app.data.model.SceneDraft
import ai.autovideo.app.data.model.SceneDocument
import ai.autovideo.app.data.remote.ApiJson
import ai.autovideo.app.domain.DistributionPlanner
import ai.autovideo.app.util.estimatedStorageBytes
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException

class ProjectPipelineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph get() = (applicationContext as AutoVideoApplication).graph
    private val projectId get() = inputData.getString(KEY_PROJECT_ID).orEmpty()

    override suspend fun doWork(): Result {
        if (projectId.isBlank()) return Result.failure(workDataOf("message" to "Projeto não informado."))
        val project = graph.projects.getProject(projectId) ?: return Result.failure(workDataOf("message" to "Projeto removido."))
        setForeground(WorkNotifications.foregroundInfo(applicationContext, "AutoVideo AI", "Analisando narração e buscando mídia", NOTIFICATION_ID))
        return try {
            val settings = graph.projects.decodeSettings(project.settingsJson)
            var effectiveDurationMs = project.durationMs.coerceAtLeast(0L)
            val stats = StatFs(applicationContext.filesDir.absolutePath)
            val estimate = estimatedStorageBytes(project.narrationSizeBytes, settings.videoCount, settings.imageCount)
            if (stats.availableBytes < estimate) {
                graph.projects.updateProgress(projectId, "STORAGE_LOW", "Espaço insuficiente", 0,
                    "Estimativa ${estimate / (1024 * 1024)} MB; disponíveis ${stats.availableBytes / (1024 * 1024)} MB.")
                return Result.failure(workDataOf("message" to "Espaço insuficiente para baixar as mídias."))
            }

            var scenes = graph.dao.getScenes(projectId)
            var drafts = loadSavedDrafts(project)
            if (scenes.isEmpty() || drafts.isNullOrEmpty()) {
                val key = graph.secureKeys.get(ApiProvider.GEMINI.name)
                    ?: run {
                        graph.projects.updateProgress(projectId, "WAITING_API", "Configure a Gemini API", 0,
                            "Adicione e valide uma chave Gemini em APIs e Integrações.")
                        return Result.failure(workDataOf("message" to "Chave Gemini não configurada."))
                    }
                stage("TRANSCRIBING", "Enviando narração ao Gemini", 4)
                val audio = File(project.narrationPath)
                val analysis = graph.gemini.analyzeNarration(audio, key, settings)
                if (effectiveDurationMs <= 0L) {
                    effectiveDurationMs = ((analysis.scenes.maxOfOrNull { it.end } ?: 1.0) * 1_000).toLong().coerceAtLeast(1_000L)
                }
                val analyzedScenes = normalizeScenes(analysis.scenes, effectiveDurationMs)
                drafts = analyzedScenes
                graph.projects.writeAnalysisFiles(projectId, analysis.transcript, analyzedScenes)
                scenes = toSceneEntities(projectId, analyzedScenes, effectiveDurationMs)
                graph.dao.deleteScenes(projectId)
                graph.dao.upsertScenes(scenes)
                stage("SCENES_READY", "${scenes.size} microcenas criadas", 26)
            } else {
                if (effectiveDurationMs <= 0L) {
                    val savedDrafts = drafts.orEmpty()
                    effectiveDurationMs = ((savedDrafts.maxOfOrNull { it.end } ?: 1.0) * 1_000).toLong().coerceAtLeast(1_000L)
                    drafts = normalizeScenes(savedDrafts, effectiveDurationMs)
                    scenes = toSceneEntities(projectId, drafts.orEmpty(), effectiveDurationMs)
                    graph.dao.deleteScenes(projectId)
                    graph.dao.upsertScenes(scenes)
                    val transcript = File(project.folderPath, "script.txt").takeIf { it.exists() }?.readText(Charsets.UTF_8)
                        ?: drafts.orEmpty().joinToString(" ") { it.narration }
                    graph.projects.writeAnalysisFiles(projectId, transcript, drafts.orEmpty())
                }
                stage("SCENES_READY", "Retomando microcenas salvas", 26)
            }

            if (effectiveDurationMs <= 0L) effectiveDurationMs = scenes.maxOfOrNull { it.endMs }?.coerceAtLeast(1_000L) ?: 1_000L
            if (project.durationMs != effectiveDurationMs) {
                graph.projects.updateProject(project.copy(durationMs = effectiveDurationMs))
            }
            val totalDuration = effectiveDurationMs
            val plan = DistributionPlanner.plan(
                scenes, totalDuration, settings.videoCount, settings.imageCount, settings.distributionMode
            )
            if (plan.isEmpty()) {
                graph.projects.updateProgress(projectId, "NEEDS_MEDIA", "Defina ao menos uma mídia", 100,
                    "Este projeto não tem vídeos ou imagens configurados.")
                return Result.success()
            }
            val sceneByIndex = scenes.associateBy { it.sceneIndex }
            val plannedBySlot = plan.associateBy { it.slotIndex }
            val existingAssets = graph.dao.getProjectAssets(projectId)
            existingAssets.filter { asset -> plannedBySlot[asset.slotIndex]?.mediaKind?.name != asset.mediaType }
                .forEach { asset -> graph.dao.deleteAsset(asset); runCatching { File(asset.localPath).delete() } }
            val retainedAssets = graph.dao.getProjectAssets(projectId)
            val usedSources = retainedAssets.mapNotNull { it.sourceUrl ?: it.localPath }.toMutableSet()
            var lastSource = retainedAssets.maxByOrNull { it.timelineStartMs }?.let { it.sourceUrl ?: it.localPath }
            val queryCache = mutableMapOf<String, List<ai.autovideo.app.data.remote.MediaCandidate>>()
            stage("SEARCHING_MEDIA", "Pesquisando mídias gratuitas", 30)

            for (slot in plan) {
                if (isStopped) throw CancellationException("Processamento cancelado.")
                val current = graph.dao.getProjectAssets(projectId).firstOrNull { it.slotIndex == slot.slotIndex }
                if (current != null && current.mediaType == slot.mediaKind.name && File(current.localPath).exists()) {
                    lastSource = current.sourceUrl ?: current.localPath
                    continue
                }
                val scene = sceneByIndex[slot.sceneIndex] ?: scenes.first()
                val kind = slot.mediaKind
                val queries = runCatching {
                    ApiJson.decodeFromString<List<String>>(scene.searchQueriesJson)
                }.getOrDefault(emptyList())
                    .plus(scene.visualDescription)
                    .distinct().filter(String::isNotBlank).take(5)
                var selected: ai.autovideo.app.data.remote.MediaCandidate? = null
                for (query in queries) {
                    if (isStopped) throw CancellationException("Processamento cancelado.")
                    delay(350)
                    val cacheKey = "${kind.name}:${query.lowercase().trim()}"
                    val results = queryCache[cacheKey] ?: graph.mediaSearch.search(
                        query = query,
                        mediaKind = kind,
                        settings = settings,
                        preferredDurationSeconds = settings.takeMode.fixedSeconds()
                    ).also { queryCache[cacheKey] = it }
                    selected = results.firstOrNull { candidate ->
                        val source = candidate.sourceUrl ?: candidate.existingLocalPath ?: candidate.downloadUrl
                        source == null || source !in usedSources
                    } ?: if (settings.allowRepeat) {
                        results.firstOrNull { candidate ->
                            val source = candidate.sourceUrl ?: candidate.existingLocalPath ?: candidate.downloadUrl
                            source == null || source != lastSource
                        } ?: results.firstOrNull()
                    } else null
                    if (selected != null) break
                }
                val chosen = selected
                if (chosen != null) {
                    graph.mediaFiles.downloadToProject(
                        candidate = chosen,
                        projectId = projectId,
                        projectFolderPath = project.folderPath,
                        slotIndex = slot.slotIndex,
                        sceneIndex = scene.sceneIndex,
                        timelineStartMs = slot.timelineStartMs,
                        timelineEndMs = slot.timelineEndMs
                    )
                    val sourceKey = chosen.sourceUrl ?: chosen.existingLocalPath ?: chosen.downloadUrl
                    sourceKey?.let(usedSources::add)
                    lastSource = sourceKey
                }
                val itemProgress = 30 + ((slot.slotIndex + 1).toFloat() / plan.size * 55f).toInt()
                val stageText = "Mídia ${slot.slotIndex + 1} de ${plan.size} • cena ${scene.sceneIndex + 1} de ${scenes.size}"
                graph.projects.updateProgress(projectId, "SEARCHING_MEDIA", stageText, itemProgress)
                setProgress(workDataOf("progress" to itemProgress, "slot" to slot.slotIndex + 1, "total" to plan.size))
            }

            val finalAssets = graph.dao.getProjectAssets(projectId).filter { File(it.localPath).exists() }
            val videos = finalAssets.count { it.mediaType == MediaKind.VIDEO.name }
            val images = finalAssets.count { it.mediaType == MediaKind.IMAGE.name }
            val credits = finalAssets.joinToString(System.lineSeparator()) { asset ->
                listOf(asset.title, asset.source, asset.author, asset.attributionUrl.orEmpty(), asset.sourceUrl.orEmpty())
                    .filter(String::isNotBlank).joinToString(" · ")
            }
            File(project.folderPath, "credits.txt").writeText(credits, Charsets.UTF_8)
            val thumbnail = finalAssets.firstOrNull()?.let { createThumbnail(project, it) }
            val missingCount = (plan.size - finalAssets.map { it.slotIndex }.distinct().size).coerceAtLeast(0)
            val status = if (missingCount == 0) "READY_TO_RENDER" else "NEEDS_MEDIA"
            val message = if (missingCount == 0) null else "Não foi possível encontrar mídia suficiente. Faltam $missingCount arquivo(s). Configure Pexels/Pixabay, adicione mídia local, tente novamente, reduza a quantidade ou permita repetição."
            graph.dao.updateMediaSummary(
                projectId = projectId,
                totalScenes = scenes.size,
                videos = videos,
                images = images,
                missing = missingCount,
                status = status,
                stage = if (missingCount == 0) "Timeline pronta para prévia" else "Mídia insuficiente",
                progress = 100,
                error = message
            )
            graph.projects.getProject(projectId)?.let { graph.projects.updateProject(it.copy(
                foundVideos = videos,
                foundImages = images,
                missingMedia = missingCount,
                thumbnailPath = thumbnail?.absolutePath ?: it.thumbnailPath,
                status = status,
                currentStage = if (missingCount == 0) "Timeline pronta para prévia" else "Mídia insuficiente",
                progress = 100,
                errorMessage = message
            )) }
            Result.success()
        } catch (cancelled: CancellationException) {
            graph.projects.updateProgress(projectId, "PAUSED", "Processamento pausado", 0, "O processamento foi cancelado; os arquivos já baixados foram preservados.")
            Result.failure(workDataOf("message" to "Processamento cancelado."))
        } catch (error: Exception) {
            val message = error.message ?: "Falha inesperada no processamento."
            if (runAttemptCount < MAX_RETRIES) {
                graph.projects.updateProgress(projectId, "RETRYING", "Falha temporária — nova tentativa automática", 5, message)
                Result.retry()
            } else {
                graph.projects.updateProgress(projectId, "FAILED", "Processamento interrompido", 0, message)
                Result.failure(workDataOf("message" to message))
            }
        }
    }

    private fun toSceneEntities(projectId: String, drafts: List<SceneDraft>, durationMs: Long): List<SceneEntity> {
        val durationLimit = durationMs.coerceAtLeast(1L)
        return drafts.mapIndexed { index, scene ->
            val start = ((scene.start * 1_000).toLong()).coerceIn(0L, (durationLimit - 1L).coerceAtLeast(0L))
            val end = ((scene.end * 1_000).toLong()).coerceIn(start + 1L, durationLimit.coerceAtLeast(start + 1L))
            SceneEntity(
                projectId = projectId,
                sceneIndex = index,
                startMs = start,
                endMs = end,
                durationMs = (end - start).coerceAtLeast(1L),
                narration = scene.narration,
                visualDescription = scene.visualDescription,
                searchQueriesJson = ApiJson.encodeToString(scene.searchQueries),
                preferredMedia = scene.preferredMedia
            )
        }
    }

    private fun normalizeScenes(scenes: List<SceneDraft>, durationMs: Long): List<SceneDraft> {
        if (scenes.isEmpty()) return scenes
        val total = (durationMs / 1_000.0).takeIf { it > 0.0 }
            ?: scenes.maxOfOrNull { it.end }?.coerceAtLeast(0.25) ?: 0.25
        val endLimit = total.coerceAtLeast(0.25)
        var cursor = 0.0
        val normalized = scenes.sortedBy { it.start }.mapIndexed { index, scene ->
            val minStart = cursor.coerceAtMost(endLimit - 0.25)
            val start = scene.start.coerceIn(minStart, endLimit - 0.25)
            val end = maxOf(start + 0.25, scene.end.coerceAtMost(endLimit)).coerceAtMost(endLimit)
            cursor = end
            scene.copy(id = index + 1, start = start, end = end, duration = (end - start).coerceAtLeast(0.25))
        }.toMutableList()
        val last = normalized.last()
        if (last.end < endLimit) normalized[normalized.lastIndex] = last.copy(end = endLimit, duration = endLimit - last.start)
        return normalized
    }

    private fun createThumbnail(project: ProjectEntity, asset: ai.autovideo.app.data.local.MediaAssetEntity): File? = runCatching {
        val output = File(project.folderPath, "thumbnails/cover.jpg")
        if (output.exists() && output.length() > 0) return output
        output.parentFile?.mkdirs()
        val source = File(asset.localPath)
        val bitmap = if (asset.mediaType == MediaKind.IMAGE.name) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(source.absolutePath, bounds)
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            val sample = (maxSide / 512).coerceAtLeast(1)
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeFile(source.absolutePath, options)
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source.absolutePath)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        } ?: return null
        try {
            java.io.FileOutputStream(output).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
                stream.flush()
                stream.fd.sync()
            }
        } finally {
            bitmap.recycle()
        }
        output.takeIf { it.exists() && it.length() > 0 }
    }.getOrNull()

    private suspend fun stage(status: String, text: String, progress: Int) {
        graph.projects.updateProgress(projectId, status, text, progress)
        setProgress(workDataOf("progress" to progress, "stage" to text))
    }

    private fun loadSavedDrafts(project: ProjectEntity): List<SceneDraft>? = runCatching {
        val file = File(project.folderPath, "scenes.json")
        if (!file.exists()) return null
        val json = file.readText(Charsets.UTF_8)
        runCatching { ApiJson.decodeFromString(SceneDocument.serializer(), json).scenes }
            .getOrElse { ApiJson.decodeFromString<List<SceneDraft>>(json) }
    }.getOrNull()

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        private const val NOTIFICATION_ID = 7101
        private const val MAX_RETRIES = 2
    }
}
