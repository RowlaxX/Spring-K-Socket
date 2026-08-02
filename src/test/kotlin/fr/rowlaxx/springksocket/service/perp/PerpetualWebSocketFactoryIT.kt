package fr.rowlaxx.springksocket.service.perp

import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.PerpetualWebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import fr.rowlaxx.springksocket.service.io.BaseWebSocketFactory
import fr.rowlaxx.springksocket.service.io.ClientWebSocketFactory
import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpHeaders
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end tests for [PerpetualWebSocketFactory] over a real loopback echo server and the real
 * async-http-client transport. They cover the two hard guarantees of the perpetual layer: eventual
 * delivery of every message across a disconnect (the 2s resend), and consistent, thread-safe fan-in
 * when many threads send concurrently.
 */
@Timeout(120)
class PerpetualWebSocketFactoryIT {

    private class EchoServer(
        port: Int,
        private val pushCount: Int = 0,
    ) : WebSocketServer(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) {
        val started = CountDownLatch(1)
        val received = ConcurrentHashMap.newKeySet<String>()
        override fun onStart() { started.countDown() }
        override fun onOpen(conn: JWebSocket, handshake: ClientHandshake) {
            if (pushCount > 0) {
                Thread({ for (i in 0 until pushCount) runCatching { conn.send("S:$i") } }, "push")
                    .apply { isDaemon = true }.start()
            }
        }
        override fun onClose(conn: JWebSocket, code: Int, reason: String?, remote: Boolean) {}
        override fun onError(conn: JWebSocket?, ex: Exception) {}
        override fun onMessage(conn: JWebSocket, message: String) { received += message; conn.send("echo:$message") }
        override fun onMessage(conn: JWebSocket, message: ByteBuffer) {}
    }

    /** Echo-less server that lets the test broadcast an identical stream to every open connection. */
    private class BroadcastServer(port: Int) :
        WebSocketServer(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) {
        val started = CountDownLatch(1)
        val twoConnected = CountDownLatch(2)
        override fun onStart() { started.countDown() }
        override fun onOpen(conn: JWebSocket, handshake: ClientHandshake) { twoConnected.countDown() }
        override fun onClose(conn: JWebSocket, code: Int, reason: String?, remote: Boolean) {}
        override fun onError(conn: JWebSocket?, ex: Exception) {}
        override fun onMessage(conn: JWebSocket, message: String) {}
        override fun onMessage(conn: JWebSocket, message: ByteBuffer) {}

        /** Sends [msg] to every currently open connection, like an upstream fanning out its stream. */
        fun sendToAll(msg: String) {
            connections.filter { it.isOpen }.forEach { runCatching { it.send(msg) } }
        }
    }

    private class CountingHandler : PerpetualWebSocketHandler {
        override val serializer = WebSocketSerializer.Passthrough
        override val deserializer = WebSocketDeserializer.Passthrough
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val available = CountDownLatch(1)
        override fun onAvailable(webSocket: PerpetualWebSocket) { available.countDown() }
        override fun onMessage(webSocket: PerpetualWebSocket, connection: WebSocket, msg: Any) {
            counts.computeIfAbsent(msg as String) { AtomicInteger() }.incrementAndGet()
        }
    }

    private class RecordingHandler : PerpetualWebSocketHandler {
        override val serializer = WebSocketSerializer.Passthrough
        override val deserializer = WebSocketDeserializer.Passthrough
        val messages = ConcurrentHashMap.newKeySet<String>()
        val available = CountDownLatch(1)
        override fun onAvailable(webSocket: PerpetualWebSocket) { available.countDown() }
        override fun onMessage(webSocket: PerpetualWebSocket, connection: WebSocket, msg: Any) { messages += msg as String }
    }

    private lateinit var threads: GlobalThreadConfiguration
    private lateinit var eventLoopGroup: EventLoopGroup
    private lateinit var client: AsyncHttpClient
    private lateinit var factory: PerpetualWebSocketFactory

