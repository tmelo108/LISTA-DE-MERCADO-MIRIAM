package ai.autovideo.app.ui

import ai.autovideo.app.data.local.MediaAssetEntity
import ai.autovideo.app.data.local.ProjectEntity
import ai.autovideo.app.data.local.SceneEntity
import ai.autovideo.app.data.model.MediaKind
import ai.autovideo.app.data.remote.MediaCandidate
import ai.autovideo.app.data.remote.YouTubeReference
import ai.autovideo.app.util.formatBytes
import ai.autovideo.app.util.formatDuration
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ProjectDetailScreen(
    projectId: String,
    viewModel: MainViewModel,
    searchState: SearchUiState,
    onCreateAnother: () -> Unit
) {
    val context = LocalContext.current
    val projectFlow = remember(projectId) { viewModel.observeProject(projectId) }
    val scenesFlow = remember(projectId) { viewModel.observeScenes(projectId) }
    val assetsFlow = remember(projectId) { viewModel.observeAssets(projectId) }
    val project by projectFlow.collectAsStateWithLifecycle(initialValue = null)
    val scenes by scenesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val assets by assetsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var pendingScene by remember { mutableStateOf<Int?>(null) }
    var pendingKind by remember { mutableStateOf(MediaKind.VIDEO) }
    var cancelDialog by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }
    var references by remember { mutableStateOf<List<YouTubeReference>?>(null) }
    var referenceError by remember { mutableStateOf<String?>(null) }
    var referenceLoading by remember { mutableStateOf(false) }
    var referenceTitle by remember { mutableStateOf("Referências do YouTube") }

    val manualPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val scene = pendingScene
        if (uri != null && scene != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.importManualMedia(projectId, scene, uri, pendingKind)
        }
        pendingScene = null
    }

    if (project == null) {
        EmptyState("Carregando projeto…")
        return
    }
    val current = project!!
    val settings = viewModel.decodeSettings(current.settingsJson)
    val activeWork = current.status in setOf("QUEUED", "TRANSCRIBING", "SCENES_READY", "SEARCHING_MEDIA", "RETRYING", "RENDERING", "RENDERING_PREVIEW")
    val preview = current.outputPath?.takeIf { File(it).exists() } ?: current.previewPath?.takeIf { File(it).exists() }
    val byScene = assets.filter { !it.isLocalLibraryItem }.groupBy { it.sceneIndex }
    val hasMedia = assets.any { !it.isLocalLibraryItem && File(it.localPath).exists() }

    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 35.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ProjectHero(current)
        }
        if (preview != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(if (current.outputPath != null) "Vídeo concluído" else "Prévia local", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    ExoVideoPreview(preview)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            if (current.outputPath != null) shareFile(context, File(current.outputPath), "video/mp4")
                        }, enabled = current.outputPath != null, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Compartilhar")
                        }
                        OutlinedButton(onClick = { viewModel.saveVideoToGallery(projectId) }, enabled = current.outputPath != null, modifier = Modifier.weight(1f)) {
                            Text("Salvar na galeria")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("${settings.aspectRatio.label} · ${settings.resolution.label} · ${settings.fps} FPS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatBytes(File(preview).length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Fila de processamento", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(current.currentStage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${current.progress}%", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(current.progress / 100f, modifier = Modifier.fillMaxWidth().height(6.dp))
                    QueueStages(current)
                    if (!current.errorMessage.isNullOrBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Text(current.errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (current.status == "NEEDS_MEDIA") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Não foi possível encontrar mídia suficiente.", fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.retryProcessing(projectId) }, modifier = Modifier.weight(1f)) { Text("Tentar novamente") }
                                OutlinedButton(onClick = { viewModel.reduceQuantityToFound(current) }, modifier = Modifier.weight(1f)) { Text("Reduzir quantidade") }
                            }
                            Button(
                                onClick = { viewModel.enableMediaRepeatsAndRetry(current) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                            ) { Text("Permitir repetição e tentar") }
                        }
                    }
                    if (current.status in setOf("WAITING_API", "FAILED", "PAUSED", "STORAGE_LOW")) {
                        val renderFailure = current.currentStage.contains("renderiza", ignoreCase = true)
                        val canRetryRender = renderFailure && current.totalScenes > 0 && current.missingMedia == 0 && hasMedia
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { if (canRetryRender) viewModel.render(projectId, preview = false) else viewModel.retryProcessing(projectId) },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (canRetryRender) "Tentar renderizar novamente" else "Continuar / tentar de novo") }
                            if (activeWork) OutlinedButton(onClick = { cancelDialog = true }) { Text("Cancelar") }
                        }
                    } else if (activeWork) {
                        OutlinedButton(onClick = { cancelDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Cancelar processamento") }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Timeline e renderização", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${current.totalScenes} microcenas · ${current.foundVideos}/${current.requestedVideos} vídeos · ${current.foundImages}/${current.requestedImages} imagens", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    PrimaryButton("GERAR PRÉVIA · 20 SEGUNDOS", { viewModel.render(projectId, preview = true) }, enabled = hasMedia && !activeWork)
                    PrimaryButton(
                        if (current.status == "COMPLETED") "RENDERIZAR NOVAMENTE" else "RENDERIZAR VÍDEO COMPLETO",
                        { viewModel.render(projectId, preview = false) },
                        enabled = current.missingMedia == 0 && hasMedia && !activeWork
                    )
                    if (current.outputPath != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { shareFile(context, File(current.outputPath), "video/mp4") }, modifier = Modifier.weight(1f)) { Text("Compartilhar MP4") }
                            OutlinedButton(onClick = { folderDialog = true }, modifier = Modifier.weight(1f)) { Text("Abrir pasta") }
                        }
                    }
                    val srt = File(current.folderPath, "script.srt")
                    if (srt.exists()) TextButton(onClick = { shareFile(context, srt, "application/x-subrip") }) { Text("Compartilhar arquivo SRT") }
                    Text("Exportação com Media3 Transformer no Android. Não há renderização em servidor.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Editor de microcenas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Confira os takes e substitua qualquer mídia.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${scenes.size}", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            }
        }
        if (scenes.isEmpty()) {
            item { EmptyState("As microcenas aparecerão aqui depois da análise do Gemini. Configure a chave em APIs e Integrações para começar.") }
        } else {
            items(scenes, key = { "${it.projectId}-${it.sceneIndex}" }) { scene ->
                SceneEditorCard(
                    scene = scene,
                    assets = byScene[scene.sceneIndex].orEmpty(),
                    onReplace = { asset -> pendingScene = scene.sceneIndex; pendingKind = MediaKind.valueOf(asset.mediaType); manualPicker.launch(arrayOf(if (pendingKind == MediaKind.VIDEO) "video/*" else "image/*")) },
                    onReSearch = { asset -> viewModel.searchAgain(projectId, scene.sceneIndex, MediaKind.valueOf(asset.mediaType)) },
                    onAdd = { kind -> pendingScene = scene.sceneIndex; pendingKind = kind; manualPicker.launch(arrayOf(if (kind == MediaKind.VIDEO) "video/*" else "image/*")) },
                    onSearchEmpty = { kind -> viewModel.searchAgain(projectId, scene.sceneIndex, kind) },
                    onRemove = { asset -> viewModel.removeSceneMedia(projectId, scene.sceneIndex, asset.id) },
                    onView = { asset -> openMedia(context, asset) },
                    onYoutube = {
                        referenceLoading = true; references = null; referenceError = null; referenceTitle = scene.visualDescription
                        viewModel.searchYoutube(scene.visualDescription) { result, error ->
                            referenceLoading = false; references = result; referenceError = error
                        }
                    }
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { folderDialog = true }, modifier = Modifier.weight(1f)) { Text("Arquivos do projeto") }
                OutlinedButton(onClick = onCreateAnother, modifier = Modifier.weight(1f)) { Text("Novo projeto") }
            }
        }
    }

    if (searchState.projectId == projectId && (searchState.loading || searchState.error != null || searchState.results.isNotEmpty())) {
        MediaSearchDialog(searchState, onDismiss = viewModel::clearSearch, onSelect = viewModel::selectSearchResult)
    }
    if (cancelDialog) {
        AlertDialog(
            onDismissRequest = { cancelDialog = false },
            title = { Text("Cancelar processamento?") },
            text = { Text("O projeto e os arquivos já baixados serão preservados. Você poderá continuar depois.") },
            confirmButton = { TextButton(onClick = { cancelDialog = false; viewModel.cancelProjectWork(projectId) }) { Text("Salvar progresso e cancelar") } },
            dismissButton = { TextButton(onClick = { cancelDialog = false }) { Text("Voltar") } }
        )
    }
    if (folderDialog) ProjectFolderDialog(current, onDismiss = { folderDialog = false }, onShare = {
        val path = current.outputPath
        if (path != null) shareFile(context, File(path), "video/mp4")
    })
    if (referenceLoading || references != null || referenceError != null) {
        YouTubeReferencesDialog(
            title = referenceTitle,
            loading = referenceLoading,
            references = references.orEmpty(),
            error = referenceError,
            onDismiss = { referenceLoading = false; references = null; referenceError = null },
            onOpen = { openUrl(context, it) }
        )
    }
}

