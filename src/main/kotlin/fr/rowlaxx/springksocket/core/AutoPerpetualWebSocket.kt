package fr.rowlaxx.springksocket.core

import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AutoPerpetualWebSocket {
    private val lock = ReentrantLock()
    private var perp: PerpetualWebSocket? = null
    private val sender = MessageSender(lock, { perp != null }, { perp!!.sendMessageAsync(it) })

    fun set(perpetualWebSocket: PerpetualWebSocket) {
        lock.withLock {
            if (perp != null) {
                return
            }

            perp = perpetualWebSocket
            sender.retry()
        }
    }

    fun sendMessageAsync(message: Any): CompletableFuture<Unit> {
        return sender.send(message)
    }

    fun isConnected(): Boolean {
        lock.withLock {
            return perp != null && perp!!.isConnected()
        }
    }

}