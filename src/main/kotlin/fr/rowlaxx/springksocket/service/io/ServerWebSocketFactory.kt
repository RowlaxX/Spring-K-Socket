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
import kotlinx.coroutines.Job
import org.springframework.stereotype.Service
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession

@Service
class ServerWebSocketFactory(
    private val baseFactory: BaseWebSocketFactory,
) {

    fun wrap(
        session: WebSocketSession,
        config: WebSocketServerProperties,
    ): WebSocket {
        return InternalImplementation(
            session = session,
            config = config,
            factory = baseFactory,
        )
    }

    private class InternalImplementation(
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

        override fun pingNow(): Job {
            session.sendMessage(PingMessage())
            return Job().also { it.complete() }
        }

        override fun sendText(msg: String): Job {
            session.sendMessage(TextMessage(msg))
            return Job().also { it.complete() }
        }

        override fun sendBinary(msg: ByteArray): Job {
            session.sendMessage(BinaryMessage(msg))
            return Job().also { it.complete() }
        }

        override fun handleClose() {}
        override fun handleOpen(obj: Any) {}

    }

}