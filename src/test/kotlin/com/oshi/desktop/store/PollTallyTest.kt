package com.oshi.desktop.store

import com.oshi.desktop.msg.PollPayload
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_POLLS_2026_10_29__ The tally rules — Android's `PollStore.results()` and
 * `record()`, which could not be shared (`Context`, `android.util.Log`, `SealedStringStore`,
 * coroutines) and so had to be re-stated here.
 *
 * Every test below names the DECISION it pins rather than the arithmetic. The arithmetic is
 * obvious; the decisions are where a second implementation silently differs, and three of
 * them are invisible until two devices show different bars for the same poll.
 */
class PollTallyTest {

    private val dir: File = Files.createTempDirectory("oshi-poll-tally").toFile()
    private val file = File(dir, PollTally.FILE_NAME)
    private var tally = PollTally(file)

    @After fun tearDown() { dir.deleteRecursively() }

    private val me = "ME/desktop+key/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa="
    private val alice = "ALICE/key/cccccccccccccccccccccccccccccc="
    private val bob = "BOB/key/dddddddddddddddddddddddddddddd="

    private fun poll(
        multi: Boolean = false,
        anon: Boolean = false,
        options: List<String> = listOf("Pizza", "Sushi", "Either"),
    ) = PollPayload.Poll(
        id = "P-1", question = "Pizza or sushi?", options = options,
        allowsMultipleAnswers = multi, isAnonymous = anon,
        createdAtUnix = 1_770_000_000.0, closesAtUnix = null, creatorPublicKey = me,
    )

    private fun ballot(vararg picks: Int, at: Double) = PollPayload.Ballot(picks.toList(), at)

    // ------------------------------------------------------------------ record

    /**
     * STRICTLY LATER WINS, and an equal stamp keeps what we hold. Android's reason: the mesh
     * redelivers the same vote routinely, and two ballots at one instant from one voter is a
     * duplicate — "flapping between them would make the bars jitter".
     */
    @Test
    fun `a later ballot replaces an earlier one and an equal one does not`() {
        assertTrue(tally.record("P-1", alice, ballot(0, at = 100.0)))
        assertFalse("the same instant is a duplicate", tally.record("P-1", alice, ballot(1, at = 100.0)))
        assertFalse("an older ballot must never win", tally.record("P-1", alice, ballot(1, at = 99.0)))
        assertEquals(listOf(0), tally.ballotsFor("P-1")[PollTally.normalize(alice)]!!.picks)
        assertTrue(tally.record("P-1", alice, ballot(1, at = 100.5)))
        assertEquals(listOf(1), tally.ballotsFor("P-1")[PollTally.normalize(alice)]!!.picks)
    }

    @Test
    fun `an empty poll id or voter is refused`() {
        assertFalse(tally.record("", alice, ballot(0, at = 1.0)))
        assertFalse(tally.record("P-1", "", ballot(0, at = 1.0)))
        assertTrue(tally.ballotsFor("P-1").isEmpty())
    }

    /**
     * THE KEY SPELLING. This client's usual canonical form RE-ADDS base64 padding
     * (`LegacyRatchetEnvelope.canonicalKey`); Android's `PollStore` strips it. Using the
     * padded form here would order voters differently from a phone, because `results` sorts
     * by `(atUnix, key)` precisely so two devices agree — and "AAAA" and "AAAA==" do not
     * sort alike.
     */
    @Test
    fun `the voter key is normalised Android's way, padding stripped`() {
        assertEquals("AAAA", PollTally.normalize("AAAA=="))
        assertEquals("a+b/c", PollTally.normalize("a-b_c"))
        assertEquals("AAAA", PollTally.normalize("  AAAA==  "))
        // Android's `CallAdmissionPolicy.normalizeKey`, character for character.
        val androids = { k: String -> k.trim().replace('-', '+').replace('_', '/').replace("=", "") }
        for (k in listOf(alice, bob, me, "a-b_c==", "  pad==  ", "")) {
            assertEquals("diverged from Android for '$k'", androids(k), PollTally.normalize(k))
        }
        // And the two spellings of one key are ONE voter, not two.
        tally.record("P-1", "AAAA==", ballot(0, at = 1.0))
        tally.record("P-1", "AAAA", ballot(1, at = 2.0))
        assertEquals(1, tally.ballotsFor("P-1").size)
    }

