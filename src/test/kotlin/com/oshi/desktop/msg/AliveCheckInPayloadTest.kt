package com.oshi.desktop.msg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __ALIVE_CHECK_IN_2026_11_04__ The desktop's half of the liveness check-in.
 *
 * This is the THIRD implementation of one wire format, so the assertions that
 * matter most are the ones that pin it to the other two rather than to itself:
 * the sentinel's exact codepoints, seconds-not-millis, and which events are
 * accepted. A divergence in any of those is invisible on all three screens and
 * shows up only as a conversation that is green on a phone and red here.
 */
class AliveCheckInPayloadTest {

    private val day = 86_400L
    private val t0 = 1_762_300_000_000L

    private fun event(kind: AliveCheckInPayload.Kind, atMillis: Long = t0, iv: Long = day) =
        AliveCheckInPayload.Event(kind, atMillis / 1000.0, if (kind == AliveCheckInPayload.Kind.DISABLED) 0L else iv)

    @Test
    fun `an event round-trips through its own encoding`() {
        val back = AliveCheckInPayload.decode(event(AliveCheckInPayload.Kind.CONFIRMED).encode())
        assertNotNull(back)
        assertEquals(AliveCheckInPayload.Kind.CONFIRMED, back!!.kind)
        assertEquals(day, back.intervalSeconds)
        assertEquals(t0 / 1000.0, back.atUnix, 0.0005)
    }

    @Test
    fun `the wire shape is the one the phones parse`() {
        val wire = event(AliveCheckInPayload.Kind.ENABLED).encode()
        assertTrue(wire.startsWith(ControlPrefix.ALIVE_CHECK_IN))
        val o = JSONObject(ControlPrefix.strip(wire))
        assertEquals(1, o.getInt("v"))
        assertEquals("on", o.getString("t"))
        assertEquals(day, o.getLong("iv"))
        // SECONDS, not millis. Both phones write `at = millis / 1000.0`; a millis
        // value here would land every check-in in the year 57000 on their side
        // and read as permanently confirmed.
        assertEquals(t0 / 1000.0, o.getDouble("at"), 0.0005)
    }

    @Test
    fun `the sentinel is the same two codepoints the phones match on`() {
        // Written out by hand, not read from the constant: the point is that
        // THIS string is what the other clients compare against, and a test
        // that reads the constant cannot notice the constant changing.
        assertEquals("💓ALIVE💓", ControlPrefix.ALIVE_CHECK_IN)
        // No U+FE0F. The variation-selector skew that forced the whole variant
        // table exists because `🛡️` carries one and half the wire omits it; this
        // sentinel has exactly one spelling everywhere.
        assertFalse(ControlPrefix.ALIVE_CHECK_IN.contains('️'))
    }

    @Test
    fun `the catalog claims it once, as rendered`() {
        val hits = ControlPrefix.catalog.filter { it.prefix == ControlPrefix.ALIVE_CHECK_IN }
        assertEquals(1, hits.size)
        assertEquals(ControlPrefix.Kind.RENDERED, hits.first().kind)
        // And it is NOT in the no-push list: this is the one message whose
        // absence is the alarm.
        assertFalse(ControlPrefix.silentNoPushPrefixes.contains(ControlPrefix.ALIVE_CHECK_IN))
    }

    @Test
    fun `a disabled event carries no interval`() {
        val off = event(AliveCheckInPayload.Kind.DISABLED)
        assertEquals(0L, off.intervalSeconds)
        assertFalse(off.isConfirmation)
    }

    @Test
    fun `an unreadable payload is refused rather than guessed`() {
        val p = ControlPrefix.ALIVE_CHECK_IN
        // No timestamp: the event cannot be placed in time, and "now" would be a
        // fabricated fresh confirmation.
        assertNull(AliveCheckInPayload.decode(p + """{"v":1,"t":"ok","iv":86400}"""))
        assertNull(AliveCheckInPayload.decode(p + """{"v":2,"t":"ok","at":1,"iv":1}"""))
        assertNull(AliveCheckInPayload.decode(p + """{"v":1,"t":"maybe","at":1,"iv":1}"""))
        assertNull(AliveCheckInPayload.decode("hello"))
        assertFalse(AliveCheckInPayload.isEvent("hello"))
    }

