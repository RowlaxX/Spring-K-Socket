package fr.rowlaxx.springksocket.service.io

import com.sun.management.ThreadMXBean
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.Dsl
import org.asynchttpclient.ws.WebSocket as AhcWebSocket
import org.asynchttpclient.ws.WebSocketListener
import org.asynchttpclient.ws.WebSocketUpgradeHandler
import org.java_websocket.WebSocket as JWebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Not a correctness test — quantifies optimization vector 2: the JDK `java.net.http.WebSocket`
 * transport allocates a fresh decrypt buffer per TLS chunk (`SSLFlowDelegate.getAppBuffer`), whereas
 * the async-http-client (Netty) transport now used by [ClientWebSocketFactory] reads through pooled
 * Netty buffers.
 *
 * Both clients consume the identical wss:// message stream from one in-process TLS server, so the
 * server-side allocation is a shared baseline and the delta isolates the transport. We measure total
 * heap allocation across all threads ([ThreadMXBean.getTotalThreadAllocatedBytes]).
 *
 * Run manually:
 *   ./gradlew test --tests "*WsTransportAllocationBench" -i
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WsTransportAllocationBench {

    private val messages = 8_000
    private val payload = buildString {
        append("{\"bids\":[")
        repeat(200) { i -> if (i > 0) append(','); append("[\"6${5000 + i}.${10000000 + i}\",\"0.${1000000 + i}\"]") }
        append("]}")
    } // ~8 KB, shaped like an orderbook depth frame

    private lateinit var serverCtx: SSLContext
    private lateinit var server: WebSocketServer
    private var port = 0

    @BeforeAll
    fun setUp() {
        val ks = generateKeystore() ?: return
        serverCtx = serverContext(ks.first, ks.second)

        server = object : WebSocketServer(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) {
            override fun onStart() {}
            override fun onOpen(conn: JWebSocket, handshake: ClientHandshake) {
                Thread { repeat(messages) { conn.send(payload) } }.start()
            }
            override fun onClose(conn: JWebSocket, code: Int, reason: String?, remote: Boolean) {}
            override fun onError(conn: JWebSocket?, ex: Exception) {}
            override fun onMessage(conn: JWebSocket, message: String) {}
        }.apply {
            setWebSocketFactory(DefaultSSLWebSocketServerFactory(serverCtx))
            start()
            for (i in 0 until 100) { if (getPort() > 0) break; Thread.sleep(50) }
        }
        port = server.port
        assumeTrue(port > 0, "TLS server did not bind")
    }

    @AfterAll
    fun tearDown() {
        if (::server.isInitialized) runCatching { server.stop(1000) }
    }

    private val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean

    private fun measure(label: String, run: () -> Unit): Long {
        System.gc(); Thread.sleep(200)
        val before = bean.totalThreadAllocatedBytes
        run()
        val bytes = bean.totalThreadAllocatedBytes - before
        println("$label: ${bytes / 1_000_000.0} MB total  |  ${"%.1f".format(bytes.toDouble() / messages)} bytes/msg")
        return bytes
    }

    @Test
    fun `netty transport allocates less than the JDK client over TLS`() {
        assumeTrue(::serverCtx.isInitialized, "keytool/cert unavailable — skipping")

        receiveViaJdkClient(); receiveViaAhc()   // warm up both (JIT)

        val jdk = measure("java.net.http.WebSocket ") { receiveViaJdkClient() }
        val ahc = measure("async-http-client (Netty)") { receiveViaAhc() }

        println("saved     = ${"%.1f".format((jdk - ahc) / 1_000_000.0)} MB over $messages msgs (shared server cost included)")
        println("reduction = ${"%.1f".format((1 - ahc.toDouble() / jdk) * 100)}% (lower bound)")
        assertTrue(ahc < jdk * 0.8,
            "Netty transport should allocate <80% of the JDK client over TLS: jdk=$jdk ahc=$ahc")
    }

    private fun receiveViaJdkClient() {
        val done = CompletableFuture<Void>()
        val count = AtomicInteger()
        val client = HttpClient.newBuilder().sslContext(trustAllContext()).build()
        val ws = client.newWebSocketBuilder()
            .buildAsync(URI.create("wss://localhost:$port"), object : WebSocket.Listener {
                override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
                    if (last && count.incrementAndGet() >= messages) done.complete(null)
                    webSocket.request(1)
                    return null
                }
            }).join()
        done.orTimeout(60, TimeUnit.SECONDS).join()
        ws.abort()
    }

    private fun receiveViaAhc() {
        val done = CountDownLatch(1)
        val count = AtomicInteger()
        val client = Dsl.asyncHttpClient(
            DefaultAsyncHttpClientConfig.Builder()
                .setThreadPoolName("WS-Bench")
                .setUseInsecureTrustManager(true)
                .setRequestTimeout(Duration.ofMillis(-1))
                .setReadTimeout(Duration.ofMillis(-1))
                .build()
        )
        try {
            client.prepareGet("wss://localhost:$port").execute(
                WebSocketUpgradeHandler.Builder().addWebSocketListener(object : WebSocketListener {
                    override fun onOpen(websocket: AhcWebSocket) {}
                    override fun onClose(websocket: AhcWebSocket, code: Int, reason: String?) {}
                    override fun onError(t: Throwable) {}
                    override fun onTextFrame(payload: String, finalFragment: Boolean, rsv: Int) {
                        if (finalFragment && count.incrementAndGet() >= messages) done.countDown()
                    }
                }).build()
            )
            assertTrue(done.await(60, TimeUnit.SECONDS), "AHC did not receive all messages")
        } finally {
            client.close()
        }
    }

    // --- cert / ssl plumbing -------------------------------------------------------------------

    private fun generateKeystore(): Pair<String, CharArray>? {
        val dir = Files.createTempDirectory("ws-bench")
        val path = dir.resolve("ks.p12").toString()
        val pass = "changeit"
        val proc = ProcessBuilder(
            "keytool", "-genkeypair", "-alias", "ws", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=localhost", "-validity", "1",
            "-ext", "san=dns:localhost,ip:127.0.0.1",
            "-keystore", path, "-storetype", "PKCS12", "-storepass", pass, "-keypass", pass
        ).redirectErrorStream(true).start()
        val ok = proc.waitFor(30, TimeUnit.SECONDS) && proc.exitValue() == 0
        assumeTrue(ok, "keytool unavailable — skipping TLS allocation benchmark")
        return path to pass.toCharArray()
    }

    private fun serverContext(ksPath: String, pass: CharArray): SSLContext {
        val ks = KeyStore.getInstance("PKCS12")
        Files.newInputStream(java.nio.file.Path.of(ksPath)).use { ks.load(it, pass) }
        val kmf = KeyManagerFactory.getInstance("SunX509").apply { init(ks, pass) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    private fun trustAllContext(): SSLContext {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        return SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
    }
}
