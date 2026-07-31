package fr.rowlaxx.springksocket.stress

import fr.rowlaxx.springksocket.SpringKSocketConfiguration
import fr.rowlaxx.springksocket.annotation.OnAvailable
import fr.rowlaxx.springksocket.annotation.OnMessage
import fr.rowlaxx.springksocket.annotation.OnUnavailable
import fr.rowlaxx.springksocket.annotation.WebSocketClient
import fr.rowlaxx.springksocket.annotation.WebSocketServer
import fr.rowlaxx.springksocket.core.AutoWebSocketCollection
import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.service.io.BaseWebSocketFactory
import fr.rowlaxx.springksocket.service.perp.PerpetualWebSocketFactory
import fr.rowlaxx.springkutils.SpringKUtilsConfiguration
import io.netty.channel.nio.NioEventLoopGroup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpHeaders
import java.time.Duration
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.concurrent.thread

/*
 * High-throughput stress IT for the ANNOTATION layer: a @WebSocketServer bean and a @WebSocketClient
 * bean living in ONE Spring Boot application, talking to each other over a real embedded Tomcat.
 *
 * Everything here is @Profile("stress")-gated: SpringKSocketConfiguration component-scans the whole
 * fr.rowlaxx.springksocket package (test classes included), so without the gate these beans would leak
 * into every other @SpringBootTest context in the build.
 *
 * The port is pinned up-front (DEFINED_PORT + @DynamicPropertySource) instead of reading
 * `local.server.port`, because the perpetual client connects at @PostConstruct — before the port
 * property exists — and ClientWebSocketFactory.connectFailsafe retries forever with the properties
 * captured at that first attempt (the properties factory is only re-evaluated after a connection has
 * successfully opened and later closed).
 */

@Profile("stress")
@Configuration(proxyBeanMethods = false)
@Import(
    TomcatServletWebServerAutoConfiguration::class,
    DispatcherServletAutoConfiguration::class,
    SpringKSocketConfiguration::class,
    SpringKUtilsConfiguration::class,
)
class StressApplication {

    companion object {
        /**
         * PRE-EXISTING INCOMPATIBILITY WORKAROUND (test-scoped, main code untouched):
         * spring-k-utils' GlobalThreadConfiguration now exposes a Netty 4.2 MultiThreadIoEventLoopGroup,
         * but async-http-client (latest is 3.0.11) only accepts Nio/Epoll/KQueue/IOUring groups —
         * its shared `httpClient` bean therefore throws "Unknown event loop group
         * MultiThreadIoEventLoopGroup" and EVERY @SpringBootTest context on master fails to boot
         * (see SpringWebSocketAopApplicationTests). We drop that bean definition and supply a
         * Nio-based client below, exactly like the transport ITs (HighConcurrencyIT) do.
         */
        @Bean
        @JvmStatic
        fun dropBrokenSharedHttpClient(): BeanDefinitionRegistryPostProcessor = object : BeanDefinitionRegistryPostProcessor {
            override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
                if (registry.containsBeanDefinition("httpClient")) registry.removeBeanDefinition("httpClient")
            }
            override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {}
        }
    }

    @Bean(destroyMethod = "close")
    fun stressHttpClient(): AsyncHttpClient = Dsl.asyncHttpClient(
        DefaultAsyncHttpClientConfig.Builder()
            .setEventLoopGroup(NioEventLoopGroup(1, java.util.concurrent.ThreadFactory { r -> Thread(r, "stress-ahc-io").apply { isDaemon = true } }))
            .setRequestTimeout(Duration.ofMillis(-1))
            .setReadTimeout(Duration.ofMillis(-1))
            .setTcpNoDelay(true)
            .setWebSocketMaxFrameSize(16 * 1024 * 1024)
            .build()
    )
}

/**
 * Server side. Counts flood messages ("f:<producer>:<seq>"), tracks paced messages
 * ("p:<producer>:<seq>") idempotently in a per-producer BitSet (so at-least-once retransmits still
 * allow an exact zero-loss count), and acks sync markers ("s:<token>" -> "a:<token>") by returning
 * a value from the handler (auto-send).
 */
