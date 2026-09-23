package com.oshi.desktop.media

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.UnsupportedAudioFileException

/**
 * Play one decrypted voice note. Never on the caller's thread.
 *
 * ============================================================ THE JDK CANNOT PLAY AN M4A
 *
 * Stated plainly because it is the thing that makes this file more than a wrapper:
 * `AudioSystem.getAudioInputStream` on an m4a throws `UnsupportedAudioFileException`
 * (measured, JDK 25, against a real afconvert encode). Its readers are WAVE, AU and AIFF.
 * **So the format both phones send is the one format the JVM cannot open.**
 *
 * The answer is the same subprocess that produces an m4a in the first place: an
 * unreadable container is decoded to a temp WAV by [AudioTranscoder] and played from
 * there. That keeps ONE playback engine — one thread, one `stop()`, one end callback —
 * instead of a native path plus an `afplay` path with different lifetimes.
 *
 * When no transcoder works, the failure is [PlaybackFailure.UnsupportedContainer], which
 * names the container and the tools that were tried. A note that cannot be played says so.
 *
 * ============================================================ NOT ON THE CALLER'S THREAD
 *
 * [play] opens the file, opens the line, and returns. All the writing happens on a daemon
 * thread. `onEnd` fires exactly once — on natural completion, on [stop], and on failure
 * after the thread has started — and it fires on that thread, so a UI must marshal it.
 *
 * One note plays at a time, which is what both phones do: iOS's `AudioMessageView`
 * stops the current player before starting another (`OSHI/AudioMessageView.swift:179-186`)
 * and Android's `AudioPlayerManager.play` calls `stop()` first
 * (`OSHI-Android/…/service/AudioRecorderManager.kt`, `AudioPlayerManager.play`). Starting a second note here
 * stops the first and delivers its [PlaybackEnd.Stopped].
 *
 * ============================================================ WHAT IS NOT VERIFIED
 *
 * No sound has been produced by this class on any machine. The decode fallback, the
 * single-playback rule, the stop path and the end callback are exercised against a fake
 * render device that counts bytes. **A speaker has never been opened by a test.**
 */
