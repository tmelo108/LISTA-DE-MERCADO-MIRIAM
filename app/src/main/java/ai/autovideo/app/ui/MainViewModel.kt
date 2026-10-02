package ai.autovideo.app.ui

import ai.autovideo.app.AutoVideoApplication
import ai.autovideo.app.data.local.MediaAssetEntity
import ai.autovideo.app.data.local.ProjectEntity
import ai.autovideo.app.data.local.PresetEntity
import ai.autovideo.app.data.model.ApiProvider
import ai.autovideo.app.data.model.MediaKind
import ai.autovideo.app.data.model.ProjectSettings
import ai.autovideo.app.data.remote.ApiTestResult
import ai.autovideo.app.data.remote.MediaCandidate
import ai.autovideo.app.data.remote.YouTubeReference
import ai.autovideo.app.domain.DistributionPlanner
import ai.autovideo.app.domain.PlannedMediaSlot
import ai.autovideo.app.work.ProjectWorkCoordinator
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

data class SearchUiState(
    val loading: Boolean = false,
    val projectId: String? = null,
    val sceneIndex: Int? = null,
    val kind: MediaKind? = null,
    val results: List<MediaCandidate> = emptyList(),
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AutoVideoApplication
    private val graph = app.graph

    val projects = graph.projects.observeProjects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val presets = graph.projects.observePresets().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val library = graph.projects.observeLibrary().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _apiTests = MutableStateFlow<Map<ApiProvider, ApiTestResult>>(emptyMap())
    val apiTests: StateFlow<Map<ApiProvider, ApiTestResult>> = _apiTests.asStateFlow()
    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun observeProject(id: String) = graph.projects.observeProject(id)
    fun observeScenes(id: String) = graph.projects.observeScenes(id)
    fun observeAssets(id: String) = graph.projects.observeAssets(id)

    fun createProject(
        name: String,
        audioUri: Uri,
        audioName: String,
        audioSize: Long,
        durationMs: Long,
        settings: ProjectSettings,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                val project = graph.projects.createProject(name, audioUri, audioName, audioSize, durationMs, settings)
                ProjectWorkCoordinator.enqueueProcessing(app, project.id)
                onCreated(project.id)
            }.onFailure { _message.value = it.message ?: "Não foi possível criar o projeto." }
        }
    }

    fun retryProcessing(projectId: String) {
        ProjectWorkCoordinator.enqueueProcessing(app, projectId)
        _message.value = "Projeto colocado novamente na fila."
    }

    fun enableMediaRepeatsAndRetry(project: ProjectEntity) {
        viewModelScope.launch {
            val settings = graph.projects.decodeSettings(project.settingsJson).copy(allowRepeat = true)
            graph.projects.saveSettings(project, settings)
            ProjectWorkCoordinator.enqueueProcessing(app, project.id)
            _message.value = "Repetição permitida. A busca será retomada sem apagar o que já foi baixado."
        }
    }

    fun reduceQuantityToFound(project: ProjectEntity) {
        viewModelScope.launch {
            val settings = graph.projects.decodeSettings(project.settingsJson).copy(
                videoCount = project.foundVideos,
                imageCount = project.foundImages
            )
            graph.projects.saveSettings(project, settings)
            ProjectWorkCoordinator.enqueueProcessing(app, project.id)
            _message.value = "Quantidades reduzidas ao que já foi encontrado."
        }
    }

    fun render(projectId: String, preview: Boolean) {
        ProjectWorkCoordinator.enqueueRender(app, projectId, preview)
        _message.value = if (preview) "Prévia adicionada à fila de renderização local." else "Renderização local adicionada à fila."
    }

    fun cancelProjectWork(projectId: String) {
        ProjectWorkCoordinator.cancel(app, projectId)
        viewModelScope.launch {
            graph.projects.updateProgress(projectId, "PAUSED", "Processamento pausado", 0,
                "Projeto e arquivos preservados. Toque em Continuar para retomar.")
        }
        _message.value = "Fila cancelada. O projeto foi preservado."
    }

    fun deleteProject(projectId: String) {
        ProjectWorkCoordinator.cancel(app, projectId)
        viewModelScope.launch {
            graph.projects.deleteProject(projectId)
            _message.value = "Projeto excluído do aparelho."
        }
    }

    fun readApiKey(provider: ApiProvider, onLoaded: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val value = graph.apis.getKey(provider).orEmpty()
            withContext(Dispatchers.Main) { onLoaded(value) }
        }
    }

    fun saveApiKey(provider: ApiProvider, key: String, onSaved: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            graph.apis.saveKey(provider, key)
            _apiTests.value = _apiTests.value - provider
            withContext(Dispatchers.Main) {
                _message.value = if (key.isBlank()) "Chave removida." else "Chave salva com criptografia do Android Keystore."
                onSaved?.invoke()
            }
        }
    }

    fun testApi(provider: ApiProvider) {
        viewModelScope.launch {
            _apiTests.value = _apiTests.value - provider
            val result = graph.apis.test(provider)
            _apiTests.value = _apiTests.value + (provider to result)
        }
    }

    fun addToLibrary(uri: Uri, kind: MediaKind, displayName: String) {
        viewModelScope.launch {
            runCatching { graph.mediaFiles.addToLibrary(uri, kind, displayName) }
                .onSuccess { _message.value = "Arquivo indexado na biblioteca local. As tags iniciais vêm do nome do arquivo." }
                .onFailure { _message.value = it.message ?: "Não foi possível adicionar o arquivo à biblioteca." }
        }
    }

    fun removeLibraryAsset(asset: MediaAssetEntity) {
        viewModelScope.launch {
            graph.dao.deleteAsset(asset)
            runCatching { File(asset.localPath).delete() }
            _message.value = "Arquivo removido da biblioteca local."
        }
    }

    fun savePreset(name: String, settings: ProjectSettings) {
        val trimmed = name.trim().ifBlank { "Meu preset" }
        viewModelScope.launch {
            val json = ai.autovideo.app.data.remote.ApiJson.encodeToString(ProjectSettings.serializer(), settings)
            graph.dao.upsertPreset(PresetEntity(trimmed, json))
            _message.value = "Preset ‘$trimmed’ salvo."
        }
    }

    fun decodeSettings(json: String): ProjectSettings = graph.projects.decodeSettings(json)

    fun searchAgain(projectId: String, sceneIndex: Int, kind: MediaKind) {
        viewModelScope.launch {
            _search.value = SearchUiState(loading = true, projectId = projectId, sceneIndex = sceneIndex, kind = kind)
            try {
                val project = graph.projects.getProject(projectId) ?: error("Projeto não encontrado.")
                val scene = graph.dao.getScenes(projectId).firstOrNull { it.sceneIndex == sceneIndex }
                    ?: error("Microcena não encontrada.")
                val queries = runCatching {
                    ai.autovideo.app.data.remote.ApiJson.decodeFromString<List<String>>(scene.searchQueriesJson)
                }.getOrDefault(emptyList()) + scene.visualDescription
                val settings = graph.projects.decodeSettings(project.settingsJson)
                // Run API queries sequentially to respect free-tier rate limits.
                val collected = mutableListOf<MediaCandidate>()
                for (query in queries.filter(String::isNotBlank).distinct().take(3)) {
                    collected += graph.mediaSearch.search(query, kind, settings, settings.takeMode.fixedSeconds())
                    if (collected.size >= 5) break
                    kotlinx.coroutines.delay(350)
                }
                _search.value = SearchUiState(false, projectId, sceneIndex, kind, collected.distinctBy { it.sourceUrl ?: it.existingLocalPath }.take(5))
            } catch (error: Exception) {
                _search.value = SearchUiState(false, projectId, sceneIndex, kind, emptyList(), error.message ?: "Busca indisponível.")
            }
        }
    }

    fun clearSearch() { _search.value = SearchUiState() }

    fun selectSearchResult(candidate: MediaCandidate) {
        val state = _search.value
        val projectId = state.projectId ?: return
        val sceneIndex = state.sceneIndex ?: return
        val kind = state.kind ?: return
        viewModelScope.launch {
            try {
                val project = graph.projects.getProject(projectId) ?: error("Projeto não encontrado.")
                val old = graph.dao.getAssetsForScene(projectId, sceneIndex).firstOrNull { it.mediaType == kind.name }
                val planned = if (old == null) findFreeSlot(project, sceneIndex, kind) else null
                val slotIndex = old?.slotIndex ?: planned?.slotIndex ?: (graph.dao.getProjectAssets(projectId).maxOfOrNull { it.slotIndex }?.plus(1) ?: 0)
                val start = old?.timelineStartMs ?: planned?.timelineStartMs ?: 0L
                val end = old?.timelineEndMs ?: planned?.timelineEndMs ?: project.durationMs
                graph.mediaFiles.downloadToProject(candidate, projectId, project.folderPath, slotIndex, sceneIndex, start, end)
                old?.let { graph.dao.deleteAsset(it); runCatching { File(it.localPath).delete() } }
                refreshMediaCounts(projectId)
                _message.value = "Mídia da cena substituída."
                _search.value = SearchUiState()
            } catch (error: Exception) {
                _message.value = error.message ?: "Não foi possível selecionar esse resultado."
            }
        }
    }

    fun importManualMedia(projectId: String, sceneIndex: Int, uri: Uri, kind: MediaKind) {
        viewModelScope.launch {
            try {
                val project = graph.projects.getProject(projectId) ?: error("Projeto não encontrado.")
                val existing = graph.dao.getAssetsForScene(projectId, sceneIndex).firstOrNull { it.mediaType == kind.name }
                val planned = if (existing == null) findFreeSlot(project, sceneIndex, kind) else null
                val slotIndex = existing?.slotIndex ?: planned?.slotIndex ?: (graph.dao.getProjectAssets(projectId).maxOfOrNull { it.slotIndex }?.plus(1) ?: 0)
                graph.mediaFiles.importManualIntoProject(
                    uri, kind, projectId, project.folderPath, sceneIndex, slotIndex,
                    existing?.timelineStartMs ?: planned?.timelineStartMs ?: 0L,
                    existing?.timelineEndMs ?: planned?.timelineEndMs ?: project.durationMs
                )
                existing?.let { graph.dao.deleteAsset(it); runCatching { File(it.localPath).delete() } }
                refreshMediaCounts(projectId)
                _message.value = "Mídia local adicionada à cena."
            } catch (error: Exception) {
                _message.value = error.message ?: "Não foi possível importar a mídia."
            }
        }
    }

    fun removeSceneMedia(projectId: String, sceneIndex: Int, assetId: String) {
        viewModelScope.launch {
            val asset = graph.dao.getAssetsForScene(projectId, sceneIndex).firstOrNull { it.id == assetId } ?: return@launch
            graph.dao.deleteAsset(asset)
            runCatching { File(asset.localPath).delete() }
            refreshMediaCounts(projectId)
            _message.value = "Mídia removida da cena."
        }
    }

    fun saveVideoToGallery(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val project = graph.projects.getProject(projectId) ?: error("Projeto não encontrado.")
                val source = File(project.outputPath ?: error("Renderize o vídeo completo antes de salvar na galeria."))
                if (!source.exists()) error("O MP4 não está disponível.")
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "${project.name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")}.mp4")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Movies/AutoVideo AI")
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = app.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Não foi possível criar o arquivo na galeria.")
                try {
                    resolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                        ?: error("Não foi possível gravar o vídeo na galeria.")
                    values.clear()
                    values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (error: Throwable) {
                    resolver.delete(uri, null, null)
                    throw error
                }
            }.onSuccess {
                withContext(Dispatchers.Main) { _message.value = "Vídeo salvo em Filmes/AutoVideo AI." }
            }.onFailure {
                withContext(Dispatchers.Main) { _message.value = it.message ?: "Falha ao salvar na galeria." }
            }
        }
    }

    fun searchYoutube(query: String, onResult: (List<YouTubeReference>, String?) -> Unit) {
        viewModelScope.launch {
            runCatching { graph.apis.searchYoutubeReferences(query) }
                .onSuccess { onResult(it, null) }
                .onFailure { onResult(emptyList(), it.message ?: "Pesquisa de referência indisponível.") }
        }
    }

    fun dismissMessage() { _message.value = null }

    private suspend fun findFreeSlot(project: ProjectEntity, sceneIndex: Int, kind: MediaKind): PlannedMediaSlot? {
        val settings = graph.projects.decodeSettings(project.settingsJson)
        val scenes = graph.dao.getScenes(project.id)
        val slots = DistributionPlanner.plan(
            scenes, project.durationMs, settings.videoCount, settings.imageCount, settings.distributionMode
        ).filter { it.mediaKind == kind }
        val occupied = graph.dao.getProjectAssets(project.id).map { it.slotIndex }.toSet()
        return slots.firstOrNull { it.sceneIndex == sceneIndex && it.slotIndex !in occupied }
            ?: slots.firstOrNull { it.slotIndex !in occupied }
    }

    private suspend fun refreshMediaCounts(projectId: String) {
        val project = graph.projects.getProject(projectId) ?: return
        val assets = graph.dao.getProjectAssets(projectId).filter { File(it.localPath).exists() }
        val videos = assets.count { it.mediaType == MediaKind.VIDEO.name }
        val images = assets.count { it.mediaType == MediaKind.IMAGE.name }
        val desired = project.requestedVideos + project.requestedImages
        val missing = (desired - assets.size).coerceAtLeast(0)
        graph.dao.updateMediaSummary(
            projectId, project.totalScenes, videos, images, missing,
            if (missing == 0) "READY_TO_RENDER" else "NEEDS_MEDIA",
            if (missing == 0) "Timeline pronta para prévia" else "Mídia insuficiente",
            100,
            if (missing == 0) null else "Ainda faltam $missing mídia(s)."
        )
    }
}
