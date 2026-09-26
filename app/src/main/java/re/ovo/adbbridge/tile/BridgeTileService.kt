package re.ovo.adbbridge.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
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
        updateTile()
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
        val running = BridgeStatus.running.value
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_bridge_tile)
        tile.label = appString(R.string.app_name)
        tile.subtitle = when {
            !BridgeController.isPaired(this) -> appString(R.string.status_unpaired)
            running && BridgeStatus.connected.value -> appString(R.string.status_connected)
            running -> appString(R.string.widget_waiting)
            !BridgeServer.isPortFree(AppPrefs.wsPort.value) -> appString(R.string.widget_port_in_use)
            else -> appString(R.string.status_stopped)
        }
        tile.updateTile()
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
}
