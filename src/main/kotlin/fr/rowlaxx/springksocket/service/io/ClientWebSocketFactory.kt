package fr.rowlaxx.springksocket.service.io

import fr.rowlaxx.springksocket.core.JavaWebSocketListener
import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.exception.WebSocketCreationException
import fr.rowlaxx.springksocket.exception.WebSocketException
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springkutils.concurrent.config.ThreadConfiguration
import fr.rowlaxx.springkutils.io.service.HttpClientService
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.asDeferred
import org.springframework.stereotype.Service
import java.net.http.HttpClient
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.TimeUnit

@Service
class ClientWebSocketFactory(
    private val baseFactory: BaseWebSocketFactory,
    private val threads: ThreadConfiguration,
    private val httpClient: HttpClientService
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
            onInitializationError = onInitializationError
        ).apply { connect(httpClient.client, properties.connectTimeout) }
    }

    private class InternalImplementation(
        name: String,
        factory: BaseWebSocketFactory,
        properties: WebSocketClientProperties,
        handlerChain: List<WebSocketHandler>,
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
        private var javaWS: java.net.http.WebSocket? = null

        fun connect(client: HttpClient, timeout: Duration) {
            val listener = JavaWebSocketListener(
                onOpened = { openWith(it) },
                onError = { closeWith(it) },
                onTextMessage = { acceptMessage(it) },
                onBinaryMessage = { acceptMessage(it) },
                onInData = { onDataReceived() },
            )

            val builder = client.newWebSocketBuilder()
                .connectTimeout(timeout)

            requestHeaders.map()
                .flatMap { it.value.map { v -> it.key to v } }
                .forEach { builder.header(it.first, it.second) }

            builder.buildAsync(uri, listener)
                .exceptionally {
                    closeWith(WebSocketCreationException(it.message ?: "Unknown error"))
                    null
                }
        }

        override fun pingNow(): Job {
            return javaWS!!.sendPing(ByteBuffer.allocate(0)).asDeferred()
        }

        override fun sendText(msg: String): Job {
            return javaWS!!.sendText(msg, true).asDeferred()
        }

        override fun sendBinary(msg: ByteArray): Job {
            return javaWS!!.sendBinary(ByteBuffer.wrap(msg), true).asDeferred()
        }

        override fun handleClose() {
            javaWS?.abort()

            if (!isInitialized()) {
                onInitializationError(getClosedReason()!!)
            }
        }

        override fun handleOpen(obj: Any) {
            javaWS = obj as java.net.http.WebSocket
        }
    }

}