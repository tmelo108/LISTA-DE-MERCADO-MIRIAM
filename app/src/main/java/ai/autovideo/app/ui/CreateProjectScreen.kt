package ai.autovideo.app.ui

import ai.autovideo.app.data.local.PresetEntity
import ai.autovideo.app.data.model.AspectRatio
import ai.autovideo.app.data.model.DistributionMode
import ai.autovideo.app.data.model.OutputResolution
import ai.autovideo.app.data.model.ProjectSettings
import ai.autovideo.app.data.model.TakeDurationMode
import ai.autovideo.app.data.model.VideoCodec
import ai.autovideo.app.data.model.VisualStyle
import ai.autovideo.app.util.UriMediaMetadata
import ai.autovideo.app.util.estimatedStorageBytes
import ai.autovideo.app.util.formatBytes
import ai.autovideo.app.util.formatDuration
import ai.autovideo.app.util.readUriMetadata
import android.net.Uri
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun CreateProjectScreen(
    viewModel: MainViewModel,
    presets: List<PresetEntity>,
    onBack: () -> Unit,
    onCreated: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var audioMetadata by remember { mutableStateOf<UriMediaMetadata?>(null) }
    var settings by remember { mutableStateOf(ProjectSettings.storytelling()) }
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var showPresetDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("Meu preset") }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                picking = true
                val metadata = withContext(Dispatchers.IO) { readUriMetadata(context, uri) }
                val extension = metadata.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val accepted = extension in setOf("mp3", "wav", "m4a", "aac") ||
                    metadata.mimeType in setOf("audio/mpeg", "audio/wav", "audio/x-wav", "audio/mp4", "audio/aac", "audio/x-aac")
                if (!accepted) {
                    localError = "Formato não suportado. Selecione MP3, WAV, M4A ou AAC."
                } else {
                    audioUri = uri
                    audioMetadata = metadata
                    localError = null
                }
                picking = false
            }
        }
    }

    val estimate = estimatedStorageBytes(audioMetadata?.sizeBytes ?: 0L, settings.videoCount, settings.imageCount)
    val freeSpace = remember(settings.videoCount, settings.imageCount, audioMetadata) {
        runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
    }
    val enoughSpace = freeSpace >= estimate

    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Transforme uma voz em uma história visual.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("O processamento é feito em etapas e o vídeo final é renderizado localmente.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SettingsPanel("01  ·  Projeto") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("Nome do projeto") },
                    placeholder = { Text("Ex.: Uma manhã diferente") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (audioMetadata == null) {
                    PrimaryButton(
                        text = if (picking) "Lendo arquivo…" else "SELECIONAR NARRAÇÃO",
                        onClick = { audioPicker.launch(arrayOf("audio/mpeg", "audio/wav", "audio/x-wav", "audio/mp4", "audio/aac", "audio/*")) },
                        enabled = !picking
                    )
                } else {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))) {
                        Row(Modifier.fillMaxWidth().padding(13.dp), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AudioFile, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Column(Modifier.weight(1f)) {
                                Text(audioMetadata!!.displayName, maxLines = 1, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("${formatDuration(audioMetadata!!.durationMs)}  ·  ${formatBytes(audioMetadata!!.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { audioUri = null; audioMetadata = null }) { Text("Trocar") }
                        }
                    }
                }
                if (localError != null) Text(localError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("MP3 · WAV · M4A · AAC", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsPanel("02  ·  Quantidade de mídia", "As quantidades são limites exatos de arquivos distintos que a busca tenta encontrar.") {
                QuantityControl("Vídeos", settings.videoCount, "movimento e ação") { settings = settings.copy(videoCount = it) }
                QuantityControl("Imagens", settings.imageCount, "respiros e cenas contemplativas") { settings = settings.copy(imageCount = it) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Total solicitado", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${settings.videoCount + settings.imageCount} mídias", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                }
                Text("Distribuição", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ChoiceChips(DistributionMode.entries, settings.distributionMode, { it.label }) { settings = settings.copy(distributionMode = it) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Permitir repetição", fontWeight = FontWeight.Medium)
                        Text("Tenta usar a quantidade inteira mesmo com poucos resultados únicos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.allowRepeat, onCheckedChange = { settings = settings.copy(allowRepeat = it) })
                }
            }
        }
        item {
            SettingsPanel("03  ·  Formato do vídeo") {
                Text("Proporção", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ChoiceChips(AspectRatio.entries, settings.aspectRatio, { it.label }) { settings = settings.copy(aspectRatio = it) }
                Text("Resolução", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ChoiceChips(OutputResolution.entries, settings.resolution, { it.label }) { settings = settings.copy(resolution = it) }
                Text("FPS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ChoiceChips(listOf(24, 30, 60), settings.fps, { "$it" }) { settings = settings.copy(fps = it) }
                Text("Codec", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ChoiceChips(VideoCodec.entries, settings.codec, { it.label }) { settings = settings.copy(codec = it) }
                Text("H.265 será solicitado quando escolhido; o aparelho precisa oferecer um encoder compatível.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsPanel("04  ·  Ritmo e estilo") {
                Text("Duração dos takes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ChoiceChips(TakeDurationMode.entries, settings.takeMode, { it.label }) { settings = settings.copy(takeMode = it) }
                if (settings.takeMode == TakeDurationMode.CUSTOM) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberInput("Mínimo (s)", settings.customTakeMinSeconds, Modifier.weight(1f)) { settings = settings.copy(customTakeMinSeconds = it.coerceIn(1, 60)) }
                        NumberInput("Máximo (s)", settings.customTakeMaxSeconds, Modifier.weight(1f)) { settings = settings.copy(customTakeMaxSeconds = it.coerceIn(settings.customTakeMinSeconds, 90)) }
                    }
                }
                Text("Estilo visual", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ChoiceChips(VisualStyle.entries, settings.visualStyle, { it.label }) { settings = settings.copy(visualStyle = it) }
                Text("O estilo é incluído nas consultas de Pexels e Pixabay.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsPanel("05  ·  Movimento") {
                ToggleLine("Ken Burns nas imagens", "Zoom e pan suave aplicado às imagens durante a exportação.", settings.kenBurnsEnabled) {
                    settings = settings.copy(kenBurnsEnabled = it)
                }
                if (settings.kenBurnsEnabled) {
                    Text("Intensidade  ${settings.kenBurnsIntensity}%", style = MaterialTheme.typography.labelLarge)
                    Slider(value = settings.kenBurnsIntensity.toFloat(), onValueChange = { settings = settings.copy(kenBurnsIntensity = it.toInt()) }, valueRange = 0f..100f)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ToggleLine("Pequeno movimento cinematográfico", "Movimento discreto também nos vídeos.", settings.cinematicVideoMotion) {
                    settings = settings.copy(cinematicVideoMotion = it)
                }
                Text("Intensidade dos vídeos  ${settings.videoMotionIntensity}%", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(listOf(0, 10, 20, 30, 40, 50), settings.videoMotionIntensity, { "$it%" }) { settings = settings.copy(videoMotionIntensity = it) }
            }
        }
        item {
            SettingsPanel("06  ·  Áudio, transições e legendas") {
                Text("Volume da narração  ${settings.narrationVolume}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Slider(value = settings.narrationVolume.toFloat(), onValueChange = { settings = settings.copy(narrationVolume = it.toInt()) }, valueRange = 0f..100f)
                UnsupportedOption("Música de fundo", "Seleção e mixagem de música local ainda não estão implementadas nesta versão.")
                UnsupportedOption("Transições", "A exportação atual usa cortes secos. Crossfade de vídeo entre sequências não é suportado pelo Composition usado nesta versão do Media3.")
                Text("Legendas SRT são geradas junto com a transcrição.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                UnsupportedOption("Texto queimado no vídeo", "A exportação de SRT separado funciona; renderizar texto sobre a imagem ainda não está implementado.")
            }
        }
        item {
            SettingsPanel("07  ·  Preset") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = false, onClick = { settings = ProjectSettings.storytelling() }, label = { Text("Storytelling") })
                    presets.forEach { preset ->
                        FilterChip(selected = false, onClick = { settings = viewModel.decodeSettings(preset.settingsJson) }, label = { Text(preset.name) })
                    }
                }
                TextButton(onClick = { showPresetDialog = true }) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Salvar configuração como preset")
                }
            }
        }
        item {
            StorageEstimateCard(estimate, freeSpace, enoughSpace)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).height(54.dp)) { Text("Cancelar") }
                androidx.compose.material3.Button(
                    onClick = {
                        val uri = audioUri ?: return@Button
                        if (settings.videoCount + settings.imageCount < 1) {
                            localError = "Escolha pelo menos uma mídia."
                            return@Button
                        }
                        saving = true
                        val file = audioMetadata
                        viewModel.createProject(
                            name = name.trim().ifBlank { "Meu vídeo" },
                            audioUri = uri,
                            audioName = file?.displayName ?: "narracao.mp3",
                            audioSize = file?.sizeBytes ?: 0L,
                            durationMs = file?.durationMs ?: 0L,
                            settings = settings
                        ) { id -> saving = false; onCreated(id) }
                    },
                    enabled = audioUri != null && !saving && enoughSpace,
                    modifier = Modifier.weight(1.5f).height(54.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                ) {
                    if (saving) CircularProgressIndicator(modifier = Modifier.padding(end = 9.dp), strokeWidth = 2.dp)
                    Text(if (saving) "Criando…" else "CRIAR E PROCESSAR", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showPresetDialog) {
        AlertDialog(
            onDismissRequest = { showPresetDialog = false },
            title = { Text("Salvar preset") },
            text = {
                OutlinedTextField(value = presetName, onValueChange = { presetName = it }, label = { Text("Nome") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { viewModel.savePreset(presetName, settings); showPresetDialog = false }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { showPresetDialog = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun SettingsPanel(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            content()
        }
    }
}

@Composable
private fun QuantityControl(title: String, value: Int, helper: String, onValueChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(helper, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.IconButton(onClick = { onValueChange((value - 1).coerceAtLeast(0)) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
            NumberInput("Qtd.", value, Modifier.width(82.dp), onValueChange)
            androidx.compose.material3.IconButton(onClick = { onValueChange((value + 1).coerceAtMost(200)) }) { Text("＋", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun <T> ChoiceChips(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEach { item ->
            FilterChip(selected = item == selected, onClick = { onSelect(item) }, label = { Text(label(item)) })
        }
    }
}

@Composable
private fun NumberInput(label: String, value: Int, modifier: Modifier = Modifier, onValueChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { raw -> raw.filter(Char::isDigit).take(3).toIntOrNull()?.let(onValueChange) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun ToggleLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun UnsupportedOption(title: String, explanation: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f))
        }
    }
}

@Composable
private fun StorageEstimateCard(estimate: Long, available: Long, enough: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Armazenamento estimado", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Necessário: ${formatBytes(estimate)}   ·   Disponível: ${formatBytes(available)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (enough) "✓ Espaço suficiente" else "⚠ Espaço insuficiente — reduza a quantidade de mídias ou libere espaço.", color = if (enough) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            Text("Estimativa aproximada. O tamanho final depende dos downloads e da codificação local.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
