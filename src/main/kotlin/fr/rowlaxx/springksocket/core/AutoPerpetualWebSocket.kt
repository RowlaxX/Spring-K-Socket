package fr.rowlaxx.springksocket.core

import fr.rowlaxx.springksocket.data.WebSocketAttributes
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AutoPerpetualWebSocket {
    private var perp: PerpetualWebSocket? = null
    private val pending = ConcurrentLinkedQueue<Pair<Any, CompletableFuture<Unit>>>()

    private fun sendIfPossible() {
        if (perp == null) {
            return
        }

        while (!pending.isEmpty()) {
            val (msg, cf) = pending.poll() ?: continue
            perp!!.sendMessageAsync(msg).whenComplete { _, _ -> cf.complete(Unit) }
        }
    }

    fun set(perpetualWebSocket: PerpetualWebSocket) {
        if (perp != null) {
            return
        }
        perp = perpetualWebSocket
        sendIfPossible()
    }

    fun sendMessageAsync(message: Any): CompletableFuture<Unit> {
        if (perp != null) {
            return perp!!.sendMessageAsync(message)
        }
        else {
            val cf = CompletableFuture<Unit>()
            pending.add(message to cf)
            sendIfPossible()
            return cf
        }
    }

    fun isConnected(): Boolean {
        return perp?.isConnected() ?: false
    }

}