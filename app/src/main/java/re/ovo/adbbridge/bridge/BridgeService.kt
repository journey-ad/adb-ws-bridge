package re.ovo.adbbridge.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import re.ovo.adbbridge.pairing.PairingManager
import re.ovo.adbbridge.util.getLanAddress

class BridgeService : Service() {

    private lateinit var pairing: PairingManager
    private var server: BridgeServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        start()
        return START_STICKY
    }

    private fun start() {
        pairing = PairingManager(this)
        if (!pairing.isPaired) {
            BridgeStatus.log("尚未配对，先完成配对再启动转发")
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification(0))

        pairing.discoverConnectPort()
        val port = runBlocking {
            repeat(50) {
                if (BridgeStatus.connectPort.value > 0) return@runBlocking BridgeStatus.connectPort.value
                delay(100)
            }
            0
        }
        if (port == 0) {
            BridgeStatus.log("未发现设备连接端口，请确认无线调试已开启")
            stop()
            return
        }

        server = BridgeServer(
            port = WS_PORT,
            tunnelFactory = { AdbTunnel(TUNNEL_HOST, BridgeStatus.connectPort.value, pairing.key) },
            onConnectionChanged = { count ->
                BridgeStatus.connectionCount.value = count
                updateNotification(count)
            },
            onLog = { BridgeStatus.log(it) },
        )
        server?.start()
        BridgeStatus.running.value = true
    }

    private fun stop() {
        server?.close()
        server = null
        BridgeStatus.running.value = false
        BridgeStatus.connectionCount.value = 0
        if (::pairing.isInitialized) {
            pairing.stopDiscovery()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(count: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ADB 桥", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val address = "ws://${getLanAddress() ?: "127.0.0.1"}:$WS_PORT"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("转发服务运行中")
            .setContentText("$address · 当前 $count 条连接")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(count: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(count))
    }

    companion object {
        const val ACTION_START = "re.ovo.adbbridge.action.START"
        const val ACTION_STOP = "re.ovo.adbbridge.action.STOP"
        const val WS_PORT = 5556
        private const val TUNNEL_HOST = "127.0.0.1"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1
    }
}
