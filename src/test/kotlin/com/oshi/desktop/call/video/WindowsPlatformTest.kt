package com.oshi.desktop.call.video

import com.oshi.desktop.call.media.CallAudio
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What a Windows machine offers a call, asked of the real OS. Runs everywhere; the
 * DirectShow half only on Windows (GitHub `windows-latest` in CI: no camera, no audio
 * endpoint — which is exactly the case these must survive without throwing).
 */
class WindowsPlatformTest {

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @Test
    fun `the video natives load and an H264 encoder opens`() {
        assumeTrue("not Windows", isWindows)
        assertTrue("FFmpeg natives: ${FfmpegVideo.unavailableReason()}", FfmpegVideo.available)
        val names = FfmpegVideo.encoderCandidates()
        println("[windows] H.264 encoders present: $names")
        assertTrue("no H.264 encoder in the Windows natives", names.isNotEmpty())
        H264Encoder(CameraCapture.DEFAULT_WIDTH, CameraCapture.DEFAULT_HEIGHT).use { println("[windows] opened ${it.name}") }
    }

    @Test
    fun `dshow enumeration never throws, and no camera is a sentence not a crash`() {
        assumeTrue("not Windows", isWindows)
        val cams = CameraCapture.dshowCameras()
        println("[windows] DirectShow cameras: ${cams.ifEmpty { "none" }}")
        if (cams.isEmpty()) {
            val e = runCatching { CameraCapture() }.exceptionOrNull()
            assertTrue("opening a camera on a camera-less machine must fail with CameraUnavailable, got $e",
                e is CameraCapture.CameraUnavailable)
            assertTrue(e!!.message!!.contains("Privacy & security"))
        }
    }

    @Test
    fun `audio devices are reported, not assumed`() {
        // Printed for the CI log: a runner has no endpoint and a laptop has several.
        println("[audio] ${System.getProperty("os.name")}: full-duplex 48 kHz available=${CallAudio.isAvailable()}, " +
            "capture mixers=${CallAudio.captureDevices()}")
    }
}
