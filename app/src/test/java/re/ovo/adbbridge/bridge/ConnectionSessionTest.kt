package re.ovo.adbbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSessionTest {

    @Test
    fun trafficIsAccumulated() {
        val session = ConnectionSession("id", "192.168.0.2")
        session.recordUp(120)
        session.recordDown(340)
        val snapshot = session.snapshot()
        assertEquals(120L, snapshot.upBytes)
        assertEquals(340L, snapshot.downBytes)
        assertEquals("192.168.0.2", snapshot.remote)
        assertTrue(snapshot.durationMs >= 0)
    }

    @Test
    fun rateIsDerivedFromSamplingWindow() {
        val session = ConnectionSession("id", "192.168.0.2")
        session.recordUp(4096)
        Thread.sleep(30)
        session.recordUp(4096)
        val before = session.snapshot()
        assertEquals(0L, before.upRate)

        session.sample()
        val after = session.snapshot()
        assertTrue(after.upRate > 0)
        assertEquals(0L, after.downRate)
    }

    @Test
    fun rateDropsWhenSamplingWindowHasNoTraffic() {
        val session = ConnectionSession("id", "192.168.0.2")
        session.recordUp(1024)
        Thread.sleep(20)
        session.sample()
        assertTrue(session.snapshot().upRate > 0)
        Thread.sleep(20)
        session.sample()
        assertEquals(0L, session.snapshot().upRate)
    }
}
