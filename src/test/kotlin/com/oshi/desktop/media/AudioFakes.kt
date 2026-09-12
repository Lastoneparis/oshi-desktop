package com.oshi.desktop.media

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat

/**
 * Fake audio hardware.
 *
 * Everything in this package is testable without a microphone or a speaker BECAUSE of
 * these three classes, and the one that earns its keep is [FakeCaptureLine] with
 * `sampleValue = 0`: a device that opens, reads, and returns nothing but zeros forever is
 * not a hypothetical. It is what macOS hands a process that has not been granted the
 * Microphone permission, and it is the only failure in this package that produces a
 * plausible-looking file.
 */
class FakeDevices(
    var captureSupported: Boolean = true,
    var renderSupported: Boolean = true,
    var captureNames: List<String> = listOf("Fake Microphone"),
    var openCaptureThrows: Throwable? = null,
    var openRenderThrows: Throwable? = null,
    var captureLine: () -> CaptureLine = { FakeCaptureLine(totalBytes = 0L) },
    var renderLine: () -> RenderLine = { FakeRenderLine() },
) : AudioDevices {

    val openedCaptures = mutableListOf<CaptureLine>()
    val openedRenders = mutableListOf<RenderLine>()

    override fun supportsCapture(format: AudioFormat): Boolean = captureSupported

    override fun supportsRender(format: AudioFormat): Boolean = renderSupported

    override fun captureDeviceNames(format: AudioFormat): List<String> = captureNames

    override fun openCapture(format: AudioFormat, bufferBytes: Int): CaptureLine {
        openCaptureThrows?.let { throw it }
        return captureLine().also { openedCaptures += it }
    }

    override fun openRender(format: AudioFormat, bufferBytes: Int): RenderLine {
        openRenderThrows?.let { throw it }
        return renderLine().also { openedRenders += it }
    }
}

/**
 * A microphone that delivers [totalBytes] of frames all carrying [sampleValue], then
 * blocks like a real line waiting for more audio until it is closed.
 *
 * @param totalBytes null means endless — the only way to exercise the 5-minute bound
 *   without waiting 5 minutes, because the bound is counted in BYTES.
 * @param sampleValue 0 models a macOS microphone without the TCC grant: a line that
 *   works perfectly and carries no signal.
 */
class FakeCaptureLine(
    private val totalBytes: Long?,
    private val sampleValue: Int = 4_000,
    private val throwOnRead: Throwable? = null,
) : CaptureLine {

    val closed = AtomicBoolean(false)
    private val delivered = AtomicLong(0)
    private val gate = CountDownLatch(1)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        throwOnRead?.let { throw it }
        if (closed.get()) return -1
        val remaining = totalBytes?.minus(delivered.get())
        if (remaining != null && remaining <= 0L) {
            // Exactly what a real TargetDataLine does with nothing to give: block. The
            // recorder is expected to unblock this by closing the line.
            gate.await(5, TimeUnit.SECONDS)
            return -1
        }
        val n = if (remaining == null) length else minOf(length.toLong(), remaining).toInt()
        var i = 0
        while (i + 1 < n) {
            buffer[offset + i] = (sampleValue and 0xFF).toByte()
            buffer[offset + i + 1] = ((sampleValue shr 8) and 0xFF).toByte()
            i += 2
        }
        delivered.addAndGet(n.toLong())
        return n
    }

    override fun close() {
        closed.set(true)
        gate.countDown()
    }
}

/** A speaker that counts. [writeDelayMs] models a device that plays in real time. */
class FakeRenderLine(private val writeDelayMs: Long = 0L) : RenderLine {
    val written = AtomicLong(0)
    val drained = AtomicBoolean(false)
    val flushed = AtomicBoolean(false)
    val closed = AtomicBoolean(false)

    override fun write(buffer: ByteArray, offset: Int, length: Int): Int {
        if (writeDelayMs > 0L) Thread.sleep(writeDelayMs)
        written.addAndGet(length.toLong())
        return length
    }

    override fun drain() { drained.set(true) }

    override fun flush() { flushed.set(true) }

    override fun close() { closed.set(true) }
}

