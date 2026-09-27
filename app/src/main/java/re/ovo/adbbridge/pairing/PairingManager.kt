package re.ovo.adbbridge.pairing

import android.content.Context
import androidx.lifecycle.Observer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import re.ovo.adbbridge.R
import re.ovo.adbbridge.bridge.AdbTunnel
import re.ovo.adbbridge.bridge.BridgeController
import re.ovo.adbbridge.bridge.BridgeStatus
import re.ovo.adbbridge.util.appString

class PairingManager(context: Context) {

    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val keyStore = PreferenceAdbKeyStore(preferences)
    private val appContext = context.applicationContext

    /** RSA 钥匙在首次使用时构造，密钥库读取与签名运算不在界面线程进行 */
    val key: AdbKey by lazy { AdbKey(keyStore, KEY_NAME) }

    private var pairingMdns: AdbMdns? = null
    private var connectMdns: AdbMdns? = null

    val isPaired: Boolean
        get() = preferences.getBoolean(KEY_PAIRED, false)

    /**
     * 配对标记存在本地，系统里撤销无线调试授权后它不会自己失效，所以要用一次握手确认
     * 确认方式是建立隧道后执行一条 shell 命令，adbd 不认这把钥匙时不会回包
     * 单次探测超时或中途出错只说明这次没确认，连续 PROBE_ATTEMPTS 次都没有回包才认定失效
     */
    suspend fun verifyPaired(): Boolean = withContext(Dispatchers.IO) {
        val port = awaitConnectPort()
        if (port <= 0) return@withContext false
        repeat(PROBE_ATTEMPTS) {
            val tunnel = AdbTunnel(CONNECT_HOST, port, key)
            var connected = false
            val output = try {
                tunnel.connect()
                connected = true
                withTimeoutOrNull(PROBE_TIMEOUT_MS) { tunnel.selfTest() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BridgeStatus.log(appContext.appString(R.string.log_pairing_verify_failed, e.message))
                if (!connected) {
                    markUnpaired()
                    return@withContext false
                }
                null
            } finally {
                runCatching { tunnel.close() }
            }
            if (output == null) {
                BridgeStatus.log(appContext.appString(R.string.log_pairing_probe_timeout))
            }
            if (output?.contains(PROBE_ECHO) == true) return@withContext true
        }
        markUnpaired()
        false
    }

    private suspend fun awaitConnectPort(): Int {
        BridgeStatus.connectPort.value.takeIf { it > 0 }?.let { return it }
        discoverConnectPort()
        repeat(DISCOVERY_ATTEMPTS) {
            delay(DISCOVERY_INTERVAL_MS)
            BridgeStatus.connectPort.value.takeIf { it > 0 }?.let { return it }
        }
        BridgeStatus.log(appContext.appString(R.string.log_pairing_port_unknown))
        return 0
    }

    fun markUnpaired() {
        setPaired(false)
        BridgeStatus.log(appContext.appString(R.string.log_pairing_expired))
    }

    /** 配对状态变化后同步磁贴与小组件，两者都按配对与否取不同图标与文案 */
    private fun setPaired(paired: Boolean) {
        preferences.edit().putBoolean(KEY_PAIRED, paired).apply()
        BridgeStatus.paired.value = paired
        BridgeController.refreshSurfaces(appContext)
    }

    fun discoverPairingPort() {
        stop(pairingMdns)
        pairingMdns = AdbMdns(appContext, AdbMdns.TLS_PAIRING, Observer { port ->
            if (port > 0) {
                BridgeStatus.pairingPort.value = port
                BridgeStatus.log(appContext.appString(R.string.log_pairing_port_found, port))
            }
        })
        pairingMdns?.start()
        BridgeStatus.log(appContext.appString(R.string.log_pairing_searching))
    }

    fun discoverConnectPort() {
        stop(connectMdns)
        connectMdns = AdbMdns(appContext, AdbMdns.TLS_CONNECT, Observer { port ->
            if (port > 0) {
                BridgeStatus.connectPort.value = port
                BridgeStatus.log(appContext.appString(R.string.log_connect_port_found, port))
            }
        })
        connectMdns?.start()
    }

    fun pair(port: Int, code: String): Boolean {
        val client = AdbPairingClient(PAIRING_HOST, port, code, key)
        return try {
            val ok = client.start()
            if (ok) {
                setPaired(true)
                BridgeStatus.log(appContext.appString(R.string.log_pairing_success))
            } else {
                BridgeStatus.log(appContext.appString(R.string.log_pairing_failed))
            }
            ok
        } catch (e: Exception) {
            BridgeStatus.log(appContext.appString(R.string.log_pairing_error, e.message))
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
        private const val PROBE_ATTEMPTS = 2
        private const val DISCOVERY_ATTEMPTS = 30
        private const val DISCOVERY_INTERVAL_MS = 100L
    }
}
