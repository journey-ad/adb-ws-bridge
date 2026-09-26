package re.ovo.adbbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbPacketInspectorTest {

    private fun packet(command: Int, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(1)
        buffer.putInt(2)
        buffer.putInt(payload.size)
        buffer.putInt(0)
        buffer.putInt(command xor -0x1)
        buffer.put(payload)
        return buffer.array()
    }

    private fun push(inspector: AdbPacketInspector, bytes: ByteArray) {
        inspector.push(bytes, bytes.size)
    }

    @Test
    fun openDestinationIsReported() {
        val opened = mutableListOf<String>()
        val inspector = AdbPacketInspector(onOpen = { opened.add(it) }, onSync = { _, _ -> })
        push(inspector, packet(0x4e45504f, "shell:ls\u0000".toByteArray()))
        assertEquals(listOf("shell:ls"), opened)
    }

    @Test
    fun syncDestinationIsNotReportedAsOpen() {
        val opened = mutableListOf<String>()
        val inspector = AdbPacketInspector(onOpen = { opened.add(it) }, onSync = { _, _ -> })
        push(inspector, packet(0x4e45504f, "sync:\u0000".toByteArray()))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun packetSplitAcrossPushesIsReassembled() {
        val opened = mutableListOf<String>()
        val inspector = AdbPacketInspector(onOpen = { opened.add(it) }, onSync = { _, _ -> })
        val bytes = packet(0x4e45504f, "shell:pm list packages\u0000".toByteArray())
        val half = bytes.size / 2
        inspector.push(bytes.copyOfRange(0, half), half)
        assertTrue(opened.isEmpty())
        inspector.push(bytes.copyOfRange(half, bytes.size), bytes.size - half)
        assertEquals(listOf("shell:pm list packages"), opened)
    }

    @Test
    fun syncRequestIsDecoded() {
        val sync = mutableListOf<String>()
        val inspector = AdbPacketInspector(onOpen = {}, onSync = { request, path -> sync.add("$request|$path") })
        val payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .put("SEND".toByteArray(Charsets.US_ASCII))
            .putInt(5)
            .put("/sdca".toByteArray())
            .array()
        push(inspector, packet(0x45545257, payload))
        assertEquals(listOf("SEND|/sdca"), sync)
    }

    @Test
    fun oversizedPayloadLengthResetsBuffer() {
        val opened = mutableListOf<String>()
        val inspector = AdbPacketInspector(onOpen = { opened.add(it) }, onSync = { _, _ -> })
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x4e45504f)
        header.putInt(0)
        header.putInt(0)
        header.putInt(32 * 1024 * 1024)
        header.putInt(0)
        header.putInt(0x4e45504f xor -0x1)
        push(inspector, header.array())
        assertTrue(opened.isEmpty())
        push(inspector, packet(0x4e45504f, "shell:id\u0000".toByteArray()))
        assertEquals(listOf("shell:id"), opened)
    }
}
