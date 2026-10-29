package com.oshi.desktop.call.media

import com.oshi.desktop.i18n.catalogKey
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.PI

/**
 * __CALL_VOICE_EFFECTS_2026_10_28__ The eight live call voice effects iOS has and this
 * client did not — the last `❌ NOT ported` on PARITY row 2.1-d.
 *
 * Checked before porting, because a row claiming a gap has been wrong eight times in this
 * repository, and because row 2.4 is the opposite trap: a doc describing a desktop gap
 * against an iOS function that had been DELETED (`sendCovert` throws unconditionally, zero
 * callers). Neither applies here. iOS's [CallVoiceEffect] is live and mature — an
 * `AVAudioEngine` graph of `AVAudioUnitTimePitch` + distortion + reverb + delay, wired to a
 * picker in both call screens and a default in Settings, carrying its own hard-won fixes
 * (`__EFFECT_GRAPH_CHURN_2026_08_25__`: rebuilding the graph for an unchanged routing
 * restarted the engine five times in one 15-minute call and three of the six audio-dropout
 * windows in that log fall in the second after a switch). And a `grep` for
 * `pitch|reverb|distort|waveshap` over this module found nothing but an unrelated word.
 *
 * ## The constraint that chooses the algorithm
 *
 * A frame on this wire is exactly [CallAudio.SAMPLES_PER_FRAME] samples — 20 ms, fixed, not
 * a preference — and `pumpCapture` seals one read as one payload. So an effect **may not
 * change the length of a frame**, which rules out the obvious way to change a voice's
 * pitch: resampling. Resampling by 1.3 and sending 739 samples where 960 are expected is a
 * different bug on each codec path (Opus reframes, ADPCM mis-sizes, raw PCM desynchronises).
 *
 * [PitchShifter] is therefore a delay-line shifter: a ring buffer written at the real rate
 * and read by a head moving at a different rate, cross-faded with a second head half a
 * window out of phase so the wrap is not a click. Duration in equals duration out, by
 * construction, and the state lives across frames so the effect does not restart 50 times
 * a second. It is not `AVAudioUnitTimePitch`'s phase vocoder and will not sound identical —
 * that is honest and acceptable for a voice effect; what matters is that the direction and
 * the magnitude are right, which is exactly what the tests assert.
 *
 * ## Where it runs, and what it is NOT
 *
 * Applied in `pumpCapture` to the captured frame **before** encoding, so one call site
 * covers Opus, WB-ADPCM and raw PCM. It touches only what we SEND: the peer hears the
 * effect and the person speaking does not, which is how iOS behaves.
 *
 * `ANONYMOUS` is listed by iOS as *"Special effect for maximum privacy"*, and that wording
 * deserves care. It makes a voice hard for a LISTENER to recognise. It is **not** a
 * de-identification guarantee: pitch shifting is invertible, the frames still carry their
 * normal timing, and nothing here defeats speaker recognition by software. Anyone reading
 * "maximum privacy" as an anonymity control should be corrected, and
 * `CallVoiceEffectTest` asserts the shift is a shift and claims nothing more.
 */
object CallVoiceEffects {

    /**
     * The eight effects, with iOS's raw values as the identity.
     *
     * [wire] matches `CallVoiceEffect.rawValue` exactly, because that value is the
     * storage identity on iOS (`__EFFECT_NAMES_WERE_ENGLISH_2026_08_20__` says so: the raw
     * value is the wire/storage identity and must not change, only the displayed name
     * does) and the Settings default is persisted under it. A desktop that spelled them
     * differently would silently fail to honour a default set on a phone.
     */
    enum class Effect(val wire: String) {
        NONE("None"),
        ROBOT("Robot"),
        ALIEN("Alien"),
        DEEP("Deep"),
        CHIPMUNK("Chipmunk"),
        ECHO("Echo"),
        REVERB("Reverb"),
        ANONYMOUS("Anonymous");

