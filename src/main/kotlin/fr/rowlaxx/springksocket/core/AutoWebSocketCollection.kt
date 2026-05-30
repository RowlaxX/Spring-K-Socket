package fr.rowlaxx.springksocket.core

import fr.rowlaxx.springksocket.model.WebSocket
import kotlinx.coroutines.future.asCompletableFuture
import java.util.*
import java.util.concurrent.CompletableFuture

class AutoWebSocketCollection {
    private val webSockets = LinkedList<WebSocket>()

    fun add(webSocket: WebSocket) {
        synchronized(webSockets) {
            webSockets.add(webSocket)
        }
    }

    fun remove(webSocket: WebSocket) {
        synchronized(webSockets) {
            webSockets.remove(webSocket)
        }
    }

    fun filter(predicate: (WebSocket) -> Boolean): List<WebSocket> {
        return synchronized(webSockets) { webSockets.toList()}
            .filter(predicate)
    }

    fun send(msg: Any, filter: (WebSocket) -> Boolean): CompletableFuture<Unit> {
        val wss = synchronized(webSockets) { webSockets.toList() }
            .filter(filter)
            .filter { it.isConnected() }

        val serMessages = wss.map { it.currentHandler.serializer }
            .toSet()
            .associateWith { it.toStringOrByteArray(msg) }

        val cfs = wss.map { it.sendMessageAsync(serMessages[it.currentHandler.serializer]!!).asCompletableFuture() }

        return CompletableFuture.allOf(*cfs.toTypedArray()).thenApply { }
    }

    fun sendAll(msg: Any) = send(msg) { true }

}