package fr.rowlaxx.springksocket.service.aop

import fr.rowlaxx.springksocket.annotation.AfterHandshake
import fr.rowlaxx.springksocket.annotation.BeforeHandshake
import fr.rowlaxx.springksocket.util.HttpHeadersUtils.toJavaHeaders
import fr.rowlaxx.springksocket.util.WebSocketMapAttributesUtils
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.canInvoke
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.invoke
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.toInjectionSupport
import fr.rowlaxx.springkutils.reflection.utils.ReflectionUtils
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Service
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor

@Service
class HandshakeInterceptorFactory {

    fun extract(bean: Any): HandshakeInterceptor {
        val before = ReflectionUtils.findMethodsWithAnnotation(bean, BeforeHandshake::class)
            .map { it.second.toInjectionSupport() }

        val after = ReflectionUtils.findMethodsWithAnnotation(bean, AfterHandshake::class)
            .map { it.second.toInjectionSupport() }

        return InternalImplementation(
            after = after,
            before = before
        )
    }

    private class InternalImplementation(
        private val before: List<InjectionUtils.Injection>,
        private val after: List<InjectionUtils.Injection>,
    ) : HandshakeInterceptor {

        override fun beforeHandshake(
            request: ServerHttpRequest,
            response: ServerHttpResponse,
            wsHandler: WebSocketHandler,
            wsAttributes: MutableMap<String, Any>
        ): Boolean {
            val attributes = WebSocketMapAttributesUtils.getOrCreateAttributes(request.attributes)
            WebSocketMapAttributesUtils.setAttributes(wsAttributes, attributes)
            WebSocketMapAttributesUtils.setRequestHeaders(wsAttributes, request.headers.toJavaHeaders())
            WebSocketMapAttributesUtils.setURI(wsAttributes, request.uri)

            val args = arrayOf(request, response, attributes)

            before.filter { it.canInvoke(*args) }.forEach {
                try {
                    val result = it.invoke(it, *args)

                    if (result == false) {
                        return false
                    }
                } catch (e: Exception) {
                    log.warn("Method ${it.method} threw an exception. Please avoid throwing an exception from a @BeforeHandshake function but return false instead.", e)
                    throw e
                }
            }

            return true
        }

        override fun afterHandshake(
            request: ServerHttpRequest,
            response: ServerHttpResponse,
            wsHandler: WebSocketHandler,
            exception: Exception?
        ) {
            val attributes = WebSocketMapAttributesUtils.getOrCreateAttributes(request.attributes)
            val args = arrayOf(request, response, attributes)

            after.filter { it.canInvoke(*args) }.forEach {
                try {
                    it.invoke(it, *args)
                } catch (e: Exception) {
                    log.warn("Method ${it.method} threw an Exception", e)
                }
            }
        }
    }
}