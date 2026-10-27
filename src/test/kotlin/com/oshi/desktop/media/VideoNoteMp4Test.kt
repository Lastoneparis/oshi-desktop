package com.oshi.desktop.media

import com.oshi.desktop.call.video.FfmpegVideo
import com.oshi.desktop.call.video.VideoImage
import com.oshi.desktop.call.video.VideoSource
import com.oshi.desktop.call.video.CameraCapture
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.PointerPointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin

/**
 * __VIDEO_NOTE_2026_09_24__ The note's MP4, produced and read back on the FFmpeg natives this
 * build already ships — not a mock. What is asserted is what a PHONE will see: an MP4 whose
 * video stream is H.264 at 480×480 and whose audio stream is AAC, ≤ 15 s, without a software
 * tag; and that the 15 s cap is enforced by the writer itself.
 */
class VideoNoteMp4Test {

    private fun natives() = assumeTrue("FFmpeg natives not loadable here", FfmpegVideo.load().isSuccess &&
        FfmpegVideo.encoderCandidates().isNotEmpty())

    /** A 480×480 YUV420P test card whose luma shifts with [n]. */
    private class Card(side: Int) : AutoCloseable {
        val frame: AVFrame = avutil.av_frame_alloc().apply {
            format(avutil.AV_PIX_FMT_YUV420P); width(side); height(side)
            check(avutil.av_frame_get_buffer(this, 32) >= 0)
        }
        private val side = side
        fun paint(n: Int): AVFrame {
            avutil.av_frame_make_writable(frame)
            val row = ByteArray(side) { x -> ((x + n * 8) % 220 + 16).toByte() }
            val chroma = ByteArray(side / 2) { 128.toByte() }
            for (y in 0 until side) frame.data(0).position(y.toLong() * frame.linesize(0)).put(*row)
            for (y in 0 until side / 2) {
                frame.data(1).position(y.toLong() * frame.linesize(1)).put(*chroma)
                frame.data(2).position(y.toLong() * frame.linesize(2)).put(*chroma)
            }
            frame.data(0).position(0); frame.data(1).position(0); frame.data(2).position(0)
            return frame
        }
        override fun close() { avutil.av_frame_free(frame) }
    }

    private fun tone(ms: Long): ByteArray {
        val n = (ms * VideoNoteFormat.SAMPLE_RATE / 1000).toInt()
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = (sin(2 * PI * 440 * i / VideoNoteFormat.SAMPLE_RATE) * 8000).toInt()
            out[2 * i] = (s and 0xFF).toByte(); out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }

