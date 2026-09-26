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

private val localizedLock = Any()

@Volatile
private var localizedLanguage: AppLanguage? = null

@Volatile
private var localizedContext: Context? = null

/**
 * 应用级上下文不随界面重建，取文案时按当前选择重新包装
 * 日志与通知取文案的频率很高，包装结果按语言缓存，同一语言共用一份配置上下文
 */
fun Context.appString(resId: Int, vararg args: Any?): String {
    return localizedContext().getString(resId, *args)
}

private fun Context.localizedContext(): Context {
    val language = AppPrefs.language.value
    if (language == AppLanguage.SYSTEM) return applicationContext
    val cached = localizedContext
    if (cached != null && localizedLanguage == language) return cached
    synchronized(localizedLock) {
        val again = localizedContext
        if (again != null && localizedLanguage == language) return again
        val created = applicationContext.withAppLanguage(language)
        localizedLanguage = language
        localizedContext = created
        return created
    }
}
