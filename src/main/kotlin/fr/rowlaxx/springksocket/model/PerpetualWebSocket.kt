package fr.rowlaxx.springksocket.model

import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import java.time.Duration

interface PerpetualWebSocket : WebSocketMessageSender {

    val id: Int
    val name: String
    val switchDuration: Duration
    val shiftDuration: Duration
    val initializers: List<WebSocketHandler>
    val handler: PerpetualWebSocketHandler
    val propertiesFactory: () -> WebSocketClientProperties

    fun isConnected(): Boolean

}