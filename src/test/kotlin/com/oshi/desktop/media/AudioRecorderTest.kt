package com.oshi.desktop.media

import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Every way recording fails, exercised against [FakeDevices].
 *
 * **No test here opens a microphone**, and the one that matters most —
 * `a device that returns nothing but zeros is a failure` — could not be written any other
 * way: a machine WITH a working microphone cannot produce that condition on demand.
 */
class AudioRecorderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun recorder(
        devices: FakeDevices,
        maxBytes: Long = VoiceNoteFormat.MAX_CAPTURE_BYTES,
        minMs: Long = VoiceNoteFormat.MIN_DURATION_MS,
    ) = AudioRecorder(
        devices = devices,
        workDir = tmp.newFolder(),
        maxCaptureBytes = maxBytes,
        minDurationMs = minMs,
    )

    private fun seconds(n: Double): Long = (n * VoiceNoteFormat.BYTES_PER_SECOND).toLong() and 1L.inv()

    // ============================================================ the absence of a device

    /**
     * **The tested behaviour is the ABSENCE.** A headless build server, a container with
     * no `/dev/snd`, a Mac whose only input is exclusive-mode: all of them must produce a
     * named failure here, and none of them may produce a file.
     */
    @Test
    fun `a machine with no input device fails by name and writes nothing`() {
        val dir = tmp.newFolder()
        val rec = AudioRecorder(devices = FakeDevices(captureSupported = false), workDir = dir)

        assertFalse(rec.isAvailable())
        val start = rec.start()
        assertTrue(start is RecordingStart.Failure)
        assertEquals(RecordingFailure.NoInputDevice, (start as RecordingStart.Failure).reason)
        assertFalse(rec.isRecording)
        // Nothing on disk. Not an empty WAV, not a zero-byte temp: nothing.
        assertTrue(dir.listFiles().orEmpty().toList().toString(), dir.listFiles().orEmpty().isEmpty())
    }

    /** A device held by another process. `LineUnavailableException` is the JDK's word for it. */
    @Test
    fun `a busy device is reported as unavailable with the driver's own detail`() {
        val devices = FakeDevices(openCaptureThrows = LineUnavailableException("line with format ... not supported"))
        val start = recorder(devices).start()
        val reason = (start as RecordingStart.Failure).reason
        assertTrue(reason is RecordingFailure.DeviceUnavailable)
        assertTrue(reason.message, reason.message.contains("not supported"))
    }

    /** A `SecurityManager` refusal is a different sentence from a busy device. */
    @Test
    fun `a security refusal is reported as permission denied, not as a busy device`() {
        val devices = FakeDevices(openCaptureThrows = SecurityException("no line permission"))
        val start = recorder(devices).start()
        val reason = (start as RecordingStart.Failure).reason
        assertTrue(reason is RecordingFailure.PermissionDenied)
    }

    // ==================================================================== the state machine

    @Test
    fun `stop before start is a named failure, not an exception and not an empty note`() {
        val stop = recorder(FakeDevices()).stop()
        assertEquals(RecordingFailure.NotRecording, (stop as RecordingStop.Failure).reason)
    }

    @Test
    fun `cancel before start is a no-op`() {
        recorder(FakeDevices()).cancel() // must not throw
    }

    @Test
    fun `start twice is refused and does not open a second microphone`() {
        val devices = FakeDevices(captureLine = { FakeCaptureLine(totalBytes = null) })
        val rec = recorder(devices)
        assertTrue(rec.start() is RecordingStart.Started)
        val second = rec.start()
        assertEquals(RecordingFailure.AlreadyRecording, (second as RecordingStart.Failure).reason)
        assertEquals(1, devices.openedCaptures.size)
        rec.cancel()
    }

    @Test
    fun `cancel releases the device and leaves no file behind`() {
        val dir = tmp.newFolder()
        val devices = FakeDevices(captureLine = { FakeCaptureLine(totalBytes = null) })
        val rec = AudioRecorder(devices = devices, workDir = dir)
        rec.start()
        rec.cancel()
        assertFalse(rec.isRecording)
        assertTrue((devices.openedCaptures[0] as FakeCaptureLine).closed.get())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    // ========================================================= THE SILENT-CAPTURE GUARD

    /**
     * **The failure this whole class exists for.**
     *
     * On macOS a process without the Microphone TCC grant opens a line, reads from it at
     * the right rate, and gets zeros forever. No exception, correct byte counts, correct
     * duration, a perfectly valid WAV — of silence. Length checks cannot see it. Only the
     * sample values can.
     */
    @Test
    fun `a device that returns nothing but zeros is a failure and not a voice note`() {
        val dir = tmp.newFolder()
        val devices = FakeDevices(
            captureLine = { FakeCaptureLine(totalBytes = seconds(2.0), sampleValue = 0) },
        )
        val rec = AudioRecorder(devices = devices, workDir = dir)
        rec.start()
        Thread.sleep(80)
        val stop = rec.stop()

        val reason = (stop as RecordingStop.Failure).reason
        assertTrue(reason.toString(), reason is RecordingFailure.SilentCapture)
        // The message must send the user to the permission, not to their volume slider.
        assertTrue(reason.message, reason.message.contains("Microphone"))
        // And crucially: no file was left for a UI to send anyway.
        assertTrue(dir.listFiles().orEmpty().toList().toString(), dir.listFiles().orEmpty().isEmpty())
    }

    /** A device that opens and immediately ends is EMPTY, which is a different sentence. */
    @Test
    fun `a device that delivers no bytes at all is empty, not silent`() {
        val devices = FakeDevices(captureLine = { FakeCaptureLine(totalBytes = 0L) })
        val rec = recorder(devices)
        rec.start()
        Thread.sleep(50)
        val reason = (rec.stop() as RecordingStop.Failure).reason
        assertTrue(reason.toString(), reason is RecordingFailure.EmptyCapture)
    }

    /** A read that throws mid-capture is carried into the failure, not swallowed. */
    @Test
    fun `a device that throws mid-read reports the detail`() {
        val devices = FakeDevices(
            captureLine = { FakeCaptureLine(totalBytes = null, throwOnRead = IllegalStateException("device yanked")) },
        )
        val rec = recorder(devices)
        rec.start()
        Thread.sleep(50)
        val reason = (rec.stop() as RecordingStop.Failure).reason
        assertTrue(reason is RecordingFailure.EmptyCapture)
        assertTrue(reason.message, reason.message.contains("device yanked"))
    }

    // ================================================================= a good recording

    @Test
    fun `a normal recording produces a wav the JDK itself can read back`() {
        val devices = FakeDevices(captureLine = { FakeCaptureLine(totalBytes = seconds(1.0)) })
        val rec = recorder(devices)
        rec.start()
        Thread.sleep(100)
        val captured = rec.stop()
        assertTrue(captured.toString(), captured is RecordingStop.Captured)
        val recording = (captured as RecordingStop.Captured).recording

        assertEquals(seconds(1.0), recording.pcmBytes)
        assertEquals(1_000L, recording.durationMs)
        assertFalse(recording.truncated)
        assertTrue(recording.peakSample > 0)

        // The header is not merely "a header we like": the JDK's own reader accepts it,
        // at the format we claim, with the frame count we claim.
        val stream = AudioSystem.getAudioInputStream(recording.file)
        assertEquals(VoiceNoteFormat.SAMPLE_RATE.toFloat(), stream.format.sampleRate, 0.001f)
        assertEquals(VoiceNoteFormat.CHANNELS, stream.format.channels)
        assertEquals(VoiceNoteFormat.SAMPLE_BITS, stream.format.sampleSizeInBits)
        assertFalse(stream.format.isBigEndian)
        assertEquals(VoiceNoteFormat.SAMPLE_RATE.toLong(), stream.frameLength)
        stream.close()

        // And the probe agrees with the recorder about how long it is.
        assertEquals(recording.durationMs, AudioDurationProbe.of(recording.file)!!.durationMs)
    }

    /** Duration comes from BYTES. A test that trusted a clock would drift under load. */
    @Test
    fun `duration is derived from bytes captured and not from elapsed time`() {
        val devices = FakeDevices(captureLine = { FakeCaptureLine(totalBytes = seconds(0.5)) })
        val rec = recorder(devices)
        rec.start()
        // Deliberately wait much longer than the audio is. The answer must not move.
        Thread.sleep(400)
        val recording = (rec.stop() as RecordingStop.Captured).recording
        assertEquals(500L, recording.durationMs)
    }

    @Test
    fun `a recording shorter than the floor is refused and its file removed`() {
        val dir = tmp.newFolder()
        val devices = FakeDevices(captureLine = { FakeCaptureLine(totalBytes = seconds(0.05)) })
        val rec = AudioRecorder(devices = devices, workDir = dir, minDurationMs = 200L)
        rec.start()
        Thread.sleep(50)
        val reason = (rec.stop() as RecordingStop.Failure).reason
        assertTrue(reason is RecordingFailure.TooShort)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    // ========================================================================= the bound

    /**
     * The 5-minute bound, exercised at a scaled-down ceiling because it is counted in
     * BYTES — an endless device is stopped by the byte ceiling, not by a timer, so the
     * test costs milliseconds and the production number is the same arithmetic.
     */
    @Test
    fun `an endless device is cut at the byte ceiling and the note says it was truncated`() {
        val ceiling = seconds(0.4)
        val devices = FakeDevices(captureLine = { FakeCaptureLine(totalBytes = null) })
        val rec = recorder(devices, maxBytes = ceiling)
        rec.start()
        Thread.sleep(200)
        val recording = (rec.stop() as RecordingStop.Captured).recording

        assertEquals(ceiling, recording.pcmBytes)
        assertTrue(recording.truncated)
        assertEquals(400L, recording.durationMs)
        // The device was released, not left lit after the ceiling was hit.
        assertTrue((devices.openedCaptures[0] as FakeCaptureLine).closed.get())
    }

    @Test
    fun `the shipped ceiling is five minutes of audio and nothing else`() {
        assertEquals(300_000L, VoiceNoteFormat.MAX_DURATION_MS)
        assertEquals(
            VoiceNoteFormat.MAX_DURATION_MS,
            VoiceNoteFormat.pcmDurationMs(VoiceNoteFormat.MAX_CAPTURE_BYTES),
        )
        // 26.5 MB — comfortably under the blob ceiling, so the bound is about the
        // recording and not about the uplink.
        assertTrue(VoiceNoteFormat.MAX_CAPTURE_BYTES < 30L * 1024 * 1024)
    }

    // ===================================================================== PCM arithmetic

    @Test
    fun `peak reads little-endian signed samples and never overflows at minus full scale`() {
        // 0x8000 = -32768. Naive negation returns -32768 again, which is negative and
        // would leave the peak at 0 — i.e. a full-scale recording read as silence.
        assertEquals(32767, Pcm.peakAbs16(byteArrayOf(0x00, 0x80.toByte()), 0, 2))
        assertEquals(32767, Pcm.peakAbs16(byteArrayOf(0xFF.toByte(), 0x7F), 0, 2))
        assertEquals(0, Pcm.peakAbs16(byteArrayOf(0, 0, 0, 0), 0, 4))
        // Big-endian bytes for +1 would read as 256 if the order were wrong.
        assertEquals(1, Pcm.peakAbs16(byteArrayOf(0x01, 0x00), 0, 2))
    }

    @Test
    fun `peak ignores a trailing half sample instead of inventing one`() {
        // Three bytes: one whole sample of 0, then half of something loud.
        assertEquals(0, Pcm.peakAbs16(byteArrayOf(0, 0, 0x7F), 0, 3))
    }

    @Test
    fun `peak never reads past the buffer`() {
        assertEquals(0, Pcm.peakAbs16(ByteArray(4), 0, 1_000))
        assertEquals(0, Pcm.peakAbs16(ByteArray(4), 8, 4))
    }

    // ======================================================================= wav header

    @Test
    fun `the wav header states the byte rate a receiver divides by`() {
        val h = WavWriter.header(VoiceNoteFormat.BYTES_PER_SECOND.toLong())
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVE", String(h, 8, 4))
        assertEquals("fmt ", String(h, 12, 4))
        assertEquals("data", String(h, 36, 4))
        assertEquals(1, le16(h, 20)) // WAVE_FORMAT_PCM
        assertEquals(VoiceNoteFormat.CHANNELS, le16(h, 22))
        assertEquals(VoiceNoteFormat.SAMPLE_RATE, le32(h, 24))
        // A wrong byte rate here makes every duration in the UI wrong while the audio
        // still plays perfectly — the failure with no symptom.
        assertEquals(VoiceNoteFormat.BYTES_PER_SECOND, le32(h, 28))
        assertEquals(VoiceNoteFormat.SAMPLE_BITS, le16(h, 34))
        assertEquals(VoiceNoteFormat.BYTES_PER_SECOND, le32(h, 40))
        assertEquals(36 + VoiceNoteFormat.BYTES_PER_SECOND, le32(h, 4))
    }

    @Test
    fun `the probe tone is a wav the JDK reads and is not silence`() {
        val f = File(tmp.newFolder(), "tone.wav")
        f.writeBytes(WavWriter.tone(millis = 100))
        assertNotNull(AudioSystem.getAudioInputStream(f).also { it.close() })
        assertEquals(100L, AudioDurationProbe.of(f)!!.durationMs)
        val pcm = f.readBytes()
        assertTrue(Pcm.peakAbs16(pcm, WavWriter.HEADER_BYTES, pcm.size - WavWriter.HEADER_BYTES) > 1000)
    }

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
