package re.ovo.adbbridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import re.ovo.adbbridge.R
import re.ovo.adbbridge.bridge.BridgeStatus
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.ui.theme.AdbBridgeTheme
import re.ovo.adbbridge.util.withAppLanguage

enum class Screen { BRIDGE, LOG, SETTINGS, HISTORY }

@Composable
fun AppContent() {
    val language by AppPrefs.language.collectAsState()
    val base = LocalContext.current
    val context = remember(base, language) { base.withAppLanguage(language) }
    var screen by remember { mutableStateOf(Screen.BRIDGE) }
    CompositionLocalProvider(LocalContext provides context) {
        AdbBridgeTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                MainScreen(screen = screen, onScreenChange = { screen = it })
            }
        }
    }
}

@Composable
private fun screenTitle(screen: Screen): String = when (screen) {
    Screen.BRIDGE -> stringResource(R.string.screen_bridge_title)
    Screen.LOG -> stringResource(R.string.screen_log_title)
    Screen.SETTINGS -> stringResource(R.string.screen_settings_title)
    Screen.HISTORY -> stringResource(R.string.screen_history_title)
}

@Composable
private fun screenLabel(screen: Screen): String = when (screen) {
    Screen.BRIDGE -> stringResource(R.string.screen_bridge_label)
    Screen.LOG -> stringResource(R.string.screen_log_title)
    Screen.SETTINGS -> stringResource(R.string.screen_settings_title)
    Screen.HISTORY -> stringResource(R.string.screen_log_title)
}

@Composable
fun MainScreen(
    screen: Screen,
    onScreenChange: (Screen) -> Unit,
    viewModel: MainViewModel = viewModel(),
) {
    val running by BridgeStatus.running.collectAsState()
    val paired by BridgeStatus.paired.collectAsState()
    val connection by BridgeStatus.connection.collectAsState()
    val status = appStatus(running = running, paired = paired, connected = connection != null)

    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPaired()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        TopBar(title = screenTitle(screen), status = status)
        Box(
            modifier = Modifier
                .weight(1f)
                .imePadding(),
        ) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = { slideFade() },
                modifier = Modifier.fillMaxSize().clipToBounds(),
                label = "screen",
            ) { target ->
                when (target) {
                    Screen.BRIDGE -> BridgeScreen(viewModel)
                    Screen.LOG -> LogScreen(viewModel, onOpenHistory = { onScreenChange(Screen.HISTORY) })
                    Screen.SETTINGS -> SettingsScreen(viewModel)
                    Screen.HISTORY -> HistoryScreen(viewModel, onBack = { onScreenChange(Screen.LOG) })
                }
            }
        }
        ScreenBar(
            current = if (screen == Screen.HISTORY) Screen.LOG else screen,
            onSelect = onScreenChange,
        )
    }
}

@Composable
private fun TopBar(title: String, status: AppStatus) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = title,
            transitionSpec = { crossFade() },
            label = "topBarTitle",
        ) { value ->
            Text(text = value, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(modifier = Modifier.weight(1f))
        AnimatedContent(
            targetState = status,
            transitionSpec = { crossFade() },
            label = "topBarStatus",
        ) { value ->
            StatusChip(text = value.text, color = value.color)
        }
    }
}

@Composable
private fun ScreenBar(current: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar(
        modifier = Modifier.height(54.dp),
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Screen.entries.filter { it != Screen.HISTORY }.forEach { screen ->
            val icon = remember(screen) {
                when (screen) {
                    Screen.BRIDGE -> Icons.Outlined.SwapHoriz
                    Screen.LOG, Screen.HISTORY -> Icons.AutoMirrored.Outlined.Article
                    Screen.SETTINGS -> Icons.Outlined.Settings
                }
            }
            NavigationBarItem(
                selected = current == screen,
                onClick = { onSelect(screen) },
                icon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                label = { Text(text = screenLabel(screen), style = MaterialTheme.typography.labelMedium) },
                alwaysShowLabel = true,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}
