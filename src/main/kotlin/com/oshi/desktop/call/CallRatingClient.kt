package com.oshi.desktop.call

import com.oshi.desktop.i18n.t
import com.oshi.desktop.store.AtomicFile
import com.oshi.desktop.store.DesktopPaths
import com.oshi.messenger.service.CallRatingPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * __CALL_RATING_2026_09_22__ — the post-call quality rating, on Windows and Linux.
 *
 * ## What this is for
 *
 * The server can MEASURE a call — packets each way, which transport carried them, how
 * lopsided the two directions were — but it cannot tell whether the person could
 * actually HEAR. Measured on the live server Sep 15-22: calls that looked healthy in
 * the relay counters were one-way in practice, and calls that looked dead there had in
 * fact run cleanly through coturn, which the relay never sees. The rating is the
 * missing half: it turns "450 packets one way, 0 back" into "…and they heard nothing".
 *
 * ## Why the decision is not made here
 *
 * WHETHER to ask is [CallRatingPolicy], compiled out of the Android tree rather than
 * reimplemented — see the note on the shared-source list in `build.gradle.kts`. A
 * desktop that sampled every second call while the phones sampled every third would
 * produce a channel whose denominator is wrong in a way nobody can see, because the
 * channel only ever shows the ratings that WERE asked for.
 *
 * What this class owns is the two seams the policy deliberately has no opinion about:
 * where the ask-history lives (a JSON file under [DesktopPaths], not
 * SharedPreferences), and how the answer is posted.
 *
 * ## What leaves the machine
 *
 * A star count, optional canned reasons, an optional note the person typed, the callId,
 * the platform and the version. Never the peer's identity, never anything from the
 * conversation. Identical body to iOS and Android — one channel, one vocabulary.
 */
