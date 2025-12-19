package fr.rowlaxx.springwebsocketaop.model

import fr.rowlaxx.springwebsocketaop.data.WebSocketAttributes
import java.util.concurrent.CompletableFuture

interface PerpetualWebSocket {

    val id: Long
    val name: String
    val attributes: WebSocketAttributes

    fun isConnected(): Boolean
    fun sendMessageAsync(message: Any): CompletableFuture<Unit>

}