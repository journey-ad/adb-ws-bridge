package re.ovo.adbbridge.pairing

import android.content.Context
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import re.ovo.adbbridge.bridge.AdbTunnel
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

    /**
     * 配对标记存在本地，系统里撤销无线调试授权后它不会自己失效，所以要用一次握手确认
     * 确认方式是建立隧道后执行一条 shell 命令，adbd 不认这把钥匙时不会回包
     */
    suspend fun verifyPaired(): Boolean = withContext(Dispatchers.IO) {
        val port = awaitConnectPort()
        if (port <= 0) return@withContext false
        val tunnel = AdbTunnel(CONNECT_HOST, port, key)
        try {
            tunnel.connect()
            val output = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                async { runCatching { tunnel.selfTest() }.getOrNull() }.await()
            }
            if (output?.contains(PROBE_ECHO) == true) {
                true
            } else {
                markUnpaired()
                false
            }
        } catch (e: Exception) {
            BridgeStatus.log("配对校验失败：${e.message}")
            markUnpaired()
            false
        } finally {
            runCatching { tunnel.close() }
        }
    }

    private suspend fun awaitConnectPort(): Int {
        BridgeStatus.connectPort.value.takeIf { it > 0 }?.let { return it }
        discoverConnectPort()
        repeat(DISCOVERY_ATTEMPTS) {
            delay(DISCOVERY_INTERVAL_MS)
            BridgeStatus.connectPort.value.takeIf { it > 0 }?.let { return it }
        }
        BridgeStatus.log("未能确认配对状态：未发现设备连接端口")
        return 0
    }

    fun markUnpaired() {
        preferences.edit().putBoolean(KEY_PAIRED, false).apply()
        BridgeStatus.paired.value = false
        BridgeStatus.log("配对已失效，请重新配对")
    }

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
        private const val CONNECT_HOST = "127.0.0.1"
        private const val PROBE_ECHO = "tunnel-ok"
        private const val PROBE_TIMEOUT_MS = 4000L
        private const val DISCOVERY_ATTEMPTS = 30
        private const val DISCOVERY_INTERVAL_MS = 100L
    }
}
