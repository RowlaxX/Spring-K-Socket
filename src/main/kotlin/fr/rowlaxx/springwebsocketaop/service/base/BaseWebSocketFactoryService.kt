package fr.rowlaxx.springwebsocketaop.service.base

import fr.rowlaxx.springwebsocketaop.data.WebSocketAttributes
import fr.rowlaxx.springwebsocketaop.exception.WebSocketClosedException
import fr.rowlaxx.springwebsocketaop.exception.WebSocketConnectionException
import fr.rowlaxx.springwebsocketaop.exception.WebSocketCreationException
import fr.rowlaxx.springwebsocketaop.exception.WebSocketException
import fr.rowlaxx.springwebsocketaop.exception.WebSocketInitializationException
import fr.rowlaxx.springwebsocketaop.exception.WebSocketTimeoutException
import fr.rowlaxx.springwebsocketaop.model.WebSocket
import fr.rowlaxx.springwebsocketaop.model.WebSocketDeserializer
import fr.rowlaxx.springwebsocketaop.model.WebSocketHandler
import fr.rowlaxx.springwebsocketaop.model.WebSocketSerializer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URI
import java.net.http.HttpHeaders
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service
class BaseWebSocketFactoryService {
    private val log = LoggerFactory.getLogger(this.javaClass)
    private val idCounter = AtomicLong()
    private val executor = Executors.newScheduledThreadPool(4) { Thread(it, "WebSocket IO") }

