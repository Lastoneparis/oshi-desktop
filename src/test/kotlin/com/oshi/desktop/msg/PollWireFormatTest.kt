package com.oshi.desktop.msg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_POLLS_2026_10_29__ The poll wire format, pinned to payloads written by hand from
 * the two shipped sources.
 *
 * [PollPayload] is a THIRD implementation — Android's `data/model/PollMessage.kt` could not
 * be compiled here because its wire entry points reach `OSHIControlPayload`, which is 954
 * lines of `android.content.Context` and `com.oshi.messenger.R`. This file is the mitigation
 * this repository has settled on for that situation, and the form of it is deliberate:
 *
 * **The vectors are LITERAL JSON, not round trips.** `encode(decode(x)) == x` passes just as
 * happily with every key renamed, as long as both halves are renamed together — that is the
 * whole failure mode of a third implementation and a round-trip test cannot see it. These
 * payloads are the bytes a phone puts on the wire; nothing in this file is produced by the
 * code under test.
 *
 * Sources for every key and value below, read rather than remembered:
 *   - `OSHI/PollMessage.swift:61-71` — the `CodingKeys`: v, id, q, opts, multi, anon, at,
 *     closes, by; and `:204-210` for a vote: v, poll, picks, at.
 *   - `OSHI-Android/.../data/model/PollMessage.kt:61-75,155-170` — the same keys, written as
 *     `JSONObject` puts. The two agreed already; this client now agrees with both.
 */
class PollWireFormatTest {

    /** A poll exactly as an iPhone emits one. Hand-written. */
    private val iosPoll =
        "📊POLL📊{\"v\":1,\"id\":\"3F2A9C10-1B2C-4D5E-8F90-ABCDEF012345\"," +
            "\"q\":\"Pizza or sushi?\",\"opts\":[\"Pizza\",\"Sushi\",\"Either\"]," +
            "\"multi\":false,\"anon\":false,\"at\":1770000000.5," +
            "\"by\":\"PEER/phone+key/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb=\"}"

    @Test
    fun `an iPhone's poll decodes field for field`() {
        val p = PollPayload.decodePoll(iosPoll)!!
        assertEquals("3F2A9C10-1B2C-4D5E-8F90-ABCDEF012345", p.id)
        assertEquals("Pizza or sushi?", p.question)
        assertEquals(listOf("Pizza", "Sushi", "Either"), p.options)
        assertFalse(p.allowsMultipleAnswers)
        assertFalse(p.isAnonymous)
        assertEquals(1770000000.5, p.createdAtUnix, 0.0001)
        assertEquals("PEER/phone+key/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb=", p.creatorPublicKey)
        assertEquals(1, p.version)
    }

    /**
     * THE EPOCH. `at` is seconds since 1970, not since 2001 — iOS carries a comment naming
     * the 978 307 200 s gap because a Swift `Date` through a default `JSONEncoder` would
     * have been the other one. Being wrong here is invisible until a poll's close time is
     * compared, so it is asserted as a DATE and not as a number copied from the line above.
     */
    @Test
    fun `the timestamp is unix seconds and not apple reference seconds`() {
        val p = PollPayload.decodePoll(iosPoll)!!
        // 1770000000 s after 1970-01-01 is 2026-02-02T02:40:00Z.
        val asInstant = java.time.Instant.ofEpochMilli((p.createdAtUnix * 1000).toLong())
        assertEquals(2026, asInstant.atZone(java.time.ZoneOffset.UTC).year)
        // Had this been read as Apple-reference seconds it would land in 2026+31 = 2057.
        assertTrue("an Apple-epoch reading would put this past 2050", asInstant.epochSecond < 2_524_608_000L)
    }

    /** An absent `closes` is an OPEN poll, not one that closed at the epoch. */
    @Test
    fun `an absent closes key is an open-ended poll`() {
        val p = PollPayload.decodePoll(iosPoll)!!
        assertNull(p.closesAtUnix)
        assertFalse("optDouble's own default would have closed it in 1970", p.isClosed(1_770_000_100.0))
    }

    @Test
    fun `a closing poll stops taking ballots but still shows`() {
        val closing = iosPoll.replace("\"at\":1770000000.5", "\"at\":1770000000.5,\"closes\":1770000600.0")
        val p = PollPayload.decodePoll(closing)!!
        assertEquals(1770000600.0, p.closesAtUnix!!, 0.0001)
        assertFalse(p.isClosed(1_770_000_599.0))
        assertTrue("the boundary is <=, as iOS has it", p.isClosed(1_770_000_600.0))
    }

    /** A vote as a phone emits one — and it carries NO voter field, by design. */
    @Test
    fun `a vote decodes and names no voter`() {
        val wire = "📊POLL_VOTE📊{\"v\":1,\"poll\":\"P-1\",\"picks\":[0,2],\"at\":1770000100.0}"
        val v = PollPayload.decodeVote(wire)!!
        assertEquals("P-1", v.pollId)
        assertEquals(listOf(0, 2), v.picks)
        assertEquals(1770000100.0, v.atUnix, 0.0001)
        val body = JSONObject(ControlPrefix.strip(wire))
        for (forbidden in listOf("voter", "by", "sender", "senderPublicKey", "from")) {
            assertFalse(
                "a voter field on the wire would let anyone vote as anyone",
                body.has(forbidden),
            )
        }
    }

