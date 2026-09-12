package com.oshi.desktop.media

import com.oshi.desktop.store.DesktopPaths
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The AAC encoder the JVM does not have.
 *
 * ============================================================ WHY A SUBPROCESS
 *
 * Both phones record AAC-LC in MPEG-4 ([VoiceNoteFormat] cites the lines). The JDK can
 * neither encode AAC nor mux MPEG-4 — `javax.sound.sampled`'s writers are WAVE, AU and
 * AIFF, and its readers refuse an m4a outright (`UnsupportedAudioFileException`, measured
 * on this machine against a real afconvert encode). The alternatives were:
 *
 *  - **A pure-Java AAC encoder.** None exists that is maintained; the JCodec/jaad family
 *    decodes only. This would be a new dependency for a format the OS already ships an
 *    encoder for.
 *  - **Ship WAV and hope.** Rejected as the DEFAULT. It may well work — see
 *    [VoiceNoteContainer.WAV_PCM] — but "may well" is not something to ship silently as
 *    the normal path.
 *  - **A subprocess.** Taken. On macOS `/usr/bin/afconvert` is part of the OS and is the
 *    same AudioToolbox encoder `AVAudioRecorder` itself uses on the iPhone, so the m4a it
 *    produces is the same KIND of file, not merely a compatible one. Elsewhere `ffmpeg`
 *    if the user has one.
 *
 * ============================================================ PRESENCE IS NOT WORKINGNESS
 *
 * **A tool being on `PATH` proves nothing.** The `ffmpeg` on the machine this was written
 * on is on `PATH`, is executable, and dies at startup with
 * `Library not loaded: …libx265.215.dylib` — a Homebrew upgrade left it half-installed.
 * A `which`-based capability check would have reported "m4a available" on this very
 * machine and then produced nothing.
 *
 * So [probe] RUNS a candidate on a real 100 ms WAV and inspects the bytes it produced,
 * and every real conversion validates its own output the same way ([AudioContainers]).
 * An encoder that exits 0 and leaves a 0-byte file, or a WAV, has failed.
 */
interface AudioTranscoder {

    /**
     * Which tool this machine will actually use, verified by running it. Null when none
     * works — in which case a voice note ships as WAV and the caller must say so.
     *
     * Cached after the first call: the probe costs one subprocess.
     */
    fun probe(): String?

    /** Encode a PCM WAV to AAC/MPEG-4. */
    fun toM4a(source: File, target: File): TranscodeResult

    /** Decode anything the tool understands to PCM WAV, so the JDK can play it. */
    fun toWav(source: File, target: File): TranscodeResult
}

sealed interface TranscodeResult {
    /** [file] exists, is non-empty, and its magic bytes match what was asked for. */
    data class Produced(val file: File, val tool: String) : TranscodeResult

    /** No working encoder on this machine. [tried] names what was attempted and why each failed. */
    data class Unavailable(val tried: List<String>) : TranscodeResult {
        val message: String
            get() = if (tried.isEmpty()) {
                "no audio transcoder configured"
            } else {
                "no working audio transcoder: ${tried.joinToString("; ")}"
            }
    }

    /** A tool ran and did not deliver. Distinct from [Unavailable]: this one is a bug or a bad input. */
    data class Failed(val tool: String, val reason: String) : TranscodeResult
}

/** Minimal process seam, so the tool table can be tested without running anything. */
fun interface ProcessRunner {
    data class Outcome(val exitCode: Int, val stderr: String, val timedOut: Boolean)

    fun run(command: List<String>, timeoutSeconds: Long): Outcome
}

/**
 * Run a command with a timeout, and find a binary on `PATH`.
 *
 * `com.oshi.desktop.store.Proc` already does both — and this does NOT call it, on
 * purpose. `Proc` is `internal` and lives inside `SecretStore.kt`, a file this package
 * does not own; reaching into another owner's private helper couples a media path to a
 * keychain file, and the two have nothing to do with each other. Thirty lines here cost
 * less than that coupling.
 *
 * The two things that matter and are easy to get wrong are both copied deliberately:
 * **stdin is closed** (an `ffmpeg` that decides to prompt otherwise hangs until the
 * timeout), and **both pipes are drained on their own threads** (a child that fills the
 * stderr buffer while the parent blocks on stdout deadlocks, and `ffmpeg` is very chatty
 * on stderr).
 */
