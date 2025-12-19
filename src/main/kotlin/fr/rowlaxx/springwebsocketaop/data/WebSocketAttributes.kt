package fr.rowlaxx.springwebsocketaop.data

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class WebSocketAttributes {
    private val attributes = ConcurrentHashMap<KClass<*>, Any>()

    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: WebSocketAttribute<T>): T? = attributes[key::class] as? T

    operator fun <T> set(key: WebSocketAttribute<T>, value: T) {
        attributes[key::class] = value as Any
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> computeIfAbsent(key: WebSocketAttribute<T>, supplier: () -> T): T = attributes.computeIfAbsent(key::class) { supplier() as Any } as T

}