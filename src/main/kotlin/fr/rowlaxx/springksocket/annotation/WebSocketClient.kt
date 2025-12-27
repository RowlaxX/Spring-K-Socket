package fr.rowlaxx.springksocket.annotation

import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import org.intellij.lang.annotations.Language
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Component
annotation class WebSocketClient(

    val name: String = "",
    val url: String,
    val headers: Array<Header> = [],

    val initializers: Array<KClass<*>> = [],
    val defaultSerializer: KClass<out WebSocketSerializer> = WebSocketSerializer.Passthrough::class,
    val defaultDeserializer: KClass<out WebSocketDeserializer> = WebSocketDeserializer.Passthrough::class,

    val connectTimeout: String = "PT5S",
    val initTimeout: String = "PT10S",
    val pingAfter: String = "PT5S",
    val readTimeout: String = "PT10S",

    val shiftDuration: String = "PT4H",
    val switchDuration: String = "PT3S"

) {
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Header(
        val name: String,

        @Language("SpEL")
        val expression: String,

    )
}
