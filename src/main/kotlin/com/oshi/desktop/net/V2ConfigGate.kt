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
) {
    @Volatile private var cachedEnabled = false
    @Volatile private var cachedAt = 0L
    @Volatile private var localOverride = System.getenv("OSHI_V2_FORCE")?.equals("1") == true

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    /** Dev/testing override. Forces V2 on for this process without touching the server. */
    fun setLocalEnabled(on: Boolean) { localOverride = on }

    /** Pure, never-blocking read for the send path. */
    fun isEnabledCached(): Boolean = localOverride || cachedEnabled

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
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/v2/config"))
            .timeout(Duration.ofSeconds(15)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) false else {
            val json = JSONObject(resp.body())
            when {
                !json.optBoolean("v2_enabled", false) -> false
                buildNumber < json.optInt("min_build", Int.MAX_VALUE) -> false
                else -> bucket(userKey) < json.optInt("rollout_percent", 0)
            }
        }
    } catch (_: Exception) {
        false
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
