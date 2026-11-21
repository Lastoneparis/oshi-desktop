package com.oshi.desktop.lora

import com.oshi.desktop.store.LoRaNodeBindingStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "One person, one conversation" — the manual link, and the four things it must refuse.
 *
 * Port of `LoRaNodeIdentityMapTests` (iOS, 2026-10-28). The rules are not arbitrary: each
 * refusal is a case where honouring a person's assertion would overwrite evidence or
 * resolve a contradiction by coin flip, and a link that did either would make a
 * conversation's name depend on who pressed what last.
 */
class LoRaManualLinkTest {

    private val t0 = 1_761_600_000_000L
    private val alice = "AAAAbbbbCCCCddddEEEEffffGGGGhhhhIIIIjjjjKKK="
    private val bob = "ZZZZyyyyXXXXwwwwVVVVuuuuTTTTssssRRRRqqqqPPP="
    private val nodeA = 0x766eb396u
    private val nodeB = 0x4b88a86du

    @After
    fun tearDown() {
        LoRaNodeBindings.adoptRawTextIntoContactThread = false
    }

    // ---------------------------------------------------------------- what it buys

    /**
     * THE WHOLE POINT. The adopt switch is OFF — as it is on all three clients — and a
     * manual link still routes. That is not a hole in the switch: the switch governs
     * whether the APP may INFER an identity from a learned binding. A person naming their
     * own radio is not an inference.
     */
    @Test
    fun `a manual link routes even with the adopt switch off`() {
        val m = LoRaNodeBindings()
        assertEquals("lora!766eb396", m.conversationKey(nodeA))

        assertEquals(LoRaNodeBindings.LearnResult.BOUND, m.link(nodeA, alice, t0))

        assertFalse(LoRaNodeBindings.adoptRawTextIntoContactThread)
        assertEquals(alice, m.conversationKey(nodeA))
        assertEquals(alice, m.manualKey(nodeA))
    }

    /**
     * The inverse holds absolutely. Nothing is ever guessed: a node nobody has named and
     * nothing has proved keeps its own thread.
     */
    @Test
    fun `an unlinked node is never guessed`() {
        val m = LoRaNodeBindings()
        m.link(nodeA, alice, t0)
        assertEquals("lora!4b88a86d", m.conversationKey(nodeB))
        assertNull(m.manualKey(nodeB))
    }

    /** A manual link is not proof, and must not be reported as one. */
    @Test
    fun `a manual link is not a proved binding`() {
        val m = LoRaNodeBindings()
        m.link(nodeA, alice, t0)
        assertNull("boundKey is for what the ratchet proved", m.boundKey(nodeA))
        assertTrue(m.binding(nodeA)!!.isManual)
    }

    // ---------------------------------------------------------------- what it refuses

    /**
     * The ratchet outranks a recollection. Silently replacing proof with assertion is how a
     * conversation acquires the wrong name.
     */
    @Test
    fun `a person may not override what the ratchet proved`() {
        val m = LoRaNodeBindings()
        assertEquals(LoRaNodeBindings.LearnResult.BOUND, m.bind(nodeA, alice, t0))

        assertEquals(LoRaNodeBindings.LearnResult.CONFLICT, m.link(nodeA, bob, t0 + 1))
        assertEquals("the proof is intact", alice, m.boundKey(nodeA))
        assertNull("and no manual link was recorded", m.manualKey(nodeA))
    }

    /** Naming the identity the ratchet already proved is a no-op, not a conflict. */
    @Test
    fun `naming the identity already proved changes nothing`() {
        val m = LoRaNodeBindings()
        m.bind(nodeA, alice, t0)
        assertEquals(LoRaNodeBindings.LearnResult.UNCHANGED, m.link(nodeA, alice, t0 + 1))
        assertFalse("still proof, not an assertion", m.binding(nodeA)!!.isManual)
    }

    /**
     * Vouching cannot resolve contradictory evidence. An ambiguous node routes nowhere, and
     * a person saying "it's her" does not tell us which of the two claims was the lie.
     */
    @Test
    fun `a person may not rescue an ambiguous node`() {
        val m = LoRaNodeBindings()
        m.bind(nodeA, alice, t0)
        m.bind(nodeA, bob, t0 + 1)            // rule 1: one node, two identities
        assertTrue(m.isAmbiguous(nodeA))

        assertEquals(LoRaNodeBindings.LearnResult.IGNORED_AMBIGUOUS, m.link(nodeA, alice, t0 + 2))
        assertNull(m.manualKey(nodeA))
        assertEquals("lora!766eb396", m.conversationKey(nodeA))
    }

    @Test
    fun `a key that is not an identity key is refused`() {
        val m = LoRaNodeBindings()
        assertEquals(LoRaNodeBindings.LearnResult.REJECTED, m.link(nodeA, "nope", t0))
        assertEquals(LoRaNodeBindings.LearnResult.REJECTED, m.link(nodeA, "", t0))
        assertEquals("lora!766eb396", m.conversationKey(nodeA))
    }

    // ---------------------------------------------------------------- corrections

    @Test
    fun `a person may correct their own link`() {
        val m = LoRaNodeBindings()
        m.link(nodeA, alice, t0)
        assertEquals(LoRaNodeBindings.LearnResult.BOUND, m.link(nodeA, bob, t0 + 1))
        assertEquals(bob, m.manualKey(nodeA))
    }