    @Test
    fun `a corrupt body still gets a sentence, and not the stopped one`() {
        // Claiming a schedule ended when we cannot tell is the reading that
        // costs something — the other side would stop expecting confirmations.
        assertEquals(AliveCheckInPayload.Kind.CONFIRMED, AliveCheckInPayload.summaryKind(ControlPrefix.ALIVE_CHECK_IN + "}{"))
        assertEquals(AliveCheckInPayload.Kind.ENABLED, AliveCheckInPayload.summaryKind(event(AliveCheckInPayload.Kind.ENABLED).encode()))
        assertEquals(AliveCheckInPayload.Kind.DISABLED, AliveCheckInPayload.summaryKind(event(AliveCheckInPayload.Kind.DISABLED).encode()))
        assertEquals(AliveCheckInPayload.Kind.CONFIRMED, AliveCheckInPayload.summaryKind(event(AliveCheckInPayload.Kind.CONFIRMED).encode()))
    }

    @Test
    fun `a check-in never renders as prose, which is what the catalog entry buys`() {
        val wire = event(AliveCheckInPayload.Kind.CONFIRMED).encode()
        assertTrue(ControlPrefix.isControl(wire))
        // Prose would mean the raw JSON printed in the conversation — the exact
        // failure __DESKTOP_CATALOG_DRIFT_2026_10_27__ describes.
        assertTrue(ControlPayloadRouter.classify(wire) is ControlEvent.Foreign)
    }

    @Test
    fun `a sentinel in the middle of prose is prose`() {
        val typed = "we should add a ${ControlPrefix.ALIVE_CHECK_IN} marker"
        assertFalse(AliveCheckInPayload.isEvent(typed))
    }

    // ── The policy ──

    @Test
    fun `a clock from the future cannot keep a conversation green`() {
        val declared = t0 + 7L * 24 * 3_600 * 1_000
        assertEquals(t0, AliveCheckInPayload.Policy.confirmedAtMillis(declared, t0))
    }

    @Test
    fun `a clock years behind tells us nothing about time`() {
        assertEquals(t0, AliveCheckInPayload.Policy.confirmedAtMillis(t0 - 400L * 24 * 3_600 * 1_000, t0))
        assertEquals(t0, AliveCheckInPayload.Policy.confirmedAtMillis(0L, t0))
    }

    @Test
    fun `an ordinary queue delay is honoured, because that lateness is the point`() {
        val declared = t0 - 6 * 3_600 * 1_000
        assertEquals(declared, AliveCheckInPayload.Policy.confirmedAtMillis(declared, t0))
    }

    @Test
    fun `a replay changes nothing and a late older event cannot pull a confirmation back`() {
        assertFalse(AliveCheckInPayload.Policy.accepts(100.0, 100.0))
        assertFalse(AliveCheckInPayload.Policy.accepts(99.9, 100.0))
        assertTrue(AliveCheckInPayload.Policy.accepts(100.1, 100.0))
    }

    @Test
    fun `overdue is decided on absolute instants, at the deadline and not after it`() {
        val due = AliveCheckInPayload.Policy.dueAtMillis(t0, day)
        assertFalse(AliveCheckInPayload.Policy.isOverdue(t0, day, due - 1))
        assertTrue(AliveCheckInPayload.Policy.isOverdue(t0, day, due))
        // Nothing configured is never overdue.
        assertFalse(AliveCheckInPayload.Policy.isOverdue(0L, day, due))
        assertFalse(AliveCheckInPayload.Policy.isOverdue(t0, 0L, due))
    }

    // ── The bounds a sender must respect ──

    @Test
    fun `the interval bounds are the same numbers the phones enforce`() {
        // Restated here rather than imported, because the phones are where they are
        // enforced and this client must not be able to announce a schedule they would
        // refuse to display.
        assertEquals(3_600L, AliveCheckInPayload.MIN_INTERVAL_SECONDS)
        assertEquals(2_592_000L, AliveCheckInPayload.MAX_INTERVAL_SECONDS)
        assertEquals(86_400L, AliveCheckInPayload.DEFAULT_INTERVAL_SECONDS)
    }

    @Test
    fun `an event this client builds is one the phones accept`() {
        // The round trip that matters is not ours: it is that our bytes decode under
        // the rules the other two apply. Same version, a kind they know, a positive
        // fractional-seconds stamp, and an interval inside the bounds.
        for (kind in AliveCheckInPayload.Kind.entries) {
            val wire = event(kind).encode()
            val back = AliveCheckInPayload.decode(wire)
            assertNotNull("this client emitted something it cannot read back: $kind", back)
            assertEquals(1, back!!.version)
            assertTrue(back.atUnix > 0)
            assertTrue(
                "interval out of the range the phones clamp to",
                back.intervalSeconds == 0L ||
                    back.intervalSeconds in
                    AliveCheckInPayload.MIN_INTERVAL_SECONDS..AliveCheckInPayload.MAX_INTERVAL_SECONDS,
            )
            assertEquals(kind == AliveCheckInPayload.Kind.DISABLED, back.intervalSeconds == 0L)
        }
    }
}
