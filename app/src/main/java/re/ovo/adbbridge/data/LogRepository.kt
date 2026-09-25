package re.ovo.adbbridge.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogCategory { BRIDGE, ACTION }

data class LogEntry(val time: Long, val category: LogCategory, val message: String)

/** 一次连接的日志归为一个会话，会话之外的记录归入应用会话 */
data class LogSession(
    val id: String,
    val startAt: Long,
    val endAt: Long,
    val count: Int,
    val remote: String,
) {
    val isAppSession: Boolean
        get() = id == LogRepository.APP_SESSION
}

object LogRepository {

    const val APP_SESSION = "app"
    private const val DIR_NAME = "logs"
    private const val MAX_SESSIONS = 40
    private const val MAX_ENTRIES_PER_SESSION = 5000

    private val idFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
    private lateinit var dir: File
    private val metas = HashMap<String, LogSession>()

    val sessions = MutableStateFlow<List<LogSession>>(emptyList())
    val selectedId = MutableStateFlow(APP_SESSION)
    val entries = MutableStateFlow<List<LogEntry>>(emptyList())

    @Synchronized
    fun init(context: Context) {
        dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        metas.clear()
        dir.listFiles()?.forEach { file ->
            readSession(file)?.let { metas[it.id] = it }
        }
        if (!metas.containsKey(APP_SESSION)) {
            metas[APP_SESSION] = LogSession(APP_SESSION, System.currentTimeMillis(), 0L, 0, "")
        }
        publishSessions()
        prune()
    }

    @Synchronized
    fun openSession(remote: String): String {
        val base = idFormat.format(Date())
        var id = base
        var index = 2
        while (metas.containsKey(id)) {
            id = "$base-$index"
            index++
        }
        val now = System.currentTimeMillis()
        metas[id] = LogSession(id, now, now, 0, remote)
        val file = fileOf(id)
        file.writeText("#$now|$remote\n")
        publishSessions()
        select(id)
        return id
    }

    @Synchronized
    fun closeSession(id: String) {
        val session = metas[id] ?: return
        metas[id] = session.copy(endAt = System.currentTimeMillis())
        publishSessions()
        prune()
    }

    @Synchronized
    fun append(sessionId: String?, category: LogCategory, message: String) {
        if (!AppPrefs.logEnabled.value) return
        val id = sessionId ?: APP_SESSION
        val time = System.currentTimeMillis()
        val session = metas[id] ?: LogSession(id, time, time, 0, "")
        metas[id] = session.copy(endAt = time, count = session.count + 1)
        if (AppPrefs.logPersist.value) {
            runCatching {
                fileOf(id).appendText(serialize(LogEntry(time, category, message)) + "\n")
            }
        }
        if (selectedId.value == id) {
            entries.value = trim(entries.value + LogEntry(time, category, message))
        }
        publishSessions()
    }

    @Synchronized
    fun select(id: String) {
        selectedId.value = id
        entries.value = readEntries(fileOf(id))
    }

    @Synchronized
    fun clear(id: String?) {
        if (id == null) {
            dir.listFiles()?.forEach { it.delete() }
            val now = System.currentTimeMillis()
            metas.clear()
            metas[APP_SESSION] = LogSession(APP_SESSION, now, now, 0, "")
        } else {
            fileOf(id).delete()
            metas.remove(id)
            if (id == APP_SESSION) {
                metas[APP_SESSION] = LogSession(APP_SESSION, System.currentTimeMillis(), 0L, 0, "")
            }
            if (selectedId.value !in metas) {
                selectedId.value = orderedSessions().first().id
            }
        }
        entries.value = readEntries(fileOf(selectedId.value))
        publishSessions()
        prune()
    }

    private fun fileOf(id: String) = File(dir, "$id.log")

    private fun publishSessions() {
        sessions.value = orderedSessions()
    }

    private fun orderedSessions(): List<LogSession> {
        return metas.values.sortedWith(
            compareByDescending<LogSession> { !it.isAppSession }.thenByDescending { it.startAt }
        )
    }

    private fun prune() {
        val removable = metas.values.filter { !it.isAppSession }.sortedByDescending { it.startAt }
        removable.drop(MAX_SESSIONS).forEach {
            fileOf(it.id).delete()
            metas.remove(it.id)
        }
    }

    private fun readSession(file: File): LogSession? {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val id = file.nameWithoutExtension
        if (id == APP_SESSION) {
            val items = lines.mapNotNull { parse(it) }
            val start = items.firstOrNull()?.time ?: file.lastModified()
            val end = items.lastOrNull()?.time ?: start
            return LogSession(id, start, end, items.size, "")
        }
        val head = lines.firstOrNull()?.takeIf { it.startsWith("#") } ?: return null
        val parts = head.removePrefix("#").split('|', limit = 2)
        val start = parts.getOrNull(0)?.toLongOrNull() ?: file.lastModified()
        val remote = parts.getOrNull(1) ?: ""
        val end = lines.lastOrNull()?.let { parse(it)?.time } ?: start
        return LogSession(id, start, end, lines.size - 1, remote)
    }

    private fun readEntries(file: File): List<LogEntry> {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return emptyList()
        return lines.mapNotNull { parse(it) }
    }

    private fun trim(list: List<LogEntry>): List<LogEntry> {
        return if (list.size > MAX_ENTRIES_PER_SESSION) list.takeLast(MAX_ENTRIES_PER_SESSION) else list
    }

    private fun serialize(entry: LogEntry): String {
        val message = entry.message.replace("\\", "\\\\").replace("\n", "\\n")
        return "${entry.time}|${entry.category.name}|$message"
    }

    private fun parse(line: String): LogEntry? {
        if (line.startsWith("#")) return null
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) return null
        val time = parts[0].toLongOrNull() ?: return null
        val category = LogCategory.entries.firstOrNull { it.name == parts[1] } ?: return null
        return LogEntry(time, category, parts[2].replace("\\n", "\n"))
    }
}
