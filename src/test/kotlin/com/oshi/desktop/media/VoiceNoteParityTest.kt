package com.oshi.desktop.media

import com.oshi.desktop.store.MediaType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The claims in [VoiceNoteFormat]'s doc, re-derived from the SHIPPED Swift and Kotlin on
 * every run.
 *
 * A comment saying "the phones record 44 100 Hz AAC" is worth nothing the day someone
 * changes the phone. These assertions go red instead. They match on CONTENT and never on
 * line numbers — the numbers in the prose above drift and are only navigation aids; three
 * of them had already moved by 700 lines while this package was being written.
 *
 * Skipped where the phone trees are not checked out, in the same shape as
 * `StringsFileTest` and `MeshWireFormatTest`.
 */
class VoiceNoteParityTest {

    private val iosRoot = File(System.getProperty("oshi.ios.root") ?: "../OSHI")
    private val androidRoot = File(System.getProperty("oshi.android.root") ?: "../OSHI-Android")

    private fun ios(name: String): String? =
        File(iosRoot, name).takeIf { it.isFile }?.readText()

    private fun android(name: String): String? =
        File(androidRoot, "app/src/main/java/com/oshi/messenger/$name").takeIf { it.isFile }?.readText()

    // ============================================================= what iOS records

    @Test
    fun `iOS records AAC in MPEG-4 at the rate and channel count this client captures`() {
        val src = ios("AudioRecorderManager.swift")
        assumeTrue("no iOS tree on this machine", src != null)

        assertTrue("iOS no longer records AAC", src!!.contains("AVFormatIDKey: Int(kAudioFormatMPEG4AAC)"))
        assertTrue("iOS sample rate changed", src.contains("AVSampleRateKey: ${VoiceNoteFormat.SAMPLE_RATE}"))
        assertTrue("iOS channel count changed", src.contains("AVNumberOfChannelsKey: ${VoiceNoteFormat.CHANNELS}"))
        assertTrue("iOS no longer writes .m4a", src.contains(".m4a"))
    }

