package com.oshi.desktop.lora

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Node number → identity, and the spoofing it is designed to survive.
 *
 * The literals here (`%08x`, `lora!`, the 16-character floor) are the ones the Android tree
 * pins in `LoRaIdentityMapTest.kt:60-90`; the conflict rules are read off
 * `LoRaNodeIdentityMap.kt:160-208` and `LoRaNodeIdentityMap.swift:239-251`.
 */
class LoRaNodeBindingsTest {

    private val t0 = 1_756_089_600_000L
    private val alice = "AAAAbbbbCCCCddddEEEEffffGGGGhhhhIIIIjjjjKKK="
    private val bob = "ZZZZyyyyXXXXwwwwVVVVuuuuTTTTssssRRRRqqqqPPP="

    @After
    fun tearDown() {
        // The adopt flag is global on all three clients. Restore it so one test cannot
        // change another's routing — the kind of cross-test leak that makes a security
        // default look enforced when it is only untested.
        LoRaNodeBindings.adoptRawTextIntoContactThread = false
    }

    // ============================================================ THE KEY FORMATS

    /** `LoRaIdentityMapTest.kt:60-76` — fixed 8-digit lowercase hex, masked to 32 bits. */
    @Test
    fun `shipped vector - the node key is fixed eight-digit lowercase hex`() {
        assertEquals("1234abcd", LoRaNodeBindings.nodeKey(0x1234ABCDu))
        assertEquals("0000beef", LoRaNodeBindings.nodeKey(0x0000BEEFu))
        assertEquals("00000000", LoRaNodeBindings.nodeKey(0u))
        assertEquals("ffffffff", LoRaNodeBindings.nodeKey(0xFFFFFFFFu))
    }

    /** `lora!<nodehex>` — **never changed**, which is why existing threads keep working. */
    @Test
    fun `shipped vector - the fallback conversation key is lora-bang plus the node hex`() {
        assertEquals("lora!1234abcd", LoRaNodeBindings.fallbackConversationKey(0x1234ABCDu))
        assertTrue(LoRaNodeBindings.isFallbackConversationKey("lora!1234abcd"))
        assertFalse(LoRaNodeBindings.isFallbackConversationKey(alice))
    }

    /**
     * `looksLikeIdentityKey` (`swift:130-136`): 16-character floor, not a `lora!` key, and
     * containing no `!` at all.
     *
     * The `!` rule is the load-bearing one — it is what stops a fallback key being fed back
     * in and becoming a binding to itself.
     */
    @Test
    fun `shipped vector - looksLikeIdentityKey rejects synthetic and short keys`() {
        assertTrue(LoRaNodeBindings.looksLikeIdentityKey(alice))
        assertFalse("too short", LoRaNodeBindings.looksLikeIdentityKey("abc"))
        assertFalse("a fallback key", LoRaNodeBindings.looksLikeIdentityKey("lora!1234abcd"))
        assertFalse("any bang at all", LoRaNodeBindings.looksLikeIdentityKey("aaaaaaaaaaaaaaaa!b"))
        assertFalse("empty", LoRaNodeBindings.looksLikeIdentityKey(""))
    }

    // ============================================================ BINDING

    @Test
    fun `a first binding is BOUND and routes`() {
        val m = LoRaNodeBindings()
        assertEquals(LoRaNodeBindings.LearnResult.BOUND, m.bind(1u, alice, t0))
        assertEquals(alice, m.boundKey(1u))
        assertFalse(m.isAmbiguous(1u))
    }

    @Test
    fun `re-binding the same identity is UNCHANGED`() {
        val m = LoRaNodeBindings()
        m.bind(1u, alice, t0)
        assertEquals(LoRaNodeBindings.LearnResult.UNCHANGED, m.bind(1u, alice, t0 + 1))
        // …including when the same key arrives spelled base64url. Reading that as a
        // CONFLICT would lock a node out of routing over a spelling.
        val urlSpelling = alice.replace("+", "-").replace("/", "_").trimEnd('=')
        assertEquals(LoRaNodeBindings.LearnResult.UNCHANGED, m.bind(1u, urlSpelling, t0 + 2))
    }

    @Test
    fun `a key that does not look like an identity is REJECTED`() {
        val m = LoRaNodeBindings()
        assertEquals(LoRaNodeBindings.LearnResult.REJECTED, m.bind(1u, "lora!0000beef", t0))
        assertEquals(LoRaNodeBindings.LearnResult.REJECTED, m.bind(1u, "short", t0))
        assertNull(m.boundKey(1u))
    }

