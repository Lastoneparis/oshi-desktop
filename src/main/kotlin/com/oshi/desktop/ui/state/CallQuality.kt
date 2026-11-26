package com.oshi.desktop.ui.state

import com.oshi.desktop.i18n.catalogKey

/**
 * __CALL_QUALITY_TIERS_2026_10_28__ The call-quality badge iOS has and this client did not.
 *
 * PARITY row 2.1-d listed `call-quality tiers (Excellent…Very poor, latency ms)` as
 * **not ported** — the one gap in that row alongside voice effects. Checked before porting,
 * because a row claiming a gap has been wrong eight times in this repository: iOS really
 * does have `ConnectionQuality` + `CallQualityStats.quality`
 * (`VoiceCallManager.swift:2097-2184`) and a `grep` for `callQuality|veryPoor|qualityTier`
 * over this module returned nothing. The gap was real.
 *
 * What was NOT missing: the seven labels. `call.quality.excellent` … `.disconnected` are
 * already checked in, in all 34 catalogs, extracted from iOS when row 1.5 was done. So this
 * file adds a classifier and a tier, not a translation.
 *
 * ## [quality] is iOS's function, term for term — on purpose
 *
 * The thresholds below are not a judgement of mine and must not drift. They carry a
 * measurement: iOS's own comment (`__QUALITY_FROM_WHAT_IS_HEARD_2026_09_22__`) records call
 * `6216CD2F`, 29 minutes, WiFi ↔ 5G, where raw jitter ran 22–43 ms with p95 bursts to
 * 300 ms while **0.0 % of audio was actually lost** — the buffer absorbed all of it. Judging
 * on raw jitter therefore marked whichever side held the 5G leg as "poor" throughout a
 * perfect call. Hence:
 *
 *  - jitter is consulted **only** to deny `EXCELLENT`, never to push a call below [GOOD] —
 *    look at the branches: the 150 ms test appears in the first one and in no other. That
 *    asymmetry is the fix, so a "tidy-up" that moves the test into the second branch
 *    reintroduces the defect.
 *  - a latency at or above 400 ms is **discarded, not punished**: at that magnitude the
 *    figure is an HTTP round trip to the VPS rather than the call, so iOS replaces it with
 *    0. Mirrored here, including the consequence that a genuinely 500 ms call is judged on
 *    loss alone.
 *  - loss is what is HEARD — frames the renderer had to fill with silence after the jitter
 *    buffer — not packets missing from a socket. See `CallAudioSession.framesSilenced`.
 *
 * Both platforms run one shared table of inputs through this function and compare a single
 * digest (`CallQualityParityTest`), because matching test NAMES do not prove two cores
 * answer the same way — this repository has already paid for that lesson once.
 *
 * ## What this file deliberately does NOT do
 *
 * It does not decide whether a badge is shown. [CallQualityStats.measured] answers that
 * separately, and the reason is a trap iOS walks into and this client should not: the
 * classifier maps "no data at all" (0 ms, 0 %, 0 ms) to [EXCELLENT], because every
 * threshold is an upper bound and zero passes them all. On iOS that is masked by the badge
 * only ever being read mid-call. Here, a connected call with nothing measured yet would
 * display a confident **Excellent** — an indicator that extinguishes doubt instead of
 * reporting it, which is exactly how a freshness light once read "live" with one of its two
 * sources four days stale. So [CallQualityStats.measured] is false until there is something
 * to judge, and the caller shows no tier at all rather than a flattering one.
 *
 * ## Why the keys are wrapped in [catalogKey]
 *
 * `catalogKey` returns its argument unchanged — it is a marker, not a lookup. It is what
 * tells `SourceKeys.ACCESSORS` that a string literal is a catalog key *stored as data and
 * rendered later with `t()`*, which is exactly what happens here: the badge in
 * `CallOverlay` calls `t(q.key)`. Written as bare literals, `CatalogAuditTest` reports all
 * seven as *"real catalog keys but never reach the catalog"* — and it is right to, because
 * from a scanner's view a key nothing resolves is indistinguishable from a key that is
 * missing from every catalog, which the audit calls its loudest failure. So the wrapper
 * stays, and a "simplification" back to bare strings turns this file red.
 */
enum class CallQuality(val key: String) {
    EXCELLENT(catalogKey("call.quality.excellent")),
    GOOD(catalogKey("call.quality.good")),
    FAIR(catalogKey("call.quality.fair")),
    POOR(catalogKey("call.quality.poor")),
    VERY_POOR(catalogKey("call.quality.very_poor")),

