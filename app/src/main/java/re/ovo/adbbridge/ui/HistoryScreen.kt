package re.ovo.adbbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import re.ovo.adbbridge.R
import re.ovo.adbbridge.data.LogRepository
import re.ovo.adbbridge.data.LogSession

private val REVEAL_WIDTH = 72.dp

@Composable
fun HistoryScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val sessions by LogRepository.sessions.collectAsState()
    val selectedId by LogRepository.selectedId.collectAsState()
    val activeId by LogRepository.activeId.collectAsState()
    var clearingAll by remember { mutableStateOf(false) }
    var revealedId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<LogSession?>(null) }

    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.history_count, sessions.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { clearingAll = true }) {
                Text(text = stringResource(R.string.action_clear_all), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null) { revealedId = null },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            items(sessions, key = { it.id }) { session ->
                SessionItem(
                    session = session,
                    selected = session.id == selectedId,
                    current = session.id == activeId,
                    revealed = revealedId == session.id,
                    modifier = itemAnimation(),
                    onReveal = { open -> revealedId = if (open) session.id else null },
                    onClick = {
                        if (revealedId == null) {
                            viewModel.selectLogSession(session.id)
                            onBack()
                        } else {
                            revealedId = null
                        }
                    },
                    onDelete = { pendingDelete = session },
                )
            }
        }
    }

    if (clearingAll) {
        AlertDialog(
            onDismissRequest = { clearingAll = false },
            title = { Text(text = stringResource(R.string.history_clear_all_title)) },
            text = { Text(text = stringResource(R.string.history_clear_all_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllLogs()
                    clearingAll = false
                }) {
                    Text(text = stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearingAll = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = stringResource(R.string.history_delete_title)) },
            text = {
                Text(text = stringResource(R.string.history_delete_message, sessionLabel(session)))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteLogSession(session.id)
                    revealedId = null
                    pendingDelete = null
                }) {
                    Text(text = stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SessionItem(
    session: LogSession,
    selected: Boolean,
    current: Boolean,
    revealed: Boolean,
    modifier: Modifier = Modifier,
    onReveal: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = remember(density) { with(density) { REVEAL_WIDTH.toPx() } }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(revealed, revealPx) {
        offset.animateTo(if (revealed) revealPx else 0f)
    }

    Box(modifier = modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                .clickable(onClick = onDelete),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.history_delete_desc),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(26.dp))
        }
        SessionRow(
            session = session,
            selected = selected,
            current = current,
            modifier = Modifier
                .offset { IntOffset(-offset.value.toInt(), 0) }
                .clickable {
                    if (revealed) {
                        onReveal(false)
                    } else {
                        onClick()
                    }
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value - delta).coerceIn(0f, revealPx)) }
                    },
                    onDragStopped = {
                        val open = offset.value > revealPx / 2
                        scope.launch { offset.animateTo(if (open) revealPx else 0f) }
                        onReveal(open)
                    },
                ),
        )
    }
}

@Composable
private fun SessionRow(
    session: LogSession,
    selected: Boolean,
    current: Boolean,
    modifier: Modifier = Modifier,
) {
    val corner = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = corner,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = sessionLabel(session), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary(session),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (current) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.log_session_current), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
