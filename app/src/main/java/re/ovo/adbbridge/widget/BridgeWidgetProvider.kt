package re.ovo.adbbridge.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import re.ovo.adbbridge.MainActivity
import re.ovo.adbbridge.R
import re.ovo.adbbridge.bridge.BridgeController
import re.ovo.adbbridge.bridge.BridgeServer
import re.ovo.adbbridge.bridge.BridgeStatus
import re.ovo.adbbridge.bridge.ConnectionSnapshot
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.util.appString
import re.ovo.adbbridge.util.formatBytes
import re.ovo.adbbridge.util.formatDuration
import re.ovo.adbbridge.util.formatRate
import re.ovo.adbbridge.util.getLanAddresses

class BridgeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        if (BridgeController.isPaired(context)) {
            BridgeController.toggle(context)
        } else {
            openApp(context)
        }
    }

    companion object {

        private const val ACTION_TOGGLE = "re.ovo.adbbridge.widget.TOGGLE"
        private const val DASH = "—"

        private const val TITLE_LIGHT = 0xFF6B7285.toInt()
        private const val MUTED_LIGHT = 0xFF5A6070.toInt()
        private const val TITLE_DARK = 0xFF8A93A6.toInt()
        private const val MUTED_DARK = 0xFFA9B0C0.toInt()

        /** 连接快照每秒更新，小组件按此间隔取一次最新数据 */
        private const val REFRESH_INTERVAL_MS = 1_000L

        private var renderedAt = 0L

        /**
         * force 为 true 时全量重建，状态、配色、端口探测都在这一路径
         * 其余情况只提交逐秒变化的字段，省掉每秒重建视图与探测端口
         */
        fun refresh(context: Context, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - renderedAt < REFRESH_INTERVAL_MS) return
            renderedAt = now
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, BridgeWidgetProvider::class.java))
            ids.forEach {
                if (force) render(context, manager, it) else updateMetrics(context, manager, it)
            }
        }

        /** 部分更新只提交变化的字段，其余沿用上次全量结果；要求该小组件至少全量更新过一次 */
        private fun updateMetrics(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_bridge)
            lanRows(views, getLanAddresses(), AppPrefs.wsPort.value)
            metrics(context, views, BridgeStatus.connection.value)
            manager.partiallyUpdateAppWidget(id, views)
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val muted = if (dark) MUTED_DARK else MUTED_LIGHT
            val status = widgetStatus(context, dark)
            val snapshot = BridgeStatus.connection.value
            val addresses = getLanAddresses()
            val port = AppPrefs.wsPort.value
            val views = RemoteViews(context.packageName, R.layout.widget_bridge)
            views.setInt(
                R.id.widget_root,
                "setBackgroundResource",
                if (dark) R.drawable.widget_bg_dark else R.drawable.widget_bg_light,
            )
            views.setInt(
                R.id.widget_toggle,
                "setBackgroundResource",
                if (dark) R.drawable.widget_button_dark else R.drawable.widget_button_light,
            )
            views.setImageViewResource(R.id.widget_toggle, status.iconRes)
            views.setContentDescription(
                R.id.widget_toggle,
                context.appString(
                    if (BridgeStatus.running.value) R.string.action_stop else R.string.action_start
                ),
            )
            views.setTextViewText(R.id.widget_title, context.appString(R.string.app_name))
            views.setTextColor(R.id.widget_title, if (dark) TITLE_DARK else TITLE_LIGHT)
            lanRows(views, addresses, port)
            metrics(context, views, snapshot)
            listOf(
                R.id.widget_address,
                R.id.widget_address_second,
                R.id.widget_client,
                R.id.widget_throughput,
                R.id.widget_speed,
            ).forEach { views.setTextColor(it, muted) }
            views.setTextViewText(R.id.widget_state, status.state)
            views.setTextColor(R.id.widget_state, status.stateColor)
            views.setInt(R.id.widget_state, "setBackgroundResource", status.pillRes)
            views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
            views.setOnClickPendingIntent(R.id.widget_toggle, toggle(context))
            manager.updateAppWidget(id, views)
        }

        /** 两行本机地址，局域网切换时地址会变，跟随逐秒更新一并提交 */
        private fun lanRows(views: RemoteViews, addresses: List<String>, port: Int) {
            val second = addresses.getOrNull(1)
            views.setTextViewText(R.id.widget_address, url(addresses.firstOrNull(), port))
            views.setViewVisibility(
                R.id.widget_address_second,
                if (second == null) View.GONE else View.VISIBLE,
            )
            views.setTextViewText(R.id.widget_address_second, second?.let { url(it, port) }.orEmpty())
        }

        /** 会话期间逐秒变化的字段，全量与部分更新共用 */
        private fun metrics(context: Context, views: RemoteViews, snapshot: ConnectionSnapshot?) {
            views.setTextViewText(
                R.id.widget_client,
                labeled(
                    context,
                    R.string.label_client,
                    snapshot?.let { "${it.remote} · ${formatDuration(it.durationMs)}" },
                ),
            )
            views.setTextViewText(
                R.id.widget_throughput,
                labeled(
                    context,
                    R.string.label_throughput,
                    snapshot?.let { traffic(formatBytes(it.upBytes), formatBytes(it.downBytes)) },
                ),
            )
            views.setTextViewText(
                R.id.widget_speed,
                labeled(
                    context,
                    R.string.label_speed,
                    snapshot?.let { traffic(formatRate(it.upRate), formatRate(it.downRate)) },
                ),
            )
        }

        private fun widgetStatus(context: Context, dark: Boolean): WidgetContent {
            val paired = BridgeController.isPaired(context)
            val running = BridgeStatus.running.value
            val portFree = running || BridgeServer.isPortFree(AppPrefs.wsPort.value)
            return when {
                !paired -> WidgetContent(
                    context.appString(R.string.status_unpaired),
                    Palette.warning(dark),
                    R.drawable.widget_pill_warning,
                    R.drawable.ic_tile_unpaired,
                )
                !portFree -> WidgetContent(
                    context.appString(R.string.widget_port_in_use),
                    Palette.danger(dark),
                    R.drawable.widget_pill_danger,
                    R.drawable.ic_tile_idle,
                )
                BridgeStatus.connected.value -> WidgetContent(
                    context.appString(R.string.status_connected),
                    Palette.online(dark),
                    R.drawable.widget_pill_online,
                    R.drawable.ic_tile_connected,
                )
                running -> WidgetContent(
                    context.appString(R.string.widget_waiting),
                    Palette.offline(dark),
                    R.drawable.widget_pill_offline,
                    R.drawable.ic_tile_waiting,
                )
                else -> WidgetContent(
                    context.appString(R.string.status_stopped),
                    Palette.offline(dark),
                    R.drawable.widget_pill_offline,
                    R.drawable.ic_tile_idle,
                )
            }
        }

        private fun labeled(context: Context, resId: Int, value: String?): String {
            return "${context.appString(resId)} ${value ?: DASH}"
        }

        private fun traffic(up: String, down: String): String {
            return "↑ $up   ↓ $down"
        }

        private fun url(address: String?, port: Int): String {
            return "ws://${address ?: "127.0.0.1"}:$port/adb"
        }

        private fun openApp(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(context, 0, intent, FLAGS)
        }

        private fun toggle(context: Context): PendingIntent {
            val intent = Intent(context, BridgeWidgetProvider::class.java).setAction(ACTION_TOGGLE)
            return PendingIntent.getBroadcast(context, 1, intent, FLAGS)
        }

        private const val FLAGS =
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}

