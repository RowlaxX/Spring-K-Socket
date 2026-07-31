package fr.rowlaxx.springksocket

import fr.rowlaxx.springkutils.SpringKUtilsConfiguration
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.Dsl
import org.springframework.boot.SpringBootConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.time.Duration

/**
 * Test-only configuration so `@SpringBootTest` can bootstrap the library the way a consuming application
 * would. It explicitly imports the socket configuration (which component-scans every factory/service and
 * enables the WebSocket server support) and the spring-k-utils configuration that provides the shared
 * thread pools and the `httpClient` bean the client transport depends on.
 *
 * It deliberately does NOT use `@EnableAutoConfiguration`: spring-k-utils drags JPA/Hibernate onto the
 * classpath, and full auto-configuration would then try to build a `DataSource` and fail — this library
 * needs no database. Importing just the two configurations wires exactly what the socket layer requires.
 */
@SpringBootConfiguration
@Import(SpringKSocketConfiguration::class, SpringKUtilsConfiguration::class)
class TestApplication {

    /**
     * Overrides spring-k-utils' `httpClient` bean (requires
     * `spring.main.allow-bean-definition-overriding=true` on the test): that bean hands AHC 3.0.x a
     * netty 4.2 `MultiThreadIoEventLoopGroup`, which AHC does not recognize ("Unknown event loop
     * group"). Without an explicit group, AHC creates — and releases on `close()` — its own
     * `NioEventLoopGroup`.
     */
    @Bean(destroyMethod = "close")
    fun httpClient(): AsyncHttpClient = Dsl.asyncHttpClient(
        DefaultAsyncHttpClientConfig.Builder()
            .setRequestTimeout(Duration.ofMillis(-1))
            .setReadTimeout(Duration.ofMillis(-1))
            .setWebSocketMaxFrameSize(16 * 1024 * 1024)
            .build()
    )
}
