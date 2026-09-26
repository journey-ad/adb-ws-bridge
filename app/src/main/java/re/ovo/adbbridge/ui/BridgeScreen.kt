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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import re.ovo.adbbridge.R
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
        !paired -> AppStatus(stringResource(R.string.status_unpaired), status.warning)
        connected -> AppStatus(stringResource(R.string.status_connected), status.online)
        running -> AppStatus(stringResource(R.string.status_connecting), status.offline)
        else -> AppStatus(stringResource(R.string.status_stopped), status.offline)
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
    url: String,
    port: String,
    passwordEnabled: Boolean,
    onCopy: (String) -> Unit,
    onChangePassword: () -> Unit,
    onChangePort: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.address_title)) {
        InfoRow(
            label = stringResource(R.string.label_address),
            value = url,
            action = {
                SmallActionButton(
                    text = stringResource(R.string.action_copy),
                    icon = Icons.Outlined.ContentCopy,
                    onClick = { onCopy(url) },
                )
            },
        )
        InfoRow(
            label = stringResource(R.string.label_port),
            value = port,
            action = {
                SmallActionButton(
                    text = stringResource(R.string.action_edit),
                    icon = Icons.Outlined.Edit,
                    onClick = onChangePort,
                )
            },
        )
        InfoRow(
            label = stringResource(R.string.label_auth),
            value = if (passwordEnabled) {
                stringResource(R.string.auth_required)
            } else {
                stringResource(R.string.auth_none)
            },
            action = {
                SmallActionButton(
                    text = stringResource(R.string.action_edit),
                    icon = Icons.Outlined.Edit,
                    onClick = onChangePassword,
                )
            },
        )
    }
}

@Composable
private fun ConnectionCard(connection: ConnectionSnapshot, onDisconnect: () -> Unit) {
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
