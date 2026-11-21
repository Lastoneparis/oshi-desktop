package com.oshi.desktop.ui.state

import com.oshi.desktop.msg.AliveCheckInPayload
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_1_6_PARITY__ The safety check-in state, derived from a conversation's own
 * `💓ALIVE💓` rows — including the exact strings the phones put on the wire.
 */
class AliveStateTest {

    private val t0 = 1_763_480_000_000L
    private var n = 0

    private fun row(content: String, fromMe: Boolean, atMs: Long) = Message(
        id = "m${n++}", conversationId = "peer", senderAddress = if (fromMe) "me" else "peer",
        recipientAddress = if (fromMe) "peer" else "me", fromMe = fromMe, content = content,
        sentAtMs = atMs, sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
    )

    @Test fun `decodes the payload Android and iOS send`() {
        // AliveCheckIn.kt / AliveCheckIn.swift doc example, byte for byte.
        val wire = "💓ALIVE💓{\"v\":1,\"t\":\"ok\",\"at\":1762300123.456,\"iv\":86400}"
        val e = AliveCheckInPayload.decode(wire)
        assertNotNull(e)
        assertEquals(AliveCheckInPayload.Kind.CONFIRMED, e!!.kind)
        assertEquals(86_400L, e.intervalSeconds)
        // And the start a phone sends when the switch is turned on.
        val on = AliveCheckInPayload.decode("💓ALIVE💓{\"v\":1,\"t\":\"on\",\"at\":1762300000,\"iv\":172800}")
        assertEquals(AliveCheckInPayload.Kind.ENABLED, on!!.kind)
        // Without the variation selector (both phones accept it).
        assertNotNull(AliveCheckInPayload.decode("💓ALIVE💓{\"v\":1,\"t\":\"off\",\"at\":1762300000,\"iv\":0}"))
    }

    @Test fun `what this window sends is what the phones decode`() {
        val wire = AliveCheckInPayload.Event(AliveCheckInPayload.Kind.ENABLED, 1_762_300_000.5, 86_400).encode()
        assertTrue(wire.startsWith("💓ALIVE💓{"))
        val o = org.json.JSONObject(wire.removePrefix("💓ALIVE💓"))
        assertEquals(1, o.getInt("v"))
        assertEquals("on", o.getString("t"))
        assertEquals(1_762_300_000.5, o.getDouble("at"), 0.0)
        assertEquals(86_400L, o.getLong("iv"))
    }

    @Test fun `newest event per side wins, and off clears that side only`() {
        val s = AliveState.from(listOf(
            row(AliveCheckInPayload.Event(AliveCheckInPayload.Kind.ENABLED, t0 / 1000.0, 86_400).encode(), fromMe = true, atMs = t0),
            row(AliveCheckInPayload.Event(AliveCheckInPayload.Kind.CONFIRMED, (t0 + 3_600_000) / 1000.0, 86_400).encode(), fromMe = true, atMs = t0 + 3_600_000),
            row(AliveCheckInPayload.Event(AliveCheckInPayload.Kind.ENABLED, t0 / 1000.0, 172_800).encode(), fromMe = false, atMs = t0),
            row(AliveCheckInPayload.Event(AliveCheckInPayload.Kind.DISABLED, (t0 + 10_000) / 1000.0, 0).encode(), fromMe = false, atMs = t0 + 10_000),
        ))
        assertEquals(t0 + 3_600_000, s.mine!!.lastConfirmedMs)
        assertEquals(t0 + 3_600_000 + 86_400_000, s.mine!!.dueAtMs)
        assertNull(s.theirs)
    }

    @Test fun `overdue is measured from the last confirmation`() {
        val s = AliveState.from(listOf(
            row(AliveCheckInPayload.Event(AliveCheckInPayload.Kind.CONFIRMED, t0 / 1000.0, 3_600).encode(), fromMe = false, atMs = t0),
        ))
        val lane = s.theirs!!
        assertFalse(lane.overdue(t0 + 3_599_000))
        assertTrue(lane.overdue(t0 + 3_601_000))
    }

    @Test fun `a peer cannot postdate a confirmation into the future`() {
        val s = AliveState.from(listOf(
            row(AliveCheckInPayload.Event(AliveCheckInPayload.Kind.CONFIRMED, (t0 + 30L * 86_400_000) / 1000.0, 3_600).encode(), fromMe = false, atMs = t0),
        ))
        assertEquals(t0, s.theirs!!.lastConfirmedMs)
    }

    @Test fun `prose and old translated sentences are not state`() {
        val s = AliveState.from(listOf(row("💓 Confirmed alive", fromMe = false, atMs = t0), row("hello", fromMe = true, atMs = t0)))
        assertEquals(AliveState.NONE, s)
    }
}
