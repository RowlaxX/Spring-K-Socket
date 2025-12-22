package fr.rowlaxx.springwebsocketaop.service.base

import fr.rowlaxx.springwebsocketaop.data.WebSocketClientConfiguration
import fr.rowlaxx.springwebsocketaop.exception.WebSocketCreationException
import fr.rowlaxx.springwebsocketaop.model.JavaWebSocketListener
import fr.rowlaxx.springwebsocketaop.model.WebSocket
import fr.rowlaxx.springwebsocketaop.model.WebSocketDeserializer
import fr.rowlaxx.springwebsocketaop.model.WebSocketHandler
import fr.rowlaxx.springwebsocketaop.model.WebSocketSerializer
import fr.rowlaxx.springwebsocketaop.utils.HttpHeadersUtils.toJavaHeaders
import org.springframework.stereotype.Service
import java.net.http.HttpClient
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

@Service
class WebSocketClientFactoryService(
    private val baseFactory: BaseWebSocketFactoryService
) {
    private val httpClient = HttpClient.newHttpClient()

    fun connect(
        config: WebSocketClientConfiguration,
        handlerChain: List<WebSocketHandler>,
        name: String,
        serializer: WebSocketSerializer,
        deserializer: WebSocketDeserializer,
    ): WebSocket {
        val impl = InternalImplementation(
            factory = baseFactory,
            config = config,
            handlerChain = handlerChain,
            name = name,
            serializer = serializer,
            deserializer = deserializer,
        )

        impl.connect(httpClient)

        return impl
    }

    private class InternalImplementation(
        factory: BaseWebSocketFactoryService,
        config: WebSocketClientConfiguration,
        handlerChain: List<WebSocketHandler>,
        name: String,
        serializer: WebSocketSerializer,
        deserializer: WebSocketDeserializer,
    ) : BaseWebSocketFactoryService.BaseWebSocket(
        factory = factory,
        uri = config.uri,
        connectTimeout = config.connectTimeout,
        readTimeout = config.readTimeout,
        pingAfter = config.pingAfter,
        name = name,
        handlerChain = handlerChain,
        serializer = serializer,
        deserializer = deserializer,
        initTimeout = config.initTimeout,
        requestHeaders = config.headers.toJavaHeaders()
    ) {
        private var javaWS: java.net.http.WebSocket? = null

        fun connect(client: HttpClient) {
            val listener = JavaWebSocketListener(
                onOpened = { safeAsync { unsafeOpenWith(it) } },
                onError = { safeAsync { unsafeCloseWith(it) } },
                onTextMessage = { acceptMessage(it) },
                onBinaryMessage = { acceptMessage(it) },
                onDataReceived = { onDataReceived() }
            )

            val builder = client.newWebSocketBuilder()
                .connectTimeout(connectTimeout)

            requestHeaders.map()
                .flatMap { it.value.map { v -> it.key to v } }
                .forEach { builder.header(it.first, it.second) }

            builder.buildAsync(uri, listener)
                .exceptionally {
                    safeAsync {
                        unsafeCloseWith(WebSocketCreationException(it.message ?: "Unknown error"))
                    }
                    null
                }
        }

        override fun unsafePingNow(): CompletableFuture<*> {
            return javaWS!!.sendPing(ByteBuffer.allocate(0))
        }

        override fun unsafeSendText(msg: String): CompletableFuture<*> {
            return javaWS!!.sendText(msg, true)
        }

        override fun unsafeSendBinary(msg: ByteArray): CompletableFuture<*> {
            return javaWS!!.sendBinary(ByteBuffer.wrap(msg), true)
        }

        override fun unsafeHandleClose() {
            javaWS!!.abort()
        }

        override fun unsafeHandleOpen(obj: Any) {
            javaWS = obj as java.net.http.WebSocket
        }

    }

}