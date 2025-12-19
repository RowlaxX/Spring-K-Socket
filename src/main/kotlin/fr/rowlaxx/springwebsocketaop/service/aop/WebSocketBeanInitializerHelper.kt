package fr.rowlaxx.marketdata.lib.websocket.service.aop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import fr.rowlaxx.marketdata.lib.reflection.MyReflectionUtils
import fr.rowlaxx.marketdata.lib.websocket.annotation.*
import fr.rowlaxx.springwebsocketaop.exception.WebSocketException
import fr.rowlaxx.springwebsocketaop.model.WebSocket
import fr.rowlaxx.springwebsocketaop.annotation.OnCancelled
import fr.rowlaxx.springwebsocketaop.annotation.OnConnected
import fr.rowlaxx.springwebsocketaop.annotation.OnInitialized
import fr.rowlaxx.springwebsocketaop.annotation.OnMessage
import fr.rowlaxx.springwebsocketaop.annotation.WebSocketInitializer
import org.springframework.aop.support.AopUtils
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class WebSocketBeanInitializerHelper(
    private val objectMapper: ObjectMapper,
) {

    fun extractInitializer(bean: Any): Pair<WebSocketInitializerHandler, Duration> {
        val clazz = AopUtils.getTargetClass(bean)
        val anno = AnnotationUtils.getAnnotation(clazz, WebSocketInitializer::class.java)!!
        val timeout = Duration.ofMillis(anno.timeoutUnit.toMillis(anno.timeout))

        val onCanceledExec = MyReflectionUtils.whenAnnoPresentOnMethod(bean, OnCancelled::class.java)
            .map { MyReflectionUtils.executeFunction(
                obj = bean,
                method = it.first,
                params = listOf(WebSocket::class.java, WebSocketException::class.java)
            ) }

        val onInitializedExec = MyReflectionUtils.whenAnnoPresentOnMethod(bean, OnInitialized::class.java)
            .map { MyReflectionUtils.executeFunction(
                obj = bean,
                method = it.first,
                params = listOf(WebSocket::class.java)
            )}

        val onConnectedExec = MyReflectionUtils.whenAnnoPresentOnMethod(bean, OnConnected::class.java)
            .map { MyReflectionUtils.executeFunction(
                obj = bean,
                method = it.first,
                params = listOf(WebSocket::class.java, WebSocketInitializer::class.java)
            ) }

        var needParse = false
        val onMessageExec = MyReflectionUtils.whenAnnoPresentOnMethod(bean, OnMessage::class.java)
            .map {
                val jsonArg = it.first.parameterTypes.indexOfFirst { t -> JsonNode::class.java.isAssignableFrom(t) }
                val jsonArgType = if (jsonArg != -1) it.first.parameterTypes[jsonArg] else null
                val params = mutableListOf(WebSocket::class.java, String::class.java, WebSocketInitializer::class.java)

                if (jsonArgType != null) {
                    needParse = true
                    params.add(jsonArgType)
                }

                MyReflectionUtils.executeFunction(obj = bean, method = it.first, params = params)
            }

        val handler = InternalInitializerHandler(
            needParse = needParse,
            onCanceled = onCanceledExec,
            onConnected = onConnectedExec,
            onInitialized = onInitializedExec,
            onMessage = onMessageExec,
            parser = { objectMapper.readTree(it) },
        )

        return handler to timeout
    }

    private class InternalInitializerHandler(
        private val needParse: Boolean,
        private val parser: (String) -> JsonNode,
        private val onCanceled: List<((List<*>) -> Any?)>,
        private val onConnected: List<((List<*>) -> Any?)>,
        private val onInitialized: List<((List<*>) -> Any?)>,
        private val onMessage: List<((List<*>) -> Any?)>,
    ) : WebSocketInitializerHandler {

        override fun onCanceled(socket: WebSocket, error: WebSocketException) {
            val params = listOf(socket, error)
            onCanceled.mapNotNull { it(params) }
                .forEach { socket.sendMessageAsync(it) }
        }

        override fun onConnected(socket: WebSocket, initialization: WebSocketInitializer) {
            val params = listOf(socket, initialization)
            onConnected.mapNotNull { it(params) }
                .forEach { socket.sendMessageAsync(it) }
        }

        override fun onInitialized(socket: WebSocket) {
            val params = listOf(socket)
            onInitialized.mapNotNull { it(params) }
                .forEach { socket.sendMessageAsync(it) }
        }

        override fun onMessage(socket: WebSocket, message: String, initialization: WebSocketInitializer) {
            val params = mutableListOf(socket, message, initialization)

            if (needParse) {
                params.add(parser(message))
            }

            onMessage.mapNotNull { it(params) }
                .forEach { socket.sendMessageAsync(it) }
        }

    }

}