class CallRatingClient(
    private val myPublicKey: String,
    private val stateFile: File = DesktopPaths.file(FILE_NAME),
    private val endpoint: String = ENDPOINT,
    /** Injected in tests so the policy can be driven without waiting real hours. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Injected in tests so nothing is posted. */
    private val poster: (String) -> Unit = ::postJson,
) {

    /** A finished call the person has not answered about yet. */
    data class Pending(
        val callId: String,
        val peer: String,
        val durationSeconds: Long,
        val endedAbnormally: Boolean,
    )

    /**
     * The canned complaints. The wire values are the contract with the other two
     * clients — `CallRatingPolicyTest.reason wire values match the iOS enum exactly`
     * on Android asserts the same six against Swift.
     */
    enum class Reason(val wire: String) {
        COULD_NOT_HEAR_THEM("couldNotHearThem"),
        THEY_COULD_NOT_HEAR_ME("theyCouldNotHearMe"),
        CHOPPY("choppy"),
        DELAY("delay"),
        ECHO("echo"),
        DROPPED("dropped");

        /**
         * The translated label.
         *
         * Written as six literal `t("…")` calls rather than a `key` property or a
         * built-up string, because `SourceKeys` scans for keys AT a registered accessor
         * call site. A key carried as a raw string in a constructor argument — which is
         * how this was first written — reaches the catalog through a path the audit
         * cannot see, and `CatalogAuditTest` fails it by design. That audit exists
         * because this codebase has already shipped a paywall that was English in 34
         * languages behind exactly such a wrapper.
         */
        fun label(): String = when (this) {
            COULD_NOT_HEAR_THEM -> t("call.rating.reason.could_not_hear_them")
            THEY_COULD_NOT_HEAR_ME -> t("call.rating.reason.they_could_not_hear_me")
            CHOPPY -> t("call.rating.reason.choppy")
            DELAY -> t("call.rating.reason.delay")
            ECHO -> t("call.rating.reason.echo")
            DROPPED -> t("call.rating.reason.dropped")
        }

        companion object {
            /** `1`-based, as the person types it at the prompt. */
            fun byIndex(i: Int): Reason? = values().getOrNull(i - 1)
        }
    }

    @Volatile
    private var pending: Pending? = null

    fun pending(): Pending? = pending

    // ------------------------------------------------------------------ deciding

    /**
     * Called when a call finishes. Returns the [Pending] to prompt about, or null when
     * the policy says this call is not one to ask about.
     */
    @Synchronized
    fun callDidEnd(
        callId: String,
        peer: String,
        durationSeconds: Long,
        endedNormally: Boolean,
    ): Pending? {
        if (callId.isBlank()) return null
        if (pending != null) return null

        val now = clock()
        val state = load()
        val decision = CallRatingPolicy.decide(
            now = now,
            durationSeconds = durationSeconds,
            endedNormally = endedNormally,
            alreadyRated = state.ratedCallIds.contains(callId),
            lastAskAt = state.lastAskAt,
            askTimes = state.askTimes,
            goodCallCounter = state.goodCallCounter,
        )

        // The counter advances even when the answer is "not this one" — that is what
        // makes it every third CALL rather than every third ASK.
        if (decision.newGoodCallCounter != null) {
            save(state.copy(goodCallCounter = decision.newGoodCallCounter!!))
        }
        if (!decision.ask) return null

        save(
            load().copy(
                lastAskAt = now,
                askTimes = (load().askTimes.filter { it > now - CallRatingPolicy.WEEK_MS } + now),
                ratedCallIds = (load().ratedCallIds + callId).takeLast(MAX_REMEMBERED_CALLS),
            )
        )

        val p = Pending(callId, peer, durationSeconds, !endedNormally)
        pending = p
        return p
    }

    /** The person answered something else, or typed `/rate skip`. */
    @Synchronized
    fun dismiss() {
        pending = null
    }

    // ------------------------------------------------------------------ answering

    /**
     * Send the verdict. Returns false when there was nothing to rate.
     *
     * Fire-and-forget on a single-thread executor: a rating is never worth blocking a
     * prompt on, and never worth an error in the person's way.
     */
    @Synchronized
    fun submit(rating: Int, reasons: Set<Reason> = emptySet(), comment: String = ""): Boolean {
        val item = pending ?: return false
        pending = null
        if (myPublicKey.isBlank()) return false

        val body = JSONObject()
            .put("callId", item.callId)
            .put("sender", myPublicKey)
            .put("rating", rating.coerceIn(1, 5))
            .put("platform", platformName())
            .put("appVersion", appVersion())
        if (reasons.isNotEmpty()) {
            body.put("reasons", JSONArray(reasons.map { it.wire }.sorted()))
        }
        comment.trim().take(300).takeIf { it.isNotEmpty() }?.let { body.put("comment", it) }

        IO.execute {
            try {
                poster(body.toString())
            } catch (_: Exception) {
                // Deliberately silent. See the class note.
            }
        }
        return true
    }

    /** "Windows" / "Linux" / "macOS" — the channel groups complaints by platform. */
    fun platformName(): String = when {
        DesktopPaths.isWindows -> "Windows"
        DesktopPaths.isLinux -> "Linux"
        else -> "macOS-desktop"
    }

    private fun appVersion(): String =
        (javaClass.`package`?.implementationVersion ?: "dev") + " (jvm)"

    // ------------------------------------------------------------------ state

    data class State(
        val goodCallCounter: Int = 0,
        val lastAskAt: Long = 0L,
        val askTimes: List<Long> = emptyList(),
        val ratedCallIds: List<String> = emptyList(),
    )

    private fun load(): State = try {
        if (!stateFile.isFile) State() else JSONObject(stateFile.readText(Charsets.UTF_8)).let { o ->
            State(
                goodCallCounter = o.optInt("goodCallCounter", 0),
                lastAskAt = o.optLong("lastAskAt", 0L),
                askTimes = o.optJSONArray("askTimes")?.let { a -> (0 until a.length()).map { a.getLong(it) } }
                    ?: emptyList(),
                ratedCallIds = o.optJSONArray("ratedCallIds")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?: emptyList(),
            )
        }
    } catch (_: Exception) {
        // A corrupt file must not stop calls from being ratable; the worst case is one
        // extra ask, which is a far better failure than a crash on hang-up.
        State()
    }

    private fun save(s: State) {
        try {
            AtomicFile.write(
                stateFile,
                JSONObject()
                    .put("goodCallCounter", s.goodCallCounter)
                    .put("lastAskAt", s.lastAskAt)
                    .put("askTimes", JSONArray(s.askTimes))
                    .put("ratedCallIds", JSONArray(s.ratedCallIds))
                    .toString().toByteArray(Charsets.UTF_8)
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        const val FILE_NAME = "call-rating.json"
        const val ENDPOINT = "https://oshi-messenger.com/voip/call-rating"
        private const val MAX_REMEMBERED_CALLS = 50

        private val IO = Executors.newSingleThreadExecutor { r ->
            Thread(r, "oshi-call-rating").apply { isDaemon = true }
        }

        /** The default poster. Separate so a test can replace it without a socket. */
        fun postJson(body: String, endpoint: String = ENDPOINT) {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
    }
}
