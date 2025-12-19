package fr.rowlaxx.springwebsocketaop.event

import fr.rowlaxx.springwebsocketaop.model.PerpetualWebSocket

data class OnWebSocketsInitialized (
    val beanAndWS: List<Pair<Any, PerpetualWebSocket>>
)