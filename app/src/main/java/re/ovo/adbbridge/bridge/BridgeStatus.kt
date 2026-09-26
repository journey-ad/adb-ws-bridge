package re.ovo.adbbridge.bridge

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import re.ovo.adbbridge.data.LogCategory
import re.ovo.adbbridge.data.LogRepository

object BridgeStatus {

    val running = MutableStateFlow(false)
    val connection = MutableStateFlow<ConnectionSnapshot?>(null)
    /** 连接快照每秒刷新，界面只关心是否连接时订阅这个状态，整棵树不跟随每秒刷新重组 */
    val connected = MutableStateFlow(false)
    val pairingPort = MutableStateFlow(0)
    val connectPort = MutableStateFlow(0)
    val paired = MutableStateFlow(false)
    val pendingAuth = MutableStateFlow<String?>(null)
    val appForeground = MutableStateFlow(false)

    fun setConnection(snapshot: ConnectionSnapshot?) {
        connection.value = snapshot
        connected.value = snapshot != null
    }

    fun log(message: String) {
        log(null, LogCategory.BRIDGE, message)
    }

    fun log(sessionId: String?, category: LogCategory, message: String) {
        Log.i(TAG, message)
        LogRepository.append(sessionId, category, message)
    }

    private const val TAG = "AdbBridge"
}
