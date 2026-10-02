package ai.autovideo.app.data.remote

import ai.autovideo.app.data.model.ProjectSettings
import ai.autovideo.app.data.model.ScriptAnalysis
import ai.autovideo.app.data.model.SceneDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.max

class GeminiRepository(private val services: ApiServices) {
    suspend fun analyzeNarration(audioFile: File, apiKey: String, settings: ProjectSettings): ScriptAnalysis {
        require(audioFile.exists() && audioFile.length() > 0) { "O arquivo de narração está vazio ou não existe." }
        val mimeType = audioMimeType(audioFile)
        val uploaded = uploadAudio(audioFile, apiKey, mimeType)
        try {
            val targetSeconds = settings.takeMode.fixedSeconds()?.toString()
                ?: if (settings.takeMode.name == "CUSTOM") "${settings.customTakeMinSeconds} a ${settings.customTakeMaxSeconds}" else "3 a 6"
            val response = services.gemini.generateContent(
                model = "gemini-2.5-flash",
                key = apiKey,
                body = buildJsonObject {
                    putJsonArray("contents") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                add(buildJsonObject {
                                    putJsonObject("file_data") {
                                        put("mime_type", mimeType)
                                        put("file_uri", uploaded.uri)
                                    }
                                })
                                add(buildJsonObject {
                                    put(
                                        "text",
                                        "Ouça a narração anexada. Transcreva integralmente no idioma falado e divida o áudio em microcenas visuais, incluindo mudanças de pessoa, ação, objeto, ambiente, emoção e acontecimento (não use apenas parágrafos). " +
                                            "Crie cenas consecutivas, cobrindo a linha do tempo do início ao fim sem lacunas; procure takes de $targetSeconds segundos quando o conteúdo permitir. " +
                                            "Considere o estilo ${settings.visualStyle.label}. As consultas devem ser termos curtos em inglês, adequados a Pexels/Pixabay. " +
                                            "Retorne APENAS JSON válido no formato: {\"transcript\":\"texto completo\",\"scenes\":[{\"id\":1,\"start\":0.0,\"end\":3.0,\"duration\":3.0,\"narration\":\"trecho falado\",\"visual_description\":\"descrição visual\",\"search_queries\":[\"query one\",\"query two\"],\"preferred_media\":\"video\"}]}. " +
                                            "Use preferred_media image somente para cenas contemplativas/estáticas e video para ação ou movimento. Os tempos devem estar em segundos e corresponder ao áudio."
                                    )
                                })
                            }
                        }
                    }
                    putJsonObject("generationConfig") {
                        put("temperature", 0.25)
                        put("responseMimeType", "application/json")
                    }
                }
            )
            if (!response.isSuccessful) {
                throw IOException("Gemini respondeu ${response.code()}: ${response.errorBody()?.string()?.take(500) ?: "erro sem detalhes"}")
            }
            val rawText = response.body()?.let(::extractResponseText)
                ?: throw IOException("A resposta do Gemini veio vazia.")
            val jsonText = rawText.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val root = runCatching { ApiJson.parseToJsonElement(jsonText).jsonObject }
                .getOrElse { throw IOException("O Gemini retornou JSON inválido: ${it.message}") }
            val transcript = root["transcript"]?.jsonPrimitive?.contentOrNull
                ?: root["script"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val sceneElements = root["scenes"]?.jsonArray
                ?: throw IOException("O Gemini não retornou a lista de microcenas.")
            val parsedScenes = sceneElements.mapIndexed { index, item ->
                runCatching { ApiJson.decodeFromJsonElement(SceneDraft.serializer(), item) }
                    .getOrElse { throw IOException("Microcena ${index + 1} inválida: ${it.message}") }
            }.sortedBy { it.start }
            if (parsedScenes.isEmpty()) throw IOException("O Gemini não encontrou microcenas na narração.")
            val normalized = parsedScenes.mapIndexed { index, scene ->
                val start = max(0.0, scene.start)
                val end = max(start + 0.25, scene.end)
                scene.copy(
                    id = index + 1,
                    start = start,
                    end = end,
                    duration = end - start,
                    narration = scene.narration.ifBlank { "" },
                    visualDescription = scene.visualDescription.ifBlank { scene.narration },
                    searchQueries = scene.searchQueries.filter(String::isNotBlank).take(5)
                        .ifEmpty { listOf(scene.visualDescription.take(90)) },
                    preferredMedia = if (scene.preferredMedia.equals("image", ignoreCase = true)) "image" else "video"
                )
            }
            return ScriptAnalysis(
                transcript = transcript.ifBlank { normalized.joinToString(" ") { it.narration }.trim() },
                scenes = normalized
            )
        } finally {
            deleteUploadedFile(uploaded.name, apiKey)
        }
    }

    suspend fun test(apiKey: String): String {
        val response = services.gemini.generateContent(
            model = "gemini-2.5-flash",
            key = apiKey,
            body = buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", "Responda apenas: OK") }) }
                    }
                }
                putJsonObject("generationConfig") { put("maxOutputTokens", 8) }
            }
        )
        if (!response.isSuccessful) throw IOException("Gemini respondeu ${response.code()}: ${response.errorBody()?.string()?.take(300)}")
        return "Conexão Gemini validada."
    }

    private suspend fun uploadAudio(file: File, apiKey: String, mimeType: String): UploadedFile = withContext(Dispatchers.IO) {
        val encodedKey = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        val metadata = """{"file":{"display_name":"${file.name.replace("\"", "")}"}}"""
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val startRequest = Request.Builder()
            .url("https://generativelanguage.googleapis.com/upload/v1beta/files?key=$encodedKey")
            .post(metadata)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .build()
        val uploadUrl = services.httpClient.newCall(startRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Falha ao iniciar envio ao Gemini (${response.code}).")
            response.header("X-Goog-Upload-URL") ?: throw IOException("Gemini não retornou a URL de envio do arquivo.")
        }
        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .post(file.asRequestBody(mimeType.toMediaTypeOrNull()))
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .build()
        val fileObject = services.httpClient.newCall(uploadRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Falha no envio da narração ao Gemini (${response.code}).")
            val json = response.body?.string()?.let(ApiJson::parseToJsonElement)?.jsonObject
                ?: throw IOException("Resposta vazia ao enviar a narração.")
            json["file"]?.jsonObject ?: json
        }
        val name = fileObject["name"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("Gemini não retornou o identificador do arquivo.")
        val uri = fileObject["uri"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("Gemini não retornou a URI do arquivo.")
        var state = fileObject["state"]?.jsonPrimitive?.contentOrNull ?: "PROCESSING"
        repeat(30) {
            if (state.equals("ACTIVE", ignoreCase = true)) return@withContext UploadedFile(name, uri)
            if (state.equals("FAILED", ignoreCase = true)) throw IOException("Gemini não conseguiu processar o áudio enviado.")
            delay(2_000)
            val statusRequest = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/$name?key=$encodedKey")
                .get().build()
            val status = services.httpClient.newCall(statusRequest).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Não foi possível consultar o processamento do áudio (${response.code}).")
                response.body?.string()?.let(ApiJson::parseToJsonElement)?.jsonObject
                    ?: throw IOException("Resposta vazia ao consultar o áudio.")
            }
            state = status["state"]?.jsonPrimitive?.contentOrNull ?: state
        }
        throw IOException("O Gemini ainda não terminou de processar o áudio. Tente novamente.")
    }

    private suspend fun deleteUploadedFile(name: String, apiKey: String) = withContext(Dispatchers.IO) {
        runCatching {
            val encodedKey = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/$name?key=$encodedKey")
                .delete()
                .build()
            services.httpClient.newCall(request).execute().close()
        }
    }

    private fun extractResponseText(response: kotlinx.serialization.json.JsonObject): String? {
        return response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("\n")
    }

    private fun audioMimeType(file: File): String = when (file.extension.lowercase()) {
        "wav" -> "audio/wav"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        else -> "audio/mpeg"
    }

    private data class UploadedFile(val name: String, val uri: String)
}
