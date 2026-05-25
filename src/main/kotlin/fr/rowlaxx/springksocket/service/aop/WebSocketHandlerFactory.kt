package fr.rowlaxx.springksocket.service.aop

import fr.rowlaxx.springksocket.annotation.OnAvailable
import fr.rowlaxx.springksocket.annotation.OnMessage
import fr.rowlaxx.springksocket.annotation.OnUnavailable
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.canInvoke
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.invoke
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.toInjectionSupport
import fr.rowlaxx.springkutils.reflection.utils.ReflectionUtils
import org.springframework.stereotype.Service

@Service
class WebSocketHandlerFactory(
    private val collectionManager: AutoWebSocketCollectionManager
) {

    fun extract(bean: Any, serializer: WebSocketSerializer, deserializer: WebSocketDeserializer): WebSocketHandler {
        collectionManager.initializeIfNotDone(bean)

        val available = ReflectionUtils.findMethodsWithAnnotation(bean, OnAvailable::class)
            .map { it.second.toInjectionSupport() }

        val unavailable = ReflectionUtils.findMethodsWithAnnotation(bean, OnUnavailable::class)
            .map { it.second.toInjectionSupport() }

        val onMessage = ReflectionUtils.findMethodsWithAnnotation(bean, OnMessage::class)
            .map { it.second.toInjectionSupport() }

        if (available.isEmpty() && unavailable.isEmpty() && onMessage.isEmpty()) {
            throw IllegalArgumentException("Bean ${bean::class.simpleName} is not a WebSocketHandler. Please add at least one @OnAvailable, @OnUnavailable or @OnMessage method")
        }

        return InternalImplementation(
            serializer = serializer,
            deserializer = deserializer,
            available = available,
            unavailable = unavailable,
            message = onMessage,
            bean = bean
        )
    }

    private inner class InternalImplementation(
        override val deserializer: WebSocketDeserializer,
        override val serializer: WebSocketSerializer,
        private val bean: Any,
        private val available: List<InjectionUtils.Injection>,
        private val unavailable: List<InjectionUtils.Injection>,
        private val message: List<InjectionUtils.Injection>,
    ) : WebSocketHandler {

        override fun onAvailable(webSocket: WebSocket) {
            collectionManager.onAvailable(bean, webSocket)

            val args = arrayOf(webSocket, webSocket.attributes)

            available.forEach {
                runInWS(it, webSocket, *args)
            }
        }

        override fun onMessage(webSocket: WebSocket, msg: Any) {
            if (msg == Unit) {
                return
            }

            val args = arrayOf(webSocket, webSocket.attributes, msg)

            message.filter { it.canInvoke(*args) }
                .apply { ifEmpty { log.warn("Unhandled message of type ${msg::class.simpleName} in bean ${bean::class.simpleName}") } }
                .forEach { runInWS(it, webSocket, *args) }
        }

        override fun onUnavailable(webSocket: WebSocket) {
            collectionManager.onUnavailable(bean, webSocket)

            val args = arrayOf(webSocket, webSocket.attributes)

            unavailable.forEach {
                runInWS(it, webSocket, *args)
            }
        }

        private fun runInWS(scheme: InjectionUtils.Injection, ws: WebSocket, vararg args: Any?) {
            runCatching { scheme.invoke(bean, *args) }
                .onFailure { log.error("Method ${scheme.method} threw an exception", it) }
                .onSuccess {
                    if (it != null && it != Unit) {
                        ws.sendMessageAsync(it).exceptionally { e ->
                            log.error("Unable to send returned object of type ${it::class.simpleName} : ${e.message}")
                        }
                    }
                }
        }
    }
}