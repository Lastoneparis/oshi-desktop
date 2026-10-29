package com.oshi.desktop.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CALL_QUALITY_TIERS_2026_10_28__ The quality badge PARITY row 2.1-d listed as not ported.
 *
 * Two things are under test, and they are deliberately separate:
 *
 *  1. [CallQualityStats.quality] must answer exactly what iOS answers. That is checked
 *     here branch by branch, and then as a whole against a digest iOS computes too
 *     (`CallQualityParityTests.swift`) — because matching test NAMES do not prove two
 *     cores agree, a lesson this repository has already paid for.
 *  2. [CallQualityStats.measured] is this client's own addition, and the tests for it are
 *     about refusing to flatter rather than about parity.
 */
class CallQualityTest {

    // ------------------------------------------------------------------ the iOS thresholds

    @Test
    fun `a clean line is excellent`() {
        assertEquals(CallQuality.EXCELLENT, CallQualityStats(latencyMs = 40, lossPercent = 0.5, samples = 50).quality)
    }

    @Test
    fun `each tier is bounded above by the next`() {
        // The figures are iOS's, read off `CallQualityStats.quality`. One assertion per
        // branch, each just inside its band, so a reordered `if` cannot pass.
        assertEquals(CallQuality.EXCELLENT, CallQualityStats(latencyMs = 99, lossPercent = 1.9).quality)
        assertEquals(CallQuality.GOOD, CallQualityStats(latencyMs = 100, lossPercent = 1.9).quality)
        assertEquals(CallQuality.GOOD, CallQualityStats(latencyMs = 40, lossPercent = 2.0).quality)
        assertEquals(CallQuality.FAIR, CallQualityStats(latencyMs = 200, lossPercent = 1.0).quality)
        assertEquals(CallQuality.FAIR, CallQualityStats(latencyMs = 40, lossPercent = 5.0).quality)
        assertEquals(CallQuality.POOR, CallQualityStats(latencyMs = 300, lossPercent = 1.0).quality)
        assertEquals(CallQuality.POOR, CallQualityStats(latencyMs = 40, lossPercent = 10.0).quality)
        assertEquals(CallQuality.VERY_POOR, CallQualityStats(latencyMs = 40, lossPercent = 20.0).quality)
    }

    @Test
    fun `jitter can only deny excellent, never push below good`() {
        // THE FIX, not a detail. iOS measured call 6216CD2F — 29 min, WiFi to 5G — with
        // raw jitter at 22-43 ms and p95 bursts to 300 ms while 0.0% of audio was lost:
        // the buffer absorbed all of it, and judging on raw jitter marked whichever side
        // held the 5G leg "poor" for the whole of a perfect call. So the 150 ms test lives
        // in the EXCELLENT branch alone. A tidy-up that moves it into the second branch
        // reintroduces that defect, and this test is what stops it.
        val jittery = CallQualityStats(latencyMs = 10, lossPercent = 0.0, jitterMs = 300)
        assertEquals(CallQuality.GOOD, jittery.quality)
        assertEquals(
            "a perfect line with huge jitter must land at GOOD, not below",
            CallQuality.GOOD,
            CallQualityStats(latencyMs = 1, lossPercent = 0.0, jitterMs = 10_000).quality,
        )
        assertEquals(
            "149 ms of jitter still allows excellent — the bound is 150",
            CallQuality.EXCELLENT,
            CallQualityStats(latencyMs = 10, lossPercent = 0.0, jitterMs = 149).quality,
        )
    }

    @Test
    fun `a latency of 400ms or more is discarded, not punished`() {
        // At that magnitude iOS says the figure is an HTTP round trip to the VPS rather
        // than the call, and replaces it with 0. Mirrored, including the consequence: a
        // genuinely half-second call is judged on loss alone and can read EXCELLENT.
        assertEquals(CallQuality.EXCELLENT, CallQualityStats(latencyMs = 400, lossPercent = 0.0).quality)
        assertEquals(CallQuality.EXCELLENT, CallQualityStats(latencyMs = 5_000, lossPercent = 0.0).quality)
        assertEquals(
            "399 ms is still believed, and 399 is beyond every latency bound",
            CallQuality.POOR,
            CallQualityStats(latencyMs = 399, lossPercent = 0.0).quality,
        )
    }

