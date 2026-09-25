package re.ovo.adbbridge.bridge

import android.util.Log
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 把每条 WebSocket 连接映射到一条独立的 adbd TLS 隧道，双向转发原始字节
 * 不解析 ADB 业务协议
 */
class BridgeServer(
    private val port: Int,
    private val tunnelFactory: () -> AdbTunnel,
    private val onConnectionChanged: (Int) -> Unit,
    private val onLog: (String) -> Unit,
) : Closeable {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val connections = AtomicInteger(0)
    private val activeTunnel = AtomicReference<AdbTunnel?>(null)
    private val sessionFree = AtomicBoolean(true)

    private val BUSY_CODE: Short = 4001
    private val ADB_COMMAND_CNXN = 0x4e584e43
    private val TAG = "BridgeServer"
    private val ADB_HEADER_SIZE = 24

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            routing {
                webSocket("/adb") {
                    val session = this
                    if (!sessionFree.compareAndSet(true, false)) {
                        onLog("已有连接占用，拒绝新连接")
                        close(CloseReason(BUSY_CODE, "已有连接占用"))
                        return@webSocket
                    }
                    var tunnel: AdbTunnel? = null
                    try {
                        // 先确认浏览器完成握手，避免浏览器提前断开时留下无人使用的隧道
                        val first = readFirstPacket(session)
                        if (first == null) {
                            onLog("浏览器未完成握手")
                            return@webSocket
                        }
                        tunnel = tunnelFactory.invoke()
                        activeTunnel.set(tunnel)
                        connections.set(1)
                        onConnectionChanged(1)
                        onLog("新连接")
                        tunnel.connect()
                        answerClientHandshake(session, first, tunnel)
                        val upstream = launch {
                            val buffer = ByteArray(65536)
                            while (isActive) {
                                val read = tunnel.input.read(buffer)
                                if (read <= 0) {
                                    Log.i(TAG, "上行结束：设备侧关闭")
                                    break
                                }
                                Log.i(TAG, "上行 $read 字节 ${describe(buffer, read)}")
                                send(Frame.Binary(true, buffer.copyOf(read)))
                            }
                        }
                        val downstream = launch {
                            for (frame in incoming) {
                                if (frame !is Frame.Binary) continue
                                val bytes = frame.readBytes()
                                Log.i(TAG, "下行 ${bytes.size} 字节 command=${if (bytes.size >= 4) intAt(bytes, 0).toString(16) else "-"}")
                                tunnel.output.write(bytes)
                                tunnel.output.flush()
                            }
                            Log.i(TAG, "下行结束：浏览器关闭")
                        }
                        upstream.join()
                        downstream.cancel()
                    } catch (e: Exception) {
                        Log.e(TAG, "连接异常", e)
                        onLog("连接异常：${e.message}")
                    } finally {
                        tunnel?.close()
                        activeTunnel.compareAndSet(tunnel, null)
                        sessionFree.set(true)
                        connections.set(0)
                        onConnectionChanged(0)
                        onLog("连接结束")
                    }
                }
            }
        }.start(wait = false)
        onLog("转发服务已启动，端口 $port")
    }

    /**
     * 浏览器首包是它自己的 CNXN，转发给设备会让 adbd 重建连接
     * 因此用设备已回给我们的横幅报文本地应答，首包不再下行
     */
    private suspend fun answerClientHandshake(
        session: DefaultWebSocketServerSession,
        first: ByteArray,
        tunnel: AdbTunnel,
    ) {
        val isConnect = first.size >= 4 && intAt(first, 0) == ADB_COMMAND_CNXN
        if (!isConnect) {
            tunnel.output.write(first)
            tunnel.output.flush()
            Log.i(TAG, "首包非 CNXN，已按原样下行")
            return
        }
        Log.i(TAG, "设备横幅：${String(tunnel.bannerPacket.copyOfRange(24, tunnel.bannerPacket.size))}")
        session.send(Frame.Binary(true, tunnel.bannerPacket))
        onLog("已本地应答浏览器握手")
    }

    /**
     * 客户端把 ADB 包拆成多个 WebSocket 帧发送，按包长度攒够一个完整包再返回
     */
    private suspend fun readFirstPacket(session: DefaultWebSocketServerSession): ByteArray? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val frame = try {
                session.incoming.receive()
            } catch (e: Exception) {
                return null
            }
            if (frame !is Frame.Binary) continue
            buffer.write(frame.readBytes())
            val bytes = buffer.toByteArray()
            if (bytes.size < ADB_HEADER_SIZE) continue
            val payloadLength = intAt(bytes, 12)
            if (bytes.size >= ADB_HEADER_SIZE + payloadLength) {
                Log.i(TAG, "浏览器首包共 ${bytes.size} 字节")
                return bytes
            }
        }
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun describe(bytes: ByteArray, size: Int): String {
        if (size < ADB_HEADER_SIZE) return "-"
        return "command=${intAt(bytes, 0).toString(16)} arg0=${intAt(bytes, 4)} arg1=${intAt(bytes, 8)} len=${intAt(bytes, 12)}"
    }

    override fun close() {
        server?.stop(0, 0)
        server = null
        onLog("转发服务已停止")
    }
}
