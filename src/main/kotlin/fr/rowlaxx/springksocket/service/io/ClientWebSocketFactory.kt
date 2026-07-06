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
                        if (payload.length > MAX_MESSAGE_SIZE_BYTES) {
                            closeOversizedMessage()
                            return
                        }
                        acceptMessage(payload)
                    } else {
                        if (textBuffer.length.toLong() + payload.length > MAX_MESSAGE_SIZE_BYTES) {
                            textBuffer.setLength(0)
                            closeOversizedMessage()
                            return
                        }
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
                        if (payload.size > MAX_MESSAGE_SIZE_BYTES) {
                            closeOversizedMessage()
                            return
                        }
                        acceptMessage(payload)
                    } else {
                        if (binaryBuffer.size().toLong() + payload.size > MAX_MESSAGE_SIZE_BYTES) {
                            binaryBuffer.reset()
                            closeOversizedMessage()
                            return
                        }
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
                    val socket = ws

                    if (socket == null) {
                        return
                    }
                    val startedAt = System.nanoTime()
                    socket.sendPongFrame(payload).addListener {
                        if (!it.isSuccess) {
                            log.warn("[{} ({})] Pong send FAILED: {}", name, id, it.cause()?.message)
                        }
                    }
                }

                override fun onPongFrame(payload: ByteArray) {
                    onDataReceived()
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
            return sendJob { it.sendPingFrame() }
        }

        override fun sendText(msg: String): Deferred<Unit> = sendJob { it.sendTextFrame(msg) }

        override fun sendBinary(msg: ByteArray): Deferred<Unit> = sendJob { it.sendBinaryFrame(msg) }

        private fun closeOversizedMessage() {
            textBuffer.setLength(0)
            binaryBuffer.reset()
            closeWith(WebSocketConnectionException("Incoming message exceeds the maximum allowed size of $MAX_MESSAGE_SIZE_BYTES bytes"))
        }

        override fun handleClose() {
            ws?.takeIf { it.isOpen }?.sendCloseFrame()

            if (!isInitialized()) {
                onInitializationError(getClosedReason()!!)
            }
        }

        override fun handleOpen(obj: Any) {
            ws = obj as AhcWebSocket
        }

        private inline fun sendJob(action: (AhcWebSocket) -> NettyFuture<Void>): Deferred<Unit> {
            val deferred = CompletableDeferred<Unit>()
            try {
                val socket = ws ?: throw WebSocketConnectionException("WebSocket is not connected")
                action(socket).addListener {
                    if (it.isSuccess) deferred.complete(Unit)
                    else deferred.completeExceptionally(it.cause() ?: WebSocketConnectionException("Send failed"))
                }
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
            return deferred
        }
    }

    private companion object {
        /** Above this, a send/pong is slow enough to threaten a broker keepalive deadline. */
        const val KEEPALIVE_WARN_MS = 1_000L

        /** Hard ceiling on a single (possibly fragmented) inbound message; guards against unbounded reassembly buffers. */
        const val MAX_MESSAGE_SIZE_BYTES = 10 * 1024 * 1024
    }
}
