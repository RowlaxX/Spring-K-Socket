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
import java.util.concurrent.ConcurrentHashMap

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

        // One handler instance serves every socket of the bean, and their task queues run
        // concurrently on a shared pool — hence ConcurrentHashMap (unlike the perpetual
        // variant, which is single-consumer per socket).
        private val handlersByMessageType = ConcurrentHashMap<Class<*>, List<InjectionUtils.Injection>>()

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

            val handlers = handlersByMessageType[msg.javaClass]
                ?: resolveHandlers(webSocket, msg.javaClass)

            for (i in handlers.indices) {
                runInWS(handlers[i], webSocket, webSocket, webSocket.attributes, msg)
            }
        }

        private fun resolveHandlers(webSocket: WebSocket, type: Class<*>): List<InjectionUtils.Injection> {
            // @OnMessage methods inject from (WebSocket, WebSocketAttributes, MessageType), and per
            // handler instance the webSocket/attributes runtime types are fixed; selection depends
            // only on the message class, so the result is stable per type and safe to memoize.
            val resolved = message.filter { it.canInvoke(webSocket.javaClass, webSocket.attributes.javaClass, type) }
            if (resolved.isEmpty()) {
                log.warn("Unhandled message of type ${type.simpleName} in bean ${bean::class.simpleName}")
            }
            handlersByMessageType[type] = resolved
            return resolved
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
                        ws.sendMessageAsync(it)
                    }
                }
        }
    }
}