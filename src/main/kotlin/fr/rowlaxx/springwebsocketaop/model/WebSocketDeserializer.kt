package fr.rowlaxx.springwebsocketaop.model

interface WebSocketDeserializer {

    fun deserialize(obj: Any): Any

    object Passthrough : WebSocketDeserializer {
        override fun deserialize(obj: Any): Any = obj
    }
}