    abstract class BaseWebSocket(
        private val factory: BaseWebSocketFactoryService,
        override val name: String,
        override val uri: URI,
        override val requestHeaders: HttpHeaders,
        override val serializer: WebSocketSerializer,
        override val deserializer: WebSocketDeserializer,
        override val connectTimeout: Duration,
        override val initTimeout: Duration,
        override val handlerChain: List<WebSocketHandler>,
        override val pingAfter: Duration,
        override val readTimeout: Duration,
        override val attributes: WebSocketAttributes = WebSocketAttributes()
    ) : WebSocket {
        override val id = factory.idCounter.andIncrement
        private val lock = ReentrantLock()

        protected fun <T> safeAsync(action: () -> T): Future<T> {
            return factory.executor.submit<T> {
                lock.withLock(action)
            }
        }

        private fun <T> safeAsyncDelayed(delay: Duration, action: () -> T): Future<T> {
            return factory.executor.schedule<T>( {
                lock.withLock(action)
            }, delay.toMillis(), TimeUnit.MILLISECONDS)
        }

        override fun hasOpened(): Boolean = opened
        override fun getClosedReason(): WebSocketException? = closedWith
        override val currentHandlerIndex: Int get() = index

        protected abstract fun unsafePingNow(): CompletableFuture<*>
        protected abstract fun unsafeSendText(msg: String): CompletableFuture<*>
        protected abstract fun unsafeSendBinary(msg: ByteArray): CompletableFuture<*>
        protected abstract fun unsafeHandleClose()
        protected abstract fun unsafeHandleOpen(obj: Any)

        private val sendingQueue = LinkedList<Pair<CompletableFuture<Unit>, () -> CompletableFuture<*>>>()
        private val lastInData = AtomicLong()
        private var nextPing: Future<*>? = null
        private var sending = false
        private var opened = false
        private var nextReadTimeout: Future<*>? = null
        private var nextInitTimeout: Future<*>? = null
        private var index: Int = 0

        protected fun onDataReceived() {
            val last = lastInData.get()
            val now = System.currentTimeMillis()
            val expired = last + 50 < now //Improve efficiency on large traffic websocket

            if (expired && lastInData.compareAndSet(last, now)) {
                nextPing?.cancel(true)
                nextPing = safeAsyncDelayed(pingAfter) {
                    unsafeSendNow(CompletableFuture(), this::unsafePingNow)
                }
                nextReadTimeout?.cancel(true)
                nextReadTimeout = safeAsyncDelayed(readTimeout) {
                    unsafeCloseWith(WebSocketConnectionException("Read timeout"))
                }
            }
        }

        private var closedWith: WebSocketException? = null

        protected fun unsafeOpenWith(obj: Any) {
            if (hasClosed() || hasOpened()) {
                return
            }

            opened = true
            unsafeHandleOpen(obj)
            factory.log.debug("[{} ({})] Opened", name, id)

            if (!isInitialized()) {
                nextInitTimeout = safeAsyncDelayed(initTimeout) {
                    unsafeCloseWith(WebSocketInitializationException("Initialization timeout"))
                }
            }

            currentHandler.onAvailable(this)
            onDataReceived()

            sendingQueue.poll()?.let {
                unsafeSendNow(it.first, it.second)
            }
        }

        protected fun unsafeCloseWith(reason: WebSocketException) {
            if (hasClosed()) {
                return
            }

            closedWith = reason
            unsafeHandleClose()
            factory.log.debug("[{} ({})] Closed : {}", name, id, reason.message)
            currentHandler.onUnavailable(this)
        }

        protected fun acceptMessage(obj: Any) {
            if (hasClosed()) {
                return
            }

            val des = deserializer.deserialize(obj)

            safeAsync {
                if (hasClosed()) {
                    return@safeAsync
                }
                currentHandler.onMessage(this, des)
            }
        }

        override fun closeAsync(reason: String, code: Int): CompletableFuture<Unit> {
            if (hasClosed()) {
                return CompletableFuture.completedFuture(Unit)
            }

            val cf = CompletableFuture<Unit>()

            safeAsync {
                unsafeCloseWith(WebSocketClosedException(reason, code))
                cf.complete(Unit)
            }

            return cf
        }

        override fun sendMessageAsync(message: Any): CompletableFuture<Unit> {
            val cf = CompletableFuture<Unit>()
            val ser = serializer.serialize(message)

            when (ser) {
                is String -> safeAsync { unsafeSendNow(cf) { unsafeSendText(ser) } }
                is ByteArray -> safeAsync { unsafeSendNow(cf) { unsafeSendBinary(ser) } }
                else -> throw IllegalArgumentException("Message must be a String or a ByteArray after deserialization")
            }

            return cf
        }

        private fun unsafeSendNow(result: CompletableFuture<Unit>, action: () -> CompletableFuture<*>) {
            if (hasClosed()) {
                result.completeExceptionally(closedWith)
                sendingQueue.forEach {
                    it.first.completeExceptionally(closedWith)
                }
                sendingQueue.clear()
            }
            else if (hasOpened() && !sending) {
                sending = true
                action().whenComplete { _, e -> safeAsync {
                    sending = false

                    if (e == null) {
                        result.complete(Unit)
                    }
                    else {
                        factory.log.error("[{} ({})] Unable to perform out operation", name, id)

                        val ex = e as? WebSocketException ?:
                                (e as? IOException)?.let { WebSocketConnectionException("IOException : ${it.message}") } ?: WebSocketConnectionException(
                            "Unknown exception : ${e.message}"
                        )

                        unsafeCloseWith(ex)
                    }

                    sendingQueue.poll()?.let { next ->
                        unsafeSendNow(next.first, next.second)
                    }
                }}
            }
            else {
                sendingQueue.add(result to action)
            }
        }

        override fun completeHandlerAsync(): CompletableFuture<Unit> {
            val cf = CompletableFuture<Unit>()
            safeAsync {
                if (index + 1 >= handlerChain.size) {
                    unsafeCloseWith(WebSocketClosedException("Handler chain end", 1000))
                }
                else {
                    val ch = currentHandler
                    index += 1

                    if (isInitialized()) {
                        nextInitTimeout?.cancel(true)
                        nextReadTimeout = null
                    }

                    val nh = currentHandler

                    if (ch !== nh) {
                        ch.onUnavailable(this)
                        nh.onAvailable(this)
                    }
                }

                cf.complete(Unit)
            }
            return cf
        }
    }
}