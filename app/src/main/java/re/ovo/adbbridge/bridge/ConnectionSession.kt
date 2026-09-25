package re.ovo.adbbridge.bridge

import java.util.concurrent.atomic.AtomicLong

data class ConnectionSnapshot(
    val id: String,
    val remote: String,
    val startedAt: Long,
    val durationMs: Long,
    val upBytes: Long,
    val downBytes: Long,
    val upRate: Long,
    val downRate: Long,
)

/** 一条连接的流量与计时，速率按采样间隔计算 */
class ConnectionSession(val id: String, val remote: String) {

    private val startedAt = System.currentTimeMillis()
    private val upBytes = AtomicLong()
    private val downBytes = AtomicLong()

    private var sampledAt = startedAt
    private var sampledUp = 0L
    private var sampledDown = 0L

    @Volatile
    private var currentUpRate = 0L

    @Volatile
    private var currentDownRate = 0L

    fun recordUp(size: Int) {
        upBytes.addAndGet(size.toLong())
    }

    fun recordDown(size: Int) {
        downBytes.addAndGet(size.toLong())
    }

    fun sample() {
        val now = System.currentTimeMillis()
        val elapsed = now - sampledAt
        if (elapsed <= 0) return
        val up = upBytes.get()
        val down = downBytes.get()
        currentUpRate = (up - sampledUp) * 1000 / elapsed
        currentDownRate = (down - sampledDown) * 1000 / elapsed
        sampledAt = now
        sampledUp = up
        sampledDown = down
    }

    fun snapshot(): ConnectionSnapshot {
        val now = System.currentTimeMillis()
        return ConnectionSnapshot(
            id = id,
            remote = remote,
            startedAt = startedAt,
            durationMs = now - startedAt,
            upBytes = upBytes.get(),
            downBytes = downBytes.get(),
            upRate = currentUpRate,
            downRate = currentDownRate,
        )
    }
}
