package fr.rowlaxx.springksocket.service.aop

import fr.rowlaxx.springksocket.annotation.OnAvailable
import fr.rowlaxx.springksocket.annotation.OnMessage
import fr.rowlaxx.springksocket.annotation.OnUnavailable
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.PerpetualWebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import fr.rowlaxx.springksocket.util.ReflectionUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class PerpetualWebSocketHandlerFactory() {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val onAvailableInject = arrayOf(PerpetualWebSocket::class)
    private val onUnavailableInject = arrayOf(PerpetualWebSocket::class)
    private val onMessageInject = arrayOf(PerpetualWebSocket::class, Any::class)

    fun extract(bean: Any, serializer: WebSocketSerializer, deserializer: WebSocketDeserializer): PerpetualWebSocketHandler {
        val available = ReflectionUtils.findMethodsWithAnnotation(bean, OnAvailable::class)
            .map { it.second }
            .map { ReflectionUtils.findInjectionScheme(bean, it, *onAvailableInject) }

        val onMessage = ReflectionUtils.findMethodsWithAnnotation(bean, OnMessage::class)
            .map { it.second }
            .map { ReflectionUtils.findInjectionScheme(bean, it, *onMessageInject) }

        val unavailable = ReflectionUtils.findMethodsWithAnnotation(bean, OnUnavailable::class)
            .map { it.second }
            .map { ReflectionUtils.findInjectionScheme(bean, it, *onUnavailableInject) }

        if (available.isEmpty() && unavailable.isEmpty()) {
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

    private inner class InternalImplementation(
        override val deserializer: WebSocketDeserializer,
        override val serializer: WebSocketSerializer,
        private val bean: Any,
        private val available: List<ReflectionUtils.InjectionScheme>,
        private val unavailable: List<ReflectionUtils.InjectionScheme>,
        private val message: List<ReflectionUtils.InjectionScheme>,
    ) : PerpetualWebSocketHandler {

        override fun onAvailable(webSocket: PerpetualWebSocket) {
            val args = arrayOf(webSocket)

            available.forEach {
                runInWS(it, webSocket, *args)
            }
        }

        override fun onMessage(webSocket: PerpetualWebSocket, msg: Any) {
            val args1 = arrayOf(webSocket, msg)
            var handled = false

            message.filter { ReflectionUtils.canInject(it, *args1) }
                .apply { if (isNotEmpty()) handled = true }
                .forEach { runInWS(it, webSocket, *args1) }

            val deserialized = deserializer.fromStringOrByteArray(msg)

            if (deserialized !== msg) {
                val args2 = arrayOf(webSocket, deserialized)

                message.filter { ReflectionUtils.canInject(it, *args2) }
                    .apply { if (isNotEmpty()) handled = true }
                    .forEach { runInWS(it, webSocket, *args2) }
            }

            if (!handled) {
                log.warn("Unhandled message of type ${deserialized::class.simpleName} in bean ${bean::class.simpleName}")
            }
        }

        override fun onUnavailable(webSocket: PerpetualWebSocket) {
            val args = arrayOf(webSocket)

            unavailable.forEach {
                runInWS(it, webSocket, *args)
            }
        }

        private fun runInWS(scheme: ReflectionUtils.InjectionScheme, ws: PerpetualWebSocket, vararg args: Any?) {
            runCatching { ReflectionUtils.inject(scheme, *args) }
                .onFailure { log.error("Method ${scheme.method} threw an exception", it) }
                .onSuccess {
                    if (it != null && it != Unit) {
                        ws.sendMessageAsync(it) // Future is not failable
                    }
                }
        }
    }
}