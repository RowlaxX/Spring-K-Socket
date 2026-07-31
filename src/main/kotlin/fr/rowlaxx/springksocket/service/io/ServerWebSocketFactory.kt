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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.springframework.stereotype.Service
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
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
        pingInterval = config.pingInterval,
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

        override fun pingNow(): Deferred<Unit> {
            session.sendMessage(PingMessage())
            return CompletableDeferred(Unit)
        }

        override fun sendText(msg: String): Deferred<Unit> {
            session.sendMessage(TextMessage(msg))
            return CompletableDeferred(Unit)
        }

        override fun sendBinary(msg: ByteArray): Deferred<Unit> {
            session.sendMessage(BinaryMessage(msg))
            return CompletableDeferred(Unit)
        }

        override fun handleClose() {
            if (!session.isOpen) return
            val reason = getClosedReason()
            // Only codes the RFC allows on the wire; 1005/1006 and transport failures map to 1011.
            val status = if (reason is WebSocketClosedException && reason.code in 1000..4999 && reason.code != 1005 && reason.code != 1006) {
                CloseStatus(reason.code, reason.reason.take(MAX_REASON_LENGTH))
            } else {
                CloseStatus.SERVER_ERROR.withReason(reason?.message.orEmpty().take(MAX_REASON_LENGTH))
            }
            runCatching { session.close(status) }
        }

        override fun handleOpen(obj: Any) {}

    }

    private companion object {
        /** RFC 6455: a close reason must fit in 123 UTF-8 bytes. */
        const val MAX_REASON_LENGTH = 123
    }

}