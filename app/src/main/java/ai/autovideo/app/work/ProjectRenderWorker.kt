package ai.autovideo.app.work

import ai.autovideo.app.AutoVideoApplication
import ai.autovideo.app.data.model.MediaKind
import ai.autovideo.app.render.Media3ExportEngine
import ai.autovideo.app.util.estimatedExportBytes
import ai.autovideo.app.util.formatBytes
import android.content.Context
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.io.File

class ProjectRenderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph get() = (applicationContext as AutoVideoApplication).graph
    private val projectId get() = inputData.getString(KEY_PROJECT_ID).orEmpty()
    private val isPreview get() = inputData.getBoolean(KEY_PREVIEW, false)

    override suspend fun doWork(): Result {
        if (projectId.isBlank()) return Result.failure(workDataOf("message" to "Projeto não informado."))
        val project = graph.projects.getProject(projectId) ?: return Result.failure(workDataOf("message" to "Projeto removido."))
        val assets = graph.dao.getProjectAssets(projectId).filter { File(it.localPath).exists() }
        val settings = graph.projects.decodeSettings(project.settingsJson)
        val renderDuration = if (isPreview) minOf(project.durationMs, 20_000L) else project.durationMs
        val requiredBytes = estimatedExportBytes(renderDuration, settings.resolution.shortEdge, settings.fps)
        val stats = StatFs(applicationContext.filesDir.absolutePath)
        val existingOutput = File(if (isPreview) project.previewPath.orEmpty() else project.outputPath.orEmpty())
        val availableIncludingReplace = stats.availableBytes + if (existingOutput.exists()) existingOutput.length() else 0L
        if (availableIncludingReplace < requiredBytes) {
            graph.projects.updateProgress(projectId, "STORAGE_LOW", "Espaço insuficiente para renderizar", project.progress,
                "Estimativa ${formatBytes(requiredBytes)}; disponível ${formatBytes(stats.availableBytes)}.")
            return Result.failure(workDataOf("message" to "Libere espaço antes de iniciar a exportação."))
        }
        if (assets.isEmpty()) return Result.failure(workDataOf("message" to "Adicione pelo menos uma mídia antes de renderizar."))
        if (!isPreview && project.missingMedia > 0) {
            graph.projects.updateProgress(projectId, "NEEDS_MEDIA", "Mídia insuficiente", 100, "Resolva a quantidade de mídias faltantes antes da renderização completa.")
            return Result.failure(workDataOf("message" to "Ainda faltam ${project.missingMedia} mídias."))
        }
        setForeground(WorkNotifications.foregroundInfo(
            applicationContext,
            if (isPreview) "Gerando prévia" else "Renderizando vídeo",
            "Exportação local com Media3 Transformer",
            if (isPreview) 7102 else 7103
        ))
        val output = File(project.folderPath, if (isPreview) "preview.mp4" else "final.mp4")
        return try {
            graph.projects.updateProgress(projectId, if (isPreview) "RENDERING_PREVIEW" else "RENDERING", "Montando timeline e exportando no aparelho", 0)
            val engine = Media3ExportEngine(applicationContext)
            engine.export(project, settings, assets, output, isPreview) { progress ->
                val safeProgress = progress.coerceIn(0, 100)
                graph.projects.updateProgress(
                    projectId,
                    if (isPreview) "RENDERING_PREVIEW" else "RENDERING",
                    if (isPreview) "Gerando prévia · ${safeProgress}%" else "Renderizando localmente · ${safeProgress}%",
                    safeProgress
                )
                setProgress(workDataOf("progress" to safeProgress))
            }
            val latest = graph.projects.getProject(projectId) ?: project
            if (isPreview) {
                graph.dao.setOutput(
                    projectId = projectId,
                    path = latest.outputPath,
                    previewPath = output.absolutePath,
                    status = if (latest.outputPath != null) "COMPLETED" else "READY_TO_RENDER",
                    stage = "Prévia pronta · ${formatDurationShort(output)}",
                    progress = 100
                )
            } else {
                graph.dao.setOutput(
                    projectId = projectId,
                    path = output.absolutePath,
                    previewPath = latest.previewPath,
                    status = "COMPLETED",
                    stage = "Vídeo concluído",
                    progress = 100
                )
            }
            graph.projects.getProject(projectId)?.let { graph.projects.updateProject(it) }
            Result.success()
        } catch (cancelled: CancellationException) {
            output.delete()
            graph.projects.updateProgress(projectId, "READY_TO_RENDER", "Renderização cancelada", 0, "Projeto preservado. Você pode renderizar novamente.")
            Result.failure(workDataOf("message" to "Renderização cancelada."))
        } catch (error: Exception) {
            output.delete()
            graph.projects.updateProgress(projectId, "FAILED", "Falha na renderização local", 0, error.message ?: "Erro Media3 desconhecido.")
            Result.failure(workDataOf("message" to (error.message ?: "Não foi possível renderizar o vídeo.")))
        }
    }

    private fun formatDurationShort(file: File): String = if (file.exists()) "MP4 local" else "Prévia"

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_PREVIEW = "preview"
    }
}
