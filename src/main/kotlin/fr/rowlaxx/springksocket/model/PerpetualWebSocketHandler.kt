package fr.rowlaxx.springksocket.model

interface PerpetualWebSocketHandler {
    val serializer: WebSocketSerializer
    val deserializer: WebSocketDeserializer

    fun onAvailable(webSocket: PerpetualWebSocket) {}

    fun onMessage(webSocket: PerpetualWebSocket, connection: WebSocket, msg: Any) {}

    fun onShift(webSocket: PerpetualWebSocket, previous: WebSocket?, next: WebSocket?) {}

    fun onUnavailable(webSocket: PerpetualWebSocket) {}

}
