package fr.rowlaxx.springksocket.service.aop

import fr.rowlaxx.springksocket.core.AutoPerpetualWebSocket
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import org.springframework.stereotype.Service
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

@Service
class AutoPerpetualWebSocketManager {

    private val map = ConcurrentHashMap<KClass<*>, List<AutoPerpetualWebSocket>>()

    private fun find(bean: Any): List<AutoPerpetualWebSocket> {
        val result = mutableListOf<AutoPerpetualWebSocket>()

        ReflectionUtils.doWithFields(bean::class.java) {
            if (it.type == AutoPerpetualWebSocket::class.java) {
                if (Modifier.isFinal(it.modifiers)) {
                    it.isAccessible = true
                    result.add(it.get(bean) as AutoPerpetualWebSocket)
                }
                else {
                    throw IllegalArgumentException("Please make field '${it.name}' in class ${bean.javaClass.simpleName} immutable")
                }
            }
        }

        return result
    }

    fun initializeIfNotDone(bean: Any) {
        map.computeIfAbsent(bean::class) { find(bean) }
    }

    fun set(bean: Any, webSocket: PerpetualWebSocket) {
        map[bean::class]!!.forEach { it.set(webSocket) }
    }

}