package com.oshi.desktop.call.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CALL_FLUIDITY_2026_11_21__ The 0x19 receive path through [AdaptiveJitterBuffer] +
 * [OpusPlayoutDecoder]: reordering, in-band FEC, and wire compatibility with the packets
 * every existing client sends (format unchanged: same header, same redundancy).
 */
class OpusPlayoutTest {

    private fun voice(frames: Int): ShortArray = ShortArray(frames * 960) { i ->
        val t = i / 48_000.0
        (5000 * (0.6 + 0.4 * Math.sin(2 * Math.PI * 3 * t)) * (Math.sin(2 * Math.PI * 180 * t) + 0.4 * Math.sin(2 * Math.PI * 540 * t)))
            .toInt().toShort()
    }

    private fun rms(a: ShortArray, from: Int = 0, n: Int = a.size): Double {
        var s = 0.0
        for (i in from until from + n) s += a[i].toDouble() * a[i]
        return Math.sqrt(s / n)
    }

    @Test fun `packets that overtook four others do not cost the four real frames`() {
        val payloads = OpusCallEncoder().encode(voice(40))
        // 10..14 arrive after 15..18 — still before their slots are due.
        val order = (0 until 10) + (15..18) + (10..14) + (19 until 40)
        var now = 0L
        val jb = AdaptiveJitterBuffer(OpusPlayoutDecoder(), clock = { now })
        val unwrap = SeqUnwrapper()
        for (i in 0 until 3) OpusPlayoutDecoder.file(payloads[order[i]], jb, unwrap)
        var next = 3
        repeat(60) {
            now += 20
            if (next < order.size) OpusPlayoutDecoder.file(payloads[order[next++]], jb, unwrap)
            if (next < order.size && order[next - 1] in 15..17) OpusPlayoutDecoder.file(payloads[order[next++]], jb, unwrap)
            jb.pull()
        }
        assertEquals("every frame played for real", 40L, jb.framesPlayed)
        assertEquals(0L, jb.framesLossConcealed)

        // The decode-on-arrival chain it replaces: the same order costs real frames.
        val old = OpusCallDecoder()
        var concealed = 0
        for (i in order) {
            val frames = old.decode(payloads[i])
            // A frame the old chain produced for a seq it had not received is PLC.
            if (i in 15..18) concealed += frames.count { it.offset < -2 }
        }
        assertTrue("old chain concealed $concealed real frames", concealed >= 2)
    }

    @Test fun `in-band FEC recovers a lost frame from the next one when the sender carries it`() {
        // No repeated copies, LBRR on: frame 20 can only come back through FEC.
        val enc = OpusCallEncoder(32_000, 8, redundancy = 0, inbandFecLossPct = 20)
        val src = voice(60)
        val payloads = enc.encode(src)
        var now = 0L
        val dec = OpusPlayoutDecoder()
        val jb = AdaptiveJitterBuffer(dec, clock = { now })
        val unwrap = SeqUnwrapper()
        val out = ArrayList<Pair<AdaptiveJitterBuffer.Kind, ShortArray>>()
        for ((i, p) in payloads.withIndex()) {
            if (i != 20) OpusPlayoutDecoder.file(p, jb, unwrap)
            if (i >= 2) { now += 20; val f = jb.pull(); out += jb.kind() to f }
        }
        assertTrue("FEC decode used", dec.fecDecodes >= 1)
        assertEquals(1L, jb.framesLossConcealed)
        // The recovered frame is voice, not a fade to silence.
        val at = out.indexOfFirst { it.first == AdaptiveJitterBuffer.Kind.LOSS_CONCEAL }
        assertTrue(at > 0)
        assertTrue(rms(out[at].second) > 0.3 * rms(out[at - 1].second))
    }

    @Test fun `FEC turned on is still a plain Opus stream for an old receiver`() {
        // An old build decodes with OpusCallDecoder and never asks for FEC.
        val src = voice(50)
        val withFec = OpusCallEncoder(32_000, 8, inbandFecLossPct = 10).encode(src)
        val old = OpusCallDecoder()
        val pcm = withFec.flatMap { p -> old.decode(p).flatMap { it.pcm.asList() } }.toShortArray()
        assertEquals(50 * 960, pcm.size)
        assertTrue(rms(pcm, 10 * 960, 30 * 960) > 0.5 * rms(src, 10 * 960, 30 * 960))
    }

    @Test fun `the default sender is byte-identical to 1_6_2 - no new field on the wire`() {
        // SHA-256 of the first 50 payloads of OpusCallEncoder() for this input, computed on
        // release/desktop-1.6.2 (cc6e5fd1) before any change in this branch.
        val payloads = OpusCallEncoder().encode(voice(50))
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (p in payloads) md.update(p)
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        assertEquals(GOLDEN_1_6_2, hex)
    }

    companion object {
        const val GOLDEN_1_6_2 = "f6c3c9af4b3da6d0da8a3c68123ba6ca04741fd500814f81ff9313b577815242"
    }
}
