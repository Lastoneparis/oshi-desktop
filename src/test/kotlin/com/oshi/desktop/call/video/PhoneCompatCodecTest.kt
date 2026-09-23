package com.oshi.desktop.call.video

import com.oshi.desktop.call.media.AndroidOracle
import com.oshi.desktop.call.media.CallAudio
import com.oshi.desktop.call.media.CallMediaFrame
import com.oshi.desktop.call.media.IosOracle
import com.oshi.desktop.call.media.VideoFragment
import com.oshi.desktop.call.media.VideoSendSession
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * THE ENCODER THIS MACHINE PICKS, against the phones' receive paths.
 *
 * Runs on every CI OS (windows-latest, ubuntu-latest, macOS): whatever
 * [FfmpegVideo.encoderCandidates] chooses first here — libopenh264 on Windows and Linux,
 * VideoToolbox on a Mac — encodes 64 frames at the call geometry, and EVERY frame's
 * datagrams go through [IosOracle] and [AndroidOracle], the phones' receive code ported
 * line by line. What each phone would hand its hardware decoder (iOS: header SPS/PPS +
 * the AVCC with parameter sets stripped, for VTDecompressionSession; Android: SPS/PPS +
 * the enriched AVCC, for MediaCodec) is then decoded by FFmpeg as the stand-in for
 * VideoToolbox / MediaCodec.
 *
 * What this proves: the bytes a phone extracts from our datagrams are a valid H.264 stream
 * in a profile, level and geometry both phones' decoders take. What it does NOT prove:
 * that a real VideoToolbox or MediaCodec instance was fed them — no phone is involved.
 */
class PhoneCompatCodecTest {

    private val key = ByteArray(32) { (it * 7 + 1).toByte() }

