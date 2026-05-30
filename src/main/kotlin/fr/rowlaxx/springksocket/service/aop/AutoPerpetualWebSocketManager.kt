package fr.rowlaxx.springksocket.service.aop

import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springkutils.reflection.utils.ReflectionUtils
import org.springframework.stereotype.Service
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

@Service
class AutoPerpetualWebSocketManager {

    private val map = ConcurrentHashMap<KClass<*>, List<Field>>()

    private fun find(bean: Any): List<Field> {
        val result = mutableListOf<Field>()

        ReflectionUtils.findFieldsWithType(bean, PerpetualWebSocket::class.java).onEach {
            if (!Modifier.isFinal(it.modifiers)) {
                it.isAccessible = true
                result.add(it)
            }
            else {
                throw IllegalArgumentException("Please make field '${it.name}' in class ${bean.javaClass.simpleName} mutable")
            }
        }

        return result
    }

    fun initializeIfNotDone(bean: Any) {
        map.computeIfAbsent(bean::class) { find(bean) }
    }

    fun set(bean: Any, webSocket: PerpetualWebSocket) {
        map[bean::class]!!.forEach { it.set(bean, webSocket) }
    }

}