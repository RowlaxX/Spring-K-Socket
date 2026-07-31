package fr.rowlaxx.springksocket.core

import fr.rowlaxx.springkutils.collection.map.MutableLongObjectArrayMap

/**
 * Ensures exactly-once delivery while two (or more) physical connections receive the same stream
 * during a perpetual shift.
 *
 * Semantics of [accept]:
 * - first delivery of a message -> accepted (`true`), and the delivering connection is recorded as
 *   the owner of that message;
 * - the same message from a *different* connection within the retention window -> rejected (`false`);
 * - the same message again from the *owner* connection (a genuine repeat in the stream) -> accepted.
 *
 * Entries are stored in time buckets of [BUCKET_WIDTH_MS]; [clear] prunes buckets older than
 * [MAX_BUCKET_AGE] buckets and [reset] drops everything. Not thread-safe: the perpetual layer calls
 * it from a single-consumer task queue.
 */
class MessageDeduplicator(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Bucket {
        val txt = HashMap<String, Long>()
        val bin = HashMap<Long, Long>()
    }

    private val buckets = MutableLongObjectArrayMap<Bucket>(8)

    private fun currentBucketKey(): Long = clock() / BUCKET_WIDTH_MS

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
                val owner = findOwner { it.txt[msg] }
                if (owner != null) return owner == receiver
                buckets.getOrPut(currentBucketKey()) { Bucket() }.txt[msg] = receiver
                true
            }
            is ByteArray -> {
                val enc = msg.size.toLong().rotateLeft(32) + msg.contentHashCode()
                val owner = findOwner { it.bin[enc] }
                if (owner != null) return owner == receiver
                buckets.getOrPut(currentBucketKey()) { Bucket() }.bin[enc] = receiver
                true
            }
            // Payload types we cannot key on: deliver rather than drop (a duplicate during the
            // short overlap window is preferable to data loss).
            else -> true
        }
    }

    /**
     * Scans the stored entries (a message is recorded in at most one bucket, so order does not
     * matter). Iterates the live buckets, never the key range, which may be huge if the clock
     * jumped between insertions.
     */
    private inline fun findOwner(crossinline lookup: (Bucket) -> Long?): Long? {
        var owner: Long? = null
        buckets.forEach { _, bucket ->
            if (owner == null) owner = lookup(bucket)
        }
        return owner
    }

    private companion object {
        const val BUCKET_WIDTH_MS = 5000L
        const val MAX_BUCKET_AGE = 3
    }

}
