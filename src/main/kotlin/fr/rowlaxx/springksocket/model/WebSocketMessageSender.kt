package fr.rowlaxx.springksocket.model

import java.util.concurrent.CompletableFuture

interface WebSocketMessageSender {

    fun sendMessageAsync(message: Any): CompletableFuture<Unit>

}