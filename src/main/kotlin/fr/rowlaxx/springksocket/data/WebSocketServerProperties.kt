package fr.rowlaxx.springksocket.data

import fr.rowlaxx.springksocket.model.WebSocketHandler
import java.time.Duration

data class WebSocketServerProperties(
    val name: String,

    val handlerChain: List<WebSocketHandler>,
    val initTimeout: Duration,
    val pingInterval: Duration,
    val readTimeout: Duration,
)
