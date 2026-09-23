package com.oshi.desktop.call.media

import com.oshi.desktop.call.CallAccept
import com.oshi.desktop.call.CallOffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

/** __WB_ADPCM_CODEC_2026_09_23__ Desktop copy of the 0x18 codec + its negotiation. */
class WbAdpcmCodecTest {

    /** The deterministic signal of scratchpad/wbadpcm/ref.py (440 Hz + LCG noise, 16 kHz). */
    private fun refSignal(): ShortArray {
        var seed = 12345L
        return ShortArray(640) { n ->
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            val noise = ((seed shr 16) and 0x7fff).toInt() - 16384
            val v = Math.round(8000 * Math.sin(2 * Math.PI * 440 * n / 16000.0)).toInt() + (noise shr 3)
            v.coerceIn(-32768, 32767).toShort()
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun `vectors are bit-identical with the Python reference (and so iOS and Android)`() {
        val x = refSignal()
        val st = WbAdpcmCodec.EncoderState()
        val f1 = WbAdpcmCodec.encode(x.copyOfRange(0, 320), st)
        val f2 = WbAdpcmCodec.encode(x.copyOfRange(320, 640), st)
        assertEquals(163, f1.size)
        assertEquals("0000007777777711d20088cb", hex(f1.copyOf(12)))
        assertEquals("da8c3e034109431186018388", hex(f2.copyOf(12)))
        val crc = CRC32().apply { update(f1 + f2) }.value
        assertEquals(627570784L, crc)
    }

    @Test fun `decode tracks the signal (SNR above 20 dB) and each frame decodes alone`() {
        val x = refSignal()
        val st = WbAdpcmCodec.EncoderState()
        val f1 = WbAdpcmCodec.encode(x.copyOfRange(0, 320), st)
        val f2 = WbAdpcmCodec.encode(x.copyOfRange(320, 640), st)
        val d = WbAdpcmCodec.decode(f1) + WbAdpcmCodec.decode(f2)
        assertEquals(640, d.size)
        var sig = 0.0; var err = 0.0
        for (i in x.indices) { sig += x[i].toDouble() * x[i]; val e = (x[i] - d[i]).toDouble(); err += e * e }
        assertTrue(10 * Math.log10(sig / err) > 20.0)
        // Frame 2 alone (frame 1 "lost") gives the same samples as in sequence.
        assertArrayEquals(d.copyOfRange(320, 640), WbAdpcmCodec.decode(f2))
    }

    @Test fun `a 20 ms 48 kHz frame becomes 163 bytes and survives the 48-16-48 round trip`() {
        val tx = WbResampler(); val rx = WbResampler(); val st = WbAdpcmCodec.EncoderState()
        val frames = 10
        val inAll = ShortArray(960 * frames) { (8000 * Math.sin(2 * Math.PI * 1000 * it / 48000.0)).toInt().toShort() }
        val outAll = ArrayList<Short>()
        for (f in 0 until frames) {
            val le = ByteArray(1920)
            for (i in 0 until 960) {
                val s = inAll[f * 960 + i].toInt()
                le[2 * i] = s.toByte(); le[2 * i + 1] = (s shr 8).toByte()
            }
            val payload = WbAdpcmCodec.encodePcm48(le, tx, st)
            assertEquals(163, payload.size)
            val pcm = WbAdpcmCodec.decodeToPcm48(payload, rx)
            assertEquals(1920, pcm.size)
            for (i in 0 until 960) outAll.add(((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort())
        }
        // Best alignment over the filter delay (2 × 23.5 taps @48k ≈ 47 samples).
        var best = -1e9
        for (lag in 30..70) {
            var sig = 0.0; var err = 0.0
            for (i in 2000 until 9000) {
                val a = inAll[i].toDouble(); val b = outAll[i + lag].toDouble()
                sig += a * a; err += (a - b) * (a - b)
            }
            best = maxOf(best, 10 * Math.log10(sig / err))
        }
        assertTrue("round-trip SNR $best dB", best > 15.0)
    }

    @Test fun `offer advertises slot 4 and a five-byte phone tail parses with the callId intact`() {
        val id = "3F2504E0-4F89-11D3-9A0C-0305E82C3301"
        val body = CallOffer(ByteArray(32) { 7 }, byteArrayOf(1, 2, 3, 4), id, supportsVideo = true, supportsWbAdpcm = true).encode()
        assertEquals(36 + id.length + 5, body.size)
        assertEquals(0x10.toByte(), body.last())
        val back = CallOffer.decode(body)!!
        assertEquals(id, back.callId)
        assertTrue(back.supportsWbAdpcm)
        assertTrue(back.supportsVideo)

        // An iOS offer: [00][00][04][08][10].
        val ios = ByteArray(36) + id.toByteArray() + byteArrayOf(0, 0, 4, 8, 0x10)
        val p = CallOffer.decode(ios)!!
        assertEquals(id, p.callId)
        assertTrue(p.supportsAppleCodec && p.supportsVideo && p.supportsWbAdpcm)

        // A pre-WB phone (4 bytes) and a legacy 2-byte tail: no WB, callId intact.
        val old = CallOffer.decode(ByteArray(36) + id.toByteArray() + byteArrayOf(0, 0, 0, 8))!!
        assertEquals(id, old.callId)
        assertFalse(old.supportsWbAdpcm)
        assertFalse(CallOffer.decode(ByteArray(36) + id.toByteArray() + byteArrayOf(0, 0))!!.supportsWbAdpcm)
    }

    @Test fun `accept carries WB at index 4 and a legacy accept does not`() {
        val a = CallAccept(supportsVideo = true, supportsWbAdpcm = true).encode()
        assertArrayEquals(byteArrayOf(0, 0, 0, 8, 0x10), a)
        assertTrue(CallAccept.decode(a).supportsWbAdpcm)
        assertTrue(CallAccept.decode(byteArrayOf(0, 0, 4, 8, 0x10)).supportsWbAdpcm)   // iOS
        assertFalse(CallAccept.decode(byteArrayOf(0, 0, 4, 8)).supportsWbAdpcm)
        assertFalse(CallAccept.decode(ByteArray(0)).supportsWbAdpcm)
    }

    @Test fun `a sealed 0x18 frame is 200 bytes and plays as 48 kHz PCM`() {
        val key = ByteArray(32) { it.toByte() }
        val salt = byteArrayOf(9, 8, 7, 6)
        val payload = WbAdpcmCodec.encodePcm48(ByteArray(1920), WbResampler(), WbAdpcmCodec.EncoderState())
        val sealed = CallMediaFrame.encode(key, salt, true, 1L, CallMediaFrame.TYPE_WB_ADPCM, payload)
        assertEquals(200, sealed.size)
        val session = CallAudioSession(key, salt, isCaller = false, send = {})
        assertFalse(session.useWbAdpcm)
        assertTrue(session.onFrame(sealed))
        assertTrue("first 0x18 received flips our TX", session.useWbAdpcm)
        assertEquals(1920, session.playback.take(10)!!.size)
    }

    // __WB_POSTFILTER_2026_09_23__ receiver-side hiss removal. Not a wire contract, but the
    // Float pipeline is bit-identical on iOS/Android/Desktop: the CRC below is asserted
    // verbatim by all three suites (iOS `WbAdpcmCodecTests`).
    private fun pfSignal(): ShortArray {
        var seed = 12345L
        return ShortArray(640) { n ->
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            val noise = ((seed shr 16) and 0x7fff).toInt() - 16384
            val v = Math.round(8000 * Math.sin(2 * Math.PI * 440 * n / 16000.0)).toInt() + (noise shr 3)
            v.coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun postFilterOutputIsPinnedByACrcSharedWithIos() {
        val x = pfSignal()
        val st = WbAdpcmCodec.EncoderState()
        val frames = listOf(WbAdpcmCodec.encode(x.copyOfRange(0, 320), st), WbAdpcmCodec.encode(x.copyOfRange(320, 640), st))
        val pf = WbPostFilter()
        val out = ByteArray(1280)
        var o = 0
        for (f in frames) {
            val steps = IntArray(320)
            val d = WbAdpcmCodec.decode(f, steps)
            assertArrayEquals("decode(steps) must not change the samples", WbAdpcmCodec.decode(f), d)
            assertTrue(steps.all { it in 7..32767 })
            for (s in pf.process(d, steps)) { out[o++] = s.toByte(); out[o++] = (s.toInt() shr 8).toByte() }
        }
        assertEquals(2_068_779_908L, CRC32().apply { update(out) }.value)
    }

    @Test
    fun postFilterRemovesAdpcmNoiseWithoutChangingTheLevel() {
        val n = 16_000
        val tone = ShortArray(n) { Math.round(8000 * Math.sin(2 * Math.PI * 440 * it / 16000.0)).toInt().toShort() }
        val st = WbAdpcmCodec.EncoderState()
        val pf = WbPostFilter()
        val raw = ShortArray(n); val flt = ShortArray(n)
        for (f in 0 until n / 320) {
            val p = WbAdpcmCodec.encode(tone.copyOfRange(f * 320, f * 320 + 320), st)
            val steps = IntArray(320)
            val d = WbAdpcmCodec.decode(p, steps)
            System.arraycopy(d, 0, raw, f * 320, 320)
            val y = pf.process(d, steps)
            assertEquals(320, y.size)
            System.arraycopy(y, 0, flt, f * 320, 320)
        }
        val lag = WbPostFilter.N - WbPostFilter.HOP + WbPostFilter.PRIME   // 192 samples = 12 ms
        fun snr(y: ShortArray, l: Int): Double {
            var s = 0.0; var e = 0.0
            for (i in 3200 until n - 400) { val a = tone[i].toDouble(); val b = y[i + l].toDouble(); s += a * a; e += (a - b) * (a - b) }
            return 10 * Math.log10(s / e)
        }
        fun rms(y: ShortArray, l: Int) = Math.sqrt((3200 until n - 400).sumOf { y[it + l].toDouble() * y[it + l] } / (n - 3600))
        val before = snr(raw, 0); val after = snr(flt, lag)
        assertTrue("SNR $before → $after dB", after > before + 5.0)
        assertEquals(0.0, 20 * Math.log10(rms(flt, lag) / rms(raw, 0)), 0.1)
    }

    @Test
    fun postFilterKeepsSilenceSilentAndResets() {
        val pf = WbPostFilter()
        assertTrue(pf.process(ShortArray(320), IntArray(320) { 7 }).all { it.toInt() == 0 })
        val x = pfSignal()
        val p = WbAdpcmCodec.encode(x.copyOfRange(0, 320), WbAdpcmCodec.EncoderState())
        val steps = IntArray(320); val d = WbAdpcmCodec.decode(p, steps)
        val first = WbPostFilter().process(d, steps)
        pf.process(d, steps); pf.reset()
        assertArrayEquals(first, pf.process(d, steps))
        // A malformed call (fewer steps than samples) passes through untouched.
        assertArrayEquals(d, pf.process(d, IntArray(3)))
    }
}
