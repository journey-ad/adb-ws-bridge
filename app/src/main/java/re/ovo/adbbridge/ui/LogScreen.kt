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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
        !running -> LogBody.IDLE
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
                        text = selected?.let { sessionLabel(it) } ?: "当前会话",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = selected?.let { summary(it) } ?: "应用操作与设置变更",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = { settingsDialog = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "日志设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenHistory) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = "历史会话",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.logQuery.value = it },
                    label = { Text(text = "搜索日志") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(56.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { clearing = true }) {
                    Text(text = "清空")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryChip(
                    text = "全部",
                    selected = category == null,
                    onClick = { viewModel.logCategory.value = null },
                )
                Spacer(modifier = Modifier.width(6.dp))
                CategoryChip(
                    text = "系统",
                    selected = category == LogCategory.BRIDGE,
                    onClick = { viewModel.logCategory.value = LogCategory.BRIDGE },
                )
                Spacer(modifier = Modifier.width(6.dp))
                CategoryChip(
                    text = "操作",
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
                LogBody.DISABLED -> EmptyLog(message = "日志已关闭，打开日志开关后开始记录")
                LogBody.IDLE -> EmptyLog(message = "转发服务未启用，启动后在这里展示本次会话日志")
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
            title = { Text(text = "清空当前会话") },
            text = { Text(text = "是否要删除当前会话日志，删除后无法恢复") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearLogSession()
                    clearing = false
                }) {
                    Text(text = "清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { clearing = false }) { Text(text = "取消") }
            },
        )
    }

    if (settingsDialog) {
        AlertDialog(
            onDismissRequest = { settingsDialog = false },
            title = { Text(text = "日志设置") },
            text = {
                Column {
                    ToggleRow(
                        label = "日志开关",
                        description = "关闭后将不再展示和记录日志",
                        checked = logOn,
                        onCheckedChange = { AppPrefs.setLogEnabled(it) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ToggleRow(
                        label = "记录日志",
                        description = "关闭后仅展示本次会话日志，不写入存储",
                        checked = persisting,
                        onCheckedChange = { AppPrefs.setLogPersist(it) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ToggleRow(
                        label = "自动滚动",
                        description = "新日志出现时滚动到最新一条",
                        checked = autoScroll,
                        onCheckedChange = { autoScroll = it },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { settingsDialog = false }) {
                    Text(text = "完成")
                }
            },
        )
    }

    detail?.let { entry ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(text = "记录详情") },
            text = {
                Column {
                    DetailRow(label = "时间", value = "${formatDateTime(entry.time)} ${formatClock(entry.time)}")
                    Spacer(modifier = Modifier.height(4.dp))
                    DetailRow(label = "类别", value = if (entry.category == LogCategory.ACTION) "操作" else "系统")
                    Spacer(modifier = Modifier.height(4.dp))
                    DetailRow(label = "来源", value = selected?.let { sessionLabel(it) } ?: "当前会话")
                    Spacer(modifier = Modifier.height(4.dp))
                    DetailRow(label = "内容", value = entry.message)
                }
            },
            confirmButton = {
                TextButton(onClick = { detail = null }) {
                    Text(text = "关闭")
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
                    text = "没有符合条件的日志",
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
                text = if (isAction) "操作" else "系统",
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

fun sessionLabel(session: LogSession): String {
    if (session.isAppSession) return "应用操作"
    val remote = session.remote.takeIf { it.isNotBlank() }
    return if (remote == null) {
        formatDateTime(session.startAt)
    } else {
        "${formatDateTime(session.startAt)} · $remote"
    }
}

fun summary(session: LogSession): String {
    val span = (session.endAt - session.startAt).coerceAtLeast(0)
    val remote = session.remote.takeIf { it.isNotBlank() }
    val source = remote?.let { "来自 $it" } ?: "应用操作与设置变更"
    return "$source · ${session.count} 条 · ${formatDuration(span)}"
}
