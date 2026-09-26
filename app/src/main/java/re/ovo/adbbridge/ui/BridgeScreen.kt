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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import re.ovo.adbbridge.R
import re.ovo.adbbridge.bridge.BridgeStatus
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.perf.TraceComposition
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
        !paired -> AppStatus(stringResource(R.string.status_unpaired), status.warning)
        connected -> AppStatus(stringResource(R.string.status_connected), status.online)
        running -> AppStatus(stringResource(R.string.status_connecting), status.offline)
        else -> AppStatus(stringResource(R.string.status_stopped), status.offline)
    }
}

@Composable
fun BridgeScreen(viewModel: MainViewModel) {
    TraceComposition("compose.BridgeScreen")
    val context = LocalContext.current
    val running by BridgeStatus.running.collectAsState()
    val paired by BridgeStatus.paired.collectAsState()
    // 快照每秒更新，只把是否连接留在这一层，具体数值由连接卡片自己收集
    val connected by BridgeStatus.connected.collectAsState()
    val pairingPort by BridgeStatus.pairingPort.collectAsState()
    val password by AppPrefs.password.collectAsState()
    val addresses by viewModel.lanAddresses.collectAsState()
    val wsPort by AppPrefs.wsPort.collectAsState()
    val trusted by AppPrefs.trustedClients.collectAsState()
    val urls = addresses.ifEmpty { listOf("127.0.0.1") }.map { "ws://$it:$wsPort/adb" }
    val passwordQuery = password?.let { "?password=${urlEncode(it)}" }.orEmpty()

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
                        urls = urls,
                        onCopy = { copyText(context, it + passwordQuery) },
                    )
                }
            }
        }
        if (connected) {
            item(key = "connection") {
                Box(modifier = itemAnimation()) {
                    ConnectionCard(onDisconnect = { viewModel.disconnect() })
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
        item(key = "authorized") {
            Box(modifier = itemAnimation()) {
                AuthorizedCard(
                    devices = trusted.sorted(),
                    onRevoke = { AppPrefs.revokeClient(it) },
                )
            }
        }
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
                    text = if (running) {
                        stringResource(R.string.forward_running)
                    } else {
                        stringResource(R.string.forward_stopped)
                    },
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
                        text = stringResource(R.string.action_stop),
                        icon = Icons.Outlined.PowerSettingsNew,
                        onClick = onStop,
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    SmallActionButton(
                        text = stringResource(R.string.action_start),
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
    urls: List<String>,
    onCopy: (String) -> Unit,
) {
    SectionCard(title = stringResource(R.string.address_title)) {
        // 多张网卡逐个列出，只有首个地址带标签
        urls.forEachIndexed { index, url ->
            InfoRow(
                label = if (index == 0) stringResource(R.string.label_address) else "",
                value = url,
                action = {
                    SmallActionButton(
                        text = stringResource(R.string.action_copy),
                        icon = Icons.Outlined.ContentCopy,
                        onClick = { onCopy(url) },
                    )
                },
            )
        }
    }
}

@Composable
private fun ConnectionCard(onDisconnect: () -> Unit) {
    val connection = BridgeStatus.connection.collectAsState().value ?: return
    SectionCard(
        title = stringResource(R.string.connection_title),
        action = {
            SmallActionButton(
                text = stringResource(R.string.action_disconnect),
                icon = Icons.Outlined.LinkOff,
                onClick = onDisconnect,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
    ) {
        InfoRow(label = stringResource(R.string.label_client), value = connection.remote)
        InfoRow(label = stringResource(R.string.label_duration), value = formatDuration(connection.durationMs))
        InfoRow(
            label = stringResource(R.string.label_throughput),
            value = "↑ ${formatBytes(connection.upBytes)}   ↓ ${formatBytes(connection.downBytes)}",
        )
        Spacer(modifier = Modifier.height(10.dp))
        MetricGrid(
            items = listOf(
                stringResource(R.string.metric_up_rate) to formatRate(connection.upRate),
                stringResource(R.string.metric_down_rate) to formatRate(connection.downRate),
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
        title = stringResource(R.string.pairing_title),
        action = {
            SmallActionButton(
                text = stringResource(R.string.action_pair),
                icon = Icons.Outlined.Wifi,
                onClick = onGuidePairing,
            )
        },
    ) {
        if (paired) {
            Text(
                text = stringResource(R.string.pairing_done),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Text(
            text = stringResource(R.string.pairing_guide),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (port > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.pairing_port_found, port),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AuthorizedCard(devices: List<String>, onRevoke: (String) -> Unit) {
    SectionCard(title = stringResource(R.string.authorized_title)) {
        if (devices.isEmpty()) {
            Text(
                text = stringResource(R.string.auth_device_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        devices.forEach { device ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRevoke(device) }) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.auth_revoke_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun forwardSummary(running: Boolean, paired: Boolean): String {
    return when {
        running -> stringResource(R.string.forward_summary_running)
        !paired -> stringResource(R.string.forward_summary_unpaired)
        else -> stringResource(R.string.forward_summary_idle)
    }
}

private fun copyText(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("adb-bridge", text))
    Toast.makeText(context, context.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
