package com.oshi.desktop.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The duration is the load-bearing number in a voice-note bubble, and the header is its
 * only source ([VoiceNoteFormat] cites why). Everything here is bytes: no device, no
 * decode, no clock.
 */
class AudioDurationProbeTest {

    @get:Rule val tmp = TemporaryFolder()

    // ================================================================== real containers

    /**
     * A REAL afconvert header, not a fixture this project invented.
     *
     * 2 066 ms for a 2.000 s input is not an error: an AAC encode adds priming and
     * padding, and 2.066 s is exactly what `afinfo` reports for the same file and what a
     * phone would show. A parser "corrected" to say 2 000 would disagree with both phones.
     */
    @Test
    fun `reads the duration out of a real afconvert m4a header`() {
        val info = AudioDurationProbe.of(ArrayWindow(AudioFixtures.REAL_M4A_HEADER))
        assertNotNull(info)
        assertEquals(VoiceNoteContainer.M4A_AAC, info!!.container)
        assertEquals(AudioFixtures.REAL_M4A_DURATION_MS, info.durationMs)
        assertEquals(2066L, info.durationMs)
    }

    /** The WAV this client writes, measured by the same parser a phone's would be. */
    @Test
    fun `reads the duration out of a wav this client wrote`() {
        // 3 seconds exactly.
        val pcm = 3 * VoiceNoteFormat.BYTES_PER_SECOND
        val info = AudioDurationProbe.of(ArrayWindow(AudioFixtures.wav(pcm)))
        assertNotNull(info)
        assertEquals(VoiceNoteContainer.WAV_PCM, info!!.container)
        assertEquals(3_000L, info.durationMs)
        assertEquals(VoiceNoteFormat.SAMPLE_RATE, info.sampleRate)
        assertEquals(VoiceNoteFormat.CHANNELS, info.channels)
    }

    /** A file and an array must agree, or one of the two windows has an off-by-one. */
    @Test
    fun `a file window and an array window give the same answer`() {
        val bytes = AudioFixtures.wav(VoiceNoteFormat.BYTES_PER_SECOND)
        val f = tmp.newFile("one-second.wav")
        f.writeBytes(bytes)
        assertEquals(
            AudioDurationProbe.of(ArrayWindow(bytes))!!.durationMs,
            AudioDurationProbe.of(f)!!.durationMs,
        )
        assertEquals(1_000L, AudioDurationProbe.of(f)!!.durationMs)
    }

    // ======================================================================= MPEG-4

    /**
     * `mvhd` version 1 moves `timescale` from +12 to +20 and widens `duration` to 64 bits.
     *
     * Read with version-0 offsets, a v1 box yields a plausible number rather than an
     * error — which is why the version byte is read and not assumed. This fixture would
     * measure 0 ms under a v0-only parser.
     */
    @Test
    fun `an mvhd version 1 box is read with version 1 offsets`() {
        val bytes = AudioFixtures.mp4(
            AudioFixtures.box("moov", AudioFixtures.box("mvhd", AudioFixtures.mvhdV1(1_000, 7_500))),
        )
        assertEquals(7_500L, AudioDurationProbe.of(ArrayWindow(bytes))!!.durationMs)
    }

    /**
     * A muxer that cannot seek writes `mdat` before `moov`. Both phones' files are
     * front-loaded, but an inbound note is not this client's to choose.
     */
    @Test
    fun `moov is found after mdat`() {
        val bytes = AudioFixtures.mp4(
            AudioFixtures.box("mdat", ByteArray(512)),
            AudioFixtures.box("moov", AudioFixtures.box("mvhd", AudioFixtures.mvhdV0(48_000, 96_000))),
        )
        assertEquals(2_000L, AudioDurationProbe.of(ArrayWindow(bytes))!!.durationMs)
    }

