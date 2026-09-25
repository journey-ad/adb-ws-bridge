package re.ovo.adbbridge.bridge

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import re.ovo.adbbridge.data.LogCategory
import re.ovo.adbbridge.data.LogRepository

object BridgeStatus {

    val running = MutableStateFlow(false)
    val connection = MutableStateFlow<ConnectionSnapshot?>(null)
    val pairingPort = MutableStateFlow(0)
    val connectPort = MutableStateFlow(0)
    val paired = MutableStateFlow(false)

    fun log(message: String) {
        log(null, LogCategory.BRIDGE, message)
    }

    fun log(sessionId: String?, category: LogCategory, message: String) {
        Log.i(TAG, message)
        LogRepository.append(sessionId, category, message)
    }

    private const val TAG = "AdbBridge"
}
