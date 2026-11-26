package com.oshi.desktop.net

import com.oshi.messenger.network.v2.RatchetHealSignal
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_RATCHET_HEAL_2026_10_29__ The heal policy as this client applies it, and the
 * persistence that makes the policy mean anything.
 *
 * `RatchetHealSignal` itself is COMPILED from the phones' source, so its encode/parse and its
 * numbers are not re-tested here — that would be testing Android's file through a second
 * door. What is this client's own is the state the policy reads: the first-evidence stamp and
 * the last-sent stamp, both of which must survive a restart or the policy is decorative.
 */
class RatchetHealTest {

    private val dir: File = Files.createTempDirectory("oshi-heal").toFile()
    private val file = File(dir, "router.json")
    private var state = RouterState(file)

    @After fun tearDown() { dir.deleteRecursively() }

    private val peer = "PEER/key/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb="

    /**
     * FIRST evidence, not latest. The non-initiator waits a full cooldown from the moment the
     * breakage was first seen; re-stamping on every undecryptable envelope in a long broken
     * batch would push that deadline forward for ever and the quiet side would never act.
     */
    @Test
    fun `the evidence stamp is recorded once and not moved`() {
        val first = state.healEvidenceAt(peer, 1_000L)
        assertEquals(1_000L, first)
        assertEquals("a later envelope must not move it", 1_000L, state.healEvidenceAt(peer, 9_000L))
    }

    /** Both stamps survive a restart, or the cooldown and the tie-break bound nothing. */
    @Test
    fun `the stamps are persisted`() {
        state.healEvidenceAt(peer, 1_000L)
        state.noteHealSent(peer, 2_000L)
        state.flush()

        val reopened = RouterState(file)
        assertEquals(2_000L, reopened.healSentAt(peer))
        // The evidence was cleared by the send, so the next breakage starts a new episode.
        assertEquals("a new episode stamps anew", 5_000L, reopened.healEvidenceAt(peer, 5_000L))
    }

    /**
     * Sending CLEARS the evidence. Without this the next breakage would be measured against
     * an old stamp, so the non-initiator would act immediately for ever after one slow
     * repair — the symmetric loop, re-introduced by bookkeeping.
     */
    @Test
    fun `a send clears the evidence so the next episode is measured afresh`() {
        state.healEvidenceAt(peer, 1_000L)
        state.noteHealSent(peer, 2_000L)
        assertEquals(10_000L, state.healEvidenceAt(peer, 10_000L))
    }

    @Test
    fun `an untouched peer has no stamps`() {
        assertNull(state.healSentAt(peer))
        assertTrue(state.healSentAt("someone-else") == null)
    }

    /** The cooldown is read from the shared policy, applied to this client's stamps. */
    @Test
    fun `the shared cooldown gates a second acknowledgement`() {
        state.noteHealSent(peer, 1_000_000L)
        val last = state.healSentAt(peer)
        assertFalse(
            "a second ack inside the cooldown",
            RatchetHealSignal.Policy.maySend(last, 1_000_000L + RatchetHealSignal.Policy.COOLDOWN_MS - 1),
        )
        assertTrue(
            RatchetHealSignal.Policy.maySend(last, 1_000_000L + RatchetHealSignal.Policy.COOLDOWN_MS),
        )
        assertTrue("a peer we never answered may be answered", RatchetHealSignal.Policy.maySend(null, 0L))
    }

    /**
     * THE TIE-BREAK IS SHARED SOURCE, and this pins that the desktop gets the same answer the
     * phones do for the same pair. Exactly one side of any pair is the initiator — if both
     * were, both would delete and both would send, which is the loop.
     */
    @Test
    fun `exactly one side of a pair is the heal initiator`() {
        val a = "AAAA"
        val b = "BBBB"
        assertTrue(RatchetHealSignal.Policy.isHealInitiator(a, b))
        assertFalse(RatchetHealSignal.Policy.isHealInitiator(b, a))
        assertFalse("nobody initiates against themselves", RatchetHealSignal.Policy.isHealInitiator(a, a))
    }

    /** A heal payload is recognisable, which is what keeps it out of a bubble. */
    @Test
    fun `a heal payload parses and ordinary text does not`() {
        assertNotNull(RatchetHealSignal.parse(RatchetHealSignal.encode()))
        assertNull(RatchetHealSignal.parse("hello"))
        assertNull(RatchetHealSignal.parse("__RATCHET_HEAL__v1 but not really"))
        assertTrue(RatchetHealSignal.isHealMsgId(RatchetHealSignal.makeMsgId()))
        assertFalse(RatchetHealSignal.isHealMsgId("M-1"))
    }
}
