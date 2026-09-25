package re.ovo.adbbridge.pairing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import re.ovo.adbbridge.bridge.BridgeStatus

/**
 * 配对期间常驻：在通知里提供配对码输入框，用户不必离开系统设置页
 */
class PairingService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var pairing: PairingManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pairing = PairingManager(this)

        val code = intent?.getStringExtra(EXTRA_CODE)
        if (!code.isNullOrBlank()) {
            pair(code)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, searchingNotification())
        BridgeStatus.log("正在查找配对服务…")
        pairing.discoverPairingPort()

        scope.launch {
            val port = withTimeoutOrNull(60_000) {
                BridgeStatus.pairingPort.filter { it > 0 }.first()
            }
            if (port == null) {
                BridgeStatus.log("未发现配对服务，请确认已点击「使用配对码配对设备」")
                notifyPlain("未发现配对服务", "请在无线调试中点击「使用配对码配对设备」后再试")
                stop()
                return@launch
            }
            notifyInput(port)
        }
        return START_STICKY
    }

    private fun pair(code: String) {
        notifyPlain("正在配对", "配对码已收到")
        scope.launch {
            val ok = pairing.pair(BridgeStatus.pairingPort.value, code)
            pairing.stopDiscovery()
            if (ok) {
                notifyPlain("配对成功", "可以启动转发服务了")
            } else {
                notifyPlain("配对失败", "请重新获取配对码后重试")
            }
            delay(2000)
            stop()
        }
    }

    private fun stop() {
        pairing.stopDiscovery()
        BridgeStatus.pairingPort.value = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun searchingNotification() = builder()
        .setContentTitle("正在查找配对服务")
        .setContentText("请在无线调试中点击「使用配对码配对设备」")
        .build()

    private fun notifyPlain(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, builder().setContentTitle(title).setContentText(text).build())
    }

    private fun notifyInput(port: Int) {
        val remoteInput = RemoteInput.Builder(KEY_CODE).setLabel("配对码").build()
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, PairingCodeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = NotificationCompat.Action.Builder(0, "输入配对码", pendingIntent)
            .addRemoteInput(remoteInput)
            .build()
        BridgeStatus.log("配对端口 $port，请在通知中输入配对码")
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            builder()
                .setContentTitle("已找到配对服务")
                .setContentText("端口 $port，点击后在通知里输入 6 位配对码")
                .addAction(action)
                .build(),
        )
    }

    private fun builder(): NotificationCompat.Builder {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "配对", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "re.ovo.adbbridge.action.PAIRING_STOP"
        const val EXTRA_CODE = "code"
        const val KEY_CODE = "pairing_code"

        private const val CHANNEL_ID = "pairing"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context) {
            val intent = Intent(context, PairingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
