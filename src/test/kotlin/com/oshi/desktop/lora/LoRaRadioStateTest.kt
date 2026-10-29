package com.oshi.desktop.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_LORA_RADIO_STATE_2026_10_29__ The region a node is actually on, and whether it
 * will transmit at all.
 *
 * The first payload below was CAPTURED from a ThinkNode M1 on 2026-10-29, not invented. It
 * matters which: the class makes two OPPOSITE readings of protobuf's "absent means
 * default" — region absent is UNSET, tx_enabled absent is ENABLED — and a fixture I wrote
 * myself would have agreed with whichever reading I picked. The capture settles what the
 * firmware actually puts on the wire.
 */
class LoRaRadioStateTest {

    /** `FromRadio{ config: Config{ lora: <payload> } }` — config is 5, Config.lora is 6. */
    private fun burst(lora: ByteArray): ByteArray =
        ProtoWriter().bytes(5, ProtoWriter().bytes(6, lora).data).data

    /** Captured live: region 3 (EU 868), hop limit 3, tx_enabled true, tx power 27. */
    private val captured = byteArrayOf(
        0x08, 0x01, 0x20, 0x0b, 0x28, 0x05, 0x38, 0x03,
        0x40, 0x03, 0x48, 0x01, 0x50, 0x1b, 0x68, 0x01,
        0xc0.toByte(), 0x06, 0x01,
    )

    @Test
    fun `the captured config reads as EU 868 and transmitting`() {
        val s = LoRaRadioState()
        assertTrue(s.observeFromRadio(burst(captured)))
        assertTrue(s.known)
        assertEquals(3L, s.regionCode)
        assertEquals(LoRaAdmin.Region.EU868.wire, s.regionCode)
        assertTrue(s.transmitEnabled)
        assertFalse(s.willNotTransmit)
        assertNull("a healthy radio must not produce a warning", s.blockedReason)
    }

    @Test
    fun `tx_enabled false is the muted radio iOS warns about`() {
        // "A node with tx disabled looks identical to a healthy one everywhere except
        // here." Field 9 set to 0 explicitly.
        val muted = byteArrayOf(0x38, 0x03, 0x48, 0x00)
        val s = LoRaRadioState()
        s.observeFromRadio(burst(muted))
        assertFalse(s.transmitEnabled)
        assertTrue(s.willNotTransmit)
        assertTrue(s.blockedReason!!.contains("muted"))
    }

    @Test
    fun `tx_enabled ABSENT means enabled, and that is the opposite of the region rule`() {
        // The deliberate asymmetry. Protobuf omits defaults, so absent and zero are the
        // same bytes — none — and the two fields need opposite readings of it. Reading an
        // absent tx_enabled as "muted" would paint every healthy radio as broken, and a
        // warning that always fires is one nobody reads. MEASURED on a real node: field 9
        // IS on the wire there, so this default is a belt rather than the braces.
        val noTxField = byteArrayOf(0x38, 0x03)   // region only
        val s = LoRaRadioState()
        s.observeFromRadio(burst(noTxField))
        assertTrue("absent tx_enabled must read as ENABLED", s.transmitEnabled)
        assertFalse(s.willNotTransmit)
    }

    @Test
    fun `region ABSENT means UNSET, which is a radio that will not transmit`() {
        val noRegion = byteArrayOf(0x48, 0x01)    // tx_enabled true, no region
        val s = LoRaRadioState()
        s.observeFromRadio(burst(noRegion))
        assertNull(s.regionCode)
        assertTrue("no region is a radio that cannot legally transmit", s.willNotTransmit)
        assertTrue(s.blockedReason!!.contains("region"))
    }

    @Test
    fun `region explicitly zero is also UNSET`() {
        val s = LoRaRadioState()
        s.observeFromRadio(burst(byteArrayOf(0x38, 0x00, 0x48, 0x01)))
        assertEquals(0L, s.regionCode)
        assertTrue(s.willNotTransmit)
    }

    @Test
    fun `nothing is claimed before a config burst has been seen`() {
        // `known` exists so the pane does not warn about a radio it has not heard from.
        val s = LoRaRadioState()
        assertFalse(s.known)
        assertFalse("silence is not a muted radio", s.willNotTransmit)
        assertNull(s.blockedReason)
    }

    @Test
    fun `a packet or a node info is not a config`() {
        val s = LoRaRadioState()
        assertFalse(s.observeFromRadio(ProtoWriter().bytes(2, byteArrayOf(1, 2, 3)).data))
        assertFalse(s.observeFromRadio(ProtoWriter().bytes(4, byteArrayOf(1, 2, 3)).data))
        assertFalse(s.known)
    }

    @Test
    fun `detaching forgets what the old node said`() {
        val s = LoRaRadioState()
        s.observeFromRadio(burst(byteArrayOf(0x38, 0x03, 0x48, 0x00)))
        assertTrue(s.willNotTransmit)
        s.clear()
        assertFalse("the next radio is a different radio", s.known)
        assertTrue(s.transmitEnabled)
        assertNull(s.regionCode)
    }
}
