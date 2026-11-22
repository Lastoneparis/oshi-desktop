package com.oshi.desktop.call.bench

import com.oshi.desktop.call.media.AdaptiveJitterBuffer
import com.oshi.desktop.call.media.OpusCallDecoder
import com.oshi.desktop.call.media.OpusCallEncoder
import com.oshi.desktop.call.media.OpusWire
import com.oshi.desktop.call.media.PlaybackBuffer
import org.junit.Assume
import org.junit.Test
import java.util.PriorityQueue

/**
 * __CALL_FLUIDITY_BENCH_2026_11_21__ The desktop receive chain in VIRTUAL time: real Opus
 * encoder → [NetworkImpairment] → real decoder → playout buffer → a speaker that drains
 * 48 samples per ms from a 4-frame device buffer (the `SourceDataLine` the session opens).
 * Deterministic and ~1 s per scenario, so a change can be judged before the real-time
 * bench ([CallFluidityBench]) confirms it over real sockets.
 *
 * `OLD` reproduces `CallAudioSession` up to 1.6.2: decode on arrival, a 3-frame FIFO that
 * drops its oldest, and a render loop that waits up to one frame for audio then writes
 * silence. `NEW` is [AdaptiveJitterBuffer].
 *
 * Gate: `OSHI_FLUIDITY_BENCH=1` (prints a table; asserts nothing — the unit tests do).
 */
class FluiditySimBench {

    interface Receiver {
        fun onPacket(nowMs: Long, payload: ByteArray)
        fun tick(nowMs: Long)
        val output: ShortArray
        val outputStartMs: Double
        fun jbDelayMs(): Int
        fun counters(): String
    }

    /** A device buffer of [cap] samples draining 48/ms; [sink] collects what it plays. */
    class Device(private val cap: Int = 4 * 960) {
        private val played = ArrayList<ShortArray>()
        private var queue = ArrayDeque<Short>()
        var startMs = -1.0
        fun space(): Int = cap - queue.size
        fun write(nowMs: Long, pcm: ShortArray) {
            if (startMs < 0) startMs = nowMs.toDouble()
            for (s in pcm) queue.addLast(s)
        }
        fun drain() {
            if (startMs < 0) return
            val o = ShortArray(48)
            for (i in 0 until 48) o[i] = if (queue.isEmpty()) 0 else queue.removeFirst()
            played += o
        }
        fun output(): ShortArray {
            val r = ShortArray(played.size * 48); var o = 0
            for (p in played) { System.arraycopy(p, 0, r, o, p.size); o += p.size }
            return r
        }
    }

    class OldDesktop : Receiver {
        private val dec = OpusCallDecoder()
        private val buf = ArrayDeque<ShortArray>()
        private val dev = Device()
        private var waitingSince = -1L
        var silenced = 0L; var overflow = 0L; var frames = 0L
        override fun onPacket(nowMs: Long, payload: ByteArray) {
            for (f in dec.decode(payload)) {
                while (buf.size >= PlaybackBuffer.MAX_FRAMES) { buf.removeFirst(); overflow++ }
                buf.addLast(f.pcm)
            }
        }
        override fun tick(nowMs: Long) {
            dev.drain()
            if (dev.startMs < 0 && buf.isEmpty()) return
            if (dev.space() < 960) return                 // write() is blocking
            if (waitingSince < 0) waitingSince = nowMs   // take(waitMs) starts
            val f = buf.removeFirstOrNull()
            if (f != null) { dev.write(nowMs, f); waitingSince = -1; frames++ }
            else if (nowMs - waitingSince >= 20) { dev.write(nowMs, ShortArray(960)); waitingSince = -1; silenced++; frames++ }
        }
        override val output get() = dev.output()
        override val outputStartMs get() = dev.startMs
        override fun jbDelayMs() = buf.size * 20 + (4 * 960 - dev.space()) / 48
        override fun counters() = "silenced=$silenced/$frames overflow=$overflow resyncs=${dec.resyncs}"
    }

    /** The new chain: frames queued encoded by codec seq, decoded at playout, adaptive playout. */
    class NewDesktop : Receiver {
        var now = 0L
        val opus = com.oshi.desktop.call.media.OpusPlayoutDecoder()
        val jb = AdaptiveJitterBuffer(opus, clock = { now })
        private val dev = Device()
        private val unwrap = com.oshi.desktop.call.media.SeqUnwrapper()
        override fun onPacket(nowMs: Long, payload: ByteArray) {
            now = nowMs
            com.oshi.desktop.call.media.OpusPlayoutDecoder.file(payload, jb, unwrap)
        }
        override fun tick(nowMs: Long) {
            now = nowMs
            dev.drain()
            if (dev.startMs < 0 && jb.isEmpty()) return
            // The session keeps at most [CallAudioSession.DEVICE_AHEAD_FRAMES] in the line.
            while (4 * 960 - dev.space() < 960 * com.oshi.desktop.call.media.CallAudioSession.DEVICE_AHEAD_FRAMES) dev.write(nowMs, jb.pull())
        }
        override val output get() = dev.output()
        override val outputStartMs get() = dev.startMs
        override fun jbDelayMs() = jb.delayMs() + (4 * 960 - dev.space()) / 48
        override fun counters() = "played=${jb.framesPlayed} expand=${jb.framesExpanded} lossConceal=${jb.framesLossConcealed} silence=${jb.framesSilence} late=${jb.framesLate} skipped=${jb.framesSkipped} accelSamples=${jb.samplesAccelerated} jumps=${jb.jumps} flushes=${jb.flushes} fec=${opus.fecDecodes} target=${jb.targetMs()}"
    }

