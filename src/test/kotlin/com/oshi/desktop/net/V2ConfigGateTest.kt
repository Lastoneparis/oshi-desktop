package com.oshi.desktop.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rollout gate — PARITY.md row 0.8.
 *
 * It decides two things, and the second is the dangerous one: whether this client uses
 * the V2 transport, and whether it PUBLISHES a prekey bundle. Publishing is what makes
 * iOS and Android route V2 traffic to this address, so a gate that opens by accident
 * points other people's messages at a client that may not be able to open them. Every
 * failure path here therefore has to close, not open.
 */
class V2ConfigGateTest {

    private val relay = FakeRelay()

    @After
    fun tearDown() {
        relay.close()
    }

    private fun gate(build: Int = 1, ttl: Long = 60_000, clock: () -> Long = System::currentTimeMillis) =
        V2ConfigGate(baseUrl = relay.baseUrl, buildNumber = build, ttlMillis = ttl, clock = clock)

    @Test
    fun `an enabled rollout that covers this identity opens the gate`() {
        relay.responder = { 200 to """{"v2_enabled":true,"rollout_percent":100,"min_build":0}""" }
        assertTrue(gate().isEnabled(USER_A))
    }

    @Test
    fun `a disabled flag closes it whatever the rollout says`() {
        relay.responder = { 200 to """{"v2_enabled":false,"rollout_percent":100,"min_build":0}""" }
        assertTrue(!gate().isEnabled(USER_A))
    }

    @Test
    fun `an identity outside the rollout percentage stays out`() {
        // USER_A's bucket is 49 (computed independently, see BUCKET_VECTORS).
        relay.responder = { 200 to """{"v2_enabled":true,"rollout_percent":49,"min_build":0}""" }
        assertTrue("bucket 49 must be OUTSIDE a 49% rollout", !gate().isEnabled(USER_A))

        // Each `gate()` is a fresh instance, so this is a second, uncached fetch.
        relay.responder = { 200 to """{"v2_enabled":true,"rollout_percent":50,"min_build":0}""" }
        assertTrue("bucket 49 must be INSIDE a 50% rollout", gate().isEnabled(USER_A))
    }

    @Test
    fun `a min_build floor above this build closes it`() {
        relay.responder = { 200 to """{"v2_enabled":true,"rollout_percent":100,"min_build":99}""" }
        assertTrue(!gate(build = 1).isEnabled(USER_A))
        assertTrue(gate(build = 99).isEnabled(USER_A))
    }

    @Test
    fun `every failure closes the gate`() {
        for (response in listOf(500 to "", 404 to "", 200 to "not json", 200 to "{}")) {
            relay.responder = { response }
            assertTrue("HTTP ${response.first} '${response.second}' must fail closed",
                !gate().isEnabled(USER_A))
        }
    }

    @Test
    fun `an unreachable server closes the gate rather than hanging the caller`() {
        val closed = FakeRelay().also { it.close() }
        assertTrue(!V2ConfigGate(baseUrl = closed.baseUrl).isEnabled(USER_A))
    }

    /**
     * The bucket has to agree with the other two platforms or the same identity is inside
     * the rollout on a phone and outside it on the desktop. The expected values were
     * computed independently (a three-line Python script over SHA-256), not by running
     * this code — a self-derived vector would only prove the function is deterministic.
     */
    @Test
    fun `the rollout bucket is the first four bytes big-endian, mod 100`() {
        for ((key, expected) in BUCKET_VECTORS) {
            assertEquals("bucket for '$key'", expected, V2ConfigGate.bucket(key))
        }
    }

    /** The little-endian reading of the same bytes gives different answers — so the test bites. */
    @Test
    fun `a little-endian read would land in a different cohort`() {
        val littleEndian = mapOf(USER_A to 74, "hugo" to 24)
        for ((key, le) in littleEndian) {
            assertTrue("big-endian and little-endian agree for '$key' — this vector cannot detect the bug",
                V2ConfigGate.bucket(key) != le)
        }
    }

    @Test
    fun `the local override opens the gate without any network call`() {
        relay.responder = { 500 to "" }
        val g = gate()
        g.setLocalEnabled(true)
        assertTrue(g.isEnabledCached())
        assertEquals("the override must not have hit the server", 0, relay.requests().size)
    }

    @Test
    fun `the cached value is served without a request until the TTL expires`() {
        relay.responder = { 200 to """{"v2_enabled":true,"rollout_percent":100,"min_build":0}""" }
        var now = 1_000_000L
        val g = gate(ttl = 60_000) { now }
        assertTrue(g.isEnabled(USER_A))
        assertEquals(1, relay.requests().size)

        repeat(5) { g.isEnabled(USER_A) }
        assertEquals("the hot path must not call the server", 1, relay.requests().size)

        now += 60_001
        g.isEnabled(USER_A)
        assertEquals("the TTL must expire", 2, relay.requests().size)
    }

    /** A kill switch has to take effect within one TTL, or it is not a kill switch. */
    @Test
    fun `rollout_percent zero closes an open gate within one TTL`() {
        relay.responder = { 200 to """{"v2_enabled":true,"rollout_percent":100,"min_build":0}""" }
        var now = 1_000_000L
        val g = gate(ttl = 1_000) { now }
        assertTrue(g.isEnabled(USER_A))

        relay.responder = { 200 to """{"v2_enabled":true,"rollout_percent":0,"min_build":0}""" }
        now += 1_001
        assertTrue(!g.isEnabled(USER_A))
    }

    companion object {
        private const val USER_A = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE="
        private val BUCKET_VECTORS = mapOf(
            USER_A to 49,
            "hugo" to 99,
            "2wLYrJr299p2iuUgr2EEOnC6oPEuUItoIbwwBB49rHE=" to 46,
        )
    }
}
