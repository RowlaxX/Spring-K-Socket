package fr.rowlaxx.springksocket.util

import org.springframework.aop.support.AopUtils
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method
import kotlin.reflect.KClass

object ReflectionUtils {

    fun findInjectionScheme(instance: Any, method: Method, vararg params: KClass<*>): InjectionScheme {
        val convertAny = mutableSetOf<KClass<*>>()
        val paramOrder = method.parameterTypes
            .map { methodParam ->
                val index = params.indexOfFirst { givenParam ->
                    if (givenParam == Any::class) {
                        convertAny.add(methodParam.kotlin)
                        true
                    }
                    else {
                        methodParam.isAssignableFrom(givenParam.java)
                    }
                }

                if (index == -1) {
                    throw NoSuchElementException("Unable to find a suitable parameter for type ${methodParam.simpleName}")
                }

                index
            }

        if (convertAny.size > 1) {
            throw IllegalArgumentException("Incoherent injectable params : ${convertAny.joinToString()}")
        }

        val convertAnyTo = convertAny.singleOrNull() ?: Any::class

        return InjectionScheme(
            method = method.also { it.isAccessible = true },
            injectedTypes = params.map { if (it == Any::class) convertAnyTo else it },
            paramOrder = paramOrder,
            instance = instance,
        )
    }

    fun canInject(scheme: InjectionScheme, vararg args: Any?): Boolean {
        if (args.size != scheme.injectedTypes.size) {
            return false
        }
        args.zip(scheme.injectedTypes).forEach { (instance, type) ->
            if (instance != null && !type.isInstance(instance)) {
                return false
            }
        }
        return true
    }

    fun inject(scheme: InjectionScheme, vararg args: Any?): Any? {
        if (args.size != scheme.injectedTypes.size) {
            throw IllegalArgumentException("You must pass ${scheme.injectedTypes.size} arguments")
        }
        args.zip(scheme.injectedTypes).forEachIndexed { i, (instance, type) ->
            if (instance != null && !type.isInstance(instance)) {
                throw IllegalArgumentException("Parameter $i must be an instance of ${type.simpleName}. Current type : ${instance::class.java.simpleName}")
            }
        }

        val toInject = scheme.paramOrder.map { args[it] }.toTypedArray()

        return ReflectionUtils.invokeMethod(scheme.method, scheme.instance, *toInject)
    }

    data class InjectionScheme(
        val instance: Any,
        val method: Method,
        val injectedTypes: List<KClass<*>>,
        val paramOrder: List<Int>
    )

}