    /** Empty picks is a RETRACTION — a real ballot, which is why it decodes rather than failing. */
    @Test
    fun `an empty picks array is a retraction and not a malformed vote`() {
        val wire = "📊POLL_VOTE📊{\"v\":1,\"poll\":\"P-1\",\"picks\":[],\"at\":1770000200.0}"
        val v = PollPayload.decodeVote(wire)!!
        assertTrue(v.picks.isEmpty())
        assertEquals("P-1", v.pollId)
    }

    /**
     * What this client EMITS, checked key by key rather than as one string: a JSON object is
     * unordered, so asserting the whole serialisation would pin Kotlin's `JSONObject`
     * iteration order and break on a library bump without anything being wrong.
     */
    @Test
    fun `what we emit carries exactly the phones' keys`() {
        val poll = PollPayload.Poll(
            id = "P-9", question = "Tea?", options = listOf("Yes", "No"),
            allowsMultipleAnswers = true, isAnonymous = true,
            createdAtUnix = 1770000000.0, closesAtUnix = null, creatorPublicKey = "ME=",
        )
        val wire = poll.encode()
        assertTrue(wire.startsWith(ControlPrefix.POLL))
        val o = JSONObject(ControlPrefix.strip(wire))
        assertEquals(setOf("v", "id", "q", "opts", "multi", "anon", "at", "by"), o.keys().asSequence().toSet())
        assertEquals(1, o.getInt("v"))
        assertEquals("Tea?", o.getString("q"))
        assertEquals(true, o.getBoolean("multi"))
        assertEquals(true, o.getBoolean("anon"))
        assertEquals("ME=", o.getString("by"))
        assertEquals(2, o.getJSONArray("opts").length())
        assertFalse("an open poll must OMIT closes, as Swift's encodeIfPresent does", o.has("closes"))

        val vote = PollPayload.Vote("P-9", listOf(1), 1770000300.0).encode()
        assertTrue(vote.startsWith(ControlPrefix.POLL_VOTE))
        val vo = JSONObject(ControlPrefix.strip(vote))
        assertEquals(setOf("v", "poll", "picks", "at"), vo.keys().asSequence().toSet())
        assertEquals("P-9", vo.getString("poll"))
    }

    /** A poll we emit must be readable by our own decoder — necessary, and far from sufficient. */
    @Test
    fun `our own poll round-trips, which the golden vectors above do not rely on`() {
        val poll = PollPayload.Poll(
            id = "P-rt", question = "Q", options = listOf("a", "b", "c"),
            allowsMultipleAnswers = false, isAnonymous = false,
            createdAtUnix = 1.0, closesAtUnix = 2.0, creatorPublicKey = "K",
        )
        assertEquals(poll, PollPayload.decodePoll(poll.encode()))
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun `the four refusals both phones make`() {
        assertNull("not a poll at all", PollPayload.decodePoll("hello"))
        assertNull("not JSON", PollPayload.decodePoll(ControlPrefix.POLL + "{nope"))
        assertNull("no id", PollPayload.decodePoll(ControlPrefix.POLL + """{"q":"x","opts":["a","b"]}"""))
        assertNull(
            "fewer than two options survive",
            PollPayload.decodePoll(ControlPrefix.POLL + """{"id":"i","q":"x","opts":["only"]}"""),
        )
        assertNull("no opts key", PollPayload.decodePoll(ControlPrefix.POLL + """{"id":"i","q":"x"}"""))
        assertNull("a vote with no poll id", PollPayload.decodeVote(ControlPrefix.POLL_VOTE + """{"picks":[0]}"""))
    }

    /**
     * A blank option is DROPPED on decode, which is Android's `filter { it.isNotEmpty() }`.
     * Worth a test of its own because dropping it on one platform and keeping it on another
     * renumbers the indices every ballot refers to — a poll would show votes against the
     * wrong answers rather than failing visibly.
     */
    @Test
    fun `a blank option is dropped, and that is why index agreement matters`() {
        val wire = ControlPrefix.POLL + """{"id":"i","q":"x","opts":["a","","b"],"at":1.0}"""
        assertEquals(listOf("a", "b"), PollPayload.decodePoll(wire)!!.options)
    }

    @Test
    fun `sanitize is the one definition of sendable`() {
        assertNull("blank question", PollPayload.sanitize("   ", listOf("a", "b")))
        assertNull("one option", PollPayload.sanitize("q", listOf("a")))
        assertNull("blank options collapse below the minimum", PollPayload.sanitize("q", listOf("a", "  ")))
        assertNull("too many", PollPayload.sanitize("q", (1..13).map { "o$it" }))
        val (q, opts) = PollPayload.sanitize("  q  ", listOf(" a ", "b"))!!
        assertEquals("q", q)
        assertEquals(listOf("a", "b"), opts)
        assertEquals(PollPayload.MAX_QUESTION_LENGTH, PollPayload.sanitize("x".repeat(400), listOf("a", "b"))!!.first.length)
        assertEquals(PollPayload.MAX_OPTION_LENGTH, PollPayload.sanitize("q", listOf("y".repeat(200), "b"))!!.second[0].length)
        assertEquals("both platforms' maximum", 12, PollPayload.MAX_OPTIONS)
    }
}
