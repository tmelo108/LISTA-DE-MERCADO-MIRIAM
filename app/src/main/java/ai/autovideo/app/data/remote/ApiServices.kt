package ai.autovideo.app.data.remote

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

val ApiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

class ApiServices {
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(httpClient)
        .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
        .build()

    val gemini: GeminiApi = retrofit("https://generativelanguage.googleapis.com/v1beta/")
        .create(GeminiApi::class.java)
    val pexelsPhotos: PexelsPhotosApi = retrofit("https://api.pexels.com/v1/")
        .create(PexelsPhotosApi::class.java)
    val pexelsVideos: PexelsVideosApi = retrofit("https://api.pexels.com/videos/")
        .create(PexelsVideosApi::class.java)
    val pixabayPhotos: PixabayPhotosApi = retrofit("https://pixabay.com/api/")
        .create(PixabayPhotosApi::class.java)
    val pixabayVideos: PixabayVideosApi = retrofit("https://pixabay.com/api/videos/")
        .create(PixabayVideosApi::class.java)
    val youtube: YouTubeApi = retrofit("https://www.googleapis.com/youtube/v3/")
        .create(YouTubeApi::class.java)
}

interface GeminiApi {
    @POST("models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") key: String,
        @Body body: JsonObject
    ): Response<JsonObject>
}

interface PexelsPhotosApi {
    @GET("search")
    suspend fun search(
        @Header("Authorization") apiKey: String,
        @Query("query") query: String,
        @Query("per_page") perPage: Int = 15,
        @Query("orientation") orientation: String? = null
    ): Response<JsonObject>
}

interface PexelsVideosApi {
    @GET("search")
    suspend fun search(
        @Header("Authorization") apiKey: String,
        @Query("query") query: String,
        @Query("per_page") perPage: Int = 15,
        @Query("orientation") orientation: String? = null
    ): Response<JsonObject>
}

interface PixabayPhotosApi {
    @GET(".")
    suspend fun search(
        @Query("key") apiKey: String,
        @Query("q") query: String,
        @Query("per_page") perPage: Int = 15,
        @Query("image_type") imageType: String = "photo",
        @Query("orientation") orientation: String? = null,
        @Query("safesearch") safeSearch: Boolean = true
    ): Response<JsonObject>
}

interface PixabayVideosApi {
    @GET(".")
    suspend fun search(
        @Query("key") apiKey: String,
        @Query("q") query: String,
        @Query("per_page") perPage: Int = 15,
        @Query("safesearch") safeSearch: Boolean = true
    ): Response<JsonObject>
}

interface YouTubeApi {
    @GET("search")
    suspend fun search(
        @Query("part") part: String = "snippet",
        @Query("q") query: String,
        @Query("type") type: String = "video",
        @Query("maxResults") maxResults: Int = 5,
        @Query("key") apiKey: String
    ): Response<JsonObject>
}
