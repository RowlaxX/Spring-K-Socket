package fr.rowlaxx.springksocket.core

import java.util.LinkedList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MessageSender(
    private val lock: ReentrantLock,
    private val canSend: () -> Boolean,
    private val performSend: (Any) -> CompletableFuture<Unit>
) {
    private val queue = LinkedList<Pair<CompletableFuture<Unit>, Any>>()

    private fun sendNow(cf: CompletableFuture<Unit>, msg: Any) {
        lock.withLock {
            if (!canSend()) {
                queue.add(cf to msg)
            }
            else {
                performSend(msg).whenComplete { _, e ->
                    if (e == null) cf.complete(Unit)
                    else sendNow(cf, msg)
                }
            }
        }
    }

    fun send(message: Any): CompletableFuture<Unit> {
        val cf = CompletableFuture<Unit>()
        sendNow(cf, message)
        return cf
    }

    fun retry() {
        lock.withLock {
            (0 until queue.size).forEach { _ ->
                val (cf, msg) = queue.poll()
                sendNow(cf, msg)
            }
        }
    }

}