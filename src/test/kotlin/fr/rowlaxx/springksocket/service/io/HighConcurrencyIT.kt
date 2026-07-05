package fr.rowlaxx.springksocket.service.io

import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocketSerializer
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
import java.net.URI
import java.net.http.HttpHeaders
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * High-concurrency correctness for the client transport ([ClientWebSocketFactory] over the real
 * [BaseWebSocketFactory]). These stress the two directions the library must never corrupt or lose:
 * many threads sending on one/many sockets (out), a server flooding frames (in), and both at once.
 */
@Timeout(180)
class HighConcurrencyIT {

    /** Echoes every text as `echo:<msg>`, and (optionally) pushes `<prefix>0..pushCount-1` on open. */
    private class ConcServer(
        port: Int,
        private val pushCount: Int = 0,
        private val pushPrefix: String = "S:",
    ) : WebSocketServer(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) {
        val started = CountDownLatch(1)
        val received = ConcurrentHashMap.newKeySet<String>()
        override fun onStart() { started.countDown() }
        override fun onOpen(conn: JWebSocket, handshake: ClientHandshake) {
            if (pushCount > 0) {
                Thread({ for (i in 0 until pushCount) runCatching { conn.send("$pushPrefix$i") } }, "push")
                    .apply { isDaemon = true }.start()
            }
        }
        override fun onClose(conn: JWebSocket, code: Int, reason: String?, remote: Boolean) {}
        override fun onError(conn: JWebSocket?, ex: Exception) {}
        override fun onMessage(conn: JWebSocket, message: String) { received += message; conn.send("echo:$message") }
        override fun onMessage(conn: JWebSocket, message: ByteBuffer) {}
    }

    private class RecordingHandler : WebSocketHandler {
        override val serializer = WebSocketSerializer.Passthrough
        override val deserializer = WebSocketDeserializer.Passthrough
        val messages = ConcurrentLinkedQueue<String>()
        val available = CountDownLatch(1)
        override fun onAvailable(webSocket: WebSocket) { available.countDown() }
        override fun onMessage(webSocket: WebSocket, msg: Any) { messages += msg as String }
    }

    private lateinit var threads: GlobalThreadConfiguration
    private lateinit var eventLoopGroup: EventLoopGroup
    private lateinit var client: AsyncHttpClient
    private lateinit var factory: ClientWebSocketFactory

    @BeforeEach
    fun setUp() {
        threads = GlobalThreadConfiguration()
        eventLoopGroup = NioEventLoopGroup(1, ThreadFactory { r -> Thread(r, "IO-conc").apply { isDaemon = true } })
        client = Dsl.asyncHttpClient(
            DefaultAsyncHttpClientConfig.Builder()
                .setEventLoopGroup(eventLoopGroup)
                .setRequestTimeout(Duration.ofMillis(-1))
                .setReadTimeout(Duration.ofMillis(-1))
                .setWebSocketMaxFrameSize(16 * 1024 * 1024)
                .build()
        )
        factory = ClientWebSocketFactory(BaseWebSocketFactory(threads), threads, client)
    }

