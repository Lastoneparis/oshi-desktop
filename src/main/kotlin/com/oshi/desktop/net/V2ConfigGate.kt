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
     * __DESKTOP_SAY_WHICH_FAILURE_2026_10_29__ True when the last attempt never reached the
     * server at all, as opposed to the server answering no.
     *
     * Exposed because the WINDOW was blaming the wrong party. A send that fails while the
     * relay is unreachable came back as `GATE_CLOSED` — drawn as *"the V2 rollout gate is
     * closed for this account"* — or as `NO_V2_PATH`, drawn as *"that peer has published no
     * prekey bundle"*. Both tell the user something false about themselves or their contact
     * when the truth is that a server did not answer, and both are unactionable: there is
     * nothing to do about a rollout you are not in, and nothing to do about a contact who
     * has in fact set OSHI up perfectly well.
     *
     * Measured 2026-10-29 against production, which is what made this visible: `/v2/config`
     * answered in 9-25 s while `/` answered in 0 s, so the timeouts were constant and every
     * one of them was reported to the user as somebody else's fault.
     */
    @Volatile
    var serverUnreachable: Boolean = false
        private set

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

    /**
     * __GATE_NOT_YET_IS_NOT_NO_2026_10_29__ Has the gate ever had an ANSWER?
     *
     * [isEnabledCached] is false both before the first `/v2/config` reply and after one
     * that said no, and the send path told the user the same sentence either way: "the V2
     * rollout gate is closed for this account". On this relay `/v2/config` has been
     * measured at 9-25 s, so "I have not heard back yet" is a state a user meets simply by
     * starting the app and typing — and being told their ACCOUNT is gated sends them to
     * the wrong place entirely. The comment on [lastDecision] already lists the four
     * causes this conflates; this is the flag that separates the first of them.
     */
    @Volatile var everAnswered: Boolean = false
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

    /**
     * __LEGACY_COMPAT_2026_09_23__ `/v2/config.group_legacy_compat` (default false; only a JSON
     * `true` counts). While it is on (and before [GROUP_LEGACY_COMPAT_SUNSET_MS]), iOS and
     * Android ALSO send the legacy group path to members on builds that cannot read v2 groups.
     *
     * Desktop reads it for parity and diagnostics only: Desktop has always been v2-only for
     * groups (no IPFS, no Pinata, no `/api/queue` — GROUP_E2E_V2_SPEC §8), so old phones never
     * received Desktop group traffic and "exactly as before" means sending nothing legacy here.
     * What Desktop does do is announce [CAP_GROUP_V2] in its bundle (V2KeysClient), so phones
     * never waste a legacy copy on a Desktop member.
     */
    @Volatile var groupLegacyCompat: Boolean = false
        private set

    /** The flag as the phones apply it: on AND before the compiled-in sunset. */
    fun groupLegacyCompatActive(nowMs: Long = clock()): Boolean = isGroupLegacyCompatActive(groupLegacyCompat, nowMs)

    /** Drop the TTL so the next [refresh] asks the server again (a 409 device-mailbox-disabled). */
    fun invalidate() { cachedAt = 0L }

    /** TTL-bounded refresh. Drive this from the poll loop, not from a send. */
    fun refresh(userKey: String) {
        if (localOverride) return
        if (clock() - cachedAt < ttlMillis) return
        synchronized(this) {
            if (clock() - cachedAt < ttlMillis) return
            // __DESKTOP_GATE_DOES_NOT_CACHE_A_TIMEOUT_2026_10_29__ A SERVER DECISION IS
            // CACHED; A FAILURE TO REACH THE SERVER IS NOT.
            //
            // `cachedAt` used to be stamped unconditionally, so one unreachable moment
            // disabled the whole V2 lane for the full TTL — and `fetch` returns false for
            // both cases, which made a slow network indistinguishable from "the server
            // rolled you out to 0%". Measured against production on 2026-10-29:
            // `GET /v2/config` answered in 9, 22, 25 and 25 s on four consecutive attempts
            // while `GET /` answered in 0 s, so a 15 s timeout missed roughly half of them
            // and a live bench reported "v2 gate closed" — a rollout decision — when the
            // truth was a slow server.
            //
            // Caching a decision is right: the server said something and it will keep saying
            // it. Caching a timeout is not: nothing was learned, and the only effect is to
            // guarantee the next 60 s cannot learn anything either.
            val answered = fetch(userKey)
            cachedEnabled = answered.open
            serverUnreachable = !answered.reachedServer
            if (answered.reachedServer) cachedAt = clock()
        }
    }

    fun isEnabled(userKey: String): Boolean {
        refresh(userKey)
        return isEnabledCached()
    }

    /** Whether the gate is open, AND whether the server actually answered. See [refresh]. */
    private data class Answer(val open: Boolean, val reachedServer: Boolean)

    private fun fetch(userKey: String): Answer = try {
        val url = "$baseUrl/v2/config"
        val req = HttpRequest.newBuilder(URI.create(url))
            // 30 s, and this is NOT a fix for the server. Measured 2026-10-29: this endpoint
            // answered in 9-25 s while `/` answered instantly, so 15 s cut off about half of
            // its own successful responses. A timeout below the server's real response time
            // turns a slow lane into a closed one. The latency itself is a server defect and
            // is recorded as such; this only stops the client lying about it.
            .timeout(Duration.ofSeconds(30)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            Answer(decide(false, "HTTP ${resp.statusCode()} from $url"), reachedServer = true)
        } else {
            val json = JSONObject(resp.body())
            if (System.getenv("OSHI_DEVICE_MAILBOX_FORCE") != "1") {
                deviceMailboxEnabled = json.optBoolean("device_mailbox_enabled", false)
            }
            // __LEGACY_COMPAT_2026_09_23__ strict: a string "true" or a 1 does not count.
            groupLegacyCompat = json.opt("group_legacy_compat") == true
            val enabled = json.optBoolean("v2_enabled", false)
            val minBuild = json.optInt("min_build", Int.MAX_VALUE)
            val rollout = json.optInt("rollout_percent", 0)
            val bucket = bucket(userKey)
            val open = when {
                !enabled -> decide(false, "the server has v2_enabled=false")
                buildNumber < minBuild -> decide(false, "this build ($buildNumber) is below the server's min_build ($minBuild)")
                bucket >= rollout -> decide(false, "this identity is in bucket $bucket, outside the ${rollout}% rollout")
                else -> decide(true, "bucket $bucket is inside the ${rollout}% rollout")
            }
            Answer(open, reachedServer = true)
        }
    } catch (e: Exception) {
        // Never reached the server. Distinct from "the server said no", and the two want
        // different reactions from whoever is reading.
        // reachedServer = false: NOT cached. See the note in [refresh].
        Answer(decide(false, "could not reach $baseUrl/v2/config: ${e.javaClass.simpleName}: ${e.message}"), reachedServer = false)
    }

    private fun decide(open: Boolean, why: String): Boolean {
        lastDecision = (if (open) "OPEN — " else "CLOSED — ") + why
        everAnswered = true
        log("v2 gate: $lastDecision")
        return open
    }

    companion object {
        /** This client's build number, for the server's `min_build` floor. */
        const val DESKTOP_BUILD = 1

        /**
         * __LEGACY_COMPAT_2026_09_23__ 2026-10-14T07:00:00Z = 09:00 Europe/Paris. After it the
         * flag is ignored whatever the server says (`/v2/config` is unsigned: a replayed `true`
         * must not downgrade anyone). Same constant on iOS and Android.
         */
        const val GROUP_LEGACY_COMPAT_SUNSET_MS = 1_791_961_200_000L

        /** Prekey-bundle capability bit: "this build reads v2 groups" (iOS `Caps.groupV2`, Android `CAP_GROUP_V2`). */
        const val CAP_GROUP_V2 = 1 shl 1

        fun isGroupLegacyCompatActive(flag: Boolean?, nowMs: Long): Boolean =
            flag == true && nowMs < GROUP_LEGACY_COMPAT_SUNSET_MS

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
