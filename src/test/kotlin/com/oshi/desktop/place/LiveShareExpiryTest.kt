package com.oshi.desktop.place

import com.oshi.desktop.msg.ControlPrefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a live share ENDS — the privacy half of PARITY.md row 0.19.
 *
 * Three separate ways a share can outlive its sender's intent are pinned here, each with
 * the shipped code that does or does not stop it:
 *
 *  1. the sender's stop message never arrives         → the wall clock must still end it
 *  2. every ping drags the wall clock forward         → Android's un-taken 2026-06-04 fix
 *  3. the share carries no wall clock at all          → live forever on BOTH phones
 *
 * All timings are explicit instants, never `System.currentTimeMillis()`: a test of expiry
 * that reads the real clock is a test that cannot sit on the boundary.
 */
class LiveShareExpiryTest {

    /** 2025-08-04T13:33:20Z, the fixture instant the wire-format tests use. */
    private val t0 = 1_754_307_200_000L
    private val fifteenMin = 900_000L

    private fun ping(
        atMs: Long,
        expiresMs: Long?,
        sessionId: String? = "s1",
        isUpdate: Boolean = true,
        isStopped: Boolean = false,
    ): LocationPayload = LocationPayload.decode(
        ControlPrefix.LOCATION + buildString {
            append("""{"latitude":48.85661,"longitude":2.35222,""")
            append(""""timestamp":${(atMs / 1000.0) - 978_307_200.0},""")
            append(""""isLive":true""")
            if (expiresMs != null) append(""","expiresAt":${(expiresMs / 1000.0) - 978_307_200.0}""")
            if (sessionId != null) append(""","sessionId":"$sessionId"""")
            append(""","isUpdate":$isUpdate,"isStopped":$isStopped}""")
        },
    )!!

    // ------------------------------------------------- 1. the wall clock alone suffices

    @Test
    fun `a share whose stop message never arrives still expires on the wall clock`() {
        val p = ping(atMs = t0, expiresMs = t0 + fifteenMin, isUpdate = false)
        assertEquals(LiveState.LIVE, p.liveState(t0 + fifteenMin - 1))
        assertEquals(LiveState.EXPIRED, p.liveState(t0 + fifteenMin))
        assertNull(p.remainingMs(t0 + fifteenMin))
    }

    @Test
    fun `an explicit stop ends it before its wall clock does`() {
        val p = ping(atMs = t0, expiresMs = t0 + fifteenMin, isStopped = true)
        assertEquals(LiveState.STOPPED, p.liveState(t0))
    }

    // ------------------------------------------------- 2. the ratchet Android still has

    @Test
    fun `a later ping cannot push the expiry outward`() {
        // GUARD: LiveShareTracker.EXPIRY_NEVER_EXTENDS.
        //
        // Reproduces Android's `liveUpdate` exactly (LocationSharingManager.kt:589-607):
        // every 10 s tick recomputes `expiresAt = now + durationSeconds`. iOS fixed this on
        // 2026-06-04 by pinning `liveShareEndTime`; Android did not. Honouring the newest
        // ping is what makes the share "never expire on the receiver side" — iOS's own
        // words at swift:940-945.
        val tracker = LiveShareTracker()

        assertEquals(LiveState.LIVE, tracker.observe(ping(t0, t0 + fifteenMin, isUpdate = false), t0))

        // Ten minutes in, a ratcheting Android sender claims a NEW end 15 min out.
        val tenMin = t0 + 600_000L
        assertEquals(LiveState.LIVE, tracker.observe(ping(tenMin, tenMin + fifteenMin), tenMin))

        // The share must still end at the FIRST deadline its user chose, not the dragged one.
        assertEquals(t0 + fifteenMin, tracker.session("s1")!!.pinnedExpiryMs)
        assertEquals(
            "the ratchet must not survive the tracker",
            LiveState.EXPIRED,
            tracker.observe(ping(t0 + fifteenMin, t0 + fifteenMin + fifteenMin), t0 + fifteenMin),
        )
    }

    @Test
    fun `the expiry may still move INWARD, because that is what a stop is`() {
        // `stopped()` sets expiresAt = now on both platforms. A rule that only ever pinned
        // the first value would refuse the message that ends the share early.
        val tracker = LiveShareTracker()
        tracker.observe(ping(t0, t0 + fifteenMin, isUpdate = false), t0)
        val early = t0 + 60_000L
        assertEquals(LiveState.EXPIRED, tracker.observe(ping(early, early), early))
        assertEquals(early, tracker.session("s1")!!.pinnedExpiryMs)
    }

    @Test
    fun `once stopped a session stays stopped even if a stale ping arrives after it`() {
        // The relay and the mesh race; a ping emitted before the stop can be delivered
        // after it. Un-stopping a share on a late packet is the same class of bug as
        // un-reading a message on a late delivery receipt (row 0.18, rule 2).
        val tracker = LiveShareTracker()
        tracker.observe(ping(t0, t0 + fifteenMin, isUpdate = false), t0)
        tracker.observe(ping(t0 + 60_000L, t0 + 60_000L, isStopped = true), t0 + 60_000L)
        assertEquals(
            LiveState.STOPPED,
            tracker.observe(ping(t0 + 30_000L, t0 + fifteenMin), t0 + 90_000L),
        )
    }

    @Test
    fun `sessions are independent`() {
        val tracker = LiveShareTracker()
        tracker.observe(ping(t0, t0 + fifteenMin, sessionId = "a", isUpdate = false), t0)
        tracker.observe(ping(t0, t0 + 60_000L, sessionId = "b", isUpdate = false), t0)
        assertEquals(t0 + fifteenMin, tracker.session("a")!!.pinnedExpiryMs)
        assertEquals(t0 + 60_000L, tracker.session("b")!!.pinnedExpiryMs)
    }

    @Test
    fun `pruning drops only the sessions that have ended`() {
        // The desktop's pruneExpiredPeerLocations (LocationSharingManager.swift:758-767).
        val tracker = LiveShareTracker()
        tracker.observe(ping(t0, t0 + 60_000L, sessionId = "short", isUpdate = false), t0)
        tracker.observe(ping(t0, t0 + fifteenMin, sessionId = "long", isUpdate = false), t0)
        assertEquals(1, tracker.pruneEnded(t0 + 120_000L))
        assertNull(tracker.session("short"))
        assertNotEquals(null, tracker.session("long"))
    }

    // ------------------------------------------------- 3. the share with no wall clock

    @Test
    fun `a live share with no expiresAt is never rendered as live`() {
        // GUARD: LiveState.UNBOUNDED.
        //
        // Both shipped clients return `false` from isExpired here — iOS `guard let
        // expiresAt = expiresAt else { return false }` (swift:837), Android `val expires =
        // expiresAt ?: return false` (kt:688) — so this exact payload, which is four
        // required fields and nothing invalid, is a share that never ends on either phone
        // and that no UI on either platform offers a way to dismiss.
        val p = ping(atMs = t0, expiresMs = null, isUpdate = false)
        assertEquals(LiveState.UNBOUNDED, p.liveState(t0))
        assertEquals(LiveState.UNBOUNDED, p.liveState(t0 + 86_400_000L * 3650))
        assertNull(p.remainingMs(t0))
        assertTrue(p.renderToText(t0).contains("no valid expiry"))
    }

    @Test
    fun `a live share claiming more than the longest shipped duration is not live`() {
        // GUARD: LocationPayload.MAX_LIVE_DURATION_SECONDS.
        //
        // 8 h is the longest preset on BOTH platforms (LiveDuration.eightHours = 480 min,
        // swift:407 / kt:476), so a longer span cannot have come from either UI.
        val eightHours = 28_800_000L
        assertEquals(
            LiveState.LIVE,
            ping(t0, t0 + eightHours, isUpdate = false).liveState(t0),
        )
        assertEquals(
            LiveState.UNBOUNDED,
            ping(t0, t0 + eightHours + 1_000L, isUpdate = false).liveState(t0),
        )
    }

    @Test
    fun `a live share with no sessionId cannot be tracked and is refused as unbounded`() {
        // `live()` always sets one on both platforms (swift:919, kt:556). Without a key
        // there is nothing to pin an expiry to, so guessing one would silently defeat
        // EXPIRY_NEVER_EXTENDS for every share that omits it.
        val tracker = LiveShareTracker()
        val p = ping(t0, t0 + fifteenMin, sessionId = null, isUpdate = false)
        assertEquals(LiveState.LIVE, p.liveState(t0))
        assertEquals(LiveState.UNBOUNDED, tracker.observe(p, t0))
        assertTrue(tracker.sessions().isEmpty())
    }

    @Test
    fun `a one-time pin is not a share and is passed straight through`() {
        val tracker = LiveShareTracker()
        val pin = LocationPayload.decode(
            ControlPrefix.LOCATION +
                """{"latitude":1.0,"longitude":2.0,"timestamp":776000000.0,"isLive":false}""",
        )!!
        assertEquals(LiveState.NOT_LIVE, tracker.observe(pin, t0))
        assertTrue(tracker.sessions().isEmpty())
    }
}
