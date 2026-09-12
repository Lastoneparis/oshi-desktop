package com.oshi.desktop.media

import java.io.Closeable
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * The one place this package touches audio hardware — so that everything above it can be
 * tested on a machine with no microphone and no speaker.
 *
 * [com.oshi.desktop.call.media.CallAudioSession] talks to `javax.sound.sampled` directly
 * and pays for it: its own doc says "opening a real microphone is NOT tested and cannot
 * be". That is acceptable for a call, where a device failure ends the call loudly. It is
 * NOT acceptable here, because the failure this package must never commit — writing a
 * silent or empty file and calling it a voice note — is only reachable THROUGH the
 * device. So the device is an interface, the real one is thirty lines at the bottom of
 * this file, and every failure mode above it is exercised against a fake.
 */
interface AudioDevices {

    /** Can this machine capture [format]? Asks the mixer; opens nothing. */
    fun supportsCapture(format: AudioFormat): Boolean

    /** Can this machine render [format]? Asks the mixer; opens nothing. */
    fun supportsRender(format: AudioFormat): Boolean

    /** Names of mixers offering a capture line in [format]. Diagnostics for a CLI/UI. */
    fun captureDeviceNames(format: AudioFormat): List<String>

    /**
     * Open a microphone.
     *
     * @throws LineUnavailableException when the device exists but cannot be opened —
     *   another process holds it exclusively, or the OS refused. **On macOS a DENIED
     *   microphone permission does not usually land here**; see [AudioRecorder] for what
     *   does happen and how it is caught.
     * @throws SecurityException when a security manager forbids the line outright.
     */
    @Throws(LineUnavailableException::class)
    fun openCapture(format: AudioFormat, bufferBytes: Int): CaptureLine

    /** Open a speaker. Same failure contract as [openCapture]. */
    @Throws(LineUnavailableException::class)
    fun openRender(format: AudioFormat, bufferBytes: Int): RenderLine

    companion object {
        /** The real devices. */
        val JavaSound: AudioDevices get() = JavaSoundDevices
    }
}

/** A microphone that is open. */
interface CaptureLine : Closeable {
    /**
     * Blocking read of at most [length] bytes.
     *
     * Returns the number of bytes read. **Returns 0 or -1 when the line has been stopped
     * or closed** — which is exactly how [AudioRecorder.stop] unblocks this call, so the
     * capture loop treats any non-positive return as "the device is done" and exits
     * rather than spinning. A `TargetDataLine` only returns 0 on a stopped/flushed line,
     * so this loses no real audio.
     */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    override fun close()
}

/** A speaker that is open. */
interface RenderLine : Closeable {
    /** Blocking write. Returns bytes accepted. */
    fun write(buffer: ByteArray, offset: Int, length: Int): Int

    /** Block until everything written has been played. */
    fun drain()

    /** Discard anything queued — used when playback is stopped early. */
    fun flush()

    override fun close()
}

// ---------------------------------------------------------------- the real devices

internal object JavaSoundDevices : AudioDevices {

    override fun supportsCapture(format: AudioFormat): Boolean =
        runCatching {
            AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, format))
        }.getOrDefault(false)

    override fun supportsRender(format: AudioFormat): Boolean =
        runCatching {
            AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, format))
        }.getOrDefault(false)

    override fun captureDeviceNames(format: AudioFormat): List<String> =
        runCatching {
            AudioSystem.getMixerInfo().filter { info ->
                runCatching {
                    AudioSystem.getMixer(info)
                        .isLineSupported(DataLine.Info(TargetDataLine::class.java, format))
                }.getOrDefault(false)
            }.map { it.name }
        }.getOrDefault(emptyList())

    override fun openCapture(format: AudioFormat, bufferBytes: Int): CaptureLine {
        val line = AudioSystem.getLine(
            DataLine.Info(TargetDataLine::class.java, format),
        ) as TargetDataLine
        line.open(format, bufferBytes)
        line.start()
        return TargetLine(line)
    }

    override fun openRender(format: AudioFormat, bufferBytes: Int): RenderLine {
        val line = AudioSystem.getLine(
            DataLine.Info(SourceDataLine::class.java, format),
        ) as SourceDataLine
        line.open(format, bufferBytes)
        line.start()
        return SourceLine(line)
    }

    /**
     * `stop()` → `flush()` → `close()`, in that order, for the reason spelled out in
     * `CallAudio`'s doc: `close()` alone can block on a line with queued data, and
     * flushing before stopping races the mixer thread.
     */
    private class TargetLine(private val line: TargetDataLine) : CaptureLine {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            line.read(buffer, offset, length)

        override fun close() {
            runCatching { line.stop() }
            runCatching { line.flush() }
            runCatching { line.close() }
        }
    }

    private class SourceLine(private val line: SourceDataLine) : RenderLine {
        override fun write(buffer: ByteArray, offset: Int, length: Int): Int =
            line.write(buffer, offset, length)

        override fun drain() { runCatching { line.drain() } }

        override fun flush() { runCatching { line.flush() } }

        override fun close() {
            runCatching { line.stop() }
            runCatching { line.flush() }
            runCatching { line.close() }
        }
    }
}
