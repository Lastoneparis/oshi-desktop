package com.oshi.desktop.app

import com.oshi.desktop.sync.SyncProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row 0.26 wired into the client — the SEAM, not the codec.
 *
 * `BotEnvelopeTest` already proves the envelope parses. These prove the parts that only
 * exist once the package is reachable from [OshiClient]: that a post fans out and comes
 * back, that it is filed somewhere it can never be mistaken for a ratcheted message, that
 * acking actually clears the server queue, and that the plaintext warning is not something
 * a surface can forget to carry.
 *
 * None of these would still pass with the wiring removed, which is the bar for a test in
 * this file.
 */
class BotWiringTest {

    private val fx = WiringFixture()

    @After fun tearDown() = fx.close()

    /** The queue is addressed by the LEGACY path key, not by the raw address. */
    private fun subscribe(c: OshiClient) {
        fx.relay.botSubscribers.add(SyncProtocol.legacyPathKey(c.address))
    }

    @Test
    fun `a bot post is delivered, stored, and acked off the queue`() {
        val a = fx.client("a")
        subscribe(a)

        val sent = a.sendBotMessage("tok12345", "grp-1", "build finished").getOrThrow()
        assertEquals(1, sent.delivered)

        assertEquals("the post did not come back off the queue", 1, a.pollBots())

        val row = a.messages.messages("bot!grp-1").single()
        assertEquals("build finished", row.content)

        // Acked, so a second poll is silent rather than re-reading the same post for ever.
        assertEquals("the queue entry was never acked", 0, a.pollBots())
        assertEquals(1, a.messages.messages("bot!grp-1").size)
    }

    /**
     * The important one. A bot post is NOT ratcheted and no peer authenticated it, so it
     * must never land in a conversation with a contact — rendering it there would make it
     * indistinguishable from a message the ratchet vouched for.
     */
    @Test
    fun `a bot post never lands in a peer conversation`() {
        val a = fx.client("a")
        val b = fx.client("b")
        subscribe(a)

        a.sendBotMessage("tok12345", "grp-1", "not from bob").getOrThrow()
        a.pollBots()

        assertTrue("a bot post was filed under a real peer", a.messages.messages(b.address).isEmpty())
        val ids = a.messages.conversations().map { it.conversationId }
        assertTrue("expected a bot! conversation, got $ids", ids.any { it.startsWith("bot!") })
        assertTrue("a bot conversation must not be addressable as a peer", ids.none { it == "bot" })
    }

    /**
     * The bot queue stamps ISO-8601 (epoch 4 of four live in this project), NOT millis.
     * A post that carries one must be recorded as ISO8601 — recording it as LOCAL_CLOCK
     * would claim this machine stamped a time it was merely told.
     */
    @Test
    fun `a bot post records ISO-8601 as its timestamp source`() {
        val a = fx.client("a")
        subscribe(a)
        a.sendBotMessage("tok12345", "grp-1", "stamped").getOrThrow()
        a.pollBots()

        val row = a.messages.messages("bot!grp-1").single()
        assertEquals("the ISO-8601 stamp was not parsed", 1_700_000_000_000L, row.sentAtMs)
        assertEquals(
            "a queue-supplied timestamp was recorded as if this machine had stamped it",
            com.oshi.desktop.store.TimestampSource.ISO8601, row.sentAtSource,
        )
    }

    /**
     * The warning is a value the client hands out, not a sentence a surface remembers to
     * write. If this constant is ever emptied, every surface silently stops warning — so
     * the guard is on the constant itself.
     */
    @Test
    fun `the plaintext warning exists and says the two things that matter`() {
        val w = OshiClient.BOT_CHANNEL_IS_PLAINTEXT
        assertTrue("the warning must say it is not end-to-end encrypted", w.contains("NOT end-to-end encrypted"))
        assertTrue("the warning must say the server can read it", w.contains("server can read"))
        assertTrue("the warning must name the metadata leak", w.contains("every member"))
    }

    /** A malformed entry costs that entry, never the rest of the queue. */
    @Test
    fun `a malformed bot envelope does not stop the ones behind it`() {
        val a = fx.client("a")
        subscribe(a)
        val key = SyncProtocol.legacyPathKey(a.address)
        fx.relay.pendingQueue.getOrPut(key) { mutableListOf() }.add("bot:broken:!!!not-base64!!!")

        a.sendBotMessage("tok12345", "grp-1", "behind the bad one").getOrThrow()

        assertEquals("the good post was lost with the bad one", 1, a.pollBots())
        assertNotNull(a.messages.messages("bot!grp-1").firstOrNull { it.content == "behind the bad one" })
    }
}
