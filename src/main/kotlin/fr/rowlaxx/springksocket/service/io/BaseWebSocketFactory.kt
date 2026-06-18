package fr.rowlaxx.springksocket.service.io

import fr.rowlaxx.springksocket.data.WebSocketAttributes
import fr.rowlaxx.springksocket.exception.WebSocketClosedException
import fr.rowlaxx.springksocket.exception.WebSocketConnectionException
import fr.rowlaxx.springksocket.exception.WebSocketException
import fr.rowlaxx.springksocket.exception.WebSocketInitializationException
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
import fr.rowlaxx.springkutils.concurrent.core.TaskQueue
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URI
import java.net.http.HttpHeaders
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Service
class BaseWebSocketFactory(
    private val threads: GlobalThreadConfiguration
) {
    private val idCounter = AtomicLong()
    private val sockets = ConcurrentHashMap<Long, BaseWebSocket>()
    private val isShutdown = AtomicBoolean()


    @PreDestroy
    fun destroy() {
        isShutdown.set(true)
        runBlocking {
            while (sockets.isNotEmpty()) {
                sockets.values.toList()
                    .onEach { it.closeAsync("Application Closed") }
                    .forEach { it.joinClose() }
            }
        }
    }

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

        init {
            if (factory.isShutdown.get()) throw IllegalStateException("Cannot create a websocket since application is being shutdown")
            if (pingAfter.isNegative) throw IllegalArgumentException("pingAfter must be a positive integer")
            if (readTimeout.isNegative) throw IllegalArgumentException("readTimeout must be a positive integer")
            if (initTimeout.isNegative) throw IllegalArgumentException("initTimeout must be a positive integer")
            factory.sockets[id] = this
        }

        private var handlerIndex: Int = 0
        private val lastInData = AtomicLong()
        private var opened = false
        private var closedWith: WebSocketException? = null

        private var nextPing: Future<*>? = null
        private var nextReadTimeout: Future<*>? = null
        private var nextInitTimeout: Future<*>? = null


        override val currentHandlerIndex: Int get() = handlerIndex

        private val mainQueue = TaskQueue(factory.threads.ioDispatcher)
        private val sendQueue = TaskQueue(factory.threads.ioDispatcher, paused = true)

        override fun hasOpened(): Boolean = opened
        override fun getClosedReason(): WebSocketException? = closedWith

        private fun <T> delayed(delay: Duration, action: () -> T): Future<T>? {
            try {
                return factory.threads.taskScheduler.scheduledExecutor.schedule<T>(
                    action,
                    delay.toMillis(),
                    TimeUnit.MILLISECONDS
                )
            } catch (t: Exception) {
                return null
            }
        }

        private inline fun runHandler(handler: WebSocketHandler, action: WebSocketHandler.(WebSocket) -> Unit) {
            runCatching { action(handler, this) }
                .onFailure { factory.log.error("[{} ({})] A handler error occurred", name, id, it) }
        }

        protected abstract fun pingNow(): Job
        protected abstract fun sendText(msg: String): Job
        protected abstract fun sendBinary(msg: ByteArray): Job
        protected abstract fun handleClose()
        protected abstract fun handleOpen(obj: Any)

        fun onDataReceived() {
            val last = lastInData.get()
            val now = System.currentTimeMillis()
            val expired = last + 100 < now //Improve efficiency on large traffic websocket

            if (expired && lastInData.compareAndSet(last, now)) {
                nextPing?.cancel(true)
                nextReadTimeout?.cancel(true)

                nextPing = delayed(pingAfter) {
                    sendQueue.submit { pingNow().join() }
                }

                nextReadTimeout = delayed(readTimeout) {
                    closeWith(WebSocketConnectionException("Read timeout"))
                }
            }
        }




        override fun closeAsync(reason: String, code: Int): Job {
            return closeWith(WebSocketClosedException(reason, code))
        }

        override fun sendMessageAsync(message: Any): Job {
            return sendQueue.submit { unsafeSendMessage(message) }
        }

        protected fun closeWith(reason: WebSocketException): Job {
            return mainQueue.submit { unsafeCloseWith(reason) }
        }

        protected fun openWith(obj: Any) {
            mainQueue.submit { unsafeOpenWith(obj) }
        }

        protected fun acceptMessage(obj: Any) {
            mainQueue.submit { unsafeAcceptMessage(obj) }
        }

        override fun completeHandlerAsync(): Job {
            return mainQueue.submit { unsafeCompleteHandler() }
        }




        private fun unsafeOpenWith(obj: Any) {
            if (hasClosed() || hasOpened()) {
                return
            }

            opened = true
            handleOpen(obj)

            if (!isInitialized()) {
                nextInitTimeout = delayed(initTimeout) {
                    closeWith(WebSocketInitializationException("Initialization timeout"))
                }
            }

            onDataReceived()
            log.debug("[{} ({})] Opened", name, id)
            sendQueue.resume()
            runHandler(currentHandler) { onAvailable(it) }
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
            mainQueue.close()
            sendQueue.close()
            factory.sockets.remove(id)
            handleClose()
            log.debug("[{} ({})] Closed : {}", name, id, reason.message)
            runHandler(currentHandler) { onUnavailable(it) }
        }

        private fun unsafeCompleteHandler() {
            if (!isConnected()) {
                throw IllegalStateException("WebSocket is not connected yet")
            }

            if (handlerIndex + 1 >= handlerChain.size) {
                unsafeCloseWith(WebSocketClosedException("End of HandlerChain", 1000))
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

        private fun unsafeAcceptMessage(obj: Any) {
            if (hasClosed()) {
                return
            }

            val deserialized = currentHandler.deserializer.fromStringOrByteArray(obj)
            runHandler(currentHandler) { onMessage(it, deserialized) }
        }

        private suspend fun unsafeSendMessage(msg: Any) {
            if (hasClosed()) {
                return
            }

            val ser = when (msg) {
                is String -> msg
                is ByteArray -> msg
                else -> currentHandler.serializer.toStringOrByteArray(msg)
            }

            val job = when (ser) {
                is String -> sendText(ser)
                is ByteArray -> sendBinary(ser)
                else -> throw IllegalStateException("Message must be a String or a ByteArray after serialization. Current type : ${msg.javaClass.simpleName}")
            }

            try {
                job.join()
            } catch (e: Exception) {
                val ex = when (e) {
                    is WebSocketException -> e
                    is IOException -> WebSocketConnectionException("IOException : ${e.message}")
                    else -> WebSocketConnectionException("Unknown exception : ${e.message}")
                }

                closeWith(ex)
            }
        }

        internal suspend fun joinClose() {
            sendQueue.join()
            mainQueue.join()
        }
    }
}