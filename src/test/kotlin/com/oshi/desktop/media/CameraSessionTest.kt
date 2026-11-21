package com.oshi.desktop.media

import com.oshi.desktop.call.video.FfmpegVideo
import com.oshi.desktop.call.video.SyntheticCamera
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.PointerPointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * __DESKTOP_CAMERA_2027__ The camera sheet's two outputs, on the synthetic test card (no real
 * camera, no permission prompt): a FULL-size still, and a 16:9 H.264 clip that a phone reads.
 */
class CameraSessionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun natives() = assumeTrue("FFmpeg natives not loadable here",
        FfmpegVideo.load().isSuccess && FfmpegVideo.encoderCandidates().isNotEmpty())

    private fun session(maxMs: Long = CameraSession.MAX_CLIP_MS) = CameraSession(
        openCamera = { SyntheticCamera(CameraSession.WIDTH, CameraSession.HEIGHT, 30) },
        workDir = tmp.root,
        microphone = { null },
        maxClipMs = maxMs,
    )

    @Test
    fun `a photo is the full camera frame, not the preview`() {
        natives()
        val photos = LinkedBlockingQueue<BufferedImage>()
        val previews = LinkedBlockingQueue<Int>()
        val s = session()
        s.start(object : CameraSession.Listener {
            override fun onPhoto(image: BufferedImage) { photos.add(image) }
            override fun onPreview(image: com.oshi.desktop.call.video.VideoImage) { previews.add(image.width) }
        })
        try {
            val previewWidth = previews.poll(10, TimeUnit.SECONDS) ?: error("no preview")
            s.snap()
            val p = photos.poll(10, TimeUnit.SECONDS) ?: error("no photo")
            assertEquals(CameraSession.WIDTH, p.width)
            assertEquals(CameraSession.HEIGHT, p.height)
            assertTrue("preview is smaller than the photo", previewWidth < p.width)
        } finally { s.close() }
    }

    @Test
    fun `a clip is a 1280x720 H264 MP4, and the cap stops it`() {
        natives()
        val capped = LinkedBlockingQueue<Boolean>()
        val s = session(maxMs = 1_500)
        s.start(object : CameraSession.Listener { override fun onCapped() { capped.add(true) } })
        try {
            Thread.sleep(300)
            assertTrue(s.startRecording())
            assertTrue("cap not reached", capped.poll(15, TimeUnit.SECONDS) == true)
            val clip = s.stopRecording() as? CameraSession.Clip.Ready ?: error("no clip")
            assertTrue(clip.durationMs in 500..1_500)
            val fmt = AVFormatContext(null)
            assertTrue(avformat.avformat_open_input(fmt, clip.file.absolutePath, null, null) >= 0)
            avformat.avformat_find_stream_info(fmt, null as PointerPointer<*>?)
            val par = fmt.streams(0).codecpar()
            assertEquals(avutil.AVMEDIA_TYPE_VIDEO, par.codec_type())
            assertEquals(1280, par.width()); assertEquals(720, par.height())
            avformat.avformat_close_input(fmt)
        } finally { s.close() }
    }
}
