package fr.rowlaxx.springksocket.service.aop

import com.sun.management.ThreadMXBean
import fr.rowlaxx.springksocket.annotation.OnMessage
import fr.rowlaxx.springksocket.data.WebSocketAttributes
import fr.rowlaxx.springksocket.exception.WebSocketException
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.http.HttpHeaders
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class ClientBenchMsgA(val v: Int)
class ClientBenchMsgB(val v: Int)
class ClientBenchMsgC(val v: Int)
class ClientBenchMsgD(val v: Int)

class ClientBenchHandlerBean {
    val hits = AtomicLong()
    @OnMessage fun a(ws: WebSocket, m: ClientBenchMsgA) { hits.incrementAndGet() }
    @OnMessage fun b(ws: WebSocket, m: ClientBenchMsgB) { hits.incrementAndGet() }
    @OnMessage fun c(ws: WebSocket, m: ClientBenchMsgC) { hits.incrementAndGet() }
    @OnMessage fun d(ws: WebSocket, m: ClientBenchMsgD) { hits.incrementAndGet() }
}

/** Minimal fake; only [sendMessageAsync] is reachable, and only if a handler returns a value. */
open class BenchFakeWebSocket : WebSocket {
    override val id = 1L
    override val name = "bench"
    override val uri: URI = URI.create("ws://bench")
    override val pingInterval: Duration = Duration.ofSeconds(1)
    override val readTimeout: Duration = Duration.ofSeconds(1)
    override val initTimeout: Duration = Duration.ofSeconds(1)
    override val attributes = WebSocketAttributes()
    override val handlerChain: List<WebSocketHandler> = emptyList()
    override val currentHandlerIndex = 0
    override val requestHeaders: HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
    // open so correctness tests can record auto-sent return values
    override fun sendMessageAsync(message: Any): Deferred<Unit> = CompletableDeferred(Unit)
    override fun completeHandlerAsync(): Job = Job().also { it.complete() }
    override fun closeAsync(reason: String, code: Int): Job = Job().also { it.complete() }
    override fun getClosedReason(): WebSocketException? = null
    override fun hasOpened(): Boolean = true
}

/**
 * Not a correctness test — measures the per-message cost of [WebSocketHandlerFactory]'s
 * `onMessage` dispatch (4 candidate `@OnMessage` handlers, allocation-free bodies), so the number
 * reflects only the dispatch overhead (handler selection + invocation). Both a single-type hot
 * loop and a mixed-type rotation are measured. Run manually:
 *   ./gradlew test --tests "*WebSocketHandlerDispatchBench" -i
 */
class WebSocketHandlerDispatchBench {

    private fun measure(label: String, handler: WebSocketHandler, messages: Array<Any>) {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val ws = BenchFakeWebSocket()

        repeat(1_000_000) { handler.onMessage(ws, messages[it % messages.size]) }

        val iterations = 5_000_000
        val tid = Thread.currentThread().threadId()
        val bytesBefore = bean.getThreadAllocatedBytes(tid)
        val t0 = System.nanoTime()
        for (i in 0 until iterations) {
            handler.onMessage(ws, messages[i % messages.size])
        }
        val nanos = System.nanoTime() - t0
        val bytes = bean.getThreadAllocatedBytes(tid) - bytesBefore

        println("=== WebSocketHandler.onMessage dispatch (4 candidates, $label) ===")
        println("iterations    = $iterations")
        println("bytes/op      = ${"%.3f".format(bytes.toDouble() / iterations)}")
        println("ns/op         = ${"%.2f".format(nanos.toDouble() / iterations)}")
        println("msgs/sec      = ${"%.0f".format(iterations / (nanos / 1e9))}")
    }

    @Test
    fun `onMessage dispatch bytes per op and ns per op`() {
        val handler = WebSocketHandlerFactory(AutoWebSocketCollectionManager())
            .extract(ClientBenchHandlerBean(), WebSocketSerializer.Passthrough, WebSocketDeserializer.Passthrough)

        measure("single type", handler, arrayOf(ClientBenchMsgA(1)))
        measure("mixed types", handler, arrayOf(ClientBenchMsgA(1), ClientBenchMsgB(2), ClientBenchMsgC(3), ClientBenchMsgD(4)))
    }
}
