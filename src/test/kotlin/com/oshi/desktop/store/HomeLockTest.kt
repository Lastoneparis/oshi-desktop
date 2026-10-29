package com.oshi.desktop.store

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_HOME_LOCK_2026_10_29__ One process per data directory.
 *
 * A same-JVM second `acquire` reports [HomeLock.Outcome.Busy], which is the behaviour a
 * second app instance produces and the one the stores care about — the effect of two writers
 * on an append-only log and a serialized ratchet blob is identical whether they are two
 * processes or two attempts in one.
 *
 * A genuinely separate process is NOT simulated here, and that is said rather than implied:
 * `FileChannel.tryLock` is an OS lock, so the cross-process behaviour is the kernel's and not
 * this code's. What is testable is that this code asks for it, keeps it, releases it, and
 * tells the three outcomes apart.
 */
class HomeLockTest {

    private val dir: File = Files.createTempDirectory("oshi-home-lock").toFile()
    private val held = mutableListOf<HomeLock>()

    @After fun tearDown() { held.forEach { it.close() }; dir.deleteRecursively() }

    private fun acquire(d: File = dir) = HomeLock.acquire(d).also {
        (it as? HomeLock.Outcome.Held)?.let { h -> held.add(h.lock) }
    }

    /**
     * THE BUG THIS SUITE MISSED, and the reason it missed it.
     *
     * Both entry points held the lock as `when (val lock = acquire(home)) { is Held -> Unit }`
     * — which DISCARDS the returned `HomeLock`. An unreachable `FileChannel` is eligible for
     * finalization, finalizing it releases the OS lock, and a second instance then started
     * normally. Verified against two real JVMs: process 2 launched happily on process 1's
     * profile.
     *
     * Every other test here passed throughout, because a test keeps each lock it takes in a
     * list so teardown can close them. **The test retained what the application threw away.**
     * This one reproduces the application's behaviour instead: take the lock, drop the only
     * reference, force collection, and require that it is still held.
     */
    @Test
    fun `the lock survives its reference being dropped`() {
        // Nothing may escape this call — not the HomeLock and not the Outcome that wraps it.
        // Keeping either is what made the first version of this test pass against the bug:
        // `val outcome = acquire(...)` holds the Held, which holds the channel.
        fun takeAndForget(): Boolean = HomeLock.acquire(dir) is HomeLock.Outcome.Held
        assertTrue(takeAndForget())
        // Best effort, which is all the JVM offers — and enough: without the retention this
        // frees the channel and the lock with it.
        repeat(5) { System.gc(); System.runFinalization(); Thread.sleep(20) }

        val second = HomeLock.acquire(dir)
        assertTrue(
            "the lock was released when nothing referenced it — a second instance could start",
            second is HomeLock.Outcome.Busy,
        )
    }

    @Test
    fun `the first caller holds it and a second is refused`() {
        assertTrue(acquire() is HomeLock.Outcome.Held)
        assertTrue("a second instance must be refused", acquire() is HomeLock.Outcome.Busy)
    }

    /**
     * RELEASING MUST ACTUALLY RELEASE. If it did not, a clean restart would be locked out of
     * its own messages — which is the failure mode that makes a pid-file implementation worse
     * than none.
     */
    @Test
    fun `closing the lock lets the next instance start`() {
        val first = acquire() as HomeLock.Outcome.Held
        assertTrue(acquire() is HomeLock.Outcome.Busy)
        first.lock.close()
        held.remove(first.lock)
        assertTrue("a restart after a clean exit must be allowed", acquire() is HomeLock.Outcome.Held)
    }

    /** The lock is per DIRECTORY: two different accounts on one machine must both run. */
    @Test
    fun `a different data directory is independent`() {
        assertTrue(acquire() is HomeLock.Outcome.Held)
        val other = File(dir.parentFile, dir.name + "-other")
        try {
            assertTrue(acquire(other) is HomeLock.Outcome.Held)
        } finally {
            other.deleteRecursively()
        }
    }

    /** The directory is created if it does not exist — a first run has no data dir yet. */
    @Test
    fun `a missing directory is created rather than failing`() {
        val fresh = File(dir, "not-yet/created")
        assertTrue(acquire(fresh) is HomeLock.Outcome.Held)
        assertTrue(File(fresh, HomeLock.FILE_NAME).isFile)
    }

    /**
     * A LEFTOVER LOCK FILE IS NOT A LOCK. The kernel drops the lock when the holder dies, so
     * a file left behind by a crash must not keep the user out. This is the whole reason the
     * implementation is a file LOCK and not a file's existence.
     */
    @Test
    fun `a leftover lock file from a dead process does not block startup`() {
        File(dir, HomeLock.FILE_NAME).writeText("stale", Charsets.UTF_8)
        assertTrue(
            "the presence of the file must not be mistaken for a held lock",
            acquire() is HomeLock.Outcome.Held,
        )
    }
}