    /** Rule 1: one node claiming two identities goes ambiguous and stops routing. */
    @Test
    fun `one node claiming two identities becomes ambiguous`() {
        val m = LoRaNodeBindings()
        m.bind(1u, alice, t0)
        assertEquals(LoRaNodeBindings.LearnResult.CONFLICT, m.bind(1u, bob, t0 + 1))
        assertTrue(m.isAmbiguous(1u))
        assertNull("an ambiguous binding does not route", m.boundKey(1u))
    }

    /**
     * **Rule 2 — the 2026-08-10 iOS fix, and the direction an attacker actually uses.**
     *
     * The conflict rule originally ran one way only. The INVERSE was explicitly allowed:
     * bind your own node to a contact's already-bound identity, and every raw text you
     * transmit is filed in that contact's conversation.
     *
     * BOTH bindings are marked ambiguous, because which of the two is the impostor is not
     * knowable here and picking one would be a coin flip on whose messages get attributed
     * to a real contact.
     */
    @Test
    fun `two nodes claiming one identity marks BOTH ambiguous`() {
        val m = LoRaNodeBindings()
        assertEquals(LoRaNodeBindings.LearnResult.BOUND, m.bind(1u, alice, t0))

        // The attacker's node claims Alice's identity.
        assertEquals(LoRaNodeBindings.LearnResult.CONFLICT, m.bind(2u, alice, t0 + 1))

        assertTrue("the impostor is ambiguous", m.isAmbiguous(2u))
        assertTrue("and so is the legitimate one — we cannot tell them apart", m.isAmbiguous(1u))
        assertNull(m.boundKey(1u))
        assertNull(m.boundKey(2u))
    }

    /** Once ambiguous, stays ambiguous — until [LoRaNodeBindings.forget]. */
    @Test
    fun `an ambiguous node is not re-bound and forget is the way out`() {
        val m = LoRaNodeBindings()
        m.bind(1u, alice, t0)
        m.bind(1u, bob, t0 + 1)
        assertEquals(LoRaNodeBindings.LearnResult.IGNORED_AMBIGUOUS, m.bind(1u, alice, t0 + 2))

        m.forget(1u)
        assertFalse(m.isAmbiguous(1u))
        assertEquals(LoRaNodeBindings.LearnResult.BOUND, m.bind(1u, alice, t0 + 3))
        assertEquals(alice, m.boundKey(1u))
    }

    // ============================================================ THE ADOPT FLAG

    /**
     * **Default OFF, and a bound node still gets the `lora!` thread.**
     *
     * This is the security default the whole interop lane rests on: a raw Meshtastic text
     * packet is unauthenticated (`MeshPacket.from` is an unsigned integer anyone in range
     * can claim), so filing one into a contact's E2E conversation would render it
     * identically to a message the ratchet authenticated.
     */
    @Test
    fun `with the adopt flag off a bound node still routes to its lora thread`() {
        val m = LoRaNodeBindings()
        m.bind(0x1234ABCDu, alice, t0)
        assertEquals(alice, m.boundKey(0x1234ABCDu))
        assertEquals(
            "a PROVED identity still does not adopt raw text",
            "lora!1234abcd",
            m.conversationKey(0x1234ABCDu),
        )
    }

    /** With it on, it does — which is the behaviour that needs a UI marker first. */
    @Test
    fun `with the adopt flag on a bound node adopts the contact thread`() {
        val m = LoRaNodeBindings()
        m.bind(0x1234ABCDu, alice, t0)
        LoRaNodeBindings.adoptRawTextIntoContactThread = true
        assertEquals(alice, m.conversationKey(0x1234ABCDu))
    }

    /** …but never for an unbound or an ambiguous node, flag or no flag. */
    @Test
    fun `the adopt flag never routes an unbound or ambiguous node`() {
        val m = LoRaNodeBindings()
        LoRaNodeBindings.adoptRawTextIntoContactThread = true

        assertEquals("lora!00000009", m.conversationKey(9u))

        m.bind(1u, alice, t0)
        m.bind(1u, bob, t0 + 1)
        assertEquals("lora!00000001", m.conversationKey(1u))
    }

    // ============================================================ PERSISTENCE SHAPE

    @Test
    fun `a snapshot restores to the same routing`() {
        val a = LoRaNodeBindings()
        a.bind(0x1234ABCDu, alice, t0)
        a.bind(2u, bob, t0)
        a.bind(2u, alice, t0)   // rule 1: node 2 claimed two identities → node 2 ambiguous

        val b = LoRaNodeBindings()
        b.restore(a.snapshot())
        assertEquals(a.boundKey(0x1234ABCDu), b.boundKey(0x1234ABCDu))
        assertEquals(a.isAmbiguous(2u), b.isAmbiguous(2u))
        assertTrue(b.snapshot().containsKey("1234abcd"))
    }
}
