package com.oshi.desktop.app

import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.ProfileLinksPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_PROFILE_LINKS_2026_10_29__ Profile links through two real clients.
 *
 * The defect was not in a codec. The sentinel was in the catalog, so the payload reached
 * `ControlEvent.Foreign`, fell to step 7 of `handleV2Inbound` and was dropped — the fourth
 * sentinel in this catalog to do that, and the last one that is live on a phone.
 */
class ProfileLinksWiringTest {

    private val fx = WiringFixture()

    @After fun tearDown() = fx.close()

    private val links = ProfileLinksPayload.Links(
        websiteUrl = "https://example.org",
        githubUrl = "https://github.com/someone",
    )

    @Test
    fun `links reach the peer, and withdrawing hides them without forgetting them`() {
        val a = fx.client("a")
        val b = fx.client("b")

        assertEquals(OshiClient.SendOutcome.SENT, a.setProfileLinksVisible(b.address, true, links))
        b.router.poll()
        assertEquals("the payload was dropped at step 7", "https://example.org", b.contactLinks.shownLinks(a.address)!!.websiteUrl)
        assertTrue(b.contactLinks.isVisible(a.address))

        // SILENT: never a row, never a notification.
        assertTrue("a link change must not be a message", b.history(a.address).isEmpty())

        assertEquals(OshiClient.SendOutcome.SENT, a.setProfileLinksVisible(b.address, false, links))
        b.router.poll()
        assertFalse(b.contactLinks.isVisible(a.address))
        assertNull("nothing may be shown while hidden", b.contactLinks.shownLinks(a.address))
        assertEquals(
            "a withdrawal is a request, not a revocation — the strings are already here",
            "https://example.org", b.contactLinks.linksOf(a.address)!!.websiteUrl,
        )
        assertTrue(b.history(a.address).isEmpty())
    }

    /** A withdrawal must not carry the strings it is withdrawing. */
    @Test
    fun `a withdrawal sends no links on the wire`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        val wire = ProfileLinksPayload.encode(false, links)
        assertFalse(wire.contains("example.org"))
        assertFalse(wire.contains("links"))
        assertEquals(OshiClient.SendOutcome.NO_V2_PATH.javaClass, me.setProfileLinksVisible(peer, false, links).javaClass)
    }

    /** A malformed payload changes nothing and never becomes a row. */
    @Test
    fun `a malformed payload is dropped`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        fx.inbound(me, peer, ControlPrefix.LINKS_VISIBILITY + "{not json")
        assertNull(me.contactLinks.shownLinks(peer))
        assertTrue(me.history(peer).isEmpty())
    }

    /**
     * A hostile length cannot decide how much this window holds. Asserted on the stored
     * value, not on the decoder, because the store is what the view reads.
     */
    @Test
    fun `a peer cannot store an unbounded string`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        fx.inbound(
            me, peer,
            ProfileLinksPayload.encode(true, ProfileLinksPayload.Links(websiteUrl = "x".repeat(10_000))),
        )
        assertEquals(
            ProfileLinksPayload.MAX_LINK_LENGTH,
            me.contactLinks.shownLinks(peer)!!.websiteUrl!!.length,
        )
    }
}
