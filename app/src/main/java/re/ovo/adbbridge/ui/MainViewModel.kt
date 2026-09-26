package re.ovo.adbbridge.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import re.ovo.adbbridge.R
import re.ovo.adbbridge.bridge.BridgeService
import re.ovo.adbbridge.bridge.BridgeStatus
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.data.LogCategory
import re.ovo.adbbridge.data.LogEntry
import re.ovo.adbbridge.data.LogRepository
import re.ovo.adbbridge.pairing.PairingManager
import re.ovo.adbbridge.pairing.PairingService
import re.ovo.adbbridge.util.appString
import re.ovo.adbbridge.util.getLanAddress

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val pairing = PairingManager(application)
    private var pairingCheck: Job? = null

    val passwordEnabled: StateFlow<Boolean> = AppPrefs.password
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs.passwordEnabled)

    val lanAddress = MutableStateFlow(getLanAddress())

    val versionName: String = versionOf(application)

    val logQuery = MutableStateFlow("")
    val logCategory = MutableStateFlow<LogCategory?>(null)

    val logEntries: StateFlow<List<LogEntry>> = combine(
        LogRepository.entries,
        logQuery,
        logCategory,
    ) { entries, query, category ->
        entries.filter { entry ->
            (category == null || entry.category == category) &&
                (query.isBlank() || entry.message.contains(query, ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        BridgeStatus.paired.value = pairing.isPaired
        refreshPaired()
        LogRepository.select(LogRepository.sessions.value.firstOrNull()?.id ?: LogRepository.APP_SESSION)
    }

    /** 系统里撤销无线调试授权后应用无从得知，回到前台或启动转发前重新确认一次 */
    fun refreshPaired() {
        if (!pairing.isPaired) return
        pairingCheck?.cancel()
        pairingCheck = viewModelScope.launch(Dispatchers.IO) { pairing.verifyPaired() }
    }

    /** 启动时同时打开开发者选项并高亮无线调试开关，与 Shizuku 的引导一致 */
    fun startPairing() {
        PairingService.start(getApplication())
        openDeveloperOptions()
    }

    fun openDeveloperOptions() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(SETTINGS_FRAGMENT_ARGS_KEY, WIRELESS_DEBUGGING_KEY)
        }
        runCatching { getApplication<Application>().startActivity(intent) }
    }

    fun startBridge() {
        if (BridgeStatus.running.value) return
        viewModelScope.launch(Dispatchers.IO) {
            if (!pairing.verifyPaired()) return@launch
            val address = getLanAddress()
            withContext(Dispatchers.Main) {
                lanAddress.value = address
                pairing.discoverConnectPort()
                val intent = Intent(getApplication(), BridgeService::class.java)
                getApplication<Application>().startForegroundService(intent)
            }
        }
    }

    fun stopBridge() {
        val intent = Intent(getApplication(), BridgeService::class.java).setAction(BridgeService.ACTION_STOP)
        getApplication<Application>().startService(intent)
    }

    fun disconnect() {
        val intent = Intent(getApplication(), BridgeService::class.java)
            .setAction(BridgeService.ACTION_DISCONNECT)
        getApplication<Application>().startService(intent)
    }

    fun setWsPort(value: Int) {
        AppPrefs.setWsPort(value)
        logAction(text(R.string.log_port_changed, value))
        if (!BridgeStatus.running.value) return
        stopBridge()
        viewModelScope.launch {
            delay(RESTART_DELAY_MS)
            startBridge()
        }
    }

    fun setPassword(password: String) {
        if (password.isBlank()) return
        val changed = AppPrefs.passwordEnabled
        AppPrefs.setPassword(password)
        logAction(if (changed) {
            text(R.string.log_password_changed)
        } else {
            text(R.string.log_password_enabled)
        })
    }

    fun clearPassword() {
        AppPrefs.clearPassword()
        logAction(text(R.string.log_password_disabled))
    }

    fun selectLogSession(id: String) {
        LogRepository.select(id)
    }

    fun deleteLogSession(id: String) {
        LogRepository.clear(id)
        logAction(text(R.string.log_session_deleted))
    }

    fun clearLogSession() {
        val id = LogRepository.selectedId.value
        LogRepository.clear(id)
        logAction(text(R.string.log_session_cleared))
    }

    fun clearAllLogs() {
        LogRepository.clear(null)
        LogRepository.select(LogRepository.APP_SESSION)
        logAction(text(R.string.log_all_cleared))
    }

    private fun text(resId: Int, vararg args: Any?): String {
        return getApplication<Application>().appString(resId, *args)
    }

    private fun logAction(message: String) {
        LogRepository.append(null, LogCategory.ACTION, message)
    }

    private fun versionOf(context: Context): String {
        val manager = context.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L)).versionName
            } else {
                manager.getPackageInfo(context.packageName, 0).versionName
            }
        }.getOrNull().orEmpty()
    }

    companion object {
        private const val RESTART_DELAY_MS = 600L
        private const val SETTINGS_FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
        private const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"
    }
}
