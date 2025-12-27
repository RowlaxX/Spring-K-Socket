package fr.rowlaxx.springksocket.annotation

import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Component
annotation class WebSocketServer(

    val name: String = "",
    val paths: Array<String>,

    val initializers: Array<KClass<*>> = [],
    val defaultSerializer: KClass<out WebSocketSerializer> = WebSocketSerializer.Passthrough::class,
    val defaultDeserializer: KClass<out WebSocketDeserializer> = WebSocketDeserializer.Passthrough::class,

    val withSockJS: Boolean = false,
    val allowedOriginPatterns: Array<String> = [],

    val initTimeout: String = "PT10S",
    val pingAfter: String = "PT5S",
    val readTimeout: String = "PT10S"

)