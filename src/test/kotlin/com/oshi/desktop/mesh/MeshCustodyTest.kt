package com.oshi.desktop.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_MESH_CUSTODY_2026_10_29__ Holding somebody else's message until a peer appears.
 *
 * The bounds are the subject here, not the happy path. Held payloads are other people's
 * traffic sitting on the user's disk, so every limit is a promise — and a promise nobody
 * tests is a leak waiting for a busy mesh.
 */
class MeshCustodyTest {

    private val now = 1_770_000_000_000L

    private fun msg(id: String, seenBy: List<String> = emptyList(), from: String = "alice") = MeshMessage(
        id = id,
        type = "text_message",
        senderPublicKey = from,
        senderName = "A",
        recipientPublicKey = "carol",
        payload = "x",
        timestamp = now.toDouble(),
        hopCount = 1,
        maxHops = MeshProtocol.MAX_HOPS,
        seenBy = seenBy,
        platform = "desktop",
    )

    @Test
    fun `a message is held and offered to a peer that has not seen it`() {
        val c = MeshCustody()
        assertTrue(c.take(msg("m1"), ByteArray(100), now))
        assertEquals(1, c.size)
        assertEquals(100, c.byteCount)
        assertEquals(listOf("m1"), c.offersFor("bob", now).map { it.message.id })
    }

    /** A peer already in `seenBy`, or the original sender, is not offered it back. */
    @Test
    fun `a peer that has seen it, or sent it, is never offered it`() {
        val c = MeshCustody()
        c.take(msg("m1", seenBy = listOf("bob")), ByteArray(10), now)
        c.take(msg("m2", from = "dave"), ByteArray(10), now)
        assertTrue(c.offersFor("bob", now).none { it.message.id == "m1" })
        assertTrue(c.offersFor("dave", now).none { it.message.id == "m2" })
    }

    /** Custody ends on HAND-OFF: there is no mesh ack to wait for. */
    @Test
    fun `release frees the message and its bytes`() {
        val c = MeshCustody()
        c.take(msg("m1"), ByteArray(500), now)
        c.release("m1")
        assertEquals(0, c.size)
        assertEquals(0, c.byteCount)
    }

    /**
     * SIX HOURS, then it goes. A peer appearing after the window must not be handed a stale
     * packet, and the store must not grow for ever on a node nobody visits.
     */
    @Test
    fun `a message past its TTL is dropped rather than offered`() {
        val c = MeshCustody()
        c.take(msg("m1"), ByteArray(10), now)
        val justInside = now + MeshCustodyPolicy.MAX_AGE_MS - 1
        assertEquals(1, c.offersFor("bob", justInside).size)
        val justPast = now + MeshCustodyPolicy.MAX_AGE_MS
        assertTrue(c.offersFor("bob", justPast).isEmpty())
        assertEquals("and it is gone, not merely hidden", 0, c.size)
    }

    @Test
    fun `the count bound evicts oldest first`() {
        val c = MeshCustody()
        repeat(MeshCustodyPolicy.MAX_COUNT) { i -> c.take(msg("m$i"), ByteArray(1), now + i) }
        assertEquals(MeshCustodyPolicy.MAX_COUNT, c.size)
        c.take(msg("newcomer"), ByteArray(1), now + 10_000)
        assertEquals(MeshCustodyPolicy.MAX_COUNT, c.size)
        assertTrue("the newest must be kept", c.offersFor("bob", now + 10_000).any { it.message.id == "newcomer" })
        assertTrue("the oldest must be gone", c.offersFor("bob", now + 10_000).none { it.message.id == "m0" })
    }

    /**
     * A SINGLE OVERSIZED MESSAGE IS REFUSED OUTRIGHT. Evicting the whole store to fit one
     * packet would let one peer clear out two hundred deliverable messages.
     */
    @Test
    fun `a message larger than the entire budget is refused without evicting anything`() {
        val c = MeshCustody()
        c.take(msg("small"), ByteArray(100), now)
        assertFalse(c.take(msg("huge"), ByteArray(MeshCustodyPolicy.MAX_BYTES + 1), now))
        assertEquals("the small one must survive", 1, c.size)
        assertTrue(c.offersFor("bob", now).any { it.message.id == "small" })
    }

    @Test
    fun `the byte bound evicts until there is room`() {
        val c = MeshCustody()
        val half = MeshCustodyPolicy.MAX_BYTES / 2
        c.take(msg("a"), ByteArray(half), now)
        c.take(msg("b"), ByteArray(half), now + 1)
        assertEquals(2, c.size)
        c.take(msg("c"), ByteArray(half), now + 2)
        assertTrue("within the byte budget", c.byteCount <= MeshCustodyPolicy.MAX_BYTES)
        assertTrue("the newest is kept", c.offersFor("bob", now + 2).any { it.message.id == "c" })
    }

    @Test
    fun `the same message is not held twice`() {
        val c = MeshCustody()
        assertTrue(c.take(msg("m1"), ByteArray(10), now))
        assertFalse(c.take(msg("m1"), ByteArray(10), now))
        assertEquals(1, c.size)
    }

    /** The kill switch restores the previous forward-now-or-drop behaviour exactly. */
    @Test
    fun `a disabled store holds nothing`() {
        val c = MeshCustody(enabled = false)
        assertFalse(c.take(msg("m1"), ByteArray(10), now))
        assertEquals(0, c.size)
        assertTrue(c.offersFor("bob", now).isEmpty())
    }

    @Test
    fun `the policy bounds are the phones' numbers`() {
        assertEquals(6L * 60 * 60 * 1000, MeshCustodyPolicy.MAX_AGE_MS)
        assertEquals(200, MeshCustodyPolicy.MAX_COUNT)
        assertEquals(4 * 1024 * 1024, MeshCustodyPolicy.MAX_BYTES)
        assertFalse("oversized is refused, not accommodated", MeshCustodyPolicy.hasRoom(0, 0, MeshCustodyPolicy.MAX_BYTES + 1))
        assertTrue(MeshCustodyPolicy.hasRoom(0, 0, MeshCustodyPolicy.MAX_BYTES))
        assertFalse(MeshCustodyPolicy.hasRoom(MeshCustodyPolicy.MAX_COUNT, 0, 1))
    }
}