class AudioPlayer(
    private val devices: AudioDevices = AudioDevices.JavaSound,
    private val transcoder: AudioTranscoder = SystemAudioTranscoder(),
    private val workDir: File = com.oshi.desktop.store.DesktopPaths.file(com.oshi.desktop.store.MediaVault.SCRATCH_DIR_NAME),
) {

    private val current = AtomicReference<Session?>(null)

    val isPlaying: Boolean get() = current.get()?.running?.get() == true

    /**
     * Start playing [file].
     *
     * @param onEnd called exactly once, on the playback thread, when the note finishes,
     *   is stopped, or fails mid-stream. Not called when [play] itself returns a failure —
     *   that failure IS the answer.
     */
    fun play(file: File, onEnd: (PlaybackEnd) -> Unit = {}): PlaybackStart {
        stop()

        if (!file.isFile) return PlaybackStart.Failure(PlaybackFailure.FileMissing(file.path))
        if (file.length() == 0L) return PlaybackStart.Failure(PlaybackFailure.FileEmpty(file.path))

        // __LOCAL_DATA_AT_REST_2026_09_22__ A sealed attachment ("OSHIMED1") is decrypted to a
        // scratch file for the JDK/transcoder, which only take paths; that copy is deleted
        // when the session ends (or on refusal below), and swept at the next start regardless.
        if (!com.oshi.desktop.store.MediaVault.isSealed(file)) return playPlain(file, null, onEnd)
        val vault = com.oshi.desktop.store.MediaVault.current()
            ?: return PlaybackStart.Failure(PlaybackFailure.Undecryptable(file.path, "no media key is loaded"))
        val plain = try {
            vault.decryptToScratch(file)
        } catch (t: Throwable) {
            return PlaybackStart.Failure(PlaybackFailure.Undecryptable(file.path, describe(t)))
        }
        val started = playPlain(plain, plain, onEnd)
        if (started !is PlaybackStart.Started) plain.delete()
        return started
    }

    private fun playPlain(file: File, decryptedCopy: File?, onEnd: (PlaybackEnd) -> Unit): PlaybackStart {

        val opened = open(file)
        val stream = when (opened) {
            is Opened.Ready -> opened.stream
            is Opened.Refused -> return PlaybackStart.Failure(opened.reason)
        }

        val outFormat = stream.format
        if (!devices.supportsRender(outFormat)) {
            runCatching { stream.close() }
            opened.temp?.delete()
            return PlaybackStart.Failure(PlaybackFailure.NoOutputDevice(outFormat.toString()))
        }

        val line = try {
            devices.openRender(outFormat, RENDER_BUFFER_BYTES)
        } catch (e: LineUnavailableException) {
            runCatching { stream.close() }
            opened.temp?.delete()
            return PlaybackStart.Failure(PlaybackFailure.DeviceUnavailable(describe(e)))
        } catch (t: Throwable) {
            runCatching { stream.close() }
            opened.temp?.delete()
            return PlaybackStart.Failure(PlaybackFailure.DeviceUnavailable(describe(t)))
        }

        val session = Session(stream, line, opened.temp, onEnd, decryptedCopy)
        if (!current.compareAndSet(null, session)) {
            session.release()
            return PlaybackStart.Failure(PlaybackFailure.Busy)
        }
        session.thread = Thread({ pump(session) }, "oshi-voice-playback").apply {
            isDaemon = true
            start()
        }
        return PlaybackStart.Started(
            Handle(session.running, session.stopRequested) { session.unblock() },
        )
    }

    /** Stop whatever is playing. Idempotent; a no-op when nothing is. */
    fun stop() {
        current.getAndSet(null)?.let { it.stopRequested.set(true); it.unblock() }
    }

    // -------------------------------------------------------------------- decoding

    private sealed class Opened(val temp: File?) {
        class Ready(val stream: AudioInputStream, temp: File?) : Opened(temp)
        class Refused(val reason: PlaybackFailure) : Opened(null)
    }

    /**
     * Get a PCM stream out of [file], transcoding first if the JDK refuses it.
     *
     * The conversion to signed 16-bit little-endian is deliberate rather than playing the
     * file's own format: an AIFF is big-endian, and a mixer that does not offer a
     * big-endian line would refuse a file the JDK read perfectly well.
     * `isConversionSupported` is asked rather than assumed, and the original format is
     * used when no converter exists.
     */
    private fun open(file: File): Opened {
        nativeStream(file)?.let { return Opened.Ready(toPlayablePcm(it), null) }

        val container = AudioContainers.of(file)
        val temp = File(workDir, "decode-${UUID.randomUUID()}.wav")
        return when (val result = transcoder.toWav(file, temp)) {
            is TranscodeResult.Produced -> {
                val decoded = nativeStream(temp)
                if (decoded == null) {
                    temp.delete()
                    Opened.Refused(
                        PlaybackFailure.UnsupportedContainer(
                            container?.name ?: "unknown",
                            "${result.tool} produced a WAV the JDK still refused",
                        ),
                    )
                } else {
                    Opened.Ready(toPlayablePcm(decoded), temp)
                }
            }
            is TranscodeResult.Unavailable ->
                Opened.Refused(
                    PlaybackFailure.UnsupportedContainer(container?.name ?: "unknown", result.message),
                )
            is TranscodeResult.Failed ->
                Opened.Refused(
                    PlaybackFailure.UnsupportedContainer(
                        container?.name ?: "unknown",
                        "${result.tool}: ${result.reason}",
                    ),
                )
        }
    }

    private fun nativeStream(file: File): AudioInputStream? = try {
        AudioSystem.getAudioInputStream(file)
    } catch (_: UnsupportedAudioFileException) {
        null
    } catch (_: Throwable) {
        null
    }

    private fun toPlayablePcm(stream: AudioInputStream): AudioInputStream {
        val source = stream.format
        if (source.encoding == AudioFormat.Encoding.PCM_SIGNED &&
            source.sampleSizeInBits == 16 && !source.isBigEndian
        ) {
            return stream
        }
        val target = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            source.sampleRate,
            16,
            source.channels,
            2 * source.channels,
            source.sampleRate,
            false,
        )
        return if (AudioSystem.isConversionSupported(target, source)) {
            AudioSystem.getAudioInputStream(target, stream)
        } else {
            stream
        }
    }

    // ------------------------------------------------------------------------ pump

    private fun pump(s: Session) {
        val buf = ByteArray(VoiceNoteFormat.CAPTURE_CHUNK_BYTES)
        var end: PlaybackEnd = PlaybackEnd.Completed
        try {
            while (s.running.get() && !s.stopRequested.get()) {
                val n = s.stream.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                s.line.write(buf, 0, n)
            }
            if (s.stopRequested.get()) {
                end = PlaybackEnd.Stopped
                s.line.flush()
            } else {
                // Only drain on a natural finish: draining a stopped line waits for audio
                // the user has already asked not to hear.
                s.line.drain()
            }
        } catch (t: Throwable) {
            end = PlaybackEnd.Failed(describe(t))
        } finally {
            s.running.set(false)
            current.compareAndSet(s, null)
            s.release()
            runCatching { s.onEnd(end) }
        }
    }

    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: "no detail"}"

    private class Session(
        val stream: AudioInputStream,
        val line: RenderLine,
        val temp: File?,
        val onEnd: (PlaybackEnd) -> Unit,
        val decryptedCopy: File? = null,
    ) {
        val running = AtomicBoolean(true)
        val stopRequested = AtomicBoolean(false)
        var thread: Thread? = null

        /** Unblock a `write` that is waiting on a full device buffer. */
        fun unblock() { runCatching { line.flush() } }

        fun release() {
            runCatching { line.close() }
            runCatching { stream.close() }
            temp?.let { runCatching { it.delete() } }
            decryptedCopy?.let { runCatching { it.delete() } }
        }
    }

    /**
     * A running playback. Holding one is how a UI stops the note it started, without
     * racing another bubble that may have started its own note since.
     *
     * It carries the two flags rather than the session, so nothing private leaks into a
     * public type.
     */
    class Handle internal constructor(
        private val running: java.util.concurrent.atomic.AtomicBoolean,
        private val stopRequested: java.util.concurrent.atomic.AtomicBoolean,
        private val unblock: () -> Unit,
    ) {
        val isPlaying: Boolean get() = running.get()

        fun stop() {
            stopRequested.set(true)
            unblock()
        }
    }

    companion object {
        /** ~400 ms of 44.1 kHz mono. Big enough to survive a GC pause mid-note. */
        const val RENDER_BUFFER_BYTES = VoiceNoteFormat.CAPTURE_CHUNK_BYTES * 4
    }
}

