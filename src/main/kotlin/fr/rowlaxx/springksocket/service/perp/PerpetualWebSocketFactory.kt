package fr.rowlaxx.springksocket.service.perp

import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.PerpetualWebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springksocket.service.io.ClientWebSocketFactory
import fr.rowlaxx.springksocket.core.MessageDeduplicator
import fr.rowlaxx.springksocket.core.WebSocketHandlerPerpetualProxy
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service
class PerpetualWebSocketFactory(
    private val webSocketFactory: ClientWebSocketFactory
) {
    private val idCounter = AtomicInteger()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "PerpWebSocket Scheduler") }

    fun create(
        name: String,
        initializers: List<WebSocketHandler>,
        handler: PerpetualWebSocketHandler,
        propertiesFactory: () -> WebSocketClientProperties,
        shiftDuration: Duration,
        switchDuration: Duration,
    ): PerpetualWebSocket {
        if (switchDuration.isNegative) {
            throw IllegalArgumentException("Switch duration must be a positive duration")
        }
        else if (shiftDuration.isNegative) {
            throw IllegalArgumentException("Shift duration must be a positive duration")
        }

        val id = idCounter.incrementAndGet()

        return InternalImplementation(
            id = id,
            name = name,
            initializers = initializers,
            handler = handler,
            shiftDuration = shiftDuration,
            switchDuration = switchDuration,
            propertiesFactory = propertiesFactory,
        ).apply { reconnect() }
    }

    private inner class InternalImplementation(
        override val id: Int,
        override val name: String,
        override val shiftDuration: Duration,
        override val switchDuration: Duration,
        override val propertiesFactory: () -> WebSocketClientProperties,
        override val initializers: List<WebSocketHandler>,
        override val handler: PerpetualWebSocketHandler
    ) : PerpetualWebSocket {
        private val lock = ReentrantLock()
        private val connections = LinkedList<WebSocket>()
        private var nextReconnection: Future<*>? = null
        private var connecting = false
        private val deduplicator = MessageDeduplicator()

        private val handlerProxy = WebSocketHandlerPerpetualProxy(
            acceptClosingConnection = this::acceptClosingConnection,
            acceptOpeningConnection = this::acceptOpeningConnection,
            acceptMessage = this::acceptMessage,
            perpetualWebSocket = this,
            handler = handler,
            sendPendingMessages = { messageSender.retry() }
        )

        private val messageSender = MessageSender(
            lock = lock,
            canSend = { isConnected() },
            performSend = { connections.last().sendMessageAsync(it) }
        )

        fun reconnect() = lock.withLock {
            if (!connecting) {
                connecting = true
                nextReconnection?.cancel(true)
                nextReconnection = null
                webSocketFactory.connectFailsafe(
                    name = name,
                    properties = propertiesFactory(),
                    handlerChain = initializers.plus(handlerProxy)
                )
            }
        }

        private val pendingConnections = connections.size + if (connecting) 1 else 0

        private fun acceptOpeningConnection(webSocket: WebSocket): Boolean = lock.withLock {
            connecting = false
            connections.add(webSocket)
            nextReconnection = scheduler.schedule(this::reconnect, shiftDuration.toMillis(), TimeUnit.MILLISECONDS)
            scheduler.schedule(this::closeOldConnections, switchDuration.toMillis(), TimeUnit.MILLISECONDS)
            connections.size == 1
        }

        private fun closeOldConnections() = lock.withLock {
            connections.dropLast(1).forEach {
                it.closeAsync("Shift ended", 1000)
            }
        }

        private fun acceptClosingConnection(webSocket: WebSocket): Boolean = lock.withLock {
            val isLast = connections.lastOrNull()?.id == webSocket.id
            val removed = connections.removeIf { it.id == webSocket.id }

            if (isLast) {
                reconnect()
            }
            if (pendingConnections <= 1) {
                deduplicator.reset()
            }

            removed && connections.isEmpty()
        }

        private fun acceptMessage(webSocket: WebSocket, msg: Any): Boolean = lock.withLock {
            if (pendingConnections > 1) {
                deduplicator.accept(msg, webSocket.id)
            }
            else {
                true
            }
        }

        override fun isConnected(): Boolean = lock.withLock {
            connections.lastOrNull()?.isConnected() ?: false
        }

        override fun sendMessageAsync(message: Any): CompletableFuture<Unit> {
            return messageSender.send(message)
        }
    }
}