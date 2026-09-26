package re.ovo.adbbridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import re.ovo.adbbridge.bridge.BridgeStatus
import re.ovo.adbbridge.bridge.ConnectionSnapshot
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.ui.theme.LocalStatusColors
import re.ovo.adbbridge.util.formatBytes
import re.ovo.adbbridge.util.formatDuration
import re.ovo.adbbridge.util.formatRate
import java.net.URLEncoder

data class AppStatus(val text: String, val color: Color)

@Composable
fun appStatus(running: Boolean, paired: Boolean, connected: Boolean): AppStatus {
    val status = LocalStatusColors.current
    return when {
        !paired -> AppStatus("未配对", status.warning)
        connected -> AppStatus("已连接", status.online)
        running -> AppStatus("连接中", status.offline)
        else -> AppStatus("已停止", status.offline)
    }
}

@Composable
fun BridgeScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val running by BridgeStatus.running.collectAsState()
    val paired by BridgeStatus.paired.collectAsState()
    val connection by BridgeStatus.connection.collectAsState()
    val pairingPort by BridgeStatus.pairingPort.collectAsState()
    val passwordEnabled by viewModel.passwordEnabled.collectAsState()
    val password by AppPrefs.password.collectAsState()
    val address by viewModel.lanAddress.collectAsState()
    val wsPort by AppPrefs.wsPort.collectAsState()
    val url = "ws://${address ?: "127.0.0.1"}:$wsPort/adb"
    val urlToCopy = password?.let { "$url?password=${urlEncode(it)}" } ?: url
    val port = "$wsPort"
    var passwordDialog by remember { mutableStateOf(false) }
    var portDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "forward") {
            Box(modifier = itemAnimation()) {
                ForwardCard(
                    running = running,
                    paired = paired,
                    onStart = { viewModel.startBridge() },
                    onStop = { viewModel.stopBridge() },
                )
            }
        }
        if (running) {
            item(key = "address") {
                Box(modifier = itemAnimation()) {
                    AddressCard(
                        url = url,
                        port = port,
                        passwordEnabled = passwordEnabled,
                        onCopy = { copyText(context, urlToCopy) },
                        onChangePassword = { passwordDialog = true },
                        onChangePort = { portDialog = true },
                    )
                }
            }
        }
        connection?.let { snapshot ->
            item(key = "connection") {
                Box(modifier = itemAnimation()) {
                    ConnectionCard(
                        connection = snapshot,
                        onDisconnect = { viewModel.disconnect() },
                    )
                }
            }
        }
        item(key = "pairing") {
            Box(modifier = itemAnimation()) {
                PairingCard(
                    paired = paired,
                    port = pairingPort,
                    onGuidePairing = { viewModel.startPairing() },
                )
            }
        }
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

    if (passwordDialog) {
        PasswordDialog(
            passwordEnabled = passwordEnabled,
            onDismiss = { passwordDialog = false },
            onConfirm = { value ->
                viewModel.setPassword(value)
                passwordDialog = false
            },
            onClear = {
                viewModel.clearPassword()
                passwordDialog = false
            },
        )
    }
}

@Composable
private fun ForwardCard(
    running: Boolean,
    paired: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val status = LocalStatusColors.current
    val dotColor by animateColorAsState(
        targetValue = if (running) status.online else status.offline,
        label = "forwardDot",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (running) "转发服务运行中" else "转发服务已停止",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = forwardSummary(running, paired),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            AnimatedContent(
                targetState = running,
                transitionSpec = { crossFade() },
                label = "forwardAction",
            ) { active ->
                if (active) {
                    SmallActionButton(
                        text = "停止转发",
                        icon = Icons.Outlined.PowerSettingsNew,
                        onClick = onStop,
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    SmallActionButton(
                        text = "开始转发",
                        icon = Icons.Outlined.PlayArrow,
                        onClick = onStart,
                        enabled = paired,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressCard(
    url: String,
    port: String,
    passwordEnabled: Boolean,
    onCopy: (String) -> Unit,
    onChangePassword: () -> Unit,
    onChangePort: () -> Unit,
) {
    SectionCard(title = "连接地址") {
        InfoRow(
            label = "地址",
            value = url,
            action = {
                SmallActionButton(text = "复制", icon = Icons.Outlined.ContentCopy, onClick = { onCopy(url) })
            },
        )
        InfoRow(
            label = "端口",
            value = port,
            action = {
                SmallActionButton(text = "修改", icon = Icons.Outlined.Edit, onClick = onChangePort)
            },
        )
        InfoRow(
            label = "认证",
            value = if (passwordEnabled) "需要密码" else "无需密码",
            action = {
                SmallActionButton(text = "修改", icon = Icons.Outlined.Edit, onClick = onChangePassword)
            },
        )
    }
}

@Composable
private fun ConnectionCard(connection: ConnectionSnapshot, onDisconnect: () -> Unit) {
    SectionCard(
        title = "连接信息",
        action = {
            SmallActionButton(
                text = "断开连接",
                icon = Icons.Outlined.LinkOff,
                onClick = onDisconnect,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
    ) {
        InfoRow(label = "客户端", value = connection.remote)
        InfoRow(label = "连接时长", value = formatDuration(connection.durationMs))
        InfoRow(
            label = "数据吞吐",
            value = "↑ ${formatBytes(connection.upBytes)}   ↓ ${formatBytes(connection.downBytes)}",
        )
        Spacer(modifier = Modifier.height(10.dp))
        MetricGrid(
            items = listOf(
                "上行速率" to formatRate(connection.upRate),
                "下行速率" to formatRate(connection.downRate),
            ),
        )
    }
}

@Composable
private fun PairingCard(
    paired: Boolean,
    port: Int,
    onGuidePairing: () -> Unit,
) {
    SectionCard(
        title = "设备无线调试",
        action = {
            SmallActionButton(text = "前往配对", icon = Icons.Outlined.Wifi, onClick = onGuidePairing)
        },
    ) {
        if (paired) {
            Text(
                text = "已与本机无线调试完成配对",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Text(
            text = "配对操作说明\n1. 点击「前往配对」打开无线调试开关\n2. 点击「使用配对码配对设备」\n3. 然后下拉通知填写配对码",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (port > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "已发现配对端口 $port",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun forwardSummary(running: Boolean, paired: Boolean): String {
    return when {
        running -> "同一网络的设备可连接到下面的地址"
        !paired -> "请先完成设备无线调试配对"
        else -> "启动后同一网络的设备即可连接"
    }
}

private fun copyText(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("adb-bridge", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