    /**
     * Nothing from the peer for `CallLiveness.RECONNECT_AFTER_MS`. Set by the liveness
     * watchdog, never returned by [CallQualityStats.quality] — the same split iOS has,
     * where `.reconnecting` is assigned from two independent sources and is not a tier the
     * stats can produce.
     */
    RECONNECTING(catalogKey("call.quality.reconnecting")),
    DISCONNECTED(catalogKey("call.quality.disconnected"));

    /**
     * Higher is better, for comparing two tiers. iOS `CallQualityStats.rank`, which groups
     * `veryPoor`, `reconnecting` and `disconnected` together at 0 — they are equally
     * unusable and a transition between them is not an improvement to announce.
     */
    val rank: Int
        get() = when (this) {
            EXCELLENT -> 4
            GOOD -> 3
            FAIR -> 2
            POOR -> 1
            VERY_POOR, RECONNECTING, DISCONNECTED -> 0
        }

    /** True for the two tiers iOS paints red and warns about once per call. */
    val isBad: Boolean get() = this == POOR || this == VERY_POOR
}

/**
 * One second of measured call, and the tier it earns.
 *
 * Every field is a WINDOW, not a lifetime total. A call whose first second was rough must
 * not read "poor" for the next half hour, and `CallScreenModel` therefore feeds per-tick
 * deltas rather than the counters themselves.
 *
 * @param latencyMs round-trip time on the selected path, 0 when no pong has come back yet.
 * @param lossPercent share of rendered audio frames the renderer had to fill with silence.
 * @param jitterMs 0 on this client, and honestly so: the playback queue is a FIXED 60 ms
 *   de-jitter buffer (`CallAudioSession`) with no measured arrival spread to report. The
 *   parameter exists so this function stays term-for-term identical to iOS's and so the
 *   shared parity table can exercise the 150 ms branch; passing a measured value later
 *   changes no other line.
 * @param samples how many render frames the window covered. Zero means nothing was heard.
 */
data class CallQualityStats(
    val latencyMs: Int = 0,
    val lossPercent: Double = 0.0,
    val jitterMs: Int = 0,
    val samples: Long = 0,
) {
    /**
     * iOS `CallQualityStats.quality`, term for term. See this file's header before changing
     * any constant: each one carries a measurement, and the jitter asymmetry is a fix.
     */
    val quality: CallQuality
        get() {
            // At or above 400 ms the figure is an HTTP round trip to the VPS, not the call.
            // Discarded rather than punished, exactly as iOS does.
            val latency = if (latencyMs > 0 && latencyMs < 400) latencyMs else 0
            val loss = lossPercent
            // The 150 ms jitter test belongs to THIS branch alone — see the header.
            if (loss < 2 && latency < 100 && jitterMs < 150) return CallQuality.EXCELLENT
            if (loss < 5 && latency < 200) return CallQuality.GOOD
            if (loss < 10 && latency < 300) return CallQuality.FAIR
            if (loss < 20) return CallQuality.POOR
            return CallQuality.VERY_POOR
        }

    /**
     * Whether there is anything to judge at all.
     *
     * Not part of [quality], so that the classifier stays byte-identical to iOS's while the
     * BADGE refuses to flatter. A tier is earned by one of two things: audio actually
     * rendered, or a round trip actually measured. With neither, every threshold above is
     * passed by zero and the call would read EXCELLENT on no evidence whatsoever.
     */
    val measured: Boolean get() = samples > 0 || latencyMs > 0

    /** The tier to display, or null when nothing has been measured yet. */
    val displayQuality: CallQuality? get() = if (measured) quality else null

    /** Latency to show beside the tier, or null when no pong has returned. iOS shows ms. */
    val displayLatencyMs: Int? get() = latencyMs.takeIf { it > 0 }

    companion object {
        /**
         * Build a window from two render counters and a round trip.
         *
         * `silenced` is the subset of `rendered` the renderer could not fill from the
         * queue. Guarded rather than trusted: a counter read across a reset (a second call
         * in the same session) can hand back a negative delta or a silenced count above
         * the rendered one, and a loss of −300 % would read EXCELLENT while a loss of
         * 4000 % would read VERY_POOR. Both are nonsense, so the window is clamped to
         * what is arithmetically possible.
         */
        fun of(renderedDelta: Long, silencedDelta: Long, latencyMs: Int, jitterMs: Int = 0): CallQualityStats {
            val rendered = renderedDelta.coerceAtLeast(0)
            val silenced = silencedDelta.coerceIn(0, rendered)
            val loss = if (rendered > 0) silenced * 100.0 / rendered else 0.0
            return CallQualityStats(
                latencyMs = latencyMs.coerceAtLeast(0),
                lossPercent = loss,
                jitterMs = jitterMs.coerceAtLeast(0),
                samples = rendered,
            )
        }
    }
}
