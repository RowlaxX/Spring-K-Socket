package fr.rowlaxx.springwebsocketaop.annotation

import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Component
annotation class WebSocketClient(

    val name: String = "",
    val initializer: KClass<*> = Unit::class,

    val replaceDuration: Long = 3,
    val replaceDurationUnit: TimeUnit = TimeUnit.SECONDS,

    val shiftDuration: Long = 4,
    val shiftDurationUnit: TimeUnit = TimeUnit.HOURS

)
