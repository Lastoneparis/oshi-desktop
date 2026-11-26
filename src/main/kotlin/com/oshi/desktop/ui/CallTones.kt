package com.oshi.desktop.ui

import com.oshi.desktop.ui.state.CallRinger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import kotlin.math.PI
import kotlin.math.sin

/**
 * The window's ring and ringback (__DESKTOP_CALL_UI_2026_09_23__), synthesized — no bundled
 * audio asset, so nothing to license and nothing a packager can forget.
 *
 *  - **Ring** (incoming): two short 0.4 s bursts of a soft two-tone chord (659 + 880 Hz),
 *    then 1.6 s of quiet, looped until [stop] — a "ring-ring" a person recognises as a phone.
 *  - **Ringback** (outgoing): 425 Hz, 1 s on, 4 s off — the ETSI ringback most of Europe
 *    hears, looped until [stop].
 *
 * Best-effort in the strict sense: a machine with no output device, or a device another
 * application holds exclusively, gives silence and never an exception. The call screen is the
 * alert that cannot fail; the tone is its companion. [stop] is idempotent and safe from any
 * thread, because the model calls it on every exit and some exits race each other.
 */
class CallTones : CallRinger {

    private val lock = Any()
    private var clip: Clip? = null

    override fun startIncoming() = play(ring())

    override fun startRingback() = play(ringback())

    override fun stop() {
        val old = synchronized(lock) { clip.also { clip = null } } ?: return
        runCatching { old.stop() }
        runCatching { old.close() }
    }

    private fun play(pcm: ByteArray) {
        stop()
        val c = runCatching {
            AudioSystem.getClip().apply {
                open(FORMAT, pcm, 0, pcm.size)
                loop(Clip.LOOP_CONTINUOUSLY)
            }
        }.getOrNull() ?: return
        synchronized(lock) { clip = c }
    }

    companion object {
        private const val RATE = 44_100f
        private val FORMAT = AudioFormat(RATE, 16, 1, true, false)

        /** One ring cycle: burst, gap, burst, long quiet. */
        internal fun ring(): ByteArray = pcm(
            listOf(0.4 to listOf(659.25, 880.0), 0.2 to emptyList(), 0.4 to listOf(659.25, 880.0), 1.6 to emptyList()),
            amplitude = 0.28,
        )

        /** One ringback cycle: 1 s of 425 Hz, 4 s of quiet. */
        internal fun ringback(): ByteArray = pcm(listOf(1.0 to listOf(425.0), 4.0 to emptyList()), amplitude = 0.18)

        /** Segments of (seconds, frequencies); 10 ms fades so no segment edge clicks. */
        private fun pcm(segments: List<Pair<Double, List<Double>>>, amplitude: Double): ByteArray {
            val total = segments.sumOf { (it.first * RATE).toInt() }
            val out = ByteArray(total * 2)
            var i = 0
            for ((seconds, freqs) in segments) {
                val n = (seconds * RATE).toInt()
                val fade = (0.01 * RATE).toInt()
                for (k in 0 until n) {
                    var v = 0.0
                    if (freqs.isNotEmpty()) {
                        for (f in freqs) v += sin(2 * PI * f * k / RATE)
                        v = v / freqs.size * amplitude * minOf(1.0, k.toDouble() / fade, (n - k).toDouble() / fade)
                    }
                    val s = (v * Short.MAX_VALUE).toInt()
                    out[i * 2] = (s and 0xFF).toByte()
                    out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    i++
                }
            }
            return out
        }
    }
}
