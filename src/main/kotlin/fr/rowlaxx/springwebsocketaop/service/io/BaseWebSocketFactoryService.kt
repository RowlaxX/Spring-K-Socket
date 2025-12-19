package fr.rowlaxx.springwebsocketaop.service.io

import fr.rowlaxx.springwebsocketaop.data.WebSocketAttributes
import fr.rowlaxx.springwebsocketaop.exception.WebSocketClosedException
import fr.rowlaxx.springwebsocketaop.exception.WebSocketException
import fr.rowlaxx.springwebsocketaop.exception.WebSocketTimeoutException
import fr.rowlaxx.springwebsocketaop.model.WebSocket
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.WebSocketClient
import java.time.Duration
import java.util.LinkedList
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
        override val pingInterval: Duration,
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

        override fun isOpened(): Boolean = opened
        override fun getClosedReason(): WebSocketException? = closedException

        protected abstract fun threadSafePingNow(): CompletableFuture<*>
        protected abstract fun threadSafeSend(msg: Any): CompletableFuture<*>
        protected abstract fun threadSafeHandleClose()
        protected abstract fun threadSafeHandeOpen()

        private val sendingQueue = LinkedList<Pair<CompletableFuture<Unit>, () -> CompletableFuture<*>>>()
        private val lastInData = AtomicLong()
        private var nextPing: Future<*>? = null
        private var opened = false
        private var nextReadTimeout: Future<*>? = null

        protected fun onDataReceived() {
            val last = lastInData.get()
            val now = System.currentTimeMillis()
            val expired = last + 50 < now //Improve efficiency on large traffic websocket

            if (expired && lastInData.compareAndSet(last, now)) {
                nextPing?.cancel(false)
                nextPing = safeAsyncDelayed(pingInterval) { threadSafeTryPing() }
                nextReadTimeout?.cancel(false)
                nextReadTimeout = safeAsyncDelayed(readTimeout) { threadSafeCloseWith(WebSocketTimeoutException("Read timeout")) }
            }
        }

        private fun threadSafeTryPing() {
            if (!isOpened() || isClosed()) {
                return
            }

            threadSafeTryPing()
        }


        private var closedWith: WebSocketException? = null

        protected fun threadSafeOpen() {
            if (isClosed() || isOpened()) {
                return
            }

            opened = true
            threadSafeHandeOpen()
            factory.log.debug("[{} ({})] Opened", name, id)
            currentHandler.onAvailable(this)
        }

        protected fun threadSafeCloseWith(reason: WebSocketException) {
            if (isClosed()) {
                return
            }

            opened = false
            closedWith = reason
            threadSafeHandleClose()
            factory.log.debug("[{} ({})] Closed : {}", name, id, reason.message)
            currentHandler.onUnavailable(this)
        }

        override fun closeAsync(reason: String, code: Int): CompletableFuture<Unit> {
            if (isClosed()) {
                return CompletableFuture.completedFuture(Unit)
            }

            val cf = CompletableFuture<Unit>()

            safeAsync {
                threadSafeCloseWith(WebSocketClosedException(reason, code))
                cf.complete(Unit)
            }

            return cf
        }

        private fun sendNow(result: CompletableFuture<Unit>, action: () -> CompletableFuture<*>) {

        }
    }
}