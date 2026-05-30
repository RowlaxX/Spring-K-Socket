package fr.rowlaxx.springksocket.model

import kotlinx.coroutines.Job

interface WebSocketMessageSender {

    fun sendMessageAsync(message: Any): Job

}