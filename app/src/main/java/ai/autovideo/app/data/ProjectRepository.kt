package ai.autovideo.app.data

import ai.autovideo.app.data.local.ProjectDao
import ai.autovideo.app.data.local.ProjectEntity
import ai.autovideo.app.data.local.SceneEntity
import ai.autovideo.app.data.model.ProjectSettings
import ai.autovideo.app.data.model.SceneDraft
import ai.autovideo.app.data.model.SceneDocument
import ai.autovideo.app.data.remote.ApiJson
import ai.autovideo.app.util.PROJECTS_DIRECTORY
import ai.autovideo.app.util.copyUriToFile
import ai.autovideo.app.util.safeFileName
import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

@Serializable
private data class ProjectManifest(
    val id: String,
    val name: String,
    val createdAt: Long,
    val narrationName: String,
    val narrationDurationMs: Long,
    val status: String,
    val settings: ProjectSettings
)

class ProjectRepository(
    private val context: Context,
    private val dao: ProjectDao
) {
    fun observeProjects(): Flow<List<ProjectEntity>> = dao.observeProjects()
    fun observeProject(id: String): Flow<ProjectEntity?> = dao.observeProject(id)
    fun observeScenes(id: String) = dao.observeScenes(id)
    fun observeAssets(id: String) = dao.observeProjectAssets(id)
    fun observeLibrary() = dao.observeLibrary(ai.autovideo.app.data.remote.LOCAL_LIBRARY_ID)
    fun observePresets() = dao.observePresets()

    suspend fun createProject(
        name: String,
        audioUri: Uri,
        narrationName: String,
        narrationSize: Long,
        durationMs: Long,
        settings: ProjectSettings
    ): ProjectEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val root = File(context.filesDir, PROJECTS_DIRECTORY)
        val folder = File(root, "$id-${safeFileName(name).replace(' ', '-').take(42)}")
        val media = File(folder, "media")
        File(folder, "thumbnails").mkdirs()
        media.mkdirs()
        if (!folder.exists()) error("Não foi possível criar a pasta do projeto.")

        val safeAudioName = safeFileName(narrationName)
        val audioFile = File(folder, "narracao-${safeAudioName}")
        val actualAudioSize = copyUriToFile(context.contentResolver, audioUri, audioFile)
        val now = System.currentTimeMillis()
        val project = ProjectEntity(
            id = id,
            name = name.trim(),
            folderPath = folder.absolutePath,
            narrationPath = audioFile.absolutePath,
            narrationName = narrationName,
            narrationSizeBytes = narrationSize.takeIf { it > 0 } ?: actualAudioSize,
            durationMs = durationMs,
            settingsJson = encodeSettings(settings),
            currentStage = "Pronto para analisar",
            progress = 0,
            requestedVideos = settings.videoCount,
            requestedImages = settings.imageCount,
            createdAt = now,
            updatedAt = now
        )
        File(folder, "settings.json").writeText(project.settingsJson, Charsets.UTF_8)
        dao.upsertProject(project)
        writeManifest(project)
        project
    }

    suspend fun getProject(id: String): ProjectEntity? = dao.getProject(id)

    suspend fun saveSettings(project: ProjectEntity, settings: ProjectSettings) = withContext(Dispatchers.IO) {
        val updated = project.copy(
            settingsJson = encodeSettings(settings),
            requestedVideos = settings.videoCount,
            requestedImages = settings.imageCount,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsertProject(updated)
        File(project.folderPath, "settings.json").writeText(updated.settingsJson, Charsets.UTF_8)
        writeManifest(updated)
    }

    suspend fun updateProgress(
        projectId: String,
        status: String,
        stage: String,
        progress: Int,
        error: String? = null
    ) {
        dao.updateProgress(projectId, status, stage, progress.coerceIn(0, 100), error)
        dao.getProject(projectId)?.let { withContext(Dispatchers.IO) { writeManifest(it.copy(status = status, currentStage = stage, progress = progress, errorMessage = error)) } }
    }

    suspend fun writeAnalysisFiles(projectId: String, transcript: String, scenes: List<SceneDraft>) = withContext(Dispatchers.IO) {
        val project = dao.getProject(projectId) ?: error("Projeto não encontrado.")
        val folder = File(project.folderPath)
        File(folder, "script.txt").writeText(transcript, Charsets.UTF_8)
        File(folder, "script.srt").writeText(SrtGenerator.fromScenes(scenes), Charsets.UTF_8)
        File(folder, "scenes.json").writeText(ApiJson.encodeToString(SceneDocument.serializer(), SceneDocument(scenes)), Charsets.UTF_8)
    }

    suspend fun updateProject(project: ProjectEntity) {
        dao.upsertProject(project.copy(updatedAt = System.currentTimeMillis()))
        withContext(Dispatchers.IO) { writeManifest(project) }
    }

    suspend fun availableBytes(): Long = withContext(Dispatchers.IO) {
        val stats = StatFs(context.filesDir.absolutePath)
        stats.availableBytes
    }

    suspend fun deleteProject(projectId: String) = withContext(Dispatchers.IO) {
        val project = dao.getProject(projectId) ?: return@withContext
        val root = File(context.filesDir, PROJECTS_DIRECTORY).canonicalFile
        val folder = File(project.folderPath).canonicalFile
        if (folder.path.startsWith(root.path + File.separator)) folder.deleteRecursively()
        dao.deleteProjectAssets(projectId)
        dao.deleteScenes(projectId)
        dao.deleteProjectRow(projectId)
    }

    fun decodeSettings(json: String): ProjectSettings = runCatching {
        ApiJson.decodeFromString(ProjectSettings.serializer(), json)
    }.getOrDefault(ProjectSettings())

    private fun encodeSettings(settings: ProjectSettings): String =
        ApiJson.encodeToString(ProjectSettings.serializer(), settings)

    private fun writeManifest(project: ProjectEntity) {
        val folder = File(project.folderPath)
        if (!folder.exists()) return
        val settings = decodeSettings(project.settingsJson)
        val manifest = ProjectManifest(
            id = project.id,
            name = project.name,
            createdAt = project.createdAt,
            narrationName = project.narrationName,
            narrationDurationMs = project.durationMs,
            status = project.status,
            settings = settings
        )
        File(folder, "project.json").writeText(ApiJson.encodeToString(manifest), Charsets.UTF_8)
    }
}

object SrtGenerator {
    fun fromScenes(scenes: List<SceneDraft>): String = scenes.mapIndexed { index, scene ->
        val start = (scene.start * 1_000).toLong().coerceAtLeast(0)
        val end = (scene.end * 1_000).toLong().coerceAtLeast(start + 1)
        "${index + 1}\n${timestamp(start)} --> ${timestamp(end)}\n${scene.narration.trim()}"
    }.joinToString("\n\n", postfix = "\n")

    private fun timestamp(ms: Long): String {
        val value = ms.coerceAtLeast(0)
        val hours = value / 3_600_000
        val minutes = (value % 3_600_000) / 60_000
        val seconds = (value % 60_000) / 1_000
        val millis = value % 1_000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}
