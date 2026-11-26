package com.oshi.desktop.app

import com.oshi.desktop.msg.AdvancedPrivacyPayload
import com.oshi.desktop.msg.ControlPrefix
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ The feature end to end between real clients over the test
 * relay: the capability gate (bit 16 learnt from the peer's bundle), the 1:1 local-only notice
 * for a peer that does not read it, the receive path (applied, stored, quiet), the group fan-out
 * restricted to readers, and the drops.
 */
class AdvancedChatPrivacyWiringTest {

    private val fx = WiringFixture()

    @After fun tearDown() = fx.close()

    private val wire = "🛡ADV_PRIVACY🛡"

    @Test
    fun `a peer whose caps we do not know is not sent the payload, the switch still holds here`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val out = a.setAdvancedPrivacy(b.address, true)
        assertTrue(a.isAdvancedPrivacyOn(b.address))
        assertEquals(0, out.sentTo)
        assertEquals(1, out.olderVersion)
        assertEquals("nothing reached B", 0, b.router.poll())
        val mine = a.history(b.address).single()
        assertTrue("our own notice row is stored", AdvancedPrivacyPayload.decode(mine.content)!!.on)
        assertTrue(mine.fromMe)
    }

    @Test
    fun `once the peer's bundle announced bit 16 the switch reaches it and is applied there`() {
        val a = fx.client("a")
        val b = fx.client("b")
        // Any first send fetches B's bundle; the fixture publishes this build's MY_CAPS (30).
        assertEquals(OshiClient.SendOutcome.SENT, a.send(b.address, "hello"))
        assertTrue("bit 16 learnt from the bundle", a.advancedPrivacy.peerReads(b.address))
        b.router.poll()

        val out = a.setAdvancedPrivacy(b.address, true)
        assertEquals(OshiClient.SendOutcome.SENT, out.send)
        b.router.poll()
        assertTrue("applied on B", b.isAdvancedPrivacyOn(a.address))
        assertTrue("B now knows A reads it", b.advancedPrivacy.sentUsOne(a.address))
        val row = b.history(a.address).last()
        assertTrue(AdvancedPrivacyPayload.isPayload(row.content))
        assertFalse(row.fromMe)
        assertTrue(ControlPrefix.isQuietNotice(row.content))

        // Turning it off travels the same way.
        Thread.sleep(1_100)   // a new second, so the off is strictly newer than the on
        a.setAdvancedPrivacy(b.address, false)
        b.router.poll()
        assertFalse(b.isAdvancedPrivacyOn(a.address))
    }

    @Test
    fun `an inbound payload is applied, stored once and the author is taken from the envelope`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val text = AdvancedPrivacyPayload.Change(on = true, atUnixSeconds = System.currentTimeMillis() / 1000).encode()
        fx.inbound(me, peer.address, text, msgId = "adv-1")
        fx.inbound(me, peer.address, text, msgId = "adv-1")   // the same message over a second lane
        assertTrue(me.isAdvancedPrivacyOn(peer.address))
        assertEquals("one row, not one per lane", 1, me.history(peer.address).size)
        assertTrue(me.advancedPrivacy.sentUsOne(peer.address))
    }

    @Test
    fun `the FE0F spelling is accepted and an unreadable payload is dropped, never stored`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        val at = System.currentTimeMillis() / 1000
        fx.inbound(me, peer.address, "$wire{\"v\":1,\"on\":\"yes\",\"at\":$at}")
        fx.inbound(me, peer.address, "$wire{\"v\":9,\"on\":true,\"at\":$at}")
        assertFalse(me.isAdvancedPrivacyOn(peer.address))
        assertTrue("never raw JSON in the thread", me.history(peer.address).isEmpty())

        fx.inbound(me, peer.address, "🛡️ADV_PRIVACY🛡️{\"v\":1,\"on\":true,\"at\":$at}")
        assertTrue(me.isAdvancedPrivacyOn(peer.address))
        assertEquals(1, me.history(peer.address).size)
    }

    @Test
    fun `a group switch fans out to the readers only and names the others in the footer count`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val c = fx.client("c")
        // C is on an older build: its bundle announces 14 (no bit 16), as desktop 1.6.6 does.
        fx.relay.capsOverride[c.address] = 14
        val g = a.createGroup("trio", listOf(b.address, c.address))
        b.router.poll(); c.router.poll()
        assertTrue(a.advancedPrivacy.peerReads(b.address))
        assertEquals(listOf(c.address), a.advancedPrivacyNonReaders(g.groupId))

        val cRowsBefore = c.messages.messages(g.groupId).size
        val out = a.setGroupAdvancedPrivacy(g.groupId, true)
        assertNotNull(out)
        assertEquals(1, out!!.sentTo)
        assertEquals(1, out.olderVersion)
        assertTrue(a.isAdvancedPrivacyOn(g.groupId))
        assertTrue("our own row", a.messages.messages(g.groupId).any { it.fromMe && AdvancedPrivacyPayload.isPayload(it.content) })

        b.router.poll(); c.router.poll()
        assertTrue("B (a reader) applied it", b.isAdvancedPrivacyOn(g.groupId))
        assertTrue(b.messages.messages(g.groupId).any { AdvancedPrivacyPayload.isPayload(it.content) && !it.fromMe })
        assertFalse("C was never sent it", c.isAdvancedPrivacyOn(g.groupId))
        assertEquals("no raw JSON on C", cRowsBefore, c.messages.messages(g.groupId).size)
    }

    @Test
    fun `any member, not only an admin, may switch it in a group`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val g = a.createGroup("duo", listOf(b.address))
        b.router.poll()
        // B reached A's session through A's own first message, so B never fetched A's bundle:
        // its caps reach B the way the server's `accountCaps` (device list) would carry them.
        b.advancedPrivacy.noteCaps(a.address, com.oshi.desktop.net.V2ConfigGate.MY_CAPS)
        val out = b.setGroupAdvancedPrivacy(g.groupId, true)
        assertEquals(1, out!!.sentTo)
        a.router.poll()
        assertTrue("the admin applied a member's switch", a.isAdvancedPrivacyOn(g.groupId))
    }
}
