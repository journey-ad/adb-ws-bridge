package re.ovo.adbbridge.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import re.ovo.adbbridge.MainActivity
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.data.LogCategory
import re.ovo.adbbridge.data.LogRepository
import re.ovo.adbbridge.pairing.PairingManager
import re.ovo.adbbridge.util.formatBytes
import re.ovo.adbbridge.util.formatDuration
import re.ovo.adbbridge.util.getLanAddress

class BridgeService : Service() {

    private lateinit var pairing: PairingManager
    private var server: BridgeServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stop()
                return START_NOT_STICKY
            }

            ACTION_DISCONNECT -> {
                disconnect()
                return START_NOT_STICKY
            }
        }
        start()
        return START_STICKY
    }

    private fun start() {
        if (BridgeStatus.running.value) return
        pairing = PairingManager(this)
        if (!pairing.isPaired) {
            BridgeStatus.log("尚未配对，先完成配对再启动转发")
            stop()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification(null))

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
            port = AppPrefs.wsPort.value,
            tunnelFactory = { AdbTunnel(TUNNEL_HOST, BridgeStatus.connectPort.value, pairing.key) },
            verifyPassword = { AppPrefs.verify(it) },
            onSessionChanged = { snapshot ->
                BridgeStatus.connection.value = snapshot
                updateNotification(snapshot)
            },
            onLog = { sessionId, category, message ->
                BridgeStatus.log(sessionId, category, message)
            },
        )
        server?.start()
        BridgeStatus.running.value = true
        LogRepository.append(null, LogCategory.ACTION, "已启动转发服务")
    }

    private fun disconnect() {
        val sessionId = BridgeStatus.connection.value?.id
        if (server?.disconnectActive() != true) {
            BridgeStatus.log("当前没有可断开的连接")
            return
        }
        LogRepository.append(sessionId, LogCategory.ACTION, "已主动断开连接")
    }

    private fun stop() {
        server?.close()
        server = null
        BridgeStatus.running.value = false
        BridgeStatus.connection.value = null
        if (::pairing.isInitialized) {
            pairing.stopDiscovery()
        }
        LogRepository.append(null, LogCategory.ACTION, "已停止转发服务")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 通知的展开样式可以放多行，收起时只显示标题与一行摘要 */
    private fun buildNotification(snapshot: ConnectionSnapshot?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ADB 桥", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val address = "ws://${getLanAddress() ?: "127.0.0.1"}:${AppPrefs.wsPort.value}"
        val lines = mutableListOf("连接地址 $address")
        val summary = if (snapshot == null) {
            lines += "等待连接"
            "等待连接"
        } else {
            lines += "已连接客户端 ${snapshot.remote}"
            lines += "连接时长 ${formatDuration(snapshot.durationMs)}"
            lines += "↑ ${formatBytes(snapshot.upBytes)}   ↓ ${formatBytes(snapshot.downBytes)}"
            "已连接 ${snapshot.remote} · ${formatDuration(snapshot.durationMs)}"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("转发服务运行中")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
        if (snapshot != null) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "断开连接", disconnectIntent())
        }
        return builder.build()
    }

    private fun disconnectIntent(): PendingIntent {
        val intent = Intent(this, BridgeService::class.java).setAction(ACTION_DISCONNECT)
        return PendingIntent.getService(
            this,
            DISCONNECT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun updateNotification(snapshot: ConnectionSnapshot?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    companion object {
        const val ACTION_START = "re.ovo.adbbridge.action.START"
        const val ACTION_STOP = "re.ovo.adbbridge.action.STOP"
        const val ACTION_DISCONNECT = "re.ovo.adbbridge.action.DISCONNECT"
        private const val TUNNEL_HOST = "127.0.0.1"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1
        private const val DISCONNECT_REQUEST_CODE = 2
    }
}
