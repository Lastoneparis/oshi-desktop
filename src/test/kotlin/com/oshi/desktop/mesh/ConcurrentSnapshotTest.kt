package com.oshi.desktop.mesh

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard for [concurrentSnapshot].
 *
 * The bug it exists to stop is a RACE, and a test that has to win a race to fail is a test
 * that passes by luck on a quiet machine. So the race is not raced here — it is STAGED.
 *
 * A `ConcurrentHashMap` view caught mid-removal is, for the one instant that matters, a
 * collection whose `size` says 1 and whose iterator is empty. [LiesAboutSize] IS that
 * instant, held still. Any snapshot that trusts `size` and then calls `iterator().next()`
 * throws on it, every single run, on every machine.
 */
class ConcurrentSnapshotTest {

    /**
     * `size` says one; the iterator has nothing. Exactly the window between
     * `ConcurrentHashMap.size` and `ValueIterator.next()` when the last entry is removed
     * by another thread.
     */
    private class LiesAboutSize<T> : AbstractCollection<T>() {
        override val size: Int get() = 1
        override fun iterator(): Iterator<T> = emptyList<T>().iterator()
    }

    @Test
    fun `a collection whose size outruns its iterator does not throw`() {
        // Kotlin's toList() takes its `1 ->` branch here and throws NoSuchElementException.
        // This is the assertion that fails if anyone puts toList() back.
        assertEquals(emptyList<String>(), LiesAboutSize<String>().concurrentSnapshot())
    }

    /** The same trap one element further along: size over-reports on a non-empty view. */
    @Test
    fun `a snapshot never contains more elements than the iterator yielded`() {
        val over = object : AbstractCollection<String>() {
            override val size: Int get() = 5
            override fun iterator(): Iterator<String> = listOf("a", "b").iterator()
        }
        assertEquals(listOf("a", "b"), over.concurrentSnapshot())
    }

    /**
     * And the real thing, as a belt-and-braces smoke test: snapshot a map that is being
     * emptied underneath us. This one CAN pass by luck, which is why it is not the guard —
     * it is here to show the fix holds against the actual collection, not just the stand-in.
     */
    @Test
    fun `snapshotting a map that is being emptied never throws`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val map = ConcurrentHashMap<String, String>()
                map["only"] = "value"
                val go = CountDownLatch(1)
                val remover = pool.submit { go.await(); map.remove("only") }
                val snapshotter = pool.submit<List<String>> { go.await(); map.values.concurrentSnapshot() }
                go.countDown()
                remover.get(5, TimeUnit.SECONDS)
                // The only two legal answers are "I saw it" and "I did not". Never a throw.
                assertTrue(snapshotter.get(5, TimeUnit.SECONDS).size <= 1)
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
