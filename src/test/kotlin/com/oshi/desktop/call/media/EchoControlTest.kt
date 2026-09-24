package com.oshi.desktop.call.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebRTC APM on the call path, measured on synthetic signals — no devices.
 *
 * The echo path models a laptop: the far end's speech leaves the speaker, comes back into
 * the microphone 60 ms later, attenuated and low-passed, plus a constant microphone hiss.
 * That is the situation a real iPhone call reported on 2026-09-23 ("strong echo, strong
 * hiss").
 */
class EchoControlTest {

    private val frame = CallAudio.BYTES_PER_FRAME      // 20 ms, 960 samples
    private val samples = CallAudio.SAMPLES_PER_FRAME
    private val sr = CallAudio.SAMPLE_RATE.toInt()

    private fun pcm(x: DoubleArray): ByteArray {
        val b = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) b.putShort((v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort())
        return b.array()
    }

    private fun samplesOf(p: ByteArray): DoubleArray {
        val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(p.size / 2) { b.short / 32768.0 }
    }

    private fun power(x: DoubleArray): Double = x.sumOf { it * it } / x.size.coerceAtLeast(1)
    private fun db(p: Double): Double = 10 * log10(p + 1e-20)

    /** Speech-like: a 100-900 Hz harmonic stack, syllable-modulated. */
    private fun farEnd(n: Int, t0: Int): DoubleArray = DoubleArray(n) { i ->
        val t = (t0 + i).toDouble() / sr
        val env = 0.5 + 0.5 * sin(2 * PI * 3.0 * t)
        val f0 = 140 + 40 * sin(2 * PI * 0.7 * t)
        var v = 0.0
        for (h in 1..6) v += sin(2 * PI * f0 * h * t) / h
        0.25 * env * v
    }

    private class EchoPath(delaySamples: Int) {
        private val line = DoubleArray(delaySamples + 1)
        private var w = 0
        private var lp = 0.0
        fun push(x: Double): Double {
            val out = line[w]
            line[w] = x
            w = (w + 1) % line.size
            lp = 0.6 * lp + 0.4 * out                // room + speaker low-pass
            return 0.5 * lp                           // -6 dB coupling
        }
    }

    private fun apmOrSkip(): EchoControl {
        val e = EchoControl.create()
        assumeTrue("WebRTC APM native not available on this host", e != null)
        return e!!
    }

    @Test
    fun `aec3 removes the speaker echo from the microphone - ERLE`() {
        val apm = apmOrSkip()
        try {
            val path = EchoPath(sr * 60 / 1000)
            val rnd = Random(7)
            var t = 0
            var inP = 0.0; var outP = 0.0
            val frames = 50 * 12                                // 12 s
            for (f in 0 until frames) {
                val far = farEnd(samples, t)
                apm.render(pcm(far))
                val mic = DoubleArray(samples) { i -> path.push(far[i]) + 0.0005 * (rnd.nextDouble() * 2 - 1) }
                val cleaned = samplesOf(apm.capture(pcm(mic)))
                if (f >= frames - 50 * 4) { inP += power(mic); outP += power(cleaned) }  // last 4 s
                t += samples
            }
            val erle = db(inP) - db(outP)
            println("[apm] ERLE over the last 4 s: %.1f dB (apm stats: erle=%.1f delay=%d ms)".format(erle, apm.stats()?.echoReturnLossEnhancement ?: Double.NaN, apm.stats()?.delayMs ?: -1))
            assertTrue("echo not suppressed enough: ERLE %.1f dB".format(erle), erle >= 20.0)
        } finally { apm.close() }
    }

    @Test
    fun `noise suppression lowers a steady microphone hiss`() {
        val apm = apmOrSkip()
        try {
            val rnd = Random(11)
            var inP = 0.0; var outP = 0.0
            val frames = 50 * 8
            val silence = ByteArray(frame)
            for (f in 0 until frames) {
                apm.render(silence)
                val mic = DoubleArray(samples) { 0.01 * (rnd.nextDouble() * 2 - 1) }   // about -46 dBFS white hiss
                val cleaned = samplesOf(apm.capture(pcm(mic)))
                if (f >= frames - 50 * 3) { inP += power(mic); outP += power(cleaned) }
            }
            val reduction = db(inP) - db(outP)
            println("[apm] hiss floor: in %.1f dBFS, out %.1f dBFS, reduction %.1f dB".format(db(inP / 150), db(outP / 150), reduction))
            assertTrue("hiss not reduced: %.1f dB".format(reduction), reduction >= 10.0)
        } finally { apm.close() }
    }

    @Test
    fun `the local speaker is kept while echo is removed - double talk`() {
        val apm = apmOrSkip()
        try {
            val path = EchoPath(sr * 60 / 1000)
            var t = 0
            // Converge on echo alone for 8 s.
            repeat(50 * 8) {
                val far = farEnd(samples, t)
                apm.render(pcm(far))
                apm.capture(pcm(DoubleArray(samples) { i -> path.push(far[i]) }))
                t += samples
            }
            // Then 3 s of double talk: a 1 kHz near-end tone on top of the echo.
            var nearIn = 0.0; var outP = 0.0
            repeat(50 * 3) {
                val far = farEnd(samples, t)
                apm.render(pcm(far))
                val near = DoubleArray(samples) { i -> 0.1 * sin(2 * PI * 1000.0 * (t + i) / sr) }
                val mic = DoubleArray(samples) { i -> path.push(far[i]) + near[i] }
                val cleaned = samplesOf(apm.capture(pcm(mic)))
                nearIn += power(near); outP += power(cleaned)
                t += samples
            }
            val kept = db(outP) - db(nearIn)
            println("[apm] double talk: output is %.1f dB relative to the near-end voice alone".format(kept))
            assertTrue("near-end voice was crushed (%.1f dB)".format(kept), kept >= -10.0)
        } finally { apm.close() }
    }

    @Test
    fun `a session with echo control disabled leaves the frame untouched`() {
        System.setProperty("oshi.call.apm", "false")
        try {
            assertTrue(EchoControl.create() == null)
        } finally {
            System.clearProperty("oshi.call.apm")
        }
    }
}
