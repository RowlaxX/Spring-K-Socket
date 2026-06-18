package fr.rowlaxx.springksocket.service.io

import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import fr.rowlaxx.springkutils.concurrent.config.GlobalExecutorsConfiguration
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * End-to-end check of the async-http-client (Netty) [ClientWebSocketFactory]: a real loopback
 * server, the real [BaseWebSocketFactory] lifecycle, the handler callbacks, and — critically — that
 * many connections are served by a small fixed Netty pool rather than one thread per socket.
 */
@Timeout(60)
class ClientWebSocketFactoryIT {

    private class EchoServer(port: Int) : WebSocketServer(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) {
        val started = CountDownLatch(1)
        override fun onStart() = started.countDown()
        override fun onOpen(conn: JWebSocket, handshake: ClientHandshake) {}
        override fun onClose(conn: JWebSocket, code: Int, reason: String?, remote: Boolean) {}
        override fun onError(conn: JWebSocket?, ex: Exception) {}
        override fun onMessage(conn: JWebSocket, message: String) = conn.send("echo:$message")
        override fun onMessage(conn: JWebSocket, message: ByteBuffer) {
            val bytes = ByteArray(message.remaining()).also { message.get(it) }
            conn.send(bytes)
        }
    }

    private class RecordingHandler : WebSocketHandler {
        override val serializer = WebSocketSerializer.Passthrough
        override val deserializer = WebSocketDeserializer.Passthrough
        val messages = ConcurrentLinkedQueue<Any>()
        val available = CountDownLatch(1)
        val unavailable = CountDownLatch(1)
        override fun onAvailable(webSocket: WebSocket) = available.countDown()
        override fun onUnavailable(webSocket: WebSocket) = unavailable.countDown()
        override fun onMessage(webSocket: WebSocket, msg: Any) { messages += msg }
    }

    private lateinit var threads: GlobalExecutorsConfiguration
    private lateinit var server: EchoServer
    private lateinit var eventLoopGroup: EventLoopGroup
    private lateinit var client: AsyncHttpClient
    private lateinit var factory: ClientWebSocketFactory

    @BeforeEach
    fun setUp() {
        threads = GlobalExecutorsConfiguration()
        server = EchoServer(0).apply { start() }
        assertTrue(server.started.await(10, TimeUnit.SECONDS), "echo server did not start")
        // Mirror WebSocketTransportConfiguration: a single dedicated "IO" event-loop thread.
        eventLoopGroup = NioEventLoopGroup(1, ThreadFactory { r -> Thread(r, "IO-test").apply { isDaemon = true } })
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
        runCatching { eventLoopGroup.shutdownGracefully() }
        runCatching { server.stop(1000) }
        threads.destroy()
    }

    private fun props() = WebSocketClientProperties(
        uri = URI.create("ws://${InetAddress.getLoopbackAddress().hostAddress}:${server.port}"),
        headers = HttpHeaders.of(emptyMap()) { _, _ -> true },
        initTimeout = Duration.ofSeconds(10),
        pingAfter = Duration.ofSeconds(5),
        readTimeout = Duration.ofSeconds(10),
    )

    @Test
    fun `connects, echoes text and binary, then closes`() {
        val handler = RecordingHandler()
        val ws = factory.connect("it", props(), listOf(handler)) { error -> error("init failed: ${error.message}") }

        assertTrue(handler.available.await(10, TimeUnit.SECONDS), "never became available")
        assertTrue(ws.isConnected())

        ws.sendMessageAsync("hello")
        ws.sendMessageAsync("world".toByteArray())

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (handler.messages.size < 2 && System.nanoTime() < deadline) Thread.sleep(10)

        val received = handler.messages.toList()
        assertEquals(2, received.size, "expected text + binary echo, got $received")
        assertEquals("echo:hello", received[0])
        assertEquals("world", String(received[1] as ByteArray))

        ws.closeAsync("done")
        assertTrue(handler.unavailable.await(10, TimeUnit.SECONDS), "never became unavailable")
        assertTrue(ws.hasClosed())
    }

    @Test
    fun `receives a single frame larger than AHC's 10KB default limit`() {
        val handler = RecordingHandler()
        val ws = factory.connect("big", props(), listOf(handler)) { error -> error("init failed: ${error.message}") }
        assertTrue(handler.available.await(10, TimeUnit.SECONDS), "never became available")

        val big = "x".repeat(64 * 1024) // 64 KB > AHC's 10 KB default frame size
        ws.sendMessageAsync(big)

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (handler.messages.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)

        assertEquals("echo:$big", handler.messages.firstOrNull(), "64 KB frame did not round-trip in one piece")
    }

    @Test
    fun `many connections are served by the single IO event-loop thread`() {
        val connections = 60
        val handlers = (1..connections).map { RecordingHandler() }
        handlers.forEach { factory.connect("it-$it", props(), listOf(it)) { _ -> } }

        handlers.forEach { assertTrue(it.available.await(20, TimeUnit.SECONDS), "a connection never opened") }

        val ioThreads = Thread.getAllStackTraces().keys.count { it.name == "IO-test" }
        println("[$connections connections] dedicated IO event-loop threads = $ioThreads")
        assertEquals(1, ioThreads, "all $connections sockets must multiplex over the single IO thread")
    }
}