    @BeforeEach
    fun setUp() {
        threads = GlobalThreadConfiguration()
        // Mirror WebSocketTransportConfiguration: a single dedicated "IO" event-loop thread.
        // (async-http-client does not accept the MultiThreadIoEventLoopGroup that
        // GlobalThreadConfiguration.ioEventLoopGroup provides.)
        eventLoopGroup = NioEventLoopGroup(1, ThreadFactory { r -> Thread(r, "IO-perp-test").apply { isDaemon = true } })
        client = Dsl.asyncHttpClient(
            DefaultAsyncHttpClientConfig.Builder()
                .setEventLoopGroup(eventLoopGroup)
                .setRequestTimeout(Duration.ofMillis(-1))
                .setReadTimeout(Duration.ofMillis(-1))
                .setWebSocketMaxFrameSize(16 * 1024 * 1024)
                .build()
        )
        factory = PerpetualWebSocketFactory(ClientWebSocketFactory(BaseWebSocketFactory(threads), threads, client), threads)
    }

    @AfterEach
    fun tearDown() {
        runCatching { factory.shutdown() }
        runCatching { client.close() }
        runCatching { eventLoopGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS) }
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
        name = "perp",
        initializers = emptyList(),
        handler = handler,
        propertiesFactory = props(port),
        shiftDuration = Duration.ofHours(1),
        switchDuration = Duration.ofSeconds(3),
    )

    @Test
    fun `connects, echoes, and becomes available`() {
        val server = EchoServer(0).apply { start() }
        assertTrue(server.started.await(10, TimeUnit.SECONDS))
        try {
            val handler = RecordingHandler()
            val ws = create(handler, server.port)
            assertTrue(handler.available.await(15, TimeUnit.SECONDS), "perpetual never became available")
            assertTrue(ws.isConnected())

            ws.sendMessageAsync("hello")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (handler.messages.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue("echo:hello" in handler.messages, "did not receive the echo")
        } finally {
            server.stop(1000)
        }
    }

    @Test
    fun `a message sent while disconnected is delivered once the server comes up`() {
        val port = freePort()
        val handler = RecordingHandler()

        // No server yet: the perpetual is retrying its failsafe connect every 2s.
        val ws = create(handler, port)
        // Enqueue messages while there is no connection at all. They must not be lost.
        val payloads = (1..5).map { "queued-$it" }
        payloads.forEach { ws.sendMessageAsync(it) }

        Thread.sleep(1500) // ensure we spent real time fully disconnected before any server exists

        // Bring the server up on the awaited port; the queued messages must now flow.
        val server = EchoServer(port).apply { start() }
        assertTrue(server.started.await(10, TimeUnit.SECONDS))
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!server.received.containsAll(payloads) && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(
                server.received.containsAll(payloads),
                "messages enqueued while disconnected were not all delivered: got ${server.received}"
            )
        } finally {
            server.stop(1000)
        }
    }

    @Test
    fun `high-concurrency sends are all delivered consistently`() {
        val server = EchoServer(0).apply { start() }
        assertTrue(server.started.await(10, TimeUnit.SECONDS))
        try {
            val handler = RecordingHandler()
            val ws = create(handler, server.port)
            assertTrue(handler.available.await(15, TimeUnit.SECONDS))

            val threadsN = 16
            val perThread = 200
            val expected = (0 until threadsN).flatMap { t -> (0 until perThread).map { "m-$t-$it" } }.toSet()

            val barrier = CyclicBarrier(threadsN)
            (0 until threadsN).map { t ->
                Thread {
                    barrier.await()
                    repeat(perThread) { i -> ws.sendMessageAsync("m-$t-$i") }
                }.apply { start() }
            }.forEach { it.join() }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            while (!server.received.containsAll(expected) && System.nanoTime() < deadline) Thread.sleep(50)

            val missing = expected - server.received
            assertTrue(missing.isEmpty(), "under concurrency, ${missing.size} messages were lost e.g. ${missing.take(5)}")
            assertEquals(expected.size, server.received.count { it.startsWith("m-") }, "no duplicates expected on a single connection")
        } finally {
            server.stop(1000)
        }
    }

    /**
     * Exactly-once delivery during a shift: with a short shiftDuration a second physical connection
     * opens while the first is still live, and the server pushes the *same stream* to every open
     * connection — exactly what a real upstream does during the overlap window. The handler must
     * observe each unique message exactly once, and a genuine repeat in the stream (the same payload
     * occurring twice upstream) must be delivered twice, not swallowed by the deduplicator.
     */
    @Test
    fun `overlapping connections deliver the shared stream exactly once, repeats included`() {
        val server = BroadcastServer(0).apply { start() }
        assertTrue(server.started.await(10, TimeUnit.SECONDS))
        try {
            val handler = CountingHandler()
            factory.create(
                name = "perp-overlap",
                initializers = emptyList(),
                handler = handler,
                propertiesFactory = props(server.port),
                // Short shift so a second physical connection opens ~2s after the first; long
                // switch so both stay open well past the broadcast below.
                shiftDuration = Duration.ofSeconds(2),
                switchDuration = Duration.ofSeconds(20),
                dedupe = true,
            )
            assertTrue(handler.available.await(15, TimeUnit.SECONDS), "perpetual never became available")
            assertTrue(server.twoConnected.await(15, TimeUnit.SECONDS), "the shift never opened a second connection")

            // Both connections are live: push the same stream to each of them. The stream contains
            // 150 unique messages plus the payload "rep" occurring twice (a genuine upstream repeat).
            val unique = (0 until 150).map { "u-$it" }
            unique.forEachIndexed { i, msg ->
                server.sendToAll(msg)
                if (i == 50 || i == 100) server.sendToAll("rep")
            }

            // Wait until everything sent has been seen at least once, then settle so any late
            // duplicate copy (from the second connection) would have time to arrive.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            fun allSeen() = unique.all { (handler.counts[it]?.get() ?: 0) >= 1 } &&
                (handler.counts["rep"]?.get() ?: 0) >= 2
            while (!allSeen() && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(
                allSeen(),
                "stream messages were swallowed during the overlap: " +
                    "missing=${unique.count { (handler.counts[it]?.get() ?: 0) == 0 }}, " +
                    "rep=${handler.counts["rep"]?.get() ?: 0}"
            )
            Thread.sleep(1500)

            val duplicated = unique.filter { (handler.counts[it]?.get() ?: 0) > 1 }
            assertTrue(duplicated.isEmpty(), "messages delivered more than once during overlap: ${duplicated.take(5)}")
            unique.forEach { assertEquals(1, handler.counts[it]?.get(), "message $it must be delivered exactly once") }
            assertEquals(2, handler.counts["rep"]?.get(), "a genuine repeat in the stream must be delivered twice")
        } finally {
            server.stop(1000)
        }
    }

    @Test
    fun `high-concurrency bidirectional traffic is delivered consistently`() {
        val push = 1_500
        val server = EchoServer(0, pushCount = push).apply { start() }
        assertTrue(server.started.await(10, TimeUnit.SECONDS))
        try {
            val handler = RecordingHandler()
            val ws = create(handler, server.port)
            assertTrue(handler.available.await(15, TimeUnit.SECONDS))

            val threadsN = 12
            val perThread = 200
            val sent = (0 until threadsN).flatMap { t -> (0 until perThread).map { "m-$t-$it" } }.toSet()

            val barrier = CyclicBarrier(threadsN)
            (0 until threadsN).map { t ->
                Thread {
                    barrier.await()
                    repeat(perThread) { i -> ws.sendMessageAsync("m-$t-$i") }
                }.apply { start() }
            }.forEach { it.join() }

            val expectedEchoes = sent.map { "echo:$it" }.toSet()
            val expectedPushes = (0 until push).map { "S:$it" }.toSet()
            val expectedIn = expectedEchoes + expectedPushes

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (!handler.messages.containsAll(expectedIn) && System.nanoTime() < deadline) Thread.sleep(50)

            assertTrue(server.received.containsAll(sent), "server missed ${(sent - server.received).size} concurrent sends")
            assertTrue(handler.messages.containsAll(expectedPushes), "handler missed some server-initiated pushes")
            assertTrue(handler.messages.containsAll(expectedEchoes), "handler missed some echoes")
        } finally {
            server.stop(1000)
        }
    }
}
