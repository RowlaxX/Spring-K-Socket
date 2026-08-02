package fr.rowlaxx.springksocket.service.aop

import fr.rowlaxx.springksocket.annotation.OnAvailable
import fr.rowlaxx.springksocket.annotation.OnMessage
import fr.rowlaxx.springksocket.annotation.OnUnavailable
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.PerpetualWebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.canInvoke
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.invoke
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.toInjectionSupport
import fr.rowlaxx.springkutils.reflection.utils.ReflectionUtils
import org.springframework.stereotype.Service
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap

@Service
class PerpetualWebSocketHandlerFactory() {

    fun extract(bean: Any, serializer: WebSocketSerializer, deserializer: WebSocketDeserializer): PerpetualWebSocketHandler {
        val available = ReflectionUtils.findMethodsWithAnnotation(bean, OnAvailable::class)
            .map { it.second.toInjectionSupport() }

        val onMessage = ReflectionUtils.findMethodsWithAnnotation(bean, OnMessage::class)
            .map { it.second.toInjectionSupport() }

        val unavailable = ReflectionUtils.findMethodsWithAnnotation(bean, OnUnavailable::class)
            .map { it.second.toInjectionSupport() }

        if (available.isEmpty() && unavailable.isEmpty() && onMessage.isEmpty()) {
            throw IllegalArgumentException("Bean ${bean::class.simpleName} is not a PerpetualWebSocketHandler. Please add at least one @OnAvailable, @OnUnavailable or @OnMessage method")
        }

        return InternalImplementation(
            available = available,
            unavailable = unavailable,
            message = onMessage,
            serializer = serializer,
            deserializer = deserializer,
            bean = bean
        )
    }

    private class InternalImplementation(
        override val deserializer: WebSocketDeserializer,
        override val serializer: WebSocketSerializer,
        private val bean: Any,
        private val available: List<InjectionUtils.Injection>,
        private val unavailable: List<InjectionUtils.Injection>,
        private val message: List<InjectionUtils.Injection>,
    ) : PerpetualWebSocketHandler {

        private val handlersByMessageType = HashMap<Class<*>, List<InjectionUtils.Injection>>()

        override fun onAvailable(webSocket: PerpetualWebSocket) {
            val args = arrayOf(webSocket)

            available.forEach {
                runInWS(it, webSocket, *args)
            }
        }

        override fun onMessage(webSocket: PerpetualWebSocket, connection: WebSocket, msg: Any) {
            if (msg == Unit) {
                return
            }

            val handlers = handlersByMessageType[msg.javaClass]
                ?: resolveHandlers(webSocket, connection, msg.javaClass)

            for (i in handlers.indices) {
                runInWS(handlers[i], webSocket, webSocket, connection, msg)
            }
        }

        private fun resolveHandlers(webSocket: PerpetualWebSocket, connection: WebSocket, type: Class<*>): List<InjectionUtils.Injection> {
            // @OnMessage methods inject from (PerpetualWebSocket, WebSocket connection, MessageType); the
            // socket runtime types are fixed per handler, so selection depends only on the message class
            // and is safe to memoize.
            val resolved = message.filter { it.canInvoke(webSocket.javaClass, connection.javaClass, type) }
            if (resolved.isEmpty()) {
                log.warn("Unhandled message of type ${type.simpleName} in bean ${bean::class.simpleName}")
            }
            handlersByMessageType[type] = resolved
            return resolved
        }

        override fun onUnavailable(webSocket: PerpetualWebSocket) {
            val args = arrayOf(webSocket)

            unavailable.forEach {
                runInWS(it, webSocket, *args)
            }
        }

        private fun runInWS(scheme: InjectionUtils.Injection, ws: PerpetualWebSocket, vararg args: Any?) {
            runCatching { scheme.invoke(bean, *args) }
                .onFailure { log.error("Method ${scheme.method} threw an exception", it) }
                .onSuccess {
                    if (it != null && it != Unit) {
                        ws.sendMessageAsync(it) // Future is not failable
                    }
                }
        }
    }
}