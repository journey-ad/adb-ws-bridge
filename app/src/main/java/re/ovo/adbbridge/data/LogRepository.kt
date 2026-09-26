package re.ovo.adbbridge.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import re.ovo.adbbridge.perf.PerfTrace
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class LogCategory { BRIDGE, ACTION }

data class LogEntry(val time: Long, val category: LogCategory, val message: String, val seq: Long = 0)

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

/** 一行日志的编解码，反斜杠与换行转义后才能按行存放 */
internal object LogCodec {

    fun serialize(entry: LogEntry): String {
        val message = buildString(entry.message.length + 8) {
            for (char in entry.message) {
                when (char) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    else -> append(char)
                }
            }
        }
        return "${entry.time}|${entry.category.name}|$message"
    }

    fun parse(line: String, seq: Long = 0): LogEntry? {
        if (line.startsWith("#")) return null
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) return null
        val time = parts[0].toLongOrNull() ?: return null
        val category = LogCategory.entries.firstOrNull { it.name == parts[1] } ?: return null
        return LogEntry(time, category, unescape(parts[2]), seq)
    }

    private fun unescape(value: String): String {
        if (value.indexOf('\\') < 0) return value
        return buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char != '\\' || index + 1 >= value.length) {
                    append(char)
                    index++
                    continue
                }
                when (value[index + 1]) {
                    '\\' -> append('\\')
                    'n' -> append('\n')
                    else -> append(value[index + 1])
                }
                index += 2
            }
        }
    }
}

object LogRepository {

    const val APP_SESSION = "app"
    private const val DIR_NAME = "logs"
    private const val MAX_SESSIONS = 20
    private const val MAX_ENTRIES_PER_SESSION = 2000
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val PUBLISH_INTERVAL_MS = 150L

    private val idFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
    private lateinit var dir: File
    private val metas = HashMap<String, LogSession>()

