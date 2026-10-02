package ai.autovideo.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AutoVideoColors = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF170F2B),
    primaryContainer = Color(0xFF31235B),
    onPrimaryContainer = Color(0xFFEDE7FF),
    secondary = Color(0xFF72E1CC),
    onSecondary = Color(0xFF04251E),
    tertiary = Color(0xFFFFB88D),
    background = Color(0xFF090B14),
    onBackground = Color(0xFFF6F4FB),
    surface = Color(0xFF111522),
    onSurface = Color(0xFFF6F4FB),
    surfaceVariant = Color(0xFF1B2030),
    onSurfaceVariant = Color(0xFFB5B9C9),
    outline = Color(0xFF34394A),
    error = Color(0xFFFF8D9B),
    onError = Color(0xFF32000B)
)

@Composable
fun AutoVideoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AutoVideoColors,
        typography = Typography(),
        content = content
    )
}
