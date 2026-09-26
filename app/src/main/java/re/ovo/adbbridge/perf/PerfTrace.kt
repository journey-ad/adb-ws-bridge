package re.ovo.adbbridge.perf

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Printer
import android.view.Choreographer
import re.ovo.adbbridge.BuildConfig
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主线程采样入口：帧间隔、主线程消息阻塞、区段耗时
 * release 构建下 DEBUG 为常量 false，所有采样点连同聚合逻辑都会被编译器移除
 */
object PerfTrace {

    private const val TAG = "PerfTrace"
    private const val DISPATCH_PREFIX = ">>>>>"
    private const val FINISH_PREFIX = "<<<<<"
    private const val STACK_LIMIT = 6
    private const val WINDOW_LOG_MIN_MS = 0.5

    val enabled: Boolean = BuildConfig.DEBUG

    @PublishedApi
    internal val stats by lazy(LazyThreadSafetyMode.NONE) { PerfStats() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sampler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "adbbridge-perf").apply { isDaemon = true }
    }
    private val watching = AtomicBoolean(false)

    @Volatile
    private var dispatchStart = 0L

    @Volatile
    private var dispatchLabel = "main"

    @Volatile
    private var sampledStack: List<String> = emptyList()

    @Volatile
    private var pendingSample: ScheduledFuture<*>? = null

    private var lastFrameNanos = 0L
    private var windowName = ""
    private var windowStartMs = 0L
    private var windowCounters: Counters? = null

    /** 返回一个计时起点，enabled 为 false 时返回 0 */
    fun begin(): Long {
        return if (enabled) System.nanoTime() else 0L
    }

    fun end(name: String, startNanos: Long) {
        if (!enabled || startNanos == 0L) return
        stats.recordSection(name, (System.nanoTime() - startNanos) / 1_000_000.0)
    }

    inline fun <T> measure(name: String, block: () -> T): T {
        if (!enabled) return block()
        val startNanos = System.nanoTime()
        return try {
            block()
        } finally {
            stats.recordSection(name, (System.nanoTime() - startNanos) / 1_000_000.0)
        }
    }

    fun mark(name: String) {
        if (enabled) stats.recordMark(name)
    }

    /** 开始一段观测窗口，窗口结束时输出期间的帧与区段增量 */
    fun beginWindow(name: String) {
        if (!enabled) return
        windowName = name
        windowStartMs = System.currentTimeMillis()
        stats.resetWindowMax()
        windowCounters = stats.counters()
    }

    fun endWindow() {
        if (!enabled) return
        val name = windowName
        val start = windowCounters ?: return
        val elapsed = System.currentTimeMillis() - windowStartMs
        val now = stats.counters()
        windowName = ""
        windowCounters = null
        val jank = now.jankFrames - start.jankFrames
        Log.i(TAG, buildString {
            append("window=").append(name)
            append(" span=").append(elapsed).append("ms")
            append(" frames=").append(now.frames - start.frames)
            append(" jank=").append(jank)
            append(" maxFrame=").append(ms(now.windowMaxFrameMs))
            append(" blocks=").append(now.blocks - start.blocks)
            val sections = sectionDelta(start, now)
            if (sections.isNotEmpty()) {
                append(" | ")
                append(sections)
            }
        })
    }

    fun reset() {
        if (!enabled) return
        stats.reset()
        windowName = ""
        windowCounters = null
    }

    /** 主线程调用，安装帧回调与消息监听 */
    fun startWatch() {
        if (!enabled || !watching.compareAndSet(false, true)) return
        lastFrameNanos = 0L
        Looper.getMainLooper().setMessageLogging(looperPrinter)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stopWatch() {
        if (!watching.compareAndSet(true, false)) return
        Looper.getMainLooper().setMessageLogging(null)
        pendingSample?.cancel(false)
    }

    private fun sectionDelta(from: Counters, to: Counters): String {
        return to.sectionMs.entries
            .mapNotNull { (name, totalMs) ->
                val delta = totalMs - (from.sectionMs[name] ?: 0.0)
                if (delta < WINDOW_LOG_MIN_MS) null else name to delta
            }
            .sortedByDescending { it.second }
            .take(6)
            .joinToString(", ") { (name, deltaMs) -> "$name ${ms(deltaMs)}" }
    }

    private fun ms(value: Double): String = "%.1fms".format(Locale.US, value)

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val previous = lastFrameNanos
            lastFrameNanos = frameTimeNanos
            if (previous != 0L) stats.recordFrame((frameTimeNanos - previous) / 1_000_000.0)
            if (watching.get()) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val looperPrinter = Printer { line ->
        if (line.startsWith(DISPATCH_PREFIX)) {
            dispatchLabel = labelOf(line)
            dispatchStart = System.nanoTime()
            sampledStack = emptyList()
            pendingSample = sampler.schedule(
                ::sampleMainStack,
                PerfThresholds.BLOCK_MS.toLong(),
                TimeUnit.MILLISECONDS,
            )
            return@Printer
        }
        if (!line.startsWith(FINISH_PREFIX)) return@Printer
        pendingSample?.cancel(false)
        val startNanos = dispatchStart
        if (startNanos == 0L) return@Printer
        dispatchStart = 0L
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000.0
        if (durationMs >= PerfThresholds.BLOCK_MS) {
            stats.recordBlock(dispatchLabel, durationMs, sampledStack)
            Log.w(TAG, "block ${ms(durationMs)} $dispatchLabel\n${sampledStack.joinToString("\n") { "    at $it" }}")
        }
    }

    /** 超过阈值的消息由后台线程取一次主线程栈，其余消息不取栈 */
    private fun sampleMainStack() {
        val frames = Looper.getMainLooper().thread.stackTrace
        sampledStack = frames
            .asSequence()
            .filter { it.className.startsWith("re.ovo") || it.className.startsWith("androidx.compose") }
            .take(STACK_LIMIT)
            .map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            .toList()
    }

    /** 消息头里带 Handler 类名，用它区分阻塞来自界面、输入还是服务 */
    private fun labelOf(line: String): String {
        val start = line.indexOf('(')
        val end = line.indexOf(')')
        if (start < 0 || end <= start) return "main"
        return line.substring(start + 1, end).substringAfterLast('.')
    }

    private fun post(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
}
