package com.oshi.desktop.covert

import com.oshi.messenger.network.v2.OSHICryptoV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * [CovertText] — the seam that made the text covert channel reachable.
 *
 * [TextSteganographyTest] already pins the carrier format against iOS. These tests are
 * about the thing that was missing: that two real identities agree on a key, that a third
 * cannot, and that every way this can fail is reported as something a user could act on.
 *
 * The one test that matters most is `a third party cannot reveal`. Everything else here is
 * plumbing; that one is the property the feature exists for.
 */
class CovertTextTest {

    private class Peer {
        val pair = OSHICryptoV2.generateX25519()
        val address: String = Base64.getEncoder().encodeToString(pair.pub)
        val covert = CovertText(pair.priv)

        /** Reads as "alice hides X for bob", which is what each test is actually saying. */
        fun hide(
            message: String,
            cover: String,
            to: String,
            method: TextSteganography.Method = TextSteganography.Method.COMBINED,
        ): CovertText.Hidden = covert.hide(message, cover, to, method)
    }

    private val alice = Peer()
    private val bob = Peer()

    /** Long enough to carry a sentence: zero-width capacity is about one byte per character. */
    private val cover = TextSteganography().generateCoverText(
        TextSteganography.CoverTextStyle.SOCIAL_COMMENT
    )

    private fun carrierOf(h: CovertText.Hidden): String {
        assertTrue("expected a carrier, got $h", h is CovertText.Hidden.Carrier)
        return (h as CovertText.Hidden.Carrier).text
    }

    // ---------------------------------------------------------------- the round trip

    @Test
    fun `a message hidden for a peer is revealed by that peer`() {
        val secret = "meet at the north gate at six"
        val carrier = carrierOf(alice.hide(secret, cover, bob.address))
        assertEquals(secret, bob.covert.reveal(carrier, alice.address))
    }

    /**
     * THE PROPERTY THE FEATURE EXISTS FOR.
     *
     * A static-static ECDH means Alice→Bob and Bob→Alice agree on the same secret, and
     * nobody else agrees on it at all. If this ever passes a non-null to Eve, the channel
     * is decoration.
     */
    @Test
    fun `a third party cannot reveal, even holding the carrier and both addresses`() {
        val eve = Peer()
        val carrier = carrierOf(alice.hide("the safe house moved", cover, bob.address))

        assertNull("Eve must not read it with her own key", eve.covert.reveal(carrier, alice.address))
        assertNull("nor by guessing the recipient", eve.covert.reveal(carrier, bob.address))
        // And Bob must not read it as though it came from Eve: the agreement names the peer.
        assertNull("wrong claimed sender must not decrypt", bob.covert.reveal(carrier, eve.address))
    }

    @Test
    fun `every method round-trips`() {
        for (m in TextSteganography.Method.entries) {
            val msg = "method $m"
            val h = alice.hide(msg, cover, bob.address, m)
            // WHITESPACE and HOMOGLYPH have far less room than ZERO_WIDTH; a refusal for
            // capacity is a legitimate outcome and is asserted as such rather than skipped.
            when (h) {
                is CovertText.Hidden.Carrier ->
                    assertEquals("$m must round-trip", msg, bob.covert.reveal(h.text, alice.address))
                is CovertText.Hidden.CoverTooShort ->
                    assertTrue("$m: needed must exceed available", h.needed > h.available)
                else -> throw AssertionError("$m gave an unexpected outcome: $h")
            }
        }
    }

    @Test
    fun `unicode survives, because a covert channel that is English-only is not one`() {
        // Arabic, an emoji, and a combining accent: three ways a byte-level format breaks.
        val msg = "لا تأتِ الليلة — café ☂"
        val carrier = carrierOf(alice.hide(msg, cover, bob.address))
        assertEquals(msg, bob.covert.reveal(carrier, alice.address))
    }

    // ---------------------------------------------------------------- invisibility

    @Test
    fun `the carrier reads as the cover text once stripped`() {
        val carrier = carrierOf(alice.hide("hidden", cover, bob.address))
        assertTrue("the carrier must differ from the cover, or nothing was embedded",
            carrier != cover)
        assertEquals("stripping must give the cover text back", cover, alice.covert.strip(carrier))
    }

    @Test
    fun `looksLikeCarrier finds a carrier and leaves ordinary prose alone`() {
        val carrier = carrierOf(alice.hide("hidden", cover, bob.address))
        assertTrue(alice.covert.looksLikeCarrier(carrier))
        assertFalse("plain cover text must not read as a carrier",
            alice.covert.looksLikeCarrier(cover))
    }

    // ---------------------------------------------------------------- the failure modes

    @Test
    fun `an empty message is its own outcome, not a capacity complaint`() {
        assertEquals(CovertText.Hidden.EmptyMessage, alice.hide("", cover, bob.address))
    }

    @Test
    fun `an address that is not an address is reported as such`() {
        for (bad in listOf("", "   ", "not-base64!!", "aGVsbG8=", "x")) {
            assertEquals("«$bad» must be refused as a peer",
                CovertText.Hidden.UnknownPeer, alice.hide("hi", cover, bad))
        }
    }

