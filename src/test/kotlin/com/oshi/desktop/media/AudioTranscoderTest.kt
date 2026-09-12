package com.oshi.desktop.media

import java.io.File
import javax.sound.sampled.AudioSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The encoder that turns this client's WAV into the m4a both phones record — and, more
 * importantly, every way an encoder can appear to work and not.
 *
 * The scripted tests need no tools at all. The two at the bottom use the REAL encoder and
 * are skipped when there is none, which is the only honest way to test a subprocess.
 */
class AudioTranscoderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun source(): File = tmp.newFile("in.wav").also { it.writeBytes(WavWriter.tone(millis = 100)) }

    private fun transcoder(
        tools: List<TranscodeTool>,
        onPath: Set<String>,
        runner: ProcessRunner,
    ) = SystemAudioTranscoder(
        tools = tools,
        runner = runner,
        resolve = { if (it in onPath) "/fake/bin/$it" else null },
        workDir = tmp.newFolder(),
    )

    private fun ok() = ProcessRunner.Outcome(0, "", timedOut = false)

    // ==================================================== PRESENCE IS NOT WORKINGNESS

    /**
     * **The guard this file exists for.**
     *
     * The `ffmpeg` on the machine this was written on is on `PATH`, is executable, and
     * dies at startup with `Library not loaded: …libx265.215.dylib`. A capability check
     * built on `which` would have reported "m4a available" and then shipped a WAV under an
     * m4a name. Exit code 0 is not evidence either — only the bytes are.
     */
    @Test
    fun `a tool that exits zero and produces nothing has failed`() {
        val t = transcoder(
            tools = listOf(TranscodeTool.FFMPEG),
            onPath = setOf("ffmpeg"),
            runner = { _, _ -> ok() }, // exits 0, writes no file
        )
        val result = t.toM4a(source(), File(tmp.newFolder(), "out.m4a"))
        assertTrue(result.toString(), result is TranscodeResult.Unavailable)
        assertTrue(
            (result as TranscodeResult.Unavailable).message,
            result.message.contains("produced no bytes"),
        )
        assertNull(t.probe())
    }

    /** Exit 0, a real file, the WRONG container. Named, not accepted. */
    @Test
    fun `a tool that exits zero and produces a wav when asked for m4a has failed`() {
        val out = File(tmp.newFolder(), "out.m4a")
        val t = transcoder(
            tools = listOf(TranscodeTool.FFMPEG),
            onPath = setOf("ffmpeg"),
            runner = { command, _ -> File(command.last()).writeBytes(WavWriter.tone(millis = 50)); ok() },
        )
        val result = t.toM4a(source(), out)
        assertTrue(result.toString(), result is TranscodeResult.Unavailable)
        assertTrue(
            (result as TranscodeResult.Unavailable).message,
            result.message.contains("WAV_PCM"),
        )
    }

    @Test
    fun `a tool that is not on PATH is skipped and named`() {
        val t = transcoder(listOf(TranscodeTool.AFCONVERT), emptySet()) { _, _ -> ok() }
        val result = t.toM4a(source(), File(tmp.newFolder(), "out.m4a"))
        assertTrue((result as TranscodeResult.Unavailable).message.contains("afconvert: not on PATH"))
    }

    @Test
    fun `a tool that hangs is killed and reported as a timeout, not as a crash`() {
        val t = transcoder(listOf(TranscodeTool.FFMPEG), setOf("ffmpeg")) { _, _ ->
            ProcessRunner.Outcome(-1, "", timedOut = true)
        }
        val result = t.toM4a(source(), File(tmp.newFolder(), "out.m4a"))
        assertTrue((result as TranscodeResult.Unavailable).message.contains("timed out"))
    }

    @Test
    fun `a non-zero exit carries the tool's own stderr`() {
        val t = transcoder(listOf(TranscodeTool.FFMPEG), setOf("ffmpeg")) { _, _ ->
            ProcessRunner.Outcome(1, "Unknown encoder 'aac'", timedOut = false)
        }
        val result = t.toM4a(source(), File(tmp.newFolder(), "out.m4a"))
        assertTrue((result as TranscodeResult.Unavailable).message.contains("Unknown encoder"))
    }

    /** A broken first tool must not stop a working second one. */
    @Test
    fun `a broken tool falls through to the next and the winner is named`() {
        val out = File(tmp.newFolder(), "out.m4a")
        val t = transcoder(
            tools = listOf(TranscodeTool.AFCONVERT, TranscodeTool.FFMPEG),
            onPath = setOf("afconvert", "ffmpeg"),
        ) { command, _ ->
            if (command.first() == "afconvert") {
                ProcessRunner.Outcome(1, "half-installed", timedOut = false)
            } else {
                // Write to the destination the command was actually given, so the probe
                // (which uses its own scratch files) is driven by the same fake.
                File(command.last()).writeBytes(AudioFixtures.REAL_M4A_HEADER)
                ok()
            }
        }
        val result = t.toM4a(source(), out)
        assertEquals("ffmpeg", (result as TranscodeResult.Produced).tool)
        assertEquals("ffmpeg", t.probe())
    }

    /**
     * A stale file from an earlier attempt would be sniffed as this attempt's success.
     * The target is deleted before every run for that reason alone.
     */
    @Test
    fun `a stale target from a previous attempt is not mistaken for success`() {
        val out = File(tmp.newFolder(), "out.m4a")
        out.writeBytes(AudioFixtures.REAL_M4A_HEADER) // left over, looks perfect
        val t = transcoder(listOf(TranscodeTool.FFMPEG), setOf("ffmpeg")) { _, _ ->
            ProcessRunner.Outcome(1, "disk full", timedOut = false)
        }
        val result = t.toM4a(source(), out)
        assertTrue(result.toString(), result is TranscodeResult.Unavailable)
    }

    @Test
    fun `an empty or missing source is refused before any tool runs`() {
        var ran = false
        val t = transcoder(listOf(TranscodeTool.FFMPEG), setOf("ffmpeg")) { _, _ -> ran = true; ok() }
        val empty = tmp.newFile("empty.wav")
        assertTrue(t.toM4a(empty, File(tmp.newFolder(), "o.m4a")) is TranscodeResult.Failed)
        assertTrue(t.toM4a(File("/nope/nope.wav"), File(tmp.newFolder(), "o.m4a")) is TranscodeResult.Failed)
        org.junit.Assert.assertFalse("no tool may run for an unusable source", ran)
    }

    // ================================================================= parity of the args

    /**
     * The command lines are parity, not taste: 44 100 Hz, mono, AAC at 128 kbit/s is what
     * Android's recorder is configured to
     * (`OSHI-Android/…/service/AudioRecorderManager.kt:143-145`) and what iOS's
     * `AVAudioRecorder` settings ask for (`OSHI/AudioRecorderManager.swift:86-89`).
     */
    @Test
    fun `every encode command pins the phones' rate, channel count and bitrate`() {
        val src = File("/tmp/a.wav")
        val dst = File("/tmp/b.m4a")
        for (tool in TranscodeTool.DEFAULTS) {
            val args = tool.encodeArgs(src, dst).joinToString(" ")
            assertTrue(tool.name, args.contains("44100"))
            assertTrue(tool.name, args.contains("128000") || args.contains("128k"))
            assertTrue(tool.name, args.contains(src.absolutePath))
            assertTrue(tool.name, args.contains(dst.absolutePath))
        }
        assertTrue(TranscodeTool.AFCONVERT.encodeArgs(src, dst).contains("m4af"))
        assertTrue(TranscodeTool.FFMPEG.encodeArgs(src, dst).contains("aac"))
    }

    /**
     * `-nostdin` and `-y`: without them an ffmpeg that decides to prompt inherits the
     * terminal and blocks until the timeout instead of failing.
     */
    @Test
    fun `ffmpeg is never allowed to prompt`() {
        for (args in listOf(
            TranscodeTool.FFMPEG.encodeArgs(File("a"), File("b")),
            TranscodeTool.FFMPEG.decodeArgs(File("a"), File("b")),
        )) {
            assertTrue(args.toString(), args.contains("-nostdin"))
            assertTrue(args.toString(), args.contains("-y"))
        }
    }

    @Test
    fun `afconvert is tried before ffmpeg`() {
        assertEquals("afconvert", TranscodeTool.DEFAULTS.first().name)
    }

    // ============================================================== the REAL encoder

    /**
     * The end-to-end encode, on whatever encoder this machine actually has.
     *
     * Skipped — not failed — where there is none, because a stock Linux container has no
     * `afconvert` and no `ffmpeg`, and that ABSENCE is a first-class outcome this package
     * reports rather than an environment bug.
     */
    @Test
    fun `the real encoder produces an mpeg-4 whose header states the right duration`() {
        // The gate is `probe()` and not `which`: a tool on PATH that cannot run is
        // precisely the case this class was built for, and skipping on `which` would
        // have RUN this test against the broken ffmpeg on the machine it was written on.
        val t = SystemAudioTranscoder(workDir = tmp.newFolder())
        assumeTrue("no working AAC encoder on this machine", t.probe() != null)

        val src = tmp.newFile("real-in.wav")
        src.writeBytes(WavWriter.tone(millis = 1_000))
        val dst = File(tmp.newFolder(), "real-out.m4a")

        val result = t.toM4a(src, dst)
        assertTrue(result.toString(), result is TranscodeResult.Produced)
        assertEquals(VoiceNoteContainer.M4A_AAC, AudioContainers.of(dst))

        val info = AudioDurationProbe.of(dst)
        assertNotNull("the encoder wrote no readable mvhd", info)
        // An AAC encode adds priming and padding; a second of input lands within ~200 ms.
        assertTrue("duration was ${info!!.durationMs} ms", info.durationMs in 900L..1_300L)
    }

    /**
     * And back: the decode direction is what makes an inbound PHONE voice note playable
     * here at all, since `javax.sound` cannot open an m4a.
     */
    @Test
    fun `the real decoder turns an m4a back into a wav the JDK can open`() {
        // The gate is `probe()` and not `which`: a tool on PATH that cannot run is
        // precisely the case this class was built for, and skipping on `which` would
        // have RUN this test against the broken ffmpeg on the machine it was written on.
        val t = SystemAudioTranscoder(workDir = tmp.newFolder())
        assumeTrue("no working AAC encoder on this machine", t.probe() != null)

        val src = tmp.newFile("rt-in.wav")
        src.writeBytes(WavWriter.tone(millis = 500))
        val m4a = File(tmp.newFolder(), "rt.m4a")
        assumeTrue(t.toM4a(src, m4a) is TranscodeResult.Produced)

        // This is the assertion that matters: the JDK refuses the m4a outright, which is
        // exactly why the decode step exists.
        var jdkRefusedTheM4a = false
        try {
            AudioSystem.getAudioInputStream(m4a).close()
        } catch (_: javax.sound.sampled.UnsupportedAudioFileException) {
            jdkRefusedTheM4a = true
        }
        assertTrue("the JDK unexpectedly read an m4a — revisit AudioPlayer", jdkRefusedTheM4a)

        val wav = File(tmp.newFolder(), "rt-out.wav")
        assertTrue(t.toWav(m4a, wav) is TranscodeResult.Produced)
        val stream = AudioSystem.getAudioInputStream(wav)
        assertEquals(VoiceNoteFormat.SAMPLE_RATE.toFloat(), stream.format.sampleRate, 0.001f)
        stream.close()
    }
}
