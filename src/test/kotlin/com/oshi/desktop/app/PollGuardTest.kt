package com.oshi.desktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The guard that keeps inbound delivery alive — see [PollGuard]'s own note for the bug.
 *
 * The first test is the one that matters and the one the old code failed: an `Error` is
 * not an `Exception`, and `catch (e: Exception)` let it through. The second proves the
 * consequence at the level a user feels it, by running a REAL `scheduleWithFixedDelay` and
 * showing that an unguarded body stops for ever after one throw while a guarded one keeps
 * ticking. That second test is deliberately not about our code at all — it pins the
 * `ScheduledExecutorService` contract this whole design rests on, so that nobody has to
 * take it on faith when they read the guard.
 */
class PollGuardTest {

    @Test
    fun `an Error does not escape, because an Error is not an Exception`() {
        val logged = ArrayList<String>()
        val ok = PollGuard.run(logged::add) { throw OutOfMemoryError("attachment") }

        assertFalse("the guard must report the failure", ok)
        assertEquals(1, logged.size)
        assertTrue(
            "the log has to name what happened, or a dead poll has no diagnosis: ${logged[0]}",
            logged[0].contains("OutOfMemoryError") && logged[0].contains("attachment"),
        )
    }

    @Test
    fun `an ordinary exception is swallowed and named too`() {
        val logged = ArrayList<String>()
        assertFalse(PollGuard.run(logged::add) { throw IllegalStateException("no route") })
        assertTrue(logged.single().contains("IllegalStateException"))
    }

    @Test
    fun `a body that succeeds reports success and logs nothing`() {
        val logged = ArrayList<String>()
        var ran = false
        assertTrue(PollGuard.run(logged::add) { ran = true })
        assertTrue(ran)
        assertTrue("a good tick must be silent, or the log is useless", logged.isEmpty())
    }

    /**
     * A log implementation that itself throws must not resurrect the failure.
     *
     * Under an `OutOfMemoryError` the logger is exactly the code most likely to fail next,
     * and a guard that dies inside its own error handler is no guard at all.
     */
    @Test
    fun `a logger that throws cannot kill the guard`() {
        assertFalse(PollGuard.run({ throw OutOfMemoryError("no headroom to log") }) {
            throw OutOfMemoryError("the original")
        })
    }

    /**
     * THE CONTRACT THE WHOLE DESIGN RESTS ON, pinned rather than assumed.
     *
     * `scheduleWithFixedDelay` stops for ever when its task throws — no retry, no log, and
     * nothing observes the `Future`. This runs both shapes side by side at 20 ms and shows
     * the difference a user would experience as "messages just stopped arriving".
     */
    @Test
    fun `an unguarded scheduled body stops for ever, a guarded one keeps ticking`() {
        val exec = Executors.newScheduledThreadPool(2)
        try {
            val unguarded = AtomicInteger()
            exec.scheduleWithFixedDelay({
                unguarded.incrementAndGet()
                throw OutOfMemoryError("kills the schedule")
            }, 0, 20, TimeUnit.MILLISECONDS)

            val guarded = AtomicInteger()
            val enough = CountDownLatch(4)
            exec.scheduleWithFixedDelay({
                PollGuard.run({}) {
                    guarded.incrementAndGet()
                    enough.countDown()
                    throw OutOfMemoryError("does not kill the schedule")
                }
            }, 0, 20, TimeUnit.MILLISECONDS)

            assertTrue(
                "the guarded body should have ticked at least four times",
                enough.await(5, TimeUnit.SECONDS),
            )
            assertEquals(
                "an unguarded body runs exactly once, ever — that is the bug this guards",
                1, unguarded.get(),
            )
        } finally {
            exec.shutdownNow()
        }
    }
}