    @Test
    fun `a recorded note is a square H264 plus AAC MP4 that reads back`() {
        natives()
        val dir = Files.createTempDirectory("oshi-vn").toFile()
        try {
            val out = File(dir, VideoNoteWire.filename(1L))
            val note = Card(VideoNoteFormat.SIDE).use { card ->
                VideoNoteMp4Writer().use { w ->
                    for (i in 0 until 60) assertTrue(w.addFrame(card.paint(i), i * 33L))
                    w.finish(out, tone(2_000))
                }
            }
            assertTrue(out.isFile && out.length() > 1_000)
            assertTrue("audio track missing", note.hasAudio)
            assertTrue("duration ${note.durationMs}", note.durationMs in 1_900..2_100)

            // What a phone's demuxer sees.
            val fmt = AVFormatContext(null)
            assertTrue(avformat.avformat_open_input(fmt, out.absolutePath, null, null as AVDictionary?) >= 0)
            try {
                avformat.avformat_find_stream_info(fmt, null as PointerPointer<*>?)
                val codecs = (0 until fmt.nb_streams()).map { fmt.streams(it).codecpar() }
                val v = codecs.single { it.codec_type() == avutil.AVMEDIA_TYPE_VIDEO }
                val a = codecs.single { it.codec_type() == avutil.AVMEDIA_TYPE_AUDIO }
                assertEquals(avcodec.AV_CODEC_ID_H264, v.codec_id())
                assertEquals(480, v.width()); assertEquals(480, v.height())
                assertEquals(avcodec.AV_CODEC_ID_AAC, a.codec_id())
                assertTrue("container says ${fmt.duration()} µs", fmt.duration() in 1_800_000L..2_200_000L)
            } finally {
                avformat.avformat_close_input(fmt)
            }

            // Spec §3: no software / device tag. BITEXACT suppresses libavformat's "Lavf…".
            val text = String(out.readBytes(), Charsets.ISO_8859_1)
            assertFalse("the MP4 carries a Lavf software tag", text.contains("Lavf"))

            // Our own player path: first picture is square at the requested size, audio decodes.
            VideoNoteReader(out, targetSide = 200).use { r ->
                assertTrue(r.hasAudio)
                val first = r.firstPicture()!!
                assertEquals(200, first.width); assertEquals(200, first.height)
                var sound = 0; var pictures = 1
                while (true) {
                    when (r.next() ?: break) {
                        is VideoNoteReader.Item.Sound -> sound++
                        is VideoNoteReader.Item.Picture -> pictures++
                    }
                }
                assertTrue("no audio decoded", sound > 0)
                assertTrue("only $pictures pictures decoded", pictures >= 55)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the writer refuses every frame past 15 seconds and the note never exceeds the cap`() {
        natives()
        val dir = Files.createTempDirectory("oshi-vn-cap").toFile()
        try {
            Card(160).use { card ->
                VideoNoteMp4Writer(side = 160).use { w ->
                    var accepted = 0
                    var t = 0L
                    while (t < 20_000L) {
                        if (w.addFrame(card.paint(accepted), t)) accepted++
                        t += 100
                    }
                    assertFalse("a frame at 15 000 ms was accepted", w.accepts(15_000))
                    assertTrue(w.durationMs <= VideoNoteFormat.MAX_DURATION_MS)
                    // A long microphone capture is truncated to the picture.
                    val note = w.finish(File(dir, "cap.mp4"), tone(19_000))
                    assertTrue("note is ${note.durationMs} ms", note.durationMs <= VideoNoteFormat.MAX_DURATION_MS)
                    assertEquals(150, accepted)
                    VideoNoteReader(note.file).use { r ->
                        assertTrue("container ${r.durationMs} ms", (r.durationMs ?: 0) <= VideoNoteWire.MAX_DURATION_MS + 100)
                    }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * The recorder with a scripted camera (no hardware): capture stops by itself at the cap
     * and reports it, and the MP4 lands in the scratch dir only.
     */
    @Test
    fun `the recorder stops itself at the cap and leaves the file in scratch`() {
        natives()
        val dir = Files.createTempDirectory("oshi-vn-rec").toFile()
        try {
            val cam = object : VideoSource {
                val card = Card(64)
                var n = 0
                override val outW = 64
                override val outH = 64
                override val deviceName = "script"
                override fun next(wantPreview: Boolean): CameraCapture.Captured {
                    Thread.sleep(5)
                    return CameraCapture.Captured(card.paint(n++), if (wantPreview) VideoImage(2, 2, IntArray(4)) else null)
                }
                override fun close() = card.close()
            }
            // A 1.5 s cap keeps the test fast; the production cap is the same code path.
            val rec = VideoNoteRecorder(openCamera = { cam }, workDir = dir, microphone = null, maxDurationMs = 1_500, side = 64)
            val capped = java.util.concurrent.CountDownLatch(1)
            assertTrue(rec.start(object : VideoNoteRecorder.Listener {
                override fun onCapped() = capped.countDown()
            }))
            assertTrue("never capped", capped.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val stop = rec.stop()
            assertTrue("stop said $stop", stop is VideoNoteRecorder.Stop.Ready)
            val note = (stop as VideoNoteRecorder.Stop.Ready).note
            assertTrue(note.durationMs <= 1_500)
            assertEquals(dir.canonicalFile, note.file.parentFile.canonicalFile)
            assertFalse(note.hasAudio)
        } finally {
            dir.deleteRecursively()
        }
    }
}
