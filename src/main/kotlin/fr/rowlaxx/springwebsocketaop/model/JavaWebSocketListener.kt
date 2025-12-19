package fr.rowlaxx.springwebsocketaop.model

import fr.rowlaxx.springwebsocketaop.exception.WebSocketClosedException
import fr.rowlaxx.springwebsocketaop.exception.WebSocketConnectionException
import fr.rowlaxx.springwebsocketaop.exception.WebSocketException
import tools.jackson.core.util.ByteArrayBuilder
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage

class JavaWebSocketListener(
    private val onOpened: (WebSocket) -> Unit,
    private val onTextMessage: (String) -> Unit,
    private val onBinaryMessage: (ByteArray) -> Unit,
    private val onError: (WebSocketException) -> Unit,
    private val onDataReceived: () -> Unit,
) : WebSocket.Listener {
    private var currentMessage: StringBuilder = StringBuilder()
    private var currentBinary: ByteArrayBuilder = ByteArrayBuilder()

    override fun onClose(webSocket: WebSocket?, statusCode: Int, reason: String): CompletionStage<*>? {
        val ex = WebSocketClosedException(reason, statusCode)
        onError(ex)
        return super.onClose(webSocket, statusCode, reason)
    }

    override fun onError(webSocket: WebSocket?, error: Throwable?) {
        val msg = error?.message ?: "WebSocket error"
        val ex = WebSocketConnectionException(msg)
        onError(ex)
        super.onError(webSocket, error)
    }

    override fun onOpen(webSocket: WebSocket) {
        onOpened(webSocket)
        super.onOpen(webSocket)
    }

    override fun onPing(webSocket: WebSocket?, message: ByteBuffer?): CompletionStage<*>? {
        onDataReceived()
        return super.onPing(webSocket, message)
    }

    override fun onPong(webSocket: WebSocket?, message: ByteBuffer?): CompletionStage<*>? {
        onDataReceived()
        return super.onPong(webSocket, message)
    }

    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
        onDataReceived()

        if (currentBinary.size() == 0 && last) {
            onBinaryMessage(getBackingArray(data))
        }
        else {
            currentBinary.write(getBackingArray(data))

            if (last) {
                val result = currentBinary.toByteArray()
                currentBinary = ByteArrayBuilder()
                onBinaryMessage(result)
            }
        }

        return super.onBinary(webSocket, data, last)
    }


    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        onDataReceived()

        if (currentMessage.isEmpty() && last) {
            onTextMessage(data.toString())
        }
        else {
            currentMessage.append(data)

            if (last) {
                val result = currentMessage.toString()
                currentMessage = StringBuilder()
                onTextMessage(result)
            }
        }

        return super.onText(webSocket, data, last)
    }

    fun getBackingArray(buffer: ByteBuffer): ByteArray {
        if (buffer.hasArray() && !buffer.isReadOnly()) {
            return buffer.array().clone()
        } else {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            return bytes
        }
    }
}