    @Test
    fun `a cover text too short says how short, in payload bytes`() {
        val msg = "a message far longer than the cover text can possibly carry"
        val h = alice.hide(msg, "tiny cover", bob.address, TextSteganography.Method.ZERO_WIDTH)
        assertTrue("expected CoverTooShort, got $h", h is CovertText.Hidden.CoverTooShort)
        h as CovertText.Hidden.CoverTooShort
        assertEquals("needed is the message plus AES-GCM overhead",
            msg.toByteArray().size + CovertText.GCM_OVERHEAD_BYTES, h.needed)
        assertTrue("available must be under needed, or this was not the failure", h.available < h.needed)
    }

    @Test
    fun `roomFor never promises room that hide then refuses`() {
        // The number shown to a user has to be the number `hide` actually accepts, or the
        // UI tells them a message fits and then declines it.
        val room = alice.covert.roomFor(cover, TextSteganography.Method.ZERO_WIDTH)
        assertTrue("the standard cover text should hold something", room > 0)
        val exact = "x".repeat(room)
        assertTrue("a message of exactly roomFor() bytes must be accepted",
            alice.hide(exact, cover, bob.address, TextSteganography.Method.ZERO_WIDTH)
                is CovertText.Hidden.Carrier)
        val oneTooMany = "x".repeat(room + 1)
        assertTrue("one byte over must be refused, not silently truncated",
            alice.hide(oneTooMany, cover, bob.address, TextSteganography.Method.ZERO_WIDTH)
                is CovertText.Hidden.CoverTooShort)
    }

    @Test
    fun `ordinary text carrying nothing reveals nothing`() {
        assertNull(bob.covert.reveal(cover, alice.address))
        assertNull(bob.covert.reveal("", alice.address))
        assertNull(bob.covert.reveal("just a sentence", alice.address))
    }

    /**
     * A payload that decrypts but is a JSON object is iOS's `CovertFragment`, not a message
     * a person typed. Revealing its JSON as "the hidden message" would be a lie with the
     * peer's name on it, so it is withheld.
     */
    @Test
    fun `a JSON payload is not presented as a message`() {
        val json = """{"index":0,"total":1,"data":"AAAA"}"""
        val carrier = carrierOf(alice.hide(json, cover, bob.address))
        assertNull("a CovertFragment-shaped payload must not surface as text",
            bob.covert.reveal(carrier, alice.address))
    }

    /**
     * A payload that decrypts to bytes that are not UTF-8 must reveal NOTHING.
     *
     * This test exists because the guard it covers was unwatched: replacing the strict
     * decoder with `ByteArray.toString(UTF_8)` broke no test at all, and that substitution
     * is silent — it yields U+FFFD replacement characters, so the user would be shown a
     * screenful of «����» presented as their contact's message.
     *
     * It has to go through [TextSteganography] directly, because [CovertText.hide] takes a
     * String and so can never produce invalid UTF-8 itself. The bytes here are what a
     * future binary payload — or a corrupted one — looks like.
     */
    @Test
    fun `a payload that is not valid UTF-8 reveals nothing, not replacement characters`() {
        val secret = OSHICryptoV2.dh(alice.pair.priv, bob.pair.pub)
        val notUtf8 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0xC0.toByte())
        val carrier = TextSteganography()
            .embed(notUtf8, cover, secret, TextSteganography.Method.ZERO_WIDTH)
        assertNotNull("the carrier itself must be written — the guard is on the read side", carrier)
        assertNull("invalid UTF-8 must not surface as text",
            bob.covert.reveal(carrier!!, alice.address))
    }

    /** Nothing here keeps state, and a reused instance must not behave differently. */
    @Test
    fun `the same instance hides twice without carrying anything over`() {
        val one = carrierOf(alice.hide("first", cover, bob.address))
        val two = carrierOf(alice.hide("second", cover, bob.address))
        assertEquals("first", bob.covert.reveal(one, alice.address))
        assertEquals("second", bob.covert.reveal(two, alice.address))
        assertTrue("a fresh nonce each time means the carriers must differ", one != two)
    }

    /**
     * The address the UI holds may be base64url or unpadded, because relay and deep-link
     * fields are. `ContactQr.canonicalAddress` normalises it, and a peer must agree on the
     * same key however their address was spelled — otherwise the channel works or not
     * depending on which screen the address was copied from.
     */
    @Test
    fun `an address spelled base64url or unpadded agrees on the same key`() {
        val carrier = carrierOf(alice.hide("same key either way", cover, bob.address))
        val urlish = bob.address.replace('+', '-').replace('/', '_').trimEnd('=')
        assertNotNull("padded form must work", bob.covert.reveal(carrier, alice.address))
        val viaUrlish = carrierOf(alice.hide("same key either way", cover, urlish))
        assertEquals("same key either way", bob.covert.reveal(viaUrlish, alice.address))
    }
}
