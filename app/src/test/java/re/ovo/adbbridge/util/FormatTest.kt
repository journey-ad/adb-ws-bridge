package re.ovo.adbbridge.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    @Test
    fun bytesBelowKiloStayAsBytes() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun bytesUseBinaryUnits() {
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024 * 1024))
        assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun durationKeepsTwoDigitMinutes() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("01:05", formatDuration(65_000))
        assertEquals("59:59", formatDuration(3_599_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }

    @Test
    fun rateFallsBackToZero() {
        assertEquals("0 B/s", formatRate(0))
        assertEquals("0 B/s", formatRate(-1))
        assertEquals("1.0 KB/s", formatRate(1024))
    }

    @Test
    fun clockCoversTimeOnly() {
        assertEquals(8, formatClock(1_700_000_000_000L).length)
        assertTrue(formatClock(1_700_000_000_000L).matches(Regex("\\d{2}:\\d{2}:\\d{2}")))
    }

    @Test
    fun timestampCarriesYearAndSecond() {
        val text = formatTimestamp(1_700_000_000_000L)
        assertEquals(19, text.length)
        assertTrue(text.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
    }
}
