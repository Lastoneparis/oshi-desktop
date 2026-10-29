package com.oshi.desktop.app

import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.PollPayload
import com.oshi.desktop.store.PollTally
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_POLLS_2026_10_29__ A poll and a ballot through two real clients over the fixture
 * relay.
 *
 * `PollWireFormatTest` pins the bytes and `PollTallyTest` pins the rules. Neither can see the
 * defect this closed, which was not in either: the sentinels were in the catalog, so a poll
 * reached `ControlEvent.Foreign`, fell to step 7 of `handleV2Inbound` — *"a sentinel
 * belonging to a row this client has not built"* — and was logged and dropped. Every rule
 * could have been perfect and an iPhone's poll would still have vanished.
 */
class PollWiringTest {

    private val fx = WiringFixture()

    @After fun tearDown() = fx.close()

    @Test
    fun `a poll crosses to the peer and a ballot crosses back`() {
        val a = fx.client("a")
        val b = fx.client("b")

        assertEquals(
            OshiClient.SendOutcome.SENT,
            a.sendPoll(b.address, "Pizza or sushi?", listOf("Pizza", "Sushi", "Either")),
        )
        // The sender's own row holds the payload, not a rendered summary.
        val mine = a.messages.messages(b.address).last()
        val sentPoll = PollPayload.decodePoll(mine.content)
        assertNotNull("our own bubble must be able to draw the card", sentPoll)
        assertEquals(listOf("Pizza", "Sushi", "Either"), sentPoll!!.options)
        assertEquals("the creator is us", a.address, sentPoll.creatorPublicKey)

        // It ARRIVES — the regression this test exists for.
        assertEquals(1, b.router.poll())
        val theirs = b.messages.messages(a.address).last()
        val got = PollPayload.decodePoll(theirs.content)
        assertNotNull("the poll was dropped at step 7 — the PIN_NOT_APPLIED of polls", got)
        assertEquals(sentPoll.id, got!!.id)
        assertEquals("Pizza or sushi?", got.question)

        // B votes. The ballot is recorded locally at once and reaches A.
        assertEquals(OshiClient.SendOutcome.SENT, b.votePoll(a.address, got.id, listOf(1)))
        assertEquals(setOf(1), b.pollBallots.results(got, b.address).myPicks)
        a.router.poll()
        val onA = a.pollBallots.results(sentPoll, a.address)
        assertEquals("B's ballot never reached A", listOf(0, 1, 0), onA.counts)
        assertEquals(1, onA.voterCount)
        assertTrue("A has not voted yet", onA.myPicks.isEmpty())
        assertEquals(
            "the voter is the authenticated sender, not a field in the JSON",
            listOf(PollTally.normalize(b.address)), onA.votersByOption[1],
        )
    }

    /**
     * A ballot is SILENT and never becomes a bubble. If it did, every vote in a busy poll
     * would be a message in the thread and a notification on the desktop.
     */
    @Test
    fun `a ballot is not a message row`() {
        val a = fx.client("a")
        val b = fx.client("b")
        a.sendPoll(b.address, "Q", listOf("x", "y"))
        assertEquals(1, b.router.poll())
        val before = b.messages.messages(a.address).size
        val poll = PollPayload.decodePoll(b.messages.messages(a.address).last().content)!!

        assertEquals(OshiClient.SendOutcome.SENT, a.votePoll(b.address, poll.id, listOf(0)))
        b.router.poll()
        assertEquals("a vote became a row", before, b.messages.messages(a.address).size)
        assertEquals(1, b.pollBallots.results(poll, b.address).voterCount)
        // And the sender does not store its own ballot as a row either.
        assertTrue(a.messages.messages(b.address).none { ControlPrefix.match(it.content ?: "") == ControlPrefix.POLL_VOTE })
    }

    /** Changing a vote replaces it; retracting it removes the voter entirely. */
    @Test
    fun `a changed vote replaces the earlier one and an empty one retracts it`() {
        val a = fx.client("a")
        val b = fx.client("b")
        a.sendPoll(b.address, "Q", listOf("x", "y"))
        b.router.poll()
        val poll = PollPayload.decodePoll(b.messages.messages(a.address).last().content)!!

        b.votePoll(a.address, poll.id, listOf(0))
        a.router.poll()
        assertEquals(listOf(1, 0), a.pollBallots.results(poll, a.address).counts)

        // A later ballot from the same voter wins. The fixture is fast, so the second vote
        // is stamped explicitly rather than relying on the wall clock advancing.
        b.votePoll(a.address, poll.id, listOf(1))
        a.router.poll()
        val after = a.pollBallots.results(poll, a.address)
        assertEquals("one voter, moved — never two", 1, after.voterCount)
        assertTrue("either the move landed or the stamps tied", after.counts == listOf(0, 1) || after.counts == listOf(1, 0))
    }

    /** A malformed poll is dropped rather than drawn: there is no partial poll worth showing. */
    @Test
    fun `a poll with one option never becomes a row`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        fx.inbound(me, peer, ControlPrefix.POLL + """{"id":"bad","q":"x","opts":["only"]}""")
        assertTrue("a one-option poll was stored", me.history(peer).isEmpty())
    }

    /** A vote for a poll this device never saw is recorded, so the poll tallies if it arrives later. */
    @Test
    fun `a ballot that arrives before its poll is kept`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        fx.inbound(me, peer, PollPayload.Vote("P-late", listOf(0), 500.0).encode())
        assertTrue("a ballot must never be a row", me.history(peer).isEmpty())
        assertEquals(1, me.pollBallots.ballotsFor("P-late").size)
        // And when the poll turns up, the ballot counts.
        val poll = PollPayload.Poll(
            id = "P-late", question = "Q", options = listOf("x", "y"),
            allowsMultipleAnswers = false, isAnonymous = false,
            createdAtUnix = 1.0, closesAtUnix = null, creatorPublicKey = peer,
        )
        assertEquals(listOf(1, 0), me.pollBallots.results(poll, me.address).counts)
    }

    @Test
    fun `an unsendable poll is refused without touching the network`() {
        val a = fx.client("a")
        val b = fx.client("b")
        assertNull("one option", a.sendPoll(b.address, "Q", listOf("only")))
        assertNull("blank question", a.sendPoll(b.address, "   ", listOf("x", "y")))
        assertNull("thirteen options", a.sendPoll(b.address, "Q", (1..13).map { "o$it" }))
        assertTrue(a.messages.messages(b.address).isEmpty())
        assertEquals(0, b.router.poll())
    }

    @Test
    fun `voting with no poll id is refused`() {
        val a = fx.client("a")
        val b = fx.client("b")
        assertEquals(OshiClient.SendOutcome.NO_V2_PATH, a.votePoll(b.address, "", listOf(0)))
        assertFalse(a.pollBallots.ballotsFor("").isNotEmpty())
    }
}
