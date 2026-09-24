package com.oshi.desktop.media

import com.oshi.desktop.call.video.CameraCapture
import com.oshi.desktop.call.video.FfmpegVideo
import com.oshi.desktop.call.video.VideoImage
import com.oshi.desktop.call.video.VideoSource
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.MediaVault
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * __VIDEO_NOTE_2026_09_24__ Record a round video note: the call pipeline's camera
 * ([CameraCapture], centre-cropped square) + the voice-note microphone ([AudioRecorder]) →
 * [VideoNoteMp4Writer] → one MP4 in `media-tmp/`.
 *
 * The 15 s cap is enforced by the WRITER (it refuses a frame that would end past it) and the
 * capture loop stops itself there and reports [Listener.onCapped]; the microphone is bounded
 * to the same 15 s of PCM, and the audio is truncated to the picture at mux time. No UI timer
 * is trusted with the limit.
 *
 * Plaintext on disk: only the microphone's raw PCM (the recorder's own scratch file) and the
 * finished MP4, both in `media-tmp/` ([MediaVault.SCRATCH_DIR_NAME]) — the first deleted as
 * soon as it is muxed, the second by whoever sends or discards the note ([VideoNoteOutbox]).
 */
class VideoNoteRecorder(
    private val openCamera: () -> VideoSource = {
        CameraCapture(VideoNoteFormat.SIDE, VideoNoteFormat.SIDE, VideoNoteFormat.FPS, cropSquare = true)
    },
    private val workDir: File = DesktopPaths.file(MediaVault.SCRATCH_DIR_NAME),
    /** Null = record picture only (a machine with no microphone still sends a silent note). */
    private val microphone: AudioRecorder? = AudioRecorder(
        workDir = workDir,
        maxCaptureBytes = VideoNoteFormat.MAX_DURATION_MS * VoiceNoteFormat.BYTES_PER_SECOND / 1000L,
        minDurationMs = 1L,
    ),
    private val maxDurationMs: Long = VideoNoteFormat.MAX_DURATION_MS,
    private val side: Int = VideoNoteFormat.SIDE,
) {
    /** Called on the capture thread — marshal to the UI yourself. */
    interface Listener {
        fun onPreview(image: VideoImage) {}
        fun onProgress(elapsedMs: Long) {}
        /** The 15 s cap was reached; capture has stopped by itself. Call [stop] to get the file. */
        fun onCapped() {}
        /** The camera failed (at open or mid-way); nothing will be produced. */
        fun onError(message: String) {}
    }

    sealed interface Stop {
        data class Ready(val note: VideoNoteFile) : Stop
        data class Failure(val message: String) : Stop
    }

    private class Session(val thread: Thread, val running: AtomicBoolean, val writer: AtomicReference<VideoNoteMp4Writer?>, val error: AtomicReference<String?>, val micOn: Boolean)

    private val session = AtomicReference<Session?>(null)

    val isRecording: Boolean get() = session.get() != null

    /** Start capture on a background thread. False when already recording. */
    fun start(listener: Listener): Boolean {
        if (session.get() != null) return false
        val micOn = microphone?.let { it.start() is RecordingStart.Started } ?: false
        val t0 = System.nanoTime()
        val running = AtomicBoolean(true)
        val writerRef = AtomicReference<VideoNoteMp4Writer?>(null)
        val error = AtomicReference<String?>(null)
        val thread = Thread({
            var cam: VideoSource? = null
            try {
                cam = openCamera()
                val writer = VideoNoteMp4Writer(side = side, maxDurationMs = maxDurationMs)
                writerRef.set(writer)
                while (running.get()) {
                    val shot = cam.next(wantPreview = true) ?: continue
                    val ms = (System.nanoTime() - t0) / 1_000_000L
                    // VIDEO_NOTE_SPEC §3: encoded AS SEEN in the selfie preview (mirrored), like
                    // the phones; receivers never flip.
                    mirrorYuv420(shot.yuv)
                    if (!writer.addFrame(shot.yuv, ms)) {
                        running.set(false)
                        listener.onProgress(writer.durationMs)
                        listener.onCapped()
                        break
                    }
                    // The preview is the camera's own small BGRA copy, mirrored to match the file.
                    shot.preview?.let { listener.onPreview(it.mirrored()) }
                    listener.onProgress(ms)
                }
            } catch (t: Throwable) {
                val msg = (t as? CameraCapture.CameraUnavailable)?.message
                    ?: "the camera stopped (${t.javaClass.simpleName}: ${t.message ?: "no detail"})"
                error.set(msg)
                running.set(false)
                listener.onError(msg)
            } finally {
                runCatching { cam?.close() }
            }
        }, "oshi-videonote-capture").apply { isDaemon = true }
        session.set(Session(thread, running, writerRef, error, micOn))
        thread.start()
        return true
    }

    /** Stop and produce the MP4 (in `media-tmp/`). Blocking: joins capture, encodes AAC, muxes. */
    fun stop(): Stop {
        val s = session.getAndSet(null) ?: return Stop.Failure("Nothing is being recorded.")
        s.running.set(false)
        s.thread.join(JOIN_TIMEOUT_MS)
        val pcm = if (s.micOn) microphoneBytes() else null
        val writer = s.writer.get()
        try {
            s.error.get()?.let { return Stop.Failure(it) }
            if (writer == null || writer.frames == 0) return Stop.Failure("The camera delivered no picture.")
            if (writer.durationMs < VideoNoteFormat.MIN_DURATION_MS) return Stop.Failure("Too short — hold the recording a little longer.")
            DesktopPaths.ensurePrivateDir(workDir)
            val out = File(workDir, VideoNoteWire.filename())
            val note = writer.finish(out, pcm)
            DesktopPaths.makePrivate(out)
            out.deleteOnExit()
            return Stop.Ready(note)
        } catch (t: Throwable) {
            return Stop.Failure("The video note could not be encoded (${t.javaClass.simpleName}: ${t.message ?: "no detail"}).")
        } finally {
            if (s.thread.isAlive) s.thread.join(JOIN_TIMEOUT_MS)
            // Only close once the capture thread is done with it (it may still be encoding).
            if (!s.thread.isAlive) writer?.close()
        }
    }

    /** Abandon: stop capture, drop the microphone's scratch file, write nothing. */
    fun cancel() {
        val s = session.getAndSet(null) ?: return
        s.running.set(false)
        runCatching { microphone?.cancel() }
        s.thread.join(JOIN_TIMEOUT_MS)
        if (!s.thread.isAlive) s.writer.get()?.close()
    }

    /** The microphone's PCM (WAV body), its scratch file deleted before this returns. */
    private fun microphoneBytes(): ByteArray? {
        val mic = microphone ?: return null
        return when (val r = mic.stop()) {
            is RecordingStop.Captured -> try {
                val all = r.recording.file.readBytes()
                if (all.size > WavWriter.HEADER_BYTES) all.copyOfRange(WavWriter.HEADER_BYTES, all.size) else null
            } finally {
                r.recording.file.delete()
            }
            // Silent/empty mic: the note goes out without sound rather than not at all.
            is RecordingStop.Failure -> null
        }
    }

    companion object {
        const val JOIN_TIMEOUT_MS = 3_000L

        /** Horizontal mirror of a YUV420P frame, in place (≤ 480×480: ~350 KB of row swaps). */
        internal fun mirrorYuv420(f: org.bytedeco.ffmpeg.avutil.AVFrame) {
            val w = f.width(); val h = f.height()
            for (plane in 0..2) {
                val pw = if (plane == 0) w else (w + 1) / 2
                val ph = if (plane == 0) h else (h + 1) / 2
                val stride = f.linesize(plane)
                val p = f.data(plane)
                val row = ByteArray(pw)
                for (y in 0 until ph) {
                    val at = y.toLong() * stride
                    p.position(at).get(row)
                    row.reverse()
                    p.position(at).put(*row)
                }
                p.position(0)
            }
        }

        /**
         * Null when this machine can record a note; otherwise the sentence for the disabled
         * button's tooltip. Opens no device (a Mac cannot list cameras through FFmpeg, so there
         * the answer is "probably" and a missing camera is reported when recording starts).
         */
        fun unavailableReason(os: String = System.getProperty("os.name").orEmpty()): String? {
            FfmpegVideo.loadDevices().exceptionOrNull()?.let {
                return "The camera library did not load on this machine (${it.javaClass.simpleName})."
            }
            if (FfmpegVideo.encoderCandidates().isEmpty()) return "No H.264 encoder is available on this machine."
            val o = os.lowercase()
            if (!o.contains("mac") && com.oshi.desktop.call.media.CallDevices.cameras(os).isEmpty()) {
                return "No camera found on this computer."
            }
            return null
        }
    }
}

/**
 * Where a recorded note lives between "Send" and the relay, and afterwards as OUR bubble.
 *
 * The send path keeps the file's path as the sender's own row, so the note is SEALED
 * ("OSHIMED1") under `media/videonotes/` — like [com.oshi.desktop.ui.components.GifOutbox] —
 * and the plaintext MP4 in `media-tmp/` is deleted the moment the sealed copy exists.
 */
object VideoNoteOutbox {
    fun seal(
        plain: File,
        dir: File = File(DesktopPaths.dataDir, "media/videonotes"),
        vault: MediaVault? = MediaVault.current(),
    ): File {
        val v = vault ?: throw IllegalStateException("no media vault: video note not stored")
        DesktopPaths.ensurePrivateDir(dir)
        val out = File(dir, plain.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        try {
            v.sealingStream(out).use { sink -> plain.inputStream().use { it.copyTo(sink) } }
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            plain.delete()
        }
        return out
    }
}
