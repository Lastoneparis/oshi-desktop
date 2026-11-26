package com.oshi.desktop.call.bench

import com.oshi.desktop.call.CallMediaLeg
import com.oshi.desktop.call.CallMediaSpec
import com.oshi.desktop.call.CallRelayConfig
import com.oshi.desktop.call.media.CallAudioSession
import org.junit.Assume
import org.junit.Test
import java.net.DatagramSocket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.locks.LockSupport

/**
 * __CALL_FLUIDITY_BENCH_2026_11_21__ Two real desktop call legs, in real time, through a
 * local `:8089` relay that degrades the network ([ImpairedUdpRelay]). Everything between
 * the microphone and the speaker is the shipped code — capture pump, Opus encoder, AES-GCM
 * sealing, the relay client, the receive path, the playout buffer and the render pump. Only
 * the two devices are synthetic: a "microphone" that produces [FluidityProbe]'s signal at
 * its own clock (optionally skewed) and a "speaker" that drains 48 samples/ms from a
 * 4-frame buffer like a `SourceDataLine`, timestamping what it plays.
 *
 * Gate: `OSHI_FLUIDITY_BENCH=1`. `OSHI_FLUIDITY_SCENARIOS=clean,jitter 100ms` narrows it.
 * Never touches production, the Keychain, a real microphone or speaker.
 *
 * Written to run unchanged on the code BEFORE `__CALL_FLUIDITY_2026_11_21__` (it reaches the
 * newer seams by reflection) so that the before/after numbers come from the same bench.
 */
class CallFluidityBench {

    private val rng = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    /** Synthetic mic + speaker on the relay's clock. */
    private class Devices(private val clockMs: () -> Double, private val skewPpm: Double) {
        var micStartMs = -1.0; var micCursor = 0L
        var spkStartMs = -1.0; var written = 0L; val outLock = Object()
        var out = ShortArray(48_000 * 30); var deviceUnderrunSamples = 0L
        private val cap = 4 * 960

        fun read(b: ByteArray, off: Int, len: Int): Int {
            if (micStartMs < 0) micStartMs = clockMs()
            val n = len / 2
            val dueMs = micStartMs + (micCursor + n) / (48.0 * (1 + skewPpm * 1e-6))
            while (true) { val w = dueMs - clockMs(); if (w <= 0) break; LockSupport.parkNanos((w * 1e6).toLong().coerceAtMost(2_000_000)) }
            for (i in 0 until n) {
                val v = FluidityProbe.sample(micCursor + i).toInt()
                b[off + 2 * i] = v.toByte(); b[off + 2 * i + 1] = (v shr 8).toByte()
            }
            micCursor += n
            return n * 2
        }

        private fun consumed(): Long = if (spkStartMs < 0) 0 else ((clockMs() - spkStartMs) * 48).toLong()
        fun queuedBytes(): Int = synchronized(outLock) { (maxOf(0L, written - consumed()) * 2).toInt() }

        fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(outLock) {
                if (spkStartMs < 0) spkStartMs = clockMs()
                val c = consumed()
                if (written < c) { deviceUnderrunSamples += c - written; written = c }  // played zeros
                val n = len / 2
                if (written + n > out.size) out = out.copyOf(maxOf(out.size * 2, (written + n).toInt()))
                for (i in 0 until n) out[(written + i).toInt()] = ((b[off + 2 * i + 1].toInt() shl 8) or (b[off + 2 * i].toInt() and 0xFF)).toShort()
                written += n
            }
            while (written - consumed() > cap) LockSupport.parkNanos(1_000_000)
        }

