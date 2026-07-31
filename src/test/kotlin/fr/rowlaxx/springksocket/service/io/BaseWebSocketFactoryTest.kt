package fr.rowlaxx.springksocket.service.io

import fr.rowlaxx.springksocket.exception.WebSocketConnectionException
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.ref.WeakReference
import java.net.URI
import java.net.http.HttpHeaders
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Unit tests for [BaseWebSocketFactory.BaseWebSocket] driven through a fake transport. These pin down
 * the three behaviours the refactor introduced — a fixed-rate ping bounded by the socket lifecycle, a
 * *failed* [Job] on a failed send, and serialized (thread-safe) outbound delivery under concurrency —
 * plus the read-timeout watchdog and leak-freedom on close.
 */
@Timeout(60)
class BaseWebSocketFactoryTest {

    private lateinit var threads: GlobalThreadConfiguration

    @BeforeEach
    fun setUp() { threads = GlobalThreadConfiguration() }

    @AfterEach
    fun tearDown() { threads.destroy() }

    private class PassthroughHandler : WebSocketHandler {
        override val serializer = WebSocketSerializer.Passthrough
        override val deserializer = WebSocketDeserializer.Passthrough
        val messages = ConcurrentLinkedQueue<Any>()
        val available = CountDownLatch(1)
        val unavailable = CountDownLatch(1)
        override fun onAvailable(webSocket: WebSocket) { available.countDown() }
        override fun onUnavailable(webSocket: WebSocket) { unavailable.countDown() }
        override fun onMessage(webSocket: WebSocket, msg: Any) { messages += msg }
    }

    /** Fake transport: records outbound frames, counts pings, and detects any overlapping send. */
    private class FakeSocket(
        factory: BaseWebSocketFactory,
        val handler: PassthroughHandler = PassthroughHandler(),
        pingInterval: Duration = Duration.ofMillis(80),
        readTimeout: Duration = Duration.ofSeconds(30),
        initTimeout: Duration = Duration.ofSeconds(30),
    ) : BaseWebSocketFactory.BaseWebSocket(
        factory, "fake", URI.create("ws://test"),
        HttpHeaders.of(emptyMap()) { _, _ -> true },
        initTimeout, listOf(handler), pingInterval, readTimeout
    ) {
        val sentText = ConcurrentLinkedQueue<String>()
        val pings = AtomicInteger()
        val closes = AtomicInteger()
        val failSends = AtomicBoolean(false)

        private val inSend = AtomicInteger()
        val maxConcurrentSend = AtomicInteger()

        fun open() = openWith(Any())
        fun feed(msg: Any) = acceptMessage(msg)

        override fun pingNow(): Deferred<Unit> {
            pings.incrementAndGet()
            return CompletableDeferred(Unit)
        }

        override fun sendText(msg: String): Deferred<Unit> {
            val n = inSend.incrementAndGet()
            maxConcurrentSend.updateAndGet { max(it, n) }
            try {
                if (failSends.get()) {
                    return CompletableDeferred<Unit>().also { it.completeExceptionally(WebSocketConnectionException("simulated send failure")) }
                }
                sentText += msg
                return CompletableDeferred(Unit)
            } finally {
                inSend.decrementAndGet()
            }
        }

        override fun sendBinary(msg: ByteArray): Deferred<Unit> = CompletableDeferred(Unit)
        override fun handleClose() { closes.incrementAndGet() }
        override fun handleOpen(obj: Any) {}
    }

    @Test
    fun `opens, pings at a fixed rate, and stops pinging on close`() {
        val socket = FakeSocket(BaseWebSocketFactory(threads), pingInterval = Duration.ofMillis(80))
        socket.open()
        assertTrue(socket.handler.available.await(5, TimeUnit.SECONDS), "never became available")

        Thread.sleep(350)
        val pingsWhileOpen = socket.pings.get()
        assertTrue(pingsWhileOpen >= 3, "expected repeated pings (~80ms cadence), got $pingsWhileOpen")

        socket.closeAsync("done")
        assertTrue(socket.handler.unavailable.await(5, TimeUnit.SECONDS), "never became unavailable")

        val afterClose = socket.pings.get()
        Thread.sleep(300)
        assertEquals(afterClose, socket.pings.get(), "ping schedule must be cancelled on close")
        assertTrue(socket.hasClosed())
    }