    private fun frame(w: Int, h: Int, t: Int): AVFrame = avutil.av_frame_alloc().apply {
        format(avutil.AV_PIX_FMT_YUV420P); width(w); height(h)
        check(avutil.av_frame_get_buffer(this, 32) >= 0)
        val y = data(0); val u = data(1); val v = data(2)
        for (r in 0 until h) for (c in 0 until w) y.put(r.toLong() * linesize(0) + c, ((c + t * 3) and 0xFF).toByte())
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            u.put(r.toLong() * linesize(1) + c, (128 + (r and 31)).toByte())
            v.put(r.toLong() * linesize(2) + c, (128 - (c and 31)).toByte())
        }
    }

    /** AVCC (4-byte big-endian lengths) → Annex-B, as a decoder front-end would. */
    private fun avccToAnnexB(avcc: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var o = 0
        while (o + 4 <= avcc.size) {
            val len = ByteBuffer.wrap(avcc, o, 4).int
            if (len <= 0 || o + 4 + len > avcc.size) break
            out.write(byteArrayOf(0, 0, 0, 1)); out.write(avcc, o + 4, len)
            o += 4 + len
        }
        return out.toByteArray()
    }

    @Test
    fun `this OS's encoder produces what the iOS and Android receive paths extract and decode`() {
        assumeTrue("FFmpeg natives: ${FfmpegVideo.unavailableReason()}", FfmpegVideo.available)
        val name = FfmpegVideo.encoderCandidates().firstOrNull()
        assertNotNull("no H.264 encoder on ${System.getProperty("os.name")}", name)
        val w = CameraCapture.DEFAULT_WIDTH; val h = CameraCapture.DEFAULT_HEIGHT
        val perFrame = ArrayList<List<ByteArray>>()
        val all = ArrayList<ByteArray>()
        // ONE send session, as in a call: P-frames carry the SPS/PPS cached from the IDR.
        val tx = VideoSendSession(key, true) { all += it; true }
        val keyIndices = ArrayList<Int>()
        H264Encoder(w, h, names = listOf(name!!)).use { enc ->
            for (i in 0 until 64) {
                val f = frame(w, h, i)
                for (au in enc.encode(f, forceKey = i == 0)) {
                    val before = all.size
                    tx.sendAccessUnit(au.annexB, au.key, i * 33L)
                    if (all.size == before) continue
                    perFrame += all.subList(before, all.size).toList()
                    if (au.key) keyIndices += perFrame.size - 1
                }
                avutil.av_frame_free(f)
            }
        }
        assertTrue("encoded ${perFrame.size} frames", perFrame.size >= 60)

        // Datagram size: the phones' fragment payload is 1 100 B — no datagram may be larger
        // than one fragment + fragment header + seal + envelope header (unfragmented at IP).
        val maxDatagram = perFrame.flatten().maxOf { it.size }
        assertTrue("datagram $maxDatagram B exceeds one phone fragment", maxDatagram <= 9 + 12 + 16 + 5 + VideoFragment.MAX_PAYLOAD)

        // Keyframe cadence: the phones' 1 s GOP at 30 fps.
        assertEquals("first frame is a keyframe", 0, keyIndices.first())
        assertTrue("a keyframe within every 30 frames: $keyIndices", keyIndices.zipWithNext().all { (a, b) -> b - a <= 30 } && keyIndices.size >= 2)

        H264Decoder().use { iosDec -> H264Decoder().use { androidDec ->
            var iosPics = 0; var androidPics = 0
            var profile = -1; var level = -1
            for ((i, wire) in perFrame.withIndex()) {
                val ios = IosOracle.receive(key, wire) ?: error("iOS rejected frame $i")
                val android = AndroidOracle.receive(key, wire) ?: error("Android rejected frame $i")
                // SPS/PPS in EVERY packet header, as both phones send and expect.
                val sps = ios.headerSps ?: error("frame $i: no SPS in the packet header (iOS needs it)")
                val pps = ios.headerPps ?: error("frame $i: no PPS in the packet header")
                assertNotNull("frame $i: Android sees no SPS", android.sps)
                assertEquals("keyframe flag agrees (iOS)", i in keyIndices, ios.isKeyFrame)
                assertEquals("keyframe flag agrees (Android)", i in keyIndices, android.isKeyFrame)
                profile = sps[1].toInt() and 0xFF; level = sps[3].toInt() and 0xFF

                val spsPps = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps
                // No B-frames: one picture out for every access unit in, in arrival order.
                val a = iosDec.decode((if (i == 0) spsPps else ByteArray(0)) + avccToAnnexB(ios.cleaned)).size
                val b = androidDec.decode((if (i == 0) spsPps else ByteArray(0)) + avccToAnnexB(android.h264)).size
                assertEquals("iOS stream: frame $i out of order / held back (B-frames?)", 1, a)
                assertEquals("Android stream: frame $i out of order / held back (B-frames?)", 1, b)
                iosPics += a; androidPics += b
            }
            println("[phone-compat] ${System.getProperty("os.name")} encoder=$name ${w}x$h profile_idc=$profile level_idc=$level " +
                "frames=${perFrame.size} keyframes=$keyIndices maxDatagram=${maxDatagram}B | iOS decoded $iosPics, Android decoded $androidPics, " +
                "errors ${iosDec.errors}/${androidDec.errors}")
            assertTrue("profile $profile: both phones decode Constrained Baseline (66) and Main (77)", profile == 66 || profile == 77)
            assertTrue("level $level above 3.1 — iPhone/Android baseline decoders", level in 1..31)
            assertEquals(0L, iosDec.errors); assertEquals(0L, androidDec.errors)
        } }
    }

    /**
     * Audio: the PCM framing both phones parse (`0x15`, 48 kHz mono signed-16 little-endian,
     * 20 ms = 960 samples = 1 920 bytes; 1 957 bytes sealed on the wire), asserted on the
     * OS the job runs on, where Java Sound decides the device format.
     */
    @Test
    fun `audio framing is the phones' 48 kHz 20 ms PCM on this OS`() {
        val f = CallAudio.FORMAT
        assertEquals(48_000f, f.sampleRate); assertEquals(16, f.sampleSizeInBits)
        assertEquals(1, f.channels); assertEquals(false, f.isBigEndian)
        assertEquals(20, CallAudio.FRAME_MS); assertEquals(1920, CallAudio.BYTES_PER_FRAME)
        val sealed = CallMediaFrame.encode(key, byteArrayOf(1, 2, 3, 4), true, 1, CallMediaFrame.TYPE_PCM_48K, ByteArray(1920))
        println("[phone-compat] ${System.getProperty("os.name")} audio: ${f.sampleRate.toInt()} Hz ${f.sampleSizeInBits}-bit mono LE, " +
            "${CallAudio.FRAME_MS} ms = ${CallAudio.BYTES_PER_FRAME} B PCM, ${sealed.size} B sealed (type 0x${"%02X".format(CallMediaFrame.TYPE_PCM_48K)})")
        assertEquals(1957, sealed.size)
        assertEquals(CallMediaFrame.TYPE_PCM_48K, sealed[0].toInt() and 0xFF)
    }
}
