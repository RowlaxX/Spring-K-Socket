package fr.rowlaxx.springksocket.util

import fr.rowlaxx.springksocket.exception.WebSocketClosedException
import fr.rowlaxx.springksocket.exception.WebSocketConnectionException
import fr.rowlaxx.springksocket.exception.WebSocketException
import fr.rowlaxx.springksocket.util.ByteBufferUtils.getBackingArray
import tools.jackson.core.util.ByteArrayBuilder
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage

class JavaWebSocketListener(
    private val onOpened: (WebSocket) -> Unit,
    private val onTextMessage: (String) -> Unit,
    private val onBinaryMessage: (ByteArray) -> Unit,
    private val onError: (WebSocketException) -> Unit,
    private val onPing: () -> Unit,
    private val onPong: () -> Unit,
    private val onPartialData: () -> Unit,
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
        onPing()
        return super.onPing(webSocket, message)
    }

    override fun onPong(webSocket: WebSocket?, message: ByteBuffer?): CompletionStage<*>? {
        onPong()
        return super.onPong(webSocket, message)
    }

    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
        if (currentBinary.size() == 0 && last) {
            onBinaryMessage(data.getBackingArray())
        }
        else {
            currentBinary.write(data.getBackingArray())

            if (last) {
                val result = currentBinary.toByteArray()
                currentBinary = ByteArrayBuilder()
                onBinaryMessage(result)
            }
            else {
                onPartialData()
            }
        }

        return super.onBinary(webSocket, data, last)
    }


    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
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
            else {
                onPartialData()
            }
        }

        return super.onText(webSocket, data, last)
    }

}