    @Test
    fun `relinking the same identity is unchanged`() {
        val m = LoRaNodeBindings()
        m.link(nodeA, alice, t0)
        assertEquals(LoRaNodeBindings.LearnResult.UNCHANGED, m.link(nodeA, alice, t0 + 1))
    }

    /**
     * DELIBERATELY not `bind`'s rule 2. That rule resolves contradictory evidence; two
     * manual links to one contact are not a contradiction, they are someone with two radios.
     */
    @Test
    fun `two radios may be linked to one person`() {
        val m = LoRaNodeBindings()
        assertEquals(LoRaNodeBindings.LearnResult.BOUND, m.link(nodeA, alice, t0))
        assertEquals(LoRaNodeBindings.LearnResult.BOUND, m.link(nodeB, alice, t0 + 1))
        assertFalse(m.isAmbiguous(nodeA))
        assertFalse(m.isAmbiguous(nodeB))
        assertEquals(alice, m.conversationKey(nodeA))
        assertEquals(alice, m.conversationKey(nodeB))
    }

    // ---------------------------------------------------------------- undo

    @Test
    fun `unlink sends the thread back to its own lora key`() {
        val m = LoRaNodeBindings()
        m.link(nodeA, alice, t0)
        assertTrue(m.unlink(nodeA))
        assertEquals("lora!766eb396", m.conversationKey(nodeA))
        assertNull(m.binding(nodeA))
    }

    /**
     * Unlink never discards EVIDENCE. A button that quietly dropped a proved binding would
     * make the map depend on who pressed what last; `forget` is the explicit way out.
     */
    @Test
    fun `unlink does not remove a proved binding`() {
        val m = LoRaNodeBindings()
        m.bind(nodeA, alice, t0)
        assertFalse(m.unlink(nodeA))
        assertEquals(alice, m.boundKey(nodeA))
    }

    @Test
    fun `unlink on an unknown node is false, not an exception`() {
        assertFalse(LoRaNodeBindings().unlink(nodeA))
    }

    @Test
    fun `manualLinks lists the manual ones and nothing else`() {
        val m = LoRaNodeBindings()
        m.link(nodeA, alice, t0)
        m.bind(nodeB, bob, t0)
        assertEquals(mapOf("766eb396" to alice), m.manualLinks())
    }

    // ---------------------------------------------------------------- persistence

    /**
     * A manual link that died at exit would be useless: nobody re-picks their own radio out
     * of a list on every launch. Before this store, `snapshot()`/`restore()` had no caller
     * at all on this client.
     */
    @Test
    fun `a manual link survives a restart`() {
        val f = File.createTempFile("lora-bindings", ".json")
        f.delete()
        try {
            val key = ByteArray(32) { it.toByte() }
            val store = LoRaNodeBindingStore(f, key)

            val first = LoRaNodeBindings()
            first.link(nodeA, alice, t0)
            store.save(first.snapshot())

            val second = LoRaNodeBindings().also { it.restore(LoRaNodeBindingStore(f, key).load()) }
            assertEquals(alice, second.manualKey(nodeA))
            assertEquals(alice, second.conversationKey(nodeA))
        } finally {
            f.delete()
        }
    }

    /**
     * ABSENT MEANS PROVED. A binding written before manual links existed was proof, so it
     * must decode as proof — if it came back manual, an `unlink` button would start
     * discarding evidence, and if `false` were written for "unknown" the two cases would be
     * indistinguishable.
     */
    @Test
    fun `a binding stored before manual links existed reads back as proved`() {
        val f = File.createTempFile("lora-bindings", ".json")
        try {
            f.writeText("""{"v":1,"bindings":{"766eb396":{"publicKey":"$alice","learnedAtMillis":$t0,"ambiguous":false}}}""")
            val restored = LoRaNodeBindings().also { it.restore(LoRaNodeBindingStore(f, null).load()) }

            assertNull("not asserted", restored.binding(nodeA)!!.manual)
            assertFalse(restored.binding(nodeA)!!.isManual)
            assertEquals("proved", alice, restored.boundKey(nodeA))
            assertNull(restored.manualKey(nodeA))
            // And because it is proof, a person cannot overwrite it.
            assertEquals(LoRaNodeBindings.LearnResult.CONFLICT, restored.link(nodeA, bob, t0 + 1))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `the stored file does not leave the identity in the clear when a key is given`() {
        val f = File.createTempFile("lora-bindings", ".json")
        try {
            val store = LoRaNodeBindingStore(f, ByteArray(32) { 7 })
            val m = LoRaNodeBindings().also { it.link(nodeA, alice, t0) }
            store.save(m.snapshot())
            val onDisk = f.readText()
            assertFalse("the contact's key must not be readable on disk", onDisk.contains(alice))
        } finally {
            f.delete()
        }
    }

    /**
     * A binding map is a convenience, never the only copy of anything, so an unreadable one
     * must not stop the client from starting.
     */
    @Test
    fun `an unreadable store is empty, not fatal`() {
        val f = File.createTempFile("lora-bindings", ".json")
        try {
            f.writeText("{ this is not json")
            assertEquals(emptyMap<String, LoRaNodeBindings.Binding>(), LoRaNodeBindingStore(f, null).load())
        } finally {
            f.delete()
        }
    }

    @Test
    fun `a missing store is empty`() {
        assertEquals(
            emptyMap<String, LoRaNodeBindings.Binding>(),
            LoRaNodeBindingStore(File("/no/such/lora-bindings.json"), null).load(),
        )
    }
}
