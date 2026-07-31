package fr.rowlaxx.springksocket.service.perp

import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.PerpetualWebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import fr.rowlaxx.springksocket.service.io.BaseWebSocketFactory
import fr.rowlaxx.springksocket.service.io.ClientWebSocketFactory
import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.Dsl
import org.java_websocket.WebSocket as JWebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpHeaders
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lifecycle and leak tests for [PerpetualWebSocketFactory]:
 *
 * - shutdown must cancel the failsafe reconnect loop (no further connection attempts, no orphan
 *   sockets left registered in [BaseWebSocketFactory]);
 * - a shut-down perpetual socket and its underlying transport socket must become unreachable
 *   (WeakReference + GC, mirroring `BaseWebSocketFactoryTest`);
 * - sends rejected by a saturated offline queue must fail their [Deferred] promptly, buffered sends
 *   must fail (not hang) on close, and buffered payloads must be released for GC.
 */
@Timeout(90)
class PerpetualLifecycleIT {

    private class EchoServer(port: Int) : WebSocketServer(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) {
        val started = CountDownLatch(1)
        val received = ConcurrentHashMap.newKeySet<String>()
        override fun onStart() { started.countDown() }
        override fun onOpen(conn: JWebSocket, handshake: ClientHandshake) {}
        override fun onClose(conn: JWebSocket, code: Int, reason: String?, remote: Boolean) {}
        override fun onError(conn: JWebSocket?, ex: Exception) {}
        override fun onMessage(conn: JWebSocket, message: String) { received += message }
        override fun onMessage(conn: JWebSocket, message: ByteBuffer) {}
    }

    private class RecordingHandler : PerpetualWebSocketHandler {
        override val serializer = WebSocketSerializer.Passthrough
        override val deserializer = WebSocketDeserializer.Passthrough
        val available = CountDownLatch(1)
        override fun onAvailable(webSocket: PerpetualWebSocket) { available.countDown() }
        override fun onMessage(webSocket: PerpetualWebSocket, msg: Any) {}
    }

    private lateinit var threads: GlobalThreadConfiguration
    private lateinit var client: AsyncHttpClient
    private lateinit var baseFactory: BaseWebSocketFactory
    private lateinit var factory: PerpetualWebSocketFactory

    @BeforeEach
    fun setUp() {
        threads = GlobalThreadConfiguration()
        // No setEventLoopGroup: AHC 3.0.x does not recognize netty 4.2's MultiThreadIoEventLoopGroup,
        // so let it create (and release on close()) its own NioEventLoopGroup.
        client = Dsl.asyncHttpClient(
            DefaultAsyncHttpClientConfig.Builder()
                .setRequestTimeout(Duration.ofMillis(-1))
                .setReadTimeout(Duration.ofMillis(-1))
                .build()
        )
        baseFactory = BaseWebSocketFactory(threads)
        factory = PerpetualWebSocketFactory(ClientWebSocketFactory(baseFactory, threads, client), threads)
    }

