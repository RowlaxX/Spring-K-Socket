package fr.rowlaxx.springksocket.conf

import fr.rowlaxx.springksocket.annotation.WebSocketServer
import fr.rowlaxx.springksocket.data.WebSocketServerProperties
import fr.rowlaxx.springksocket.core.SpringWebSocketHandler
import fr.rowlaxx.springksocket.service.aop.HandshakeInterceptorFactory
import fr.rowlaxx.springksocket.service.aop.WebSocketHandlerFactory
import fr.rowlaxx.springksocket.service.aop.WebSocketSerializerDeserializerExtractor
import fr.rowlaxx.springksocket.service.io.ServerWebSocketFactory
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.getBeansWithAnnotation
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import java.time.Duration

@Configuration
@EnableWebSocket
class WebSocketServerConfiguration(
    private val applicationContext: ApplicationContext,
    private val webSocketFactory: ServerWebSocketFactory,
    private val handlerFactory: WebSocketHandlerFactory,
    private val handshakeInterceptorFactory: HandshakeInterceptorFactory,
    private val serDesExtractor: WebSocketSerializerDeserializerExtractor
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        val beans = applicationContext.getBeansWithAnnotation<WebSocketServer>().values

        for (bean in beans) {
            val type = AopUtils.getTargetClass(bean)
            val anno = type.getAnnotation(WebSocketServer::class.java) ?: continue

            if (anno.paths.isEmpty()) {
                throw IllegalArgumentException("@ServerWebSocket must have at least one path : ${bean::class.java}")
            }

            val name = anno.name.ifEmpty { type.simpleName }
            val handlerChain = anno.initializers
                .map { applicationContext.getBean(it.java) }
                .plus(bean)
                .map {
                    val (ser, des) = serDesExtractor.extract(anno, bean)
                    handlerFactory.extract(it, ser, des)
                }

            val handshakeInterceptor = handshakeInterceptorFactory.extract(handlerChain.first())
            val config = WebSocketServerProperties(
                name = name,
                handlerChain = handlerChain,
                initTimeout = Duration.parse(anno.initTimeout),
                readTimeout = Duration.parse(anno.readTimeout),
                pingAfter = Duration.parse(anno.pingAfter),
            )

            val handler = SpringWebSocketHandler(
                config = config,
                serverWebSocketFactory = webSocketFactory
            )

            registry.addHandler(handler, *anno.paths)
                .apply { if (anno.withSockJS) withSockJS() }
                .apply { if (anno.allowedOriginPatterns.isNotEmpty()) setAllowedOriginPatterns(*anno.allowedOriginPatterns) }
                .apply { addInterceptors(handshakeInterceptor) }
        }


        if (beans.isNotEmpty()) {
            log.info("Found ${beans.size} WebSocket server(s)")
        }
    }

}