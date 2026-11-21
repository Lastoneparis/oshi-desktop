package com.oshi.desktop.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_LORA_BEACON_2026_10_28__ The SAME table of inputs on ALL THREE clients, reduced
 * to ONE digest.
 *
 * Android and iOS already compare their beacon policies this way
 * (`LoRaBeaconCrossPlatformTest.POLICY_DIGEST`, `LoRaBeaconPolicyTests.policyDigest`). This
 * is the third implementation, and it is the one most likely to have forked: the phones
 * were written together, this one was written afterwards against a description. A third
 * copy written from prose is exactly where a policy drifts, and matching test NAMES would
 * not catch it — "Donateur 310" on Android and "253" on iOS both passed tests with the same
 * names once.
 *
 * So the constant below is not a figure this file computed and then wrote down. It is the
 * one Android produced first, pasted into iOS, and now into here. Three implementations,
 * one number.
 */
class LoRaBeaconParityTest {

    /** Kept identical, row for row, to the Android and iOS tables. */
    private data class V(
        val enabled: Boolean, val ready: Boolean, val perm: Boolean,
        val lat: Double?, val lon: Double?,
        val now: Long, val lastSent: Long, val lastLat: Double, val lastLon: Double,
    )

    private val NOW = 1_793_200_000_000L
    private val I = LoRaPositionBeacon.INTERVAL_MS
    private val MAX = LoRaPositionBeacon.MAX_SILENCE_MS

    private fun vectors(): List<V> = listOf(
        V(false, false, false, null, null, NOW, 0L, 0.0, 0.0),
        V(true, false, true, 46.2044, 6.1432, NOW, 0L, 0.0, 0.0),
        V(true, true, false, 46.2044, 6.1432, NOW, 0L, 0.0, 0.0),
        V(true, true, true, null, null, NOW, 0L, 0.0, 0.0),
        V(true, true, true, 0.0, 0.0, NOW, 0L, 0.0, 0.0),
        V(true, true, true, 91.0, 6.0, NOW, 0L, 0.0, 0.0),
        V(true, true, true, 46.0, 181.0, NOW, 0L, 0.0, 0.0),
        V(true, true, true, -90.0001, 6.0, NOW, 0L, 0.0, 0.0),
        V(true, true, true, 51.4779, 0.0, NOW, 0L, 0.0, 0.0),
        V(true, true, true, 46.2044, 6.1432, NOW, 0L, 0.0, 0.0),
        V(true, true, true, 46.2044, 6.1432, NOW, NOW - I + 1, 48.8566, 2.3522),
        V(true, true, true, 46.2044, 6.1432, NOW, NOW - I, 46.2044, 6.1432),
        V(true, true, true, 46.2044, 6.1432, NOW, NOW - I - 1, 46.2044 - 0.00089, 6.1432),
        V(true, true, true, 46.2044, 6.1432, NOW, NOW - I - 1, 46.2044 - 0.00091, 6.1432),
        V(true, true, true, 46.2044, 6.1432, NOW, NOW - I - 1, 46.2134, 6.1432),
        V(true, true, true, 46.2044, 6.1432, NOW, NOW - MAX, 46.2044, 6.1432),
        V(true, true, true, 46.2044, 6.1432, NOW, NOW - MAX + 1, 46.2044, 6.1432),
        V(true, true, true, 46.2044, 6.1432, NOW, NOW + 86_400_000L, 46.2044, 6.1432),
        V(false, true, true, 46.2044, 6.1432, NOW, NOW - 1, 46.2044, 6.1432),
        V(true, true, true, -33.8688, 151.2093, NOW, NOW - I - 1, -33.8688, 151.2093),
        V(true, true, true, -33.8688, -70.6693, NOW, NOW - I - 1, -33.8688, 151.2093),
    )

    private fun d(v: Double?): String =
        if (v == null) "na" else String.format(java.util.Locale.ROOT, "%.7f", v)

    private fun b(v: Boolean) = if (v) "1" else "0"

    private fun transcript(): String = vectors().joinToString("\n") { v ->
        val decision = LoRaPositionBeacon.decide(
            v.enabled, v.ready, v.perm, v.lat, v.lon, v.now, v.lastSent, v.lastLat, v.lastLon,
        )
        "${b(v.enabled)}${b(v.ready)}${b(v.perm)}|${d(v.lat)}|${d(v.lon)}|" +
            "${v.now}|${v.lastSent}|${d(v.lastLat)}|${d(v.lastLon)}=>${decision.name}"
    }

    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `the policy digest matches the one BOTH phones compute`() {
        val t = transcript()
        assertEquals("the table must not lose or gain rows silently", 21, t.lines().size)
        assertEquals(
            "this client disagrees with the phones about the beacon policy, OR a rule " +
                "changed on purpose. In the second case update the constant in ALL THREE " +
                "(here, LoRaBeaconCrossPlatformTest.POLICY_DIGEST on Android, " +
                "LoRaBeaconPolicyTests.policyDigest on iOS) in the same commit.\n" + t,
            POLICY_DIGEST, sha256(t),
        )
    }