internal object AudioProc {

    fun run(command: List<String>, timeoutSeconds: Long): ProcessRunner.Outcome = try {
        val p = ProcessBuilder(command).start()
        p.outputStream.close()
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread { runCatching { p.inputStream.reader().use { out.append(it.readText()) } } }
            .apply { isDaemon = true; start() }
        val tErr = Thread { runCatching { p.errorStream.reader().use { err.append(it.readText()) } } }
            .apply { isDaemon = true; start() }
        val finished = p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            ProcessRunner.Outcome(-1, err.toString(), timedOut = true)
        } else {
            tOut.join(2_000)
            tErr.join(2_000)
            // stdout is folded in because afconvert reports some failures there.
            ProcessRunner.Outcome(p.exitValue(), (err.toString() + out.toString()), timedOut = false)
        }
    } catch (e: Exception) {
        ProcessRunner.Outcome(-1, "${e.javaClass.simpleName}: ${e.message}", timedOut = false)
    }

    /** `which`, without a shell. Null when the name is not an executable file on `PATH`. */
    fun which(binary: String): String? {
        val path = System.getenv("PATH") ?: return null
        val separator = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) ";" else ":"
        for (dir in path.split(separator)) {
            if (dir.isEmpty()) continue
            val f = File(dir, binary)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        return null
    }
}

/**
 * One external tool and the two command lines it is driven with.
 *
 * The argument lists are not incidental — they pin the output to what the phones record:
 * 44 100 Hz, mono, AAC-LC at 128 kbit/s (Android's `setAudioEncodingBitRate(128000)`,
 * `AudioRecorderManager.kt:144`). Leaving them off lets each tool pick its own defaults
 * and produces a file that differs from a phone's for no reason.
 */
data class TranscodeTool(
    val name: String,
    val binary: String,
    val encodeArgs: (src: File, dst: File) -> List<String>,
    val decodeArgs: (src: File, dst: File) -> List<String>,
) {
    companion object {
        /**
         * `afconvert -f m4af -d aac@44100 -b 128000 -c 1` — verified on this machine to
         * produce `ftypM4A `, `1 ch, 44100 Hz, aac`, i.e. the same shape `AVAudioRecorder`
         * writes on an iPhone.
         *
         * The decode direction targets WAVE/LEI16 because that is what
         * `AudioSystem.getAudioInputStream` reads back without a conversion provider —
         * confirmed against a real afconvert output (it emits `WAVE_FORMAT_EXTENSIBLE`,
         * which the JDK's `WaveExtensibleFileReader` handles).
         */
        val AFCONVERT = TranscodeTool(
            name = "afconvert",
            binary = "afconvert",
            encodeArgs = { src, dst ->
                listOf(
                    "afconvert", "-f", "m4af",
                    "-d", "aac@${VoiceNoteFormat.SAMPLE_RATE}",
                    "-b", VoiceNoteFormat.AAC_BITRATE.toString(),
                    "-c", VoiceNoteFormat.CHANNELS.toString(),
                    src.absolutePath, dst.absolutePath,
                )
            },
            decodeArgs = { src, dst ->
                listOf("afconvert", "-f", "WAVE", "-d", "LEI16", src.absolutePath, dst.absolutePath)
            },
        )

        /**
         * `-nostdin` matters: without it ffmpeg inherits the terminal, and a prompt it
         * decides to print (an existing output file, a stream choice) hangs the caller
         * until the timeout instead of failing. `-y` for the same reason.
         */
        val FFMPEG = TranscodeTool(
            name = "ffmpeg",
            binary = "ffmpeg",
            encodeArgs = { src, dst ->
                listOf(
                    "ffmpeg", "-nostdin", "-y", "-loglevel", "error", "-i", src.absolutePath,
                    "-vn",
                    "-ac", VoiceNoteFormat.CHANNELS.toString(),
                    "-ar", VoiceNoteFormat.SAMPLE_RATE.toString(),
                    "-c:a", "aac",
                    "-b:a", "${VoiceNoteFormat.AAC_BITRATE / 1000}k",
                    "-f", "mp4", dst.absolutePath,
                )
            },
            decodeArgs = { src, dst ->
                listOf(
                    "ffmpeg", "-nostdin", "-y", "-loglevel", "error", "-i", src.absolutePath,
                    "-vn", "-c:a", "pcm_s16le", "-f", "wav", dst.absolutePath,
                )
            },
        )

        /**
         * afconvert first, and not only because macOS is where this is developed: it is
         * the OS's own encoder, cannot be half-installed the way a Homebrew ffmpeg can,
         * and is the identical code path the iPhone uses. On Linux and Windows its
         * lookup simply misses and ffmpeg is tried.
         */
        val DEFAULTS: List<TranscodeTool> = listOf(AFCONVERT, FFMPEG)
    }
}