sealed interface PlaybackStart {
    data class Started(val handle: AudioPlayer.Handle) : PlaybackStart
    data class Failure(val reason: PlaybackFailure) : PlaybackStart
}

/** How a playback ended. Delivered once, on the playback thread. */
sealed interface PlaybackEnd {
    object Completed : PlaybackEnd
    object Stopped : PlaybackEnd
    data class Failed(val detail: String) : PlaybackEnd
}

sealed interface PlaybackFailure {
    val message: String

    data class FileMissing(val path: String) : PlaybackFailure {
        override val message: String = "No such audio file: $path"
    }

    data class FileEmpty(val path: String) : PlaybackFailure {
        override val message: String = "That audio file is empty: $path"
    }

    /**
     * The JDK cannot read it and no transcoder could convert it. **The expected case for
     * an inbound phone voice note on a machine with no `afconvert` and no `ffmpeg`** —
     * i.e. a stock Linux or Windows desktop.
     */
    data class UnsupportedContainer(val container: String, val detail: String) : PlaybackFailure {
        override val message: String =
            "Cannot play this $container voice note: the JVM has no AAC decoder and no " +
                "external one is usable. ($detail)"
    }

    data class NoOutputDevice(val format: String) : PlaybackFailure {
        override val message: String = "No speaker able to play $format."
    }

    data class DeviceUnavailable(val detail: String) : PlaybackFailure {
        override val message: String = "The speaker could not be opened. ($detail)"
    }

    /** A sealed attachment that could not be decrypted: wrong key, tampered or truncated. */
    data class Undecryptable(val path: String, val detail: String) : PlaybackFailure {
        override val message: String = "This voice note could not be decrypted from local storage ($detail): $path"
    }

    object Busy : PlaybackFailure {
        override val message: String = "Another voice note started playing first."
    }
}
