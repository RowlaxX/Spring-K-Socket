package fr.rowlaxx.springksocket.model

import kotlinx.coroutines.Deferred

interface WebSocketMessageSender {

    fun sendMessageAsync(message: Any): Deferred<Unit>

}