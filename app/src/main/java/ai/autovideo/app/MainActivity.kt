package ai.autovideo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ai.autovideo.app.ui.AutoVideoApp
import ai.autovideo.app.ui.AutoVideoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoVideoTheme {
                AutoVideoApp()
            }
        }
    }
}
