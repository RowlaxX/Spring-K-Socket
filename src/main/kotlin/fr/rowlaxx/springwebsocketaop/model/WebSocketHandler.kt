package fr.rowlaxx.springwebsocketaop.model

interface WebSocketHandler {

    fun onAvailable(webWebSocket: WebSocket) {}

    fun onBinaryMessage(webSocket: WebSocket, data: ByteArray)  {}
    fun onTextMessage(webSocket: WebSocket, text: String) {}

    fun onUnavailable(webSocket: WebSocket) {}

}