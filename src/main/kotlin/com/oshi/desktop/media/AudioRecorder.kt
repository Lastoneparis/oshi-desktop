package com.oshi.desktop.media

import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.LineUnavailableException

/**
 * Microphone → a PCM WAV file on disk, with every way that can go wrong given a name.
 *
 * ============================================================ THE FAILURE THIS FILE EXISTS FOR
 *
 * **On macOS, a denied microphone permission does not throw.** A process without the
 * Microphone TCC grant opens a `TargetDataLine` successfully, reads from it successfully,
 * at the right rate, forever — and every sample is zero. Byte counts are right, the file
 * size is right, the duration is right, `AudioSystem` reports no error, and the user gets
 * a voice note that is silence. Nothing in the JDK's API surface distinguishes that from
 * a very quiet room.
 *
 * So this recorder tracks the PEAK SAMPLE VALUE across the whole capture
 * ([Pcm.peakAbs16]) and refuses to hand back a recording whose peak is exactly zero,
 * returning [RecordingFailure.SilentCapture] with the permission hint attached. A quiet
 * room is not zero — a real microphone's own noise floor puts the peak in the tens even
 * in silence. An exact zero across hundreds of thousands of samples means no signal path
 * at all.
 *
 * That is the concrete form of the rule this whole package is built to: **a machine that
 * cannot record must produce a typed failure, never a silent empty file.**
 *
 * ============================================================ THE OTHER FAILURES
 *
 * | condition | result |
 * |---|---|
 * | no capture device at all | [RecordingFailure.NoInputDevice] |
 * | device held by another process, or OS refusal | [RecordingFailure.DeviceUnavailable] |
 * | a `SecurityManager` forbids the line | [RecordingFailure.PermissionDenied] |
 * | `start` while already recording | [RecordingFailure.AlreadyRecording] |
 * | `stop` with nothing running | [RecordingFailure.NotRecording] |
 * | the line ended having produced nothing | [RecordingFailure.EmptyCapture] |
 * | every sample zero | [RecordingFailure.SilentCapture] |
 * | shorter than [VoiceNoteFormat.MIN_DURATION_MS] | [RecordingFailure.TooShort] |
 * | the temp file could not be written | [RecordingFailure.WriteFailed] |
 *
 * ============================================================ THE BOUND
 *
 * 5 minutes — [VoiceNoteFormat.MAX_DURATION_MS], which is Android's shipped
 * `MAX_RECORDING_DURATION_MS`. It is enforced as a BYTE ceiling in the capture loop, so
 * the recording stops at 5 minutes of audio actually captured rather than 5 minutes of
 * wall clock; a stalled thread then yields a shorter note, which is true, instead of a
 * padded one, which is not. Hitting it is not an error: [Recording.truncated] is set and
 * the note is returned.
 *
 * ============================================================ WHAT IS NOT VERIFIED
 *
 * Every row of that table is exercised against a fake device. **None of them has been
 * observed against a real microphone**, including the silent-capture case that motivated
 * the whole design — that would need a machine with the TCC grant deliberately withheld.
 * The arithmetic, the state machine and the WAV header are proven; the hardware is not.
 */
