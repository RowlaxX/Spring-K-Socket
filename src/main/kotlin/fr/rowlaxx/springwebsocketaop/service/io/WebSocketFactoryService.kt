package fr.rowlaxx.springwebsocketaop.service.io

import fr.rowlaxx.marketdata.common.log.log
import fr.rowlaxx.marketdata.lib.synchronizer.model.Synchronizer
import fr.rowlaxx.marketdata.lib.synchronizer.service.SynchronizerFactoryService
import fr.rowlaxx.springwebsocketaop.exception.WebSocketClosedException
import fr.rowlaxx.springwebsocketaop.exception.WebSocketConnectionException
import fr.rowlaxx.springwebsocketaop.exception.WebSocketCreationException
import fr.rowlaxx.springwebsocketaop.exception.WebSocketException
import fr.rowlaxx.marketdata.lib.websocket.model.*
import fr.rowlaxx.springwebsocketaop.data.WebSocketAttribute
import fr.rowlaxx.springwebsocketaop.data.WebSocketAttributes
import fr.rowlaxx.springwebsocketaop.data.WebSocketClientConfiguration
import fr.rowlaxx.springwebsocketaop.model.WebSocket
import fr.rowlaxx.springwebsocketaop.model.WebSocketHandler
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

@Service
class WebSocketFactoryService(
    private val objectMapper: ObjectMapper,
    concurrentMemoryFactoryService: SynchronizerFactoryService
) {
    private val httpClient = HttpClient.newHttpClient()
    private val idCounter = AtomicLong(0)
    private val memory: Synchronizer<Long, InternalWebSocket> = concurrentMemoryFactoryService.create(
        parallelism = 8,
        name = "Websocket IO",
        poll = { _, ws -> ws.poll() },
        shouldRemove = { _, ws -> ws.isClosed() },
    )

    private fun serialize(obj: Any): String {
        if (obj is CharSequence || obj is JsonNode) {
            return obj.toString()
        }

        return objectMapper.writeValueAsString(obj)
    }

    fun connect(
        name: String,
        configuration: WebSocketClientConfiguration,
        handler: WebSocketHandler,
    ): Long {


        val id = idCounter.getAndIncrement()
        log.debug("[{} ({})] Creating : {}", name, id, uri)

        val listener = InternalJavaListener(
            executeAsync = { memory.runAsync(id, it) }
        )

        val websocket = InternalWebSocket(
            id = id,
            name = name,
            handler = handler,
            uri = uri,
            connectTimeout = connectTimeout,
            readTimeout = readTimeout,
            pingInterval = pingInterval,
            serialize = this::serialize,
            runInSync = { memory.runAsync(id) { _ -> it() } }
        )

        memory.put(id, websocket)

        val rawWs = httpClient.newWebSocketBuilder()
            .also { builder -> headers?.let {
                it  .filterValues { e -> e != null }
                    .forEach { (k, v) -> builder.header(k, v)}
            }}
            .connectTimeout(connectTimeout)
            .buildAsync(uri, listener)

        rawWs.exceptionally {
            websocket.closeWith(WebSocketCreationException(it.message ?: "Unknown error"))
            null
        }

        return id
    }

    private object LastIn : WebSocketAttribute<Instant>()
    private object LastOut : WebSocketAttribute<Instant>()

    private class InternalWebSocket(
        override val id: Long,
        override val name: String,
        override val handler: WebSocketHandler,
        override val uri: URI,
        override val pingInterval: Duration,
        override val connectTimeout: Duration,
        override val readTimeout: Duration,
        override val attributes: WebSocketAttributes = WebSocketAttributes(),
        private val serialize: (Any) -> String,
        private val runInSync: (() -> Unit) -> CompletableFuture<Unit>
    ) : WebSocket {
        private var sending = false
        private val sendingQueue = LinkedList<Pair<CompletableFuture<Unit>, () -> CompletableFuture<*>>>()
        private var javaWebSocket: java.net.http.WebSocket? = null

        fun accept(ws: java.net.http.WebSocket) {
            if (isClosed() || javaWebSocket != null) {
                return
            }
            log.debug("[{} ({})] Opened", name, id)
            javaWebSocket = ws
            opened = true
            handler.onOpen(this)

            sendingQueue.poll()?.let { doOutNow(it.first, it.second, true) }
        }

        fun acceptMessage(message: String) {
            if (isClosed()) {
                return
            }

            log.debug("[{} ({})] Received message : {}", name, id, message.take(70))
            handler.onMessage(this, message)
        }

        override fun sendMessageAsync(message: Any): CompletableFuture<Unit> {
            val cf = CompletableFuture<Unit>()
            runInSync {
                doOutNow(
                    result = cf,
                    action = {
                        val text = serialize(message)
                        log.debug("[{} ({})] Sending message : {}", name, id, text.take(50))
                        javaWebSocket!!.sendText(text, true)
                    },
                    redirectToQueue = true
                )
            }
            return cf
        }

        private fun doOutNow(result: CompletableFuture<Unit>, action: () -> CompletableFuture<*>) {
            if (!isOpened() || sending) {
                if (redirectToQueue) {
                    sendingQueue.add(result to action)
                }
                return
            }

            sending = true
            action().whenComplete { _, e -> runInSync {
                sending = false

                if (e == null) {
                    attributes[LastOut] = Instant.now()
                    result.complete(Unit)
                }
                else {
                    log.error("[{} ({})] Unable to perform out operation", name, id)
                    val ex = if (e is IOException) WebSocketConnectionException("IOException : ${e.message}")
                             else WebSocketClosedException("Exception : ${e.message}")

                    closeWith(ex)
                    result.completeExceptionally(e)
                }

                sendingQueue.poll()?.let {
                    doOutNow(it.first, it.second, true)
                }
            }}
        }
    }
}