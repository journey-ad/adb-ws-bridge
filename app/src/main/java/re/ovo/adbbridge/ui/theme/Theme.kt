package re.ovo.adbbridge.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B57D6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E3FF),
    onPrimaryContainer = Color(0xFF002B63),
    secondary = Color(0xFF0B6E63),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9EDE6),
    onSecondaryContainer = Color(0xFF00332E),
    tertiary = Color(0xFF7A47C9),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8DBFF),
    onTertiaryContainer = Color(0xFF2B0B55),
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF171A21),
    surface = Color(0xFFFBFBFE),
    onSurface = Color(0xFF171A21),
    surfaceVariant = Color(0xFFE6E9F2),
    onSurfaceVariant = Color(0xFF454A58),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F7FC),
    surfaceContainer = Color(0xFFEFF1F8),
    surfaceContainerHigh = Color(0xFFE9ECF5),
    surfaceContainerHighest = Color(0xFFE3E6F0),
    outline = Color(0xFFC4C8D6),
    outlineVariant = Color(0xFFDDE0EC),
    error = Color(0xFFC8372D),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE3E1),
    onErrorContainer = Color(0xFF5E0F0A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C3FF),
    onPrimary = Color(0xFF002B63),
    primaryContainer = Color(0xFF164380),
    onPrimaryContainer = Color(0xFFD8E3FF),
    secondary = Color(0xFF7FD8C8),
    onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF0B4E46),
    onSecondaryContainer = Color(0xFFC9EDE6),
    tertiary = Color(0xFFD5BCFF),
    onTertiary = Color(0xFF3A1670),
    tertiaryContainer = Color(0xFF512C93),
    onTertiaryContainer = Color(0xFFE8DBFF),
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFE3E6F0),
    surface = Color(0xFF12151C),
    onSurface = Color(0xFFE3E6F0),
    surfaceVariant = Color(0xFF2A2F3A),
    onSurfaceVariant = Color(0xFFC2C7D6),
    surfaceContainerLowest = Color(0xFF0B0E13),
    surfaceContainerLow = Color(0xFF171B23),
    surfaceContainer = Color(0xFF1B1F28),
    surfaceContainerHigh = Color(0xFF252A34),
    surfaceContainerHighest = Color(0xFF30353F),
    outline = Color(0xFF434A5A),
    outlineVariant = Color(0xFF2C323E),
    error = Color(0xFFFF9C8F),
    onError = Color(0xFF5E0F0A),
    errorContainer = Color(0xFF7A211A),
    onErrorContainer = Color(0xFFFCE3E1),
)

data class StatusColors(
    val online: Color,
    val offline: Color,
    val warning: Color,
    val danger: Color,
)

private val LightStatus = StatusColors(
    online = Color(0xFF127A3E),
    offline = Color(0xFF5A6070),
    warning = Color(0xFF9A5B00),
    danger = Color(0xFFC8372D),
)

private val DarkStatus = StatusColors(
    online = Color(0xFF6BD68C),
    offline = Color(0xFF98A0B3),
    warning = Color(0xFFF2B33D),
    danger = Color(0xFFFF9C8F),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun AdbBridgeTheme(content: @Composable () -> Unit) {
    val mode by AppPrefs.themeMode.collectAsState()
    val darkTheme = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    if (!view.isInEditMode && window != null) {
        SideEffect {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
