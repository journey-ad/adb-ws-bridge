package re.ovo.adbbridge.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import re.ovo.adbbridge.MainActivity
import re.ovo.adbbridge.R
import re.ovo.adbbridge.bridge.BridgeController
import re.ovo.adbbridge.bridge.BridgeServer
import re.ovo.adbbridge.bridge.BridgeStatus
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.util.appString

class BridgeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        listening = this
        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        if (listening === this) listening = null
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        if (listening === this) listening = null
    }

    override fun onClick() {
        super.onClick()
        if (!BridgeController.isPaired(this)) {
            openApp()
            return
        }
        BridgeController.toggle(this)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val content = tileContent()
        tile.state = content.state
        tile.icon = Icon.createWithResource(this, content.iconRes)
        tile.label = appString(content.labelRes)
        tile.subtitle = content.subtitleRes?.let { appString(it) }
        tile.updateTile()
    }

    /** 未配对、已配对未开启转发、已开启转发未连接、已连接各取一个图标与标题 */
    private fun tileContent(): TileContent {
        if (!BridgeController.isPaired(this)) {
            return TileContent(
                Tile.STATE_INACTIVE,
                R.drawable.ic_tile_unpaired,
                R.string.tile_label_unpaired,
            )
        }
        if (!BridgeStatus.running.value) {
            val portInUse = !BridgeServer.isPortFree(AppPrefs.wsPort.value)
            return TileContent(
                Tile.STATE_INACTIVE,
                R.drawable.ic_tile_idle,
                R.string.tile_label_off,
                if (portInUse) R.string.widget_port_in_use else null,
            )
        }
        return if (BridgeStatus.connected.value) {
            TileContent(
                Tile.STATE_ACTIVE,
                R.drawable.ic_tile_connected,
                R.string.tile_label_connected,
            )
        } else {
            TileContent(
                Tile.STATE_ACTIVE,
                R.drawable.ic_tile_waiting,
                R.string.tile_label_waiting,
            )
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    companion object {

        @Volatile
        private var listening: BridgeTileService? = null

        private val main = Handler(Looper.getMainLooper())

        /**
         * 面板展开期间磁贴已在监听，重复的监听请求会被系统忽略，此时直接刷新当前实例
         * 面板收起后没有实例，交给系统重新绑定并回调 onStartListening
         */
        fun refresh(context: Context) {
            val current = listening
            if (current == null) {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, BridgeTileService::class.java),
                )
            } else {
                main.post { if (listening === current) current.updateTile() }
            }
        }
    }
}

private class TileContent(
    val state: Int,
    val iconRes: Int,
    val labelRes: Int,
    val subtitleRes: Int? = null,
)
