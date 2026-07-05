package fr.rowlaxx.springksocket.service.perp

import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.PerpetualWebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import fr.rowlaxx.springksocket.service.io.BaseWebSocketFactory
import fr.rowlaxx.springksocket.service.io.ClientWebSocketFactory
import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
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
import java.util.concurrent.TimeUnit

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

    private class RecordingHandler : PerpetualWebSocketHandler {
        override val serializer = WebSocketSerializer.Passthrough
        override val deserializer = WebSocketDeserializer.Passthrough
        val messages = ConcurrentHashMap.newKeySet<String>()
        val available = CountDownLatch(1)
        override fun onAvailable(webSocket: PerpetualWebSocket) { available.countDown() }
        override fun onMessage(webSocket: PerpetualWebSocket, msg: Any) { messages += msg as String }
    }

    private lateinit var threads: GlobalThreadConfiguration
    private lateinit var client: AsyncHttpClient
    private lateinit var factory: PerpetualWebSocketFactory

    @BeforeEach
    fun setUp() {
        threads = GlobalThreadConfiguration()
        client = Dsl.asyncHttpClient(
            DefaultAsyncHttpClientConfig.Builder()
                .setEventLoopGroup(threads.ioEventLoopGroup)
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
