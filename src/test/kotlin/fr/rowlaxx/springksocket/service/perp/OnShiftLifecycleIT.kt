package fr.rowlaxx.springksocket.service.perp

import fr.rowlaxx.springksocket.annotation.OnAvailable
import fr.rowlaxx.springksocket.annotation.OnShift
import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import fr.rowlaxx.springksocket.service.aop.PerpetualWebSocketHandlerFactory
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS

/**
 * End-to-end verification that [PerpetualWebSocketFactory] invokes `@OnShift` in the three shift
 * scenarios, driving a real connection lifecycle against a loopback WebSocket server:
 *
 * - previous == null, next != null  -> first connection
 * - previous != null, next != null  -> classic shift (overlap)
 * - previous != null, next == null  -> disconnection with no replacement
 */
@Timeout(60)
class OnShiftLifecycleIT {

    private data class Shift(val previous: WebSocket?, val next: WebSocket?)

    /** Annotated handler: the `@OnAvailable` satisfies `extract`; `@OnShift` records every shift. */
    private class ShiftBean {
        val shifts = LinkedBlockingQueue<Shift>()

        @OnAvailable
        fun onAvailable() {}

        @OnShift
        fun onShift(previous: WebSocket?, next: WebSocket?) {
            shifts.add(Shift(previous, next))
        }
    }

    private class TestServer : WebSocketServer(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) {
        val started = CountDownLatch(1)
        override fun onStart() { started.countDown() }
        override fun onOpen(conn: JWebSocket, handshake: ClientHandshake) {}
        override fun onClose(conn: JWebSocket, code: Int, reason: String?, remote: Boolean) {}
        override fun onError(conn: JWebSocket?, ex: Exception) {}
        override fun onMessage(conn: JWebSocket, message: String) {}
        override fun onMessage(conn: JWebSocket, message: ByteBuffer) {}
    }

    private lateinit var threads: GlobalThreadConfiguration
    private lateinit var client: AsyncHttpClient
    private lateinit var baseFactory: BaseWebSocketFactory
    private lateinit var factory: PerpetualWebSocketFactory

    @BeforeEach
    fun setUp() {
        threads = GlobalThreadConfiguration()
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

    private fun startServer(): TestServer {
        val server = TestServer().apply { start() }
        assertTrue(server.started.await(10, SECONDS), "test server never started")
        return server
    }

    private fun props(port: Int): () -> WebSocketClientProperties = {
        WebSocketClientProperties(
            uri = URI.create("ws://${InetAddress.getLoopbackAddress().hostAddress}:$port"),
            headers = HttpHeaders.of(emptyMap()) { _, _ -> true },
            initTimeout = Duration.ofSeconds(10),
            pingInterval = Duration.ofSeconds(5),
            readTimeout = Duration.ofSeconds(15),
        )
    }

    private fun create(bean: ShiftBean, port: Int, shift: Duration, switch: Duration): PerpetualWebSocket {
        val handler = PerpetualWebSocketHandlerFactory()
            .extract(bean, WebSocketSerializer.Passthrough, WebSocketDeserializer.Passthrough)
        return factory.create(
            name = "onshift-it",
            initializers = emptyList(),
            handler = handler,
            propertiesFactory = props(port),
            shiftDuration = shift,
            switchDuration = switch,
        )
    }

    private fun ShiftBean.nextShift(): Shift =
        requireNotNull(shifts.poll(15, SECONDS)) { "onShift was not called within the timeout" }

    @Test
    fun `first connection fires onShift with a null previous`() {
        val server = startServer()
        try {
            val bean = ShiftBean()
            // No auto-shift: only the initial connection should occur.
            create(bean, server.port, shift = Duration.ofHours(1), switch = Duration.ofHours(1))

            val first = bean.nextShift()
            assertNull(first.previous, "previous must be null on the first connection")
            assertNotNull(first.next, "next must be the newly opened connection")
        } finally {
            server.stop(1000)
        }
    }

    @Test
    fun `a shift fires onShift with previous and next both set`() {
        val server = startServer()
        try {
            val bean = ShiftBean()
            // Short shift, long switch: a second connection opens while the first is still alive (overlap).
            create(bean, server.port, shift = Duration.ofMillis(500), switch = Duration.ofSeconds(30))

            val first = bean.nextShift()
            assertNull(first.previous)
            val firstConn = requireNotNull(first.next) { "first connection missing" }

            val shift = bean.nextShift()
            assertSame(firstConn, shift.previous, "the shift's previous must be the outgoing connection")
            assertNotNull(shift.next, "the shift's next must be the incoming connection")
            assertNotSame(shift.previous, shift.next, "a shift must move to a different connection")
        } finally {
            server.stop(1000)
        }
    }

    @Test
    fun `a disconnection fires onShift with a null next`() {
        val server = startServer()
        val bean = ShiftBean()
        // No auto-shift, and we take the server down so the closed connection has no replacement.
        create(bean, server.port, shift = Duration.ofHours(1), switch = Duration.ofHours(1))

        val first = bean.nextShift()
        assertNull(first.previous)
        val conn = requireNotNull(first.next) { "first connection missing" }

        // Stopping the server closes the only connection; with nothing to reconnect to, the active
        // connection goes away with no replacement.
        server.stop(1000)

        val disconnect = bean.nextShift()
        assertSame(conn, disconnect.previous, "previous must be the connection that just closed")
        assertNull(disconnect.next, "next must be null when disconnecting with no replacement")
    }
}
