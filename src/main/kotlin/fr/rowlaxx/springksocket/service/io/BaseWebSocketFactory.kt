package fr.rowlaxx.springksocket.service.io

import fr.rowlaxx.springksocket.data.WebSocketAttributes
import fr.rowlaxx.springksocket.exception.WebSocketClosedException
import fr.rowlaxx.springksocket.exception.WebSocketConnectionException
import fr.rowlaxx.springksocket.exception.WebSocketException
import fr.rowlaxx.springksocket.exception.WebSocketInitializationException
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketHandler
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
class BaseWebSocketFactory {
    private val log = LoggerFactory.getLogger(this.javaClass)
    private val idCounter = AtomicLong()
    private val executor = Executors.newScheduledThreadPool(8) { Thread(it, "WebSocket") }

    abstract class BaseWebSocket(
        private val factory: BaseWebSocketFactory,
        override val name: String,
        override val uri: URI,
        override val requestHeaders: HttpHeaders,
        override val initTimeout: Duration,
        override val handlerChain: List<WebSocketHandler>,
        override val pingAfter: Duration,
        override val readTimeout: Duration,
        override val attributes: WebSocketAttributes = WebSocketAttributes()
    ) : WebSocket {
        override val id = factory.idCounter.andIncrement
        private val lock = ReentrantLock()

        private fun <T> safeAsync(action: () -> T) {
            if (lock.isHeldByCurrentThread) {
                action()
            }
            else {
                factory.executor.submit<T> {
                    lock.withLock(withLog(action))
                }
            }
        }

        private fun <T> safeAsyncDelayed(delay: Duration, action: () -> T): Future<T> {
            return factory.executor.schedule<T>( {
                lock.withLock(withLog(action))
            }, delay.toMillis(), TimeUnit.MILLISECONDS)
        }

        private fun <T> withLog(action: () -> T): () -> T {
            return {
                try {
                    action()
                } catch (e: Throwable) {
                    factory.log.error("[{} ({})] An internal error occurred and should be debugged", name, id, e)
                    throw e
                }
            }
        }

        private inline fun callback(canChange: Boolean = false, action: () -> Unit) {
            if (!canChange) {
                canChangeState = false
            }
            runCatching { action() }
                .onFailure { factory.log.error("[{} ({})] A callback error occurred", name, id, it) }
            if (!canChange) {
                canChangeState = true
            }
        }

        override fun hasOpened(): Boolean = opened
        override fun getClosedReason(): WebSocketException? = closedWith
        override val currentHandlerIndex: Int get() = index

        protected abstract fun pingNow(): CompletableFuture<*>
        protected abstract fun sendText(msg: String): CompletableFuture<*>
        protected abstract fun sendBinary(msg: ByteArray): CompletableFuture<*>
        protected abstract fun handleClose()
        protected abstract fun handleOpen(obj: Any)

        private val sendingQueue = LinkedList<Pair<CompletableFuture<Unit>, () -> CompletableFuture<*>>>()
        private val lastInData = AtomicLong()
        private var nextPing: Future<*>? = null
        private var sending = false
        private var opened = false
        private var canChangeState = true
        private var nextReadTimeout: Future<*>? = null
        private var nextInitTimeout: Future<*>? = null
        private var index: Int = 0

        fun onDataReceived() {
            val last = lastInData.get()
            val now = System.currentTimeMillis()
            val expired = last + 50 < now //Improve efficiency on large traffic websocket

            if (expired && lastInData.compareAndSet(last, now)) {
                nextPing?.cancel(true)
                nextPing = safeAsyncDelayed(pingAfter) {
                    unsafeSendNow(CompletableFuture(), this::pingNow)
                }

                nextReadTimeout?.cancel(true)
                nextReadTimeout = safeAsyncDelayed(readTimeout) {
                    unsafeCloseWith(WebSocketConnectionException("Read timeout"))
                }
            }
        }

        private var closedWith: WebSocketException? = null

        protected fun openWith(obj: Any) {
            if (hasOpened() || hasClosed()) {
                return
            }

            safeAsync {
                if (hasClosed() || hasOpened()) {
                    return@safeAsync
                }

                opened = true
                callback { handleOpen(obj) }
                factory.log.debug("[{} ({})] Opened", name, id)

                if (!isInitialized()) {
                    nextInitTimeout = safeAsyncDelayed(initTimeout) {
                        unsafeCloseWith(WebSocketInitializationException("Initialization timeout"))
                    }
                }

                onDataReceived() // Initialize ws timeout

                sendingQueue.poll()?.let {
                    unsafeSendNow(it.first, it.second)
                }

                callback(true) { currentHandler.onAvailable(this) }
            }
        }

        private fun unsafeCloseWith(reason: WebSocketException) {
            if (hasClosed()) {
                return
            }

            closedWith = reason
            nextReadTimeout?.cancel(true)
            nextReadTimeout = null
            nextPing?.cancel(true)
            nextPing = null
            callback { handleClose() }
            factory.log.debug("[{} ({})] Closed : {}", name, id, reason.message)
            callback { currentHandler.onUnavailable(this) }
        }

        protected fun closeWith(reason: WebSocketException) {
            if (hasClosed()) {
                return
            }

            safeAsync { unsafeCloseWith(reason) }
        }

        protected fun acceptMessage(obj: Any) {
            if (hasClosed()) {
                return
            }

            onDataReceived()
            safeAsync {
                if (hasClosed()) {
                    return@safeAsync
                }
                callback(true) { currentHandler.onMessage(this, obj) }
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
            if (hasClosed()) {
                return CompletableFuture.failedFuture(closedWith!!)
            }

            val cf = CompletableFuture<Unit>()

            safeAsync {
                val ser = when (message) {
                    is String -> message
                    is ByteArray -> message
                    else -> runCatching { currentHandler.serializer.toStringOrByteArray(message) }
                        .onFailure(cf::completeExceptionally)
                        .getOrThrow()
                }

                when (ser) {
                    is String -> unsafeSendNow(cf) { sendText(ser) }
                    is ByteArray -> unsafeSendNow(cf) { sendBinary(ser) }
                    else -> throw IllegalArgumentException("Message must be a String or a ByteArray after deserialization")
                }
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
                if (!canChangeState) {
                    val ex = IllegalStateException("Cannot currently change state of websocket")
                    cf.completeExceptionally(ex)
                    throw ex
                }
                if (!hasOpened()) {
                    throw IllegalStateException("Web Socket is still not opened")
                }

                if (index + 1 >= handlerChain.size) {
                    unsafeCloseWith(WebSocketClosedException("End of HandlerChain", 1000))
                    cf.complete(Unit)
                }
                else {
                    val ch = currentHandler
                    index += 1
                    val nh = currentHandler

                    if (isInitialized()) {
                        nextInitTimeout?.cancel(true)
                        nextInitTimeout = null
                    }

                    cf.complete(Unit)

                    if (ch !== nh) {
                        callback { ch.onUnavailable(this) }
                        callback(true) { nh.onAvailable(this) }
                    }
                }
            }
            return cf
        }
    }
}