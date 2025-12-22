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
    val pingAfter: Duration
    val connectTimeout: Duration
    val readTimeout: Duration
    val initTimeout: Duration

    val attributes: WebSocketAttributes

    val handlerChain: List<WebSocketHandler>
    val currentHandler: WebSocketHandler get() = handlerChain[currentHandlerIndex]
    val currentHandlerIndex: Int
    val serializer: WebSocketSerializer
    val deserializer: WebSocketDeserializer

    val requestHeaders: HttpHeaders

    fun completeHandlerAsync(): CompletableFuture<Unit>
    fun sendMessageAsync(message: Any): CompletableFuture<Unit>
    fun closeAsync(reason: String="Normal close", code: Int=1000): CompletableFuture<Unit>

    fun hasClosed(): Boolean = getClosedReason() != null
    fun getClosedReason(): WebSocketException?
    fun hasOpened(): Boolean
    fun isConnected(): Boolean = hasOpened() && !hasClosed()
    fun isInitialized(): Boolean = currentHandlerIndex + 1 == handlerChain.size

}