package re.ovo.adbbridge.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 连接密码与主题偏好，密码只保存加盐摘要 */
object AppPrefs {

    private const val FILE = "settings"
    private const val KEY_PASSWORD = "password_hash"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_LOG_ENABLED = "log_enabled"
    private const val KEY_LOG_PERSIST = "log_persist"
    private const val KEY_WS_PORT = "ws_port"
    private const val SALT = "adb-bridge:"

    const val DEFAULT_WS_PORT = 5556

    private lateinit var prefs: SharedPreferences

    val passwordHash = MutableStateFlow<String?>(null)
    val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val logEnabled = MutableStateFlow(true)
    val logPersist = MutableStateFlow(true)
    val wsPort = MutableStateFlow(DEFAULT_WS_PORT)

    fun init(context: Context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        passwordHash.value = prefs.getString(KEY_PASSWORD, null)
        themeMode.value = ThemeMode.entries.getOrElse(prefs.getInt(KEY_THEME, 0)) { ThemeMode.SYSTEM }
        logEnabled.value = prefs.getBoolean(KEY_LOG_ENABLED, true)
        logPersist.value = prefs.getBoolean(KEY_LOG_PERSIST, true)
        wsPort.value = prefs.getInt(KEY_WS_PORT, DEFAULT_WS_PORT)
    }

    val passwordEnabled: Boolean
        get() = passwordHash.value != null

    fun setPassword(password: String) {
        val hash = digest(password)
        prefs.edit().putString(KEY_PASSWORD, hash).apply()
        passwordHash.value = hash
    }

    fun clearPassword() {
        prefs.edit().remove(KEY_PASSWORD).apply()
        passwordHash.value = null
    }

    /** 未启用密码时任何输入都通过，启用后按摘要比对 */
    fun verify(password: String?): Boolean {
        val expected = passwordHash.value ?: return true
        val actual = password?.takeIf { it.isNotEmpty() }?.let { digest(it) } ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
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

    fun setWsPort(port: Int) {
        prefs.edit().putInt(KEY_WS_PORT, port).apply()
        wsPort.value = port
    }

    private fun digest(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest((SALT + password).toByteArray())
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
