package fr.rowlaxx.springksocket.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration

/**
 * Unit tests for [MessageDeduplicator]. The deduplicator backs the perpetual layer's exactly-once
 * guarantee during a connection shift: while two physical connections receive the same stream, the
 * first delivery of a message must be accepted (and its connection recorded as owner), the same
 * message from the *other* connection must be rejected, and a genuine repeat in the stream (same
 * owner connection) must be accepted again.
 *
 * The clock is injected so bucket aging can be tested without sleeping.
 */
@Timeout(30)
class MessageDeduplicatorTest {

    /** Bucket constants mirrored from MessageDeduplicator (private there). */
    private companion object {
        const val BUCKET_WIDTH_MS = 5000L
        const val MAX_BUCKET_AGE = 3
    }

    private var now = 0L
    private fun dedup() = MessageDeduplicator { now }

    // ---------------------------------------------------------------- H1: text semantics

    @Test
    fun `first delivery of a text message is accepted`() {
        val d = dedup()
        assertTrue(d.accept("m1", 1L), "first-seen message must be delivered, not swallowed")
    }

    @Test
    fun `same text message from another connection is rejected`() {
        val d = dedup()
        assertTrue(d.accept("m1", 1L))
        assertFalse(d.accept("m1", 2L), "duplicate copy from the other connection must be rejected")
    }

    @Test
    fun `genuine repeat from the owner connection is accepted`() {
        val d = dedup()
        assertTrue(d.accept("m1", 1L))
        assertTrue(d.accept("m1", 1L), "a repeat in the owner's own stream is a real message")
        assertFalse(d.accept("m1", 2L), "the other connection's copies stay rejected")
        assertTrue(d.accept("m1", 1L))
    }

    @Test
    fun `two overlapped connections deliver a stream with repeats exactly once per occurrence`() {
        val d = dedup()
        // The upstream stream contains x, y, x (a genuine repeat of x). Both connections receive it.
        val stream = listOf("x", "y", "x")
        var delivered = 0
        for (occurrence in stream) {
            if (d.accept(occurrence, 1L)) delivered++
            if (d.accept(occurrence, 2L)) delivered++
        }
        assertEquals(stream.size, delivered, "handler must see each stream occurrence exactly once")
    }

    @Test
    fun `ownership belongs to whichever connection delivered first`() {
        val d = dedup()
        assertTrue(d.accept("m1", 2L), "connection 2 delivered first, so it is the owner")
        assertFalse(d.accept("m1", 1L))
        assertTrue(d.accept("m1", 2L))
    }

    // ---------------------------------------------------------------- H2: binary semantics

    @Test
    fun `first delivery of a binary message is accepted`() {
        val d = dedup()
        assertTrue(d.accept(byteArrayOf(1, 2, 3), 1L))
    }

    @Test
    fun `binary duplicate with equal content from another connection is rejected`() {
        val d = dedup()
        // Distinct ByteArray instances with identical content, as two sockets would produce.
        assertTrue(d.accept(byteArrayOf(1, 2, 3), 1L))
        assertFalse(d.accept(byteArrayOf(1, 2, 3), 2L), "content-equal binary duplicate must match across instances")
    }

    @Test
    fun `binary repeat from the owner connection is accepted`() {
        val d = dedup()
        assertTrue(d.accept(byteArrayOf(9, 9), 1L))
        assertTrue(d.accept(byteArrayOf(9, 9), 1L))
        assertFalse(d.accept(byteArrayOf(9, 9), 2L))
    }

    @Test
    fun `binary messages with different content are independent`() {
        val d = dedup()
        assertTrue(d.accept(byteArrayOf(1, 2, 3), 1L))
        assertTrue(d.accept(byteArrayOf(1, 2, 3, 4), 2L), "a different message is not a duplicate")
    }

    // ---------------------------------------------------------------- aging / retention

    @Test
    fun `entry still within retention is remembered after clear`() {
        val d = dedup()
        assertTrue(d.accept("m1", 1L))
        now += 2 * BUCKET_WIDTH_MS // 2 buckets old, below MAX_BUCKET_AGE
        d.clear()
        assertFalse(d.accept("m1", 2L), "entry younger than the retention window must survive clear()")
        assertTrue(d.accept("m1", 1L))
    }

    @Test
    fun `entry older than the retention window is pruned by clear`() {
        val d = dedup()
        assertTrue(d.accept("m1", 1L))
        now += (MAX_BUCKET_AGE + 1) * BUCKET_WIDTH_MS
        d.clear()
        assertTrue(d.accept("m1", 2L), "after pruning, the message is first-seen again")
    }

    @Test
    fun `reset empties all state`() {
        val d = dedup()
        assertTrue(d.accept("m1", 1L))
        assertTrue(d.accept(byteArrayOf(5), 1L))
        d.reset()
        assertTrue(d.accept("m1", 2L))
        assertTrue(d.accept(byteArrayOf(5), 2L))
    }

    // ---------------------------------------------------------------- sparse bucket keys

    @Test
    fun `lookup stays fast when bucket keys are far apart and clear has not run`() {
        val d = dedup()
        assertTrue(d.accept("old", 1L))
        // Jump the clock far ahead (~1e12 buckets) WITHOUT calling clear(), then store a new message
        // so the map holds two entries with a huge key gap. Lookups must iterate entries, not the
        // whole key range.
        now += 1_000_000_000_000L * BUCKET_WIDTH_MS
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            assertTrue(d.accept("new", 1L))
            assertFalse(d.accept("new", 2L))
            assertFalse(d.accept("old", 2L), "the old entry is still retained until clear() prunes it")
        }
    }
}