class AudioRecorder(
    private val devices: AudioDevices = AudioDevices.JavaSound,
    private val workDir: File = com.oshi.desktop.store.DesktopPaths.file(com.oshi.desktop.store.MediaVault.SCRATCH_DIR_NAME),
    private val format: AudioFormat = VoiceNoteFormat.CAPTURE_FORMAT,
    private val maxCaptureBytes: Long = VoiceNoteFormat.MAX_CAPTURE_BYTES,
    private val minDurationMs: Long = VoiceNoteFormat.MIN_DURATION_MS,
) {

    private val session = AtomicReference<Session?>(null)

    val isRecording: Boolean get() = session.get() != null

    /** Can this machine capture at all? Asks the mixer; opens nothing; safe any time. */
    fun isAvailable(): Boolean = devices.supportsCapture(format)

    /** Mixer names offering a microphone in [format]. For a settings screen or a CLI. */
    fun deviceNames(): List<String> = devices.captureDeviceNames(format)

    // ------------------------------------------------------------------------- start

    fun start(): RecordingStart {
        if (session.get() != null) return RecordingStart.Failure(RecordingFailure.AlreadyRecording)
        if (!devices.supportsCapture(format)) {
            return RecordingStart.Failure(RecordingFailure.NoInputDevice)
        }

        val raw = try {
            workDir.mkdirs()
            File(workDir, "capture-${UUID.randomUUID()}.pcm")
        } catch (t: Throwable) {
            return RecordingStart.Failure(RecordingFailure.WriteFailed(describe(t)))
        }

        val line = try {
            // Four chunks of device buffer: enough that a scheduling hiccup does not
            // overrun the line, small enough that stop() is not waiting on a backlog.
            devices.openCapture(format, VoiceNoteFormat.CAPTURE_CHUNK_BYTES * 4)
        } catch (e: LineUnavailableException) {
            raw.delete()
            return RecordingStart.Failure(RecordingFailure.DeviceUnavailable(describe(e)))
        } catch (e: SecurityException) {
            raw.delete()
            return RecordingStart.Failure(RecordingFailure.PermissionDenied(describe(e)))
        } catch (t: Throwable) {
            raw.delete()
            return RecordingStart.Failure(RecordingFailure.DeviceUnavailable(describe(t)))
        }

        val out = try {
            raw.outputStream().buffered()
        } catch (t: Throwable) {
            line.close()
            raw.delete()
            return RecordingStart.Failure(RecordingFailure.WriteFailed(describe(t)))
        }

        val s = Session(raw, out, line, maxCaptureBytes)
        if (!session.compareAndSet(null, s)) {
            // Lost a race with a concurrent start. Release what this call opened rather
            // than leaving a second microphone lit.
            s.release()
            return RecordingStart.Failure(RecordingFailure.AlreadyRecording)
        }
        s.thread = Thread({ pump(s) }, "oshi-voice-capture").apply {
            isDaemon = true
            start()
        }
        return RecordingStart.Started
    }

    // -------------------------------------------------------------------------- stop

    /**
     * Stop, finish the WAV, and judge what was captured.
     *
     * Order matters and is not arbitrary: EMPTY is checked before SILENT because a
     * zero-byte capture also has a zero peak and "the device produced nothing" is the
     * more useful sentence; SILENT is checked before TOO SHORT because a 150 ms recording
     * of pure zeros is a permission problem, not a mis-tap, and telling the user to hold
     * the button longer would send them chasing the wrong thing.
     */
    fun stop(): RecordingStop {
        val s = session.getAndSet(null) ?: return RecordingStop.Failure(RecordingFailure.NotRecording)
        s.finish()

        val bytes = s.bytes.get()
        s.writeError.get()?.let {
            s.raw.delete()
            return RecordingStop.Failure(RecordingFailure.WriteFailed(it))
        }
        if (bytes == 0L) {
            s.raw.delete()
            return RecordingStop.Failure(RecordingFailure.EmptyCapture(s.readError.get()))
        }
        val durationMs = VoiceNoteFormat.pcmDurationMs(bytes)
        if (s.peak.get() == 0) {
            s.raw.delete()
            return RecordingStop.Failure(RecordingFailure.SilentCapture(bytes, durationMs))
        }
        if (durationMs < minDurationMs) {
            s.raw.delete()
            return RecordingStop.Failure(RecordingFailure.TooShort(durationMs, minDurationMs))
        }

        val wav = File(workDir, "voice-${UUID.randomUUID()}.${VoiceNoteContainer.WAV_PCM.extension}")
        return try {
            WavWriter.write(s.raw, wav)
            s.raw.delete()
            RecordingStop.Captured(
                Recording(
                    file = wav,
                    pcmBytes = bytes,
                    durationMs = durationMs,
                    peakSample = s.peak.get(),
                    truncated = s.reachedLimit.get(),
                ),
            )
        } catch (t: Throwable) {
            s.raw.delete()
            wav.delete()
            RecordingStop.Failure(RecordingFailure.WriteFailed(describe(t)))
        }
    }

    /** Abandon the recording and delete everything. Safe when nothing is running. */
    fun cancel() {
        val s = session.getAndSet(null) ?: return
        s.finish()
        s.raw.delete()
    }

    // ------------------------------------------------------------------------- pump

    private fun pump(s: Session) {
        val buf = ByteArray(VoiceNoteFormat.CAPTURE_CHUNK_BYTES)
        while (s.running.get()) {
            val remaining = s.maxBytes - s.bytes.get()
            if (remaining <= 0L) {
                s.reachedLimit.set(true)
                break
            }
            // Always a whole number of frames: buf.size and maxBytes are both even, so
            // their minimum is, and a half-sample read would shift every later sample.
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val n = try {
                s.line.read(buf, 0, want)
            } catch (t: Throwable) {
                s.readError.set(describe(t))
                break
            }
            // A stopped or closed line returns 0 or -1; that is exactly how stop()
            // unblocks this read. Treating it as end-of-device is what keeps this loop
            // from spinning at 100 % CPU after the microphone goes away.
            if (n <= 0) break
            try {
                s.out.write(buf, 0, n)
            } catch (t: Throwable) {
                s.writeError.set(describe(t))
                break
            }
            s.bytes.addAndGet(n.toLong())
            val peak = Pcm.peakAbs16(buf, 0, n)
            if (peak > s.peak.get()) s.peak.set(peak)
        }
        s.running.set(false)
    }

    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: "no detail"}"

    private class Session(
        val raw: File,
        val out: OutputStream,
        val line: CaptureLine,
        val maxBytes: Long,
    ) {
        val bytes = AtomicLong(0)
        val peak = AtomicInteger(0)
        val running = AtomicBoolean(true)
        val reachedLimit = AtomicBoolean(false)
        val readError = AtomicReference<String?>(null)
        val writeError = AtomicReference<String?>(null)
        var thread: Thread? = null

        /** Stop the loop, unblock it by closing the device, then flush the file. */
        fun finish() {
            running.set(false)
            runCatching { line.close() }
            runCatching { thread?.join(JOIN_TIMEOUT_MS) }
            runCatching { out.flush() }
            runCatching { out.close() }
        }

        fun release() {
            running.set(false)
            runCatching { line.close() }
            runCatching { out.close() }
            raw.delete()
        }
    }

    companion object {
        /**
         * 2 s. The capture thread is blocked in one read of at most 100 ms of audio, so
         * it returns immediately once the line is closed. The timeout exists so a device
         * driver that never returns cannot wedge the UI thread that called `stop`.
         */
        const val JOIN_TIMEOUT_MS = 2_000L
    }
}

