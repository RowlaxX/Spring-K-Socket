package fr.rowlaxx.springksocket.core

import java.util.*

class MessageDeduplicator {
    private val receivedTxt = TreeMap<String, Long>()
    private val receivedBin = TreeMap<Long, Long>()

    @Synchronized
    fun reset() {
        receivedTxt.clear()
        receivedBin.clear()
    }

    @Synchronized
    fun accept(msg: Any, receiver: Long): Boolean {
        return when (msg) {
            is String -> {
                val old = receivedTxt.putIfAbsent(msg, receiver)
                old == receiver
            }
            is ByteArray -> {
                val enc = msg.size.toLong().rotateLeft(32) + msg.hashCode()
                val old = receivedBin.putIfAbsent(enc, receiver)
                old == receiver
            }
            else -> false
        }
    }

}