        /**
         * [catalogKey] is IMPORTED rather than fully qualified, and that is not a style
         * choice: `SourceKeys.ACCESSORS` matches it with a negative lookbehind that
         * rejects a preceding `.`, so `com.oshi.desktop.i18n.catalogKey("common.off")`
         * does not register and `CatalogAuditTest` reports the key as never reaching the
         * catalog. Measured — that is exactly what it did.
         *
         * The catalog key for the human name. `NONE` reuses `common.off` for the reason
         * iOS gives: "no effect" is the same idea the rest of the app already spells that
         * way, and a 35th string for it would be a translation to maintain for nothing.
         */
        val key: String get() =
            if (this == NONE) catalogKey("common.off")
            // Not wrapped: `catalogKey` marks a LITERAL for the scanner, and this one is
            // computed. `CatalogAuditTest` cannot see through the interpolation either
            // way, which is why the test above asserts every effect's key resolves in the
            // English catalog — the check the scanner cannot make, made directly.
            else "call.voice_effect.${wire.lowercase()}"

        companion object {
            /** Parse a persisted or phone-set raw value; unknown spellings mean [NONE]. */
            fun ofWire(raw: String?): Effect =
                entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) } ?: NONE
        }
    }

    /** Semitone shift per effect. Positive is up. */
    private fun semitones(e: Effect): Double = when (e) {
        Effect.CHIPMUNK -> 7.0
        Effect.ALIEN -> 5.0
        Effect.DEEP -> -5.0
        Effect.ANONYMOUS -> -4.0
        Effect.ROBOT -> 0.0
        else -> 0.0
    }

    /** `2^(n/12)` — the ratio a shift of `n` semitones multiplies a frequency by. */
    fun ratioFor(semitones: Double): Double = Math.pow(2.0, semitones / 12.0)

    /**
     * A length-preserving pitch shifter.
     *
     * A ring buffer is written at the input rate and read by a head advancing at
     * [ratio] × that rate. The head therefore drifts through the buffer and must wrap; a
     * raw wrap is a discontinuity and a click fifty times a second. Two heads half a
     * window apart, cross-faded by a raised cosine, put the wrap of each one under the
     * other's fade — the standard delay-line shifter, and the reason the window is a
     * compromise: long enough that the fade is gentle, short enough that the extra
     * delay is not heard as an echo.
     *
     * Stateful ON PURPOSE, and shared across the frames of one call: a shifter reset
     * every 20 ms produces a buzz at 50 Hz, which is the frame rate made audible.
     */
    class PitchShifter(private val ratio: Double, windowSamples: Int = WINDOW) {

        private val window = windowSamples.coerceAtLeast(64)
        private val buf = FloatArray(window * 4)
        private var writeIdx = 0
        private var readPos = 0.0

        fun reset() {
            buf.fill(0f); writeIdx = 0; readPos = 0.0
        }

        /** One sample in, one sample out. */
        fun process(x: Float): Float {
            buf[writeIdx] = x
            writeIdx = (writeIdx + 1) % buf.size

            // Two heads, half a window apart.
            val a = readAt(readPos)
            val b = readAt(readPos + window / 2.0)
            // Raised cosine: the fade is 0 at the start of a head's pass and 1 halfway, so
            // each head is silent exactly where it wraps.
            val phase = (readPos % window) / window
            val w = 0.5 - 0.5 * cos(2.0 * PI * phase)
            val out = a * (1.0 - w) + b * w

            readPos += ratio
            if (readPos >= buf.size) readPos -= buf.size
            return out.toFloat()
        }

        /** Linear interpolation, because the head sits between samples. */
        private fun readAt(pos: Double): Double {
            var p = pos % buf.size
            if (p < 0) p += buf.size
            val i = p.toInt()
            val frac = p - i
            val s0 = buf[i].toDouble()
            val s1 = buf[(i + 1) % buf.size].toDouble()
            return s0 + (s1 - s0) * frac
        }

        companion object {
            /** ~21 ms at 48 kHz: one frame's worth, the shortest gentle fade. */
            const val WINDOW = 1024
        }
    }

    /** A feedback delay line — `ECHO` on its own, and one comb of `REVERB`. */
    class DelayLine(delaySamples: Int, private val feedback: Float, private val mix: Float) {
        private val buf = FloatArray(delaySamples.coerceAtLeast(1))
        private var idx = 0

        fun reset() { buf.fill(0f); idx = 0 }

        fun process(x: Float): Float {
            val delayed = buf[idx]
            buf[idx] = x + delayed * feedback
            idx = (idx + 1) % buf.size
            return x + delayed * mix
        }
    }

    /**
     * One call's worth of effect state.
     *
     * Built once per effect change and reused for every frame, so the delay lines and the
     * pitch heads stay continuous. [apply] is allocation-light and runs on the capture
     * thread, which has 20 ms of real time per frame and must not be made to wait.
     */
    class Chain(val effect: Effect) {

        private val shifter: PitchShifter? =
            semitones(effect).takeIf { it != 0.0 }?.let { PitchShifter(ratioFor(it)) }

        /** `ECHO`: one clearly audible repeat. 180 ms, decaying. */
        private val echo: DelayLine? =
            if (effect == Effect.ECHO) DelayLine((0.180 * CallAudio.SAMPLE_RATE).toInt(), 0.35f, 0.6f) else null

        /**
         * `REVERB`: three short, mutually prime combs. Not a room model — a cheap
         * Schroeder bank, which is what a voice effect needs and all a 20 ms budget allows.
         * Prime-ish lengths so their repeats do not line up into a single slap-back.
         */
        private val reverb: List<DelayLine> =
            if (effect == Effect.REVERB) listOf(
                DelayLine(1_327, 0.42f, 0.30f),
                DelayLine(1_733, 0.38f, 0.25f),
                DelayLine(2_111, 0.34f, 0.20f),
            ) else emptyList()

        /** `ROBOT`/`ALIEN`: ring modulation. Phase kept across frames, or it clicks. */
        private var ringPhase = 0.0
        private val ringHz: Double = when (effect) {
            Effect.ROBOT -> 72.0
            Effect.ALIEN -> 160.0
            else -> 0.0
        }

        /** `ANONYMOUS`/`ROBOT`: a soft waveshaper, so the timbre changes and not just pitch. */
        private val drive: Float = when (effect) {
            Effect.ROBOT -> 2.4f
            Effect.ANONYMOUS -> 1.8f
            else -> 1f
        }

        fun reset() {
            shifter?.reset(); echo?.reset(); reverb.forEach { it.reset() }; ringPhase = 0.0
        }

        /**
         * Transform one frame of signed 16-bit little-endian mono PCM, in place of the
         * input, returning a new array of **exactly** the same length.
         *
         * [Effect.NONE] returns the input array itself. Not an optimisation worth
         * mentioning on its own — it is the guarantee that choosing "off" cannot colour
         * the audio at all, which a chain of unity-gain nodes does not give you.
         */
        fun apply(pcm: ByteArray): ByteArray {
            if (effect == Effect.NONE) return pcm
            val n = pcm.size / 2
            if (n == 0) return pcm
            val out = ByteArray(pcm.size)
            val ringStep = 2.0 * PI * ringHz / CallAudio.SAMPLE_RATE
            for (i in 0 until n) {
                val lo = pcm[i * 2].toInt() and 0xFF
                val hi = pcm[i * 2 + 1].toInt()
                var s = ((hi shl 8) or lo).toShort().toFloat() / 32768f

                shifter?.let { s = it.process(s) }

                if (ringHz > 0.0) {
                    // Ring modulation, mixed rather than replacing: pure multiplication
                    // removes the fundamental and makes speech unintelligible, which is a
                    // broken call rather than a robot voice.
                    s = (s * 0.45f + s * sin(ringPhase).toFloat() * 0.55f)
                    ringPhase += ringStep
                    if (ringPhase > 2.0 * PI) ringPhase -= 2.0 * PI
                }

                if (drive != 1f) {
                    // tanh-ish soft clip: bounded by construction, so a loud input cannot
                    // be driven into a wrap. `kotlin.math.tanh` would do, but this stays
                    // within [-1, 1] without a transcendental call per sample.
                    val d = s * drive
                    s = d / (1f + abs(d))
                }

                echo?.let { s = it.process(s) }
                if (reverb.isNotEmpty()) {
                    var acc = s
                    for (r in reverb) acc = r.process(acc)
                    s = acc * 0.7f
                }

                // Clamp BEFORE the cast. A Float outside ±1 cast straight to Short wraps,
                // and a wrap is not a loud sample — it is the loudest possible sample of
                // the opposite sign, heard as a crack.
                val v = (s.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
                out[i * 2] = (v and 0xFF).toByte()
                out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return out
        }
    }

    /** Frames needed to fill a shifter's buffer — the ramp-up a test must allow for. */
    fun warmupFrames(): Int =
        min(8, (PitchShifter.WINDOW * 4) / CallAudio.SAMPLES_PER_FRAME + 1)
}
