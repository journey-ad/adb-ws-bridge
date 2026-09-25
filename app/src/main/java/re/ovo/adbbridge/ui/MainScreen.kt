package re.ovo.adbbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import re.ovo.adbbridge.bridge.BridgeService
import re.ovo.adbbridge.bridge.BridgeStatus
import re.ovo.adbbridge.util.getLanAddress

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val paired by BridgeStatus.paired.collectAsState()
    val running by BridgeStatus.running.collectAsState()
    val pairingPort by BridgeStatus.pairingPort.collectAsState()
    val connectionCount by BridgeStatus.connectionCount.collectAsState()
    val logs by BridgeStatus.logs.collectAsState()
    var code by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "ADB 桥", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (paired) "已配对" else "未配对",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "请在开发者选项中开启无线调试，点「使用配对码配对设备」后开始配对，配对码在通知里输入",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { viewModel.startPairing() }) {
                    Text(text = "开始配对")
                }
                if (pairingPort > 0) {
                    Text(text = "配对端口：$pairingPort", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(text = "手动输入配对码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { viewModel.pair(code) }) {
                    Text(text = "手动配对")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "转发服务", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = running, onCheckedChange = { checked ->
                        if (checked) viewModel.startBridge() else viewModel.stopBridge()
                    })
                }
                Text(
                    text = "ws://${getLanAddress() ?: "127.0.0.1"}:${BridgeService.WS_PORT}/adb",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(text = "当前连接：$connectionCount", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text(text = "日志", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
