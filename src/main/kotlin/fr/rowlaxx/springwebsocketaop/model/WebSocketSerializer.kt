package fr.rowlaxx.springwebsocketaop.model

interface WebSocketSerializer {

    fun serialize(obj: Any): Any

    object Passthrough : WebSocketSerializer {
        override fun serialize(obj: Any): Any = obj
    }
}