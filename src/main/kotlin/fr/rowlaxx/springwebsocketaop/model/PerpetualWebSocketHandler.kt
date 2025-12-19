package fr.rowlaxx.springwebsocketaop.model

import fr.rowlaxx.springwebsocketaop.exception.WebSocketException

interface PerpetualWebSocketHandler {

    fun onConnected(webSocket: PerpetualWebSocket) {}

    fun onMessage(webSocket: PerpetualWebSocket, text: String) {}

    fun onDisconnected(webSocket: PerpetualWebSocket, exception: WebSocketException) {}

}