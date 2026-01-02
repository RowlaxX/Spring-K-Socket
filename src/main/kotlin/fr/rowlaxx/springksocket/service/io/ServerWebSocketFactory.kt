package fr.rowlaxx.springksocket.service.io

import fr.rowlaxx.springksocket.data.WebSocketServerProperties
import fr.rowlaxx.springksocket.exception.WebSocketClosedException
import fr.rowlaxx.springksocket.exception.WebSocketConnectionException
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.util.WebSocketMapAttributesUtils
import fr.rowlaxx.springksocket.util.WebSocketSessionUtils.setHandleBinaryMessage
import fr.rowlaxx.springksocket.util.WebSocketSessionUtils.setHandleClose
import fr.rowlaxx.springksocket.util.WebSocketSessionUtils.setHandlePongMessage
import fr.rowlaxx.springksocket.util.WebSocketSessionUtils.setHandleTextMessage
import fr.rowlaxx.springksocket.util.WebSocketSessionUtils.setHandleTransportError
import org.springframework.stereotype.Service
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@Service
class ServerWebSocketFactory(
    private val baseFactory: BaseWebSocketFactory,
) {
    private val sender = Executors.newScheduledThreadPool(2) { Thread(it, "WebSocket Sender") }

    fun wrap(
        session: WebSocketSession,
        config: WebSocketServerProperties,
    ): WebSocket {
        return InternalImplementation(
            sender = sender,
            session = session,
            config = config,
            factory = baseFactory,
        )
    }

    private class InternalImplementation(
        private val sender: Executor,
        private val session: WebSocketSession,
        factory: BaseWebSocketFactory,
        config: WebSocketServerProperties,
    ) : BaseWebSocketFactory.BaseWebSocket(
        factory = factory,
        name = config.name,
        uri = WebSocketMapAttributesUtils.getURI(session.attributes),
        requestHeaders = WebSocketMapAttributesUtils.getRequestHeaders(session.attributes),
        initTimeout = config.initTimeout,
        handlerChain = config.handlerChain,
        pingAfter = config.pingAfter,
        readTimeout = config.readTimeout,
        attributes = WebSocketMapAttributesUtils.getOrCreateAttributes(session.attributes)
    ) {

        init {
            session.setHandlePongMessage { onDataReceived() }
            session.setHandleTextMessage { acceptMessage(it) }
            session.setHandleBinaryMessage { acceptMessage(it) }
            session.setHandleClose {
                closeWith(WebSocketClosedException(it.reason ?: "Unknown reason", it.code))
            }
            session.setHandleTransportError {
                closeWith(WebSocketConnectionException("Transport error : ${it.message}"))
            }

            openWith(session)
        }

        private fun send(task: () -> Unit): CompletableFuture<Unit> {
            val cf = CompletableFuture<Unit>()

            sender.execute {
                try {
                    task()
                    cf.complete(Unit)
                } catch (e: Exception) {
                    cf.completeExceptionally(e)
                }
            }

            return cf
        }

        override fun pingNow(): CompletableFuture<*> {
            return send { session.sendMessage(PingMessage()) }
        }

        override fun sendText(msg: String): CompletableFuture<*> {
            return send { session.sendMessage(TextMessage(msg)) }
        }

        override fun sendBinary(msg: ByteArray): CompletableFuture<*> {
            return send { session.sendMessage(BinaryMessage(msg)) }
        }

        override fun handleClose() {}
        override fun handleOpen(obj: Any) {}

    }

}