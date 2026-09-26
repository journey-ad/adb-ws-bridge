package re.ovo.adbbridge.perf

import java.util.Locale
import kotlin.math.ceil

/** 采样判定阈值，单位毫秒 */
object PerfThresholds {
    /** 每帧的预算时长，启动时按屏幕刷新率改写 */
    var frameBudgetMs: Double = 16.7
    /** 帧间隔超过预算的这个倍数记为卡顿帧 */
    const val JANK_FACTOR = 2.0
    /** 主线程消息超过这个时长记为阻塞 */
    const val BLOCK_MS = 50.0
}

/** 一个区段的累计耗时 */
data class SectionStat(
    val name: String,
    val count: Int,
    val totalMs: Double,
    val maxMs: Double,
) {
    val avgMs: Double
        get() = if (count == 0) 0.0 else totalMs / count
}

/** 计数型采样，统计重组或切换发生的次数 */
data class MarkStat(val name: String, val count: Int)

/** 主线程上的一次阻塞，stack 为阻塞期间采样到的栈帧 */
data class BlockEvent(val name: String, val durationMs: Double, val stack: List<String>)

/** 帧间隔统计 */
data class FrameStat(
    val frames: Int,
    val jankFrames: Int,
    val maxMs: Double,
    val p95Ms: Double,
) {
    val jankRatio: Double
        get() = if (frames == 0) 0.0 else jankFrames.toDouble() / frames.toDouble()
}

data class PerfReport(
    val frames: FrameStat,
    val sections: List<SectionStat>,
    val marks: List<MarkStat>,
    val blocks: List<BlockEvent>,
) {

    /** 报告正文，供 logcat 与测试输出使用 */
    fun format(): String {
        val text = StringBuilder()
        text.append("frames=").append(frames.frames)
            .append(" jank=").append(frames.jankFrames)
            .append(" (").append(percent(frames.jankRatio)).append("%)")
            .append(" max=").append(ms(frames.maxMs))
            .append(" p95=").append(ms(frames.p95Ms))
            .append('\n')
        if (sections.isNotEmpty()) {
            text.append("sections:\n")
            sections.forEach {
                text.append("  ").append(it.name)
                    .append(" n=").append(it.count)
                    .append(" total=").append(ms(it.totalMs))
                    .append(" avg=").append(ms(it.avgMs))
                    .append(" max=").append(ms(it.maxMs))
                    .append('\n')
            }
        }
        if (marks.isNotEmpty()) {
            text.append("marks:\n")
            marks.forEach { text.append("  ").append(it.name).append(' ').append(it.count).append('\n') }
        }
        if (blocks.isNotEmpty()) {
            text.append("blocks:\n")
            blocks.forEach { event ->
                text.append("  ").append(ms(event.durationMs)).append(' ').append(event.name).append('\n')
                event.stack.forEach { frame -> text.append("    at ").append(frame).append('\n') }
            }
        }
        return text.toString().trimEnd()
    }

    private fun ms(value: Double): String = "%.1fms".format(Locale.US, value)

    private fun percent(ratio: Double): String = "%.1f".format(Locale.US, ratio * 100.0)
}

/** 累计计数，观测窗口用它做前后差值 */
data class Counters(
    val frames: Int,
    val jankFrames: Int,
    val maxFrameMs: Double,
    val windowMaxFrameMs: Double,
    val blocks: Int,
    val sectionMs: Map<String, Double>,
)

/** 采样聚合，只保留最近若干条样本，界面与测试都从这里取报告 */
class PerfStats {

    private val lock = Any()
    private val sections = LinkedHashMap<String, MutableSection>()
    private val marks = LinkedHashMap<String, Int>()
    private val frames = ArrayList<Double>()
    private val blocks = ArrayList<BlockEvent>()
    private var frameTotal = 0
    private var jankTotal = 0
    private var blockTotal = 0
    private var maxFrameMs = 0.0
    private var windowMaxFrameMs = 0.0

    fun recordSection(name: String, durationMs: Double) {
        synchronized(lock) {
            val section = sections.getOrPut(name) { MutableSection(name) }
            section.count++
            section.totalMs += durationMs
            if (durationMs > section.maxMs) section.maxMs = durationMs
        }
    }

    fun recordMark(name: String, delta: Int = 1) {
        synchronized(lock) { marks[name] = (marks[name] ?: 0) + delta }
    }

    fun recordFrame(durationMs: Double) {
        synchronized(lock) {
            frames.add(durationMs)
            if (frames.size > FRAME_HISTORY) frames.removeAt(0)
            frameTotal++
            if (durationMs > PerfThresholds.frameBudgetMs * PerfThresholds.JANK_FACTOR) jankTotal++
            if (durationMs > maxFrameMs) maxFrameMs = durationMs
            if (durationMs > windowMaxFrameMs) windowMaxFrameMs = durationMs
        }
    }

    /** 观测窗口开始时清零，窗口日志里报告的是这段时间内的最慢帧 */
    fun resetWindowMax() {
        synchronized(lock) { windowMaxFrameMs = 0.0 }
    }

    fun recordBlock(name: String, durationMs: Double, stack: List<String>) {
        synchronized(lock) {
            blocks.add(BlockEvent(name, durationMs, stack))
            if (blocks.size > BLOCK_HISTORY) blocks.removeAt(0)
            blockTotal++
        }
    }

    fun reset() {
        synchronized(lock) {
            sections.clear()
            marks.clear()
            frames.clear()
            blocks.clear()
            frameTotal = 0
            jankTotal = 0
            blockTotal = 0
            maxFrameMs = 0.0
            windowMaxFrameMs = 0.0
        }
    }

    fun counters(): Counters {
        synchronized(lock) {
            return Counters(
                frames = frameTotal,
                jankFrames = jankTotal,
                maxFrameMs = maxFrameMs,
                windowMaxFrameMs = windowMaxFrameMs,
                blocks = blockTotal,
                sectionMs = sections.entries.associate { it.key to it.value.totalMs },
            )
        }
    }

    fun report(): PerfReport {
        synchronized(lock) {
            val jankBudget = PerfThresholds.frameBudgetMs * PerfThresholds.JANK_FACTOR
            val sorted = frames.sorted()
            val p95Index = if (sorted.isEmpty()) 0 else ceil((sorted.size - 1) * P95).toInt()
            return PerfReport(
                frames = FrameStat(
                    frames = sorted.size,
                    jankFrames = sorted.count { it > jankBudget },
                    maxMs = sorted.lastOrNull() ?: 0.0,
                    p95Ms = sorted.getOrElse(p95Index) { 0.0 },
                ),
                sections = sections.values
                    .asSequence()
                    .filter { it.count > 0 }
                    .sortedByDescending { it.totalMs }
                    .take(SECTION_LIMIT)
                    .map { SectionStat(it.name, it.count, it.totalMs, it.maxMs) }
                    .toList(),
                marks = marks.entries
                    .sortedByDescending { it.value }
                    .map { MarkStat(it.key, it.value) }
                    .toList(),
                blocks = blocks
                    .sortedByDescending { it.durationMs }
                    .take(BLOCK_LIMIT),
            )
        }
    }

    private class MutableSection(val name: String) {
        var count = 0
        var totalMs = 0.0
        var maxMs = 0.0
    }

    private companion object {
        const val FRAME_HISTORY = 600
        const val BLOCK_HISTORY = 40
        const val SECTION_LIMIT = 12
        const val BLOCK_LIMIT = 8
        const val P95 = 0.95
    }
}
