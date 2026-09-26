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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import re.ovo.adbbridge.bridge.BridgeStatus
import re.ovo.adbbridge.ui.theme.AdbBridgeTheme

private enum class Screen(val title: String, val label: String) {
    BRIDGE("ADB Bridge", "转发"),
    LOG("日志", "日志"),
    SETTINGS("设置", "设置"),
    HISTORY("历史会话", "日志"),
}

@Composable
fun AppContent() {
    AdbBridgeTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            MainScreen()
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val running by BridgeStatus.running.collectAsState()
    val paired by BridgeStatus.paired.collectAsState()
    val connection by BridgeStatus.connection.collectAsState()
    val status = appStatus(running = running, paired = paired, connected = connection != null)
    var screen by remember { mutableStateOf(Screen.BRIDGE) }

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
        TopBar(title = screen.title, status = status)
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
                    Screen.LOG -> LogScreen(viewModel, onOpenHistory = { screen = Screen.HISTORY })
                    Screen.SETTINGS -> SettingsScreen(viewModel)
                    Screen.HISTORY -> HistoryScreen(viewModel, onBack = { screen = Screen.LOG })
                }
            }
        }
        ScreenBar(
            current = if (screen == Screen.HISTORY) Screen.LOG else screen,
            onSelect = { screen = it },
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
                label = { Text(text = screen.label, style = MaterialTheme.typography.labelMedium) },
                alwaysShowLabel = true,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}
