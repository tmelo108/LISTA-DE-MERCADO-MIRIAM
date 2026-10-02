package ai.autovideo.app.ui

import ai.autovideo.app.data.local.MediaAssetEntity
import ai.autovideo.app.data.model.ApiProvider
import ai.autovideo.app.data.model.MediaKind
import ai.autovideo.app.data.remote.ApiTestResult
import ai.autovideo.app.util.readUriMetadata
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ApiSettingsScreen(viewModel: MainViewModel, tests: Map<ApiProvider, ApiTestResult>) {
    val context = LocalContext.current
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Conecte somente os serviços que deseja usar.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("As chaves são criptografadas com AES-GCM usando uma chave do Android Keystore. Nunca são incluídas no código-fonte.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        items(ApiProvider.entries, key = { it.name }) { provider ->
            ApiKeyCard(provider, viewModel, tests[provider])
        }
        item {
            InfoCard(
                title = "Limites gratuitos e privacidade",
                body = "As buscas são sequenciais e a fila tenta novamente com backoff. As chaves são enviadas diretamente aos serviços oficiais para autenticação. YouTube é somente pesquisa/referência — o aplicativo não baixa vídeos do YouTube."
            )
        }
    }
}

@Composable
private fun ApiKeyCard(provider: ApiProvider, viewModel: MainViewModel, testResult: ApiTestResult?) {
    val context = LocalContext.current
    var value by remember(provider) { mutableStateOf("") }
    var loaded by remember(provider) { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    LaunchedEffect(provider) {
        viewModel.readApiKey(provider) { stored -> value = stored; loaded = true }
    }
    LaunchedEffect(testResult) { if (testResult != null) testing = false }
    val currentTest = testResult
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(provider.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(provider.helperText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Cole sua ${provider.title} API Key") },
                placeholder = { Text("Chave armazenada somente neste aparelho") },
                singleLine = true,
                enabled = loaded,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Ocultar" else "Ver") }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.saveApiKey(provider, value) }, modifier = Modifier.weight(1f), enabled = loaded) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.size(5.dp)); Text("Salvar")
                }
                OutlinedButton(onClick = {
                    testing = true
                    viewModel.saveApiKey(provider, value) { viewModel.testApi(provider) }
                }, modifier = Modifier.weight(1f), enabled = loaded && value.isNotBlank()) {
                    if (testing && currentTest == null) CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                    else Text("Testar API")
                }
            }
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.officialUrl))) }
            }) {
                Text(if (provider == ApiProvider.YOUTUBE) "CRIAR API KEY" else "CRIAR API KEY")
                Spacer(Modifier.size(5.dp))
                Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
            }
            if (currentTest != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (currentTest.success) Icons.Filled.CheckCircle else Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint = if (currentTest.success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(currentTest.message, color = if (currentTest.success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (provider == ApiProvider.YOUTUBE) {
                Text("O botão abre o Google Cloud. Ative YouTube Data API v3 no projeto antes de testar.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun LibraryScreen(assets: List<MediaAssetEntity>, viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importSelected(context, scope, viewModel, uris, MediaKind.VIDEO) { importing = it; info = "Vídeos indexados na biblioteca." }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importSelected(context, scope, viewModel, uris, MediaKind.IMAGE) { importing = it; info = "Imagens indexadas na biblioteca." }
    }
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item {
            Text("Seu acervo privado", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Arquivos copiados para a área privada do app; ficam disponíveis sem nova busca na nuvem.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(onClick = { importing = true; videoPicker.launch(arrayOf("video/*")) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Movie, contentDescription = null); Spacer(Modifier.size(4.dp)); Text("＋ Vídeos")
                }
                OutlinedButton(onClick = { importing = true; imagePicker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Image, contentDescription = null); Spacer(Modifier.size(4.dp)); Text("＋ Imagens")
                }
            }
            if (importing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
                Text("Copiando e indexando arquivos…", style = MaterialTheme.typography.bodySmall)
            }
            info?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        }
        item {
            InfoCard("Indexação semântica local", "Nesta versão as tags são extraídas do nome do arquivo. A descrição automática do conteúdo por IA ainda não está implementada; revise nomes como ‘homem_cidade_noite.mp4’ para melhorar a busca por tags.")
        }
        item { Text("${assets.size} arquivo(s)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        if (assets.isEmpty()) item { EmptyState("Adicione vídeos ou imagens do aparelho para usar como fonte prioritária nos projetos.") }
        items(assets, key = { it.id }) { asset ->
            LibraryAssetCard(asset, onRemove = { viewModel.removeLibraryAsset(asset) })
        }
    }
}

@Composable
private fun LibraryAssetCard(asset: MediaAssetEntity, onRemove: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(17.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
            if (asset.mediaType == MediaKind.IMAGE.name || !asset.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = if (asset.mediaType == MediaKind.IMAGE.name) File(asset.localPath) else asset.thumbnailUrl,
                    contentDescription = asset.title,
                    modifier = Modifier.size(70.dp, 54.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Icon(Icons.Filled.VideoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(asset.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${asset.mediaType.lowercase()} · ${asset.width}×${asset.height}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(asset.tags.ifBlank { "Sem tags no nome" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 2)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.DeleteOutline, contentDescription = "Remover") }
        }
    }
}

private fun importSelected(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    viewModel: MainViewModel,
    uris: List<Uri>,
    kind: MediaKind,
    onProgress: (Boolean) -> Unit
) {
    if (uris.isEmpty()) { onProgress(false); return }
    scope.launch {
        onProgress(true)
        for (uri in uris) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val metadata = withContext(Dispatchers.IO) { runCatching { readUriMetadata(context, uri) }.getOrNull() }
            viewModel.addToLibrary(uri, kind, metadata?.displayName ?: "${kind.name.lowercase()}-${System.currentTimeMillis()}")
        }
        onProgress(false)
    }
}
