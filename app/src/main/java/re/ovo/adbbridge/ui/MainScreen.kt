package re.ovo.adbbridge.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import re.ovo.adbbridge.R
import re.ovo.adbbridge.bridge.BridgeController
import re.ovo.adbbridge.bridge.BridgeStatus
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.perf.PerfTrace
import re.ovo.adbbridge.perf.TraceComposition
import re.ovo.adbbridge.ui.theme.AdbBridgeTheme
import re.ovo.adbbridge.util.withAppLanguage

enum class Screen { BRIDGE, LOG, SETTINGS, HISTORY }

@Composable
fun AppContent() {
    val language by AppPrefs.language.collectAsState()
    val base = LocalContext.current
    val context = remember(base, language) { base.withAppLanguage(language) }
    var screen by remember { mutableStateOf(Screen.BRIDGE) }
    // 切换窗口包含过渡动画与目标页首次组合，结束时输出期间的帧与区段增量
    LaunchedEffect(screen) {
        delay(SWITCH_WINDOW_MS)
        PerfTrace.endWindow()
    }
    // 进入应用与切换语言后刷新磁贴与小组件文案
    LaunchedEffect(language) {
        BridgeController.refreshSurfaces(base.applicationContext)
    }
    CompositionLocalProvider(LocalContext provides context) {
        AdbBridgeTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                MainScreen(
                    screen = screen,
                    onScreenChange = { target ->
                        PerfTrace.beginWindow("switch.$target")
                        screen = target
                    },
                )
            }
        }
    }
}

private const val SWITCH_WINDOW_MS = 500L

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
    // 连接快照每秒刷新，这里只取是否连接，界面树不跟随每秒刷新重组
    val connected by BridgeStatus.connected.collectAsState()
    val status = appStatus(running = running, paired = paired, connected = connected)
    val pendingAuth by BridgeStatus.pendingAuth.collectAsState()
    val portInUse by BridgeStatus.portInUse.collectAsState()
    val wsPort by AppPrefs.wsPort.collectAsState()
    var portDialog by remember { mutableStateOf(false) }

    TraceComposition("compose.MainScreen")

    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.refreshPaired()
                Lifecycle.Event.ON_START -> BridgeStatus.appForeground.value = true
                Lifecycle.Event.ON_STOP -> BridgeStatus.appForeground.value = false
                else -> Unit
            }
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
                .imePadding()
                .clipToBounds(),
        ) {
            // 同一时刻只有当前界面在组合里，切换即整页替换
            when (screen) {
                Screen.BRIDGE -> BridgeScreen(viewModel)
                Screen.LOG -> LogScreen(
                    viewModel,
                    onOpenHistory = { onScreenChange(Screen.HISTORY) },
                )
                Screen.SETTINGS -> SettingsScreen(viewModel)
                Screen.HISTORY -> HistoryScreen(viewModel, onBack = { onScreenChange(Screen.LOG) })
            }
        }
        ScreenBar(
            current = if (screen == Screen.HISTORY) Screen.LOG else screen,
            onSelect = onScreenChange,
        )
    }

    pendingAuth?.let { remote ->
        AuthDialog(
            remote = remote,
            onAllow = { viewModel.decideAuth(allowed = true) },
            onDeny = { viewModel.decideAuth(allowed = false) },
        )
    }

    portInUse?.let { port ->
        PortInUseDialog(
            port = port,
            onChangePort = {
                BridgeStatus.portInUse.value = null
                onScreenChange(Screen.BRIDGE)
                portDialog = true
            },
            onDismiss = { BridgeStatus.portInUse.value = null },
        )
    }

    if (portDialog) {
        PortDialog(
            current = wsPort,
            onDismiss = { portDialog = false },
            onConfirm = { value ->
                viewModel.setWsPort(value)
                portDialog = false
            },
        )
    }
}

@Composable
private fun PortInUseDialog(port: Int, onChangePort: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.port_in_use_title)) },
        text = {
            Text(
                text = stringResource(R.string.port_in_use_message, port),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onChangePort) {
                Text(text = stringResource(R.string.action_change_port))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun AuthDialog(remote: String, onAllow: () -> Unit, onDeny: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text(text = stringResource(R.string.auth_notify_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.auth_notify_text, remote),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.auth_dialog_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(text = stringResource(R.string.auth_action_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text(text = stringResource(R.string.auth_action_deny))
            }
        },
    )
}

@Composable
private fun TopBar(title: String, status: AppStatus) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.weight(1f))
        StatusChip(text = status.text, color = status.color)
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
