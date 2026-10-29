package com.oshi.desktop.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_LORA_REFUSAL_2026_10_29__ Refusing a send the radio would swallow.
 *
 * Before this, every desktop send asked only "is a link open?". `LoRaLink.send` is honest
 * that a true return "means the bytes left this process" — nothing above it was, so a
 * muted radio or one with no region accepted every message, transmitted none, and the app
 * said sent.
 */
class LoRaSendRefusalTest {

    private fun state(region: Long?, tx: Boolean): LoRaRadioState {
        val s = LoRaRadioState()
        val lora = ProtoWriter().apply {
            region?.let { varint(7, it) }
            varint(9, if (tx) 1 else 0)
        }.data
        s.observeFromRadio(ProtoWriter().bytes(5, ProtoWriter().bytes(6, lora).data).data)
        return s
    }

    @Test
    fun `no radio is the first refusal`() {
        assertEquals(
            LoRaSendRefusal.NOT_CONNECTED,
            LoRaSendRefusal.forSend(attached = false, state = state(3, true)),
        )
    }

    @Test
    fun `a muted radio is refused instead of swallowing the message`() {
        assertEquals(
            LoRaSendRefusal.RADIO_MUTED,
            LoRaSendRefusal.forSend(attached = true, state = state(3, tx = false)),
        )
    }

    @Test
    fun `no region is refused`() {
        assertEquals(
            LoRaSendRefusal.NO_REGION,
            LoRaSendRefusal.forSend(attached = true, state = state(null, tx = true)),
        )
        assertEquals(
            LoRaSendRefusal.NO_REGION,
            LoRaSendRefusal.forSend(attached = true, state = state(0, tx = true)),
        )
    }

    @Test
    fun `a healthy radio is not refused`() {
        assertNull(LoRaSendRefusal.forSend(attached = true, state = state(3, tx = true)))
    }

    @Test
    fun `SILENCE is not a refusal`() {
        // A radio mid-handshake has said nothing about itself. Refusing on silence would
        // block the first message of every session, which is worse than the problem: the
        // states this guards against are reported, and an unreported one is not evidence.
        assertNull("nothing read yet", LoRaSendRefusal.forSend(attached = true, state = LoRaRadioState()))
        assertNull("no state object at all", LoRaSendRefusal.forSend(attached = true, state = null))
    }

    @Test
    fun `muted outranks no region, because fixing the region would not help`() {
        // Order matters for the message shown. A radio that is BOTH muted and regionless
        // should say "muted" — setting a region on a muted radio changes nothing, and
        // sending somebody to the wrong control is worse than a vague error.
        assertEquals(
            LoRaSendRefusal.RADIO_MUTED,
            LoRaSendRefusal.forSend(attached = true, state = state(null, tx = false)),
        )
    }

    @Test
    fun `every refusal says something a person can act on`() {
        for (r in LoRaSendRefusal.entries) {
            assertTrue("$r has no reason", r.reason.isNotBlank())
            assertTrue("$r should read as a sentence", r.reason.trim().endsWith("."))
            assertFalse("$r must not name a protobuf field at the user", r.reason.contains("field"))
        }
    }

    @Test
    fun `the two states an admin frame repairs are the two it must NOT be refused for`() {
        // The carve-out that keeps an unconfigured radio configurable. RADIO_MUTED and
        // NO_REGION are repaired BY sending an admin frame — tx_enabled back on, a region
        // set — so if admin went through this gate, a radio in either state could never be
        // fixed from the app that noticed. Admin is unicast to our own node over the cable
        // and does not need the transmitter.
        val repairedByAdmin = setOf(LoRaSendRefusal.RADIO_MUTED, LoRaSendRefusal.NO_REGION)
        for (r in repairedByAdmin) {
            assertFalse(
                "$r must not be marked fixable by a control other than the admin write",
                r == LoRaSendRefusal.RADIO_MUTED && r.fixableHere,
            )
        }
        // NO_REGION is fixable here, because the region picker IS on this pane.
        assertTrue(LoRaSendRefusal.NO_REGION.fixableHere)
        // RADIO_MUTED is not: nothing in this app turns tx back on today, and claiming
        // otherwise would send somebody looking for a control that does not exist.
        assertFalse(LoRaSendRefusal.RADIO_MUTED.fixableHere)
    }
}