    @AfterEach
    fun tearDown() {
        runCatching { client.close() }
        runCatching { eventLoopGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS) }
        threads.destroy()
    }

    private fun props(port: Int) = WebSocketClientProperties(
        uri = URI.create("ws://${InetAddress.getLoopbackAddress().hostAddress}:$port"),
        headers = HttpHeaders.of(emptyMap()) { _, _ -> true },
        initTimeout = Duration.ofSeconds(15),
        pingInterval = Duration.ofSeconds(5),
        readTimeout = Duration.ofSeconds(30),
    )

    private fun await(timeoutSec: Long, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
        while (!cond() && System.nanoTime() < deadline) Thread.sleep(20)
    }

    @Test
    fun `fan-out - many connections each sending concurrently receive only their own echoes`() {
        val server = ConcServer(0).apply { start() }
        assertTrue(server.started.await(10, TimeUnit.SECONDS))
        try {
            val connections = 30
            val threadsPerConn = 4
            val perThread = 100
            val perConn = threadsPerConn * perThread

            val handlers = (0 until connections).map { RecordingHandler() }
            val sockets = handlers.mapIndexed { c, h -> factory.connect("c$c", props(server.port), listOf(h)) { } }
            handlers.forEach { assertTrue(it.available.await(20, TimeUnit.SECONDS), "a connection never opened") }

            // Every connection fires from several threads at once.
            val barrier = CyclicBarrier(connections * threadsPerConn)
            val workers = sockets.flatMapIndexed { c, ws ->
                (0 until threadsPerConn).map { t ->
                    Thread {
                        barrier.await()
                        repeat(perThread) { i -> ws.sendMessageAsync("m-$c-$t-$i") }
                    }.apply { start() }
                }
            }
            workers.forEach { it.join() }

            await(60) { handlers.all { it.messages.size >= perConn } }

            handlers.forEachIndexed { c, h ->
                val expected = (0 until threadsPerConn).flatMap { t -> (0 until perThread).map { "echo:m-$c-$t-$it" } }.toSet()
                val got = h.messages.toSet()
                assertEquals(perConn, h.messages.size, "connection $c must receive exactly its own echoes, no more no less")
                assertEquals(expected, got, "connection $c saw cross-talk or loss")
            }
        } finally {
            server.stop(1000)
        }
    }

    @Test
    fun `bidirectional - concurrent sends while the server floods stay consistent both ways`() {
        val serverPush = 2_000
        val server = ConcServer(0, pushCount = serverPush, pushPrefix = "S:").apply { start() }
        assertTrue(server.started.await(10, TimeUnit.SECONDS))
        try {
            val handler = RecordingHandler()
            val ws = factory.connect("bi", props(server.port), listOf(handler)) { error("init: ${it.message}") }
            assertTrue(handler.available.await(15, TimeUnit.SECONDS))

            val threadsN = 12
            val perThread = 250
            val clientSends = threadsN * perThread

            val barrier = CyclicBarrier(threadsN)
            (0 until threadsN).map { t ->
                Thread {
                    barrier.await()
                    repeat(perThread) { i -> ws.sendMessageAsync("C:$t-$i") }
                }.apply { start() }
            }.forEach { it.join() }

            val expectedIn = clientSends + serverPush // echoes of our sends + server-initiated pushes
            await(90) { handler.messages.size >= expectedIn }

            val received = handler.messages.toList()
            val pushes = received.filter { it.startsWith("S:") }.toSet()
            val echoes = received.filter { it.startsWith("echo:C:") }.toSet()

            // Outbound: the server received every message we sent.
            val expectedOut = (0 until threadsN).flatMap { t -> (0 until perThread).map { "C:$t-$it" } }.toSet()
            assertEquals(expectedOut, server.received, "server did not receive every concurrently-sent message")
            // Inbound: we got every server push and every echo, exactly once each.
            assertEquals(serverPush, pushes.size, "lost/duplicated server-initiated pushes under load")
            assertEquals(clientSends, echoes.size, "lost/duplicated echoes under load")
            assertEquals(expectedIn, received.size, "total inbound count is inconsistent")
        } finally {
            server.stop(1000)
        }
    }

    @Test
    fun `receive order is preserved under a rapid inbound burst on one connection`() {
        val burst = 5_000
        val server = ConcServer(0, pushCount = burst, pushPrefix = "").apply { start() }
        assertTrue(server.started.await(10, TimeUnit.SECONDS))
        try {
            val handler = RecordingHandler()
            factory.connect("order", props(server.port), listOf(handler)) { error("init: ${it.message}") }
            assertTrue(handler.available.await(15, TimeUnit.SECONDS))

            await(60) { handler.messages.size >= burst }

            val received = handler.messages.map { it.toInt() }
            assertEquals(burst, received.size, "every frame of the burst must be delivered")
            // The single connection funnels inbound through one FIFO queue, so order must be exact.
            assertEquals((0 until burst).toList(), received, "inbound frames were reordered")
        } finally {
            server.stop(1000)
        }
    }
}
