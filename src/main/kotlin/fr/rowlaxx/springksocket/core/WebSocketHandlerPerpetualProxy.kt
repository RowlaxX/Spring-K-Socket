package fr.rowlaxx.springksocket.core

import fr.rowlaxx.springksocket.model.*

class WebSocketHandlerPerpetualProxy(
    private val acceptOpeningConnection: (WebSocket) -> Boolean,
    private val acceptMessage: (WebSocket, Any) -> Boolean,
    private val acceptClosingConnection: (WebSocket) -> Boolean,
    private val sendPendingMessages: () -> Unit,
    private val perpetualWebSocket: PerpetualWebSocket,
    private val handler: PerpetualWebSocketHandler
) : WebSocketHandler {
    override val deserializer: WebSocketDeserializer get() = WebSocketDeserializer.Passthrough // We let the PerpetualHandler handle deduplication
    override val serializer: WebSocketSerializer get() = handler.serializer

    override fun onAvailable(webSocket: WebSocket) {
        if (acceptOpeningConnection(webSocket)) {
            handler.onAvailable(perpetualWebSocket)
        }
        sendPendingMessages()
    }

    override fun onMessage(webSocket: WebSocket, msg: Any) {
        if (acceptMessage(webSocket, msg)) {
            handler.onMessage(perpetualWebSocket, msg)
        }
    }

    override fun onUnavailable(webSocket: WebSocket) {
        if (!webSocket.isInitialized()) { // When handlerChain.size == !
            return
        }
        if (acceptClosingConnection(webSocket)) {
            handler.onUnavailable(perpetualWebSocket)
        }
    }
}