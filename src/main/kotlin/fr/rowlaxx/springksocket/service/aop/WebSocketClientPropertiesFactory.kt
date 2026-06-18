package fr.rowlaxx.springksocket.service.aop

import fr.rowlaxx.springksocket.annotation.WebSocketClient
import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.util.HttpHeadersUtils.toJavaHeaders
import fr.rowlaxx.springkutils.reflection.utils.ReflectionUtils
import org.springframework.aop.support.AopUtils
import org.springframework.context.ApplicationContext
import org.springframework.context.expression.BeanFactoryResolver
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Duration

@Service
class WebSocketClientPropertiesFactory(
    private val applicationContext: ApplicationContext
) {
    private val parser = SpelExpressionParser()
    private val context = StandardEvaluationContext()
        .apply { beanResolver = BeanFactoryResolver(applicationContext) }

    fun extract(bean: Any): () -> WebSocketClientProperties {
        val methods = ReflectionUtils.findMethodsWithReturnType(bean, WebSocketClientProperties::class.java)
        val type = AopUtils.getTargetClass(bean)
        val anno = type.getAnnotation(WebSocketClient::class.java)

        if (methods.isEmpty()) {
            if (anno == null) {
                throw IllegalArgumentException("Class ${bean.javaClass} must be annotated with @WebSocketClient or contains a method which returns WebSocketClientProperties")
            }

            val props = extractFromAnnotation(anno)
            return { props }
        }
        else if (methods.size > 1) {
            throw IllegalArgumentException("Only one method in ${bean.javaClass} can return WebSocketClientProperties")
        }

        val method = methods.single()

        return {
            org.springframework.util.ReflectionUtils.invokeMethod(method, bean) as WebSocketClientProperties
        }
    }

    private fun extractFromAnnotation(anno: WebSocketClient): WebSocketClientProperties {
        val headers = HttpHeaders()

        for (header in anno.headers) {
            val value = evaluate(header.expression) ?: continue
            headers.add(header.name, value.toString())
        }

        return WebSocketClientProperties(
            uri = URI.create(anno.url),
            initTimeout = Duration.parse(anno.initTimeout),
            readTimeout = Duration.parse(anno.readTimeout),
            pingAfter = Duration.parse(anno.pingAfter),
            headers = headers.toJavaHeaders()
        )

    }

    private fun evaluate(expression: String): Any? {
        val exp = parser.parseExpression(expression)
        return exp.getValue(context)
    }


}