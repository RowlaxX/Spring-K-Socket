package fr.rowlaxx.springksocket.service.io

import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.Dsl
import org.java_websocket.WebSocket as JWebSocket
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpHeaders
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Falsifiable proof for the "keepalive starvation" diagnosis of the exchange disconnects
 * (Binance `Pong timeout 1008`, Kucoin `Bye 1000`, Mexc `-1`).
 *
 * Hypothesis: inbound market-data processing and outbound keepalive sends share the same small
 * `ioDispatcher` (ForkJoin `ioPool`, `max(2, cores/4)` threads). When processing saturates that pool,
 * the keepalive send is starved and misses the broker's deadline, so the broker disconnects.
 *
 * These tests hold everything else constant and vary only the load / the pool. If the hypothesis is
 * wrong (keepalive stays timely under load, or a dedicated pool makes no difference) they FAIL.
 */
@Timeout(180)
class KeepaliveStarvationIT {

    private lateinit var threads: GlobalThreadConfiguration

    @BeforeEach
    fun setUp() {
        threads = GlobalThreadConfiguration()
    }

    @AfterEach
    fun tearDown() {
        threads.destroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Test 1 — mechanism, fully deterministic (no sockets).
    // A "keepalive" task submitted to the SHARED ioDispatcher while it is saturated with CPU-bound
    // "processing" is blocked for the whole processing duration; the SAME task on the async pool is not.
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `keepalive on the shared io pool is starved by cpu-bound processing, but not on a separate pool`() {
        val ioWorkers = threads.ioParallelism
        val hogMs = 2_000L
        val scope = CoroutineScope(SupervisorJob())

        // Peg every ioPool worker with a CPU-bound spin (simulates message deserialization/adaptation
        // that runs on mainQueue == ioDispatcher for every inbound frame).
        val allHogsSpinning = CountDownLatch(ioWorkers)
        repeat(ioWorkers) {
            scope.launch(threads.ioDispatcher) {
                allHogsSpinning.countDown()
                busySpin(hogMs)
            }
        }
        assertTrue(allHogsSpinning.await(10, TimeUnit.SECONDS), "hogs never started")
        Thread.sleep(50) // ensure all workers are mid-spin before we submit the keepalive tasks

        // Submit two identical "keepalive" tasks at the same instant: one to the saturated shared pool,
        // one to the (idle) async pool. Measure how long each waits before it actually runs.
        val submitAt = System.nanoTime()
        val ranOnIo = CompletableDeferred<Long>()
        val ranOnAsync = CompletableDeferred<Long>()
        scope.launch(threads.ioDispatcher) { ranOnIo.complete(System.nanoTime()) }
        scope.launch(threads.asyncDispatcher) { ranOnAsync.complete(System.nanoTime()) }

        val (ioLatencyMs, asyncLatencyMs) = runBlocking {
            (ranOnIo.await() - submitAt) / 1e6 to (ranOnAsync.await() - submitAt) / 1e6
        }

        println("[mechanism] ioParallelism=$ioWorkers  hog=${hogMs}ms")
        println("[mechanism] keepalive latency on SHARED ioDispatcher = %.0f ms".format(ioLatencyMs))
        println("[mechanism] keepalive latency on SEPARATE asyncDispatcher = %.0f ms".format(asyncLatencyMs))

        // Separate pool: keepalive runs essentially immediately.
        assertTrue(
            asyncLatencyMs < 300,
            "control failed: keepalive on a separate pool should be prompt but took ${asyncLatencyMs.toLong()}ms"
        )
        // Shared pool: keepalive is blocked ~ the whole processing burst — this is the starvation.
        assertTrue(
            ioLatencyMs > hogMs * 0.5,
            "hypothesis NOT reproduced: keepalive on the shared ioDispatcher was only ${ioLatencyMs.toLong()}ms"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Test 2 — end-to-end through the real ClientWebSocketFactory / BaseWebSocket send path.
    // Measures the wall-clock latency of an application-level ping actually arriving at the server,
    // idle vs. while other sockets flood the client with frames whose handler is CPU-heavy.
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `websocket ping delivery latency explodes under message-processing load on the shared io pool`() {
        val loadSockets = max(8, threads.ioParallelism * 4)
        val spinMsPerFrame = 8L

        val server = LoadServer(0).apply { start() }
        assertTrue(server.started.await(10, TimeUnit.SECONDS), "server did not start")

        val eventLoopGroup = threads.ioEventLoopGroup
        val client: AsyncHttpClient = Dsl.asyncHttpClient(
            DefaultAsyncHttpClientConfig.Builder()
                .setEventLoopGroup(eventLoopGroup)
                .setRequestTimeout(Duration.ofMillis(-1))
                .setReadTimeout(Duration.ofMillis(-1))
                .setWebSocketMaxFrameSize(16 * 1024 * 1024)
                .build()
        )
        val factory = ClientWebSocketFactory(BaseWebSocketFactory(threads), threads, client)

        try {
            // The socket we send pings on — the server does NOT flood it; its handler does no work.
            val pingHandler = SilentHandler()
            val pingSocket = factory.connect("ping", props(server.port, "/ping"), listOf(pingHandler)) { error("ping init: ${it.message}") }
            assertTrue(pingHandler.available.await(10, TimeUnit.SECONDS), "ping socket never opened")

            // ---- Phase 1: BASELINE (no load) -------------------------------------------------------
            val baseSent = sendPings(pingSocket, firstSeq = 0, count = 12, cadenceMs = 150)
            val baseline = collect(baseSent, server, drainMs = 5_000)

            // ---- Phase 2: LOADED -------------------------------------------------------------------
            // Open the load sockets; the server floods each one and the handler burns CPU per frame,
            // saturating the shared ioPool that the ping send also has to run on.
            val loadHandlers = (1..loadSockets).map { CpuBurningHandler(spinMsPerFrame) }
            // Don't wait for the client-side `onAvailable` — under this very load it is itself starved
            // on the ioPool. The server floods each /load socket from its own onOpen, so just wait for
            // the flood to ramp and saturate the pool.
            val loadWs = loadHandlers.map { factory.connect("load", props(server.port, "/load"), listOf(it)) { } }
            Thread.sleep(2_500)

            val loadSent = sendPings(pingSocket, firstSeq = 1_000, count = 25, cadenceMs = 150)
            // Stop the flood so the backlog can drain and the delayed pings eventually land — turning
            // "never arrived" into a measurable (still huge) latency instead of a censored lower bound.
            loadWs.forEach { it.closeAsync("load done") }
            val loaded = collect(loadSent, server, drainMs = 40_000)

            val baseP50 = percentile(baseline.latenciesMs, 50.0)
            val loadP50 = percentile(loaded.latenciesMs, 50.0)
            val loadMax = loaded.latenciesMs.maxOrNull() ?: -1.0

            println("[e2e] loadSockets=$loadSockets spin=${spinMsPerFrame}ms ioParallelism=${threads.ioParallelism}")
            println("[e2e] BASELINE pings: sent=${baseline.sent} arrived=${baseline.arrived} p50=%.0fms max=%.0fms"
                .format(baseP50, baseline.latenciesMs.maxOrNull() ?: -1.0))
            println("[e2e] LOADED   pings: sent=${loaded.sent} arrived=${loaded.arrived} never-within-40s=${loaded.sent - loaded.arrived} p50=%.0fms max=%.0fms"
                .format(loadP50, loadMax))

            // Baseline: the send path is timely when the pool is free.
            assertTrue(baseP50 < 300, "baseline ping latency should be small but p50=${baseP50.toLong()}ms")
            // Under load: the ping (same send path, same pool) is delayed by seconds — enough to blow
            // past real broker deadlines (Kucoin ~18s window, Mexc ~60s, Binance 60s pong).
            assertTrue(
                loadP50 > 1_000 && loadP50 > baseP50 * 8,
                "hypothesis NOT reproduced: loaded ping p50=${loadP50.toLong()}ms vs baseline p50=${baseP50.toLong()}ms"
            )
        } finally {
            runCatching { client.close() }
            runCatching { server.stop(1_000) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Test 3 — the pong path actually works: the server sends WS ping frames, the library must pong
    // each one (this is the Binance `Pong timeout 1008` path). AHC 3.0.11 does NOT auto-pong; the pong
    // is entirely the library listener's responsibility, so this guards that it is really sent.
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `library pongs every server ping frame`() {
        val server = PingingServer(0).apply { start() }
        assertTrue(server.started.await(10, TimeUnit.SECONDS), "server did not start")

        val client: AsyncHttpClient = Dsl.asyncHttpClient(
            DefaultAsyncHttpClientConfig.Builder()
                .setEventLoopGroup(threads.ioEventLoopGroup)
                .setRequestTimeout(Duration.ofMillis(-1))
                .setReadTimeout(Duration.ofMillis(-1))
                .build()
        )
        val factory = ClientWebSocketFactory(BaseWebSocketFactory(threads), threads, client)
        try {
            val handler = SilentHandler()
            factory.connect("pong", props(server.port, "/"), listOf(handler)) { error("pong init: ${it.message}") }
            assertTrue(handler.available.await(10, TimeUnit.SECONDS), "client never opened")
            assertTrue(server.opened.await(10, TimeUnit.SECONDS), "server never saw the client")

            val pings = 5
            repeat(pings) { server.conn?.sendPing(); Thread.sleep(200) }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (server.pongs.get() < pings && System.nanoTime() < deadline) Thread.sleep(20)

            println("[pong-path] server sent $pings pings, received ${server.pongs.get()} pongs")
            assertEquals(pings, server.pongs.get(), "library failed to pong every server ping")
        } finally {
            runCatching { client.close() }
            runCatching { server.stop(1_000) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private class PingRun(val sent: Int, val arrived: Int, val latenciesMs: List<Double>)

    /** Sends [count] `PING:<seq>` frames at [cadenceMs], recording the wall-clock send time of each. */
    private fun sendPings(socket: WebSocket, firstSeq: Int, count: Int, cadenceMs: Long): Map<Int, Long> {
        val sentAt = LinkedHashMap<Int, Long>()
        for (i in 0 until count) {
            val seq = firstSeq + i
            sentAt[seq] = System.nanoTime()
            socket.sendMessageAsync("PING:$seq")
            Thread.sleep(cadenceMs)
        }
        return sentAt
    }

    /**
     * Waits up to [drainMs] for the pings to reach the server, then returns per-ping latency.
     * A ping that never arrived is right-censored to [drainMs] (a lower bound) so the metric is
     * always defined — a non-arrival is a *worse* outcome than a large latency, not a missing sample.
     */
    private fun collect(sentAt: Map<Int, Long>, server: LoadServer, drainMs: Long): PingRun {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainMs)
        while (sentAt.keys.any { server.pingArrivals[it] == null } && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        var arrived = 0
        val latencies = sentAt.map { (seq, t0) ->
            val a = server.pingArrivals[seq]
            if (a != null) { arrived++; (a - t0) / 1e6 } else drainMs.toDouble()
        }.sorted()
        return PingRun(sentAt.size, arrived, latencies)
    }

    private fun props(port: Int, path: String) = WebSocketClientProperties(
        uri = URI.create("ws://${InetAddress.getLoopbackAddress().hostAddress}:$port$path"),
        headers = HttpHeaders.of(emptyMap()) { _, _ -> true },
        initTimeout = Duration.ofSeconds(10),
        pingInterval = Duration.ofSeconds(30),
        readTimeout = Duration.ofSeconds(60),
    )

    private class SilentHandler : WebSocketHandler {
        override val serializer = WebSocketSerializer.Passthrough
        override val deserializer = WebSocketDeserializer.Passthrough
        val available = CountDownLatch(1)
        override fun onAvailable(webSocket: WebSocket) = available.countDown()
        override fun onMessage(webSocket: WebSocket, msg: Any) {}
    }

    /** Simulates heavy per-frame deserialization/adaptation, running on mainQueue (== ioDispatcher). */
    private class CpuBurningHandler(private val spinMs: Long) : WebSocketHandler {
        override val serializer = WebSocketSerializer.Passthrough
        override val deserializer = WebSocketDeserializer.Passthrough
        val available = CountDownLatch(1)
        override fun onAvailable(webSocket: WebSocket) = available.countDown()
        override fun onMessage(webSocket: WebSocket, msg: Any) = busySpin(spinMs)
    }

    /** Loopback server that sends WS ping frames and counts the pongs the client sends back. */
    private class PingingServer(port: Int) : WebSocketServer(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) {
        val started = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val pongs = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var conn: JWebSocket? = null

        init { connectionLostTimeout = 0 } // disable the server's own auto-ping so we control it

        override fun onStart() = started.countDown()
        override fun onOpen(conn: JWebSocket, handshake: ClientHandshake) { this.conn = conn; opened.countDown() }
        override fun onClose(conn: JWebSocket, code: Int, reason: String?, remote: Boolean) {}
        override fun onError(conn: JWebSocket?, ex: Exception) {}
        override fun onMessage(conn: JWebSocket, message: String) {}
        override fun onWebsocketPong(conn: JWebSocket, f: Framedata) { pongs.incrementAndGet() }
    }

    /** Loopback server: floods `/load` connections and timestamps `PING:<seq>` arrivals. */
    private class LoadServer(port: Int) : WebSocketServer(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) {
        val started = CountDownLatch(1)
        val pingArrivals = ConcurrentHashMap<Int, Long>()
        private val flooders = Executors.newCachedThreadPool(ThreadFactory { r -> Thread(r, "flood").apply { isDaemon = true } })

        override fun onStart() = started.countDown()

        override fun onOpen(conn: JWebSocket, handshake: ClientHandshake) {
            if (handshake.resourceDescriptor.startsWith("/load")) {
                flooders.submit {
                    // Send at a steady rate; the client's CPU-heavy handler can't keep up, so the
                    // shared ioPool stays pegged for the duration of the load phase.
                    while (conn.isOpen) {
                        runCatching { conn.send("D") }
                        Thread.sleep(6)
                    }
                }
            }
        }

        override fun onClose(conn: JWebSocket, code: Int, reason: String?, remote: Boolean) {}
        override fun onError(conn: JWebSocket?, ex: Exception) {}
        override fun onMessage(conn: JWebSocket, message: String) {
            if (message.startsWith("PING:")) {
                pingArrivals[message.substring(5).toInt()] = System.nanoTime()
            }
        }
        override fun onMessage(conn: JWebSocket, message: ByteBuffer) {}
        override fun stop(timeout: Int) { runCatching { flooders.shutdownNow() }; super.stop(timeout) }
    }

    companion object {
        @Volatile private var sink: Long = 0

        /** CPU-bound busy-wait for [ms] milliseconds (not `sleep`; must actually occupy a core). */
        private fun busySpin(ms: Long) {
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
            var acc = 0L
            var i = 0L
            while (System.nanoTime() < end) {
                i++
                acc += (i * 2654435761L) xor (i shl 13)
            }
            sink = acc
        }

        private fun percentile(sortedAsc: List<Double>, p: Double): Double {
            if (sortedAsc.isEmpty()) return -1.0
            val idx = ((p / 100.0) * (sortedAsc.size - 1)).toInt()
            return sortedAsc[idx]
        }
    }
}
