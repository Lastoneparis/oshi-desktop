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

    // __DESKTOP_GATE_DOES_NOT_CACHE_A_TIMEOUT_2026_10_29__ ------------------------------

    /**
     * A SERVER DECISION IS CACHED; AN UNREACHABLE SERVER IS NOT.
     *
     * `cachedAt` used to be stamped whatever happened, so one unreachable moment disabled the
     * V2 lane for the whole TTL — and because `fetch` returned a bare `false` for both cases,
     * a slow network was indistinguishable from "the server rolled you out to 0%". A live
     * bench hit exactly that on 2026-10-29 and reported `v2 gate closed`, which reads as a
     * rollout decision, when the truth was a relay answering `/v2/config` in 9-25 s.
     *
     * Nothing is learned from a timeout, and caching it guarantees the next TTL cannot learn
     * anything either.
     */
    @Test
    fun `a failure to reach the server is not cached, so the next poll retries`() {
        // A GENUINELY unreachable address. The first version of this test made the fake
        // relay's responder throw, which `FakeRelay` turns into a 500 — a real answer from a
        // real server, and correctly cached. Nothing was being tested.
        val logs = ArrayList<String>()
        val dead = V2ConfigGate(
            baseUrl = "http://127.0.0.1:1",   // nothing listens on port 1
            buildNumber = 1,
            ttlMillis = 10 * 60_000,          // long: a cached failure could never retry
            log = { logs += it },
        )
        assertTrue("an unreachable server must read as closed", !dead.isEnabled(USER_A))
        assertTrue("and must say so in those words: ${dead.lastDecision}",
            dead.lastDecision.contains("could not reach"))
        assertTrue(!dead.isEnabled(USER_A))
        assertTrue(!dead.isEnabled(USER_A))
        assertEquals(
            "the gate stopped asking — a timeout was cached like a decision",
            3, logs.count { it.contains("could not reach") },
        )
    }

    /** ...and a real decision IS cached, or every poll would re-ask. */
    @Test
    fun `a server decision is cached for the TTL`() {
        var calls = 0
        relay.responder = {
            calls++
            200 to """{"v2_enabled":false,"rollout_percent":100,"min_build":0}"""
        }
        val g = gate(ttl = 10 * 60_000)
        assertTrue(!g.isEnabled(USER_A))
        assertTrue(!g.isEnabled(USER_A))
        assertEquals("a decision must not be re-fetched inside the TTL", 1, calls)
    }

    /**
     * `serverUnreachable` is the one bit that lets the window blame the right party. A send
     * that fails while the relay is down used to be drawn as "that peer has published no
     * prekey bundle" or "the V2 rollout gate is closed for this account" — both false, both
     * about somebody other than the actual culprit, and neither actionable.
     */
    @Test
    fun `the gate reports whether the server was reachable, separately from its answer`() {
        val dead = V2ConfigGate(baseUrl = "http://127.0.0.1:1", buildNumber = 1)
        assertTrue(!dead.isEnabled(USER_A))
        assertTrue("an unreachable server must be flagged as such", dead.serverUnreachable)

        relay.responder = { 200 to """{"v2_enabled":false,"rollout_percent":100,"min_build":0}""" }
        val refused = gate()
        assertTrue(!refused.isEnabled(USER_A))
        assertTrue(
            "a server that ANSWERED no must not be reported as unreachable",
            !refused.serverUnreachable,
        )

        // And it clears once the server answers again.
        relay.responder = { 200 to """{"v2_enabled":true,"rollout_percent":100,"min_build":0}""" }
        val ok = gate()
        assertTrue(ok.isEnabled(USER_A))
        assertTrue(!ok.serverUnreachable)
    }

    // __LEGACY_COMPAT_2026_09_23__ -------------------------------------------------------
    @Test
    fun `group_legacy_compat defaults to false and only a JSON true turns it on`() {
        relay.responder = { 200 to """{"v2_enabled":true,"rollout_percent":100,"min_build":0}""" }
        gate().also { it.isEnabled(USER_A); assertTrue("missing key => false", !it.groupLegacyCompat) }
        for (v in listOf("\"true\"", "1", "null", "false")) {
            relay.responder = { 200 to """{"v2_enabled":true,"rollout_percent":100,"min_build":0,"group_legacy_compat":$v}""" }
            gate().also { it.isEnabled(USER_A); assertTrue("$v must not enable compat", !it.groupLegacyCompat) }
        }
        relay.responder = { 200 to """{"v2_enabled":true,"rollout_percent":100,"min_build":0,"group_legacy_compat":true}""" }
        gate().also { it.isEnabled(USER_A); assertTrue(it.groupLegacyCompat) }
    }

    @Test
    fun `group_legacy_compat is ignored after the 2026-10-14 sunset`() {
        val sunset = V2ConfigGate.GROUP_LEGACY_COMPAT_SUNSET_MS
        assertEquals(java.time.Instant.parse("2026-10-14T07:00:00Z").toEpochMilli(), sunset)
        assertTrue(V2ConfigGate.isGroupLegacyCompatActive(true, sunset - 1))
        assertTrue(!V2ConfigGate.isGroupLegacyCompatActive(true, sunset))
        assertTrue(!V2ConfigGate.isGroupLegacyCompatActive(false, sunset - 1))
        assertTrue(!V2ConfigGate.isGroupLegacyCompatActive(null, sunset - 1))
        assertEquals(2, V2ConfigGate.CAP_GROUP_V2)
    }

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
