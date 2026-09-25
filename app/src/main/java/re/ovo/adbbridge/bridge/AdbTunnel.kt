package re.ovo.adbbridge.bridge

import android.util.Log
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMessage
import moe.shizuku.manager.adb.AdbProtocol
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * 一条到 adbd connect 端口的连接：先握手再升级为 TLS，之后交给调用方透传字节
 * 握手阶段的报文由本类消费，上层只处理隧道建立之后的原始流
 */
class AdbTunnel(
    private val host: String,
    private val port: Int,
    private val key: AdbKey,
) : Closeable {

    private lateinit var plainSocket: Socket
    private lateinit var sslSocket: SSLSocket

    lateinit var input: InputStream
        private set
    lateinit var output: OutputStream
        private set

    /** 设备回给客户端的 CNXN 原始报文，用于本地应答浏览器握手 */
    lateinit var bannerPacket: ByteArray
        private set

    private var lastRaw: ByteArray? = null

    fun connect() {
        Log.i(TAG, "连接隧道 $host:$port")
        plainSocket = Socket()
        plainSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        plainSocket.tcpNoDelay = true
        plainSocket.soTimeout = HANDSHAKE_TIMEOUT_MS

        val plainInput = DataInputStream(plainSocket.getInputStream())
        val plainOutput = DataOutputStream(plainSocket.getOutputStream())

        write(plainOutput, AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, MAX_PAYLOAD, SYSTEM_IDENTITY))
        Log.i(TAG, "已发送 CNXN")

        val response = read(plainInput)
        Log.i(TAG, "收到报文 command=${response.command.toString(16)}")
        if (response.command != AdbProtocol.A_STLS) {
            throw IOException("设备未要求 TLS 连接")
        }

        write(plainOutput, AdbMessage(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0, null as ByteArray?))
        Log.i(TAG, "已发送 STLS，开始 TLS 握手")

        sslSocket = key.sslContext.socketFactory.createSocket(plainSocket, host, port, true) as SSLSocket
        sslSocket.startHandshake()
        Log.i(TAG, "TLS 握手完成：${sslSocket.session.protocol} ${sslSocket.session.cipherSuite}")

        input = sslSocket.inputStream
        output = sslSocket.outputStream

        val banner = read(DataInputStream(input))
        Log.i(TAG, "握手末包 command=${banner.command.toString(16)}")
        if (banner.command != AdbProtocol.A_CNXN) {
            throw IOException("未收到设备标识报文")
        }
        bannerPacket = lastRaw ?: throw IOException("未取到设备标识报文原始字节")
        Log.i(TAG, "设备横幅：${String(banner.data ?: ByteArray(0)).trim()}")
        plainSocket.soTimeout = 0
        Log.i(TAG, "隧道就绪")
    }

    /**
     * 自检：在已建立的隧道上执行一条 shell 命令，确认设备接受这条会话
     */
    fun selfTest(): String {
        val out = DataOutputStream(output)
        val input = DataInputStream(input)
        out.write(AdbMessage(AdbProtocol.A_OPEN, 1, 0, "shell:echo tunnel-ok").toByteArray())
        out.flush()
        Log.i(TAG, "自检已发送 OPEN")

        val result = StringBuilder()
        repeat(8) {
            val message = read(input)
            Log.i(TAG, "自检收到 command=${message.command.toString(16)} arg0=${message.arg0} len=${message.data_length}")
            when (message.command) {
                AdbProtocol.A_OKAY -> Unit
                AdbProtocol.A_WRTE -> {
                    message.data?.let { result.append(String(it)) }
                    out.write(AdbMessage(AdbProtocol.A_OKAY, 1, message.arg0, null as ByteArray?).toByteArray())
                    out.flush()
                }
                AdbProtocol.A_CLSE -> {
                    out.write(AdbMessage(AdbProtocol.A_CLSE, 1, message.arg0, null as ByteArray?).toByteArray())
                    out.flush()
                    return result.toString()
                }
                else -> Unit
            }
        }
        return result.toString()
    }

    private fun write(stream: DataOutputStream, message: AdbMessage) {
        stream.write(message.toByteArray())
        stream.flush()
    }

    private fun read(stream: DataInputStream): AdbMessage {
        val header = ByteArray(24)
        stream.readFully(header)
        val payloadLength = header.intAt(12)
        val payload = if (payloadLength > 0) ByteArray(payloadLength) else null
        if (payload != null) {
            stream.readFully(payload)
        }
        lastRaw = if (payload != null) header + payload else header
        return AdbMessage(
            header.intAt(0),
            header.intAt(4),
            header.intAt(8),
            payloadLength,
            header.intAt(16),
            header.intAt(20),
            payload,
        )
    }

    private fun ByteArray.intAt(offset: Int): Int {
        return (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
    }

    override fun close() {
        runCatching { if (::sslSocket.isInitialized) sslSocket.close() }
        runCatching { if (::plainSocket.isInitialized) plainSocket.close() }
    }

    companion object {
        private const val TAG = "AdbTunnel"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val HANDSHAKE_TIMEOUT_MS = 15_000

        private const val MAX_PAYLOAD = 1024 * 1024

        /**
         * 握手时声明的能力列表
         * 不含 delayed_ack：隧道只透传字节，无法替客户端维护延迟确认所需的收发计数
         * 未协商延迟确认时，adbd 要求 OPEN 报文的初始窗口为 0
         */
        private val SYSTEM_IDENTITY = "host::features=" + listOf(
            "shell_v2",
            "cmd",
            "stat_v2",
            "ls_v2",
            "fixed_push_mkdir",
            "abb",
            "abb_exec",
            "sendrecv_v2",
            "sendrecv_v2_brotli",
            "sendrecv_v2_lz4",
            "sendrecv_v2_zstd",
            "sendrecv_v2_dry_run_send",
        ).joinToString(",")
    }
}