    @AfterEach
    fun tearDown() {
        runCatching { factory.shutdown() }
        runCatching { client.close() }
        threads.destroy()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun props(port: Int): () -> WebSocketClientProperties = {
        WebSocketClientProperties(
            uri = URI.create("ws://${InetAddress.getLoopbackAddress().hostAddress}:$port"),
            headers = HttpHeaders.of(emptyMap()) { _, _ -> true },
            initTimeout = Duration.ofSeconds(10),
            pingInterval = Duration.ofSeconds(5),
            readTimeout = Duration.ofSeconds(15),
        )
    }

    private fun create(handler: RecordingHandler, port: Int): PerpetualWebSocket = factory.create(
        name = "perp-lifecycle",
        initializers = emptyList(),
        handler = handler,
        propertiesFactory = props(port),
        shiftDuration = Duration.ofHours(1),
        switchDuration = Duration.ofHours(1),
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(target: Any, name: String): T {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = runCatching { c!!.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                return f.get(target) as T
            }
            c = c.superclass
        }
        throw NoSuchFieldException("$name on ${target.javaClass}")
    }

    private fun baseSockets(): Map<*, *> = readField(baseFactory, "sockets")
    private fun perpSockets(): Map<*, *> = readField(factory, "sockets")

    // ------------------------------------------------------------------ H3

    @Test
    fun `shutdown cancels the failsafe reconnect loop and leaves no socket registered`() {
        // A probe that accepts TCP connections and closes them immediately: every websocket attempt
        // fails initialization, so the perpetual layer keeps retrying every 2s. Each attempt = 1 accept.
        val accepts = AtomicInteger()
        val probe = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
        Thread {
            try {
                while (true) {
                    val s = probe.accept()
                    accepts.incrementAndGet()
                    s.close()
                }
            } catch (_: Exception) { /* probe closed */ }
        }.apply { isDaemon = true; start() }

        try {
            create(RecordingHandler(), probe.localPort)

            // Let the failsafe loop demonstrably run: initial attempt + at least one 2s retry.
            val warmup = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (accepts.get() < 2 && System.nanoTime() < warmup) Thread.sleep(50)
            assertTrue(accepts.get() >= 2, "expected the failsafe loop to retry against a refusing server")

            factory.shutdown()

            Thread.sleep(500) // let any in-flight attempt settle
            val afterShutdown = accepts.get()
            Thread.sleep(5_000) // more than two retry periods
            assertEquals(
                afterShutdown, accepts.get(),
                "connection attempts continued after shutdown: the pending failsafe retry was not cancelled"
            )

            assertTrue(perpSockets().isEmpty(), "perpetual sockets must be unregistered after shutdown")

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (baseSockets().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(
                baseSockets().isEmpty(),
                "orphan websockets remain registered in BaseWebSocketFactory after shutdown: ${baseSockets()}"
            )
        } finally {
            runCatching { probe.close() }
        }
    }

    // ------------------------------------------------------------------ H-gc

    @Test
    fun `after shutdown a perpetual socket and its underlying connection are released for garbage collection`() {
        val server = EchoServer(0).apply { start() }
        assertTrue(server.started.await(10, TimeUnit.SECONDS))
        try {
            val handler = RecordingHandler()
            var ws: PerpetualWebSocket? = create(handler, server.port)
            assertTrue(handler.available.await(15, TimeUnit.SECONDS), "perpetual never became available")

            val perpRef = WeakReference(ws!!)
            var base: Any? = baseSockets().values.first()
            val baseRef = WeakReference(base!!)
            base = null

            factory.shutdown()
            ws = null

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while ((perpRef.get() != null || baseRef.get() != null) && System.nanoTime() < deadline) {
                System.gc()
                Thread.sleep(50)
            }
            assertTrue(perpRef.get() == null, "shut-down perpetual socket is still strongly reachable (leak)")
            assertTrue(baseRef.get() == null, "underlying websocket is still strongly reachable after perpetual shutdown (leak)")
        } finally {
            server.stop(1000)
        }
    }

    // ------------------------------------------------------------------ H-queue

    @Test
    fun `saturated offline sends fail their Deferred and buffered sends are failed and released on shutdown`() {
        val port = freePort() // nothing ever listens: the perpetual stays offline, sendQueue stays paused
        val ws = create(RecordingHandler(), port)

        // TaskQueue buffers 8196 tasks (+1 the loop may already have pulled); beyond that it rejects.
        val total = 8_300
        val jobs = ArrayList<Deferred<Unit>>(total)
        var tracked: String? = StringBuilder("tracked-payload-").append("x".repeat(256)).toString()
        val trackedRef = WeakReference(tracked!!)
        for (i in 0 until total) {
            jobs += ws.sendMessageAsync(if (i == 100) tracked!! else "q-$i")
        }
        tracked = null

        // (1) A send rejected by the saturated queue must fail its Deferred promptly, not hang silently.
        val lastCause = runCatching { runBlocking { withTimeout(10_000) { jobs.last().await() } } }.exceptionOrNull()
        assertTrue(
            lastCause is RejectedExecutionException,
            "the Deferred of a send rejected by the saturated queue must fail with RejectedExecutionException " +
                "(got: $lastCause — a timeout means it hung silently forever)"
        )
        val minRejected = total - (8_196 + 1)
        val failed = jobs.count { it.isCancelled }
        assertTrue(failed >= minRejected, "expected at least $minRejected rejected sends to have failed, got $failed")

        // (2) On shutdown, every send still buffered while disconnected must complete (fail), not hang.
        factory.shutdown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (jobs.any { !it.isCompleted } && System.nanoTime() < deadline) Thread.sleep(50)
        val stillPending = jobs.count { !it.isCompleted }
        assertEquals(0, stillPending, "$stillPending sends buffered while disconnected still hang after shutdown")

        // (3) The buffered payloads must be released for GC once the queue is drained.
        val gcDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (trackedRef.get() != null && System.nanoTime() < gcDeadline) {
            System.gc()
            Thread.sleep(50)
        }
        assertTrue(trackedRef.get() == null, "a message buffered while disconnected is still retained after shutdown (leak)")
    }
}