/** A transcoder whose every answer is scripted. */
class FakeTranscoder(
    var probeName: String? = "fake",
    var onM4a: (File, File) -> TranscodeResult = { _, t -> TranscodeResult.Produced(t, "fake") },
    var onWav: (File, File) -> TranscodeResult = { _, t -> TranscodeResult.Produced(t, "fake") },
) : AudioTranscoder {
    override fun probe(): String? = probeName
    override fun toM4a(source: File, target: File): TranscodeResult = onM4a(source, target)
    override fun toWav(source: File, target: File): TranscodeResult = onWav(source, target)

    companion object {
        /** No encoder anywhere — a stock Linux desktop. */
        fun unavailable(): FakeTranscoder = FakeTranscoder(
            probeName = null,
            onM4a = { _, _ -> TranscodeResult.Unavailable(listOf("afconvert: not on PATH", "ffmpeg: not on PATH")) },
            onWav = { _, _ -> TranscodeResult.Unavailable(listOf("afconvert: not on PATH", "ffmpeg: not on PATH")) },
        )
    }
}

/** Byte fixtures shared by several tests. */
object AudioFixtures {

    /**
     * The first 144 bytes of a REAL `afconvert -f m4af -d aac@44100 -b 128000 -c 1`
     * output: `ftyp`(28) + `moov` header + a complete `mvhd` v0 whose timescale is 44 100
     * and whose duration is 91 136 — 2 066 ms for a 2.000 s input, the encoder's priming
     * and padding included.
     *
     * It is a real encoder's bytes and not a fixture this project invented, which is the
     * point: a parser tested only against its own idea of the format proves nothing about
     * the files it will actually meet.
     */
    val REAL_M4A_HEADER: ByteArray = hex(
        "0000001c667479704d344120000000004d3441206d70343269736f6d000004b2" +
            "6d6f6f760000006c6d76686400000000e6b734f7e6b734f70000ac4400016400" +
            "0001000001000000000000000000000000010000000000000000000000000000" +
            "0001000000000000000000000000000040000000000000000000000000000000" +
            "00000000000000000000000000000002",
    )

    /** Duration the real header above states: 91 136 / 44 100 s. */
    const val REAL_M4A_DURATION_MS = 91_136L * 1000L / 44_100L

    fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    /** A WAV of [pcmBytes] of zeroed audio, header written by the code under test. */
    fun wav(pcmBytes: Int): ByteArray = WavWriter.header(pcmBytes.toLong()) + ByteArray(pcmBytes)

    /** An MPEG-4 skeleton: `ftyp`, then the boxes given, in order. */
    fun mp4(vararg boxes: ByteArray): ByteArray {
        var out = box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII) + ByteArray(8))
        for (b in boxes) out += b
        return out
    }

    /** A 32-bit-sized box. */
    fun box(type: String, body: ByteArray): ByteArray {
        val size = 8 + body.size
        return byteArrayOf(
            ((size shr 24) and 0xFF).toByte(), ((size shr 16) and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(), (size and 0xFF).toByte(),
        ) + type.toByteArray(Charsets.US_ASCII) + body
    }

    /** A box using the 64-bit `largesize` form (`size == 1`). */
    fun largeBox(type: String, body: ByteArray): ByteArray {
        val size = 16L + body.size
        val hdr = ByteArray(16)
        hdr[3] = 1
        for (i in 0 until 4) hdr[4 + i] = type[i].code.toByte()
        for (i in 0 until 8) hdr[8 + i] = ((size shr (8 * (7 - i))) and 0xFF).toByte()
        return hdr + body
    }

    /** An `mvhd` body: version 0, 32-bit timescale and duration. */
    fun mvhdV0(timescale: Long, duration: Long): ByteArray {
        val b = ByteArray(4 + 16)
        b[0] = 0
        be32(b, 12, timescale)
        be32(b, 16, duration)
        return b
    }

    /** An `mvhd` body: version 1, 64-bit times, timescale at a DIFFERENT offset. */
    fun mvhdV1(timescale: Long, duration: Long): ByteArray {
        val b = ByteArray(4 + 28)
        b[0] = 1
        be32(b, 4 + 16, timescale)
        for (i in 0 until 8) b[4 + 20 + i] = ((duration shr (8 * (7 - i))) and 0xFF).toByte()
        return b
    }

    private fun be32(b: ByteArray, at: Int, v: Long) {
        b[at] = ((v shr 24) and 0xFF).toByte()
        b[at + 1] = ((v shr 16) and 0xFF).toByte()
        b[at + 2] = ((v shr 8) and 0xFF).toByte()
        b[at + 3] = (v and 0xFF).toByte()
    }
}
