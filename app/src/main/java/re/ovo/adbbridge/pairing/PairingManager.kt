package re.ovo.adbbridge.pairing

import android.content.Context
import androidx.lifecycle.Observer
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import re.ovo.adbbridge.bridge.BridgeStatus

class PairingManager(context: Context) {

    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val keyStore = PreferenceAdbKeyStore(preferences)
    private val appContext = context.applicationContext

    val key = AdbKey(keyStore, KEY_NAME)

    private var pairingMdns: AdbMdns? = null
    private var connectMdns: AdbMdns? = null

    val isPaired: Boolean
        get() = preferences.getBoolean(KEY_PAIRED, false)

    fun discoverPairingPort() {
        stop(pairingMdns)
        pairingMdns = AdbMdns(appContext, AdbMdns.TLS_PAIRING, Observer { port ->
            if (port > 0) {
                BridgeStatus.pairingPort.value = port
                BridgeStatus.log("发现配对端口 $port")
            }
        })
        pairingMdns?.start()
        BridgeStatus.log("正在查找配对服务…")
    }

    fun discoverConnectPort() {
        stop(connectMdns)
        connectMdns = AdbMdns(appContext, AdbMdns.TLS_CONNECT, Observer { port ->
            if (port > 0) {
                BridgeStatus.connectPort.value = port
                BridgeStatus.log("发现连接端口 $port")
            }
        })
        connectMdns?.start()
    }

    fun pair(port: Int, code: String): Boolean {
        val client = AdbPairingClient(PAIRING_HOST, port, code, key)
        return try {
            val ok = client.start()
            if (ok) {
                preferences.edit().putBoolean(KEY_PAIRED, true).apply()
                BridgeStatus.paired.value = true
                BridgeStatus.log("配对成功")
            } else {
                BridgeStatus.log("配对失败")
            }
            ok
        } catch (e: Exception) {
            BridgeStatus.log("配对异常：${e.message}")
            false
        } finally {
            client.close()
        }
    }

    fun stopDiscovery() {
        stop(pairingMdns)
        stop(connectMdns)
        pairingMdns = null
        connectMdns = null
    }

    private fun stop(mdns: AdbMdns?) {
        runCatching { mdns?.stop() }
    }

    companion object {
        private const val PREF_NAME = "adbbridge"
        private const val KEY_PAIRED = "paired"
        private const val KEY_NAME = "adbbridge"
        private const val PAIRING_HOST = "127.0.0.1"
    }
}
