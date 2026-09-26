package re.ovo.adbbridge.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import re.ovo.adbbridge.R
import re.ovo.adbbridge.bridge.BridgeStatus
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.data.LogCategory
import re.ovo.adbbridge.data.LogEntry
import re.ovo.adbbridge.data.LogRepository
import re.ovo.adbbridge.data.LogSession
import re.ovo.adbbridge.util.formatClock
import re.ovo.adbbridge.util.formatDateTime
import re.ovo.adbbridge.util.formatDuration

private enum class LogBody { DISABLED, IDLE, LIST }

@Composable
fun LogScreen(viewModel: MainViewModel, onOpenHistory: () -> Unit) {
    val running by BridgeStatus.running.collectAsState()
    val logOn by AppPrefs.logEnabled.collectAsState()
    val persisting by AppPrefs.logPersist.collectAsState()
    val sessions by LogRepository.sessions.collectAsState()
    val selectedId by LogRepository.selectedId.collectAsState()
    val query by viewModel.logQuery.collectAsState()
    val category by viewModel.logCategory.collectAsState()
    val entries by viewModel.logEntries.collectAsState()
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    var clearing by remember { mutableStateOf(false) }
    var settingsDialog by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<LogEntry?>(null) }

    val selected = sessions.firstOrNull { it.id == selectedId }
    val body = when {
        !logOn -> LogBody.DISABLED
        running -> LogBody.LIST
        selectedId == LogRepository.APP_SESSION && entries.isEmpty() -> LogBody.IDLE
        else -> LogBody.LIST
    }

    LaunchedEffect(entries.size, selectedId, autoScroll) {
        if (autoScroll && entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selected?.let { sessionLabel(it) } ?: stringResource(R.string.log_session_current),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = selected?.let { summary(it) } ?: stringResource(R.string.log_session_app_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = { settingsDialog = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.log_settings_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenHistory) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = stringResource(R.string.log_history_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.logQuery.value = it },
                    label = { Text(text = stringResource(R.string.log_search_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(56.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { clearing = true }) {
                    Text(text = stringResource(R.string.action_clear))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryChip(
                    text = stringResource(R.string.log_category_all),
                    selected = category == null,
                    onClick = { viewModel.logCategory.value = null },
                )
                Spacer(modifier = Modifier.width(6.dp))
                CategoryChip(
                    text = stringResource(R.string.log_category_bridge),
                    selected = category == LogCategory.BRIDGE,
                    onClick = { viewModel.logCategory.value = LogCategory.BRIDGE },
                )
                Spacer(modifier = Modifier.width(6.dp))
                CategoryChip(
                    text = stringResource(R.string.log_category_action),
                    selected = category == LogCategory.ACTION,
                    onClick = { viewModel.logCategory.value = LogCategory.ACTION },
                )
            }
        }
        Crossfade(
            targetState = body,
            animationSpec = tween(180),
            label = "logBody",
        ) { state ->
            when (state) {
                LogBody.DISABLED -> EmptyLog(message = stringResource(R.string.log_empty_disabled))
                LogBody.IDLE -> EmptyLog(message = stringResource(R.string.log_empty_idle))
                LogBody.LIST -> LogList(
                    entries = entries,
                    listState = listState,
                    onSelect = { detail = it },
                )
            }
        }
    }

    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(text = stringResource(R.string.log_clear_title)) },
            text = { Text(text = stringResource(R.string.log_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearLogSession()
                    clearing = false
                }) {
                    Text(text = stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearing = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (settingsDialog) {
        AlertDialog(
            onDismissRequest = { settingsDialog = false },
            title = { Text(text = stringResource(R.string.log_settings_desc)) },
            text = {
                Column {
                    ToggleRow(
                        label = stringResource(R.string.log_toggle_enable),
                        description = stringResource(R.string.log_toggle_enable_desc),
                        checked = logOn,
                        onCheckedChange = { AppPrefs.setLogEnabled(it) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ToggleRow(
                        label = stringResource(R.string.log_toggle_persist),
                        description = stringResource(R.string.log_toggle_persist_desc),
                        checked = persisting,
                        onCheckedChange = { AppPrefs.setLogPersist(it) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ToggleRow(
                        label = stringResource(R.string.log_toggle_autoscroll),
                        description = stringResource(R.string.log_toggle_autoscroll_desc),
                        checked = autoScroll,
                        onCheckedChange = { autoScroll = it },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { settingsDialog = false }) {
                    Text(text = stringResource(R.string.action_done))
                }
            },
        )
    }

    detail?.let { entry ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(text = stringResource(R.string.log_detail_title)) },
            text = {
                Column {
                    DetailRow(
                        label = stringResource(R.string.log_detail_time),
                        value = "${formatDateTime(entry.time)} ${formatClock(entry.time)}",
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    DetailRow(
                        label = stringResource(R.string.log_detail_category),
                        value = if (entry.category == LogCategory.ACTION) {
                            stringResource(R.string.log_category_action)
                        } else {
                            stringResource(R.string.log_category_bridge)
                        },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    DetailRow(
                        label = stringResource(R.string.log_detail_source),
                        value = selected?.let { sessionLabel(it) } ?: stringResource(R.string.log_session_current),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    DetailRow(label = stringResource(R.string.log_detail_content), value = entry.message)
                }
            },
            confirmButton = {
                TextButton(onClick = { detail = null }) {
                    Text(text = stringResource(R.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun LogList(
    entries: List<LogEntry>,
    listState: LazyListState,
    onSelect: (LogEntry) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        if (entries.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.log_empty_filtered),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        items(entries) { entry ->
            LogRow(entry = entry, onClick = { onSelect(entry) })
        }
    }
}

@Composable
private fun EmptyLog(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CategoryChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = text, style = MaterialTheme.typography.labelSmall) },
    )
}

@Composable
private fun LogRow(entry: LogEntry, onClick: () -> Unit) {
    val isAction = entry.category == LogCategory.ACTION
    val tagColor = if (isAction) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = formatClock(entry.time),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(58.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = tagColor.copy(alpha = 0.12f),
            contentColor = tagColor,
        ) {
            Text(
                text = if (isAction) {
                    stringResource(R.string.log_category_action)
                } else {
                    stringResource(R.string.log_category_bridge)
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun sessionLabel(session: LogSession): String {
    if (session.isAppSession) return stringResource(R.string.log_session_app)
    val remote = session.remote.takeIf { it.isNotBlank() }
    return if (remote == null) {
        formatDateTime(session.startAt)
    } else {
        "${formatDateTime(session.startAt)} · $remote"
    }
}

@Composable
fun summary(session: LogSession): String {
    val span = (session.endAt - session.startAt).coerceAtLeast(0)
    val remote = session.remote.takeIf { it.isNotBlank() }
    val source = remote?.let { stringResource(R.string.log_session_from, it) }
        ?: stringResource(R.string.log_session_app_summary)
    return stringResource(R.string.log_session_summary, source, session.count, formatDuration(span))
}