    @Test
    fun `pingInterval of zero disables pinging`() {
        val socket = FakeSocket(BaseWebSocketFactory(threads), pingInterval = Duration.ZERO)
        socket.open()
        assertTrue(socket.handler.available.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(0, socket.pings.get(), "pingInterval=0 must not schedule any ping")
        socket.closeAsync("done")
    }

    @Test
    fun `a successful send returns a completed Job and forwards the frame`() = runBlocking {
        val socket = FakeSocket(BaseWebSocketFactory(threads), pingInterval = Duration.ZERO)
        socket.open()
        assertTrue(socket.handler.available.await(5, TimeUnit.SECONDS))

        val job = socket.sendMessageAsync("hello")
        job.join()
        assertTrue(job.isCompleted && !job.isCancelled, "successful send must complete the Job")
        assertEquals(listOf("hello"), socket.sentText.toList())
        socket.closeAsync("done")
    }

    @Test
    fun `a failed send returns a failed Job and closes the socket`() = runBlocking {
        val socket = FakeSocket(BaseWebSocketFactory(threads), pingInterval = Duration.ZERO)
        socket.failSends.set(true)
        socket.open()
        assertTrue(socket.handler.available.await(5, TimeUnit.SECONDS))

        val job = socket.sendMessageAsync("hello")
        job.join()
        assertTrue(job.isCancelled, "a failed send must return a failed (cancelled) Job")
        assertTrue(socket.sentText.isEmpty(), "nothing should have been recorded as sent")
        assertTrue(socket.handler.unavailable.await(5, TimeUnit.SECONDS), "a send failure must close the socket")
    }

    @Test
    fun `sending on a closed socket returns a failed Job`() = runBlocking {
        val socket = FakeSocket(BaseWebSocketFactory(threads), pingInterval = Duration.ZERO)
        socket.open()
        assertTrue(socket.handler.available.await(5, TimeUnit.SECONDS))
        socket.closeAsync("bye")
        assertTrue(socket.handler.unavailable.await(5, TimeUnit.SECONDS))

        val job = socket.sendMessageAsync("late")
        job.join()
        assertTrue(job.isCancelled, "sending after close must fail the Job")
    }

    @Test
    fun `concurrent sends are serialized and every message is delivered exactly once`() {
        val socket = FakeSocket(BaseWebSocketFactory(threads), pingInterval = Duration.ZERO)
        socket.open()
        assertTrue(socket.handler.available.await(5, TimeUnit.SECONDS))

        val threadsN = 16
        val perThread = 500
        val barrier = CyclicBarrier(threadsN)
        val workers = (0 until threadsN).map { t ->
            Thread {
                barrier.await()
                repeat(perThread) { i -> socket.sendMessageAsync("$t-$i") }
            }.apply { start() }
        }
        workers.forEach { it.join() }

        val expected = threadsN * perThread
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (socket.sentText.size < expected && System.nanoTime() < deadline) Thread.sleep(10)

        val sent = socket.sentText.toList()
        assertEquals(expected, sent.size, "every concurrently-submitted message must be delivered")
        assertEquals(expected, sent.toSet().size, "no duplicates or corruption")
        assertEquals(1, socket.maxConcurrentSend.get(), "the transport must never see two overlapping sends")
        socket.closeAsync("done")
    }

    @Test
    fun `read timeout closes an idle socket`() {
        val socket = FakeSocket(
            BaseWebSocketFactory(threads),
            pingInterval = Duration.ZERO,
            readTimeout = Duration.ofMillis(200),
        )
        socket.open()
        assertTrue(socket.handler.available.await(5, TimeUnit.SECONDS))
        assertTrue(socket.handler.unavailable.await(5, TimeUnit.SECONDS), "idle socket must hit the read timeout")
        assertTrue(socket.hasClosed())
        assertTrue(socket.getClosedReason() is WebSocketConnectionException)
    }

    @Test
    fun `inbound traffic keeps the read-timeout watchdog from firing`() {
        val socket = FakeSocket(
            BaseWebSocketFactory(threads),
            pingInterval = Duration.ZERO,
            readTimeout = Duration.ofMillis(300),
        )
        socket.open()
        assertTrue(socket.handler.available.await(5, TimeUnit.SECONDS))

        // Keep feeding data for ~900ms (> 3x the timeout); the watchdog must keep getting reset.
        repeat(9) { socket.onDataReceived(); Thread.sleep(100) }
        assertFalse(socket.hasClosed(), "steady inbound traffic must not trip the read timeout")

        // Go quiet: now it should close within the timeout window.
        assertTrue(socket.handler.unavailable.await(5, TimeUnit.SECONDS), "quiet socket must eventually time out")
    }

    @Test
    fun `send throughput stays high on the serialized queue`() {
        val socket = FakeSocket(BaseWebSocketFactory(threads), pingInterval = Duration.ZERO)
        socket.open()
        assertTrue(socket.handler.available.await(5, TimeUnit.SECONDS))

        val total = 200_000
        val start = System.nanoTime()
        repeat(total) {
            // The send queue REJECTS beyond its bounded buffer (by design). Rejection is synchronous,
            // so back off and retry: this measures sustained drain throughput, not buffer capacity.
            while (true) {
                val job = socket.sendMessageAsync("m$it")
                if (!job.isCancelled) break
                Thread.sleep(1)
            }
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (socket.sentText.size < total && System.nanoTime() < deadline) Thread.sleep(5)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(total, socket.sentText.size, "all messages must drain through the queue")
        val perSec = total * 1000L / max(1, elapsedMs)
        println("[base-throughput] $total msgs in ${elapsedMs}ms = ~$perSec msg/s")
        assertTrue(perSec > 50_000, "serialized send throughput regressed: ~$perSec msg/s")
        socket.closeAsync("done")
    }

    @Test
    fun `a closed socket is released for garbage collection`() {
        val factory = BaseWebSocketFactory(threads)
        var socket: FakeSocket? = FakeSocket(factory, pingInterval = Duration.ZERO)
        val ref = WeakReference(socket!!)
        socket!!.open()
        assertTrue(socket!!.handler.available.await(5, TimeUnit.SECONDS))
        socket!!.closeAsync("done")
        assertTrue(socket!!.handler.unavailable.await(5, TimeUnit.SECONDS))

        socket = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (ref.get() != null && System.nanoTime() < deadline) {
            System.gc(); Thread.sleep(50)
        }
        assertTrue(ref.get() == null, "closed socket must not be retained by the factory (memory leak)")
    }
}
