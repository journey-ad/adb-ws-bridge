package re.ovo.adbbridge.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import re.ovo.adbbridge.pairing.PairingManager
import re.ovo.adbbridge.tile.BridgeTileService
import re.ovo.adbbridge.widget.BridgeWidgetProvider

/** 界面之外的启停入口，快捷设置磁贴与桌面小组件共用 */
object BridgeController {

    fun start(context: Context) {
        context.startForegroundService(Intent(context, BridgeService::class.java))
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_STOP)
        )
    }

    fun toggle(context: Context) {
        if (BridgeStatus.running.value) stop(context) else start(context)
    }

    fun isPaired(context: Context): Boolean = PairingManager(context).isPaired

    /** 服务状态变化后刷新磁贴与小组件，force 为 false 时小组件按自身间隔节流 */
    fun refreshSurfaces(context: Context, force: Boolean = true) {
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, BridgeTileService::class.java),
            )
        }
        BridgeWidgetProvider.refresh(context, force)
    }
}
