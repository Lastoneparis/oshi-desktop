package com.oshi.desktop.block

import com.oshi.desktop.store.ContactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * [BlockPolicy] — PARITY.md row 0.21.
 *
 * The fixtures are the ones the shipped clients name. In particular the padded /
 * unpadded pair is iOS's own worked example: "a contact blocked as `eAbMQUdW…cyc=` would
 * NOT match an incoming call signaling `eAbMQUdW…cyc` (no padding) and the call would
 * ring" (`BlockedContactsManager.swift:80-88`). If that sentence is true, the assertions
 * below are what makes it true here.
 */
class BlockPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): ContactStore = ContactStore(File(tmp.newFolder(), "contacts.json"))

    /** A real 32-byte key shape, `=`-padded, containing `+` and `/`. */
    private val PADDED = "kr/6aAArDrl91TOXf/bjv2u1SiRf+H8gIi8BBgPYpi0="
    private val UNPADDED = PADDED.trimEnd('=')
    private val URL_SAFE = PADDED.replace('+', '-').replace('/', '_').trimEnd('=')
    private val STRANGER = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 5 + 1).toByte() })

    private fun seeded(): ContactStore = store().apply {
        seen(PADDED, 1_000L)
        seen(STRANGER, 1_000L)
    }

    // ────────────────────────────────────────────── the fold itself

    /** `BlockedContactsManager.swift:85-90` and `MessageRepository.kt:214-219`, verbatim. */
    @Test
    fun `normalisation matches the shipped fold`() {
        assertEquals("abc+/", BlockPolicy.normalizeKey("  abc-_==  "))
        assertEquals(BlockPolicy.normalizeKey(PADDED), BlockPolicy.normalizeKey(UNPADDED))
        assertEquals(BlockPolicy.normalizeKey(PADDED), BlockPolicy.normalizeKey(URL_SAFE))
        assertEquals(BlockPolicy.normalizeKey(PADDED), BlockPolicy.normalizeKey("\n$PADDED\t"))
        // The output is a comparison value, not a storable key: the padding is gone.
        assertFalse(BlockPolicy.normalizeKey(PADDED).endsWith("="))
    }

    // ────────────────────────────────────────────── the block check

    @Test
    fun `a block set on the padded spelling holds against every other spelling`() {
        val contacts = seeded()
        contacts.block(PADDED)

        assertTrue("padded", BlockPolicy.isBlocked(contacts, PADDED))
        assertTrue("no padding — iOS's own ringing-call example", BlockPolicy.isBlocked(contacts, UNPADDED))
        assertTrue("base64url", BlockPolicy.isBlocked(contacts, URL_SAFE))
        assertTrue("surrounded by whitespace", BlockPolicy.isBlocked(contacts, "  $PADDED  "))
    }

    @Test
    fun `the store's own exact lookup is what this exists to fix`() {
        val contacts = seeded()
        contacts.block(PADDED)
        // Not a criticism of ContactStore: a store is keyed by the address it was given,
        // and this asserts the gap it leaves rather than a defect in it.
        assertFalse("exact lookup misses the unpadded spelling", contacts.isBlocked(UNPADDED))
        assertTrue("the policy does not", BlockPolicy.isBlocked(contacts, UNPADDED))
    }

    @Test
    fun `an unblocked contact and an unknown key are not blocked`() {
        val contacts = seeded()
        contacts.block(PADDED)
        assertFalse(BlockPolicy.isBlocked(contacts, STRANGER))
        assertFalse(BlockPolicy.isBlocked(contacts, ""))
        assertFalse(BlockPolicy.isBlocked(contacts, "   "))
        assertFalse(BlockPolicy.isBlocked(contacts, "someone-we-never-met"))
    }

    @Test
    fun `unblocking clears it again on every spelling`() {
        val contacts = seeded()
        contacts.block(PADDED)
        assertEquals(1, BlockPolicy.unblockEverySpelling(contacts, URL_SAFE))
        assertFalse(BlockPolicy.isBlocked(contacts, PADDED))
        assertFalse(BlockPolicy.isBlocked(contacts, UNPADDED))
        assertEquals("nothing left to unblock", 0, BlockPolicy.unblockEverySpelling(contacts, PADDED))
    }

    /**
     * The bug both phones ship: `isBlocked` is normalized, unblock is exact, so a block
     * set under one spelling and released under another survives. Asserted here as the
     * behaviour of the naive call, so the reason [BlockPolicy.unblockEverySpelling]
     * exists is visible rather than asserted in prose.
     */
    @Test
    fun `the naive exact unblock leaves the block standing`() {
        val contacts = seeded()
        contacts.block(PADDED)
        contacts.unblock(UNPADDED)                       // what both phones do
        assertTrue("still blocked — the shipped bug", BlockPolicy.isBlocked(contacts, PADDED))
        assertEquals(1, BlockPolicy.unblockEverySpelling(contacts, UNPADDED))
        assertFalse(BlockPolicy.isBlocked(contacts, PADDED))
    }

    @Test
    fun `blocking never deletes the contact or its identity`() {
        val contacts = seeded()
        val before = contacts.get(PADDED)
        assertNotNull(before)
        contacts.block(PADDED)
        val after = contacts.get(PADDED)
        assertNotNull("a blocked contact is still a contact", after)
        assertEquals(before!!.firstSeenMs, after!!.firstSeenMs)
        assertTrue(contacts.all().any { it.address == PADDED })
        assertFalse("…but not an offered one", contacts.visible().any { it.address == PADDED })
    }

    // ────────────────────────────────────────────── the decisions

    @Test
    fun `inbound drops a blocked sender and delivers everyone else`() {
        val contacts = seeded()
        contacts.block(PADDED)
        assertEquals(BlockPolicy.Inbound.DROP_BLOCKED, BlockPolicy.inbound(contacts, PADDED))
        assertEquals(BlockPolicy.Inbound.DROP_BLOCKED, BlockPolicy.inbound(contacts, URL_SAFE))
        assertEquals(BlockPolicy.Inbound.DELIVER, BlockPolicy.inbound(contacts, STRANGER))
    }

    @Test
    fun `outbound text and calls are both refused for a blocked peer`() {
        val contacts = seeded()
        contacts.block(PADDED)
        assertEquals(BlockPolicy.Outbound.REFUSE_BLOCKED, BlockPolicy.outgoingText(contacts, UNPADDED))
        assertEquals(BlockPolicy.Outbound.REFUSE_BLOCKED, BlockPolicy.outgoingCall(contacts, UNPADDED))
        assertEquals(BlockPolicy.Outbound.ALLOW, BlockPolicy.outgoingText(contacts, STRANGER))
        assertEquals(BlockPolicy.Outbound.ALLOW, BlockPolicy.outgoingCall(contacts, STRANGER))
    }

    @Test
    fun `a blocked peer is withheld from a conversation list, across spellings`() {
        val contacts = seeded()
        contacts.block(PADDED)
        val rows = listOf(PADDED, UNPADDED, URL_SAFE, STRANGER)
        val kept = BlockPolicy.filterConversations(rows, contacts) { it }
        assertEquals(listOf(STRANGER), kept)
    }

    @Test
    fun `with nobody blocked the conversation list is returned untouched`() {
        val contacts = seeded()
        val rows = listOf(PADDED, STRANGER)
        assertEquals(rows, BlockPolicy.filterConversations(rows, contacts) { it })
        assertEquals(emptyList<String>(), BlockPolicy.filterConversations(emptyList<String>(), contacts) { it })
    }
}
