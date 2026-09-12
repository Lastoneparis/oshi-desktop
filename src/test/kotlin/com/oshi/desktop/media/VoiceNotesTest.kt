package com.oshi.desktop.media

import com.oshi.desktop.store.MediaType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The seam another agent wires into `OshiClient` — and specifically the honesty of it.
 *
 * The tests that matter are the ones about [VoiceNote.phoneCompatible]: a WAV note is
 * still a note, it is still returned, and it CANNOT come back without a warning attached.
 */
class VoiceNotesTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun seam(
        devices: FakeDevices = FakeDevices(
            captureLine = { FakeCaptureLine(totalBytes = VoiceNoteFormat.BYTES_PER_SECOND.toLong()) },
        ),
        transcoder: AudioTranscoder = FakeTranscoder(),
        dir: File = tmp.newFolder(),
    ) = VoiceNotes(
        workDir = dir,
        devices = devices,
        transcoder = transcoder,
        recorder = AudioRecorder(devices = devices, workDir = dir),
        player = AudioPlayer(devices = devices, transcoder = transcoder, workDir = dir),
    )

    private fun record(v: VoiceNotes): VoiceNoteResult {
        assertTrue(v.startRecording() is RecordingStart.Started)
        Thread.sleep(100)
        return v.stopRecording()
    }

    // ===================================================================== capability

    @Test
    fun `capability names the blocker when there is no microphone`() {
        val cap = seam(devices = FakeDevices(captureSupported = false)).capability()
        assertFalse(cap.canRecord)
        assertEquals(RecordingFailure.NoInputDevice, cap.recordingBlocker)
    }

    /**
     * A desktop with no AAC encoder must be able to say so BEFORE the user records,
     * because that is the only moment where the answer is still useful.
     */
    @Test
    fun `capability warns about the format before a note is ever recorded`() {
        val cap = seam(transcoder = FakeTranscoder.unavailable()).capability()
        assertFalse(cap.producesPhoneCompatibleAudio)
        assertNull(cap.encoder)
        assertEquals(VoiceNoteContainer.WAV_PCM, cap.container)
        assertNotNull(cap.formatWarning)
        assertTrue(cap.formatWarning!!, cap.formatWarning!!.contains("m4a"))
        assertTrue(cap.formatWarning!!, cap.formatWarning!!.contains("may not play"))
    }

    @Test
    fun `capability is quiet when an encoder works`() {
        val cap = seam(transcoder = FakeTranscoder(probeName = "afconvert")).capability()
        assertTrue(cap.producesPhoneCompatibleAudio)
        assertEquals("afconvert", cap.encoder)
        assertEquals(VoiceNoteContainer.M4A_AAC, cap.container)
        assertNull(cap.formatWarning)
    }

    // ================================================================== the m4a path

    @Test
    fun `a successful encode yields an m4a note ready for sendFile`() {
        val dir = tmp.newFolder()
        val transcoder = FakeTranscoder(
            onM4a = { _, target ->
                target.writeBytes(AudioFixtures.REAL_M4A_HEADER)
                TranscodeResult.Produced(target, "afconvert")
            },
        )
        val v = seam(transcoder = transcoder, dir = dir)
        val note = (record(v) as VoiceNoteResult.Ready).note

        assertEquals(VoiceNoteContainer.M4A_AAC, note.container)
        assertTrue(note.phoneCompatible)
        assertNull(note.warning)
        assertEquals("afconvert", note.encoder)
        assertEquals("audio/m4a", note.mime)
        assertEquals("audio.m4a", note.suggestedFilename)
        // The one field a caller must pass to sendFile. Deriving it from the MIME works
        // on a desktop with a MIME database and silently degrades to DOCUMENT without one.
        assertEquals(MediaType.AUDIO, note.mediaType)
        assertTrue(note.file.isFile)
        assertTrue(note.file.name.endsWith(".m4a"))

        // The duration is the CONTAINER's, which is the number a phone will show.
        assertEquals(AudioFixtures.REAL_M4A_DURATION_MS, note.durationMs)

        // The intermediate WAV is gone: it is plaintext audio and has no reason to stay.
        assertTrue(
            dir.listFiles().orEmpty().joinToString { it.name },
            dir.listFiles().orEmpty().none { it.name.endsWith(".wav") },
        )
    }

    // ================================================================== the wav path

    /**
     * **A failed encode must not throw away audio the user already spoke** — but it also
     * must not pretend the result is what a phone records.
     */
    @Test
    fun `a failed encode still returns the recording, carrying a warning`() {
        val v = seam(transcoder = FakeTranscoder.unavailable())
        val note = (record(v) as VoiceNoteResult.Ready).note

        assertEquals(VoiceNoteContainer.WAV_PCM, note.container)
        assertTrue(note.file.isFile)
        assertTrue(note.file.length() > WavWriter.HEADER_BYTES)
        assertEquals(1_000L, note.durationMs)

        assertFalse(note.phoneCompatible)
        assertNotNull("a WAV note without a warning is the whole failure mode", note.warning)
        assertTrue(note.warning!!, note.warning!!.contains("m4a"))
        assertTrue(note.warning!!, note.warning!!.contains("may not play"))
        // And it says WHY there is no m4a, so a support answer is possible.
        assertTrue(note.warning!!, note.warning!!.contains("not on PATH"))
        // Still an audio message on the wire, not a document.
        assertEquals(MediaType.AUDIO, note.mediaType)
    }

    /** Only the container both phones record may claim compatibility. */
    @Test
    fun `phone compatibility is exactly the m4a container and nothing else`() {
        assertTrue(VoiceNoteContainer.M4A_AAC.matchesPhoneRecorder)
        assertFalse(VoiceNoteContainer.WAV_PCM.matchesPhoneRecorder)
        for (c in VoiceNoteContainer.entries) {
            assertEquals("audio.${c.extension}", c.defaultFilename)
            assertTrue(c.mime, c.mime.startsWith("audio/"))
            // Both receivers classify on the `audio/` prefix alone.
            assertEquals(MediaType.AUDIO, MediaType.forMime(c.mime))
        }
    }

    // ======================================================================= failures

    @Test
    fun `a recording failure comes through the seam unchanged`() {
        val v = seam(devices = FakeDevices(captureSupported = false))
        val result = v.startRecording()
        assertEquals(RecordingFailure.NoInputDevice, (result as RecordingStart.Failure).reason)
        assertEquals(RecordingFailure.NotRecording, (v.stopRecording() as VoiceNoteResult.Failure).reason)
    }

    @Test
    fun `a silent device produces a failure through the seam and no note at all`() {
        val dir = tmp.newFolder()
        val devices = FakeDevices(
            captureLine = {
                FakeCaptureLine(totalBytes = VoiceNoteFormat.BYTES_PER_SECOND.toLong(), sampleValue = 0)
            },
        )
        val v = seam(devices = devices, dir = dir)
        val result = record(v)
        assertTrue(result.toString(), (result as VoiceNoteResult.Failure).reason is RecordingFailure.SilentCapture)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `discard removes the file`() {
        val v = seam(transcoder = FakeTranscoder.unavailable())
        val note = (record(v) as VoiceNoteResult.Ready).note
        v.discard(note)
        assertFalse(note.file.exists())
    }

    // ======================================================================== duration

    @Test
    fun `durationMs reads a header and formatDuration prints the phones' shape`() {
        val v = seam()
        val f = tmp.newFile("probe.wav")
        f.writeBytes(WavWriter.tone(millis = 7_000))
        assertEquals(7_000L, v.durationMs(f))
        assertEquals(VoiceNoteContainer.WAV_PCM, v.probe(f)!!.container)
        assertNull(v.durationMs(tmp.newFile("not-audio.bin").also { it.writeBytes(ByteArray(64)) }))

        assertEquals("0:07", v.formatDuration(7_000))
        assertEquals("0:00", v.formatDuration(0))
        assertEquals("1:05", v.formatDuration(65_400))
        assertEquals("10:00", v.formatDuration(600_000))
        assertEquals("0:00", v.formatDuration(-5))
    }
}
