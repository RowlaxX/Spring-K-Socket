package fr.rowlaxx.springksocket.core

import fr.rowlaxx.springkutils.array.MutableLongObjectEntangledArray

class MessageDeduplicator {

    private class Bucket {
        val txt = HashMap<String, Long>()
        val bin = HashMap<Long, Long>()
    }

    private val buckets = MutableLongObjectEntangledArray<Bucket>(8)

    private fun currentBucketKey(): Long = System.currentTimeMillis() / BUCKET_WIDTH_MS

    fun reset() {
        buckets.clear()
    }

    fun clear() {
        val threshold = currentBucketKey() - MAX_BUCKET_AGE
        buckets.removeIf { key, _ -> key < threshold }
    }

    fun accept(msg: Any, receiver: Long): Boolean {
        return when (msg) {
            is String -> {
                if (buckets.isNotEmpty) {
                    val first = buckets.firstKey
                    var k = buckets.lastKey
                    while (k >= first) {
                        val old = buckets[k]?.txt?.get(msg)
                        if (old != null) return old == receiver
                        k--
                    }
                }
                buckets.getOrPut(currentBucketKey()) { Bucket() }.txt[msg] = receiver
                false
            }
            is ByteArray -> {
                val enc = msg.size.toLong().rotateLeft(32) + msg.hashCode()
                if (buckets.isNotEmpty) {
                    val first = buckets.firstKey
                    var k = buckets.lastKey
                    while (k >= first) {
                        val old = buckets[k]?.bin?.get(enc)
                        if (old != null) return old == receiver
                        k--
                    }
                }
                buckets.getOrPut(currentBucketKey()) { Bucket() }.bin[enc] = receiver
                false
            }
            else -> false
        }
    }

    private companion object {
        const val BUCKET_WIDTH_MS = 5000L
        const val MAX_BUCKET_AGE = 3
    }

}
