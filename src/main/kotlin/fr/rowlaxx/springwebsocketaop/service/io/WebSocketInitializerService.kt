package fr.rowlaxx.marketdata.lib.websocket.service.io

import fr.rowlaxx.marketdata.common.log.log
import fr.rowlaxx.springwebsocketaop.exception.WebSocketException
import fr.rowlaxx.marketdata.lib.websocket.model.*
import fr.rowlaxx.springwebsocketaop.data.WebSocketAttribute
import fr.rowlaxx.springwebsocketaop.model.WebSocket
import fr.rowlaxx.springwebsocketaop.model.WebSocketHandler
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

@Service
class WebSocketInitializerService() {
    private val scheduler = Executors.newScheduledThreadPool(1)

    fun makeInitializable(
        handler: WebSocketHandler,
        initializer: WebSocketInitializerHandler,
        initTimeout: Duration = Duration.ofSeconds(8),
    ): WebSocketHandler {
        if (initTimeout.isNegative) {
            throw IllegalArgumentException("Initialization timeout must be positive")
        }

        return ProxyWebSocketHandler(
            initializer = initializer,
            initTimeout = initTimeout,
            original = handler,
            scheduler = scheduler
        )
    }

    private class ProxyWebSocketHandler(
        private val original: WebSocketHandler,
        private val initializer: WebSocketInitializerHandler,
        private val initTimeout: Duration,
        private val scheduler: ScheduledExecutorService
    ): WebSocketHandler {
        private object Initialized: WebSocketAttribute<AtomicBoolean>()
        private object Timeout: WebSocketAttribute<Future<*>>()
        private object Initializer: WebSocketAttribute<WebSocketInitializer>()
        private object Opened: WebSocketAttribute<AtomicBoolean>()
        private object Queue: WebSocketAttribute<ConcurrentLinkedQueue<String>>()

        val WebSocket.isInitialized: AtomicBoolean
            get() = attributes.putIfAbsent(Initialized, AtomicBoolean())

        val WebSocket.isOpened: AtomicBoolean
            get() = attributes.putIfAbsent(Opened, AtomicBoolean())

        val WebSocket.queue: ConcurrentLinkedQueue<String>
            get() = attributes.putIfAbsent(Queue, ConcurrentLinkedQueue<String>())

        var WebSocket.timedOut: Future<*>
            get() = attributes[Timeout]!!
            set(value) { attributes[Timeout] = value }

        var WebSocket.initializer: WebSocketInitializer
            get() = attributes[Initializer]!!
            set(value) { attributes[Initializer] = value }

        private class InternalWebSocketInitializer(
            val completeInitialization: () -> Unit,
            val cancelInitialization: (String?) -> Unit,
        ) : WebSocketInitializer {
            override fun complete() = completeInitialization()
            override fun cancel(reason: String?) = cancelInitialization(reason)
        }

        private fun cancelInitialization(websocket: WebSocket, reason: String? = null) {
            if (websocket.isInitialized.get()) {
                return
            }

            websocket.closeAsync(reason ?: "Canceled")
        }

        private fun completeInitialization(webSocket: WebSocket) {

            if (!webSocket.isInitialized.compareAndSet(false, true)) {
                return
            }

            webSocket.timedOut.cancel(true)
            initializer.onInitialized(webSocket)
            log.debug("[{} ({})] Ready", webSocket.name, webSocket.id)
            original.onOpen(webSocket)
            webSocket.isOpened.set(true)

            while (webSocket.queue.isNotEmpty()) {
                original.onMessage(webSocket, webSocket.queue.poll())
            }
        }

        override fun onOpen(webSocket: WebSocket) {
            log.debug("[{} ({})] Initializing", webSocket.name, webSocket.id)
            webSocket.isInitialized.set(false)
            webSocket.initializer = InternalWebSocketInitializer(
                completeInitialization = { completeInitialization(webSocket) },
                cancelInitialization = { cancelInitialization(webSocket, it) }
            )
            webSocket.timedOut = scheduler.schedule({
                cancelInitialization(webSocket, "Initialization timeout")
            }, initTimeout.toMillis(), TimeUnit.MILLISECONDS)

            initializer.onConnected(webSocket, webSocket.initializer)
        }

        override fun onClose(webSocket: WebSocket, reason: WebSocketException) {
            onError(webSocket, reason)
        }

        override fun onInitError(webSocket: WebSocket, reason: WebSocketException) {
            onError(webSocket, reason)
        }

        private fun onError(webSocket: WebSocket, error: WebSocketException) {
            if (webSocket.isInitialized.get()) {
                original.onClose(webSocket, error)
            }
            else {
                initializer.onCanceled(webSocket, error)
                original.onInitError(webSocket, error)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket.isInitialized.get()) {
                if (webSocket.isOpened.get()) {
                    original.onMessage(webSocket, text)
                }
                else {
                    webSocket.queue.add(text)
                }
            }
            else {
                initializer.onMessage(webSocket, text, webSocket.initializer)
            }
        }
    }

}