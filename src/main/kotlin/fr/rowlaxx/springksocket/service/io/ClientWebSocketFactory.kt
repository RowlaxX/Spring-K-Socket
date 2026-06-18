package fr.rowlaxx.springksocket.service.io

import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.exception.WebSocketClosedException
import fr.rowlaxx.springksocket.exception.WebSocketConnectionException
import fr.rowlaxx.springksocket.exception.WebSocketCreationException
import fr.rowlaxx.springksocket.exception.WebSocketException
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
import io.netty.util.concurrent.Future as NettyFuture
import kotlinx.coroutines.Job
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.ws.WebSocketListener
import org.asynchttpclient.ws.WebSocketUpgradeHandler
import org.springframework.stereotype.Service
import tools.jackson.core.util.ByteArrayBuilder
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
        pingAfter = properties.pingAfter,
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
                    ws?.sendPongFrame(payload)
                }

                override fun onPongFrame(payload: ByteArray) = onDataReceived()
            }

            try {
                val request = client.prepareGet(uri.toString())
                requestHeaders.map().forEach { (key, values) -> values.forEach { request.addHeader(key, it) } }
                request.execute(WebSocketUpgradeHandler.Builder().addWebSocketListener(listener).build())
            } catch (t: Throwable) {
                closeWith(WebSocketCreationException(t.message ?: "Unknown error"))
            }
        }

        override fun pingNow(): Job = sendJob { it.sendPingFrame() }

        override fun sendText(msg: String): Job = sendJob { it.sendTextFrame(msg) }

        override fun sendBinary(msg: ByteArray): Job = sendJob { it.sendBinaryFrame(msg) }

        override fun handleClose() {
            ws?.takeIf { it.isOpen }?.sendCloseFrame()

            if (!isInitialized()) {
                onInitializationError(getClosedReason()!!)
            }
        }

        override fun handleOpen(obj: Any) {
            ws = obj as AhcWebSocket
        }

        private fun sendJob(action: (AhcWebSocket) -> NettyFuture<Void>): Job {
            val job = Job()
            val socket = ws
            if (socket == null) {
                job.completeExceptionally(WebSocketConnectionException("WebSocket is not connected"))
                return job
            }
            try {
                action(socket).addListener {
                    if (it.isSuccess) job.complete()
                    else job.completeExceptionally(it.cause() ?: WebSocketConnectionException("Send failed"))
                }
            } catch (t: Throwable) {
                job.completeExceptionally(t)
            }
            return job
        }
    }
}
