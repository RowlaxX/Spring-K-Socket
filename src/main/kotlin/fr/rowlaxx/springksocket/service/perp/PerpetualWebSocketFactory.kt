package fr.rowlaxx.springksocket.service.perp

import fr.rowlaxx.springksocket.conf.WebSocketConfiguration
import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.PerpetualWebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springksocket.service.io.ClientWebSocketFactory
import fr.rowlaxx.springksocket.core.MessageDeduplicator
import fr.rowlaxx.springksocket.core.WebSocketHandlerPerpetualProxy
import fr.rowlaxx.springkutils.concurrent.core.SequentialWorker
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.composeOnError
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
    private val webSocketFactory: ClientWebSocketFactory,
    private val config: WebSocketConfiguration,
) {
    private val idCounter = AtomicInteger()

    fun create(
        name: String,
        initializers: List<WebSocketHandler>,
        handler: PerpetualWebSocketHandler,
        propertiesFactory: () -> WebSocketClientProperties,
        shiftDuration: Duration,
        switchDuration: Duration,
    ): PerpetualWebSocket {
        val id = idCounter.incrementAndGet()
        val instance = InternalImplementation(
            id = id,
            name = name,
            initializers = initializers,
            handler = handler,
            shiftDuration = shiftDuration,
            switchDuration = switchDuration,
            propertiesFactory = propertiesFactory,
        )

        instance.reconnectSafe()
        return instance
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
        private val mainWorker = SequentialWorker(config.executor, enabled = true)
        private val sendWorker = SequentialWorker(config.executor, enabled = false)

        private val connections = LinkedList<WebSocket>()
        private var nextReconnection: Future<*>? = null
        private var connecting = false
        private val deduplicator = MessageDeduplicator()

        init {
            if (shiftDuration.isNegative) throw IllegalArgumentException("shiftDuration must be a positive duration")
            if (switchDuration.isNegative) throw IllegalArgumentException("switchDuration must be a positive duration")
        }

        private val handlerProxy = WebSocketHandlerPerpetualProxy(
            acceptClosingConnection = this::acceptClosingConnection,
            acceptOpeningConnection = this::acceptOpeningConnection,
            acceptMessage = this::acceptMessage,
            perpetualWebSocket = this,
        )

        private val handlerChain = initializers.plus(handlerProxy)

        fun reconnectSafe() {
            mainWorker.submitTask {
                if (connecting) {
                    return@submitTask
                }

                connecting = true
                nextReconnection?.cancel(true)
                nextReconnection = null
                webSocketFactory.connectFailsafe(name, propertiesFactory(), handlerChain)
            }
        }

        private fun totalConnections(): Int {
            return connections.size + if (connecting) 1 else 0
        }

        private fun acceptOpeningConnection(webSocket: WebSocket) = mainWorker.submitTask {
            connecting = false
            connections.add(webSocket)
            nextReconnection = config.executor.schedule(this::reconnectSafe, shiftDuration.toMillis(), TimeUnit.MILLISECONDS)
            config.executor.schedule(this::closeOldConnections, switchDuration.toMillis(), TimeUnit.MILLISECONDS)

            if (connections.size == 1) {
                sendWorker.enabled(true)
                handler.onAvailable(this)
            }
        }

        private fun closeOldConnections() = mainWorker.submitTask {
            connections
                .dropLast(1)
                .forEach { it.closeAsync("Shift ended", 1000) }
        }

        private fun acceptClosingConnection(webSocket: WebSocket) = mainWorker.submitTask {
            val isLast = connections.lastOrNull()?.id == webSocket.id
            val removed = connections.removeIf { it.id == webSocket.id }

            if (removed) {
                if (isLast) {
                    reconnectSafe()
                }
                if (totalConnections() <= 1) {
                    deduplicator.reset()
                }
                if (connections.isEmpty()) {
                    sendWorker.enabled(false)
                    handler.onUnavailable(this)
                }
            }
        }

        private fun acceptMessage(webSocket: WebSocket, msg: Any) {
            mainWorker.submitTask {
                if (totalConnections() <= 1 || (totalConnections() > 1 && deduplicator.accept(msg, webSocket.id))) {
                    val deserialized = handler.deserializer.fromStringOrByteArray(msg)
                    handler.onMessage(this, deserialized)
                }
            }
        }

        override fun isConnected(): Boolean {
            return connections.any { it.isConnected() }
        }

        override fun sendMessageAsync(message: Any): CompletableFuture<Unit> {
            return sendWorker.submitAsyncTask {
                if (!isConnected()) {
                    throw IllegalStateException("Not connected")
                }

                val ws = connections.last { it.isConnected() }
                ws.sendMessageAsync(message)
            }.composeOnError { sendMessageAsync(message) }
        }
    }
}