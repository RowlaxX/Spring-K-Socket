package fr.rowlaxx.springksocket.service.aop

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import fr.rowlaxx.springksocket.annotation.OnMessage
import fr.rowlaxx.springksocket.data.WebSocketAttributes
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class ClientTradeMsg(val v: Int)
data class ClientOrderbookMsg(val v: Int)
class ClientUnhandledMsg

class TestClientHandlerBean {
    val trades = AtomicInteger()
    val orderbooks = AtomicInteger()
    val tradesSecond = AtomicInteger()
    val lastTradeWs = AtomicReference<WebSocket?>()
    val lastAttributes = AtomicReference<WebSocketAttributes?>()

    @OnMessage
    fun onTrade(ws: WebSocket, attributes: WebSocketAttributes, msg: ClientTradeMsg) {
        trades.incrementAndGet()
        lastTradeWs.set(ws)
        lastAttributes.set(attributes)
    }

    @OnMessage
    fun onTradeSecond(ws: WebSocket, msg: ClientTradeMsg) {
        tradesSecond.incrementAndGet()
    }

    @OnMessage
    fun onOrderbook(ws: WebSocket, msg: ClientOrderbookMsg): String {
        orderbooks.incrementAndGet()
        return "ack:${msg.v}"
    }
}

/**
 * Dispatch contract for [WebSocketHandlerFactory] that the caching refactor must preserve: a
 * message is delivered to every `@OnMessage` whose parameter type matches its runtime class, the
 * `WebSocket` and its attributes are forwarded, non-Unit return values are auto-sent, unknown
 * message types warn without throwing, `Unit` is ignored, and dispatch is safe when a single
 * handler instance is driven concurrently from many sockets' task queues.
 */
class WebSocketHandlerDispatchTest {

    private fun handlerFor(bean: Any): WebSocketHandler = WebSocketHandlerFactory(AutoWebSocketCollectionManager())
        .extract(bean, WebSocketSerializer.Passthrough, WebSocketDeserializer.Passthrough)

    private class SendRecordingWebSocket : BenchFakeWebSocket() {
        val sent = java.util.concurrent.ConcurrentLinkedQueue<Any>()
        override fun sendMessageAsync(message: Any) = super.sendMessageAsync(message).also { sent += message }
    }

    @Test
    fun `message is routed only to handlers whose parameter type matches`() {
        val bean = TestClientHandlerBean()
        val handler = handlerFor(bean)
        val ws = SendRecordingWebSocket()

        handler.onMessage(ws, ClientTradeMsg(1))

        assertEquals(1, bean.trades.get())
        assertEquals(1, bean.tradesSecond.get(), "both ClientTradeMsg handlers should fire (fan-out)")
        assertEquals(0, bean.orderbooks.get())
        assertSame(ws, bean.lastTradeWs.get(), "the websocket must be forwarded to the handler")
        assertSame(ws.attributes, bean.lastAttributes.get(), "the attributes must be forwarded to the handler")

        handler.onMessage(ws, ClientOrderbookMsg(7))
        assertEquals(1, bean.orderbooks.get())
        assertEquals(1, bean.trades.get(), "ClientOrderbookMsg must not reach trade handlers")
        assertEquals("ack:7", ws.sent.firstOrNull(), "a returned value must be auto-sent")
    }

    @Test
    fun `unhandled message type warns and is ignored`() {
        val bean = TestClientHandlerBean()
        val handler = handlerFor(bean)

        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            handler.onMessage(BenchFakeWebSocket(), ClientUnhandledMsg()) // no matching @OnMessage -> no-op, no throw
        } finally {
            logger.detachAppender(appender)
        }

        assertEquals(0, bean.trades.get())
        assertEquals(0, bean.orderbooks.get())
        assertTrue(appender.list.any { it.level == Level.WARN && "Unhandled message" in it.formattedMessage },
            "an unhandled message type must log a warning")
    }

    @Test
    fun `Unit message is ignored`() {
        val bean = TestClientHandlerBean()
        val handler = handlerFor(bean)
        handler.onMessage(BenchFakeWebSocket(), Unit)
        assertEquals(0, bean.trades.get())
    }

    @Test
    fun `repeated dispatch of the same type stays correct`() {
        val bean = TestClientHandlerBean()
        val handler = handlerFor(bean)
        val ws = BenchFakeWebSocket()

        repeat(1000) { handler.onMessage(ws, ClientTradeMsg(it)) }
        repeat(500) { handler.onMessage(ws, ClientOrderbookMsg(it)) }

        assertEquals(1000, bean.trades.get())
        assertEquals(1000, bean.tradesSecond.get())
        assertEquals(500, bean.orderbooks.get())
    }

    @Test
    fun `concurrent dispatch from many sockets is safe`() {
        // One handler instance is shared by every socket of a server bean, and each socket's task
        // queue runs on a shared pool — so onMessage must tolerate genuine concurrency.
        val bean = TestClientHandlerBean()
        val handler = handlerFor(bean)
        val threads = 8
        val perThread = 5_000
        val start = CountDownLatch(1)
        val workers = (0 until threads).map { t ->
            Thread {
                val ws = BenchFakeWebSocket()
                start.await()
                repeat(perThread) {
                    if ((it + t) % 2 == 0) handler.onMessage(ws, ClientTradeMsg(it))
                    else handler.onMessage(ws, ClientOrderbookMsg(it))
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(30_000) }

        val expectedEach = threads * perThread / 2
        assertEquals(expectedEach, bean.trades.get())
        assertEquals(expectedEach, bean.tradesSecond.get())
        assertEquals(expectedEach, bean.orderbooks.get())
    }
}