    class Row(val scenario: String, val impl: String, val r: FluidityProbe.Result, val jbMean: Double, val jbMax: Int, val counters: String)

    companion object {
        fun run(spec: NetworkImpairment.Spec, rx: Receiver, durationMs: Long = 20_000, seed: Long = 7, redundancy: Int = OpusWire.MAX_REDUNDANT, fecPct: Int = 0, label: String? = null): Row {
            val net = NetworkImpairment(spec, seed)
            val enc = OpusCallEncoder(32_000, 8, redundancy, fecPct)
            val frameMs = 20.0 / (1 + spec.clockSkewPpm * 1e-6)
            val q = PriorityQueue<Pair<Double, ByteArray>>(compareBy { it.first })
            var nextSend = 20.0  // frame k is complete (and sent) 20 ms after it starts
            var k = 0L
            val jb = ArrayList<Int>()
            for (t in 0L..durationMs + 1500) {
                while (t <= durationMs && nextSend <= t) {
                    for (p in enc.encode(FluidityProbe.frame(k))) net.route(nextSend)?.let { q.add(it to p) }
                    k++; nextSend += frameMs
                }
                while (q.isNotEmpty() && q.peek().first <= t) rx.onPacket(t, q.poll().second)
                rx.tick(t)
                if (t % 100 == 0L && rx.outputStartMs >= 0) jb += rx.jbDelayMs()
            }
            val res = FluidityProbe.analyze(rx.output, rx.outputStartMs, 0.0, k * 960, 48_000.0 * (1 + spec.clockSkewPpm * 1e-6),
                outageAtMs = spec.outageAtMs, outageMs = spec.outageMs, reference = FluidityReference.cachedReference(k))
            return Row(spec.name, label ?: rx.javaClass.simpleName, res, jb.average(), jb.maxOrNull() ?: 0, rx.counters())
        }

        fun table(rows: List<Row>): String = buildString {
            appendLine("| scenario | chain | gap % | gaps/min | m2e median ms | m2e p95 | drift ms | JB mean/max ms | post-outage gap % | post-outage extra ms |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            for (w in rows) appendLine("| ${w.scenario} | ${w.impl} | ${FluidityReference.fmt(w.r.gapRatio * 100, 1)} | ${FluidityReference.fmt(w.r.gapsPerMin, 0)} | ${FluidityReference.fmt(w.r.medianLatency)} | ${FluidityReference.fmt(w.r.p95Latency)} | ${FluidityReference.fmt(w.r.driftMs)} | ${FluidityReference.fmt(w.jbMean)}/${w.jbMax} | ${FluidityReference.fmt(w.r.postOutageGapRatio * 100, 1)} | ${FluidityReference.fmt(w.r.postOutageExtraMs)} |")
        }
    }

    @Test
    fun simulate() {
        Assume.assumeTrue("set OSHI_FLUIDITY_BENCH=1", System.getenv("OSHI_FLUIDITY_BENCH") == "1")
        val rows = ArrayList<Row>()
        for (s in NetworkImpairment.SCENARIOS) {
            rows += run(s, OldDesktop())
            rows += run(s, NewDesktop())
        }
        if (System.getenv("OSHI_FLUIDITY_VARIANTS") == "1") for (s in NetworkImpairment.SCENARIOS.filter { it.lossPct > 0 || it.burstLossPct > 0 || it.jitterMs >= 100 }) {
            rows += run(s, NewDesktop(), fecPct = 10, label = "New+LBRR10")
            rows += run(s, NewDesktop(), redundancy = 3, label = "New+red3")
            rows += run(s, NewDesktop(), redundancy = 1, fecPct = 10, label = "New+red1+LBRR")
        }
        val t = table(rows)
        println(t)
        rows.forEach { println("${it.scenario} ${it.impl}: ${it.counters} | gaps by second: " + it.r.gapAtMs.groupingBy { g -> (g / 1000).toInt() }.eachCount().toSortedMap()) }
        java.io.File("build/fluidity-sim-desktop.md").writeText(t)
    }
}
