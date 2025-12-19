package fr.rowlaxx.marketdata.lib.websocket.service

import fr.rowlaxx.springwebsocketaop.event.OnWebSocketsInitialized
import fr.rowlaxx.springwebsocketaop.model.PerpetualWebSocket
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

@Service
class WebSocketHelper() {
    private val initialized = AtomicBoolean(false)
    private val pending = ConcurrentLinkedQueue<Triple<CompletableFuture<Unit>, KClass<*>, Any>>()
    private val sockets = mutableMapOf<KClass<*>, PerpetualWebSocket>()

    @EventListener(ApplicationReadyEvent::class)
    private fun checkPending() {
        if (pending.isNotEmpty()) {
            throw IllegalStateException("Pending messages are still present")
        }
    }

    @EventListener
    private fun onInitialized(event: OnWebSocketsInitialized) {
        event.beanAndWS.forEach { sockets[it.first::class] = it.second }
        initialized.set(true)

        while (pending.isNotEmpty()) {
            val (cf, clazz, payload) = pending.remove()
            val ws = sockets[clazz]!!

            ws.sendMessageAsync(payload).whenComplete { result, throwable ->
                if (throwable != null) cf.completeExceptionally(throwable)
                else cf.complete(result)
            }
        }
    }

    fun sendMessageAsync(sender: Any, message: Any): CompletableFuture<Unit> {
        if (initialized.get()) {
            val ws = sockets[sender::class]!!
            return ws.sendMessageAsync(message)
        }
        else {
            val triple = Triple(
                first = CompletableFuture<Unit>(),
                second = sender::class,
                third = message
            )
            pending.add(triple)
            return triple.first
        }
    }

    fun isConnected(sender: Any): Boolean {
        return initialized.get() && sockets[sender::class]!!.isConnected()
    }

}