    // ------------------------------------------------------------------ results

    @Test
    fun `counts voters and my own picks`() {
        tally.record("P-1", alice, ballot(0, at = 100.0))
        tally.record("P-1", bob, ballot(1, at = 101.0))
        tally.record("P-1", me, ballot(0, at = 102.0))
        val r = tally.results(poll(), me)
        assertEquals(listOf(2, 1, 0), r.counts)
        assertEquals(3, r.voterCount)
        assertEquals(setOf(0), r.myPicks)
    }

    /**
     * A RETRACTION is skipped entirely and must not raise `voterCount` — it is a real ballot
     * that beat the earlier one, and then counts for nothing.
     */
    @Test
    fun `an empty ballot retracts a vote without counting as a voter`() {
        tally.record("P-1", alice, ballot(0, at = 100.0))
        assertEquals(1, tally.results(poll(), me).voterCount)
        assertTrue(tally.record("P-1", alice, PollPayload.Ballot(emptyList(), 101.0)))
        val r = tally.results(poll(), me)
        assertEquals(listOf(0, 0, 0), r.counts)
        assertEquals("a retraction is not a voter", 0, r.voterCount)
    }

    /**
     * A SINGLE-ANSWER poll keeps the FIRST pick of a ballot that carries several, rather
     * than counting that voter once per pick. A hostile or buggy sender is the case.
     */
    @Test
    fun `a single answer poll keeps one pick from a multi-pick ballot`() {
        tally.record("P-1", alice, ballot(2, 0, at = 100.0))
        val r = tally.results(poll(multi = false), me)
        assertEquals("sorted first, so index 0", listOf(1, 0, 0), r.counts)
        assertEquals(1, r.voterCount)
        // The same ballot in a MULTI poll counts both, and still as ONE voter.
        val m = tally.results(poll(multi = true), me)
        assertEquals(listOf(1, 0, 1), m.counts)
        assertEquals("one person, two picks", 1, m.voterCount)
        assertTrue("voterCount is below the sum of counts in a multi poll", m.voterCount < m.counts.sum())
    }

    /** Out-of-range and duplicate picks cannot inflate a count or index past the options. */
    @Test
    fun `invalid and duplicate picks are filtered`() {
        tally.record("P-1", alice, PollPayload.Ballot(listOf(0, 0, 1, 99, -1), 100.0))
        val r = tally.results(poll(multi = true), me)
        assertEquals(listOf(1, 1, 0), r.counts)
        assertEquals(1, r.voterCount)
    }

    /**
     * ANONYMOUS MEANS NEVER COLLECTED, not withheld at draw time. Asserted on the RESULTS
     * object because that is what a screenshot of the window can contain.
     */
    @Test
    fun `an anonymous poll never collects voter keys`() {
        tally.record("P-1", alice, ballot(0, at = 100.0))
        tally.record("P-1", bob, ballot(0, at = 101.0))
        val anon = tally.results(poll(anon = true), me)
        assertEquals("the tally still works", listOf(2, 0, 0), anon.counts)
        assertEquals(2, anon.voterCount)
        assertTrue("no key may survive into the results", anon.votersByOption.all { it.isEmpty() })
        val named = tally.results(poll(anon = false), me)
        assertEquals(listOf(PollTally.normalize(alice), PollTally.normalize(bob)), named.votersByOption[0])
    }

