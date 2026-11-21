package com.oshi.desktop.call.video

import com.oshi.desktop.call.media.VideoSendSession
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * CI run 35868599749, production cross-host, Windows caller: 642 complete frames delivered,
 * 19 dropped before the first IDR, **0 decoded** for the whole call — while the Linux side
 * decoded every frame the other way.
 *
 * The first video datagram reached the caller over the relay before its own camera had
 * loaded FFmpeg, so the decode thread paid the native load. More than eight access units
 * queued behind it, the overflow path cleared the queue — and with it the ONLY
 * `Params` job, which the receive session writes once (first IDR) and again only when the
 * SPS/PPS bytes change. Every later IDR carried identical parameter sets, so none was ever
 * written again and the decoder never had an SPS. The fix keeps the parameter sets across
 * an overflow; this test is the slow start that proved it.
 */
class DecodeBacklogTest {
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val salt = byteArrayOf(4, 3, 2, 1)

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

    @Test
    fun `a decoder slow to start still decodes once the backlog clears`() {
        assumeTrue("FFmpeg natives: ${FfmpegVideo.unavailableReason()}", FfmpegVideo.available)
        val name = FfmpegVideo.encoderCandidates().firstOrNull()
        assumeTrue("no H.264 encoder in this build", name != null)
        val w = CameraCapture.DEFAULT_WIDTH; val h = CameraCapture.DEFAULT_HEIGHT

        val rx = CallVideoSession(
            key, salt, isCaller = false, sendDatagram = { true }, nextAudioSeq = null,
            cameraFactory = null,
            // The first-touch native load on a Windows runner: the thread is up, the
            // decoder is not, and the peer's frames keep coming.
            decoderFactory = { Thread.sleep(1_500); H264Decoder() },
        )
        try {
            val tx = VideoSendSession(key, true) { rx.onMedia(it) }
            H264Encoder(w, h, names = listOf(name!!)).use { enc ->
                // 90 frames at the pace of a call, keyframes at 0/30/60 as the phones send.
                for (i in 0 until 90) {
                    val f = frame(w, h, i)
                    for (au in enc.encode(f, forceKey = i % 30 == 0)) tx.sendAccessUnit(au.annexB, au.key, i * 33L)
                    avutil.av_frame_free(f)
                    Thread.sleep(33)
                }
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (rx.remoteFrameCount == 0L && System.currentTimeMillis() < deadline) Thread.sleep(50)
            println("DECODE_BACKLOG ${rx.rxStats()}")
            assertTrue("decoded nothing after a slow start: ${rx.rxStats()}", rx.remoteFrameCount > 0)
        } finally {
            rx.close()
        }
    }
}
