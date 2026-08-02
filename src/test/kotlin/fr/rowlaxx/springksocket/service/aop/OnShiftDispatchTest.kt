package fr.rowlaxx.springksocket.service.aop

import fr.rowlaxx.springksocket.annotation.OnAvailable
import fr.rowlaxx.springksocket.annotation.OnShift
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private data class ShiftCall(val previous: WebSocket?, val next: WebSocket?)

/** A valid handler (has an @OnAvailable so `extract` accepts it) that records every shift. */
private class ShiftHandlerBean {
    val shifts = mutableListOf<ShiftCall>()

    @OnAvailable
    fun onAvailable() {}

    @OnShift
    fun onShift(previous: WebSocket?, next: WebSocket?) {
        shifts += ShiftCall(previous, next)
    }
}

/** Two @OnShift methods to prove fan-out. */
private class MultiShiftHandlerBean {
    val first = mutableListOf<ShiftCall>()
    val second = mutableListOf<ShiftCall>()

    @OnAvailable
    fun onAvailable() {}

    @OnShift
    fun onShiftA(previous: WebSocket?, next: WebSocket?) { first += ShiftCall(previous, next) }

    @OnShift
    fun onShiftB(previous: WebSocket?, next: WebSocket?) { second += ShiftCall(previous, next) }
}

/** Returns a value from @OnShift; the dispatcher must auto-send it through the perpetual socket. */
private class ReturningShiftHandlerBean {
    @OnAvailable
    fun onAvailable() {}

    @OnShift
    fun onShift(previous: WebSocket?, next: WebSocket?): String = "resubscribe"
}

/** Only an @OnShift method: not enough to be a handler. */
private class OnlyShiftBean {
    @OnShift
    fun onShift(previous: WebSocket?, next: WebSocket?) {}
}

/**
 * Dispatch contract for `@OnShift` in [PerpetualWebSocketHandlerFactory]: the annotated method is
 * invoked on every shift with the previous and new connections, forwarded **positionally**. This is
 * the property the implementation must protect — both parameters share the type `WebSocket?`, so the
 * type-based argument resolver used by the other lifecycle hooks would bind both to the same value.
 */
class OnShiftDispatchTest {

    private fun handlerFor(bean: Any) = PerpetualWebSocketHandlerFactory()
        .extract(bean, WebSocketSerializer.Passthrough, WebSocketDeserializer.Passthrough)

    private fun fakeWs(wsId: Long): WebSocket = object : BenchFakeWebSocket() { override val id = wsId }

    @Test
    fun `onShift receives previous and next in order`() {
        val bean = ShiftHandlerBean()
        val handler = handlerFor(bean)
        val previous = fakeWs(100L)
        val next = fakeWs(200L)

        handler.onShift(FakePerpetualWebSocket(), previous, next)

        assertEquals(1, bean.shifts.size)
        assertSame(previous, bean.shifts[0].previous, "previous must be the first parameter")
        assertSame(next, bean.shifts[0].next, "next must be the second parameter")
    }

    /**
     * The two connections share the type `WebSocket?`, so a type-based argument resolver would bind
     * both parameters to the first `WebSocket` it sees, collapsing them. This asserts they are kept
     * distinct and delivered to the correct positional slots.
     */
    @Test
    fun `the two websockets are injected as distinct objects, not conflated`() {
        val bean = ShiftHandlerBean()
        val handler = handlerFor(bean)
        val previous = fakeWs(100L)
        val next = fakeWs(200L)

        handler.onShift(FakePerpetualWebSocket(), previous, next)

        val call = bean.shifts.single()
        assertEquals(100L, call.previous?.id, "slot 0 must carry the previous connection")
        assertEquals(200L, call.next?.id, "slot 1 must carry the new connection")
        assertNotSame(call.previous, call.next, "the two slots must not be the same object")
    }

    @Test
    fun `onShift forwards a null previous (first connection)`() {
        val bean = ShiftHandlerBean()
        val handler = handlerFor(bean)
        val next = fakeWs(200L)

        handler.onShift(FakePerpetualWebSocket(), null, next)

        assertEquals(1, bean.shifts.size)
        assertNull(bean.shifts[0].previous)
        assertSame(next, bean.shifts[0].next)
    }

    @Test
    fun `onShift forwards a null next`() {
        val bean = ShiftHandlerBean()
        val handler = handlerFor(bean)
        val previous = fakeWs(100L)

        handler.onShift(FakePerpetualWebSocket(), previous, null)

        assertEquals(1, bean.shifts.size)
        assertSame(previous, bean.shifts[0].previous)
        assertNull(bean.shifts[0].next)
    }

    @Test
    fun `every @OnShift method fires (fan-out)`() {
        val bean = MultiShiftHandlerBean()
        val handler = handlerFor(bean)
        val previous = fakeWs(100L)
        val next = fakeWs(200L)

        handler.onShift(FakePerpetualWebSocket(), previous, next)

        assertEquals(listOf(ShiftCall(previous, next)), bean.first)
        assertEquals(listOf(ShiftCall(previous, next)), bean.second)
    }

    @Test
    fun `a value returned from @OnShift is sent through the perpetual socket`() {
        val bean = ReturningShiftHandlerBean()
        val handler = handlerFor(bean)
        val ws = FakePerpetualWebSocket()

        handler.onShift(ws, fakeWs(100L), fakeWs(200L))

        assertEquals(listOf<Any>("resubscribe"), ws.sent)
    }

    @Test
    fun `a handler without @OnShift ignores shifts`() {
        val handler = handlerFor(TestPerpetualHandlerBean()) // only @OnMessage methods

        // no matching @OnShift -> no-op, no throw
        handler.onShift(FakePerpetualWebSocket(), fakeWs(100L), fakeWs(200L))
    }

    @Test
    fun `@OnShift alone does not make a bean a handler`() {
        assertThrows(IllegalArgumentException::class.java) { handlerFor(OnlyShiftBean()) }
    }
}