/** iconRes 与磁贴取同一组图标，四种状态各一个 */
private class WidgetContent(
    val state: String,
    val stateColor: Int,
    val pillRes: Int,
    val iconRes: Int,
)

/** 与界面 StatusColors 取同一组颜色 */
private object Palette {

    private const val ONLINE_LIGHT = 0xFF127A3E.toInt()
    private const val OFFLINE_LIGHT = 0xFF5A6070.toInt()
    private const val WARNING_LIGHT = 0xFF9A5B00.toInt()
    private const val DANGER_LIGHT = 0xFFC8372D.toInt()
    private const val ONLINE_DARK = 0xFF6BD68C.toInt()
    private const val OFFLINE_DARK = 0xFF98A0B3.toInt()
    private const val WARNING_DARK = 0xFFF2B33D.toInt()
    private const val DANGER_DARK = 0xFFFF9C8F.toInt()

    fun online(dark: Boolean): Int = if (dark) ONLINE_DARK else ONLINE_LIGHT

    fun offline(dark: Boolean): Int = if (dark) OFFLINE_DARK else OFFLINE_LIGHT

    fun warning(dark: Boolean): Int = if (dark) WARNING_DARK else WARNING_LIGHT

    fun danger(dark: Boolean): Int = if (dark) DANGER_DARK else DANGER_LIGHT
}