    /**
     * The wire announcement. `audio/m4a` and `audio.m4a` are what an iPhone puts on the
     * key message, and [VoiceNoteContainer.M4A_AAC] repeats them exactly.
     */
    @Test
    fun `iOS announces audio as audio slash m4a with the filename audio dot m4a`() {
        val src = ios("MessageManager+V2.swift")
        assumeTrue("no iOS tree on this machine", src != null)

        assertTrue(src!!.contains("""case .audio: return "${VoiceNoteContainer.M4A_AAC.mime}""""))
        assertTrue(src.contains("""case .audio: return "${VoiceNoteContainer.M4A_AAC.defaultFilename}""""))
        // And the receiver classifies on the prefix alone, which is why `audio/x-m4a`
        // from `Files.probeContentType` is equally fine.
        assertTrue(src.contains("""if mime.hasPrefix("audio/") { return .audio }"""))
    }

    /**
     * **The counter-intuitive one.** iOS has an `AudioAttachment` envelope with a
     * duration and a waveform in it, and does NOT send it: the raw m4a bytes go on the
     * wire. If this ever changes, this client's notes stop carrying what a phone reads.
     */
    @Test
    fun `iOS sends the raw container bytes, not its AudioAttachment envelope`() {
        val chat = ios("ChatView.swift")
        assumeTrue("no iOS tree on this machine", chat != null)

        val start = chat!!.indexOf("private func sendAudioMessage(")
        assertTrue("sendAudioMessage disappeared from ChatView", start > 0)
        val body = chat.substring(start, minOf(start + 1_200, chat.length))

        assertTrue("iOS no longer sends the raw bytes", body.contains("mediaAttachment: attachment.data"))
        assertTrue(body.contains("mediaType: .audio"))
        // No encoding step between the recorder and the wire.
        assertFalse("iOS started wrapping audio in JSON before sending", body.contains("JSONEncoder"))
    }

    /** The receiver falls back to "raw bytes, duration 0" — i.e. no duration crosses. */
    @Test
    fun `iOS reads an inbound note as raw bytes with no duration`() {
        val view = ios("AudioMessageView.swift")
        assumeTrue("no iOS tree on this machine", view != null)
        assertTrue(view!!.contains("Fallback for legacy audio messages"))
        assertTrue(view.contains("duration: 0"))
    }

    // ============================================================ what Android records

    @Test
    fun `Android records AAC in MPEG-4 at the same rate and bitrate this client encodes to`() {
        val src = android("service/AudioRecorderManager.kt")
        assumeTrue("no Android tree on this machine", src != null)

        assertTrue(src!!.contains("MediaRecorder.OutputFormat.MPEG_4"))
        assertTrue(src.contains("MediaRecorder.AudioEncoder.AAC"))
        assertTrue(
            "Android bitrate changed away from ${VoiceNoteFormat.AAC_BITRATE}",
            src.contains("setAudioEncodingBitRate(${VoiceNoteFormat.AAC_BITRATE})"),
        )
        assertTrue(
            "Android sample rate changed away from ${VoiceNoteFormat.SAMPLE_RATE}",
            src.contains("SAMPLE_RATE = ${VoiceNoteFormat.SAMPLE_RATE}"),
        )
    }

    /**
     * The 5-minute bound is Android's, copied. iOS has none, so the stricter shipped
     * number is the one a note must satisfy to play on both.
     */
    @Test
    fun `the recording bound is the one Android ships`() {
        val src = android("service/AudioRecorderManager.kt")
        assumeTrue("no Android tree on this machine", src != null)
        assertTrue(
            "Android's recording cap moved away from ${VoiceNoteFormat.MAX_DURATION_MS} ms",
            src!!.contains("MAX_RECORDING_DURATION_MS = 300_000L"),
        )
        assertEquals(300_000L, VoiceNoteFormat.MAX_DURATION_MS)
    }

    /**
     * Android takes the duration out of the CONTAINER, which is why the container this
     * client ships must carry a readable one — the whole reason [AudioDurationProbe] and
     * the m4a encode step exist.
     */
    @Test
    fun `Android reads the duration out of the container itself`() {
        val src = android("ui/media/VoiceNoteAudio.kt")
        assumeTrue("no Android tree on this machine", src != null)
        assertTrue(src!!.contains("MediaMetadataRetriever.METADATA_KEY_DURATION"))
    }

    @Test
    fun `Android classifies audio on the mime prefix alone`() {
        val src = android("data/repository/MessageRepository.kt")
        assumeTrue("no Android tree on this machine", src != null)
        assertTrue(src!!.contains("""mime.startsWith("audio/") -> MediaType.AUDIO"""))
    }

    // ================================================== and what this client announces

    /**
     * The field both receivers read FIRST. PARITY.md row 0.12 records what happens when
     * it is wrong: every photo, clip and voice note this client sent drew a file row with
     * a Download button.
     */
    @Test
    fun `both containers this client can send classify as AUDIO, by field and by mime`() {
        for (c in VoiceNoteContainer.entries) {
            assertEquals(c.name, MediaType.AUDIO, MediaType.forMime(c.mime))
        }
        assertEquals("audio", MediaType.AUDIO.wire)
        assertEquals(MediaType.AUDIO, MediaType.fromWire("audio"))
        // What `Files.probeContentType` returns on this desktop for the two extensions.
        assertEquals(MediaType.AUDIO, MediaType.forMime("audio/x-m4a"))
        assertEquals(MediaType.AUDIO, MediaType.forMime("audio/mp4"))
        assertEquals(MediaType.AUDIO, MediaType.forMime("audio/vnd.wave"))
        // …and the trap the seam's KDoc warns about: no MIME database, no `wav` in
        // OshiClient's fallback table, and a WAV note becomes a DOCUMENT.
        assertEquals(MediaType.DOCUMENT, MediaType.forMime("application/octet-stream"))
    }
}
