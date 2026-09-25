package re.ovo.adbbridge

import android.app.Application
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.data.LogRepository

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        LogRepository.init(this)
    }
}
