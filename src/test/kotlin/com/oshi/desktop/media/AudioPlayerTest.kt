package com.oshi.desktop.media

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.LineUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Playback, against a fake speaker. No test here makes a sound. */
class AudioPlayerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun wavFile(millis: Int = 200): File =
        tmp.newFile("note-${millis}-${System.nanoTime()}.wav").also { it.writeBytes(WavWriter.tone(millis)) }

    private fun player(
        devices: FakeDevices = FakeDevices(),
        transcoder: AudioTranscoder = FakeTranscoder.unavailable(),
    ) = AudioPlayer(devices = devices, transcoder = transcoder, workDir = tmp.newFolder())

    private fun awaitEnd(latch: CountDownLatch) =
        assertTrue("playback never ended", latch.await(5, TimeUnit.SECONDS))

    // ==================================================================== the happy path

    @Test
    fun `a wav plays every one of its pcm bytes and ends completed`() {
        val line = FakeRenderLine()
        val devices = FakeDevices(renderLine = { line })
        val latch = CountDownLatch(1)
        val end = AtomicReference<PlaybackEnd>()

        val file = wavFile(200)
        val start = player(devices).play(file) { end.set(it); latch.countDown() }
        assertTrue(start.toString(), start is PlaybackStart.Started)
        awaitEnd(latch)

        assertEquals(PlaybackEnd.Completed, end.get())
        assertEquals((file.length() - WavWriter.HEADER_BYTES), line.written.get())
        assertTrue(line.drained.get())
        assertTrue(line.closed.get())
    }

    /**
     * **Not on the caller's thread.** With a speaker that consumes at roughly real time,
     * `play` must return long before the note has finished — otherwise every voice note
     * freezes the UI for its own duration.
     */
    @Test
    fun `play returns immediately even when the speaker is slow`() {
        val devices = FakeDevices(renderLine = { FakeRenderLine(writeDelayMs = 40) })
        val latch = CountDownLatch(1)
        val p = player(devices)

        val before = System.nanoTime()
        val start = p.play(wavFile(400)) { latch.countDown() }
        val elapsedMs = (System.nanoTime() - before) / 1_000_000

        assertTrue(start is PlaybackStart.Started)
        // 400 ms of audio at 100 ms per chunk with a 40 ms write delay cannot be done in
        // 300 ms; a blocking implementation would be well past it.
        assertTrue("play() blocked for $elapsedMs ms", elapsedMs < 300)
        awaitEnd(latch)
    }

    // ========================================================================= stopping

    @Test
    fun `stop ends the playback as stopped and does not drain the queued audio`() {
        val line = FakeRenderLine(writeDelayMs = 30)
        val devices = FakeDevices(renderLine = { line })
        val latch = CountDownLatch(1)
        val end = AtomicReference<PlaybackEnd>()
        val p = player(devices)

        val start = p.play(wavFile(2_000)) { end.set(it); latch.countDown() } as PlaybackStart.Started
        Thread.sleep(60)
        start.handle.stop()
        awaitEnd(latch)

        assertEquals(PlaybackEnd.Stopped, end.get())
        // Draining a stopped line would play audio the user asked not to hear.
        assertFalse(line.drained.get())
        assertTrue(line.flushed.get())
        assertFalse(p.isPlaying)
    }

    /** Both phones stop the current note before starting another. So does this. */
    @Test
    fun `starting a second note stops the first`() {
        val devices = FakeDevices(renderLine = { FakeRenderLine(writeDelayMs = 30) })
        val latch = CountDownLatch(1)
        val first = AtomicReference<PlaybackEnd>()
        val p = player(devices)

        p.play(wavFile(2_000)) { first.set(it); latch.countDown() }
        Thread.sleep(50)
        p.play(wavFile(200))
        awaitEnd(latch)

        assertEquals(PlaybackEnd.Stopped, first.get())
    }

    @Test
    fun `stop with nothing playing is a no-op`() {
        player().stop()
    }

    // ========================================================================= refusals

    @Test
    fun `a missing file is named, not played`() {
        val start = player().play(File(tmp.newFolder(), "gone.wav"))
        assertTrue((start as PlaybackStart.Failure).reason is PlaybackFailure.FileMissing)
    }

    @Test
    fun `an empty file is named, not played`() {
        val start = player().play(tmp.newFile("empty.wav"))
        assertTrue((start as PlaybackStart.Failure).reason is PlaybackFailure.FileEmpty)
    }

    @Test
    fun `a machine with no speaker is named and no line is opened`() {
        val devices = FakeDevices(renderSupported = false)
        val start = player(devices).play(wavFile())
        assertTrue((start as PlaybackStart.Failure).reason is PlaybackFailure.NoOutputDevice)
        assertTrue(devices.openedRenders.isEmpty())
    }

    @Test
    fun `a speaker held by another process is named`() {
        val devices = FakeDevices(openRenderThrows = LineUnavailableException("in use"))
        val start = player(devices).play(wavFile())
        val reason = (start as PlaybackStart.Failure).reason
        assertTrue(reason is PlaybackFailure.DeviceUnavailable)
        assertTrue(reason.message, reason.message.contains("in use"))
    }

    // ===================================================== the m4a the JVM cannot open

    /**
     * **The honest failure for an inbound PHONE voice note on a machine with no encoder.**
     *
     * `javax.sound` cannot open an m4a. With no `afconvert` and no working `ffmpeg` —
     * a stock Linux or Windows desktop — there is nothing to do but say so, naming the
     * container and what was tried. Silently playing nothing would look identical to a
     * note of silence.
     */
    @Test
    fun `an m4a with no usable decoder is refused by name and says why`() {
        val m4a = tmp.newFile("from-a-phone.m4a")
        m4a.writeBytes(AudioFixtures.REAL_M4A_HEADER)

        val start = player(transcoder = FakeTranscoder.unavailable()).play(m4a)
        val reason = (start as PlaybackStart.Failure).reason
        assertTrue(reason.toString(), reason is PlaybackFailure.UnsupportedContainer)
        assertEquals("M4A_AAC", (reason as PlaybackFailure.UnsupportedContainer).container)
        assertTrue(reason.message, reason.message.contains("no AAC decoder"))
        assertTrue(reason.message, reason.message.contains("not on PATH"))
    }

    /** With a decoder, the same file plays — through the transcoded temp WAV. */
    @Test
    fun `an m4a plays once a decoder can turn it into a wav`() {
        val m4a = tmp.newFile("phone-note.m4a")
        m4a.writeBytes(AudioFixtures.REAL_M4A_HEADER)
        val line = FakeRenderLine()
        val devices = FakeDevices(renderLine = { line })
        val transcoder = FakeTranscoder(
            onWav = { _, target ->
                target.parentFile?.mkdirs()
                target.writeBytes(WavWriter.tone(millis = 150))
                TranscodeResult.Produced(target, "fake")
            },
        )
        val latch = CountDownLatch(1)
        val start = player(devices, transcoder).play(m4a) { latch.countDown() }
        assertTrue(start.toString(), start is PlaybackStart.Started)
        awaitEnd(latch)
        val expected = (WavWriter.tone(millis = 150).size - WavWriter.HEADER_BYTES).toLong()
        assertEquals(expected, line.written.get())
    }

    /**
     * The temp WAV a decode produced must not survive the playback: it is plaintext audio
     * from a decrypted message sitting in a working directory.
     */
    @Test
    fun `a decoded temp file is deleted when playback ends`() {
        val workDir = tmp.newFolder()
        val m4a = tmp.newFile("wipe-me.m4a")
        m4a.writeBytes(AudioFixtures.REAL_M4A_HEADER)
        val transcoder = FakeTranscoder(
            onWav = { _, target ->
                target.parentFile?.mkdirs()
                target.writeBytes(WavWriter.tone(millis = 50))
                TranscodeResult.Produced(target, "fake")
            },
        )
        val latch = CountDownLatch(1)
        val p = AudioPlayer(devices = FakeDevices(), transcoder = transcoder, workDir = workDir)
        p.play(m4a) { latch.countDown() }
        awaitEnd(latch)
        Thread.sleep(50)
        assertTrue(
            workDir.listFiles().orEmpty().joinToString { it.name },
            workDir.listFiles().orEmpty().none { it.name.startsWith("decode-") },
        )
    }
}