    private val seq = AtomicLong()
    private val writeQueue = ArrayDeque<Pair<String, String>>()
    private val writeLock = Any()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "adbbridge-log").apply { isDaemon = true }
    }
    private val publisher = Handler(Looper.getMainLooper())
    private val publishPending = AtomicBoolean(false)

    val sessions = MutableStateFlow<List<LogSession>>(emptyList())
    val selectedId = MutableStateFlow(APP_SESSION)
    /** 正在记录的连接会话，界面上的「当前会话」标记以它为准，选中态由 selectedId 决定 */
    val activeId = MutableStateFlow<String?>(null)
    val entries = MutableStateFlow<List<LogEntry>>(emptyList())

    private var pendingEntries: List<LogEntry> = emptyList()

    @Synchronized
    fun init(context: Context) {
        dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        metas.clear()
        dir.listFiles()?.forEach { file ->
            readSession(file)?.let { metas[it.id] = it }
        }
        activeId.value = null
        if (!metas.containsKey(APP_SESSION)) {
            metas[APP_SESSION] = LogSession(APP_SESSION, System.currentTimeMillis(), 0L, 0, "")
        }
        publishSessions()
        pendingEntries = readEntries(fileOf(selectedId.value))
        entries.value = pendingEntries
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
        activeId.value = id
        appendText(id, "#$now|$remote\n")
        prune()
        publishSessions()
        select(id)
        return id
    }

    @Synchronized
    fun closeSession(id: String) {
        val session = metas[id] ?: return
        metas[id] = session.copy(endAt = System.currentTimeMillis())
        if (activeId.value == id) activeId.value = null
        publishSessions()
        prune()
    }

    /** 追加一条记录：内存状态同步更新，写盘与界面刷新都交给后台合并处理 */
    fun append(sessionId: String?, category: LogCategory, message: String) =
        PerfTrace.measure("log.append") { appendInternal(sessionId, category, message) }

    private fun appendInternal(sessionId: String?, category: LogCategory, message: String) {
        if (!AppPrefs.logEnabled.value) return
        val entry = LogEntry(System.currentTimeMillis(), category, message, seq.incrementAndGet())
        val id = sessionId ?: APP_SESSION
        val isSelected = synchronized(this) {
            val session = metas[id] ?: LogSession(id, entry.time, entry.time, 0, "")
            metas[id] = session.copy(endAt = entry.time, count = session.count + 1)
            if (selectedId.value == id) {
                pendingEntries = trim(pendingEntries + entry)
                true
            } else {
                false
            }
        }
        if (AppPrefs.logPersist.value) {
            synchronized(writeLock) { writeQueue.addLast(id to LogCodec.serialize(entry)) }
            writer.execute(::drainWrites)
        }
        if (isSelected) schedulePublish()
    }

    @Synchronized
    fun select(id: String) {
        selectedId.value = id
        pendingEntries = readEntries(fileOf(id))
        entries.value = pendingEntries
    }

    @Synchronized
    fun clear(id: String?) {
        if (id == null) {
            dir.listFiles()?.forEach { it.delete() }
            val now = System.currentTimeMillis()
            metas.clear()
            metas[APP_SESSION] = LogSession(APP_SESSION, now, now, 0, "")
            activeId.value = null
        } else {
            fileOf(id).delete()
            metas.remove(id)
            if (activeId.value == id) activeId.value = null
            if (id == APP_SESSION) {
                metas[APP_SESSION] = LogSession(APP_SESSION, System.currentTimeMillis(), 0L, 0, "")
            }
            if (selectedId.value !in metas) {
                selectedId.value = orderedSessions().first().id
            }
        }
        pendingEntries = readEntries(fileOf(selectedId.value))
        entries.value = pendingEntries
        publishSessions()
        prune()
    }

    private fun fileOf(id: String) = File(dir, "$id.log")

    private fun schedulePublish() {
        if (publishPending.compareAndSet(false, true)) {
            publisher.postDelayed({
                PerfTrace.measure("log.publish") {
                    publishPending.set(false)
                    val snapshot = synchronized(this) { pendingEntries }
                    entries.value = snapshot
                    publishSessions()
                }
            }, PUBLISH_INTERVAL_MS)
        }
    }

    private fun publishSessions() {
        sessions.value = orderedSessions()
    }

    /** 应用会话固定排在首位，连接会话按开始时间倒序 */
    private fun orderedSessions(): List<LogSession> {
        return metas.values.sortedWith(
            compareByDescending<LogSession> { it.isAppSession }.thenByDescending { it.startAt }
        )
    }

    private fun prune() {
        val removable = metas.values.filter { !it.isAppSession }.sortedByDescending { it.startAt }
        removable.drop(MAX_SESSIONS).forEach {
            fileOf(it.id).delete()
            metas.remove(it.id)
        }
    }

    private fun trim(list: List<LogEntry>): List<LogEntry> {
        return if (list.size > MAX_ENTRIES_PER_SESSION) list.takeLast(MAX_ENTRIES_PER_SESSION) else list
    }

    /**
     * 写盘在后台单线程完成：一次 drain 把队列里的记录按会话合并成单次追加
     * 文件超过上限时保留后半部分，会话文件大小维持在启动扫描可承受的范围
     */
    private fun drainWrites() = PerfTrace.measure("log.write") {
        val batch = LinkedHashMap<String, StringBuilder>()
        synchronized(writeLock) {
            while (writeQueue.isNotEmpty()) {
                val (id, line) = writeQueue.removeFirst()
                batch.getOrPut(id) { StringBuilder() }.append(line).append('\n')
            }
        }
        batch.forEach { (id, text) -> appendText(id, text.toString()) }
        batch.keys.forEach { id -> trimOversized(fileOf(id)) }
    }

    private fun appendText(id: String, text: String) {
        runCatching {
            FileWriter(fileOf(id), true).use { it.write(text) }
        }
    }

    private fun trimOversized(file: File) {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) return
        runCatching {
            val lines = file.readLines()
            file.writeText(lines.takeLast(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
    }

    /** 逐行扫描只保留首行与末行，统计信息不依赖完整文件内容 */
    private fun readSession(file: File): LogSession? {
        val id = file.nameWithoutExtension
        val reader = runCatching { BufferedReader(FileReader(file)) }.getOrNull() ?: return null
        reader.use {
            val head = it.readLine() ?: return null
            var last = head
            var count = 0
            while (true) {
                val line = it.readLine() ?: break
                if (line.isNotEmpty()) {
                    last = line
                    count++
                }
            }
            if (id == APP_SESSION) {
                val start = LogCodec.parse(head)?.time ?: file.lastModified()
                val end = LogCodec.parse(last)?.time ?: start
                return LogSession(id, start, end, count, "")
            }
            if (!head.startsWith("#")) return null
            val parts = head.removePrefix("#").split('|', limit = 2)
            val start = parts.getOrNull(0)?.toLongOrNull() ?: file.lastModified()
            val remote = parts.getOrNull(1) ?: ""
            val end = LogCodec.parse(last)?.time ?: start
            return LogSession(id, start, end, count, remote)
        }
    }

    /** 只返回最后若干条，长会话的完整历史不进入界面 */
    private fun readEntries(file: File): List<LogEntry> = PerfTrace.measure("log.readEntries") {
        val result = ArrayDeque<LogEntry>(MAX_ENTRIES_PER_SESSION)
        val reader = runCatching { BufferedReader(FileReader(file)) }.getOrNull() ?: return emptyList()
        reader.useLines { lines ->
            for (line in lines) {
                val entry = LogCodec.parse(line, seq.incrementAndGet()) ?: continue
                if (result.size >= MAX_ENTRIES_PER_SESSION) result.removeFirst()
                result.addLast(entry)
            }
        }
        return result.toList()
    }
}
