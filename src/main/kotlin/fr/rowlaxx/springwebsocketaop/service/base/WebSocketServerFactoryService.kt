package fr.rowlaxx.springwebsocketaop.service.base

import fr.rowlaxx.springwebsocketaop.exception.WebSocketClosedException
import fr.rowlaxx.springwebsocketaop.exception.WebSocketConnectionException
import fr.rowlaxx.springwebsocketaop.model.WebSocket
import fr.rowlaxx.springwebsocketaop.model.WebSocketHandler
import org.springframework.stereotype.Service
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.exp

@Service
class WebSocketServerFactoryService(
    private val baseFactory: BaseWebSocketFactoryService,
) {
    private val sender = Executors.newScheduledThreadPool(4) { Thread(it, "WebSocket Sender") }

    fun accept(
        session: WebSocketSession,
        handlerChain: List<WebSocketHandler>,


    ): WebSocket {



    }

    private class InternalImplementation(
        private val sender: Executor,
        private val session: WebSocketSession,
    ) : BaseWebSocketFactoryService.BaseWebSocket {

        init {
            session.attributes["pong"] = { onDataReceived() }
            session.attributes["text"] = { text: String -> acceptMessage(text) }
            session.attributes["binary"] = { bin: ByteArray -> acceptMessage(bin) }

            session.attributes["close"] = { status: CloseStatus -> safeAsync {
                unsafeCloseWith(WebSocketClosedException(status.reason ?: "Unknown reason", status.code))
            } }

            session.attributes["error"] = { error: Throwable -> safeAsync {
                unsafeCloseWith(WebSocketConnectionException("Transport error : ${error.message}"))
            } }

            safeAsync {
                unsafeOpenWith(session)
            }
        }

        private fun send(task: () -> Unit): CompletableFuture<Unit> {
            val cf = CompletableFuture<Unit>()

            sender.execute {
                try {
                    task()
                    cf.complete(Unit)
                } catch (e: Exception) {
                    cf.completeExceptionally(e)
                }
            }

            return cf
        }

        override fun unsafePingNow(): CompletableFuture<*> {
            return send { session.sendMessage(PingMessage()) }
        }

        override fun unsafeSendText(msg: String): CompletableFuture<*> {
            return send { session.sendMessage(TextMessage(msg)) }
        }

        override fun unsafeSendBinary(msg: ByteArray): CompletableFuture<*> {
            return send { session.sendMessage(BinaryMessage(msg)) }
        }

        override fun unsafeHandleClose() {}
        override fun unsafeHandleOpen(obj: Any) {}

    }

}