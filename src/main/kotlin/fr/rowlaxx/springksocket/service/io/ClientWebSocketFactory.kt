package fr.rowlaxx.springksocket.service.io

import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.exception.WebSocketClosedException
import fr.rowlaxx.springksocket.exception.WebSocketConnectionException
import fr.rowlaxx.springksocket.exception.WebSocketCreationException
import fr.rowlaxx.springksocket.exception.WebSocketException
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import io.netty.util.concurrent.Future as NettyFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.ws.WebSocketListener
import org.asynchttpclient.ws.WebSocketUpgradeHandler
import org.springframework.stereotype.Service
import tools.jackson.core.util.ByteArrayBuilder
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.asynchttpclient.ws.WebSocket as AhcWebSocket

@Service
class ClientWebSocketFactory(
    private val baseFactory: BaseWebSocketFactory,
    private val threads: GlobalThreadConfiguration,
    private val httpClient: AsyncHttpClient,
) {
    fun connectFailsafe(
        name: String,
        properties: WebSocketClientProperties,
        handlerChain: List<WebSocketHandler>,
    ) {
        connect(name, properties, handlerChain) {
            threads.taskScheduler.scheduledExecutor.schedule({
                connectFailsafe(name, properties, handlerChain)
            }, 2000, TimeUnit.MILLISECONDS)
        }
    }

    fun connect(
        name: String,
        properties: WebSocketClientProperties,
        handlerChain: List<WebSocketHandler>,
        onInitializationError: (WebSocketException) -> Unit,
    ): WebSocket {
        return InternalImplementation(
            factory = baseFactory,
            properties = properties,
            handlerChain = handlerChain,
            name = name,
            client = httpClient,
            onInitializationError = onInitializationError
        ).apply { connect() }
    }

    private class InternalImplementation(
        name: String,
        factory: BaseWebSocketFactory,
        properties: WebSocketClientProperties,
        handlerChain: List<WebSocketHandler>,
        private val client: AsyncHttpClient,
        private val onInitializationError: (WebSocketException) -> Unit
    ) : BaseWebSocketFactory.BaseWebSocket(
        factory = factory,
        uri = properties.uri,
        readTimeout = properties.readTimeout,
        pingInterval = properties.pingInterval,
        name = name,
        handlerChain = handlerChain,
        initTimeout = properties.initTimeout,
        requestHeaders = properties.headers
    ) {
        @Volatile
        private var ws: AhcWebSocket? = null

        private val textBuffer = StringBuilder()
        private val binaryBuffer = ByteArrayBuilder()

        // --- Keepalive diagnostics: all epoch millis (0 = never). Written from the Netty IO thread,
        // read on close; @Volatile is enough since we only need eventual visibility, not atomicity.
        @Volatile private var lastServerPingAt = 0L
        @Volatile private var lastPongSentAt = 0L
        @Volatile private var lastPongSendLatencyMs = -1L
        @Volatile private var pongSendFailures = 0
        @Volatile private var lastNativePingSentAt = 0L
        @Volatile private var lastPongRecvAt = 0L
        @Volatile private var lastOutboundAt = 0L
        @Volatile private var lastOutboundLatencyMs = -1L
        @Volatile private var outboundSendFailures = 0

        fun connect() {
            val listener = object : WebSocketListener {
                override fun onOpen(webSocket: AhcWebSocket) {
                    onDataReceived()
                    openWith(webSocket)
                }

                override fun onClose(webSocket: AhcWebSocket, code: Int, reason: String?) {
                    closeWith(WebSocketClosedException(reason ?: "", code))
                }

                override fun onError(t: Throwable) {
                    closeWith(WebSocketConnectionException(t.message ?: "WebSocket error"))
                }

                override fun onTextFrame(payload: String, finalFragment: Boolean, rsv: Int) {
                    onDataReceived()
                    if (finalFragment && textBuffer.isEmpty()) {
                        acceptMessage(payload)
                    } else {
                        textBuffer.append(payload)
                        if (finalFragment) {
                            val msg = textBuffer.toString()
                            textBuffer.setLength(0)
                            acceptMessage(msg)
                        }
                    }
                }

                override fun onBinaryFrame(payload: ByteArray, finalFragment: Boolean, rsv: Int) {
                    onDataReceived()
                    if (finalFragment && binaryBuffer.size() == 0) {
                        acceptMessage(payload)
                    } else {
                        binaryBuffer.write(payload)
                        if (finalFragment) {
                            val msg = binaryBuffer.toByteArray()
                            binaryBuffer.reset()
                            acceptMessage(msg)
                        }
                    }
                }

                override fun onPingFrame(payload: ByteArray) {
                    onDataReceived()
                    lastServerPingAt = System.currentTimeMillis()
                    val socket = ws
                    if (socket == null) {
                        // A server PING arrived before handleOpen assigned `ws` — the pong is dropped.
                        log.warn("[{} ({})] Server PING arrived before socket ready; pong NOT sent", name, id)
                        return
                    }
                    val startedAt = System.nanoTime()
                    socket.sendPongFrame(payload).addListener {
                        if (it.isSuccess) {
                            lastPongSentAt = System.currentTimeMillis()
                            lastPongSendLatencyMs = (System.nanoTime() - startedAt) / 1_000_000
                            if (lastPongSendLatencyMs > KEEPALIVE_WARN_MS) {
                                log.warn("[{} ({})] Pong send took {}ms (server was waiting)", name, id, lastPongSendLatencyMs)
                            }
                        } else {
                            pongSendFailures++
                            log.warn("[{} ({})] Pong send FAILED: {}", name, id, it.cause()?.message)
                        }
                    }
                }

                override fun onPongFrame(payload: ByteArray) {
                    onDataReceived()
                    lastPongRecvAt = System.currentTimeMillis()
                }
            }

            try {
                val request = client.prepareGet(uri.toString())
                request.setRequestTimeout(Duration.ofMillis(-1))
                request.setReadTimeout(Duration.ofMillis(-1))
                requestHeaders.map().forEach { (key, values) -> values.forEach { request.addHeader(key, it) } }
                request.execute(WebSocketUpgradeHandler.Builder().addWebSocketListener(listener).build())
            } catch (t: Throwable) {
                closeWith(WebSocketCreationException(t.message ?: "Unknown error"))
            }
        }

        override fun pingNow(): Deferred<Unit> {
            lastNativePingSentAt = System.currentTimeMillis()
            return sendJob { it.sendPingFrame() }
        }

        override fun sendText(msg: String): Deferred<Unit> = sendJob { it.sendTextFrame(msg) }

        override fun sendBinary(msg: ByteArray): Deferred<Unit> = sendJob { it.sendBinaryFrame(msg) }

        override fun handleClose() {
            ws?.takeIf { it.isOpen }?.sendCloseFrame()
            logKeepaliveSummary()

            if (!isInitialized()) {
                onInitializationError(getClosedReason()!!)
            }
        }

        /**
         * One-line snapshot of the keepalive state at the moment this socket closed. Since the exchange
         * disconnects (Binance `Pong timeout`, Kucoin `Bye`, Mexc) are the events we cannot explain, this
         * tells us whether *we* stopped sending pongs/pings on time, or whether inbound simply went quiet.
         */
        private fun logKeepaliveSummary() {
            val now = System.currentTimeMillis()
            fun ago(t: Long) = if (t == 0L) "never" else "${now - t}ms"
            log.info(
                "[{} ({})] keepalive@close: lastInbound={} lastServerPing={} lastPongSent={} (lat={}ms, {} fail) " +
                    "lastNativePing={} lastPongRecv={} lastOutbound={} (lat={}ms, {} fail)",
                name, id,
                ago(lastInboundAtMillis()),
                ago(lastServerPingAt),
                ago(lastPongSentAt), lastPongSendLatencyMs, pongSendFailures,
                ago(lastNativePingSentAt),
                ago(lastPongRecvAt),
                ago(lastOutboundAt), lastOutboundLatencyMs, outboundSendFailures,
            )
        }

        override fun handleOpen(obj: Any) {
            ws = obj as AhcWebSocket
        }

        private fun sendJob(action: (AhcWebSocket) -> NettyFuture<Void>): Deferred<Unit> {
            val job = CompletableDeferred<Unit>()
            val socket = ws
            if (socket == null) {
                outboundSendFailures++
                job.completeExceptionally(WebSocketConnectionException("WebSocket is not connected"))
                return job
            }
            val startedAt = System.nanoTime()
            try {
                action(socket).addListener {
                    if (it.isSuccess) {
                        lastOutboundAt = System.currentTimeMillis()
                        lastOutboundLatencyMs = (System.nanoTime() - startedAt) / 1_000_000
                        if (lastOutboundLatencyMs > KEEPALIVE_WARN_MS) {
                            log.warn("[{} ({})] Outbound frame send took {}ms", name, id, lastOutboundLatencyMs)
                        }
                        job.complete(Unit)
                    } else {
                        outboundSendFailures++
                        job.completeExceptionally(it.cause() ?: WebSocketConnectionException("Send failed"))
                    }
                }
            } catch (t: Throwable) {
                outboundSendFailures++
                job.completeExceptionally(t)
            }
            return job
        }
    }

    private companion object {
        /** Above this, a send/pong is slow enough to threaten a broker keepalive deadline. */
        const val KEEPALIVE_WARN_MS = 1_000L
    }
}
