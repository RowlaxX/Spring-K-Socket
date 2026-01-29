package fr.rowlaxx.springksocket.model

import fr.rowlaxx.springksocket.data.WebSocketAttributes
import fr.rowlaxx.springksocket.exception.WebSocketException
import java.net.URI
import java.net.http.HttpHeaders
import java.time.Duration
import java.util.concurrent.CompletableFuture

interface WebSocket : WebSocketMessageSender {

    val id: Long
    val name: String
    val uri: URI
    val pingAfter: Duration
    val readTimeout: Duration
    val initTimeout: Duration

    val attributes: WebSocketAttributes

    val handlerChain: List<WebSocketHandler>
    val currentHandler: WebSocketHandler get() = handlerChain[currentHandlerIndex]
    val currentHandlerIndex: Int

    val requestHeaders: HttpHeaders

    fun completeHandlerAsync(): CompletableFuture<Unit>
    fun closeAsync(reason: String="Normal close", code: Int=1000): CompletableFuture<Unit>

    fun hasClosed(): Boolean = getClosedReason() != null
    fun getClosedReason(): WebSocketException?
    fun hasOpened(): Boolean
    fun isConnected(): Boolean = hasOpened() && !hasClosed()
    fun isInitialized(): Boolean = currentHandlerIndex + 1 == handlerChain.size && hasOpened()

}