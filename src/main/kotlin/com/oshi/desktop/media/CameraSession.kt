package com.oshi.desktop.media

import com.oshi.desktop.call.video.CameraCapture
import com.oshi.desktop.call.video.Scaler
import com.oshi.desktop.call.video.VideoImage
import com.oshi.desktop.call.video.VideoSource
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.MediaVault
import org.bytedeco.ffmpeg.global.avutil
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * __DESKTOP_CAMERA_2027__ Take a photo or record a video with this computer's camera, for the
 * composer — the phones' camera button. Built from the pieces calls and video notes already
 * use: [CameraCapture] (the call pipeline's camera, permission prompts and device choice
 * included), a full-size BGRA conversion for the still, and [VideoNoteMp4Writer] widened to a
 * 16:9 frame for the clip.
 *
 * One capture thread drives a live preview. [snap] takes the NEXT frame at full size (never
 * the small preview) and hands it over as a picture; nothing is written to disk for a photo —
 * the caller sends it through the markup screen, which writes the one temporary file the send
 * path needs. [startRecording] / [stopRecording] make an MP4 in `media-tmp/` (plaintext until
 * the send path seals it, exactly like a video note), capped at [MAX_CLIP_MS].
 *
 * Not mirrored: a photo or clip from the camera shows the scene as others see it, as the
 * phones' rear-camera default does; only the PREVIEW is mirrored, because a person framing
 * themselves expects a mirror.
 */
