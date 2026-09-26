package re.ovo.adbbridge.bridge

import android.content.Context
import android.util.Log
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import re.ovo.adbbridge.R
import re.ovo.adbbridge.data.LogCategory
import re.ovo.adbbridge.data.LogRepository
import re.ovo.adbbridge.util.appString
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 把每条 WebSocket 连接映射到一条独立的 adbd TLS 隧道，双向转发原始字节
 * 不解析 ADB 业务协议，仅统计流量并提取 A_OPEN 目标用于记录操作
 */
class BridgeServer(
    private val context: Context,
    private val port: Int,
    private val tunnelFactory: () -> AdbTunnel,
    private val verifyPassword: (String?) -> Boolean,
    private val authorize: suspend (String) -> Boolean,
    private val onSessionChanged: (ConnectionSnapshot?) -> Unit,
    private val onLog: (String?, LogCategory, String) -> Unit,
) : Closeable {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val activeTunnel = AtomicReference<AdbTunnel?>(null)
    private val sessionFree = AtomicBoolean(true)

    @Volatile
    private var activeJob: Job? = null

    private val BUSY_CODE: Short = 4001
    private val DENIED_CODE: Short = 4002
    private val UNAUTHORIZED_CODE: Short = 4003
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
                    val ws = this
                    val remote = remoteHost(ws)
                    val password = ws.call.request.queryParameters["password"]
                    if (!verifyPassword(password)) {
                        onLog(null, LogCategory.BRIDGE, context.appString(R.string.log_password_rejected, remote))
                        ws.close(CloseReason(UNAUTHORIZED_CODE, context.appString(R.string.ws_password_rejected)))
                        return@webSocket
                    }
                    if (!sessionFree.compareAndSet(true, false)) {
                        onLog(null, LogCategory.BRIDGE, context.appString(R.string.log_busy, remote))
                        ws.close(CloseReason(BUSY_CODE, context.appString(R.string.ws_busy)))
                        return@webSocket
                    }
                    // 先确认浏览器完成握手，浏览器提前断开时不留下无人使用的隧道
                    val first = readFirstPacket(ws)
                    if (first == null) {
                        sessionFree.set(true)
                        onLog(
                            null,
                            LogCategory.BRIDGE,
                            context.appString(R.string.log_handshake_incomplete, remote),
                        )
                        return@webSocket
                    }
                    if (!authorize(remote)) {
                        sessionFree.set(true)
                        ws.close(CloseReason(DENIED_CODE, context.appString(R.string.ws_denied)))
                        return@webSocket
                    }
                    val sessionId = LogRepository.openSession(remote)
                    val session = ConnectionSession(sessionId, remote)
                    activeJob = ws.coroutineContext[Job]
                    onLog(
                        sessionId,
                        LogCategory.BRIDGE,
                        context.appString(R.string.log_client_connected, remote),
                    )
                    var tunnel: AdbTunnel? = null
                    try {
                        val created = tunnelFactory.invoke()
                        tunnel = created
                        activeTunnel.set(created)
                        created.connect()
                        onSessionChanged(session.snapshot())
                        answerClientHandshake(ws, first, created, sessionId)

                        val upstream = launch {
                            val buffer = ByteArray(65536)
                            while (isActive) {
                                val read = created.input.read(buffer)
                                if (read <= 0) {
                                    Log.i(TAG, "上行结束：设备侧关闭")
                                    break
                                }
                                session.recordUp(read)
                                ws.send(Frame.Binary(true, buffer.copyOf(read)))
                            }
                        }
                        val downstream = launch {
                            val inspector = AdbPacketInspector(
                                onOpen = { destination ->
                                    describeDestination(destination)?.let {
                                        onLog(sessionId, LogCategory.ACTION, it)
                                    }
                                },
                                onSync = { request, path ->
                                    onLog(sessionId, LogCategory.ACTION, describeSyncRequest(request, path))
                                },
                            )
                            for (frame in incoming) {
                                if (frame !is Frame.Binary) continue
                                val bytes = frame.readBytes()
                                session.recordDown(bytes.size)
                                inspector.push(bytes, bytes.size)
                                created.output.write(bytes)
                                created.output.flush()
                            }
                            Log.i(TAG, "下行结束：浏览器关闭")
                        }
                        val sampler = launch {
                            while (isActive) {
                                delay(SAMPLE_INTERVAL_MS)
                                session.sample()
                                onSessionChanged(session.snapshot())
                            }
                        }
                        // 任一侧结束都关闭隧道并终止对端，阻塞中的读写随套接字关闭而退出
                        upstream.invokeOnCompletion {
                            runCatching { created.close() }
                            downstream.cancel()
                        }
                        downstream.invokeOnCompletion {
                            runCatching { created.close() }
                            upstream.cancel()
                        }
                        upstream.join()
                        downstream.join()
                        sampler.cancel()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        onLog(sessionId, LogCategory.BRIDGE, context.appString(R.string.log_connection_interrupted))
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "连接异常", e)
                        onLog(
                            sessionId,
                            LogCategory.BRIDGE,
                            context.appString(R.string.log_connection_error, e.message),
                        )
                    } finally {
                        val last = session.snapshot()
                        tunnel?.close()
                        activeTunnel.compareAndSet(tunnel, null)
                        activeJob = null
                        sessionFree.set(true)
                        onSessionChanged(null)
                        onLog(sessionId, LogCategory.BRIDGE, describeDisconnect(last))
                        LogRepository.closeSession(sessionId)
                    }
                }
            }
        }.start(wait = false)
        onLog(null, LogCategory.BRIDGE, context.appString(R.string.log_server_started, port))
    }

    /** 断开当前连接，隧道与统计随连接结束一并复位 */
    fun disconnectActive(): Boolean {
        val job = activeJob
        activeJob = null
        job?.cancel()
        return job != null
    }

    private fun remoteHost(session: DefaultWebSocketServerSession): String {
        return runCatching { session.call.request.local.remoteHost }
            .getOrDefault(context.appString(R.string.log_unknown_remote))
    }

    private fun describeDisconnect(last: ConnectionSnapshot): String {
        return context.appString(
            R.string.log_disconnect_summary,
            last.upBytes,
            last.downBytes,
            last.durationMs / 1000,
        )
    }

    private fun describeSyncRequest(request: String, path: String): String {
        val format = when (request) {
            "SEND" -> R.string.log_sync_send
            "RECV" -> R.string.log_sync_recv
            "LIST" -> R.string.log_sync_list
            else -> R.string.log_sync_stat
        }
        return context.appString(format, path)
    }

    /** 返回 null 表示这条目标不需要记录 */
    private fun describeDestination(destination: String): String? {
        val (format, target) = when {
            destination.startsWith("shell:") -> R.string.log_dest_shell to destination.removePrefix("shell:")
            destination.startsWith("exec:") -> R.string.log_dest_shell to destination.removePrefix("exec:")
            destination.startsWith("sync:") -> return null
            destination.startsWith("tcp:") -> R.string.log_dest_tcp to destination.removePrefix("tcp:")
            destination.startsWith("localabstract:") -> {
                R.string.log_dest_localabstract to destination.removePrefix("localabstract:")
            }
            destination.startsWith("dev:") -> R.string.log_dest_dev to destination.removePrefix("dev:")
            destination.startsWith("framebuffer:") -> return context.appString(R.string.log_dest_framebuffer)
            else -> R.string.log_dest_stream to destination
        }
        return context.appString(format, target)
    }

    /**
     * 浏览器首包是它自己的 CNXN，转发给设备会让 adbd 重建连接
     * 这条首包用设备已返回的横幅报文本地应答，不转发到设备
     */
    private suspend fun answerClientHandshake(
        session: DefaultWebSocketServerSession,
        first: ByteArray,
        tunnel: AdbTunnel,
        sessionId: String,
    ) {
        val isConnect = first.size >= 4 && intAt(first, 0) == ADB_COMMAND_CNXN
        if (!isConnect) {
            tunnel.output.write(first)
            tunnel.output.flush()
            Log.i(TAG, "首包非 CNXN，已按原样转发")
            return
        }
        Log.i(TAG, "设备横幅：${String(tunnel.bannerPacket.copyOfRange(24, tunnel.bannerPacket.size))}")
        session.send(Frame.Binary(true, tunnel.bannerPacket))
        onLog(sessionId, LogCategory.BRIDGE, context.appString(R.string.log_handshake_ok))
    }

    /**
     * 客户端把 ADB 包拆成多个 WebSocket 帧发送，按包长度收齐一个完整包再返回
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

    override fun close() {
        disconnectActive()
        server?.stop(0, 0)
        server = null
        onLog(null, LogCategory.BRIDGE, context.appString(R.string.log_server_stopped))
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 1000L
    }
}
