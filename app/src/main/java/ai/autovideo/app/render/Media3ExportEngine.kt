package ai.autovideo.app.render

import ai.autovideo.app.data.local.MediaAssetEntity
import ai.autovideo.app.data.local.ProjectEntity
import ai.autovideo.app.data.model.AspectRatio
import ai.autovideo.app.data.model.MediaKind
import ai.autovideo.app.data.model.ProjectSettings
import ai.autovideo.app.data.model.VideoCodec
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.MediaCodecList
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@UnstableApi
class Media3ExportEngine(private val context: Context) {
    suspend fun export(
        project: ProjectEntity,
        settings: ProjectSettings,
        assets: List<MediaAssetEntity>,
        output: File,
        preview: Boolean,
        onProgress: suspend (Int) -> Unit
    ) {
        require(assets.isNotEmpty()) { "Ainda não há mídias locais para renderizar." }
        val previewLimitMs = if (preview) 20_000L else project.durationMs.coerceAtLeast(1_000L)
        val selected = assets.filter { it.timelineStartMs < previewLimitMs }
            .sortedWith(compareBy<MediaAssetEntity> { it.timelineStartMs }.thenBy { it.slotIndex })
        require(selected.isNotEmpty()) { "Não há mídia no trecho de prévia." }

        val outputSize = outputSize(settings.aspectRatio, settings.resolution.shortEdge)
        val presentation = Presentation.createForWidthAndHeight(
            outputSize.first,
            outputSize.second,
            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )
        val editedVideoItems = ArrayList<EditedMediaItem>()
        for ((index, asset) in selected.withIndex()) {
            val segmentEnd = min(asset.timelineEndMs, previewLimitMs)
            val segmentDurationMs = (segmentEnd - asset.timelineStartMs).coerceAtLeast(1L)
            val file = File(asset.localPath)
            require(file.exists() && file.length() > 0) { "A mídia ${asset.title} não está mais disponível no armazenamento." }
            val isImage = asset.mediaType == MediaKind.IMAGE.name
            val animatedMotion = if (isImage) settings.kenBurnsEnabled else
                settings.cinematicVideoMotion && (settings.smoothVideoZoom || settings.smoothVideoPan)
            val intensity = if (isImage) settings.kenBurnsIntensity else settings.videoMotionIntensity
            val effects = buildList {
                add(presentation)
                if (animatedMotion && intensity > 0) {
                    add(createMotionEffect(segmentDurationMs * 1_000L, intensity, index))
                }
            }

            if (isImage) {
                val imageItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(file))
                    .setImageDurationMs(segmentDurationMs)
                    .build()
                editedVideoItems += EditedMediaItem.Builder(imageItem)
                    .setFrameRate(settings.fps)
                    .setEffects(Effects(emptyList(), effects))
                    .build()
            } else {
                val actualDuration = asset.durationMs.takeIf { it > 0 } ?: segmentDurationMs
                val movingDuration = min(segmentDurationMs, actualDuration).coerceAtLeast(1L)
                val videoMediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(file))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0)
                            .setEndPositionMs(movingDuration)
                            .build()
                    )
                    .build()
                editedVideoItems += EditedMediaItem.Builder(videoMediaItem)
                    .setRemoveAudio(true)
                    .setFrameRate(settings.fps)
                    .setEffects(Effects(emptyList(), effects))
                    .build()

                // Keep the narration covered when an API video is shorter than its allocated timeline slot.
                // The hold frame is derived locally from the downloaded take; it is not an additional searched asset.
                if (segmentDurationMs > movingDuration) {
                    val hold = extractHoldFrame(file, File(project.folderPath, "thumbnails/hold_${asset.slotIndex}_${asset.id.take(8)}.jpg"), movingDuration)
                    val holdDuration = segmentDurationMs - movingDuration
                    val holdItem = MediaItem.Builder()
                        .setUri(Uri.fromFile(hold))
                        .setImageDurationMs(holdDuration)
                        .build()
                    editedVideoItems += EditedMediaItem.Builder(holdItem)
                        .setFrameRate(settings.fps)
                        .setEffects(Effects(emptyList(), effects))
                        .build()
                }
            }
        }

        val audioFile = File(project.narrationPath)
        require(audioFile.exists()) { "O arquivo de narração do projeto não foi encontrado." }
        val audioEnd = if (preview) min(previewLimitMs, project.durationMs.coerceAtLeast(previewLimitMs)) else project.durationMs
        val narrationItem = MediaItem.Builder()
            .setUri(Uri.fromFile(audioFile))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(0)
                    .setEndPositionMs(audioEnd.coerceAtLeast(1L))
                    .build()
            )
            .build()
        val gain = (settings.narrationVolume.coerceIn(0, 100) / 100f)
        val narration = EditedMediaItem.Builder(narrationItem)
            .setRemoveVideo(true)
            .setEffects(Effects(listOf(LinearGainAudioProcessor(gain)), emptyList()))
            .build()

        val videoSequence = EditedMediaItemSequence.Builder(editedVideoItems.toTypedArray()).build()
        val narrationSequence = EditedMediaItemSequence.Builder(arrayOf(narration)).build()
        val composition = Composition.Builder(videoSequence, narrationSequence).build()
        output.parentFile?.mkdirs()
        output.delete()
        val requestedMime = if (settings.codec == VideoCodec.H265 && supportsEncoder(MimeTypes.VIDEO_H265)) {
            MimeTypes.VIDEO_H265
        } else {
            MimeTypes.VIDEO_H264
        }
        try {
            transform(composition, output, requestedMime, onProgress)
        } catch (error: Exception) {
            if (requestedMime != MimeTypes.VIDEO_H265 || error is kotlinx.coroutines.CancellationException) throw error
            output.delete()
            transform(composition, output, MimeTypes.VIDEO_H264, onProgress)
        }
    }

    private fun supportsEncoder(mimeType: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
    }.getOrDefault(false)

    private suspend fun transform(
        composition: Composition,
        output: File,
        videoMimeType: String,
        onProgress: suspend (Int) -> Unit
    ) {
        val completed = CompletableDeferred<ExportException?>()
        lateinit var transformer: Transformer
        withContext(Dispatchers.Main.immediate) {
            transformer = Transformer.Builder(context)
                .setVideoMimeType(videoMimeType)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (!completed.isCompleted) completed.complete(null)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (!completed.isCompleted) completed.complete(exportException)
                    }
                })
                .build()
            transformer.start(composition, output.absolutePath)
        }
        try {
            val holder = ProgressHolder()
            while (!completed.isCompleted) {
                currentCoroutineContext().ensureActive()
                val percent = withContext(Dispatchers.Main.immediate) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else 0
                }
                onProgress(percent.coerceIn(0, 99))
                delay(700L)
            }
            completed.await()?.let { throw IllegalStateException("Media3 não conseguiu exportar o vídeo: ${it.message}", it) }
            if (!output.exists() || output.length() == 0L) throw IllegalStateException("A exportação terminou sem gerar o MP4.")
            onProgress(100)
        } catch (error: Throwable) {
            withContext(Dispatchers.Main.immediate) { runCatching { transformer.cancel() } }
            output.delete()
            throw error
        }
    }

    private fun createMotionEffect(durationUs: Long, intensityPercent: Int, index: Int): MatrixTransformation {
        val movement = (intensityPercent.coerceIn(0, 100) / 100f)
        val zoomDistance = 0.10f * movement
        val panDistance = 0.10f * movement
        val reverse = index % 2 == 1
        return MatrixTransformation { presentationTimeUs ->
            val progress = (presentationTimeUs.toFloat() / durationUs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            val normalized = if (reverse) 1f - progress else progress
            val startScale = if (reverse) 1f + zoomDistance else 1f
            val endScale = if (reverse) 1f else 1f + zoomDistance
            val scale = startScale + (endScale - startScale) * normalized
            val pan = (normalized - 0.5f) * panDistance * if (index % 3 == 0) 1f else -1f
            Matrix().apply {
                postScale(scale, scale)
                postTranslate(pan, if (index % 2 == 0) -pan else pan)
            }
        }
    }

    private fun extractHoldFrame(source: File, output: File, atMs: Long): File {
        if (output.exists() && output.length() > 0) return output
        output.parentFile?.mkdirs()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val bitmap = retriever.getFrameAtTime((atMs.coerceAtLeast(1) * 1_000).coerceAtLeast(1), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IllegalStateException("Não foi possível obter um quadro do vídeo ${source.name}.")
            FileOutputStream(output).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                stream.fd.sync()
            }
            bitmap.recycle()
        } finally {
            retriever.release()
        }
        return output
    }

    private fun outputSize(aspect: AspectRatio, shortEdge: Int): Pair<Int, Int> = when (aspect) {
        AspectRatio.LANDSCAPE -> if (shortEdge >= 1080) 1920 to 1080 else 1280 to 720
        AspectRatio.PORTRAIT -> if (shortEdge >= 1080) 1080 to 1920 else 720 to 1280
        AspectRatio.SQUARE -> if (shortEdge >= 1080) 1080 to 1080 else 720 to 720
    }
}

@UnstableApi
private class LinearGainAudioProcessor(private val gain: Float) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val encoding = inputAudioFormat.encoding
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val output = replaceOutputBuffer(input.remaining())
        if (encoding == C.ENCODING_PCM_FLOAT) {
            while (input.remaining() >= Float.SIZE_BYTES) {
                output.putFloat((input.getFloat() * gain).coerceIn(-1f, 1f))
            }
        } else {
            while (input.remaining() >= Short.SIZE_BYTES) {
                val sample = input.getShort() * gain
                output.putShort(sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        output.flip()
    }
}
