package fr.rowlaxx.marketdata.lib.websocket.service.aop

import fr.rowlaxx.springwebsocketaop.annotation.WebSocketClient
import fr.rowlaxx.springwebsocketaop.event.OnWebSocketsInitialized
import fr.rowlaxx.springwebsocketaop.model.PerpetualWebSocket
import fr.rowlaxx.springwebsocketaop.data.WebSocketClientConfiguration
import fr.rowlaxx.marketdata.lib.websocket.service.io.PerpetualWebSocketFactoryService
import jakarta.annotation.PostConstruct
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.getBeansWithAnnotation
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.*

@Service
@AutoConfigureAfter
class WebSocketBeanFactoryService(
    private val applicationContext: ApplicationContext,
    private val configurationFactoryHelper: WebSocketBeanConfigurationFactoryHelper,
    private val initializerHelper: WebSocketBeanInitializerHelper,
    private val handlerHelper: WebSocketBeanHandlerHelper,
    private val perpetualWebSocketFactoryService: PerpetualWebSocketFactoryService,
    private val applicationEventPublisher: ApplicationEventPublisher
) : SmartInitializingSingleton {
    private val temp: MutableList<Pair<Any, () -> PerpetualWebSocket>> = LinkedList()

    @PostConstruct
    private fun init() {
        val beans = applicationContext.getBeansWithAnnotation<WebSocketClient>().values

        for (bean in beans) {
            val name = bean::class.simpleName!!
            val anno = AopUtils.getTargetClass(bean).getAnnotation(WebSocketClient::class.java)!!
            val shiftDuration = Duration.ofMillis(anno.shiftDurationUnit.toMillis(anno.shiftDuration))
            val replaceDuration = Duration.ofMillis(anno.replaceDurationUnit.toMillis(anno.replaceDuration))
            val handler = handlerHelper.extractHandler(bean)
            var configuration: () -> WebSocketClientConfiguration
            var initTimeout: Duration? = null
            var initializer: WebSocketInitializerHandler? = null

            if (anno.initializer != Unit::class) {
                val initBean = applicationContext.getBean(anno.initializer.java)
                configuration = configurationFactoryHelper.extractConfigurationFactory(initBean)
                val pair = initializerHelper.extractInitializer(initBean)
                initializer = pair.first
                initTimeout = pair.second
            }
            else {
                configuration = configurationFactoryHelper.extractConfigurationFactory(bean)
            }

            temp.add(bean to {
                perpetualWebSocketFactoryService.create(
                    name = name,
                    configurationFactory = configuration,
                    handler = handler,
                    initializer = initializer,
                    initTimeout = initTimeout,
                    shiftDuration = shiftDuration,
                    replaceDuration = replaceDuration
                )
            })
        }
    }

    override fun afterSingletonsInstantiated() {
        val result = temp.map { it.first to it.second() }

        if (result.isNotEmpty()) {
            temp.clear()
            applicationEventPublisher.publishEvent(OnWebSocketsInitialized(result))
        }
    }

}