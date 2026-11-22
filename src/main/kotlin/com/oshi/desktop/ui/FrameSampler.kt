package com.oshi.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos

/**
 * Real-window frame times — PARITY.md row 1.2-b, the half `FluidityBench` cannot reach.
 *
 * That row says *"JFR has no compose frame callback, so there is still no real-window
 * p50/p95"*. JFR has none; **Compose does**. `withFrameNanos` suspends until the next frame
 * and hands back that frame's time, so the gap between consecutive resumptions IS the frame
 * interval, measured on the GPU-rasterised window the user actually has rather than on
 * `ImageComposeScene`'s software raster.
 *
 * Off unless `OSHI_FRAME_BENCH=1`, and it allocates one long per frame into a list it
 * clears every report, so leaving the call site in costs a dead `if` on a build nobody
 * benches.
 *
 * ============================================================ WHAT A GAP MEANS HERE
 *
 * A Compose frame clock ticks only when something INVALIDATES. A window sitting idle with
 * nothing animating produces no frames at all, so a long delta is the app having nothing to
 * draw, not a frame that took that long. Reporting a p95 over a quiet window would therefore
 * measure how still the user sat. Both numbers are printed: the distribution over frames
 * that followed another frame within [ACTIVE_GAP_MS], which is drawing, and separately the
 * count of longer gaps, which is idleness. The row's own warning — *"'idle' is not 'nothing
 * was asked of it'"* — is the same point from the other side.
 */
object FrameSampler {

    const val ENV = "OSHI_FRAME_BENCH"

    /** Beyond this a delta is the clock resting, not a slow frame. ~6 frames at 60 Hz. */
    const val ACTIVE_GAP_MS = 100.0

    private const val REPORT_EVERY_NS = 5_000_000_000L

    /** n/p50/p95/max over drawing frames, with idle gaps counted separately. Pure. */
    internal fun summarise(deltasNs: List<Long>): String {
        val ms = deltasNs.map { it / 1e6 }
        val drawing = ms.filter { it <= ACTIVE_GAP_MS }.sorted()
        val idle = ms.size - drawing.size
        if (drawing.isEmpty()) return "[frames] no drawing frames in this window ($idle idle gaps)"
        fun p(q: Double) = drawing[((drawing.size - 1) * q).toInt()]
        val slow = drawing.count { it > 16.7 }
        return "[frames] n=%d p50=%.2f ms p95=%.2f ms max=%.2f ms  over16.7ms=%d (%.1f%%)  idleGaps=%d"
            .format(drawing.size, p(0.5), p(0.95), drawing.last(), slow, 100.0 * slow / drawing.size, idle)
    }

    @Composable
    fun Install(
        enabled: Boolean = System.getenv(ENV) == "1",
        log: (String) -> Unit = ::println,
    ) {
        if (!enabled) return
        LaunchedEffect(Unit) {
            val deltas = ArrayList<Long>(4096)
            var last = 0L
            var lastReport = 0L
            while (true) {
                val now = withFrameNanos { it }
                if (last != 0L) deltas.add(now - last)
                last = now
                if (lastReport == 0L) lastReport = now
                if (now - lastReport >= REPORT_EVERY_NS) {
                    if (deltas.isNotEmpty()) log(summarise(deltas))
                    deltas.clear()
                    lastReport = now
                }
            }
        }
    }
}
