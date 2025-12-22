package fr.rowlaxx.springwebsocketaop.data

import org.springframework.http.HttpHeaders
import java.net.URI
import java.time.Duration

data class WebSocketClientConfiguration(
    val uri: URI,
    val headers: HttpHeaders = HttpHeaders.EMPTY,

    val initTimeout: Duration = Duration.ofSeconds(10),
    val pingAfter: Duration = Duration.ofSeconds(5),
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(10),
) {
    init {
        if (pingAfter.isNegative) {
            throw IllegalArgumentException("pingInterval must be positive")
        }
        else if (initTimeout.isNegative) {
            throw IllegalArgumentException("initTimeout must be positive")
        }
        else if (connectTimeout.isNegative) {
            throw IllegalArgumentException("connectTimeout must be positive")
        }
        else if (readTimeout.isNegative) {
            throw IllegalArgumentException("readTimeout must be positive")
        }
    }
}
