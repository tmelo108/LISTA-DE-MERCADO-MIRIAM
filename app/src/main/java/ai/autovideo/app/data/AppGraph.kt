package ai.autovideo.app.data

import android.content.Context
import ai.autovideo.app.data.local.AppDatabase
import ai.autovideo.app.data.remote.ApiRepository
import ai.autovideo.app.data.remote.ApiServices
import ai.autovideo.app.data.remote.GeminiRepository
import ai.autovideo.app.data.remote.MediaSearchRepository
import ai.autovideo.app.security.SecureKeyStore

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val database = AppDatabase.get(appContext)
    val dao = database.projectDao()
    val apiServices = ApiServices()
    val secureKeys = SecureKeyStore(appContext)
    val projects = ProjectRepository(appContext, dao)
    val gemini = GeminiRepository(apiServices)
    val mediaSearch = MediaSearchRepository(apiServices, secureKeys, dao, gemini)
    val apis = ApiRepository(apiServices, secureKeys, mediaSearch)
    val mediaFiles = MediaFileRepository(appContext, dao, apiServices.httpClient)
}