@Profile("stress")
@WebSocketServer(paths = ["/stress"], initTimeout = "PT30S", pingInterval = "PT2S", readTimeout = "PT60S")
class StressServerBean {

    /** `final val` of type AutoWebSocketCollection: auto-tracks every live server-side connection. */
    val clients = AutoWebSocketCollection()

    class ProducerState {
        private val seen = BitSet()
        private var highest = -1
        var orderViolations = 0L; private set
        var duplicates = 0L; private set

        @Synchronized
        fun accept(seq: Int) {
            if (seen.get(seq)) { duplicates++; return }
            seen.set(seq)
            if (seq < highest) orderViolations++ else highest = seq
        }

        @Synchronized fun distinct(): Long = seen.cardinality().toLong()
        @Synchronized fun violations(): Long = orderViolations
        @Synchronized fun dups(): Long = duplicates
    }

    val floodReceived = LongAdder()
    val floodOrderViolations = LongAdder()
    private val floodLast = ConcurrentHashMap<Int, Int>()
    val paced = ConcurrentHashMap<Int, ProducerState>()

    fun resetFlood() { floodReceived.reset(); floodOrderViolations.reset(); floodLast.clear() }
    fun resetPaced() = paced.clear()
    fun pacedDistinct(): Long = paced.values.sumOf { it.distinct() }
    fun pacedViolations(): Long = paced.values.sumOf { it.violations() }
    fun pacedDuplicates(): Long = paced.values.sumOf { it.dups() }

    @OnMessage
    fun onMessage(ws: WebSocket, msg: String): String? {
        when (msg[0]) {
            'f' -> {
                val c = msg.indexOf(':', 2)
                val p = msg.substring(2, c).toInt()
                val seq = msg.substring(c + 1).toInt()
                floodReceived.increment()
                val prev = floodLast.put(p, seq)
                if (prev != null && seq <= prev) floodOrderViolations.increment()
            }
            'p' -> {
                val c = msg.indexOf(':', 2)
                val p = msg.substring(2, c).toInt()
                val seq = msg.substring(c + 1).toInt()
                paced.computeIfAbsent(p) { ProducerState() }.accept(seq)
            }
            's' -> return "a:${msg.substring(2)}" // sync -> ack (auto-sent, FIFO after the batch)
        }
        return null
    }
}

/**
 * Client side. Becomes a perpetual websocket (auto-reconnect); the non-final [ws] field is injected.
 * Counts downstream flood ("d:<producer>:<seq>") and resolves sync acks ("a:<token>").
 */
@Profile("stress")
@WebSocketClient(url = "ws://overridden-by-properties-method", initTimeout = "PT30S", pingInterval = "PT2S", readTimeout = "PT60S")
class StressClientBean(private val environment: Environment) {

    lateinit var ws: PerpetualWebSocket

    @Volatile var connected = false
    val downReceived = LongAdder()
    val downOrderViolations = LongAdder()
    private val downLast = ConcurrentHashMap<Int, Int>()
    val acks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    fun resetDown() { downReceived.reset(); downOrderViolations.reset(); downLast.clear() }

    /** Picked up by WebSocketClientPropertiesFactory; overrides the annotation url. */
    fun stressClientProperties(): WebSocketClientProperties = WebSocketClientProperties(
        uri = URI.create("ws://127.0.0.1:${environment.getRequiredProperty("stress.port")}/stress"),
        headers = HttpHeaders.of(emptyMap()) { _, _ -> true },
        initTimeout = Duration.ofSeconds(30),
        pingInterval = Duration.ofSeconds(2),
        readTimeout = Duration.ofSeconds(60),
    )

    @OnAvailable fun onUp(perp: PerpetualWebSocket) { connected = true }
    @OnUnavailable fun onDown(perp: PerpetualWebSocket) { connected = false }

    @OnMessage
    fun onMessage(perp: PerpetualWebSocket, msg: String) {
        when (msg[0]) {
            'd' -> {
                val c = msg.indexOf(':', 2)
                val p = msg.substring(2, c).toInt()
                val seq = msg.substring(c + 1).toInt()
                downReceived.increment()
                val prev = downLast.put(p, seq)
                if (prev != null && seq <= prev) downOrderViolations.increment()
            }
            'a' -> acks.remove(msg.substring(2))?.complete(Unit)
        }
    }
}

