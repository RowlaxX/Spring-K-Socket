package fr.rowlaxx.springwebsocketaop.model

import fr.rowlaxx.springwebsocketaop.data.WebSocketAttributes
import fr.rowlaxx.springwebsocketaop.exception.WebSocketException
import java.net.URI
import java.net.http.HttpHeaders
import java.time.Duration
import java.util.concurrent.CompletableFuture

interface WebSocket {

    val id: Long
    val name: String
    val uri: URI
    val pingInterval: Duration
    val connectTimeout: Duration
    val readTimeout: Duration

    val attributes: WebSocketAttributes

    val handlerChain: List<WebSocketHandler>
    val currentHandler: WebSocketHandler get() = handlerChain[currentHandlerIndex]
    val currentHandlerIndex: Int

    fun completeCurrentHandler(): CompletableFuture<Unit>
    fun sendMessageAsync(message: Any): CompletableFuture<Unit>
    fun closeAsync(reason: String="Normal close", code: Int=1000): CompletableFuture<Unit>

    fun isClosed(): Boolean = getClosedReason() != null
    fun getClosedReason(): WebSocketException?
    fun isOpened(): Boolean
    fun isInitialized(): Boolean = currentHandlerIndex + 1 == handlerChain.size

}