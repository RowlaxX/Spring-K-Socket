package fr.rowlaxx.marketdata.lib.websocket.service.aop

import fr.rowlaxx.springwebsocketaop.annotation.WebSocketClientConfiguration
import fr.rowlaxx.springwebsocketaop.data.WebSocketClientConfiguration
import org.springframework.aop.support.AopUtils
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.stereotype.Service
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method
import java.net.URI
import java.time.Duration

@Service
class WebSocketBeanConfigurationFactoryHelper {

    fun extractConfigurationFactory(bean: Any): () -> fr.rowlaxx.springwebsocketaop.data.WebSocketClientConfiguration {
        val clazz = AopUtils.getTargetClass(bean)
        val anno = AnnotationUtils.getAnnotation(clazz, WebSocketClientConfiguration::class.java)

        if (anno != null) {
            val conf = WebSocketClientConfiguration(
                uri = URI.create(anno.uri),
                pingInterval = Duration.ofMillis(anno.pingIntervalUnit.toMillis(anno.pingInterval)),
                connectTimeout = Duration.ofMillis(anno.connectTimeoutUnit.toMillis(anno.connectTimeout)),
                readTimeout = Duration.ofMillis(anno.readTimeoutUnit.toMillis(anno.readTimeout)),
            )

            return { conf }
        }

        val candidates = mutableListOf<Method>()

        ReflectionUtils.doWithMethods(clazz) {
            if (it.returnType == WebSocketClientConfiguration::class.java) {
                candidates.add(it)
            }
        }

        if (candidates.isEmpty()) {
            throw IllegalStateException("No WebSocketConfiguration factory found for bean $bean")
        }
        else if (candidates.size > 1) {
            throw IllegalStateException("Multiple WebSocketConfiguration factory found for bean $bean")
        }

        val candidate = candidates.single()

        if (candidate.parameterCount != 0) {
            throw IllegalStateException("WebSocketConfiguration factory must have no parameters")
        }

        candidate.isAccessible = true

        return { candidate.invoke(bean) as fr.rowlaxx.springwebsocketaop.data.WebSocketClientConfiguration }
    }

}