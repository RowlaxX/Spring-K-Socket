package fr.rowlaxx.springksocket.service.perp

import fr.rowlaxx.springksocket.core.MessageDeduplicator
import fr.rowlaxx.springksocket.core.WebSocketHandlerPerpetualProxy
import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.PerpetualWebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springksocket.service.io.ClientWebSocketFactory
import fr.rowlaxx.springkutils.concurrent.config.GlobalExecutorsConfiguration
import fr.rowlaxx.springkutils.concurrent.core.TaskQueue
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service
import jakarta.annotation.PreDestroy
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Service
class PerpetualWebSocketFactory(
    private val webSocketFactory: ClientWebSocketFactory,
    private val threads: GlobalExecutorsConfiguration,
) {
    private val idCounter = AtomicInteger()
    private val isShutdown = AtomicBoolean()
    private val sockets = ConcurrentHashMap<Int, InternalImplementation>()

    @PreDestroy
    fun shutdown() {
        isShutdown.set(true)
        runBlocking {
            while (sockets.isNotEmpty()) {
                sockets.values.toList()
                    .onEach { it.close() }
                    .forEach { it.awaitClose() }
            }
        }
    }

    fun create(
        name: String,
        initializers: List<WebSocketHandler>,
        handler: PerpetualWebSocketHandler,
        propertiesFactory: () -> WebSocketClientProperties,
        shiftDuration: Duration,
        switchDuration: Duration,
    ): PerpetualWebSocket {
        if (isShutdown.get()) {
            throw IllegalStateException("Application is shutting down")
        }

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
        sockets[id] = instance
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
        private val mainQueue = TaskQueue(threads.ioDispatcher)
        private val sendQueue = TaskQueue(threads.ioDispatcher, paused = true)

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
            mainQueue.submit {
                if (connecting) {
                    return@submit
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

        private fun <T> delayed(delay: Duration, action: () -> T): Future<T>? {
            try {
                return threads.taskScheduler.scheduledExecutor.schedule<T>( action, delay.toMillis(), TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                return null
            }
        }

        private fun acceptOpeningConnection(webSocket: WebSocket) {
            mainQueue.submit {
                connecting = false
                connections.add(webSocket)
                nextReconnection = delayed(shiftDuration, this::reconnectSafe)
                delayed(switchDuration, this::closeOldConnections)

                if (connections.size == 1) {
                    sendQueue.resume()
                    handler.onAvailable(this)
                }
            }
        }

        private fun closeOldConnections() = mainQueue.submit {
            connections
                .dropLast(1)
                .forEach { it.closeAsync("Shift ended", 1000) }
        }

        private fun acceptClosingConnection(webSocket: WebSocket) {
            mainQueue.submit {
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
                        sendQueue.pause()
                        handler.onUnavailable(this)
                    }
                }
            }
        }

        private fun acceptMessage(webSocket: WebSocket, msg: Any) {
            mainQueue.submit {
                if (totalConnections() <= 1 || (totalConnections() > 1 && deduplicator.accept(msg, webSocket.id))) {
                    val deserialized = handler.deserializer.fromStringOrByteArray(msg)
                    handler.onMessage(this, deserialized)
                }
            }
        }

        override fun isConnected(): Boolean {
            return connections.any { it.isConnected() }
        }

        override fun sendMessageAsync(message: Any): Job {
            val job = Job()
            sendMessageAsync(message, job)
            return job
        }

        private fun sendMessageAsync(message: Any, job: CompletableJob) {
            sendQueue.submit {
                val ws = connections.lastOrNull { it.isConnected() }

                if (ws == null) {
                    sendMessageAsync(message, job) //Should normally be paused
                }
                else {
                    try {
                        ws.sendMessageAsync(message).join()
                        job.complete()
                    } catch (_: Exception) {
                        sendMessageAsync(message, job)
                    }
                }
            }
        }

        internal fun close() {
            sendQueue.close()
            mainQueue.close()
            sockets.remove(id)
        }

        internal suspend fun awaitClose() {
            log.info("Awaiting close for {}", name)
            sendQueue.join()
            mainQueue.join()
        }
    }
}