/** What [AudioRecorder.start] answers. */
sealed interface RecordingStart {
    object Started : RecordingStart
    data class Failure(val reason: RecordingFailure) : RecordingStart
}

/** What [AudioRecorder.stop] answers. */
sealed interface RecordingStop {
    data class Captured(val recording: Recording) : RecordingStop
    data class Failure(val reason: RecordingFailure) : RecordingStop
}

/**
 * A finished PCM WAV.
 *
 * @property durationMs derived from [pcmBytes], not from a clock.
 * @property peakSample the loudest absolute sample seen. Never 0 — a zero peak is
 *   [RecordingFailure.SilentCapture] and never becomes a [Recording].
 * @property truncated the 5-minute bound was reached and the recording was cut there.
 */
data class Recording(
    val file: File,
    val pcmBytes: Long,
    val durationMs: Long,
    val peakSample: Int,
    val truncated: Boolean,
)

/** Every way recording fails, each with the sentence a user should see. */
sealed interface RecordingFailure {

    /** One line, safe to show in a UI. */
    val message: String

    object NoInputDevice : RecordingFailure {
        override val message: String =
            "No microphone. This machine offers no audio input at 44.1 kHz mono."
    }

    data class DeviceUnavailable(val detail: String) : RecordingFailure {
        override val message: String =
            "The microphone could not be opened — it may be in use by another app. ($detail)"
    }

    data class PermissionDenied(val detail: String) : RecordingFailure {
        override val message: String = "Microphone access was refused. ($detail)"
    }

    object AlreadyRecording : RecordingFailure {
        override val message: String = "Already recording."
    }

    object NotRecording : RecordingFailure {
        override val message: String = "Nothing is being recorded."
    }

    /** The line opened and then ended without delivering a byte. */
    data class EmptyCapture(val detail: String?) : RecordingFailure {
        override val message: String =
            "The microphone delivered no audio." + (detail?.let { " ($it)" } ?: "")
    }

    /**
     * Bytes arrived and every one of them was zero.
     *
     * **On macOS this is what a denied microphone permission looks like** — there is no
     * exception to catch. The message names the fix rather than the symptom, because
     * "your recording was silent" sends the user to check their microphone volume, which
     * is not the problem.
     */
    data class SilentCapture(val bytes: Long, val durationMs: Long) : RecordingFailure {
        override val message: String =
            "Recorded $durationMs ms of pure silence — every sample was zero, which means " +
                "no signal reached the app. On macOS, grant Microphone access in " +
                "System Settings → Privacy & Security → Microphone; elsewhere, check that " +
                "the input device is not muted."
    }

    data class TooShort(val durationMs: Long, val minimumMs: Long) : RecordingFailure {
        override val message: String =
            "Too short: ${durationMs} ms, minimum ${minimumMs} ms. Hold to record."
    }

    data class WriteFailed(val detail: String) : RecordingFailure {
        override val message: String = "The recording could not be written to disk. ($detail)"
    }
}
