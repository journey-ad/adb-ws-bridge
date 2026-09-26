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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import re.ovo.adbbridge.MainActivity
import re.ovo.adbbridge.R
import re.ovo.adbbridge.bridge.BridgeStatus
import re.ovo.adbbridge.util.appString

/**
 * 配对期间常驻，通知里提供配对码输入框，配对码取自系统开发者选项的无线调试页面
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
        pairing.discoverPairingPort()

        scope.launch {
            val port = withTimeoutOrNull(60_000) {
                BridgeStatus.pairingPort.filter { it > 0 }.first()
            }
            if (port == null) {
                notifyPlain(
                    appString(R.string.pairing_missing_title),
                    appString(R.string.pairing_missing_text),
                )
                stop()
                return@launch
            }
            notifyInput(port)
        }
        return START_STICKY
    }

    private fun pair(code: String) {
        notifyPlain(
            appString(R.string.pairing_received_title),
            appString(R.string.pairing_received_text),
        )
        scope.launch {
            val ok = pairing.pair(BridgeStatus.pairingPort.value, code)
            pairing.stopDiscovery()
            if (ok) {
                notifyPlain(
                    appString(R.string.pairing_success_title),
                    appString(R.string.pairing_success_text),
                )
            } else {
                notifyPlain(
                    appString(R.string.pairing_failed_title),
                    appString(R.string.pairing_failed_text),
                )
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
        .setContentTitle(appString(R.string.pairing_searching_title))
        .setContentText(appString(R.string.pairing_searching_text))
        .build()

    private fun notifyPlain(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(
                NOTIFICATION_ID,
                builder(ongoing = false)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build(),
            )
    }

    private fun notifyInput(port: Int) {
        val remoteInput = RemoteInput.Builder(KEY_CODE).setLabel(appString(R.string.pairing_code_label)).build()
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, PairingCodeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = NotificationCompat.Action.Builder(0, appString(R.string.pairing_code_action), pendingIntent)
            .addRemoteInput(remoteInput)
            .build()
        BridgeStatus.log(appString(R.string.log_pairing_code_prompt, port))
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            builder()
                .setContentTitle(appString(R.string.pairing_found_title))
                .setContentText(appString(R.string.pairing_found_text, port))
                .addAction(action)
                .build(),
        )
    }

    private fun builder(ongoing: Boolean = true): NotificationCompat.Builder {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    appString(R.string.channel_pairing),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(ongoing)
            .setContentIntent(openAppIntent())
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
