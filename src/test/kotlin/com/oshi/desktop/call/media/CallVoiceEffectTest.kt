package com.oshi.desktop.call.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * __CALL_VOICE_EFFECTS_2026_10_28__ The eight live call voice effects, PARITY row 2.1-d's
 * last `❌ NOT ported`.
 *
 * DSP has no natural oracle: nobody can assert that a robot sounds like a robot. So every
 * test below measures a PROPERTY the effect must have — a zero-crossing rate that moved in
 * the right direction, a frame length that did not change, an impulse that came back at the
 * configured delay — and none of them claims the result is pleasant. That is the honest
 * limit of this suite, and it is stated rather than implied: these tests prove the port is
 * not broken, not that it sounds like iOS's phase vocoder, which it does not.
 *
 * Zero-crossing rate is the measurement of choice because for a near-sinusoid it is
 * directly proportional to frequency, needs no FFT, and cannot be fooled by a gain change —
 * which is exactly the failure mode of asserting on amplitude instead.
 */
class CallVoiceEffectTest {

    private val n = CallAudio.SAMPLES_PER_FRAME

    /** A 440 Hz tone at −6 dBFS, as signed 16-bit LE mono, continuous across frames. */
    private fun tone(frameIndex: Int, hz: Double = 440.0, amp: Double = 0.5): ByteArray {
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val t = (frameIndex * n + i).toDouble() / CallAudio.SAMPLE_RATE
            val v = (sin(2 * PI * hz * t) * amp * 32767).roundToInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun samples(pcm: ByteArray): IntArray =
        IntArray(pcm.size / 2) { i ->
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort().toInt()
        }

    /** Sign changes per frame. Proportional to frequency for a near-sinusoid. */
    private fun zeroCrossings(pcm: ByteArray): Int {
        val s = samples(pcm)
        var c = 0
        for (i in 1 until s.size) {
            // A deadband, so a tiny amount of noise around zero is not counted as a
            // crossing — without it a near-silent tail reads as a very high frequency.
            if (abs(s[i]) < 64 || abs(s[i - 1]) < 64) continue
            if ((s[i] >= 0) != (s[i - 1] >= 0)) c++
        }
        return c
    }

    /** Run one frame through a bare [CallVoiceEffects.PitchShifter], 16-bit LE in and out. */
    private fun shift(sh: CallVoiceEffects.PitchShifter, pcm: ByteArray): ByteArray {
        val out = ByteArray(pcm.size)
        val src = samples(pcm)
        for (i in src.indices) {
            val y = sh.process(src[i] / 32768f)
            val v = (y.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Magnitude of one frequency bin, by Goertzel. One bin is all these tests need, and it
     * is a dozen lines against pulling in an FFT for a sideband check.
     */
    private fun goertzel(pcm: ByteArray, hz: Double): Double {
        val s = samples(pcm)
        val w = 2.0 * PI * hz / CallAudio.SAMPLE_RATE
        val coeff = 2.0 * kotlin.math.cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (x in s) {
            val s0 = x / 32768.0 + coeff * s1 - s2
            s2 = s1; s1 = s0
        }
        return kotlin.math.sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2)
    }

    /** [count] consecutive steady-state frames, concatenated, for a narrow-bin analysis. */
    private fun steadyStateLong(effect: CallVoiceEffects.Effect, count: Int, hz: Double = 440.0): ByteArray {
        val chain = CallVoiceEffects.Chain(effect)
        val warm = CallVoiceEffects.warmupFrames() + 12
        for (f in 0 until warm) chain.apply(tone(f, hz))
        val out = java.io.ByteArrayOutputStream()
        for (f in warm until warm + count) out.write(chain.apply(tone(f, hz)))
        return out.toByteArray()
    }

    /** The same span of UNPROCESSED tone, so the two analyses share a window length. */
    private fun cleanLong(count: Int, hz: Double = 440.0): ByteArray {
        val warm = CallVoiceEffects.warmupFrames() + 12
        val out = java.io.ByteArrayOutputStream()
        for (f in warm until warm + count) out.write(tone(f, hz))
        return out.toByteArray()
    }

    /** Feed enough frames to fill the delay lines, then measure the next one. */
    private fun steadyState(effect: CallVoiceEffects.Effect, hz: Double = 440.0): ByteArray {
        val chain = CallVoiceEffects.Chain(effect)
        var last = ByteArray(0)
        val frames = CallVoiceEffects.warmupFrames() + 12
        for (f in 0 until frames) last = chain.apply(tone(f, hz))
        return last
    }

    // ------------------------------------------------------------------ the hard constraint

    @Test
    fun `every effect preserves the frame length exactly`() {
        // THE constraint that chose the algorithm. A frame on this wire is 20 ms, fixed,
        // and `pumpCapture` seals one read as one payload — so an effect that resampled
        // would hand 739 samples to a path expecting 960 and break each codec differently.
        for (e in CallVoiceEffects.Effect.entries) {
            val chain = CallVoiceEffects.Chain(e)
            for (f in 0 until 20) {
                val out = chain.apply(tone(f))
                assertEquals("$e changed the frame length", CallAudio.BYTES_PER_FRAME, out.size)
            }
        }
    }

    @Test
    fun `an empty or odd frame is handled rather than crashing`() {
        for (e in CallVoiceEffects.Effect.entries) {
            val chain = CallVoiceEffects.Chain(e)
            assertEquals(0, chain.apply(ByteArray(0)).size)
            // One stray byte cannot form a sample; the length must still survive.
            assertEquals(1, chain.apply(ByteArray(1)).size)
        }
    }

    // ------------------------------------------------------------------ off means off

    @Test
    fun `NONE returns the very same array, not a faithful copy`() {
        // A chain of unity-gain nodes is not the same promise. Identity means choosing
        // "off" provably cannot colour the audio — no rounding, no clamp, no drift.
        val chain = CallVoiceEffects.Chain(CallVoiceEffects.Effect.NONE)
        val input = tone(0)
        assertSame(input, chain.apply(input))
    }

    // ------------------------------------------------------------------ pitch direction

    @Test
    fun `the shifter moves a tone in the direction it was asked to`() {
        // The pitch component on its own, away from the effects that also reshape the
        // spectrum. This is the claim the delay-line shifter has to earn.
        for ((semis, mustRise) in listOf(7.0 to true, 5.0 to true, -5.0 to false, -4.0 to false)) {
            val sh = CallVoiceEffects.PitchShifter(CallVoiceEffects.ratioFor(semis))
            var out = ByteArray(0)
            // Warm the ring buffer, then measure a frame well clear of the ramp-up.
            for (f in 0 until CallVoiceEffects.warmupFrames() + 12) out = shift(sh, tone(f))
            val z = zeroCrossings(out)
            val plain = zeroCrossings(tone(30))
            if (mustRise) assertTrue("$semis st must raise: $z vs $plain", z > plain)
            else assertTrue("$semis st must lower: $z vs $plain", z < plain)
        }
    }

    @Test
    fun `chipmunk and deep move a voice in opposite directions`() {
        // Measured on the two effects that are PURE pitch shifts. ALIEN and ROBOT are
        // deliberately left out: they ring-modulate, which moves energy to sidebands and
        // amplitude-modulates the envelope, so a zero-crossing count stops being a
        // measure of pitch for them. The first version of this test asserted ALIEN rose
        // and it measured 12 crossings against 16 — the code was right and the
        // measurement was wrong for that effect. Their defining property is asserted
        // below instead.
        val plain = zeroCrossings(tone(30))
        assertTrue("the test tone must cross zero to be measurable", plain > 10)
        val chipmunk = zeroCrossings(steadyState(CallVoiceEffects.Effect.CHIPMUNK))
        val deep = zeroCrossings(steadyState(CallVoiceEffects.Effect.DEEP))
        val anonymous = zeroCrossings(steadyState(CallVoiceEffects.Effect.ANONYMOUS))
        assertTrue("CHIPMUNK must raise the pitch: $chipmunk vs $plain", chipmunk > plain)
        assertTrue("DEEP must lower the pitch: $deep vs $plain", deep < plain)
        assertTrue("ANONYMOUS must lower the pitch: $anonymous vs $plain", anonymous < plain)
    }

    @Test
    fun `robot and alien put energy where the input had none`() {
        // What ring modulation IS: a tone at f comes out with sidebands at f +/- the
        // modulator. So measure the modulator's sideband in the output and require it to
        // stand far above what the clean tone shows in the same bin.
        //
        // Measured over TWENTY frames, not one, and that is the point. One 960-sample
        // frame gives 50 Hz bins, so ROBOT's 512 Hz sideband sits 1.4 bins from the 440 Hz
        // carrier and spectral leakage alone put 52 units there — against 117 of real
        // sideband, which is a ratio too weak to assert on. 20 frames narrow the bin to
        // 2.5 Hz and the leakage with it. The first version of this test loosened the
        // threshold instead; the measurement was what needed fixing.
        for ((effect, modHz) in listOf(
            CallVoiceEffects.Effect.ROBOT to 72.0,
            CallVoiceEffects.Effect.ALIEN to 160.0,
        )) {
            val sideband = 440.0 + modHz
            val got = goertzel(steadyStateLong(effect, 20), sideband)
            val clean = goertzel(cleanLong(20), sideband)
            assertTrue(
                "$effect must create a sideband at $sideband Hz: got $got, clean tone $clean",
                got > clean * 4 + 1.0,
            )
        }
    }

    @Test
    fun `the semitone ratio is the real one`() {
        assertEquals(1.0, CallVoiceEffects.ratioFor(0.0), 1e-12)
        assertEquals(2.0, CallVoiceEffects.ratioFor(12.0), 1e-12)
        assertEquals(0.5, CallVoiceEffects.ratioFor(-12.0), 1e-12)
        // A fifth is 1.4983, not 1.5 — equal temperament, not just intonation.
        assertEquals(1.4983, CallVoiceEffects.ratioFor(7.0), 1e-4)
    }

    // ------------------------------------------------------------------ the time effects

    @Test
    fun `echo returns an impulse at the configured delay`() {
        // The one effect with an exactly checkable signature. 180 ms at 48 kHz is 8,640
        // samples, i.e. 9 frames of 960.
        val chain = CallVoiceEffects.Chain(CallVoiceEffects.Effect.ECHO)
        val impulse = ByteArray(n * 2)
        impulse[0] = 0xFF.toByte(); impulse[1] = 0x7F   // +32767, full scale
        val frames = ArrayList<IntArray>()
        frames += samples(chain.apply(impulse))
        for (f in 1 until 14) frames += samples(chain.apply(ByteArray(n * 2)))

        val delay = (0.180 * CallAudio.SAMPLE_RATE).toInt()
        val frameOfEcho = delay / n
        val offsetInFrame = delay % n
        val heard = frames[frameOfEcho][offsetInFrame]
        assertTrue("no echo at $delay samples (frame $frameOfEcho, offset $offsetInFrame): $heard",
            abs(heard) > 8_000)
        // And silence where there is no echo yet: frame 1 must be quiet.
        assertTrue("something arrived before the delay", frames[1].all { abs(it) < 100 })
    }

    @Test
    fun `reverb adds a tail that decays rather than growing`() {
        // The failure this guards is a comb with feedback >= 1, which does not sound like a
        // room — it rings louder every pass until it clips, and it would pass any
        // length-only test.
        val chain = CallVoiceEffects.Chain(CallVoiceEffects.Effect.REVERB)
        for (f in 0 until 10) chain.apply(tone(f))
        val tail = ArrayList<Int>()
        for (f in 0 until 40) {
            val out = samples(chain.apply(ByteArray(n * 2)))
            tail += out.maxOf { abs(it) }
        }
        assertTrue("the tail must be audible at first", tail.first() > 50)
        assertTrue(
            "the tail must decay: first=${tail.first()} last=${tail.last()}",
            tail.last() < tail.first(),
        )
        assertTrue("the tail must not clip", tail.all { it <= 32_767 })
    }

    @Test
    fun `silence in is silence out for the effects with no tail`() {
        for (e in listOf(
            CallVoiceEffects.Effect.NONE, CallVoiceEffects.Effect.DEEP,
            CallVoiceEffects.Effect.CHIPMUNK, CallVoiceEffects.Effect.ROBOT,
            CallVoiceEffects.Effect.ALIEN, CallVoiceEffects.Effect.ANONYMOUS,
        )) {
            val chain = CallVoiceEffects.Chain(e)
            for (f in 0 until 10) {
                val out = chain.apply(ByteArray(n * 2))
                assertArrayEquals("$e invented sound out of digital silence", ByteArray(n * 2), out)
            }
        }
    }

    // ------------------------------------------------------------------ no cracks

    @Test
    fun `a full-scale input never wraps`() {
        // A Float outside ±1 cast straight to Short wraps, and a wrap is not a loud sample:
        // it is the loudest possible sample of the OPPOSITE sign, heard as a crack. The
        // clamp is before the cast, and this is what proves it. ROBOT and ANONYMOUS drive
        // the signal deliberately, so they are the ones that would wrap.
        for (e in CallVoiceEffects.Effect.entries) {
            val chain = CallVoiceEffects.Chain(e)
            for (f in 0 until 15) {
                val out = samples(chain.apply(tone(f, amp = 1.0)))
                // A wrap shows up as a sign flip between neighbouring samples of a
                // near-full-scale signal; the bound alone cannot catch it, since every
                // Short is "in range". So look for an impossible jump instead.
                for (i in 1 until out.size) {
                    val jump = abs(out[i] - out[i - 1])
                    assertTrue(
                        "$e: a jump of $jump between adjacent samples is a wrap, not audio",
                        jump < 60_000,
                    )
                }
            }
        }
    }

    @Test
    fun `the ring modulator keeps its phase across frames`() {
        // A phase reset every frame is a discontinuity 50 times a second — a 50 Hz buzz, the
        // frame rate made audible. Reset state must produce a DIFFERENT frame from
        // continuous state; if they match, the phase was not being carried.
        val continuous = CallVoiceEffects.Chain(CallVoiceEffects.Effect.ROBOT)
        var last = ByteArray(0)
        for (f in 0 until 6) last = continuous.apply(tone(f))

        val restarted = CallVoiceEffects.Chain(CallVoiceEffects.Effect.ROBOT)
        val firstAfterReset = restarted.apply(tone(5))

        assertNotEquals(
            "frame 5 processed with carried phase equals frame 5 from a fresh chain — " +
                "the modulator is restarting every frame",
            last.toList(), firstAfterReset.toList(),
        )
    }

    @Test
    fun `an effect is deterministic once reset`() {
        val a = CallVoiceEffects.Chain(CallVoiceEffects.Effect.ALIEN)
        val first = (0 until 8).map { a.apply(tone(it)).toList() }
        a.reset()
        val second = (0 until 8).map { a.apply(tone(it)).toList() }
        assertEquals("the same input after reset must give the same output", first, second)
    }

    // ------------------------------------------------------------------ identity on the wire

    @Test
    fun `the wire names are iOS's raw values, exactly`() {
        // iOS says the raw value is the WIRE/storage identity and must not change
        // (`__EFFECT_NAMES_WERE_ENGLISH_2026_08_20__`), and the Settings default is
        // persisted under it. A desktop that spelled these differently would silently
        // ignore a default set on a phone.
        assertEquals(
            listOf("None", "Robot", "Alien", "Deep", "Chipmunk", "Echo", "Reverb", "Anonymous"),
            CallVoiceEffects.Effect.entries.map { it.wire },
        )
    }

    @Test
    fun `an unknown or absent stored value means no effect`() {
        assertEquals(CallVoiceEffects.Effect.NONE, CallVoiceEffects.Effect.ofWire(null))
        assertEquals(CallVoiceEffects.Effect.NONE, CallVoiceEffects.Effect.ofWire(""))
        assertEquals(CallVoiceEffects.Effect.NONE, CallVoiceEffects.Effect.ofWire("Vocoder"))
        assertEquals(CallVoiceEffects.Effect.CHIPMUNK, CallVoiceEffects.Effect.ofWire("Chipmunk"))
        // Tolerant of case, because a stored value is data from another platform.
        assertEquals(CallVoiceEffects.Effect.DEEP, CallVoiceEffects.Effect.ofWire("deep"))
    }

    @Test
    fun `every effect names a catalog key that exists`() {
        val en = CallVoiceEffectTest::class.java.getResourceAsStream("/i18n/Localizable/en.json")!!
            .readBytes().decodeToString()
        for (e in CallVoiceEffects.Effect.entries) {
            assertTrue("missing catalog key ${e.key} for $e", en.contains("\"${e.key}\""))
        }
    }
}
