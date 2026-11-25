package com.oshi.desktop.pairing

import com.oshi.desktop.call.video.CameraCapture
import com.oshi.desktop.call.video.VideoImage
import com.oshi.desktop.call.video.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The camera scan loop, driven end to end without a camera.
 *
 * The source is a constructor parameter for exactly this reason. A scanner that could only
 * be exercised by holding a phone up to a laptop would be a scanner nobody tests — and the
 * QR direction this client already had carries its own warning about that: *"a wrong byte is
 * invisible until someone points a phone at the screen."*
 *
 * The frames here are real: `QrMatrix` encodes the payload (the same generator the New
 * conversation screen paints on screen for a phone to read), it is rasterised into the
 * packed-int picture a camera preview actually produces, and ZXing decodes it from there. A
 * fixture that handed the scanner a string would test nothing but the plumbing.
 */
class QrCameraScannerTest {

    @Test
    fun `a QR held in front of the camera is read, once, and the camera is released`() {
        val payload = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789+/="
        // Blank frames first: most frames of any real scan have no code in them, and the
        // NotFoundException they raise is the NORMAL case rather than an error to report.
        val source = FakeCamera(List(3) { blank(320, 240) } + qrFrame(payload))

        val result = AtomicReference<Triple<String?, String?, Boolean>>()
        val previews = AtomicInteger()
        val done = CountDownLatch(1)
        val scanner = QrCameraScanner { source }

        assertNull("the scanner refused to start", scanner.start(object : QrCameraScanner.Listener {
            override fun onPreview(image: VideoImage) { previews.incrementAndGet() }
            override fun onFinished(payload: String?, error: String?, stopped: Boolean) {
                result.set(Triple(payload, error, stopped)); done.countDown()
            }
        }))
        assertTrue("the scan never finished", done.await(30, TimeUnit.SECONDS))

        val (got, error, stopped) = result.get()
        assertEquals("the payload must arrive byte for byte", payload, got)
        assertNull(error)
        assertFalse(stopped)
        assertTrue("every frame must reach the preview, including the blank ones", previews.get() >= 4)
        assertTrue("the camera must be released when the scan ends", source.closed)
        assertFalse(scanner.isScanning)
    }

    @Test
    fun `stopping closes the camera and reports a stop, not a failure`() {
        // A source that never shows a code and never ends: the user closing the panel is the
        // only way out, and it must not look like an error to the UI.
        // Endless and never showing a code: one frame, handed back forever.
        val forever = blank(160, 120)
        val source = FakeCamera { forever }
        val done = CountDownLatch(1)
        val result = AtomicReference<Triple<String?, String?, Boolean>>()
        val seen = CountDownLatch(2)
        val scanner = QrCameraScanner { source }

        scanner.start(object : QrCameraScanner.Listener {
            override fun onPreview(image: VideoImage) { seen.countDown() }
            override fun onFinished(payload: String?, error: String?, stopped: Boolean) {
                result.set(Triple(payload, error, stopped)); done.countDown()
            }
        })
        assertTrue("the scan never produced a preview", seen.await(30, TimeUnit.SECONDS))
        scanner.stop()
        assertTrue("stopping did not end the scan", done.await(30, TimeUnit.SECONDS))

        val (got, error, stopped) = result.get()
        assertNull(got)
        assertNull("a user closing the panel is not a failure", error)
        assertTrue(stopped)
        assertTrue(source.closed)
    }

    @Test
    fun `a camera that will not open fails in the caller's hands, not in a thread`() {
        val done = CountDownLatch(1)
        val error = AtomicReference<String?>()
        val scanner = QrCameraScanner { throw CameraCapture.CameraUnavailable("no camera on this machine") }
        scanner.start(object : QrCameraScanner.Listener {
            override fun onFinished(payload: String?, e: String?, stopped: Boolean) {
                error.set(e); done.countDown()
            }
        })
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertEquals("no camera on this machine", error.get())
        assertFalse(scanner.isScanning)
    }

    @Test
    fun `whatever is decoded still goes through the ordinary refusals`() {
        // The scanner decides NOTHING. A camera is an input method, not an authority, and a
        // string off a lens gets the same treatment as one off the clipboard.
        val junk = "https://example.invalid/not-a-contact"
        val source = FakeCamera(listOf(qrFrame(junk)))
        val result = AtomicReference<String?>()
        val done = CountDownLatch(1)
        QrCameraScanner { source }.start(object : QrCameraScanner.Listener {
            override fun onFinished(payload: String?, error: String?, stopped: Boolean) {
                result.set(payload); done.countDown()
            }
        })
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertEquals("the scanner must hand back exactly what it read", junk, result.get())
        assertTrue(
            "and ContactQr must refuse it, as it would a pasted one",
            ContactQr.parse(result.get()!!) is ContactQr.Scan.Rejected,
        )
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * A camera is a STREAM, not a list, and this fake models it as one.
     *
     * The first version held its frames in a `List`, and the endless-scan test asked it for
     * 10 000 — which is 10 000 × 160 × 120 × 4 bytes of `IntArray` allocated up front, and
     * it died with `OutOfMemoryError: Java heap space`. A real camera never materialises its
     * frames either; it hands over one at a time and forgets it.
     */
    private class FakeCamera(private val supply: () -> VideoImage?) : VideoSource {
        constructor(frames: List<VideoImage>) : this(fromList(frames))

        override val outW = 320
        override val outH = 240
        override val deviceName = "fake camera"
        @Volatile var closed = false
        override fun next(wantPreview: Boolean): CameraCapture.Captured? {
            val image = synchronized(this) { supply() } ?: return null
            // `yuv` is never read by the scanner — it only ever wants the preview — so the
            // fake does not fabricate an AVFrame it would have to free.
            return CameraCapture.Captured(NO_YUV, image)
        }
        override fun close() { closed = true }
    }

    private fun blank(w: Int, h: Int) = VideoImage(w, h, IntArray(w * h) { 0xFFFFFFFF.toInt() })

    /** The payload as a real QR, rasterised the way a camera preview frame is packed. */
    private fun qrFrame(payload: String): VideoImage {
        val matrix = QrMatrix.encode(payload)
        val module = 6
        val quiet = 4
        val side = (matrix.size + quiet * 2) * module
        val px = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (row in 0 until matrix.size) for (col in 0 until matrix.size) {
            if (!matrix.dark(row, col)) continue
            for (dy in 0 until module) for (dx in 0 until module) {
                val y = (row + quiet) * module + dy
                val x = (col + quiet) * module + dx
                px[y * side + x] = 0xFF000000.toInt()
            }
        }
        return VideoImage(side, side, px)
    }

    private companion object {
        /** One frame at a time out of [frames], then end of stream. */
        fun fromList(frames: List<VideoImage>): () -> VideoImage? {
            val it = frames.iterator()
            return { if (it.hasNext()) it.next() else null }
        }

        /** A placeholder the scanner never dereferences. */
        val NO_YUV: org.bytedeco.ffmpeg.avutil.AVFrame = org.bytedeco.ffmpeg.avutil.AVFrame()
    }
}
