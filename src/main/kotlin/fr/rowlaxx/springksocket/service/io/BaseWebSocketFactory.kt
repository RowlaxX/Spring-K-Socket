package fr.rowlaxx.springksocket.service.io

import fr.rowlaxx.springksocket.data.WebSocketAttributes
import fr.rowlaxx.springksocket.exception.WebSocketClosedException
import fr.rowlaxx.springksocket.exception.WebSocketConnectionException
import fr.rowlaxx.springksocket.exception.WebSocketException
import fr.rowlaxx.springksocket.exception.WebSocketInitializationException
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springkutils.concurrent.core.SequentialWorker
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.composeOnCompleted
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.composeOnDone
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.composeOnError
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.onCompleted
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.onDone
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.onError
import fr.rowlaxx.springkutils.concurrent.utils.ExecutorsUtils
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URI
import java.net.http.HttpHeaders
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Service
class BaseWebSocketFactory {
    private val idCounter = AtomicLong()
    private val executor: ScheduledExecutorService = ExecutorsUtils.newFailsafeScheduledExecutor(8, "WebSocket")


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
        private val mainWorker = SequentialWorker(factory.executor)
        private val sendWorker = SequentialWorker(factory.executor, enabled = false)

        private var handlerIndex: Int = 0
        private val lastInData = AtomicLong()
        private var opened = false
        private var closedWith: WebSocketException? = null

        private var nextPing: Future<*>? = null
        private var nextReadTimeout: Future<*>? = null
        private var nextInitTimeout: Future<*>? = null


        override val id = factory.idCounter.andIncrement
        override val currentHandlerIndex: Int get() = handlerIndex

        override fun hasOpened(): Boolean = opened
        override fun getClosedReason(): WebSocketException? = closedWith

        private fun <T> delayed(delay: Duration, action: () -> T): Future<T> {
            return factory.executor.schedule<T>( action, delay.toMillis(), TimeUnit.MILLISECONDS)
        }

        private inline fun runHandler(handler: WebSocketHandler, action: WebSocketHandler.(WebSocket) -> Unit) {
            runCatching { action(handler, this) }
                .onFailure { factory.log.error("[{} ({})] A handler error occurred", name, id, it) }
        }

        protected abstract fun pingNow(): CompletableFuture<*>
        protected abstract fun sendText(msg: String): CompletableFuture<*>
        protected abstract fun sendBinary(msg: ByteArray): CompletableFuture<*>
        protected abstract fun handleClose()
        protected abstract fun handleOpen(obj: Any)

        fun onDataReceived() {
            val last = lastInData.get()
            val now = System.currentTimeMillis()
            val expired = last + 50 < now //Improve efficiency on large traffic websocket

            if (expired && lastInData.compareAndSet(last, now)) {
                nextPing?.cancel(true)
                nextPing = delayed(pingAfter) {
                    sendWorker.submitAsyncTask { pingNow() }
                }

                nextReadTimeout?.cancel(true)
                nextReadTimeout = delayed(readTimeout) {
                    closeWith(WebSocketConnectionException("Read timeout"))
                }
            }
        }




        override fun closeAsync(reason: String, code: Int): CompletableFuture<Unit> {
            return closeWith(WebSocketClosedException(reason, code)).onError {  }
        }

        override fun sendMessageAsync(message: Any): CompletableFuture<Unit> {
            return sendWorker.submitAsyncTask { threadSafeSendMessage(message) }
                .onError { throw closedWith!! }
        }

        protected fun closeWith(reason: WebSocketException): CompletableFuture<Unit> {
            return mainWorker.submitTask { threadSafeCloseWith(reason) }
        }

        protected fun openWith(obj: Any) {
            mainWorker.submitTask { threadSafeOpenWith(obj) }
        }

        protected fun acceptMessage(obj: Any) {
            mainWorker.submitTask { threadSafeAcceptMessage(obj) }
        }

        override fun completeHandlerAsync(): CompletableFuture<Unit> {
            return mainWorker.submitTask { threadSafeCompleteHandler() }
                .onError { throw closedWith!! }
        }




        private fun threadSafeOpenWith(obj: Any) {
            if (hasClosed() || hasOpened()) {
                return
            }

            opened = true
            handleOpen(obj)

            if (!isInitialized()) {
                nextInitTimeout = delayed(initTimeout) { mainWorker.submitTask {
                    threadSafeCloseWith(WebSocketInitializationException("Initialization timeout"))
                }}
            }

            onDataReceived() // Initialize ws timeout
            log.debug("[{} ({})] Opened", name, id)
            sendWorker.enabled(true)
            runHandler(currentHandler) { onAvailable(it) }
        }

        private fun threadSafeCloseWith(reason: WebSocketException) {
            if (hasClosed()) {
                return
            }

            closedWith = reason
            nextReadTimeout?.cancel(true)
            nextReadTimeout = null
            nextPing?.cancel(true)
            nextPing = null
            mainWorker.retire()
            sendWorker.retire()
            handleClose()
            log.debug("[{} ({})] Closed : {}", name, id, reason.message)
            runHandler(currentHandler) { onUnavailable(it) }
        }

        private fun threadSafeCompleteHandler() {
            if (!isConnected()) {
                throw IllegalStateException("WebSocket is not connected yet")
            }

            if (handlerIndex + 1 >= handlerChain.size) {
                threadSafeCloseWith(WebSocketClosedException("End of HandlerChain", 1000))
            }
            else {
                val ch = currentHandler
                handlerIndex += 1
                val nh = currentHandler

                if (isInitialized()) {
                    nextInitTimeout?.cancel(true)
                    nextInitTimeout = null
                }

                if (ch !== nh) {
                    runHandler(ch) { onUnavailable(it) }
                    runHandler(nh) { onAvailable(it) }
                }
            }
        }

        private fun threadSafeAcceptMessage(obj: Any) {
            if (hasClosed()) {
                return
            }

            val deserialized = currentHandler.deserializer.fromStringOrByteArray(obj)
            runHandler(currentHandler) { onMessage(it, deserialized) }
        }

        private fun threadSafeSendMessage(msg: Any): CompletableFuture<Unit> {
            if (hasClosed()) {
                return CompletableFuture.failedFuture(closedWith!!)
            }

            val ser = when (msg) {
                is String -> msg
                is ByteArray -> msg
                else -> currentHandler.serializer.toStringOrByteArray(msg)
            }

            val cf = when (ser) {
                is String -> sendText(ser)
                is ByteArray -> sendBinary(ser)
                else -> throw IllegalStateException("Message must be a String or a ByteArray after serialization. Current type : ${msg.javaClass.simpleName}")
            }

            return cf.composeOnError {
                if (cf.isCancelled) {
                    return@composeOnError CompletableFuture.failedFuture(it)
                }

                val ex = when (it) {
                    is WebSocketException -> it
                    is IOException -> WebSocketConnectionException("IOException : ${it.message}")
                    else -> WebSocketConnectionException("Unknown exception : ${it.message}")
                }

                return@composeOnError mainWorker.submitTask { threadSafeCloseWith(ex) }
            }
        }
    }
}