package com.oshi.desktop.app

import com.oshi.desktop.msg.ControlPrefix
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __SHARED_NICKNAME_2026_09_22__ The desktop SENDS its nickname now, end to end through a
 * behaving relay: set → encrypted `📸PROFILE_UPDATE📸` over V2 → the peer's poll → stored
 * as the peer's shared nickname, with no row and no notification on either side.
 */
class SharedNicknameSendTest {

    private val fx = WiringFixture()

    @After
    fun tearDown() = fx.close()

    /** Two accounts that have exchanged one real message each way. */
    private fun pair(): Pair<OshiClient, OshiClient> {
        val a = fx.client("alice")
        val b = fx.client("bob")
        assertEquals(OshiClient.SendOutcome.SENT, a.send(b.address, "hi"))
        b.router.poll()
        assertEquals(OshiClient.SendOutcome.SENT, b.send(a.address, "hello"))
        a.router.poll()
        return a to b
    }

    @Test
    fun `setting the nickname reaches a contact silently, and removing it clears it there`() {
        val (a, b) = pair()
        val rowsBefore = b.history(a.address).size
        var notified = 0
        b.onMessage = { notified++ }

        val set = a.setOwnNickname("  Alice‮ ")
        assertEquals("Alice", set.nickname)
        assertEquals(1, set.eligible)
        assertEquals(1, set.sent)
        assertEquals("Alice", a.ownNickname)
        assertEquals("Alice", a.displayName)
        b.router.poll()

        assertEquals("Alice", b.contacts.get(a.address)!!.sharedNickname)
        assertNull("never written into the local alias", b.contacts.get(a.address)!!.displayName)
        assertEquals("no row on the receiver", rowsBefore, b.history(a.address).size)
        assertEquals("no notification on the receiver", 0, notified)

        val clear = a.setOwnNickname("   ")
        assertNull(clear.nickname)
        assertTrue(clear.changed)
        b.router.poll()
        assertNull(b.contacts.get(a.address)!!.sharedNickname)
        assertEquals("alice", a.displayName)   // back to the launch fallback
    }

    @Test
    fun `nothing is sent to someone we never wrote to`() {
        val a = fx.client("alice")
        val stranger = fx.client("stranger")
        // The stranger writes to us; we never answer.
        assertEquals(OshiClient.SendOutcome.SENT, stranger.send(a.address, "who are you"))
        a.router.poll()

        val u = a.setOwnNickname("Alice")
        assertEquals(0, u.eligible)
        stranger.router.poll()
        assertNull(stranger.contacts.get(a.address)?.sharedNickname)
    }

    @Test
    fun `a first reply introduces us, and a profile request is answered`() {
        val a = fx.client("alice")
        val b = fx.client("bob")
        a.setOwnNickname("Alice")          // nobody eligible yet
        assertEquals(OshiClient.SendOutcome.SENT, b.send(a.address, "ping"))
        a.router.poll()

        // First reply: the profile follows the message.
        assertEquals(OshiClient.SendOutcome.SENT, a.send(b.address, "pong"))
        b.router.poll()
        assertEquals("Alice", b.contacts.get(a.address)!!.sharedNickname)

        // A phone that lost it asks; the answer carries the current name.
        b.contacts.setSharedNickname(a.address, null)
        fx.inbound(a, b.address, ControlPrefix.PROFILE_REQUEST)
        b.router.poll()
        assertEquals("Alice", b.contacts.get(a.address)!!.sharedNickname)
    }

    @Test
    fun `a contact who set a name before we ever heard it is asked once, on their next message`() {
        val a = fx.client("alice")
        val b = fx.client("bob")
        assertEquals(OshiClient.SendOutcome.SENT, a.send(b.address, "1"))
        b.router.poll()
        // Bob's name exists but was never broadcast to Alice (a pre-feature contact).
        b.vault.put(OshiClient.OWN_NICKNAME_ACCOUNT, "Bob".toByteArray())
        assertEquals(OshiClient.SendOutcome.SENT, b.send(a.address, "2"))
        a.router.poll()      // prose from Bob → Alice asks for his profile (silent)
        b.router.poll()      // Bob answers
        a.router.poll()
        assertEquals("Bob", a.contacts.get(b.address)!!.sharedNickname)
        assertEquals("no extra rows from the exchange", 2, a.history(b.address).size)
    }

    @Test
    fun `the nickname survives a restart - it lives in the sealed vault, not a plaintext file`() {
        val a = fx.client("alice")
        a.setOwnNickname("Alice")
        assertTrue(a.vault.accounts().contains(OshiClient.OWN_NICKNAME_ACCOUNT))
        assertEquals("Alice", a.ownNickname)
    }

    @Test
    fun `the REPL sets, shows and clears it`() {
        val (a, _) = pair()
        assertTrue(fx.repl(a, "/nick Alice").single().contains("sent to 1/1"))
        assertTrue(fx.repl(a, "/nick").single().contains("Alice"))
        assertTrue(fx.repl(a, "/nick clear").single().contains("removed"))
    }
}
