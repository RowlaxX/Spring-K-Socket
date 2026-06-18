package fr.rowlaxx.springksocket.service.aop

import fr.rowlaxx.springksocket.annotation.OnMessage
import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class TradeMsg(val v: Int)
data class OrderbookMsg(val v: Int)
class UnhandledMsg

/** Minimal fake; only [sendMessageAsync] is reachable, and only if a handler returns a value. */
class FakePerpetualWebSocket : PerpetualWebSocket {
    val sent = mutableListOf<Any>()
    override fun sendMessageAsync(message: Any): Job { sent += message; return Job() }
    override val id = 0
    override val name = "fake"
    override val switchDuration: Duration = Duration.ZERO
    override val shiftDuration: Duration = Duration.ZERO
    override val initializers: List<WebSocketHandler> = emptyList()
    override val handler get() = throw UnsupportedOperationException()
    override val propertiesFactory: () -> WebSocketClientProperties get() = throw UnsupportedOperationException()
    override fun isConnected() = true
}

class TestPerpetualHandlerBean {
    val trades = AtomicInteger()
    val orderbooks = AtomicInteger()
    val tradesSecond = AtomicInteger()
    val lastTradeWs = AtomicReference<PerpetualWebSocket?>()

    @OnMessage
    fun onTrade(ws: PerpetualWebSocket, msg: TradeMsg) {
        trades.incrementAndGet()
        lastTradeWs.set(ws)
    }

    @OnMessage
    fun onTradeSecond(ws: PerpetualWebSocket, msg: TradeMsg) {
        tradesSecond.incrementAndGet()
    }

    @OnMessage
    fun onOrderbook(ws: PerpetualWebSocket, msg: OrderbookMsg) {
        orderbooks.incrementAndGet()
    }
}

/**
 * Dispatch contract for [PerpetualWebSocketHandlerFactory] that the caching refactor must preserve:
 * a message is delivered to every `@OnMessage` whose parameter type matches its runtime class, the
 * `PerpetualWebSocket` is forwarded, and unknown / `Unit` messages are ignored.
 */
class PerpetualWebSocketHandlerDispatchTest {

    private fun handlerFor(bean: Any) = PerpetualWebSocketHandlerFactory()
        .extract(bean, WebSocketSerializer.Passthrough, WebSocketDeserializer.Passthrough)

    @Test
    fun `message is routed only to handlers whose parameter type matches`() {
        val bean = TestPerpetualHandlerBean()
        val handler = handlerFor(bean)
        val ws = FakePerpetualWebSocket()

        handler.onMessage(ws, TradeMsg(1))

        assertEquals(1, bean.trades.get())
        assertEquals(1, bean.tradesSecond.get(), "both TradeMsg handlers should fire (fan-out)")
        assertEquals(0, bean.orderbooks.get())
        assertSame(ws, bean.lastTradeWs.get(), "the websocket must be forwarded to the handler")

        handler.onMessage(ws, OrderbookMsg(1))
        assertEquals(1, bean.orderbooks.get())
        assertEquals(1, bean.trades.get(), "OrderbookMsg must not reach trade handlers")
    }

    @Test
    fun `unhandled message type is ignored`() {
        val bean = TestPerpetualHandlerBean()
        val handler = handlerFor(bean)
        val ws = FakePerpetualWebSocket()

        handler.onMessage(ws, UnhandledMsg()) // no matching @OnMessage -> no-op, no throw

        assertEquals(0, bean.trades.get())
        assertEquals(0, bean.orderbooks.get())
    }

    @Test
    fun `Unit message is ignored`() {
        val bean = TestPerpetualHandlerBean()
        val handler = handlerFor(bean)
        handler.onMessage(FakePerpetualWebSocket(), Unit)
        assertEquals(0, bean.trades.get())
    }

    @Test
    fun `repeated dispatch of the same type stays correct`() {
        val bean = TestPerpetualHandlerBean()
        val handler = handlerFor(bean)
        val ws = FakePerpetualWebSocket()

        repeat(1000) { handler.onMessage(ws, TradeMsg(it)) }
        repeat(500) { handler.onMessage(ws, OrderbookMsg(it)) }

        assertEquals(1000, bean.trades.get())
        assertEquals(1000, bean.tradesSecond.get())
        assertEquals(500, bean.orderbooks.get())
    }
}
