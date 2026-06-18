package fr.rowlaxx.springksocket.data

import java.net.URI
import java.net.http.HttpHeaders
import java.time.Duration

data class WebSocketClientProperties(
    val uri: URI,
    val headers: HttpHeaders,

    val initTimeout: Duration,
    val pingAfter: Duration,
    val readTimeout: Duration,
) {
    init {
        if (pingAfter.isNegative) {
            throw IllegalArgumentException("pingInterval must be positive")
        }
        else if (initTimeout.isNegative) {
            throw IllegalArgumentException("initTimeout must be positive")
        }
        else if (readTimeout.isNegative) {
            throw IllegalArgumentException("readTimeout must be positive")
        }
    }
}
