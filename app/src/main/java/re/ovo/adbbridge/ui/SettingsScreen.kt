package re.ovo.adbbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.data.ThemeMode

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val themeMode by AppPrefs.themeMode.collectAsState()
    val wsPort by AppPrefs.wsPort.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            AppearanceCard(mode = themeMode, onSelect = { AppPrefs.setThemeMode(it) })
        }
        item {
            AboutCard(version = viewModel.versionName, port = "$wsPort")
        }
    }
}

@Composable
private fun AppearanceCard(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SectionCard(title = "外观") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeOption(label = "跟随系统", option = ThemeMode.SYSTEM, mode = mode, onSelect = onSelect)
            ThemeOption(label = "浅色", option = ThemeMode.LIGHT, mode = mode, onSelect = onSelect)
            ThemeOption(label = "深色", option = ThemeMode.DARK, mode = mode, onSelect = onSelect)
        }
    }
}

@Composable
private fun ThemeOption(
    label: String,
    option: ThemeMode,
    mode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    FilterChip(
        selected = mode == option,
        onClick = { onSelect(option) },
        label = { Text(text = label, style = MaterialTheme.typography.labelMedium) },
    )
}

@Composable
private fun AboutCard(version: String, port: String) {
    SectionCard(title = "关于") {
        InfoRow(label = "版本", value = version)
        InfoRow(label = "端口", value = port)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "通过 WebSocket 向同一网络的浏览器转发本机 ADB",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