    /** Voters are ordered by (time, key) so two devices list them identically. */
    @Test
    fun `voter order is deterministic across devices`() {
        tally.record("P-1", bob, ballot(0, at = 100.0))
        tally.record("P-1", alice, ballot(0, at = 100.0))   // same instant, different key
        val first = tally.results(poll(), me).votersByOption[0]
        // A fresh store loading the same file must produce the same order.
        val reloaded = PollTally(file).results(poll(), me).votersByOption[0]
        assertEquals(first, reloaded)
        assertEquals(listOf(PollTally.normalize(alice), PollTally.normalize(bob)).sorted(), first.sorted())
        assertEquals("ties break on the key, ascending", first, first.sortedBy { it })
    }

    /** Never 0 % for an option that really has a voter — "a bar with a body and 0 % on it reads as a bug". */
    @Test
    fun `a tiny share rounds up to one percent rather than zero`() {
        repeat(300) { i -> tally.record("P-1", "voter$i", ballot(0, at = 100.0 + i)) }
        tally.record("P-1", "lonely", ballot(1, at = 500.0))
        val r = tally.results(poll(), me)
        assertEquals(301, r.voterCount)
        assertTrue("1/301 is 0.33 %", r.share(1) < 0.01f)
        assertEquals("clamped up, not down", 1, r.percent(1))
        assertEquals(0, r.percent(2))
        // 300/301 = 99.67 %, which rounds to 100 while another option still holds a voter.
        // Kept as an assertion because it is the honest consequence of rounding each bar
        // independently: the bars can sum past 100 %, and that is both phones' behaviour.
        assertEquals(100, r.percent(0))
        assertTrue("independently rounded bars may exceed 100 %", r.percent(0) + r.percent(1) > 100)
    }

    @Test
    fun `an empty poll has no voters and no percentages`() {
        val r = tally.results(poll(), me)
        assertEquals(0, r.voterCount)
        assertEquals(listOf(0, 0, 0), r.counts)
        assertEquals(0, r.percent(0))
        assertEquals(0f, r.share(0), 0f)
        assertTrue(r.myPicks.isEmpty())
    }

    // ------------------------------------------------------------------ disk

    @Test
    fun `ballots survive a restart`() {
        tally.record("P-1", alice, ballot(1, at = 100.0))
        val fresh = PollTally(file)
        assertEquals(listOf(1), fresh.ballotsFor("P-1")[PollTally.normalize(alice)]!!.picks)
        assertEquals(1, fresh.results(poll(), me).voterCount)
    }

    /** A retraction must survive too — it is the one ballot with nothing in its picks array. */
    @Test
    fun `a persisted retraction is reloaded as a retraction and not as a missing ballot`() {
        tally.record("P-1", alice, ballot(0, at = 100.0))
        tally.record("P-1", alice, PollPayload.Ballot(emptyList(), 101.0))
        val fresh = PollTally(file)
        val reloaded = fresh.ballotsFor("P-1")[PollTally.normalize(alice)]
        assertTrue("the ballot itself must still be there", reloaded != null)
        assertTrue(reloaded!!.picks.isEmpty())
        assertEquals(101.0, reloaded.atUnix, 0.0001)
        // And it must still beat an older ballot after the reload.
        assertFalse(fresh.record("P-1", alice, ballot(2, at = 100.5)))
    }

    @Test
    fun `a corrupt file reads as no ballots and still accepts a new one`() {
        file.writeText("{ not json", Charsets.UTF_8)
        val fresh = PollTally(file)
        assertTrue(fresh.ballotsFor("P-1").isEmpty())
        assertTrue(fresh.record("P-1", alice, ballot(0, at = 1.0)))
    }

    @Test
    fun `forget drops one poll and leaves the others`() {
        tally.record("P-1", alice, ballot(0, at = 1.0))
        tally.record("P-2", alice, ballot(0, at = 1.0))
        tally.forget("P-1")
        assertTrue(tally.ballotsFor("P-1").isEmpty())
        assertEquals(1, tally.ballotsFor("P-2").size)
    }
}
