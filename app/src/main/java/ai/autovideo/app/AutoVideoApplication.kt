package ai.autovideo.app

import android.app.Application
import ai.autovideo.app.data.AppGraph

class AutoVideoApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
