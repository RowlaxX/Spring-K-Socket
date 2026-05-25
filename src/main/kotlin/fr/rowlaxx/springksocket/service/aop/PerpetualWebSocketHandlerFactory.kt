package fr.rowlaxx.springksocket.service.aop

import fr.rowlaxx.springksocket.annotation.OnAvailable
import fr.rowlaxx.springksocket.annotation.OnMessage
import fr.rowlaxx.springksocket.annotation.OnUnavailable
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.PerpetualWebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.canInvoke
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.invoke
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.toInjectionSupport
import fr.rowlaxx.springkutils.reflection.utils.ReflectionUtils
import org.springframework.stereotype.Service

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

        override fun onAvailable(webSocket: PerpetualWebSocket) {
            val args = arrayOf(webSocket)

            available.forEach {
                runInWS(it, webSocket, *args)
            }
        }

        override fun onMessage(webSocket: PerpetualWebSocket, msg: Any) {
            if (msg == Unit) {
                return
            }

            val args1 = arrayOf(webSocket, msg)

            message.filter { it.canInvoke(*args1) }
                .apply { ifEmpty { log.warn("Unhandled message of type ${msg::class.simpleName} in bean ${bean::class.simpleName}") }}
                .forEach { runInWS(it, webSocket, *args1) }
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