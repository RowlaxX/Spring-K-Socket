package fr.rowlaxx.marketdata.lib.websocket.service.aop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import fr.rowlaxx.marketdata.lib.reflection.MyReflectionUtils
import fr.rowlaxx.springwebsocketaop.annotation.OnConnected
import fr.rowlaxx.springwebsocketaop.annotation.OnDisconnected
import fr.rowlaxx.springwebsocketaop.annotation.OnMessage
import fr.rowlaxx.springwebsocketaop.exception.WebSocketException
import fr.rowlaxx.springwebsocketaop.model.PerpetualWebSocket
import fr.rowlaxx.springwebsocketaop.model.PerpetualWebSocketHandler
import org.springframework.stereotype.Service

@Service
class WebSocketBeanHandlerHelper(
    private val objectMapper: ObjectMapper,
) {

    fun extractHandler(bean: Any): PerpetualWebSocketHandler {
        val onConnectedExec = MyReflectionUtils.whenAnnoPresentOnMethod(bean, OnConnected::class.java)
            .map { MyReflectionUtils.executeFunction(
                obj = bean,
                method = it.first,
                params = listOf(PerpetualWebSocket::class.java)
            ) }

        val onDisconnectedExec = MyReflectionUtils.whenAnnoPresentOnMethod(bean, OnDisconnected::class.java)
            .map { MyReflectionUtils.executeFunction(
                obj = bean,
                method = it.first,
                params = listOf(PerpetualWebSocket::class.java, Exception::class.java)
            ) }

        var needParse = false
        val onMessageExec = MyReflectionUtils.whenAnnoPresentOnMethod(bean, OnMessage::class.java)
            .map {
                val jsonArg = it.first.parameterTypes.indexOfFirst { t -> JsonNode::class.java.isAssignableFrom(t) }
                val jsonArgType = if (jsonArg != -1) it.first.parameterTypes[jsonArg] else null
                val params = mutableListOf(PerpetualWebSocket::class.java, String::class.java)

                if (jsonArgType != null) {
                    needParse = true
                    params.add(jsonArgType)
                }

                MyReflectionUtils.executeFunction(obj = bean, method = it.first, params = params)
            }

        val handler = InternalHandler(
            needParse = needParse,
            onConnected = onConnectedExec,
            onMessage = onMessageExec,
            parser = { objectMapper.readTree(it) },
            onDisconnected = onDisconnectedExec
        )

        return handler
    }

    private class InternalHandler(
        private val onConnected: List<(List<*>) -> Any?>,
        private val onDisconnected: List<(List<*>) -> Any?>,
        private val onMessage: List<(List<*>) -> Any?>,
        private val needParse: Boolean,
        private val parser: (String) -> JsonNode,
    ) : PerpetualWebSocketHandler {

        override fun onConnected(webSocket: PerpetualWebSocket) {
            val params = listOf(webSocket)

            onConnected.mapNotNull { it(params) }
                .forEach { webSocket.sendMessageAsync(it) }
        }

        override fun onDisconnected(webSocket: PerpetualWebSocket, exception: WebSocketException) {
            val params = listOf(webSocket, exception)

            onDisconnected.mapNotNull { it(params) }
                .forEach { webSocket.sendMessageAsync(it) }
        }

        override fun onMessage(webSocket: PerpetualWebSocket, text: String) {
            val params = mutableListOf(webSocket, text)

            if (needParse) {
                params.add(parser(text))
            }

            onMessage.mapNotNull { it(params) }
                .forEach { webSocket.sendMessageAsync(it) }
        }
    }

}