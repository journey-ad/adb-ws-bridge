package re.ovo.adbbridge.bridge

/**
 * 从下行字节里还原 ADB 报文边界，提取 A_OPEN 的目标串与 sync 流的请求用于记录操作
 * 报文本身不受影响，仍由调用方原样转发
 */
class AdbPacketInspector(
    private val onOpen: (String) -> Unit,
    private val onSync: (String, String) -> Unit,
) {

    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0

    fun push(bytes: ByteArray, length: Int) {
        ensureCapacity(size + length)
        System.arraycopy(bytes, 0, buffer, size, length)
        size += length
        drain()
    }

    private fun drain() {
        var offset = 0
        while (size - offset >= HEADER_SIZE) {
            val command = intAt(buffer, offset)
            val payloadLength = intAt(buffer, offset + PAYLOAD_LENGTH_OFFSET)
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
                size = 0
                return
            }
            val total = HEADER_SIZE + payloadLength
            if (size - offset < total) break
            if (command == COMMAND_OPEN && payloadLength in 1..MAX_DESTINATION) {
                val destination = String(buffer, offset + HEADER_SIZE, payloadLength, Charsets.UTF_8)
                    .trimEnd('\u0000')
                describe(destination)?.let(onOpen)
            } else if (command == COMMAND_WRITE) {
                readSyncRequest(buffer, offset + HEADER_SIZE, payloadLength)?.let { (request, path) ->
                    onSync(request, path)
                }
            }
            offset += total
        }
        if (offset > 0) {
            System.arraycopy(buffer, offset, buffer, 0, size - offset)
            size -= offset
        }
    }

    /** sync 目标不含路径，文件路径来自后续的请求报文 */
    private fun describe(destination: String): String? {
        return if (destination.startsWith(SYNC_DESTINATION)) null else destination
    }

    /** sync 请求的载荷是四字节命令、四字节路径长度与路径本身 */
    private fun readSyncRequest(bytes: ByteArray, start: Int, length: Int): Pair<String, String>? {
        if (length < SYNC_HEADER_SIZE) return null
        val request = String(bytes, start, SYNC_COMMAND_SIZE, Charsets.US_ASCII)
        if (request !in SYNC_REQUESTS) return null
        val pathLength = intAt(bytes, start + SYNC_COMMAND_SIZE)
        if (pathLength < 1 || SYNC_HEADER_SIZE + pathLength > length) return null
        val path = String(bytes, start + SYNC_HEADER_SIZE, pathLength, Charsets.UTF_8).trimEnd('\u0000')
        return request to path
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var capacity = buffer.size
        while (capacity < required) {
            capacity *= 2
        }
        buffer = buffer.copyOf(capacity)
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }

    companion object {
        private const val HEADER_SIZE = 24
        private const val PAYLOAD_LENGTH_OFFSET = 12
        private const val COMMAND_OPEN = 0x4e45504f
        private const val COMMAND_WRITE = 0x45545257
        private const val SYNC_COMMAND_SIZE = 4
        private const val SYNC_HEADER_SIZE = 8
        private const val SYNC_DESTINATION = "sync:"
        private val SYNC_REQUESTS = setOf("SEND", "RECV", "LIST", "STAT")
        private const val MAX_PAYLOAD = 8 * 1024 * 1024
        private const val MAX_DESTINATION = 4096
        private const val INITIAL_CAPACITY = 8192
    }
}