class CameraSession(
    private val openCamera: () -> VideoSource = { CameraCapture(WIDTH, HEIGHT, VideoNoteFormat.FPS) },
    private val workDir: File = DesktopPaths.file(MediaVault.SCRATCH_DIR_NAME),
    private val microphone: () -> AudioRecorder? = {
        AudioRecorder(
            workDir = workDir,
            maxCaptureBytes = MAX_CLIP_MS * VoiceNoteFormat.BYTES_PER_SECOND / 1000L,
            minDurationMs = 1L,
        )
    },
    private val maxClipMs: Long = MAX_CLIP_MS,
) {
    /** Called on the capture thread — marshal to the UI yourself. */
    interface Listener {
        fun onPreview(image: VideoImage) {}
        fun onPhoto(image: BufferedImage) {}
        fun onRecording(elapsedMs: Long) {}
        /** The clip cap was reached; recording stopped by itself. Call [stopRecording]. */
        fun onCapped() {}
        fun onError(message: String) {}
    }

    sealed interface Clip {
        data class Ready(val file: File, val durationMs: Long) : Clip
        data class Failure(val message: String) : Clip
    }

    private class Recording(val writer: VideoNoteMp4Writer, val t0: Long, val mic: AudioRecorder?, val micOn: Boolean)

    private val running = AtomicBoolean(false)
    private val snapRequested = AtomicBoolean(false)
    private val recording = AtomicReference<Recording?>(null)
    private val finishing = AtomicReference<Recording?>(null)
    private var thread: Thread? = null

    val isOpen: Boolean get() = running.get()
    val isRecording: Boolean get() = recording.get() != null

    /** Open the camera on a background thread. False when already open. */
    fun start(listener: Listener): Boolean {
        if (!running.compareAndSet(false, true)) return false
        thread = Thread({ loop(listener) }, "oshi-camera").apply { isDaemon = true; start() }
        return true
    }

    /** The next frame, full size, goes to [Listener.onPhoto]. */
    fun snap() { snapRequested.set(true) }

    fun startRecording(): Boolean {
        if (!running.get() || recording.get() != null) return false
        val writer = runCatching {
            VideoNoteMp4Writer(side = WIDTH, height = HEIGHT, maxDurationMs = maxClipMs, bitRate = CLIP_BITRATE)
        }.getOrElse { return false }
        val mic = runCatching { microphone() }.getOrNull()
        val micOn = mic?.let { it.start() is RecordingStart.Started } ?: false
        recording.set(Recording(writer, System.nanoTime(), mic, micOn))
        return true
    }

    /** Stop recording and write the MP4. Blocking (AAC + mux), call off the UI thread. */
    fun stopRecording(): Clip {
        val r = recording.getAndSet(null) ?: finishing.getAndSet(null) ?: return Clip.Failure("not recording")
        finishing.set(null)
        // Let the capture thread finish the frame it may be encoding.
        Thread.sleep(80)
        synchronized(r.writer) {
            val pcm = if (r.micOn) micBytes(r.mic) else { runCatching { r.mic?.cancel() }; null }
            return try {
                if (r.writer.frames == 0) return Clip.Failure("The camera delivered no picture.")
                if (r.writer.durationMs < VideoNoteFormat.MIN_DURATION_MS) return Clip.Failure("too short")
                DesktopPaths.ensurePrivateDir(workDir)
                val out = File(workDir, "OSHI-video-${System.currentTimeMillis()}.mp4")
                val made = r.writer.finish(out, pcm)
                DesktopPaths.makePrivate(out)
                out.deleteOnExit()
                Clip.Ready(made.file, made.durationMs)
            } catch (t: Throwable) {
                Clip.Failure("${t.javaClass.simpleName}: ${t.message ?: "no detail"}")
            } finally {
                runCatching { r.writer.close() }
            }
        }
    }

    /** Close the camera; an unfinished recording is dropped. */
    fun close() {
        recording.getAndSet(null)?.let { r -> runCatching { r.mic?.cancel() }; synchronized(r.writer) { runCatching { r.writer.close() } } }
        finishing.getAndSet(null)?.let { r -> runCatching { r.mic?.cancel() }; runCatching { r.writer.close() } }
        running.set(false)
        thread?.join(JOIN_TIMEOUT_MS)
        thread = null
    }

    private fun loop(listener: Listener) {
        var cam: VideoSource? = null
        var still: Scaler? = null
        try {
            cam = openCamera()
            while (running.get()) {
                val shot = cam.next(wantPreview = true) ?: continue
                if (snapRequested.getAndSet(false)) {
                    val s = still ?: Scaler(cam.outW, cam.outH, avutil.AV_PIX_FMT_YUV420P, cam.outW, cam.outH, avutil.AV_PIX_FMT_BGRA)
                        .also { still = it }
                    s.scale(shot.yuv)
                    listener.onPhoto(toBufferedImage(s.toImage()))
                }
                recording.get()?.let { r ->
                    val ms = (System.nanoTime() - r.t0) / 1_000_000L
                    val accepted = synchronized(r.writer) { r.writer.addFrame(shot.yuv, ms) }
                    if (!accepted) {
                        // The cap: stop taking frames, keep the writer for stopRecording().
                        if (recording.compareAndSet(r, null)) finishing.set(r)
                        listener.onCapped()
                    } else listener.onRecording(ms)
                }
                shot.preview?.let { listener.onPreview(it.mirrored()) }
            }
        } catch (t: Throwable) {
            val msg = (t as? CameraCapture.CameraUnavailable)?.message
                ?: "the camera stopped (${t.javaClass.simpleName}: ${t.message ?: "no detail"})"
            listener.onError(msg)
        } finally {
            running.set(false)
            runCatching { still?.close() }
            runCatching { cam?.close() }
        }
    }

    private fun micBytes(mic: AudioRecorder?): ByteArray? {
        val m = mic ?: return null
        return when (val r = m.stop()) {
            is RecordingStop.Captured -> try {
                val all = r.recording.file.readBytes()
                if (all.size > WavWriter.HEADER_BYTES) all.copyOfRange(WavWriter.HEADER_BYTES, all.size) else null
            } finally {
                r.recording.file.delete()
            }
            is RecordingStop.Failure -> null
        }
    }

    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        /** One minute: ~15 MB at [CLIP_BITRATE] — a clip, not a film. */
        const val MAX_CLIP_MS = 60_000L
        const val CLIP_BITRATE = 2_000_000L
        const val JOIN_TIMEOUT_MS = 3_000L

        fun toBufferedImage(img: VideoImage): BufferedImage =
            BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also {
                it.setRGB(0, 0, img.width, img.height, img.argb, 0, img.width)
            }

        /** Same rule as the video-note button: null when this machine can try. */
        fun unavailableReason(): String? = VideoNoteRecorder.unavailableReason()
    }
}
