package re.ovo.adbbridge.bridge

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
import re.ovo.adbbridge.data.LogCategory
import re.ovo.adbbridge.data.LogRepository
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 把每条 WebSocket 连接映射到一条独立的 adbd TLS 隧道，双向转发原始字节
 * 不解析 ADB 业务协议，仅统计流量并提取 A_OPEN 目标用于记录操作
 */
class BridgeServer(
    private val port: Int,
    private val tunnelFactory: () -> AdbTunnel,
    private val verifyPassword: (String?) -> Boolean,
    private val onSessionChanged: (ConnectionSnapshot?) -> Unit,
    private val onLog: (String?, LogCategory, String) -> Unit,
) : Closeable {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val activeTunnel = AtomicReference<AdbTunnel?>(null)
    private val sessionFree = AtomicBoolean(true)

    @Volatile
    private var activeJob: Job? = null

    private val BUSY_CODE: Short = 4001
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
                        onLog(null, LogCategory.BRIDGE, "连接密码校验失败：$remote")
                        ws.close(CloseReason(UNAUTHORIZED_CODE, "连接密码校验失败"))
                        return@webSocket
                    }
                    if (!sessionFree.compareAndSet(true, false)) {
                        onLog(null, LogCategory.BRIDGE, "已有连接占用，拒绝 $remote")
                        ws.close(CloseReason(BUSY_CODE, "已有连接占用"))
                        return@webSocket
                    }
                    // 先确认浏览器完成握手，浏览器提前断开时不留下无人使用的隧道
                    val first = readFirstPacket(ws)
                    if (first == null) {
                        sessionFree.set(true)
                        onLog(null, LogCategory.BRIDGE, "浏览器未完成握手：$remote")
                        return@webSocket
                    }
                    val sessionId = LogRepository.openSession(remote)
                    val session = ConnectionSession(sessionId, remote)
                    activeJob = ws.coroutineContext[Job]
                    onLog(sessionId, LogCategory.BRIDGE, "客户端 $remote 已连接")
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
                        onLog(sessionId, LogCategory.BRIDGE, "连接被中断")
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "连接异常", e)
                        onLog(sessionId, LogCategory.BRIDGE, "连接异常：${e.message}")
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
        onLog(null, LogCategory.BRIDGE, "转发服务已启动，端口 $port")
    }

    /** 断开当前连接，隧道与统计随连接结束一并复位 */
    fun disconnectActive(): Boolean {
        val job = activeJob
        activeJob = null
        job?.cancel()
        return job != null
    }

    private fun remoteHost(session: DefaultWebSocketServerSession): String {
        return runCatching { session.call.request.local.remoteHost }.getOrDefault("未知来源")
    }

    private fun describeDisconnect(last: ConnectionSnapshot): String {
        return "连接结束，上行 ${last.upBytes} 字节，下行 ${last.downBytes} 字节，时长 ${last.durationMs / 1000} 秒"
    }

    private fun describeSyncRequest(request: String, path: String): String {
        return when (request) {
            "SEND" -> "写入文件：$path"
            "RECV" -> "读取文件：$path"
            "LIST" -> "列出目录：$path"
            else -> "查询文件：$path"
        }
    }

    /** 返回 null 表示这条目标不需要记录 */
    private fun describeDestination(destination: String): String? {
        return when {
            destination.startsWith("shell:") -> "执行命令：${destination.removePrefix("shell:")}"
            destination.startsWith("exec:") -> "执行命令：${destination.removePrefix("exec:")}"
            destination.startsWith("sync:") -> null
            destination.startsWith("tcp:") -> "端口转发：${destination.removePrefix("tcp:")}"
            destination.startsWith("localabstract:") -> "连接本地服务：${destination.removePrefix("localabstract:")}"
            destination.startsWith("dev:") -> "读取设备文件：${destination.removePrefix("dev:")}"
            destination.startsWith("framebuffer:") -> "读取屏幕画面"
            else -> "打开流：$destination"
        }
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
        onLog(sessionId, LogCategory.BRIDGE, "浏览器握手成功")
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
        onLog(null, LogCategory.BRIDGE, "转发服务已停止")
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 1000L
    }
}
