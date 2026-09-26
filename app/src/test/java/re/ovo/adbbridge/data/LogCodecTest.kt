package re.ovo.adbbridge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogCodecTest {

    @Test
    fun plainMessageRoundTrips() {
        val entry = LogEntry(1_700_000_000_000L, LogCategory.ACTION, "shell:ls /sdcard", 1)
        val parsed = LogCodec.parse(LogCodec.serialize(entry), 1)
        assertEquals(entry, parsed)
    }

    @Test
    fun backslashSurvivesRoundTrip() {
        val entry = LogEntry(1L, LogCategory.BRIDGE, """path C:\temp\a\b""", 2)
        val parsed = LogCodec.parse(LogCodec.serialize(entry), 2)
        assertEquals(entry.message, parsed?.message)
        assertEquals(entry, parsed)
    }

    @Test
    fun newlineAndPipeArePreserved() {
        val entry = LogEntry(2L, LogCategory.BRIDGE, "first\nsecond|third", 3)
        val parsed = LogCodec.parse(LogCodec.serialize(entry), 3)
        assertEquals(entry.message, parsed?.message)
    }

    @Test
    fun sessionHeaderIsNotAnEntry() {
        assertNull(LogCodec.parse("#1700000000000|192.168.0.2", 4))
    }

    @Test
    fun malformedLineIsRejected() {
        assertNull(LogCodec.parse("abc|BRIDGE|message", 5))
        assertNull(LogCodec.parse("12|UNKNOWN|message", 6))
        assertNull(LogCodec.parse("12|BRIDGE", 7))
    }
}
