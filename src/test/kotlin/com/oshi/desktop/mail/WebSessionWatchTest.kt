package com.oshi.desktop.mail

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_WEB_SESSION_WATCH_2026_10_29__ The browser-session watch.
 *
 * The cases below are the ones iOS's `LinkedDeviceWatch` and Android's test both single out,
 * and they are singled out because nobody is going to reproduce them by hand against a live
 * relay and a second machine: the first run ever, a session that vanishes and returns,
 * several appearing at once, and — the one that matters most — the difference between "we
 * have never looked" and "last time there were none".
 */
class WebSessionWatchTest {

    private val dir: File = Files.createTempDirectory("oshi-wsw").toFile()
    private val baseline = WebSessionWatch.Baseline(File(dir, WebSessionWatch.FILE_NAME))

    @After fun tearDown() { dir.deleteRecursively() }

    /**
     * NULL IS NOT EMPTY. The first check seeds and reports nothing; treating every existing
     * session as new would fire a security alert at every user on upgrade day and teach them
     * to dismiss it, which is worse than not warning at all.
     */
    @Test
    fun `the first check ever seeds and reports nothing`() {
        val outcome = WebSessionWatch.classify(listOf("s1", "s2"), known = null)
        assertEquals(WebSessionWatch.Outcome.Seeded(2), outcome)
        assertTrue(WebSessionWatch.newlyLinked(listOf("s1", "s2"), known = null).isEmpty())
    }

    /**
     * ...AND EMPTY IS NOT NULL. A session appearing after a check that genuinely found none
     * is the single most newsworthy case there is, and conflating the two would silence it.
     */
    @Test
    fun `a session appearing after an empty check is reported`() {
        assertEquals(
            WebSessionWatch.Outcome.Linked(listOf("s1")),
            WebSessionWatch.classify(listOf("s1"), known = emptySet()),
        )
    }

    @Test
    fun `nothing new is Unchanged`() {
        assertEquals(
            WebSessionWatch.Outcome.Unchanged,
            WebSessionWatch.classify(listOf("s1", "s2"), known = setOf("s1", "s2")),
        )
        // A session DISAPPEARING is not news: signing one out is a thing the user did.
        assertEquals(
            WebSessionWatch.Outcome.Unchanged,
            WebSessionWatch.classify(listOf("s1"), known = setOf("s1", "s2")),
        )
    }

    @Test
    fun `several appearing at once are all reported`() {
        assertEquals(
            WebSessionWatch.Outcome.Linked(listOf("s2", "s3")),
            WebSessionWatch.classify(listOf("s1", "s2", "s3"), known = setOf("s1")),
        )
    }

    /**
     * A session that vanishes and RETURNS is reported again — it is a new sign-in, and the
     * second one is as worth knowing about as the first.
     */
    @Test
    fun `a session that vanishes and returns is reported again`() {
        baseline.save(listOf("s1"))
        assertEquals(WebSessionWatch.Outcome.Unchanged, WebSessionWatch.classify(listOf("s1"), baseline.load()))
        baseline.save(emptyList())
        assertEquals(
            WebSessionWatch.Outcome.Linked(listOf("s1")),
            WebSessionWatch.classify(listOf("s1"), baseline.load()),
        )
    }

    // ------------------------------------------------------------------ the baseline

    @Test
    fun `an unwritten baseline is null and not empty`() {
        assertNull("a missing file must mean 'never looked', not 'there were none'", baseline.load())
        baseline.save(emptyList())
        assertEquals("and once written, empty means empty", emptySet<String>(), baseline.load())
    }

    @Test
    fun `the baseline round-trips and survives a new instance`() {
        baseline.save(listOf("s2", "s1"))
        assertEquals(setOf("s1", "s2"), WebSessionWatch.Baseline(File(dir, WebSessionWatch.FILE_NAME)).load())
    }

    /**
     * A CORRUPT baseline reads as null, i.e. it re-seeds. That direction is deliberate: the
     * alternative is reporting every session as new on a damaged file, and the noisy failure
     * is the one that trains people to ignore the alert.
     */
    @Test
    fun `a corrupt baseline re-seeds rather than crying wolf`() {
        File(dir, WebSessionWatch.FILE_NAME).writeText("{ not json", Charsets.UTF_8)
        assertNull(baseline.load())
        assertTrue(
            WebSessionWatch.classify(listOf("s1"), baseline.load()) is WebSessionWatch.Outcome.Seeded,
        )
    }

    @Test
    fun `reset makes the next check seed again`() {
        baseline.save(listOf("s1"))
        baseline.reset()
        assertNull(baseline.load())
    }
}
