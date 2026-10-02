package ai.autovideo.app.util

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

const val PROJECTS_DIRECTORY = "AutoVideoAI/Projetos"
const val LIBRARY_DIRECTORY = "AutoVideoAI/Biblioteca"

data class UriMediaMetadata(
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String? = null
)

fun readUriMetadata(context: Context, uri: Uri): UriMediaMetadata {
    val resolver = context.contentResolver
    var name = "arquivo"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
        }
    }
    val mime = resolver.getType(uri)
    var duration = 0L
    var width = 0
    var height = 0
    val isImage = mime?.startsWith("image/") == true || name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif")
    if (isImage) {
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, options)
                width = options.outWidth.coerceAtLeast(0)
                height = options.outHeight.coerceAtLeast(0)
            }
        }
    } else {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            } finally {
                retriever.release()
            }
        }
    }
    return UriMediaMetadata(name, size, duration, width, height, mime)
}

fun copyUriToFile(resolver: ContentResolver, uri: Uri, destination: File): Long {
    destination.parentFile?.mkdirs()
    val temp = File(destination.parentFile, destination.name + ".part")
    resolver.openInputStream(uri)?.use { input ->
        FileOutputStream(temp).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
            }
            output.fd.sync()
            if (total == 0L) throw IllegalStateException("O arquivo selecionado está vazio.")
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
            return total
        }
    } ?: throw IllegalStateException("Não foi possível abrir o arquivo selecionado.")
}

fun safeFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        .trim().trim('.')
    return base.take(90).ifBlank { "arquivo-${System.currentTimeMillis()}" }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var index = 0
    while (size >= 1024 && index < units.lastIndex) {
        size /= 1024
        index++
    }
    return String.format(Locale.getDefault(), if (index == 0) "%.0f %s" else "%.1f %s", size, units[index])
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

fun estimatedStorageBytes(audioBytes: Long, videos: Int, images: Int): Long =
    (audioBytes.coerceAtLeast(0) * 2L) + videos.coerceAtLeast(0) * 18L * 1024 * 1024 +
        images.coerceAtLeast(0) * 5L * 1024 * 1024 + 80L * 1024 * 1024

fun estimatedExportBytes(durationMs: Long, shortEdge: Int, fps: Int): Long {
    val bitrate = (if (shortEdge >= 1080) 8_000_000L else 3_500_000L) *
        (if (fps >= 60) 14L else if (fps <= 24) 9L else 10L) / 10L
    return (durationMs.coerceAtLeast(0) * bitrate / 8_000L * 12L / 10L) + 64L * 1024 * 1024
}
