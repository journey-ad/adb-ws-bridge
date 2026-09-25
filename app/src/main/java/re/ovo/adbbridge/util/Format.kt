package re.ovo.adbbridge.util

import java.util.Locale

private const val KILO = 1024.0

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / KILO
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= KILO
        index++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
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
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

fun formatClock(timestamp: Long): String {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    return String.format(
        Locale.getDefault(),
        "%02d:%02d:%02d",
        calendar.get(java.util.Calendar.HOUR_OF_DAY),
        calendar.get(java.util.Calendar.MINUTE),
        calendar.get(java.util.Calendar.SECOND),
    )
}

fun formatDateTime(timestamp: Long): String {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    return String.format(
        Locale.getDefault(),
        "%02d-%02d %02d:%02d",
        calendar.get(java.util.Calendar.MONTH) + 1,
        calendar.get(java.util.Calendar.DAY_OF_MONTH),
        calendar.get(java.util.Calendar.HOUR_OF_DAY),
        calendar.get(java.util.Calendar.MINUTE),
    )
}