@Timeout(150)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ActiveProfiles("stress")
@SpringBootTest(
    classes = [StressApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    // TestApplication (picked up by SpringKSocketConfiguration's package scan) overrides the shared
    // spring-k-utils httpClient bean; the registry post-processor above then drops it in favor of
    // stressHttpClient either way.
    properties = ["spring.main.allow-bean-definition-overriding=true"],
)
class AnnotatedStressIT {

    companion object {
        private val port: Int = ServerSocket(0).use { it.localPort }

        @JvmStatic
        @DynamicPropertySource
        fun dynamicProps(registry: DynamicPropertyRegistry) {
            registry.add("server.port") { port }
            registry.add("stress.port") { port }
        }

        private const val MB = 1024L * 1024L
        private fun report(line: String) = println("[STRESS] $line")
    }

    @Autowired lateinit var server: StressServerBean
    @Autowired lateinit var client: StressClientBean
    @Autowired lateinit var baseFactory: BaseWebSocketFactory
    @Autowired lateinit var perpFactory: PerpetualWebSocketFactory

    // ------------------------------------------------------------------ helpers

    private fun awaitConnected(timeoutSec: Long = 30) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
        while (System.nanoTime() < deadline) {
            if (client.connected && client.ws.isConnected() && server.clients.filter { it.isConnected() }.isNotEmpty()) return
            Thread.sleep(50)
        }
        throw AssertionError("client and server never linked up within ${timeoutSec}s")
    }

    /** Polls [counter] until it stops changing for [stableMs] (or [maxWaitSec] elapses). */
    private fun settle(stableMs: Long = 1500, maxWaitSec: Long = 30, counter: () -> Long): Long {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxWaitSec)
        var last = counter()
        var lastChange = System.nanoTime()
        while (System.nanoTime() < deadline) {
            Thread.sleep(150)
            val now = counter()
            if (now != last) { last = now; lastChange = System.nanoTime() }
            else if (System.nanoTime() - lastChange >= TimeUnit.MILLISECONDS.toNanos(stableMs)) break
        }
        return last
    }

    private fun awaitAtLeast(expected: Long, timeoutSec: Long, counter: () -> Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
        while (counter() < expected && System.nanoTime() < deadline) Thread.sleep(100)
    }

    @Suppress("UNCHECKED_CAST")
    private fun socketsOf(factory: Any): Map<Any, Any> {
        val field = factory.javaClass.getDeclaredField("sockets")
        field.isAccessible = true
        return field.get(factory) as Map<Any, Any>
    }

    private fun baseSocketMap() = socketsOf(baseFactory)

    /** Closes the client-side transport connection(s): a REAL close handshake, so both ends notice. */
    private fun killClientSideConnections(): Int {
        val victims = baseSocketMap().values.filterIsInstance<WebSocket>()
            .filter { it.name == "StressClientBean" && it.isConnected() }
        victims.forEach { it.closeAsync("churn kill") }
        return victims.size
    }

    private fun usedHeapAfterGc(): Long {
        repeat(3) { System.gc(); Thread.sleep(250) }
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    /**
     * Paced, windowed, LOSSLESS-by-design load: each producer sends batches of [batchSize] messages
     * followed by a sync marker, and only proceeds once the server acked the batch (bounded in-flight
     * window << TaskQueue's 8196 cap). A missing ack within [ackTimeoutMs] retransmits the whole
     * batch (server-side BitSet counting is idempotent), giving exactly-once accounting even across
     * reconnects. Returns retransmit count; producer errors are asserted empty.
     */
    private fun runPaced(producers: Int, batches: Int, batchSize: Int, ackTimeoutMs: Long = 15_000): Long {
        client.acks.clear()
        val retransmits = AtomicLong()
        val errors = ConcurrentLinkedQueue<String>()
        val threads = (0 until producers).map { p ->
            thread(name = "paced-$p") {
                try {
                    for (b in 0 until batches) {
                        var attempt = 0
                        while (true) {
                            val base = b * batchSize
                            for (i in 0 until batchSize) client.ws.sendMessageAsync("p:$p:${base + i}")
                            val token = "$p-$b-$attempt"
                            val waiter = CompletableDeferred<Unit>()
                            client.acks[token] = waiter
                            client.ws.sendMessageAsync("s:$token")
                            val acked = runBlocking { withTimeoutOrNull(ackTimeoutMs) { waiter.await() } } != null
                            if (acked) break
                            client.acks.remove(token)
                            retransmits.incrementAndGet()
                            if (++attempt > 20) error("batch $p/$b never acked after $attempt attempts")
                        }
                    }
                } catch (t: Throwable) {
                    errors += "producer $p: $t"
                }
            }
        }
        threads.forEach { it.join(140_000) }
        assertTrue(errors.isEmpty(), "paced producers failed: $errors")
        return retransmits.get()
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(0)
    fun `annotated server and client link up over embedded Tomcat and round-trip`() {
        awaitConnected()
        val waiter = CompletableDeferred<Unit>()
        client.acks["hello"] = waiter
        client.ws.sendMessageAsync("s:hello")
        val ok = runBlocking { withTimeoutOrNull(10_000) { waiter.await() } } != null
        assertTrue(ok, "sync round-trip through @OnMessage auto-reply never came back")
        report("connectivity: OK on port $port, base sockets=${baseSocketMap().size}")
    }

    @Test
    @Order(1)
    @Timeout(90)
    fun `flood client to server - throughput, drops and survivor ordering`() {
        awaitConnected()

        // Warmup (JIT + transport), then reset counters for the measured window.
        floodUp(producers = 2, durationMs = 1500)
        settle(stableMs = 1000, maxWaitSec = 15) { server.floodReceived.sum() }
        server.resetFlood()

        val producers = 6
        val durationMs = 6000L
        val completedOk = LongAdder()
        val completedFail = LongAdder()
        val start = System.nanoTime()
        val offered = floodUp(producers, durationMs) { deferred ->
            deferred.invokeOnCompletion { if (it == null) completedOk.increment() else completedFail.increment() }
        }
        val offerNanos = System.nanoTime() - start

        val delivered = settle(maxWaitSec = 30) { server.floodReceived.sum() }
        val drainNanos = System.nanoTime() - start - TimeUnit.MILLISECONDS.toNanos(1500)

        val offeredRate = offered * 1e9 / offerNanos
        val deliveredRate = delivered * 1e9 / drainNanos
        val hung = offered - completedOk.sum() - completedFail.sum()

        report("client->server flood: offered=$offered in ${offerNanos / 1_000_000}ms (%.0f msg/s)".format(offeredRate))
        report("client->server flood: delivered=$delivered (%.0f msg/s), dropped=${offered - delivered} (%.1f%%)"
            .format(deliveredRate, 100.0 * (offered - delivered) / offered.coerceAtLeast(1)))
        report("client->server flood: send-deferreds ok=${completedOk.sum()} failed=${completedFail.sum()} neverCompleted=$hung " +
            "(saturation drops must fail the returned Deferred, never leave it hanging)")
        report("client->server flood: survivor order violations=${server.floodOrderViolations.sum()}")
        assertEquals(0L, hung, "every rejected send must complete its Deferred exceptionally - hung Deferreds are undetectable drops")

        assertTrue(offeredRate >= 200_000.0) { "offered rate too low: $offeredRate/s" }
        assertTrue(delivered <= offered)
        assertTrue(deliveredRate >= 20_000.0) { "delivered rate below conservative floor: $deliveredRate/s" }
        assertEquals(0, server.floodOrderViolations.sum(), "surviving flood messages arrived out of per-producer order")
    }

    private fun floodUp(producers: Int, durationMs: Long, onSend: ((kotlinx.coroutines.Deferred<Unit>) -> Unit)? = null): Long {
        val offered = AtomicLong()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs)
        (0 until producers).map { p ->
            thread(name = "flood-up-$p") {
                var i = 0
                while (System.nanoTime() < deadline) {
                    val d = client.ws.sendMessageAsync("f:$p:$i")
                    onSend?.invoke(d)
                    i++
                }
                offered.addAndGet(i.toLong())
            }
        }.forEach { it.join() }
        return offered.get()
    }

    @Test
    @Order(2)
    @Timeout(90)
    fun `flood server to client - throughput, drops and survivor ordering`() {
        awaitConnected()
        client.resetDown()

        val serverSock = server.clients.filter { it.isConnected() }.single()

        val producers = 6
        val durationMs = 6000L
        val offered = AtomicLong()
        val sendFailed = LongAdder()
        val start = System.nanoTime()
        val deadline = start + TimeUnit.MILLISECONDS.toNanos(durationMs)
        (0 until producers).map { p ->
            thread(name = "flood-down-$p") {
                var i = 0
                while (System.nanoTime() < deadline) {
                    serverSock.sendMessageAsync("d:$p:$i").invokeOnCompletion { if (it != null) sendFailed.increment() }
                    i++
                }
                offered.addAndGet(i.toLong())
            }
        }.forEach { it.join() }
        val offerNanos = System.nanoTime() - start

        val delivered = settle(maxWaitSec = 30) { client.downReceived.sum() }
        val drainNanos = System.nanoTime() - start - TimeUnit.MILLISECONDS.toNanos(1500)

        val offeredRate = offered.get() * 1e9 / offerNanos
        val deliveredRate = delivered * 1e9 / drainNanos

        report("server->client flood: offered=${offered.get()} (%.0f msg/s), outbound-rejected=${sendFailed.sum()}".format(offeredRate))
        report("server->client flood: delivered=$delivered (%.0f msg/s), total lost=${offered.get() - delivered} (%.1f%%)"
            .format(deliveredRate, 100.0 * (offered.get() - delivered) / offered.get().coerceAtLeast(1)))
        report("server->client flood: survivor order violations=${client.downOrderViolations.sum()}")

        assertTrue(offeredRate >= 200_000.0) { "offered rate too low: $offeredRate/s" }
        assertTrue(delivered <= offered.get())
        assertTrue(deliveredRate >= 20_000.0) { "delivered rate below conservative floor: $deliveredRate/s" }
        assertEquals(0, client.downOrderViolations.sum(), "surviving downstream messages arrived out of per-producer order")
    }

    @Test
    @Order(3)
    @Timeout(120)
    fun `paced windowed load is strictly lossless with per-producer ordering`() {
        awaitConnected()
        server.resetPaced()

        val producers = 4
        val batches = 12
        val batchSize = 1000
        val expected = producers.toLong() * batches * batchSize

        val start = System.nanoTime()
        val retransmits = runPaced(producers, batches, batchSize)
        val elapsed = System.nanoTime() - start
        awaitAtLeast(expected, timeoutSec = 20) { server.pacedDistinct() }

        val distinct = server.pacedDistinct()
        report("paced: expected=$expected distinct=$distinct rate=%.0f msg/s retransmits=$retransmits duplicates=${server.pacedDuplicates()}"
            .format(expected * 1e9 / elapsed))
        report("paced: order violations=${server.pacedViolations()}")

        assertEquals(expected, distinct, "PACED SCENARIO LOST MESSAGES")
        assertEquals(0, server.pacedViolations(), "paced messages arrived out of per-producer order")
        assertEquals(0, server.pacedDuplicates(), "no duplicates expected without retransmits")
        assertEquals(0, retransmits, "no retransmit should be needed on a healthy connection")
    }

    @Test
    @Order(4)
    @Timeout(150)
    fun `repeated load cycles do not grow the heap or accumulate sockets`() {
        awaitConnected()

        val cycles = 4
        val perCycle = 4L * 8 * 1000
        val heaps = LongArray(cycles)
        val baseSockets = IntArray(cycles)
        val perpSockets = IntArray(cycles)

        // Warmup cycle (allocator/JIT steady state) before measuring.
        server.resetPaced()
        runPaced(producers = 4, batches = 8, batchSize = 1000)
        awaitAtLeast(perCycle, timeoutSec = 20) { server.pacedDistinct() }

        for (c in 0 until cycles) {
            server.resetPaced()
            runPaced(producers = 4, batches = 8, batchSize = 1000)
            awaitAtLeast(perCycle, timeoutSec = 20) { server.pacedDistinct() }
            assertEquals(perCycle, server.pacedDistinct(), "cycle $c lost messages")

            heaps[c] = usedHeapAfterGc()
            baseSockets[c] = baseSocketMap().size
            perpSockets[c] = socketsOf(perpFactory).size
            report("cycle $c: heap=${heaps[c] / MB}MB baseSockets=${baseSockets[c]} perpetualSockets=${perpSockets[c]}")
        }

        val growth = heaps.last() - heaps.first()
        report("heap growth across ${cycles} cycles: ${growth / MB}MB (${heaps.first() / MB}MB -> ${heaps.last() / MB}MB)")

        assertTrue(growth < 40 * MB) { "heap grew by ${growth / MB}MB across identical load cycles - possible leak" }
        // One server-side + one client-side socket for the single live connection; no accumulation.
        assertEquals(baseSockets.first(), baseSockets.last(), "BaseWebSocketFactory.sockets accumulated entries")
        assertTrue(baseSockets.all { it in 1..4 }) { "unexpected socket-map sizes: ${baseSockets.toList()}" }
        assertEquals(1, perpSockets.last(), "PerpetualWebSocketFactory should track exactly one perpetual socket")
    }

    @Test
    @Order(5)
    @Timeout(150)
    fun `perpetual client survives connection churn without losing paced messages`() {
        awaitConnected()

        // --- Server-initiated close must reach the peer: closeAsync() closes the underlying Tomcat
        // session, the client receives the close frame, and its perpetual layer reconnects with a
        // NEW connection well before the read-timeout would fire. (Regression guard for the empty
        // ServerWebSocketFactory.handleClose() bug: back then the peer only noticed at read-timeout.)
        val serverSock = server.clients.filter { it.isConnected() }.single()
        serverSock.closeAsync("probe close")
        val reconnectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var replacement = server.clients.filter { it.isConnected() && it.id != serverSock.id }
        while (replacement.isEmpty() && System.nanoTime() < reconnectDeadline) {
            Thread.sleep(50)
            replacement = server.clients.filter { it.isConnected() && it.id != serverSock.id }
        }
        report("server-close propagation: serverSocket.hasClosed=${serverSock.hasClosed()}, " +
            "client reconnected with new connection=${replacement.isNotEmpty()}")
        assertTrue(serverSock.hasClosed(), "server-side closeAsync must close the library socket")
        assertTrue(replacement.isNotEmpty()) {
            "server-side closeAsync never reached the peer: the client did not reconnect within 10s " +
                "(close frame not sent - ServerWebSocketFactory.handleClose regression)"
        }
        awaitConnected()

        // --- Churn under paced load: kill the client transport a few times mid-run.
        server.resetPaced()
        val producers = 2
        val batches = 15
        val batchSize = 800
        val expected = producers.toLong() * batches * batchSize

        val kills = AtomicLong()
        val killer = thread(name = "churn-killer") {
            repeat(3) {
                Thread.sleep(2500)
                kills.addAndGet(killClientSideConnections().toLong())
            }
        }

        val retransmits = runPaced(producers, batches, batchSize, ackTimeoutMs = 10_000)
        killer.join()
        // The last kill is asynchronous: give it time to actually tear the connection down before
        // checking recovery, otherwise awaitConnected() can pass on the not-yet-closed connection.
        Thread.sleep(750)
        awaitConnected()
        awaitAtLeast(expected, timeoutSec = 40) { server.pacedDistinct() }

        report("churn: kills=${kills.get()} retransmits=$retransmits duplicates=${server.pacedDuplicates()} " +
            "orderViolations=${server.pacedViolations()} (violations possible: perpetual 2s resend can reorder)")
        report("churn: expected=$expected distinct=${server.pacedDistinct()}")

        assertTrue(kills.get() >= 3) { "churn did not actually kill connections" }
        assertTrue(client.ws.isConnected(), "perpetual client did not recover after churn")
        assertEquals(expected, server.pacedDistinct(), "PACED SCENARIO LOST MESSAGES ACROSS RECONNECTS")
    }
}
