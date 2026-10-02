package ai.autovideo.app.data.remote

import ai.autovideo.app.data.local.ProjectDao
import ai.autovideo.app.data.model.ApiProvider
import ai.autovideo.app.data.model.AspectRatio
import ai.autovideo.app.data.model.MediaKind
import ai.autovideo.app.data.model.ProjectSettings
import ai.autovideo.app.security.SecureKeyStore
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

const val LOCAL_LIBRARY_ID = "__local_library__"

data class MediaCandidate(
    val mediaType: MediaKind,
    val title: String,
    val description: String,
    val tags: String,
    val source: String,
    val sourceUrl: String?,
    val downloadUrl: String?,
    val previewUrl: String?,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val score: Float,
    val author: String,
    val attributionUrl: String?,
    val existingLocalPath: String? = null
)

data class YouTubeReference(val title: String, val channel: String, val thumbnailUrl: String?, val watchUrl: String)

data class ApiTestResult(val success: Boolean, val message: String)

class ApiRepository(
    private val services: ApiServices,
    private val secureKeyStore: SecureKeyStore,
    private val mediaSearchRepository: MediaSearchRepository
) {
    fun getKey(provider: ApiProvider): String? = secureKeyStore.get(provider.name)
    fun saveKey(provider: ApiProvider, key: String) = secureKeyStore.put(provider.name, key)

    suspend fun test(provider: ApiProvider): ApiTestResult {
        val key = getKey(provider)?.takeIf(String::isNotBlank)
            ?: return ApiTestResult(false, "Adicione uma chave antes de testar.")
        return try {
            val message = when (provider) {
                ApiProvider.GEMINI -> mediaSearchRepository.testGemini(key)
                ApiProvider.PEXELS -> {
                    val response = services.pexelsPhotos.search(key, "nature", perPage = 1)
                    requireSuccess(response.code(), response.isSuccessful, response.errorBody()?.string())
                    "Pexels conectado."
                }
                ApiProvider.PIXABAY -> {
                    val response = services.pixabayPhotos.search(key, "nature", perPage = 1)
                    requireSuccess(response.code(), response.isSuccessful, response.errorBody()?.string())
                    "Pixabay conectado."
                }
                ApiProvider.YOUTUBE -> {
                    val response = services.youtube.search(query = "nature", maxResults = 1, apiKey = key)
                    requireSuccess(response.code(), response.isSuccessful, response.errorBody()?.string())
                    "YouTube Data API v3 conectada."
                }
            }
            ApiTestResult(true, message)
        } catch (error: Exception) {
            ApiTestResult(false, error.message ?: "Não foi possível validar a chave.")
        }
    }

    suspend fun searchYoutubeReferences(query: String): List<YouTubeReference> {
        val key = getKey(ApiProvider.YOUTUBE) ?: throw IOException("Configure a chave do YouTube Data API v3 em APIs e Integrações.")
        val response = services.youtube.search(query = query, maxResults = 5, apiKey = key)
        if (!response.isSuccessful) throw IOException("YouTube respondeu ${response.code()}: ${response.errorBody()?.string()?.take(250)}")
        return response.body()?.get("items")?.arrayOrEmpty()?.mapNotNull { item ->
            val obj = item.objectOrNull() ?: return@mapNotNull null
            val snippet = obj["snippet"].objectOrNull() ?: return@mapNotNull null
            val id = obj["id"].objectOrNull()?.get("videoId")?.textOrNull() ?: return@mapNotNull null
            YouTubeReference(
                title = snippet["title"].textOrNull().orEmpty(),
                channel = snippet["channelTitle"].textOrNull().orEmpty(),
                thumbnailUrl = snippet["thumbnails"].objectOrNull()?.get("medium").objectOrNull()?.get("url")?.textOrNull(),
                watchUrl = "https://www.youtube.com/watch?v=$id"
            )
        }.orEmpty()
    }

    private fun requireSuccess(code: Int, success: Boolean, errorBody: String?) {
        if (!success) throw IOException("API respondeu $code: ${errorBody?.take(300).orEmpty()}")
    }
}