    @Test
    fun `rank groups the three unusable tiers together`() {
        assertEquals(4, CallQuality.EXCELLENT.rank)
        assertEquals(3, CallQuality.GOOD.rank)
        assertEquals(2, CallQuality.FAIR.rank)
        assertEquals(1, CallQuality.POOR.rank)
        // iOS puts all three at 0: they are equally unusable, and a move between them is
        // not an improvement worth announcing.
        assertEquals(0, CallQuality.VERY_POOR.rank)
        assertEquals(0, CallQuality.RECONNECTING.rank)
        assertEquals(0, CallQuality.DISCONNECTED.rank)
    }

    @Test
    fun `every tier names a key that the catalogs actually carry`() {
        // A tier whose key is absent renders as the raw key on screen, in 34 languages.
        val en = CallQualityTest::class.java.getResourceAsStream("/i18n/Localizable/en.json")!!
            .readBytes().decodeToString()
        for (q in CallQuality.entries) {
            assertTrue("missing catalog key ${q.key}", en.contains("\"${q.key}\""))
        }
    }

    // ------------------------------------------------------- refusing to flatter

    @Test
    fun `nothing measured shows NO tier rather than a flattering one`() {
        // The trap. Every threshold is an upper bound, so all-zero clears them all and the
        // classifier answers EXCELLENT on no evidence whatsoever. A badge built straight
        // off `quality` would state "Excellent" over a call nobody has heard a frame of —
        // an indicator that extinguishes doubt instead of reporting it, which is how a
        // freshness light once read "live" with one of its two sources four days stale.
        val nothing = CallQualityStats()
        assertEquals("the classifier still agrees with iOS", CallQuality.EXCELLENT, nothing.quality)
        assertFalse(nothing.measured)
        assertNull("but the BADGE must show nothing", nothing.displayQuality)
        assertNull(nothing.displayLatencyMs)
    }

    @Test
    fun `either audio heard or a round trip measured earns a tier`() {
        assertTrue(CallQualityStats(samples = 1).measured)
        assertTrue(CallQualityStats(latencyMs = 1).measured)
        assertEquals(CallQuality.EXCELLENT, CallQualityStats(samples = 1).displayQuality)
    }

    // ------------------------------------------------------- the window arithmetic

    @Test
    fun `loss is the share of frames the renderer had to fill with silence`() {
        val s = CallQualityStats.of(renderedDelta = 50, silencedDelta = 5, latencyMs = 30)
        assertEquals(10.0, s.lossPercent, 1e-9)
        assertEquals(50L, s.samples)
        assertEquals(CallQuality.POOR, s.quality)   // loss 10.0 is outside FAIR's `< 10`
    }

    @Test
    fun `a window with no frames is not a window with no loss`() {
        val s = CallQualityStats.of(renderedDelta = 0, silencedDelta = 0, latencyMs = 0)
        assertEquals(0.0, s.lossPercent, 1e-9)
        assertFalse("no frames and no pong is nothing to judge", s.measured)
    }

    @Test
    fun `counters read across a call boundary cannot invent a tier`() {
        // A second call in the same session starts fresh counters, so a stale baseline
        // makes the delta negative. Unclamped, −30 of 100 would be a loss of −30% (reads
        // EXCELLENT) and 300 of 100 would be 300% (reads VERY_POOR). Both are nonsense,
        // and the nonsense would be rendered with full confidence.
        val negative = CallQualityStats.of(renderedDelta = -100, silencedDelta = -30, latencyMs = 0)
        assertEquals(0.0, negative.lossPercent, 1e-9)
        assertEquals(0L, negative.samples)
        assertFalse(negative.measured)

        val impossible = CallQualityStats.of(renderedDelta = 100, silencedDelta = 300, latencyMs = 0)
        assertEquals("silenced cannot exceed rendered", 100.0, impossible.lossPercent, 1e-9)
        assertEquals(CallQuality.VERY_POOR, impossible.quality)

        assertEquals(
            "a negative round trip is not a fast one",
            0,
            CallQualityStats.of(renderedDelta = 10, silencedDelta = 0, latencyMs = -50).latencyMs,
        )
    }

    @Test
    fun `every frame silenced is very poor, and none is excellent`() {
        assertEquals(CallQuality.VERY_POOR, CallQualityStats.of(50, 50, 20).quality)
        assertEquals(CallQuality.EXCELLENT, CallQualityStats.of(50, 0, 20).quality)
    }
}