    /** `size == 1` means a 64-bit size follows. A parser that ignores it walks off. */
    @Test
    fun `a 64-bit box size is followed`() {
        val bytes = AudioFixtures.mp4(
            AudioFixtures.largeBox("mdat", ByteArray(64)),
            AudioFixtures.box("moov", AudioFixtures.box("mvhd", AudioFixtures.mvhdV0(44_100, 44_100))),
        )
        assertEquals(1_000L, AudioDurationProbe.of(ArrayWindow(bytes))!!.durationMs)
    }

    /** `0xFFFFFFFF` is the spec's "unknown", not 27 hours. */
    @Test
    fun `an unknown v0 duration is null and not twenty-seven hours`() {
        val bytes = AudioFixtures.mp4(
            AudioFixtures.box("moov", AudioFixtures.box("mvhd", AudioFixtures.mvhdV0(44_100, 0xFFFFFFFFL))),
        )
        assertNull(AudioDurationProbe.of(ArrayWindow(bytes)))
    }

    /** A zero timescale would divide by zero. It must be null, not a crash. */
    @Test
    fun `a zero timescale is null`() {
        val bytes = AudioFixtures.mp4(
            AudioFixtures.box("moov", AudioFixtures.box("mvhd", AudioFixtures.mvhdV0(0, 44_100))),
        )
        assertNull(AudioDurationProbe.of(ArrayWindow(bytes)))
    }

    /**
     * A box header declaring a size below its own header length is corruption, and the
     * walk must stop rather than advance by that size into the middle of a field.
     *
     * **This fixture is built so that TOLERATING the bad size produces a plausible answer
     * rather than nothing.** The first attempt at this test used a lone 3-byte box and it
     * was worthless: without the guard the walk still ran off the end and still returned
     * null, so the mutation survived and the test looked like it was pinning something.
     *
     * Here the undersized box's advance lands EXACTLY on a fabricated `moov`/`mvhd` that a
     * correct parser can never reach. Correct: null. Tolerant: 1 000 ms, confidently.
     */
    @Test
    fun `an undersized box stops the walk instead of advancing into the middle of a field`() {
        val mvhd = AudioFixtures.box("mvhd", AudioFixtures.mvhdV0(44_100, 44_100)) // 28 bytes
        val trap =
            byteArrayOf(0, 0, 0, 4) +                       // declared size 4 — illegal
                byteArrayOf(0, 0, 0, (8 + mvhd.size).toByte()) + // …which is ALSO a valid size
                "moov".toByteArray(Charsets.US_ASCII) +      // …followed by a real moov type
                mvhd
        val bytes = AudioFixtures.mp4(trap)

        assertNull(
            "an illegal box size was tolerated and the walk landed inside a field",
            AudioDurationProbe.of(ArrayWindow(bytes)),
        )
    }

    /** And the shape the guard must NOT reject: a legal minimum-size box. */
    @Test
    fun `a legal eight-byte box is walked past, not rejected`() {
        val bytes = AudioFixtures.mp4(
            AudioFixtures.box("free", ByteArray(0)), // exactly 8 bytes, entirely legal
            AudioFixtures.box("moov", AudioFixtures.box("mvhd", AudioFixtures.mvhdV0(44_100, 22_050))),
        )
        assertEquals(500L, AudioDurationProbe.of(ArrayWindow(bytes))!!.durationMs)
    }

    /** A truncated download must measure nothing, never a number from partial bytes. */
    @Test
    fun `a truncated mvhd is null and not a number invented from half a field`() {
        val full = AudioFixtures.mp4(
            AudioFixtures.box("moov", AudioFixtures.box("mvhd", AudioFixtures.mvhdV0(44_100, 44_100))),
        )
        // Cut two bytes off the duration field.
        val cut = full.copyOfRange(0, full.size - 2)
        assertNull(AudioDurationProbe.of(ArrayWindow(cut)))
    }

    // ========================================================================== WAV

