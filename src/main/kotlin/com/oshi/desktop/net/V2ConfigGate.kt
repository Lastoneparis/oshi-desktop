package com.oshi.desktop.net

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * The V2 rollout gate — `GET /v2/config`, unauthenticated. Desktop port of Android
 * `V2Config` / iOS `V2FeatureFlag`.
 *
 * It answers one question: may this identity use the V2 transport? And it answers it
 * FAIL-CLOSED — any error, any unreachable server, any parse problem means "no". That is
 * not defensiveness for its own sake: the gate also controls whether we PUBLISH a prekey
 * bundle, and publishing is what makes iOS and Android route V2 traffic to us. Publishing
 * from a client whose receive path is not proven means messages routed to somewhere they
 * cannot be opened (PLAN.md §4.10).
 *
 * The bucket must match the other two platforms exactly: the first four bytes of
 * SHA-256(userKey), read BIG-ENDIAN, mod 100. A little-endian read puts this identity in
 * a different cohort than the server believes it is in — the same identity would be in
 * the rollout on a phone and out of it on the desktop.
 *
 * The value is cached with a TTL so the hot path never blocks on the network, and a
 * kill-switch (`rollout_percent: 0`) takes effect within one TTL.
 */
class V2ConfigGate(
    private val baseUrl: String = V2Http.defaultBaseUrl(),
    private val buildNumber: Int = DESKTOP_BUILD,
    private val ttlMillis: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var cachedEnabled = false
    @Volatile private var cachedAt = 0L

    /**
     * Why the gate last answered what it did — in words, for a human.
     *
     * A gate that fails closed and says nothing is undiagnosable from the outside: "V2 is
     * off" looks identical whether the server disabled it, this build is below the floor,
     * this identity fell outside the rollout, or the request never left the machine. Each
     * of those wants a different response from whoever is looking, and this is the only
     * place that knows which one happened.
     */
    @Volatile var lastDecision: String = "not checked yet"
        private set
    @Volatile private var localOverride = System.getenv("OSHI_V2_FORCE")?.equals("1") == true

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        // A rollout answer must come from the configured relay, not a redirect target.
        // Keep this explicit beside the signed clients' identical policy.
        .followRedirects(HttpClient.Redirect.NEVER)
        // Plain http: no h2c upgrade (see V2Http — the v2 server 404s unknown upgrades).
        .apply { if (baseUrl.startsWith("http://")) version(HttpClient.Version.HTTP_1_1) }
        .build()

    /** Dev/testing override. Forces V2 on for this process without touching the server. */
    fun setLocalEnabled(on: Boolean) { localOverride = on }

    /** Pure, never-blocking read for the send path. */
    fun isEnabledCached(): Boolean = localOverride || cachedEnabled

    /**
     * __PER_DEVICE_MAILBOX_2026_09_23__ `/v2/config.device_mailbox_enabled` (default false).
     * Fail-closed on the FIRST answer only; afterwards a failed fetch keeps the last value, so
     * a blip in reachability does not flap this install between its device cursor and the
     * legacy one (CLIENT_SPEC.md §3.1: a flag that turns false is honoured on the next
     * successful read).
     */
    @Volatile var deviceMailboxEnabled: Boolean = System.getenv("OSHI_DEVICE_MAILBOX_FORCE") == "1"
        private set

    /** Tests / diagnostics. */
    fun setDeviceMailboxEnabled(on: Boolean) { deviceMailboxEnabled = on }

    /** Drop the TTL so the next [refresh] asks the server again (a 409 device-mailbox-disabled). */
    fun invalidate() { cachedAt = 0L }

    /** TTL-bounded refresh. Drive this from the poll loop, not from a send. */
    fun refresh(userKey: String) {
        if (localOverride) return
        if (clock() - cachedAt < ttlMillis) return
        synchronized(this) {
            if (clock() - cachedAt < ttlMillis) return
            cachedEnabled = fetch(userKey)
            cachedAt = clock()
        }
    }

    fun isEnabled(userKey: String): Boolean {
        refresh(userKey)
        return isEnabledCached()
    }

    private fun fetch(userKey: String): Boolean = try {
        val url = "$baseUrl/v2/config"
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            decide(false, "HTTP ${resp.statusCode()} from $url")
        } else {
            val json = JSONObject(resp.body())
            if (System.getenv("OSHI_DEVICE_MAILBOX_FORCE") != "1") {
                deviceMailboxEnabled = json.optBoolean("device_mailbox_enabled", false)
            }
            val enabled = json.optBoolean("v2_enabled", false)
            val minBuild = json.optInt("min_build", Int.MAX_VALUE)
            val rollout = json.optInt("rollout_percent", 0)
            val bucket = bucket(userKey)
            when {
                !enabled -> decide(false, "the server has v2_enabled=false")
                buildNumber < minBuild -> decide(false, "this build ($buildNumber) is below the server's min_build ($minBuild)")
                bucket >= rollout -> decide(false, "this identity is in bucket $bucket, outside the ${rollout}% rollout")
                else -> decide(true, "bucket $bucket is inside the ${rollout}% rollout")
            }
        }
    } catch (e: Exception) {
        // Never reached the server. Distinct from "the server said no", and the two want
        // different reactions from whoever is reading.
        decide(false, "could not reach $baseUrl/v2/config: ${e.javaClass.simpleName}: ${e.message}")
    }

    private fun decide(open: Boolean, why: String): Boolean {
        lastDecision = (if (open) "OPEN — " else "CLOSED — ") + why
        log("v2 gate: $lastDecision")
        return open
    }

    companion object {
        /** This client's build number, for the server's `min_build` floor. */
        const val DESKTOP_BUILD = 1

        /** First 4 bytes of SHA-256(userKey), BIG-ENDIAN, mod 100. */
        fun bucket(userKey: String): Int {
            val h = MessageDigest.getInstance("SHA-256").digest(userKey.toByteArray(Charsets.UTF_8))
            val v = ((h[0].toLong() and 0xFF) shl 24) or
                ((h[1].toLong() and 0xFF) shl 16) or
                ((h[2].toLong() and 0xFF) shl 8) or
                (h[3].toLong() and 0xFF)
            return (v % 100).toInt()
        }
    }
}
