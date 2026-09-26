package re.ovo.adbbridge.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfStatsTest {

    @Test
    fun sectionAggregatesCountTotalAndMax() {
        val stats = PerfStats()
        stats.recordSection("log.filter", 4.0)
        stats.recordSection("log.filter", 6.0)

        val section = stats.report().sections.single { it.name == "log.filter" }
        assertEquals(2, section.count)
        assertEquals(10.0, section.totalMs, EPSILON)
        assertEquals(6.0, section.maxMs, EPSILON)
        assertEquals(5.0, section.avgMs, EPSILON)
    }

    @Test
    fun sectionsAreSortedByTotalTime() {
        val stats = PerfStats()
        stats.recordSection("small", 1.0)
        stats.recordSection("large", 20.0)

        val names = stats.report().sections.map { it.name }
        assertEquals(listOf("large", "small"), names)
    }

    @Test
    fun framesAreJudgedAgainstBudget() {
        val stats = PerfStats()
        PerfThresholds.frameBudgetMs = 10.0
        try {
            stats.recordFrame(8.0)
            stats.recordFrame(15.0)
            stats.recordFrame(30.0)
            val frames = stats.report().frames
            assertEquals(3, frames.frames)
            assertEquals(1, frames.jankFrames)
            assertEquals(30.0, frames.maxMs, EPSILON)
            assertEquals(30.0, frames.p95Ms, EPSILON)
        } finally {
            PerfThresholds.frameBudgetMs = 16.7
        }
    }

    @Test
    fun blocksKeepTheSlowestOnesWithStack() {
        val stats = PerfStats()
        val stack = listOf("LogScreen.invoke:112", "MainScreen.invoke:151")
        stats.recordBlock("Choreographer", 120.0, stack)
        stats.recordBlock("Handler", 60.0, emptyList())

        val blocks = stats.report().blocks
        assertEquals(120.0, blocks.first().durationMs, EPSILON)
        assertEquals(stack, blocks.first().stack)
        assertEquals(2, blocks.size)
    }

    @Test
    fun countersGiveWindowDelta() {
        val stats = PerfStats()
        val before = stats.counters()
        stats.recordSection("log.filter", 30.0)
        stats.recordFrame(200.0)
        stats.recordBlock("Choreographer", 90.0, emptyList())
        val after = stats.counters()

        assertEquals(1, after.frames - before.frames)
        assertEquals(1, after.jankFrames - before.jankFrames)
        assertEquals(1, after.blocks - before.blocks)
        assertEquals(30.0, (after.sectionMs["log.filter"] ?: 0.0) - (before.sectionMs["log.filter"] ?: 0.0), EPSILON)
    }

    @Test
    fun windowMaxFrameIsClearedOnDemand() {
        val stats = PerfStats()
        stats.recordFrame(90.0)
        stats.resetWindowMax()
        stats.recordFrame(20.0)

        assertEquals(20.0, stats.counters().windowMaxFrameMs, EPSILON)
        assertEquals(90.0, stats.counters().maxFrameMs, EPSILON)
    }

    @Test
    fun reportTextCoversEveryKindOfSample() {
        val stats = PerfStats()
        stats.recordSection("log.filter", 12.0)
        stats.recordMark("screen.switch")
        stats.recordFrame(90.0)
        stats.recordBlock("Choreographer", 90.0, listOf("LogScreen.invoke:112"))

        val text = stats.report().format()
        assertTrue(text.contains("jank="))
        assertTrue(text.contains("log.filter"))
        assertTrue(text.contains("screen.switch"))
        assertTrue(text.contains("LogScreen.invoke:112"))
    }

    @Test
    fun resetClearsEverything() {
        val stats = PerfStats()
        stats.recordSection("log.filter", 12.0)
        stats.recordFrame(90.0)
        stats.recordMark("screen.switch")
        stats.reset()

        val report = stats.report()
        assertTrue(report.sections.isEmpty())
        assertTrue(report.marks.isEmpty())
        assertEquals(0, report.frames.frames)
    }

    private companion object {
        const val EPSILON = 0.001
    }
}