    /**
     * A `data` size of 0 is written by anything that streams a WAV without seeking back
     * to patch the header. "The rest of the file" is the only true reading.
     */
    @Test
    fun `a data chunk declaring zero bytes measures the rest of the file`() {
        val pcm = VoiceNoteFormat.BYTES_PER_SECOND
        val bytes = AudioFixtures.wav(pcm)
        // Zero the `data` size field (last 4 bytes of the 44-byte header).
        for (i in 40 until 44) bytes[i] = 0
        assertEquals(1_000L, AudioDurationProbe.of(ArrayWindow(bytes))!!.durationMs)
    }

    /** A `data` size larger than the file is a lie; the bytes present are the truth. */
    @Test
    fun `a data chunk that overruns the file is clamped to what is there`() {
        val pcm = VoiceNoteFormat.BYTES_PER_SECOND
        val bytes = AudioFixtures.wav(pcm)
        // Claim 10 seconds.
        val claim = 10L * VoiceNoteFormat.BYTES_PER_SECOND
        for (i in 0 until 4) bytes[40 + i] = ((claim shr (8 * i)) and 0xFF).toByte()
        assertEquals(1_000L, AudioDurationProbe.of(ArrayWindow(bytes))!!.durationMs)
    }

    /** A zero byte rate is the one thing that makes a WAV unmeasurable. */
    @Test
    fun `a zero byte rate is null rather than a division by zero`() {
        val bytes = AudioFixtures.wav(VoiceNoteFormat.BYTES_PER_SECOND)
        for (i in 28 until 32) bytes[i] = 0 // nAvgBytesPerSec
        assertNull(AudioDurationProbe.of(ArrayWindow(bytes)))
    }

    /**
     * RIFF chunks are word-aligned: an odd-sized chunk carries one pad byte that is NOT
     * counted in its size. A parser that forgets it lands one byte into every later
     * chunk id and never finds `data`.
     */
    @Test
    fun `an odd-sized chunk before data is skipped with its pad byte`() {
        val pcm = VoiceNoteFormat.BYTES_PER_SECOND
        val header = WavWriter.header(pcm.toLong())
        val listChunk = "LIST".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(5, 0, 0, 0) + ByteArray(5) { 0x41 } + byteArrayOf(0)
        // RIFF(12) + fmt(24) + LIST(8+5+1) + data(8) + pcm
        val bytes = header.copyOfRange(0, 36) + listChunk + header.copyOfRange(36, 44) + ByteArray(pcm)
        assertEquals(1_000L, AudioDurationProbe.of(ArrayWindow(bytes))!!.durationMs)
    }

    // ======================================================================== other

    @Test
    fun `a container this client does not know is null, not a guess`() {
        assertNull(AudioDurationProbe.of(ArrayWindow("OggS".toByteArray() + ByteArray(64))))
        assertNull(AudioDurationProbe.of(ArrayWindow(ByteArray(0))))
        assertNull(AudioDurationProbe.of(File("/nonexistent/never.m4a")))
    }

    @Test
    fun `container sniffing ignores the extension entirely`() {
        val f = tmp.newFile("looks-like-a-note.m4a")
        f.writeBytes(AudioFixtures.wav(VoiceNoteFormat.BYTES_PER_SECOND))
        // Both phones rename an inbound note to `.m4a` before decoding it, so a parser
        // that trusted the extension would be wrong on every single received file.
        assertEquals(VoiceNoteContainer.WAV_PCM, AudioContainers.of(f))
        assertEquals(1_000L, AudioDurationProbe.of(f)!!.durationMs)
    }

    /** The `ftyp` brand is not checked: afconvert writes `M4A `, MediaMuxer `mp42`/`isom`. */
    @Test
    fun `any ftyp brand counts as mpeg-4`() {
        for (brand in listOf("M4A ", "mp42", "isom", "3gp4")) {
            val head = byteArrayOf(0, 0, 0, 0x18) + "ftyp".toByteArray(Charsets.US_ASCII) +
                brand.toByteArray(Charsets.US_ASCII)
            assertTrue(brand, AudioContainers.isMp4(head))
        }
    }
}
