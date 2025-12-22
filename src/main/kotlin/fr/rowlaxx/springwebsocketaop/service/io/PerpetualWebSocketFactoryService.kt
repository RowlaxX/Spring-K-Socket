package fr.rowlaxx.marketdata.lib.websocket.service.io

import fr.rowlaxx.marketdata.lib.synchronizer.model.Synchronizer
import fr.rowlaxx.marketdata.lib.synchronizer.service.SynchronizerFactoryService
import fr.rowlaxx.springwebsocketaop.exception.WebSocketException
import fr.rowlaxx.marketdata.lib.websocket.model.*
import fr.rowlaxx.springwebsocketaop.data.WebSocketAttribute
import fr.rowlaxx.springwebsocketaop.data.WebSocketAttributes
import fr.rowlaxx.springwebsocketaop.data.WebSocketClientConfiguration
import fr.rowlaxx.springwebsocketaop.model.PerpetualWebSocket
import fr.rowlaxx.springwebsocketaop.model.PerpetualWebSocketHandler
import fr.rowlaxx.springwebsocketaop.model.WebSocket
import fr.rowlaxx.springwebsocketaop.model.WebSocketHandler
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

@Service
class PerpetualWebSocketFactoryService(
    private val webSocketFactoryService: WebSocketFactoryService,
    private val webSocketInitializerService: WebSocketInitializerService,

    concurrentMemoryFactoryService: SynchronizerFactoryService,
){
    private val idCounter = AtomicLong()
    private val memory: Synchronizer<Long, InternalPerpetualWebSocket> = concurrentMemoryFactoryService.create(
        name = "WebSocket",
        parallelism = 8,
        poll = { _, ws -> ws.poll() },
    )

    fun create(
        name: String,
        configurationFactory: () -> WebSocketClientConfiguration,
        handler: PerpetualWebSocketHandler,
        initializer: WebSocketInitializerHandler? = null,
        initTimeout: Duration? = null,
        shiftDuration: Duration,
        replaceDuration: Duration = Duration.ofSeconds(3),
    ): PerpetualWebSocket {
        if ((initTimeout?.isNegative ?: false) || shiftDuration.isNegative || replaceDuration.isNegative) {
            throw IllegalArgumentException("Init timeout, shift duration and replace duration must be positive")
        }
        if (initializer != null && initTimeout == null) {
            throw IllegalArgumentException("Init timeout must be provided if initializer is provided")
        }

        val id = idCounter.getAndIncrement()
        val internalHandler = InternalWebSocketHandler { memory.runAsync(id, it) }
        val trueHandler = if (initializer == null) internalHandler else webSocketInitializerService.makeInitializable(
            initTimeout = initTimeout!!,
            initializer = initializer,
            handler = internalHandler
        )

        val socketCreator: () -> Unit = { webSocketFactoryService.create(
            configuration = configurationFactory().copy(name = name),
            handler = trueHandler
        )}

        val result = InternalPerpetualWebSocket(
            id = id,
            name = name,
            shiftDuration = shiftDuration,
            replaceDuration = replaceDuration,
            handler = handler,
            createSocket = socketCreator,
            runInSync = { memory.runAsync(id) { _ -> it() } }
        )

        memory.put(id, result)
        return result
    }

    private object OpenedAt : WebSocketAttribute<Instant>()

    private class InternalPerpetualWebSocket(
        override val id: Long,
        override val name: String,
        override val attributes: WebSocketAttributes = WebSocketAttributes(),

        private val shiftDuration: Duration,
        private val replaceDuration: Duration,
        private val handler: PerpetualWebSocketHandler,
        private val createSocket: () -> Unit,
        private val runInSync: (() -> Unit) -> Unit
    ) : PerpetualWebSocket {
        fun poll() {
            closeOldConnections()
            reconnectIfNeeded()
        }

        override fun isConnected(): Boolean {
            return webSockets.lastOrNull()?.isOpened() ?: false
        }

        // Deduplicate messages
        private val receivedMessages = TreeSet<String>()

        fun validateMessage(message: String): Boolean {
            if (connecting || webSockets.size <= 1) {
                return true
            }

            return receivedMessages.add(message)
        }

        // WebSocket shift
        private val webSockets = LinkedList<WebSocket>()
        private var connecting: Boolean = false

        fun acceptOpen(ws: WebSocket) {
            ws.attributes[OpenedAt] = Instant.now()
            webSockets.add(ws)
            connecting = false

            if (webSockets.size == 1) {
                handler.onConnected(this)
            }

            val toSend = sendQueue.toList()
            sendQueue.clear()
            toSend.forEach { sendNow(it.first, it.second) }
        }

        fun acceptInitError() {
            connecting = false
        }

        fun acceptClose(ws: WebSocket, e: WebSocketException) {
            if (webSockets.removeIf { it.id == ws.id }) {
                if (webSockets.isEmpty()) {
                    handler.onDisconnected(this, e)
                }
                else if (webSockets.size == 1) {
                    receivedMessages.clear()
                }
            }
        }

        private fun closeOldConnections() {
            if (webSockets.size < 2) {
                return
            }

            val current = webSockets.last()
            val toClose = webSockets.dropLast(1)
            val openedAt = current.attributes[OpenedAt]!!

            if (openedAt + replaceDuration < Instant.now()) {
                toClose.forEach { it.closeAsync("Shift terminated") }
            }
        }

        private fun reconnectIfNeeded() {
            if (connecting) {
                return
            }

            val currentWS = webSockets.lastOrNull()
            val openedAt = currentWS?.attributes?.get(OpenedAt) ?: Instant.EPOCH
            val closed = currentWS?.isClosed() ?: true
            val canReconnect = closed || openedAt + shiftDuration < Instant.now()

            if (canReconnect) {
                connecting = true
                createSocket()
            }
        }


        //Send messages
        private val sendQueue = LinkedList<Pair<CompletableFuture<Unit>, Any>>()

        private fun sendNow(cf: CompletableFuture<Unit>, msg: Any) {
            val ws = webSockets.lastOrNull()

            if (ws == null) {
                sendQueue.add(cf to msg)
                return
            }

            ws.sendMessageAsync(msg).whenComplete { _, e -> runInSync {
                if (e == null) {
                    cf.complete(Unit)
                }
                else {
                    sendNow(cf, msg)
                }
            }}
        }

        override fun sendMessageAsync(message: Any): CompletableFuture<Unit> {
            val cf = CompletableFuture<Unit>()
            runInSync { sendNow(cf, message) }
            return cf
        }


        fun acceptMessage(message: String) {
            if (validateMessage(message)) {
                handler.onMessage(this, message)
            }
        }
    }

    private class InternalWebSocketHandler(
        private val perform: ((InternalPerpetualWebSocket) -> Unit) -> Unit
    ) : WebSocketHandler {
        override fun onClose(webSocket: WebSocket, reason: WebSocketException) {
            perform { it.acceptClose(webSocket, reason) }
        }

        override fun onInitError(webSocket: WebSocket, reason: WebSocketException) {
            perform { it.acceptInitError() }
        }

        override fun onOpen(webSocket: WebSocket) {
            perform { it.acceptOpen(webSocket) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            perform { it.acceptMessage(text) }
        }
    }
}