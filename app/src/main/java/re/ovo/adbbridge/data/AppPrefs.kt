package re.ovo.adbbridge.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppLanguage { SYSTEM, ENGLISH, CHINESE }

/** 连接密码与主题偏好，密码以明文保存以便复制完整的连接地址 */
object AppPrefs {

    private const val FILE = "settings"
    private const val KEY_PASSWORD = "password"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_LOG_ENABLED = "log_enabled"
    private const val KEY_LOG_PERSIST = "log_persist"
    private const val KEY_WS_PORT = "ws_port"
    private const val KEY_LANGUAGE = "language"

    const val DEFAULT_WS_PORT = 5556

    private lateinit var prefs: SharedPreferences

    val password = MutableStateFlow<String?>(null)
    val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val logEnabled = MutableStateFlow(true)
    val logPersist = MutableStateFlow(true)
    val wsPort = MutableStateFlow(DEFAULT_WS_PORT)
    val language = MutableStateFlow(AppLanguage.SYSTEM)

    fun init(context: Context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        password.value = prefs.getString(KEY_PASSWORD, null)
        themeMode.value = ThemeMode.entries.getOrElse(prefs.getInt(KEY_THEME, 0)) { ThemeMode.SYSTEM }
        logEnabled.value = prefs.getBoolean(KEY_LOG_ENABLED, true)
        logPersist.value = prefs.getBoolean(KEY_LOG_PERSIST, true)
        wsPort.value = prefs.getInt(KEY_WS_PORT, DEFAULT_WS_PORT)
        language.value = AppLanguage.entries.getOrElse(prefs.getInt(KEY_LANGUAGE, 0)) { AppLanguage.SYSTEM }
    }

    val passwordEnabled: Boolean
        get() = password.value != null

    fun setPassword(password: String) {
        prefs.edit().putString(KEY_PASSWORD, password).apply()
        this.password.value = password
    }

    fun clearPassword() {
        prefs.edit().remove(KEY_PASSWORD).apply()
        password.value = null
    }

    /** 未启用密码时任何输入都通过，启用后按明文比对 */
    fun verify(input: String?): Boolean {
        val expected = password.value ?: return true
        return input?.let { MessageDigest.isEqual(expected.toByteArray(), it.toByteArray()) } ?: false
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putInt(KEY_THEME, mode.ordinal).apply()
        themeMode.value = mode
    }

    fun setLogEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOG_ENABLED, enabled).apply()
        logEnabled.value = enabled
    }

    fun setLogPersist(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOG_PERSIST, enabled).apply()
        logPersist.value = enabled
    }

    fun setLanguage(language: AppLanguage) {
        prefs.edit().putInt(KEY_LANGUAGE, language.ordinal).apply()
        this.language.value = language
    }

    fun setWsPort(port: Int) {
        prefs.edit().putInt(KEY_WS_PORT, port).apply()
        wsPort.value = port
    }
}
