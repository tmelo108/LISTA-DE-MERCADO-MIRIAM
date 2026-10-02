package ai.autovideo.app.data

import ai.autovideo.app.data.local.MediaAssetEntity
import ai.autovideo.app.data.local.ProjectDao
import ai.autovideo.app.data.model.MediaKind
import ai.autovideo.app.data.remote.LOCAL_LIBRARY_ID
import ai.autovideo.app.data.remote.MediaCandidate
import ai.autovideo.app.util.LIBRARY_DIRECTORY
import ai.autovideo.app.util.copyUriToFile
import ai.autovideo.app.util.safeFileName
import android.content.Context
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.net.Uri
import android.os.StatFs
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class MediaFileRepository(
    private val context: Context,
    private val dao: ProjectDao,
    private val httpClient: OkHttpClient
) {
    suspend fun downloadToProject(
        candidate: MediaCandidate,
        projectId: String,
        projectFolderPath: String,
        slotIndex: Int,
        sceneIndex: Int,
        timelineStartMs: Long,
        timelineEndMs: Long
    ): MediaAssetEntity = withContext(Dispatchers.IO) {
        val mediaDir = File(projectFolderPath, "media").apply { mkdirs() }
        val extension = extensionFor(candidate)
        val cacheKey = sha256(listOf(candidate.sourceUrl, candidate.downloadUrl, candidate.existingLocalPath)
            .filterNotNull().joinToString("|"))
        val cacheDir = File(context.filesDir, "AutoVideoAI/Cache").apply { mkdirs() }
        val cached = File(cacheDir, "$cacheKey.$extension")
        if (candidate.existingLocalPath != null) {
            val source = File(candidate.existingLocalPath)
            if (!source.exists()) throw IllegalStateException("O arquivo da biblioteca não está mais disponível.")
            if (!cached.exists() || cached.length() == 0L) source.copyTo(cached, overwrite = true)
        } else {
            val downloadUrl = candidate.downloadUrl ?: throw IllegalStateException("O resultado não fornece um arquivo para download.")
            if (!cached.exists() || cached.length() == 0L) streamDownload(downloadUrl, cached)
        }
        val local = File(mediaDir, "take_${slotIndex.toString().padStart(3, '0')}_${cacheKey.take(10)}.$extension")
        if (!local.exists() || local.length() == 0L) cached.copyTo(local, overwrite = true)
        val dimensions = dimensions(local, candidate.mediaType)
        val thumbnail = if (candidate.mediaType == MediaKind.VIDEO) createVideoThumbnail(local, File(projectFolderPath, "thumbnails"), "slot_$slotIndex") else null
        val asset = MediaAssetEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            sceneIndex = sceneIndex,
            slotIndex = slotIndex,
            timelineStartMs = timelineStartMs,
            timelineEndMs = timelineEndMs,
            mediaType = candidate.mediaType.name,
            title = candidate.title,
            description = candidate.description,
            tags = candidate.tags,
            source = candidate.source,
            sourceUrl = candidate.sourceUrl,
            localPath = local.absolutePath,
            thumbnailUrl = thumbnail?.let(Uri::fromFile)?.toString() ?: candidate.previewUrl,
            durationMs = dimensions.first,
            width = dimensions.second,
            height = dimensions.third,
            score = candidate.score,
            author = candidate.author,
            attributionUrl = candidate.attributionUrl
        )
        dao.upsertAsset(asset)
        asset
    }

    suspend fun addToLibrary(uri: Uri, kind: MediaKind, displayName: String): MediaAssetEntity = withContext(Dispatchers.IO) {
        val folder = File(context.filesDir, LIBRARY_DIRECTORY).apply { mkdirs() }
        val safe = safeFileName(displayName)
        val originalExtension = safe.substringAfterLast('.', "").lowercase()
        val hasExtension = safe.lastIndexOf('.') > 0 && originalExtension.length in 1..8
        val extension = if (hasExtension) originalExtension else if (kind == MediaKind.VIDEO) "mp4" else "jpg"
        val storedName = safe.take(64).let { if (hasExtension) it else "$it.$extension" }
        val file = File(folder, "${UUID.randomUUID()}-$storedName")
        copyUriToFile(context.contentResolver, uri, file)
        val (duration, width, height) = dimensions(file, kind)
        val thumbnail = if (kind == MediaKind.VIDEO) createVideoThumbnail(file, File(folder, "thumbnails"), file.nameWithoutExtension) else null
        val nameWithoutExtension = safe.substringBeforeLast('.', safe)
        val tags = nameWithoutExtension
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 }
            .distinct().joinToString(", ")
        val asset = MediaAssetEntity(
            id = UUID.randomUUID().toString(),
            projectId = LOCAL_LIBRARY_ID,
            sceneIndex = -1,
            slotIndex = -1,
            timelineStartMs = 0,
            timelineEndMs = duration,
            mediaType = kind.name,
            title = nameWithoutExtension,
            description = tags,
            tags = tags,
            source = "Biblioteca local",
            sourceUrl = null,
            localPath = file.absolutePath,
            thumbnailUrl = thumbnail?.let(Uri::fromFile)?.toString(),
            durationMs = duration,
            width = width,
            height = height,
            score = 0f,
            author = "Seu arquivo",
            attributionUrl = null,
            isLocalLibraryItem = true
        )
        dao.upsertAsset(asset)
        asset
    }

    suspend fun importManualIntoProject(
        uri: Uri,
        kind: MediaKind,
        projectId: String,
        projectFolderPath: String,
        sceneIndex: Int,
        slotIndex: Int,
        timelineStartMs: Long,
        timelineEndMs: Long
    ): MediaAssetEntity = withContext(Dispatchers.IO) {
        val folder = File(projectFolderPath, "media").apply { mkdirs() }
        val file = File(folder, "manual_${sceneIndex.toString().padStart(3, '0')}_${UUID.randomUUID()}.${if (kind == MediaKind.VIDEO) "mp4" else "jpg"}")
        copyUriToFile(context.contentResolver, uri, file)
        val (duration, width, height) = dimensions(file, kind)
        val thumbnail = if (kind == MediaKind.VIDEO) createVideoThumbnail(file, File(projectFolderPath, "thumbnails"), "manual_$slotIndex") else null
        val title = file.nameWithoutExtension
        val asset = MediaAssetEntity(
            id = UUID.randomUUID().toString(), projectId = projectId, sceneIndex = sceneIndex,
            slotIndex = slotIndex, timelineStartMs = timelineStartMs, timelineEndMs = timelineEndMs,
            mediaType = kind.name, title = title, description = "Mídia adicionada manualmente",
            tags = title.replace('_', ' '), source = "Arquivo local", sourceUrl = null,
            localPath = file.absolutePath, thumbnailUrl = thumbnail?.let(Uri::fromFile)?.toString(), durationMs = duration,
            width = width, height = height, score = 1f, author = "Você", attributionUrl = null
        )
        dao.upsertAsset(asset)
        asset
    }

    private fun streamDownload(url: String, destination: File) {
        val stats = StatFs(context.filesDir.absolutePath)
        if (stats.availableBytes < 20L * 1024 * 1024) throw IllegalStateException("Há menos de 20 MB livres para baixar mídia.")
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        val request = Request.Builder().url(url).header("User-Agent", "AutoVideoAI-Android/1.0").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Download respondeu ${response.code}.")
            val body = response.body ?: throw java.io.IOException("Download sem conteúdo.")
            body.byteStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 1_500L * 1024 * 1024) throw java.io.IOException("O arquivo ultrapassa o limite de 1,5 GB por mídia.")
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
        }
        if (!temporary.exists() || temporary.length() == 0L) {
            temporary.delete()
            throw java.io.IOException("O download terminou sem arquivo válido.")
        }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    private fun createVideoThumbnail(source: File, destinationDir: File, key: String): File? = runCatching {
        destinationDir.mkdirs()
        val output = File(destinationDir, "${safeFileName("frame_$key")}.jpg")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L)
            if (frame == null) return@runCatching null
            val maxEdge = maxOf(frame.width, frame.height)
            val scale = minOf(1f, 720f / maxEdge.coerceAtLeast(1))
            val thumbnail = if (scale < 1f) Bitmap.createScaledBitmap(
                frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true
            ) else frame
            try {
                val saved = FileOutputStream(output).use { thumbnail.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                if (!saved) output.delete()
                output.takeIf { saved && it.length() > 0L }
            } finally {
                if (thumbnail !== frame) thumbnail.recycle()
                frame.recycle()
            }
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun dimensions(file: File, kind: MediaKind): Triple<Long, Int, Int> {
        if (kind == MediaKind.IMAGE) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            return Triple(0L, options.outWidth.coerceAtLeast(0), options.outHeight.coerceAtLeast(0))
        }
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                Triple(duration, width, height)
            } finally {
                retriever.release()
            }
        }.getOrDefault(Triple(0L, 0, 0))
    }

    private fun extensionFor(candidate: MediaCandidate): String {
        val pathExtension = runCatching {
            Uri.parse(candidate.downloadUrl ?: candidate.existingLocalPath).lastPathSegment?.substringAfterLast('.', "")
        }.getOrNull()
        val valid = setOf("mp4", "mov", "webm", "jpg", "jpeg", "png", "webp")
        val chosen = pathExtension?.lowercase()?.takeIf { it in valid }
        return chosen ?: if (candidate.mediaType == MediaKind.VIDEO) "mp4" else "jpg"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
