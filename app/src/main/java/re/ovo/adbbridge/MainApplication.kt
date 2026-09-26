package re.ovo.adbbridge

import android.app.Application
import android.hardware.display.DisplayManager
import android.view.Display
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.data.LogRepository
import re.ovo.adbbridge.perf.PerfThresholds
import re.ovo.adbbridge.perf.PerfTrace

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        LogRepository.init(this)
        PerfThresholds.frameBudgetMs = frameBudgetMs()
        PerfTrace.startWatch()
    }

    /** 卡顿判定按屏幕实际刷新率，高刷屏上帧预算更短 */
    private fun frameBudgetMs(): Double {
        val manager = getSystemService(DisplayManager::class.java)
        val refreshRate = manager?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f
        return if (refreshRate <= 0f) 16.7 else 1000.0 / refreshRate.toDouble()
    }
}
