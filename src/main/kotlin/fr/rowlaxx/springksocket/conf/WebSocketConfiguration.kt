package fr.rowlaxx.springksocket.conf

import fr.rowlaxx.springkutils.concurrent.utils.ExecutorsUtils
import jakarta.annotation.PreDestroy
import org.springframework.context.annotation.Configuration

@Configuration
class WebSocketConfiguration {

    val executor = ExecutorsUtils.newFailsafeScheduledExecutor(8, "WebSocket")

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}