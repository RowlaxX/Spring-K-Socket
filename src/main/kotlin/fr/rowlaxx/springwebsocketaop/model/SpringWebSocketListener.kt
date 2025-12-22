package fr.rowlaxx.springwebsocketaop.model

import fr.rowlaxx.springwebsocketaop.utils.ByteBufferUtils.getBackingArray
import org.springframework.web.socket.*
import org.springframework.web.socket.handler.AbstractWebSocketHandler

@Suppress("UNCHECKED_CAST")
class SpringWebSocketListener(

) : AbstractWebSocketHandler() {

    override fun handleBinaryMessage(session: WebSocketSession, message: BinaryMessage) {
        val callback = session.attributes["binary"] as (ByteArray) -> Unit
        val array = message.payload.getBackingArray()
        callback(array)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val callback = session.attributes["text"] as (String) -> Unit
        val text = message.payload
        callback(text)
    }

    override fun handlePongMessage(session: WebSocketSession, message: PongMessage) {
        val callback = session.attributes["pong"] as () -> Unit
        callback()
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        val callback = session.attributes["error"] as (Throwable) -> Unit
        callback(exception)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val callback = session.attributes["close"] as (CloseStatus) -> Unit
        callback(status)
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {

    }

}