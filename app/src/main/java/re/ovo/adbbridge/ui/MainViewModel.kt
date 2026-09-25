package re.ovo.adbbridge.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import re.ovo.adbbridge.bridge.BridgeService
import re.ovo.adbbridge.bridge.BridgeStatus
import re.ovo.adbbridge.pairing.PairingManager
import re.ovo.adbbridge.pairing.PairingService

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val pairing = PairingManager(application)

    init {
        BridgeStatus.paired.value = pairing.isPaired
    }

    /** 启动时同时打开开发者选项并高亮无线调试开关，与 Shizuku 的引导一致 */
    fun startPairing() {
        PairingService.start(getApplication())
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(SETTINGS_FRAGMENT_ARGS_KEY, WIRELESS_DEBUGGING_KEY)
        }
        runCatching { getApplication<Application>().startActivity(intent) }
    }

    fun discoverPairingPort() {
        pairing.discoverPairingPort()
    }

    fun pair(code: String) {
        val port = BridgeStatus.pairingPort.value
        if (port <= 0 || code.isBlank()) {
            BridgeStatus.log("请先填入配对码并确认已发现配对端口")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            pairing.pair(port, code)
            pairing.stopDiscovery()
        }
    }

    fun startBridge() {
        pairing.discoverConnectPort()
        val intent = Intent(getApplication(), BridgeService::class.java)
        getApplication<Application>().startForegroundService(intent)
    }

    fun stopBridge() {
        val intent = Intent(getApplication(), BridgeService::class.java).setAction(BridgeService.ACTION_STOP)
        getApplication<Application>().startService(intent)
    }

    companion object {
        private const val SETTINGS_FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
        private const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"
    }
}