@Composable
private fun ProjectHero(project: ProjectEntity) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF302054), Color(0xFF142E37))))
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(17.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.secondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Narração · ${formatDuration(project.durationMs)} · ${formatBytes(project.narrationSizeBytes)}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
            Text(statusLabel(project.status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun QueueStages(project: ProjectEntity) {
    val stages = listOf("Transcrição", "Análise Gemini", "Microcenas", "Busca e download", "Timeline", "Renderização", "Finalização")
    val stageIndex = when (project.status) {
        "TRANSCRIBING", "WAITING_API" -> 0
        "SCENES_READY" -> 2
        "SEARCHING_MEDIA", "RETRYING" -> 3
        "READY_TO_RENDER", "NEEDS_MEDIA" -> 4
        "RENDERING_PREVIEW", "RENDERING" -> 5
        "COMPLETED" -> 6
        else -> -1
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stages.forEachIndexed { index, label ->
            val done = index < stageIndex || project.status == "COMPLETED"
            val current = index == stageIndex && project.status != "COMPLETED"
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                when {
                    done -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(17.dp))
                    current -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else -> Box(Modifier.size(7.dp).padding(start = 4.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outline))
                }
                Text(label, style = MaterialTheme.typography.bodySmall, color = if (done || current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SceneEditorCard(
    scene: SceneEntity,
    assets: List<MediaAssetEntity>,
    onReplace: (MediaAssetEntity) -> Unit,
    onReSearch: (MediaAssetEntity) -> Unit,
    onAdd: (MediaKind) -> Unit,
    onSearchEmpty: (MediaKind) -> Unit,
    onRemove: (MediaAssetEntity) -> Unit,
    onView: (MediaAssetEntity) -> Unit,
    onYoutube: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("CENA ${(scene.sceneIndex + 1).toString().padStart(3, '0')}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                Text("${formatDuration(scene.startMs)} – ${formatDuration(scene.endMs)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(scene.visualDescription, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (scene.narration.isNotBlank()) Text("“${scene.narration}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (assets.isEmpty()) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Nenhuma mídia selecionada para esta cena.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onSearchEmpty(if (scene.preferredMedia.equals("image", true)) MediaKind.IMAGE else MediaKind.VIDEO) }) { Text("Pesquisar mídia") }
                    TextButton(onClick = { onAdd(MediaKind.VIDEO) }) { Text("＋ Vídeo") }
                    TextButton(onClick = { onAdd(MediaKind.IMAGE) }) { Text("＋ Imagem") }
                }
                TextButton(onClick = onYoutube) { Text("Referência YouTube") }
            } else {
                assets.forEach { asset ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssetThumbnail(asset, Modifier.size(82.dp, 52.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(asset.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${asset.mediaType.lowercase().replaceFirstChar(Char::uppercase)} · ${asset.source} · ${(asset.score * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            if (asset.author.isNotBlank()) Text("Por ${asset.author}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Selecionada", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = { onReplace(asset) }) { Text("Trocar") }
                        TextButton(onClick = { onReSearch(asset) }) { Text("Pesquisar novamente") }
                        TextButton(onClick = { onView(asset) }) { Text("Ver") }
                        TextButton(onClick = { onRemove(asset) }) { Icon(Icons.Filled.DeleteOutline, contentDescription = "Remover") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onAdd(MediaKind.VIDEO) }) { Text("＋ Vídeo") }
                    TextButton(onClick = { onAdd(MediaKind.IMAGE) }) { Text("＋ Imagem") }
                }
                TextButton(onClick = onYoutube) { Text("Pesquisar referência no YouTube") }
            }
        }
    }
}

@Composable
private fun AssetThumbnail(asset: MediaAssetEntity, modifier: Modifier = Modifier) {
    val model: Any = asset.thumbnailUrl?.takeIf(String::isNotBlank) ?: File(asset.localPath)
    Box(modifier.clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (asset.mediaType == MediaKind.IMAGE.name || !asset.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(model = model, contentDescription = asset.title, modifier = Modifier.fillMaxWidth().height(72.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        } else {
            Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        if (asset.mediaType == MediaKind.VIDEO.name) Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).size(17.dp), tint = Color.White)
    }
}

@Composable
private fun MediaSearchDialog(state: SearchUiState, onDismiss: () -> Unit, onSelect: (MediaCandidate) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resultados · ${state.kind?.label ?: "mídia"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                when {
                    state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("Consultando Pexels, Pixabay e a biblioteca local…")
                    }
                    state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                    state.results.isEmpty() -> Text("Nenhum resultado encontrado. Verifique as chaves das APIs ou adicione mídia local.")
                    else -> androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(390.dp)) {
                        items(state.results, key = { it.sourceUrl ?: it.existingLocalPath ?: it.title }) { candidate ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        candidate.previewUrl?.let { AsyncImage(it, candidate.title, modifier = Modifier.size(74.dp, 52.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                                        Column(Modifier.weight(1f)) {
                                            Text(candidate.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text("${candidate.source} · ${candidate.author}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("Relevância ${(candidate.score * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text(candidate.attributionUrl?.let { "Licença / atribuição disponível" } ?: "Arquivo da biblioteca local", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        TextButton(onClick = { onSelect(candidate) }) { Text("Usar") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

@Composable
private fun YouTubeReferencesDialog(
    title: String,
    loading: Boolean,
    references: List<YouTubeReference>,
    error: String?,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Referências · ${title.take(28)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Somente pesquisa. Nenhum vídeo do YouTube é baixado.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    loading -> CircularProgressIndicator()
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                    references.isEmpty() -> Text("Nenhuma referência encontrada.")
                    else -> references.forEach { reference ->
                        TextButton(onClick = { onOpen(reference.watchUrl) }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(reference.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text("${reference.channel} · Abrir no YouTube", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

@Composable
private fun ProjectFolderDialog(project: ProjectEntity, onDismiss: () -> Unit, onShare: () -> Unit) {
    val entries = remember(project.folderPath, project.updatedAt) {
        File(project.folderPath).walkTopDown().filter { it.isFile }.map { it.relativeTo(File(project.folderPath)).path }.take(100).toList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Arquivos do projeto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pasta privada do app no armazenamento local:", style = MaterialTheme.typography.bodySmall)
                Text(project.folderPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                HorizontalDivider()
                if (entries.isEmpty()) Text("Nenhum arquivo criado ainda.") else entries.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        },
        confirmButton = { TextButton(onClick = { onShare(); onDismiss() }, enabled = project.outputPath != null) { Text("Compartilhar MP4") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

@Composable
private fun ExoVideoPreview(path: String) {
    val context = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = true } },
        update = { it.player = player },
        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp))
    )
}

private fun shareFile(context: android.content.Context, file: File, mime: String) {
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Compartilhar com"))
}

private fun openMedia(context: android.content.Context, asset: MediaAssetEntity) {
    val file = File(asset.localPath)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val mime = if (asset.mediaType == MediaKind.VIDEO.name) "video/mp4" else "image/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
}

private fun openUrl(context: android.content.Context, value: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
}

private fun statusLabel(status: String): String = when (status) {
    "QUEUED" -> "Na fila"
    "WAITING_API" -> "Aguardando configuração da Gemini API"
    "TRANSCRIBING" -> "Transcrevendo e analisando narração"
    "SCENES_READY" -> "Microcenas criadas"
    "SEARCHING_MEDIA" -> "Buscando e baixando mídias"
    "RETRYING" -> "Nova tentativa automática"
    "NEEDS_MEDIA" -> "Mídia insuficiente"
    "STORAGE_LOW" -> "Espaço insuficiente"
    "READY_TO_RENDER" -> "Pronto para gerar prévia"
    "RENDERING_PREVIEW" -> "Gerando prévia local"
    "RENDERING" -> "Renderizando no aparelho"
    "COMPLETED" -> "Vídeo concluído"
    "PAUSED" -> "Pausado"
    "FAILED" -> "Processamento interrompido"
    else -> status
}
