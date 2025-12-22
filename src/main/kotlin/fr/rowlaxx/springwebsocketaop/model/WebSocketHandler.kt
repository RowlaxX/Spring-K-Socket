package fr.rowlaxx.springwebsocketaop.model

interface WebSocketHandler {

    fun onAvailable(webWebSocket: WebSocket) {}

    fun onMessage(webSocket: WebSocket, msg: Any)  {}

    fun onUnavailable(webSocket: WebSocket) {}

}