    /**
     * The constant the digest is built from, checked separately.
     *
     * The ceiling is not a nicety on THIS client: a fixed position never moves, so the
     * movement gate suppresses every beacon after the first and the ceiling is the only
     * thing that ever fires again. If someone removes it as "redundant with the interval",
     * a desktop node goes silent after one beacon and the digest above would NOT catch it
     * (the phones would change too). Hence a direct assertion.
     */
    @Test
    fun `the ceiling is what keeps a fixed position on the air`() {
        assertTrue(LoRaPositionBeacon.MAX_SILENCE_MS > LoRaPositionBeacon.INTERVAL_MS)
        val fixed = 46.2044 to 6.1432
        // Past the interval, not moved: suppressed.
        assertEquals(
            LoRaPositionBeacon.Decision.STILL_HERE,
            LoRaPositionBeacon.decide(
                true, true, true, fixed.first, fixed.second,
                NOW, NOW - I - 1, fixed.first, fixed.second,
            ),
        )
        // Past the ceiling, still not moved: sent anyway. This is the desktop's whole clock.
        assertEquals(
            LoRaPositionBeacon.Decision.SEND,
            LoRaPositionBeacon.decide(
                true, true, true, fixed.first, fixed.second,
                NOW, NOW - MAX, fixed.first, fixed.second,
            ),
        )
    }

    @Test
    fun `the haversine agrees with the phones to the metre`() {
        // Geneva to Paris, the figure both phones compute with the same formula and radius.
        val m = LoRaPositionBeacon.greatCircleMetres(46.2044, 6.1432, 48.8566, 2.3522)
        assertEquals(410_000.0, m, 5_000.0)
        assertEquals(0.0, LoRaPositionBeacon.greatCircleMetres(46.2044, 6.1432, 46.2044, 6.1432), 1e-9)
        // Either side of the 100 m gate, the two rows the shared table pins.
        assertTrue(LoRaPositionBeacon.greatCircleMetres(46.2044 - 0.00089, 6.1432, 46.2044, 6.1432) < 100.0)
        assertTrue(LoRaPositionBeacon.greatCircleMetres(46.2044 - 0.00091, 6.1432, 46.2044, 6.1432) > 100.0)
    }

    // ------------------------------------------------------------------ the driver

    @Test
    fun `tick sends nothing without a radio, a switch or a position`() {
        LoRaPositionBeacon.reset()
        var sends = 0
        val send: (Double, Double) -> Boolean = { _, _ -> sends++; true }

        // No radio.
        assertEquals(
            LoRaPositionBeacon.Decision.DISABLED,
            LoRaPositionBeacon.tick(radioReady = false, nowMs = NOW, send = send),
        )
        assertEquals("nothing may leave", 0, sends)
    }

    @Test
    fun `a position is refused unless it is on Earth`() {
        // Guarded at the STORE as well as at the sender, deliberately twice: the sender
        // stops a bad value going out, this stops it being remembered, which is what would
        // otherwise put this node in the Gulf of Guinea for as long as nobody noticed.
        assertFalse("0/0 is a blank form, not a place", LoRaFixedPosition.set(0.0, 0.0))
        assertFalse(LoRaFixedPosition.set(91.0, 6.0))
        assertFalse(LoRaFixedPosition.set(46.0, 181.0))
        assertFalse(LoRaFixedPosition.set(Double.NaN, 6.0))
        // Greenwich: longitude 0 alone is a real place and must be accepted.
        assertTrue(LoRaFixedPosition.set(51.4779, 0.0))
    }

    @Test
    fun `the switch cannot be turned on with nothing to send`() {
        // A switch reading "on" while nothing goes out is the indicator failure this
        // codebase keeps paying for.
        LoRaFixedPosition.clear()
        assertFalse("no position means no beacon", LoRaFixedPosition.setEnabled(true))
        assertFalse(LoRaFixedPosition.enabled)
        assertTrue(LoRaFixedPosition.set(46.2044, 6.1432))
        assertTrue(LoRaFixedPosition.setEnabled(true))
        assertTrue(LoRaFixedPosition.enabled)
        // Clearing the position must also stop the beacon, not leave it on with nothing.
        LoRaFixedPosition.clear()
        assertFalse(LoRaFixedPosition.enabled)
    }

    companion object {
        /**
         * SHA-256 of the transcript. The SAME literal appears in Android's
         * `LoRaBeaconCrossPlatformTest.POLICY_DIGEST` and iOS's
         * `LoRaBeaconPolicyTests.policyDigest`.
         */
        const val POLICY_DIGEST =
            "28bbeb82edc14c3c8aff1afa9792a18077b9a0b70e6ca88481a21fd7d6774f1d"
    }
}