/**
 * @param workDir where the probe's throwaway files go. Defaults to the OSHI data dir so a
 *   probe never litters the user's cwd.
 * @param resolve turns a binary name into an absolute path, or null. Injected for tests.
 */
class SystemAudioTranscoder(
    private val tools: List<TranscodeTool> = TranscodeTool.DEFAULTS,
    private val runner: ProcessRunner = DefaultRunner,
    private val resolve: (String) -> String? = AudioProc::which,
    private val workDir: File = DesktopPaths.file("media"),
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : AudioTranscoder {

    /** Null = not probed yet. Some(null) = probed, nothing works. */
    private val probed = AtomicReference<Optional?>(null)

    private data class Optional(val tool: TranscodeTool?)

    override fun probe(): String? {
        probed.get()?.let { return it.tool?.name }
        val found = runProbe()
        probed.compareAndSet(null, Optional(found))
        return probed.get()?.tool?.name
    }

    /**
     * Encode with the first tool that delivers a real MPEG-4.
     *
     * Each candidate is tried on the ACTUAL file, not just the probe file, because a tool
     * can encode a 100 ms tone and choke on a 5-minute one (a full disk, a quota). The
     * output is then sniffed: exit code 0 is not evidence.
     */
    override fun toM4a(source: File, target: File): TranscodeResult =
        convert(source, target, VoiceNoteContainer.M4A_AAC)

    override fun toWav(source: File, target: File): TranscodeResult =
        convert(source, target, VoiceNoteContainer.WAV_PCM)

    private fun convert(source: File, target: File, want: VoiceNoteContainer): TranscodeResult {
        if (!source.isFile || source.length() == 0L) {
            return TranscodeResult.Failed("none", "source missing or empty: ${source.name}")
        }
        target.parentFile?.mkdirs()
        val tried = mutableListOf<String>()
        for (tool in tools) {
            if (resolve(tool.binary) == null) {
                tried += "${tool.name}: not on PATH"
                continue
            }
            // A stale target from a previous attempt would be mistaken for success by the
            // sniff below. ffmpeg's own -y does not help when it fails before opening it.
            runCatching { target.delete() }
            val args = if (want == VoiceNoteContainer.M4A_AAC) {
                tool.encodeArgs(source, target)
            } else {
                tool.decodeArgs(source, target)
            }
            val outcome = runner.run(args, timeoutSeconds)
            when {
                outcome.timedOut -> tried += "${tool.name}: timed out after ${timeoutSeconds}s"
                outcome.exitCode != 0 ->
                    tried += "${tool.name}: exit ${outcome.exitCode} ${outcome.stderr.trim().take(200)}"
                !target.isFile || target.length() == 0L ->
                    tried += "${tool.name}: exit 0 but produced no bytes"
                AudioContainers.of(target) != want ->
                    tried += "${tool.name}: produced ${AudioContainers.of(target) ?: "an unrecognised container"}, wanted $want"
                else -> return TranscodeResult.Produced(target, tool.name)
            }
            runCatching { target.delete() }
        }
        return TranscodeResult.Unavailable(tried)
    }

    private fun runProbe(): TranscodeTool? {
        val dir = File(workDir, "probe")
        dir.mkdirs()
        val src = File(dir, "probe-in.wav")
        val dst = File(dir, "probe-out.m4a")
        return try {
            src.writeBytes(WavWriter.tone(millis = PROBE_MILLIS))
            for (tool in tools) {
                if (resolve(tool.binary) == null) continue
                runCatching { dst.delete() }
                val outcome = runner.run(tool.encodeArgs(src, dst), timeoutSeconds)
                val ok = !outcome.timedOut && outcome.exitCode == 0 &&
                    dst.isFile && dst.length() > 0L &&
                    AudioContainers.of(dst) == VoiceNoteContainer.M4A_AAC
                if (ok) return tool
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { src.delete() }
            runCatching { dst.delete() }
        }
    }

    companion object {
        /**
         * 120 s. A 5-minute WAV encodes in well under a second on any machine that can
         * run a UI, so this is a hang guard, not a budget.
         */
        const val DEFAULT_TIMEOUT_SECONDS = 120L

        /** Long enough that no encoder rejects it as empty; short enough to be free. */
        const val PROBE_MILLIS = 100

        /** The real subprocess. See [AudioProc] for the two traps it avoids. */
        val DefaultRunner = ProcessRunner { command, timeout -> AudioProc.run(command, timeout) }
    }
}

/**
 * A RIFF/WAVE writer for the exact one format this package captures.
 *
 * `AudioSystem.write` could do this, but only from an `AudioInputStream` with a known
 * frame count, and it is the one thing in this package that would then be untestable
 * without a live capture. Forty lines of header, written here, are checkable byte by byte
 * — and the header is where a wrong `byteRate` would make every duration in the UI wrong
 * while the audio still played fine.
 */
object WavWriter {

    const val HEADER_BYTES = 44

    /** The 44-byte canonical header for [pcmBytes] of [VoiceNoteFormat.CAPTURE_FORMAT]. */
    fun header(pcmBytes: Long): ByteArray {
        val out = ByteArray(HEADER_BYTES)
        var i = 0
        fun ascii(s: String) { for (c in s) out[i++] = c.code.toByte() }
        fun le32(v: Long) {
            out[i++] = (v and 0xFF).toByte()
            out[i++] = ((v shr 8) and 0xFF).toByte()
            out[i++] = ((v shr 16) and 0xFF).toByte()
            out[i++] = ((v shr 24) and 0xFF).toByte()
        }
        fun le16(v: Int) {
            out[i++] = (v and 0xFF).toByte()
            out[i++] = ((v shr 8) and 0xFF).toByte()
        }
        ascii("RIFF")
        le32(36L + pcmBytes) // everything after this field
        ascii("WAVE")
        ascii("fmt ")
        le32(16L)
        le16(1) // WAVE_FORMAT_PCM
        le16(VoiceNoteFormat.CHANNELS)
        le32(VoiceNoteFormat.SAMPLE_RATE.toLong())
        le32(VoiceNoteFormat.BYTES_PER_SECOND.toLong())
        le16(VoiceNoteFormat.FRAME_BYTES)
        le16(VoiceNoteFormat.SAMPLE_BITS)
        ascii("data")
        le32(pcmBytes)
        return out
    }

    /** Copy [pcm] into [target], header first. Streams: a 5-minute note is 26 MB. */
    fun write(pcm: File, target: File) {
        target.parentFile?.mkdirs()
        target.outputStream().buffered().use { out ->
            out.write(header(pcm.length()))
            pcm.inputStream().buffered().use { it.copyTo(out) }
        }
    }

    /** A short 440 Hz tone, for the transcoder probe. Never played to anyone. */
    fun tone(millis: Int, hz: Double = 440.0): ByteArray {
        val frames = VoiceNoteFormat.SAMPLE_RATE * millis / 1000
        val pcm = ByteArray(frames * VoiceNoteFormat.FRAME_BYTES)
        for (n in 0 until frames) {
            val v = (12000 * kotlin.math.sin(2 * Math.PI * hz * n / VoiceNoteFormat.SAMPLE_RATE)).toInt()
            pcm[n * 2] = (v and 0xFF).toByte()
            pcm[n * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return header(pcm.size.toLong()) + pcm
    }
}