        fun output(): ShortArray = synchronized(outLock) { out.copyOf(written.toInt()) }
    }

    private class BenchSession(spec: CallMediaSpec, send: (ByteArray) -> Unit, val dev: Devices) :
        CallAudioSession(spec.sessionKey, spec.nonceSalt, spec.isCaller, send) {
        // What `CallMedia.real` does for a peer that advertised 0x20 (every current build).
        init { useOpus = spec.opus; useWbAdpcm = spec.wbAdpcm }
        override fun openDevices(): AudioDevices {
            val read: (ByteArray, Int, Int) -> Int = { b, o, l -> dev.read(b, o, l) }
            val write: (ByteArray, Int, Int) -> Unit = { b, o, l -> dev.write(b, o, l) }
            val close: () -> Unit = {}
            val queued: () -> Int = { dev.queuedBytes() }
            val ctors = AudioDevices::class.java.constructors
            ctors.firstOrNull { it.parameterCount == 4 }?.let { return it.newInstance(read, write, close, queued) as AudioDevices }
            return ctors.first { it.parameterCount == 3 }.newInstance(read, write, close) as AudioDevices
        }
        fun jbDelayMs(): Int = runCatching { javaClass.superclass.getMethod("playoutDelayMs").invoke(this) as Int }
            .getOrElse { playback.size() * 20 }
    }

    class Live(val scenario: String, val dir: String, val r: FluidityProbe.Result, val jbMean: Double, val jbMax: Int, val counters: String)

    private fun runScenario(spec: NetworkImpairment.Spec, seconds: Int = 20): List<Live> {
        val nets = HashMap<String, Long>()
        val relay = ImpairedUdpRelay { sender -> NetworkImpairment(spec, 7L + (nets.getOrPut(sender) { nets.size.toLong() })) }
        val clock = { relay.nowMs() }
        val callId = UUID.randomUUID().toString().uppercase()
        val key = bytes(32); val salt = bytes(4)
        val aKey = Base64.getEncoder().encodeToString(bytes(32)); val bKey = Base64.getEncoder().encodeToString(bytes(32))
        val sessions = HashMap<String, BenchSession>()
        fun leg(isCaller: Boolean, self: String, peer: String): CallMediaLeg {
            val s = CallMediaSpec(callId, key, salt, isCaller, selfKey = self, peerKey = peer, opus = true)
            val dev = Devices(clock, if (isCaller) spec.clockSkewPpm else 0.0)
            return CallMediaLeg(
                spec = s,
                datagram = DatagramSocket(0),
                audioFor = { send -> BenchSession(s, send, dev).also { sessions[if (isCaller) "A" else "B"] = it } },
                stunServer = null,
                log = {},
                hostCandidates = { emptyList() },
                relayConfig = CallRelayConfig(turn = null, udpRelay = relay.address, relayOnly = true),
            )
        }
        val a = leg(true, aKey, bKey); val b = leg(false, bKey, aKey)
        val jb = HashMap<String, MutableList<Int>>()
        val stats = HashMap<String, String>()
        try {
            a.start(); b.start()
            val end = clock() + seconds * 1000 + 1500
            while (clock() < end) {
                Thread.sleep(100)
                for ((k, s) in sessions) jb.getOrPut(k) { ArrayList() } += s.jbDelayMs()
            }
            for ((k, s) in sessions) stats[k] = runCatching { s.javaClass.superclass.getMethod("playoutStats").invoke(s) as String }.getOrDefault("")
        } finally { a.close(); b.close(); relay.close() }
        return listOf("A" to "B", "B" to "A").map { (from, to) ->
            val tx = sessions[from]!!.dev; val rx = sessions[to]!!
            val inputRate = 48_000.0 * (1 + (if (from == "A") spec.clockSkewPpm else 0.0) * 1e-6)
            val inputSamples = (seconds * inputRate).toLong()
            val res = FluidityProbe.analyze(rx.dev.output(), rx.dev.spkStartMs, tx.micStartMs, inputSamples, inputRate,
                outageAtMs = if (spec.outageAtMs >= 0) (spec.outageAtMs - tx.micStartMs).toLong() else -1, outageMs = spec.outageMs,
                reference = FluidityReference.cachedReference(inputSamples / 960 + 1))
            val series = jb[to] ?: mutableListOf()
            Live(spec.name, "$from→$to", res, series.average(), series.maxOrNull() ?: 0,
                "rendered=${rx.framesRendered.get()} silenced=${rx.framesSilenced.get()} deviceUnderrunMs=${rx.dev.deviceUnderrunSamples / 48} " +
                    (stats[to] ?: ""))
        }
    }

    @Test
    fun liveScenarios() {
        Assume.assumeTrue("set OSHI_FLUIDITY_BENCH=1", System.getenv("OSHI_FLUIDITY_BENCH") == "1")
        val only = System.getenv("OSHI_FLUIDITY_SCENARIOS")?.split(',')?.map { it.trim() }?.toSet()
        val label = System.getenv("OSHI_FLUIDITY_LABEL") ?: "build"
        val rows = ArrayList<Live>()
        for (s in NetworkImpairment.SCENARIOS) {
            if (only != null && s.name !in only) continue
            val r = runScenario(s)
            r.forEach { println("[fluidity] ${it.scenario} ${it.dir}: gap=${"%.2f".format(it.r.gapRatio * 100)}% m2e=${it.r.medianLatency} ${it.counters}") }
            rows += r
        }
        val t = buildString {
            appendLine("| scenario | dir | gap % | gaps/min | m2e median ms | m2e p95 | drift ms | JB mean/max ms | post-outage gap % | post-outage extra ms | counters |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
            for (w in rows) appendLine("| ${w.scenario} | ${w.dir} | ${FluidityReference.fmt(w.r.gapRatio * 100, 1)} | ${FluidityReference.fmt(w.r.gapsPerMin)} | ${FluidityReference.fmt(w.r.medianLatency)} | ${FluidityReference.fmt(w.r.p95Latency)} | ${FluidityReference.fmt(w.r.driftMs)} | ${FluidityReference.fmt(w.jbMean)}/${w.jbMax} | ${FluidityReference.fmt(w.r.postOutageGapRatio * 100, 1)} | ${FluidityReference.fmt(w.r.postOutageExtraMs)} | ${w.counters} |")
        }
        println(t)
        java.io.File("build/fluidity-live-desktop-$label.md").writeText(t)
    }
}
