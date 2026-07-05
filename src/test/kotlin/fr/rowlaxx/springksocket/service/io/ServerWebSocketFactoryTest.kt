package fr.rowlaxx.springksocket.service.io

import fr.rowlaxx.springksocket.data.WebSocketServerProperties
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import fr.rowlaxx.springksocket.util.WebSocketSessionUtils.handleTextMessage
import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketExtension
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpHeaders
import java.security.Principal
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Unit tests for [ServerWebSocketFactory] over a fake [WebSocketSession]. They verify the server-side
 * lifecycle (open → available), that application sends and keepalive pings are funnelled to the session
 * as the right Spring message types, and — critically for a servlet container where a session is not
 * safe for concurrent writes — that concurrent sends are strictly serialized.
 */
@Timeout(60)
class ServerWebSocketFactoryTest {

    private lateinit var threads: GlobalThreadConfiguration
    private lateinit var baseFactory: BaseWebSocketFactory
    private lateinit var factory: ServerWebSocketFactory

    @BeforeEach
    fun setUp() {
        threads = GlobalThreadConfiguration()
        baseFactory = BaseWebSocketFactory(threads)
        factory = ServerWebSocketFactory(baseFactory)
    }

    @AfterEach
    fun tearDown() { threads.destroy() }

    private class RecordingHandler : WebSocketHandler {
        override val serializer = WebSocketSerializer.Passthrough
        override val deserializer = WebSocketDeserializer.Passthrough
        val messages = ConcurrentLinkedQueue<Any>()
        val available = CountDownLatch(1)
        val unavailable = CountDownLatch(1)
        override fun onAvailable(webSocket: WebSocket) { available.countDown() }
        override fun onUnavailable(webSocket: WebSocket) { unavailable.countDown() }
        override fun onMessage(webSocket: WebSocket, msg: Any) { messages += msg }
    }

    /** Minimal in-memory [WebSocketSession] that records outbound messages and flags concurrent writes. */
    private class FakeSession : WebSocketSession {
        private val attrs = HashMap<String, Any>()
        val sent = ConcurrentLinkedQueue<WebSocketMessage<*>>()
        private val inWrite = AtomicInteger()
        val maxConcurrentWrite = AtomicInteger()
        @Volatile private var open = true

        init {
            attrs["uri"] = URI.create("ws://localhost/test")
            attrs["requestHeaders"] = HttpHeaders.of(emptyMap()) { _, _ -> true }
        }

        override fun sendMessage(message: WebSocketMessage<*>) {
            val n = inWrite.incrementAndGet()
            maxConcurrentWrite.updateAndGet { max(it, n) }
            try {
                Thread.sleep(1) // widen the window so any real overlap is detected
                sent += message
            } finally {
                inWrite.decrementAndGet()
            }
        }

        override fun getId(): String = "fake"
        override fun getUri(): URI = attrs["uri"] as URI
        override fun getHandshakeHeaders(): org.springframework.http.HttpHeaders = org.springframework.http.HttpHeaders.EMPTY
        override fun getAttributes(): MutableMap<String, Any> = attrs
        override fun getPrincipal(): Principal? = null
        override fun getLocalAddress(): InetSocketAddress? = null
        override fun getRemoteAddress(): InetSocketAddress? = null
        override fun getAcceptedProtocol(): String? = null
        override fun setTextMessageSizeLimit(messageSizeLimit: Int) {}
        override fun getTextMessageSizeLimit(): Int = 0
        override fun setBinaryMessageSizeLimit(messageSizeLimit: Int) {}
        override fun getBinaryMessageSizeLimit(): Int = 0
        override fun getExtensions(): MutableList<WebSocketExtension> = mutableListOf()
        override fun isOpen(): Boolean = open
        override fun close() { open = false }
        override fun close(status: CloseStatus) { open = false }
    }

    private fun props(handler: WebSocketHandler) = WebSocketServerProperties(
        name = "srv",
        handlerChain = listOf(handler),
        initTimeout = Duration.ofSeconds(10),
        pingInterval = Duration.ZERO,
        readTimeout = Duration.ofSeconds(30),
    )

    @Test
    fun `wrapping a session opens the socket and makes it available`() {
        val handler = RecordingHandler()
        val session = FakeSession()
        val ws = factory.wrap(session, props(handler))

        assertTrue(handler.available.await(5, TimeUnit.SECONDS), "server socket never became available")
        assertTrue(ws.isConnected())
    }

    @Test
    fun `application sends become TextMessage and pings become PingMessage`() {
        val handler = RecordingHandler()
        val session = FakeSession()
        val ws = factory.wrap(session, props(handler).copy(pingInterval = Duration.ofMillis(80)))
        assertTrue(handler.available.await(5, TimeUnit.SECONDS))

        ws.sendMessageAsync("hello")
        ws.sendMessageAsync("world".toByteArray())

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (session.sent.count { it is TextMessage || it is BinaryMessage } < 2 && System.nanoTime() < deadline) Thread.sleep(10)
        Thread.sleep(300) // let a few pings fire

        val texts = session.sent.filterIsInstance<TextMessage>().map { it.payload }
        val binaries = session.sent.filterIsInstance<BinaryMessage>()
        val pings = session.sent.filterIsInstance<PingMessage>()

        assertTrue("hello" in texts, "text send must become a TextMessage")
        assertEquals(1, binaries.size, "binary send must become a BinaryMessage")
        assertTrue(pings.isNotEmpty(), "the fixed-rate ping must send PingMessage frames")
    }

    @Test
    fun `inbound text frames reach the handler`() {
        val handler = RecordingHandler()
        val session = FakeSession()
        factory.wrap(session, props(handler))
        assertTrue(handler.available.await(5, TimeUnit.SECONDS))

        session.handleTextMessage("ping-from-client")

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (handler.messages.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("ping-from-client", handler.messages.firstOrNull())
    }

    @Test
    fun `concurrent sends are strictly serialized onto the session`() {
        val handler = RecordingHandler()
        val session = FakeSession()
        val ws = factory.wrap(session, props(handler))
        assertTrue(handler.available.await(5, TimeUnit.SECONDS))

        val threadsN = 12
        val perThread = 100
        val barrier = CyclicBarrier(threadsN)
        (0 until threadsN).map { t ->
            Thread {
                barrier.await()
                repeat(perThread) { i -> ws.sendMessageAsync("$t-$i") }
            }.apply { start() }
        }.forEach { it.join() }

        val expected = threadsN * perThread
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (session.sent.size < expected && System.nanoTime() < deadline) Thread.sleep(10)

        assertEquals(expected, session.sent.size, "every message must be written to the session")
        assertEquals(1, session.maxConcurrentWrite.get(), "the servlet session must never be written concurrently")
    }
}
