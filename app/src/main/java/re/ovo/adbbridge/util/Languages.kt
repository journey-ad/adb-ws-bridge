package re.ovo.adbbridge.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import re.ovo.adbbridge.data.AppLanguage
import re.ovo.adbbridge.data.AppPrefs
import java.util.Locale

/** 显式选择的语言覆盖系统语言，跟随系统时沿用系统的语言配置 */
fun Context.withAppLanguage(language: AppLanguage): Context {
    val locale = when (language) {
        AppLanguage.SYSTEM -> return this
        AppLanguage.ENGLISH -> Locale.ENGLISH
        AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
    }
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList(locale))
    return createConfigurationContext(config)
}

/** 应用级上下文不随界面重建，取文案时按当前选择重新包装 */
fun Context.appString(resId: Int, vararg args: Any?): String {
    return applicationContext.withAppLanguage(AppPrefs.language.value).getString(resId, *args)
}
