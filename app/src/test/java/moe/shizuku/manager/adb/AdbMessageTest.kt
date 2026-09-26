package moe.shizuku.manager.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbMessageTest {

    @Test
    fun headerIsLittleEndianAndCarriesPayload() {
        val message = AdbMessage(AdbProtocol.A_OPEN, 7, 9, "shell:ls")
        val bytes = message.toByteArray()
        assertEquals(24 + 9, bytes.size)
        assertEquals(AdbProtocol.A_OPEN, intAt(bytes, 0))
        assertEquals(7, intAt(bytes, 4))
        assertEquals(9, intAt(bytes, 12))
    }

    @Test
    fun stringConstructorAppendsTerminator() {
        val bytes = AdbMessage(AdbProtocol.A_OPEN, 1, 0, "dev:null").toByteArray()
        assertEquals(0.toByte(), bytes[bytes.size - 1])
    }

    @Test
    fun validateAcceptsWellFormedMessage() {
        val message = AdbMessage(AdbProtocol.A_WRTE, 3, 4, "payload")
        assertTrue(message.validate())
    }

    @Test
    fun validateRejectsBrokenMagic() {
        val valid = AdbMessage(AdbProtocol.A_WRTE, 3, 4, "payload")
        val broken = AdbMessage(valid.command, valid.arg0, valid.arg1, valid.data_length, valid.data_crc32, 0, valid.data)
        assertFalse(broken.validate())
    }

    @Test
    fun payloadRoundTrips() {
        val payload = byteArrayOf(0x01, 0x02, 0x7f, 0x00)
        val message = AdbMessage(AdbProtocol.A_OKAY, 1, 2, payload)
        assertArrayEquals(payload, message.data)
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }
}
