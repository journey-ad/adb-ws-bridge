package re.ovo.adbbridge.util

import java.text.SimpleDateFormat
import java.util.Locale

private const val KILO = 1024.0

private val clockFormat: ThreadLocal<SimpleDateFormat> =
    ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

private val timestampFormat: ThreadLocal<SimpleDateFormat> =
    ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / KILO
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= KILO
        index++
    }
    val scaled = (value * 10).toLong()
    return "${scaled / 10}.${scaled % 10} ${units[index]}"
}

fun formatRate(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0) return "0 B/s"
    return "${formatBytes(bytesPerSecond)}/s"
}

fun formatDuration(millis: Long): String {
    val total = millis / 1000
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) {
        "$hours:${digits(minutes)}:${digits(seconds)}"
    } else {
        "${digits(minutes)}:${digits(seconds)}"
    }
}

/** 日志每行都要取时间，格式器按线程复用，同一线程共用一份日历与格式化实例 */
fun formatClock(timestamp: Long): String {
    return checkNotNull(clockFormat.get()).format(timestamp)
}

/** 完整到秒的时刻，日期带年份 */
fun formatTimestamp(timestamp: Long): String {
    return checkNotNull(timestampFormat.get()).format(timestamp)
}

private fun digits(value: Long): String {
    return if (value < 10) "0$value" else value.toString()
}
