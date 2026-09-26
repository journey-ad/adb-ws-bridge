package re.ovo.adbbridge.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import re.ovo.adbbridge.MainActivity
import re.ovo.adbbridge.R
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.data.LogCategory
import re.ovo.adbbridge.data.LogRepository
import re.ovo.adbbridge.pairing.PairingManager
import re.ovo.adbbridge.util.appString
import re.ovo.adbbridge.util.formatBytes
import re.ovo.adbbridge.util.formatDuration
import re.ovo.adbbridge.util.getLanAddress

class BridgeService : Service() {

    private lateinit var pairing: PairingManager
    private var server: BridgeServer? = null
    @Volatile
    private var pendingAuth: CompletableDeferred<Boolean>? = null

    @Volatile
    private var pendingRemote: String? = null

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

            ACTION_ALLOW -> {
                decideAuth(allowed = true)
                return START_NOT_STICKY
            }

            ACTION_DENY -> {
                decideAuth(allowed = false)
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
            BridgeStatus.log(appString(R.string.log_not_paired))
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
            BridgeStatus.log(appString(R.string.log_no_connect_port))
            stop()
            return
        }

        server = BridgeServer(
            context = applicationContext,
            port = AppPrefs.wsPort.value,
            tunnelFactory = { AdbTunnel(TUNNEL_HOST, BridgeStatus.connectPort.value, pairing.key) },
            verifyPassword = { AppPrefs.verify(it) },
            authorize = { remote -> requestAuth(remote) },
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
        LogRepository.append(null, LogCategory.ACTION, appString(R.string.log_bridge_started))
    }

    /** 新客户端必须经本机确认，已授权过的地址不再询问 */
    private suspend fun requestAuth(remote: String): Boolean {
        if (AppPrefs.isTrusted(remote)) return true
        val deferred = CompletableDeferred<Boolean>()
        pendingAuth = deferred
        pendingRemote = remote
        BridgeStatus.pendingAuth.value = remote
        if (!BridgeStatus.appForeground.value) showAuthNotification(remote)
        val allowed = withTimeoutOrNull(AUTH_TIMEOUT_MS) { deferred.await() }
        pendingAuth = null
        pendingRemote = null
        BridgeStatus.pendingAuth.value = null
        dismissAuthNotification()
        when (allowed) {
            true -> LogRepository.append(null, LogCategory.ACTION, appString(R.string.log_auth_allowed, remote))
            false -> LogRepository.append(null, LogCategory.BRIDGE, appString(R.string.log_auth_denied, remote))
            null -> LogRepository.append(null, LogCategory.BRIDGE, appString(R.string.log_auth_timeout, remote))
        }
        return allowed == true
    }

    private fun decideAuth(allowed: Boolean) {
        val remote = pendingRemote ?: return
        if (allowed) AppPrefs.trustClient(remote)
        pendingAuth?.complete(allowed)
    }

    private fun showAuthNotification(remote: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(AUTH_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    AUTH_CHANNEL_ID,
                    appString(R.string.channel_auth),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val text = appString(R.string.auth_notify_text, remote)
        manager.notify(
            AUTH_NOTIFICATION_ID,
            NotificationCompat.Builder(this, AUTH_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle(appString(R.string.auth_notify_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent())
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    appString(R.string.auth_action_deny),
                    authIntent(ACTION_DENY),
                )
                .addAction(
                    android.R.drawable.ic_menu_add,
                    appString(R.string.auth_action_allow),
                    authIntent(ACTION_ALLOW),
                )
                .build(),
        )
    }

    private fun dismissAuthNotification() {
        getSystemService(NotificationManager::class.java).cancel(AUTH_NOTIFICATION_ID)
    }

    private fun disconnect() {
        val sessionId = BridgeStatus.connection.value?.id
        if (server?.disconnectActive() != true) {
            BridgeStatus.log(appString(R.string.log_no_connection))
            return
        }
        LogRepository.append(sessionId, LogCategory.ACTION, appString(R.string.log_disconnected))
    }

    private fun stop() {
        pendingAuth?.complete(false)
        dismissAuthNotification()
        server?.close()
        server = null
        BridgeStatus.running.value = false
        BridgeStatus.connection.value = null
        if (::pairing.isInitialized) {
            pairing.stopDiscovery()
        }
        LogRepository.append(null, LogCategory.ACTION, appString(R.string.log_bridge_stopped))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 通知的展开样式可以放多行，收起时只显示标题与一行摘要 */
    private fun buildNotification(snapshot: ConnectionSnapshot?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, appString(R.string.channel_bridge), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val address = "ws://${getLanAddress() ?: "127.0.0.1"}:${AppPrefs.wsPort.value}"
        val lines = mutableListOf(appString(R.string.notify_address_line, address))
        val summary = if (snapshot == null) {
            lines += appString(R.string.notify_waiting)
            appString(R.string.notify_waiting)
        } else {
            lines += appString(R.string.notify_client_line, snapshot.remote)
            lines += appString(R.string.notify_duration_line, formatDuration(snapshot.durationMs))
            lines += getString(
                R.string.notify_traffic_line,
                formatBytes(snapshot.upBytes),
                formatBytes(snapshot.downBytes),
            )
            getString(
                R.string.notify_connected_summary,
                snapshot.remote,
                formatDuration(snapshot.durationMs),
            )
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(appString(R.string.notify_forward_title))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
        if (snapshot != null) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appString(R.string.action_disconnect),
                disconnectIntent(),
            )
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

    private fun authIntent(action: String): PendingIntent {
        val code = if (action == ACTION_ALLOW) ALLOW_REQUEST_CODE else DENY_REQUEST_CODE
        val intent = Intent(this, BridgeService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            code,
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
        const val ACTION_ALLOW = "re.ovo.adbbridge.action.ALLOW"
        const val ACTION_DENY = "re.ovo.adbbridge.action.DENY"
        private const val TUNNEL_HOST = "127.0.0.1"
        private const val CHANNEL_ID = "bridge"
        private const val AUTH_CHANNEL_ID = "auth"
        private const val NOTIFICATION_ID = 1
        private const val AUTH_NOTIFICATION_ID = 2
        private const val DISCONNECT_REQUEST_CODE = 2
        private const val ALLOW_REQUEST_CODE = 3
        private const val DENY_REQUEST_CODE = 4
        private const val AUTH_TIMEOUT_MS = 60_000L
    }
}
