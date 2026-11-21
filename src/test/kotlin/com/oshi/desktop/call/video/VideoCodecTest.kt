package com.oshi.desktop.call.video

import com.oshi.desktop.call.media.AnnexB
import com.oshi.desktop.call.media.IosOracle
import com.oshi.desktop.call.media.VideoReceiveSession
import com.oshi.desktop.call.media.VideoSendSession
import com.oshi.desktop.call.media.VideoStreamSink
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The REAL codec, on this machine's natives: synthetic pictures → the H.264 encoder the
 * app would pick → [VideoSendSession] → the iOS oracle and our own receiver → the FFmpeg
 * decoder → pixels. Skipped (not passed) where the natives did not load.
 */
class VideoCodecTest {

    private val key = ByteArray(32) { it.toByte() }

    private fun frame(w: Int, h: Int, t: Int): AVFrame = avutil.av_frame_alloc().apply {
        format(avutil.AV_PIX_FMT_YUV420P); width(w); height(h)
        check(avutil.av_frame_get_buffer(this, 32) >= 0)
        for (plane in 0..2) {
            val pw = if (plane == 0) w else w / 2
            val ph = if (plane == 0) h else h / 2
            val stride = linesize(plane)
            val row = ByteArray(pw)
            for (y in 0 until ph) {
                for (x in 0 until pw) row[x] = (if (plane == 0) (x + y + t * 4) and 0xFF else 128).toByte()
                data(plane).position((y * stride).toLong()).put(row, 0, pw)
            }
            data(plane).position(0)
        }
    }

    private fun runCodec(encoderName: String) {
        assumeTrue("FFmpeg natives: ${FfmpegVideo.unavailableReason()}", FfmpegVideo.available)
        assumeTrue("$encoderName not in this build", encoderName in FfmpegVideo.encoderCandidates())
        val w = CameraCapture.DEFAULT_WIDTH; val h = CameraCapture.DEFAULT_HEIGHT
        val wire = ArrayList<ByteArray>()
        val tx = VideoSendSession(key, true) { wire += it; true }
        val units = ArrayList<ByteArray>()
        var params: Pair<ByteArray, ByteArray>? = null
        val rx = VideoReceiveSession(key, sink = object : VideoStreamSink {
            override fun writeParameterSets(sps: ByteArray, pps: ByteArray) { params = sps to pps }
            override fun writeAccessUnit(bytes: ByteArray) { units += bytes }
        })
        var firstFrameWire: List<ByteArray>? = null
        H264Encoder(w, h, names = listOf(encoderName)).use { enc ->
            for (i in 0 until 45) {
                val f = frame(w, h, i)
                val before = wire.size
                for (au in enc.encode(f, forceKey = i == 0 || i == 40)) tx.sendAccessUnit(au.annexB, au.key, i * 33L)
                if (firstFrameWire == null && wire.size > before) firstFrameWire = wire.subList(before, wire.size).toList()
                avutil.av_frame_free(f)
            }
        }
        for (d in wire) rx.onPacket(d)
        assertNotNull("receiver got parameter sets", params)
        val profileIdc = params!!.first[1].toInt() and 0xFF
        println("VIDEO_CODEC $encoderName: ${tx.framesSent} frames, ${tx.fragmentsSent} datagrams, ${tx.bytesSent} B, profile_idc=$profileIdc, level=${params!!.first[3].toInt() and 0xFF}")
        // Main (77) or Constrained Baseline (66): both phones' decoders take either.
        assertTrue("profile $profileIdc", profileIdc == 77 || profileIdc == 66)
        // No B-frames: every access unit is decodable in arrival order — one out per one in.
        assertTrue("frames delivered ${units.size}", units.size >= 40)

        val first = IosOracle.receive(key, firstFrameWire!!)!!
        assertTrue("first frame is a keyframe to iOS", first.isKeyFrame)
        assertEquals(5, first.firstNalType)

        H264Decoder().use { dec ->
            dec.setParameterSets(params!!.first, params!!.second)
            var pictures = 0
            var last: VideoImage? = null
            for (u in units) dec.decode(u).forEach { pictures++; last = it }
            assertEquals(0L, dec.errors)
            assertTrue("decoded $pictures", pictures >= 40)
            assertEquals(w, last!!.width); assertEquals(h, last!!.height)
            // The luma ramp survives: left edge darker than right edge on the same row.
            val row = h / 2 * w
            fun luma(p: Int) = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
            assertTrue(luma(last!!.argb[row + 10]) != luma(last!!.argb[row + w - 10]))
        }
    }

    @Test fun `videotoolbox encodes what the phones decode`() = runCodec("h264_videotoolbox")

    @Test fun `openh264 encodes what the phones decode`() = runCodec("libopenh264")

    @Test
    fun `annex-B from the encoder contains the parameter sets on the first keyframe`() {
        assumeTrue(FfmpegVideo.available)
        val name = FfmpegVideo.encoderCandidates().firstOrNull()
        assumeTrue(name != null)
        H264Encoder(640, 360, names = listOf(name!!)).use { enc ->
            val f = frame(640, 360, 0)
            var out = enc.encode(f, true)
            var n = 0
            while (out.isEmpty() && n++ < 5) out = enc.encode(f, false)
            avutil.av_frame_free(f)
            val types = AnnexB.split(out.first().annexB).map { it[0].toInt() and 0x1F }
            assertTrue("NAL types $types", 7 in types && 8 in types && 5 in types)
        }
    }
}
