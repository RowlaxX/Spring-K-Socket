package fr.rowlaxx.springksocket.service.perp

import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.PerpetualWebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import fr.rowlaxx.springksocket.service.io.BaseWebSocketFactory
import fr.rowlaxx.springksocket.service.io.ClientWebSocketFactory
import fr.rowlaxx.springkutils.collection.map.MutableLongObjectArrayMap
import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.Dsl
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpHeaders
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The perpetual layer must age out deduplicator buckets by itself. The library cannot rely on
 * `@Scheduled` firing: nothing in spring-k-socket enables Spring scheduling, so a consuming
 * application that wires the socket services without `@EnableScheduling` (e.g. providing
 * [GlobalThreadConfiguration] directly instead of importing `SpringKUtilsConfiguration`) would
 * otherwise never run the cleanup — and a long-lived connection overlap would retain every unique
 * message string forever.
 */
@Timeout(60)
class DeduplicatorCleanupSchedulingIT {

    /**
     * A consumer application that uses the library but never enables Spring scheduling.
     *
     * Deliberately NOT annotated with `@Configuration`: this package is component-scanned by
     * `SpringKSocketConfiguration`, and an annotated nested class would leak its beans into every
     * `@SpringBootTest` context. `AnnotationConfigApplicationContext` still processes the `@Bean`
     * methods in lite mode.
     */
    class ConsumerWithoutScheduling {
        @Bean fun threads() = GlobalThreadConfiguration()

        @Bean(destroyMethod = "close")
        fun consumerHttpClient(): AsyncHttpClient = Dsl.asyncHttpClient(
            DefaultAsyncHttpClientConfig.Builder()
                .setRequestTimeout(Duration.ofMillis(-1))
                .setReadTimeout(Duration.ofMillis(-1))
                .build()
        )

        @Bean fun baseWebSocketFactory(threads: GlobalThreadConfiguration) = BaseWebSocketFactory(threads)

        @Bean fun clientWebSocketFactory(
            base: BaseWebSocketFactory,
            threads: GlobalThreadConfiguration,
            http: AsyncHttpClient,
        ) = ClientWebSocketFactory(base, threads, http)

        @Bean fun perpetualWebSocketFactory(
            client: ClientWebSocketFactory,
            threads: GlobalThreadConfiguration,
        ) = PerpetualWebSocketFactory(client, threads)
    }

    private class NoopHandler : PerpetualWebSocketHandler {
        override val serializer = WebSocketSerializer.Passthrough
        override val deserializer = WebSocketDeserializer.Passthrough
        override fun onAvailable(webSocket: PerpetualWebSocket) {}
        override fun onMessage(webSocket: PerpetualWebSocket, connection: WebSocket, msg: Any) {}
    }

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

    @Test
    fun `stale deduplicator buckets are aged out even when the consumer never enables Spring scheduling`() {
        val ctx = AnnotationConfigApplicationContext(ConsumerWithoutScheduling::class.java)
        try {
            // Precondition: this consumer context must NOT process @Scheduled annotations.
            assertTrue(
                ctx.beanDefinitionNames.none { it.contains("internalScheduledAnnotationProcessor") },
                "precondition failed: the consumer context unexpectedly enables Spring scheduling"
            )

            val factory = ctx.getBean(PerpetualWebSocketFactory::class.java)

            // No server needs to listen: the perpetual instance (and its deduplicator) exists regardless.
            val port = ServerSocket(0).use { it.localPort }
            factory.create(
                name = "dedup-cleanup",
                initializers = emptyList(),
                handler = NoopHandler(),
                propertiesFactory = {
                    WebSocketClientProperties(
                        uri = URI.create("ws://${InetAddress.getLoopbackAddress().hostAddress}:$port"),
                        headers = HttpHeaders.of(emptyMap()) { _, _ -> true },
                        initTimeout = Duration.ofSeconds(10),
                        pingInterval = Duration.ofSeconds(5),
                        readTimeout = Duration.ofSeconds(15),
                    )
                },
                shiftDuration = Duration.ofHours(1),
                switchDuration = Duration.ofHours(1),
                dedupe = true,
            )

            // Reach into the live deduplicator and plant an ancient bucket (key 1 is far older than
            // MAX_BUCKET_AGE). The library's own cleanup must remove it within a few of its 5s periods.
            val instance = readField<Map<*, *>>(factory, "sockets").values.first()!!
            val deduplicator = readField<Any>(instance, "deduplicator")
            val buckets = readField<MutableLongObjectArrayMap<Any?>>(deduplicator, "buckets")
            val bucketClass = Class.forName("fr.rowlaxx.springksocket.core.MessageDeduplicator\$Bucket")
            val bucket = bucketClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            buckets.put(1L, bucket)

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (buckets[1L] != null && System.nanoTime() < deadline) Thread.sleep(100)
            assertTrue(
                buckets[1L] == null,
                "stale deduplicator bucket was never cleaned up: the library must age out buckets itself " +
                    "instead of relying on a @Scheduled annotation the consumer may never enable"
            )
        } finally {
            ctx.close()
        }
    }
}