class MediaSearchRepository(
    private val services: ApiServices,
    private val secureKeyStore: SecureKeyStore,
    private val dao: ProjectDao,
    private val geminiRepository: GeminiRepository
) {
    suspend fun testGemini(key: String): String = geminiRepository.test(key)

    suspend fun search(
        query: String,
        mediaKind: MediaKind,
        settings: ProjectSettings,
        preferredDurationSeconds: Int? = null
    ): List<MediaCandidate> {
        val styledQuery = listOf(query, settings.visualStyle.searchSuffix).filter(String::isNotBlank).joinToString(" ")
        val candidates = mutableListOf<MediaCandidate>()
        val errors = mutableListOf<String>()
        var providersConfigured = 0
        val orientation = when (settings.aspectRatio) {
            AspectRatio.LANDSCAPE -> "landscape"
            AspectRatio.PORTRAIT -> "portrait"
            AspectRatio.SQUARE -> "square"
        }

        secureKeyStore.get(ApiProvider.PEXELS.name)?.takeIf(String::isNotBlank)?.let { key ->
            providersConfigured++
            try {
                val response = when (mediaKind) {
                    MediaKind.IMAGE -> services.pexelsPhotos.search(key, styledQuery, 15, orientation)
                    MediaKind.VIDEO -> services.pexelsVideos.search(key, styledQuery, 15, orientation)
                }
                if (!response.isSuccessful) throw IOException("Pexels ${response.code()}: ${response.errorBody()?.string()?.take(160)}")
                candidates += parsePexels(response.body(), mediaKind, query, settings, preferredDurationSeconds)
            } catch (error: Exception) {
                errors += error.message ?: "Pexels indisponível"
            }
        }

        if (candidates.size < 6) {
            secureKeyStore.get(ApiProvider.PIXABAY.name)?.takeIf(String::isNotBlank)?.let { key ->
                providersConfigured++
                delay(500)
                try {
                    val pixabayOrientation = when (settings.aspectRatio) {
                        AspectRatio.LANDSCAPE -> "horizontal"
                        AspectRatio.PORTRAIT -> "vertical"
                        AspectRatio.SQUARE -> null
                    }
                    val response = when (mediaKind) {
                        MediaKind.IMAGE -> services.pixabayPhotos.search(key, styledQuery, perPage = 15, orientation = pixabayOrientation)
                        MediaKind.VIDEO -> services.pixabayVideos.search(key, styledQuery, perPage = 15)
                    }
                    if (!response.isSuccessful) throw IOException("Pixabay ${response.code()}: ${response.errorBody()?.string()?.take(160)}")
                    candidates += parsePixabay(response.body(), mediaKind, query, settings, preferredDurationSeconds)
                } catch (error: Exception) {
                    errors += error.message ?: "Pixabay indisponível"
                }
            }
        }

        val local = dao.getLibraryAssets().mapNotNull { asset ->
            if (asset.mediaType != mediaKind.name) return@mapNotNull null
            val score = relevanceScore(query, "${asset.title} ${asset.description} ${asset.tags}")
            if (score < 0.03f) return@mapNotNull null
            MediaCandidate(
                mediaType = mediaKind,
                title = asset.title,
                description = asset.description,
                tags = asset.tags,
                source = "Biblioteca local",
                sourceUrl = null,
                downloadUrl = null,
                previewUrl = null,
                durationMs = asset.durationMs,
                width = asset.width,
                height = asset.height,
                score = score,
                author = "Seu arquivo",
                attributionUrl = null,
                existingLocalPath = asset.localPath
            )
        }
        candidates += local

        if (candidates.isEmpty() && providersConfigured > 0 && errors.size == providersConfigured) {
            throw IOException(errors.joinToString(" • "))
        }
        return candidates
            .map { it.copy(score = calculateScore(it, query, settings, preferredDurationSeconds)) }
            .sortedByDescending { it.score }
    }

    private fun parsePexels(
        body: JsonObject?,
        kind: MediaKind,
        query: String,
        settings: ProjectSettings,
        preferredDurationSeconds: Int?
    ): List<MediaCandidate> {
        val responseKey = if (kind == MediaKind.IMAGE) "photos" else "videos"
        return body?.get(responseKey).arrayOrEmpty().mapNotNull { element ->
            val item = element.objectOrNull() ?: return@mapNotNull null
            if (kind == MediaKind.IMAGE) {
                val src = item["src"].objectOrNull() ?: return@mapNotNull null
                val width = item["width"].intOrZero()
                val height = item["height"].intOrZero()
                val url = src["large2x"].textOrNull() ?: src["large"].textOrNull() ?: src["original"].textOrNull()
                    ?: return@mapNotNull null
                val author = item["photographer"].textOrNull().orEmpty()
                val alt = item["alt"].textOrNull().orEmpty()
                MediaCandidate(kind, alt.ifBlank { "Foto Pexels" }, alt, alt, "Pexels", item["url"].textOrNull(), url,
                    src["medium"].textOrNull() ?: src["small"].textOrNull(), 0L, width, height,
                    calculateCandidateScore(query, alt, width, height, 0L, kind, settings, preferredDurationSeconds), author,
                    "https://www.pexels.com/license/")
            } else {
                val files = item["video_files"].arrayOrEmpty().mapNotNull { it.objectOrNull() }
                    .filter { it["file_type"].textOrNull()?.contains("mp4", true) != false && it["link"].textOrNull() != null }
                val chosen = files.maxByOrNull { file ->
                    val pixels = file["width"].intOrZero() * file["height"].intOrZero()
                    val wanted = if (settings.resolution.shortEdge >= 1080) 1920 * 1080 else 1280 * 720
                    min(pixels, wanted).toDouble() / max(pixels, wanted).coerceAtLeast(1).toDouble()
                } ?: return@mapNotNull null
                val width = chosen["width"].intOrZero()
                val height = chosen["height"].intOrZero()
                val duration = item["duration"].intOrZero().toLong() * 1_000
                val user = item["user"].objectOrNull()
                val alt = item["url"].textOrNull().orEmpty()
                val thumbnail = item["image"].textOrNull()
                MediaCandidate(kind, "Vídeo Pexels", query, query, "Pexels", item["url"].textOrNull(),
                    chosen["link"].textOrNull(), thumbnail, duration, width, height,
                    calculateCandidateScore(query, query, width, height, duration, kind, settings, preferredDurationSeconds),
                    user?.get("name").textOrNull().orEmpty(), "https://www.pexels.com/license/")
            }
        }
    }

    private fun parsePixabay(
        body: JsonObject?,
        kind: MediaKind,
        query: String,
        settings: ProjectSettings,
        preferredDurationSeconds: Int?
    ): List<MediaCandidate> {
        val hits = body?.get("hits").arrayOrEmpty()
        return hits.mapNotNull { element ->
            val item = element.objectOrNull() ?: return@mapNotNull null
            val tags = item["tags"].textOrNull().orEmpty()
            if (kind == MediaKind.IMAGE) {
                val url = item["largeImageURL"].textOrNull() ?: item["webformatURL"].textOrNull()
                    ?: return@mapNotNull null
                val width = item["imageWidth"].intOrZero()
                val height = item["imageHeight"].intOrZero()
                val author = item["user"].textOrNull().orEmpty()
                MediaCandidate(kind, tags.ifBlank { "Imagem Pixabay" }, tags, tags, "Pixabay", item["pageURL"].textOrNull(),
                    url, item["previewURL"].textOrNull(), 0L, width, height,
                    calculateCandidateScore(query, tags, width, height, 0L, kind, settings, preferredDurationSeconds), author,
                    "https://pixabay.com/service/license-summary/")
            } else {
                val videoMap = item["videos"].objectOrNull() ?: return@mapNotNull null
                val rendition = listOf("large", "medium", "small", "tiny")
                    .firstNotNullOfOrNull { videoMap[it].objectOrNull()?.takeIf { v -> v["url"].textOrNull() != null } }
                    ?: return@mapNotNull null
                val width = rendition["width"].intOrZero()
                val height = rendition["height"].intOrZero()
                val duration = item["duration"].intOrZero().toLong() * 1_000
                MediaCandidate(kind, tags.ifBlank { "Vídeo Pixabay" }, tags, tags, "Pixabay", item["pageURL"].textOrNull(),
                    rendition["url"].textOrNull(), rendition["thumbnail"].textOrNull(), duration, width, height,
                    calculateCandidateScore(query, tags, width, height, duration, kind, settings, preferredDurationSeconds),
                    item["user"].textOrNull().orEmpty(), "https://pixabay.com/service/license-summary/")
            }
        }
    }

    private fun calculateScore(candidate: MediaCandidate, query: String, settings: ProjectSettings, duration: Int?): Float =
        calculateCandidateScore(query, "${candidate.title} ${candidate.description} ${candidate.tags}",
            candidate.width, candidate.height, candidate.durationMs, candidate.mediaType, settings, duration)

    private fun calculateCandidateScore(
        query: String,
        searchableText: String,
        width: Int,
        height: Int,
        durationMs: Long,
        kind: MediaKind,
        settings: ProjectSettings,
        preferredDurationSeconds: Int?
    ): Float {
        val textScore = relevanceScore(query, searchableText)
        val targetRatio = when (settings.aspectRatio) {
            AspectRatio.LANDSCAPE -> 16f / 9f
            AspectRatio.PORTRAIT -> 9f / 16f
            AspectRatio.SQUARE -> 1f
        }
        val ratio = if (width > 0 && height > 0) width.toFloat() / height else targetRatio
        val orientationScore = (1f - (abs(ln((ratio / targetRatio).coerceAtLeast(0.01f).toDouble())) / 2.4).toFloat()).coerceIn(0f, 1f)
        val targetPixels = if (settings.resolution.shortEdge >= 1080) 1920f * 1080f else 1280f * 720f
        val qualityScore = if (width > 0 && height > 0) min(1f, width.toFloat() * height / targetPixels) else 0.35f
        val desiredMs = (preferredDurationSeconds ?: settings.takeMode.fixedSeconds() ?: 4) * 1_000L
        val durationScore = if (kind == MediaKind.IMAGE || durationMs <= 0L) 0.75f
        else if (durationMs >= desiredMs) 1f else (durationMs.toFloat() / desiredMs).coerceIn(0.1f, 1f)
        return (textScore * 0.58f + orientationScore * 0.20f + qualityScore * 0.14f + durationScore * 0.08f)
            .coerceIn(0f, 1f)
    }

    private fun relevanceScore(query: String, text: String): Float {
        val ignored = setOf("the", "and", "with", "from", "that", "this", "for", "uma", "um", "de", "da", "do", "no", "na", "em", "e", "com")
        val queryWords = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 && it !in ignored }.toSet()
        if (queryWords.isEmpty()) return 0.1f
        val body = text.lowercase()
        val matched = queryWords.count { body.contains(it) }
        return (matched.toFloat() / queryWords.size).coerceIn(0f, 1f)
    }
}

private fun JsonElement?.arrayOrEmpty(): JsonArray = runCatching { this?.jsonArray }.getOrNull() ?: JsonArray(emptyList())
private fun JsonElement?.objectOrNull(): JsonObject? = runCatching { this?.jsonObject }.getOrNull()
private fun JsonElement?.textOrNull(): String? = runCatching { this?.jsonPrimitive?.contentOrNull }.getOrNull()
private fun JsonElement?.intOrZero(): Int = textOrNull()?.toDoubleOrNull()?.toInt() ?: 0
