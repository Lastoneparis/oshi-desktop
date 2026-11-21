package com.oshi.desktop.app

import com.oshi.desktop.msg.ControlPrefix
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __SHARED_NICKNAME_2026_09_22__ A `📸PROFILE_UPDATE📸` reaching the router's callback
 * sets the sender's shared nickname — and does NOTHING else: no row, no `onMessage`
 * (the notification trigger), and never the local alias.
 */
class SharedNicknameWiringTest {

    private val fx = WiringFixture()

    @After
    fun tearDown() = fx.close()

    private fun profile(body: String) = ControlPrefix.PROFILE_UPDATE + body

    @Test
    fun `a profile update stores the nickname silently`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        var notified = 0
        me.onMessage = { notified++ }

        fx.inbound(me, peer, profile("""{"type":"profile_update","lastSeen":1.7E9,"version":1,"displayName":"Hugo‮"}"""))

        val c = me.contacts.get(peer)!!
        assertEquals("Hugo", c.sharedNickname)
        assertNull("the peer's name must never become our alias", c.displayName)
        assertTrue("a profile update must not become a message row", me.history(peer).isEmpty())
        assertEquals("a profile update must not notify", 0, notified)
    }

    @Test
    fun `an old payload keeps the nickname, an explicit empty one clears it, the alias always wins`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        fx.inbound(me, peer, profile("""{"type":"profile_update","displayName":"Hugo"}"""))

        fx.inbound(me, peer, profile("""{"type":"profile_update","lastSeen":1.7E9,"version":1}"""))
        assertEquals("Hugo", me.contacts.get(peer)!!.sharedNickname)

        me.contacts.setDisplayName(peer, "Mum")
        assertEquals("Mum", me.contacts.get(peer)!!.label("x"))

        fx.inbound(me, peer, profile("""{"type":"profile_update","displayName":""}"""))
        assertNull(me.contacts.get(peer)!!.sharedNickname)
        assertEquals("Mum", me.contacts.get(peer)!!.label("x"))
    }
}
