package fr.rowlaxx.springksocket

import fr.rowlaxx.springkutils.SpringKUtilsConfiguration
import org.springframework.boot.SpringBootConfiguration
import org.springframework.context.annotation.Import

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
class TestApplication
