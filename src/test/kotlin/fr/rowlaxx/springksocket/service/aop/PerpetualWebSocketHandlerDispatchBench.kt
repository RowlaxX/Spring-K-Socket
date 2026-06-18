package fr.rowlaxx.springksocket.service.aop

import com.sun.management.ThreadMXBean
import fr.rowlaxx.springksocket.annotation.OnMessage
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong

class BenchMsgA(val v: Int)
class BenchMsgB(val v: Int)
class BenchMsgC(val v: Int)
class BenchMsgD(val v: Int)

class BenchHandlerBean {
    val hits = AtomicLong()
    @OnMessage fun a(ws: PerpetualWebSocket, m: BenchMsgA) { hits.incrementAndGet() }
    @OnMessage fun b(ws: PerpetualWebSocket, m: BenchMsgB) { hits.incrementAndGet() }
    @OnMessage fun c(ws: PerpetualWebSocket, m: BenchMsgC) { hits.incrementAndGet() }
    @OnMessage fun d(ws: PerpetualWebSocket, m: BenchMsgD) { hits.incrementAndGet() }
}

/**
 * Not a correctness test — measures the per-message cost of [PerpetualWebSocketHandlerFactory]'s
 * `onMessage` dispatch (4 candidate `@OnMessage` handlers, allocation-free bodies), so the number
 * reflects only the dispatch overhead (handler selection + invocation). Run manually:
 *   ./gradlew test --tests "*PerpetualWebSocketHandlerDispatchBench" -i
 */
class PerpetualWebSocketHandlerDispatchBench {

    @Test
    fun `onMessage dispatch bytes per op and ns per op`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val handler = PerpetualWebSocketHandlerFactory()
            .extract(BenchHandlerBean(), WebSocketSerializer.Passthrough, WebSocketDeserializer.Passthrough)
        val ws = FakePerpetualWebSocket()
        val msg = BenchMsgA(1) // reused: only dispatch overhead is measured

        repeat(1_000_000) { handler.onMessage(ws, msg) }

        val iterations = 5_000_000
        val tid = Thread.currentThread().threadId()
        val bytesBefore = bean.getThreadAllocatedBytes(tid)
        val t0 = System.nanoTime()
        for (i in 0 until iterations) {
            handler.onMessage(ws, msg)
        }
        val nanos = System.nanoTime() - t0
        val bytes = bean.getThreadAllocatedBytes(tid) - bytesBefore

        println("=== PerpetualWebSocketHandler.onMessage dispatch (4 candidates) ===")
        println("iterations    = $iterations")
        println("bytes/op      = ${"%.3f".format(bytes.toDouble() / iterations)}")
        println("ns/op         = ${"%.2f".format(nanos.toDouble() / iterations)}")
    }
}
