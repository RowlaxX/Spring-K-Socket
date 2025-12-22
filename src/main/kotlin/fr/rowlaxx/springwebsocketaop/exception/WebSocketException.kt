package fr.rowlaxx.springwebsocketaop.exception

open class WebSocketException(msg: String, t: Throwable?) : Exception(msg, t)

class WebSocketClosedException(
    val reason: String,
    val code: Int,
) : WebSocketException("$reason ($code)", null)

class WebSocketInitializationException(msg: String): WebSocketException(msg, null)
class WebSocketConnectionException(msg: String) : WebSocketException(msg, null)
class WebSocketCreationException(msg: String) : WebSocketException(msg, null)