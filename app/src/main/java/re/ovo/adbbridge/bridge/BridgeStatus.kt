package re.ovo.adbbridge.bridge

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

object BridgeStatus {

    val logs = MutableStateFlow<List<String>>(emptyList())
    val running = MutableStateFlow(false)
    val connectionCount = MutableStateFlow(0)
    val pairingPort = MutableStateFlow(0)
    val connectPort = MutableStateFlow(0)
    val paired = MutableStateFlow(false)

    fun log(message: String) {
        Log.i(TAG, message)
        logs.value = (logs.value + message).takeLast(200)
    }

    private const val TAG = "AdbBridge"
}
