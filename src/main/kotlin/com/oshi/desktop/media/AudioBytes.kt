package com.oshi.desktop.media

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Random access to a run of bytes, so the header parsers in [AudioDurationProbe] can be
 * fed from RAM in a test and from a 26 MB file in production without a second code path.
 *
 * The idea, and the reason it is worth a named type rather than a `ByteArray`, is
 * Android's: `VoiceNoteAudio` feeds `MediaMetadataRetriever` through a
 * `ByteArrayMediaDataSource` and keeps the index arithmetic in a separate function
 * "because that class extends an Android framework type and nothing about it can be
 * exercised off-device — but this arithmetic is the part that can be wrong"
 * (`OSHI-Android/…/ui/media/VoiceNoteAudio.kt:168-189`). Same split here.
 */
interface ByteWindow : Closeable {
    val size: Long

    /**
     * Read exactly [len] bytes at [pos] into a fresh array, or return null when the
     * window does not hold that many.
     *
     * Returning null rather than a short array is the point: every caller in
     * [AudioDurationProbe] is reading a fixed-width header field, and a header field that
     * is half-present is not a header field. A truncated file must degrade to "no
     * duration", never to a duration computed from whatever bytes happened to be there.
     */
    fun read(pos: Long, len: Int): ByteArray?

    override fun close() {}
}

/** A [ByteWindow] over a byte array. Used by tests and by any in-RAM caller. */
class ArrayWindow(private val data: ByteArray) : ByteWindow {
    override val size: Long get() = data.size.toLong()

    override fun read(pos: Long, len: Int): ByteArray? {
        if (len < 0 || pos < 0 || pos + len > data.size) return null
        return data.copyOfRange(pos.toInt(), pos.toInt() + len)
    }
}

/** A [ByteWindow] over a file. Seeks; never reads the whole thing. */
class FileWindow(file: File) : ByteWindow {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun read(pos: Long, len: Int): ByteArray? {
        if (len < 0 || pos < 0 || pos + len > size) return null
        val buf = ByteArray(len)
        raf.seek(pos)
        raf.readFully(buf)
        return buf
    }

    override fun close() { runCatching { raf.close() } }
}

/**
 * Container identification by CONTENT, never by extension.
 *
 * This is not fussiness. Both phones rename an inbound note to `.m4a` before handing it
 * to their decoder — Android to `voice_<id>.m4a` (`AudioRecorderManager.kt:621`), iOS to
 * `playback_<uuid>.m4a` (`AudioMessageView.swift:195`) — so on the receiving side the
 * extension carries no information at all and only the magic bytes do. It is also how
 * this client verifies that an external encoder actually produced what it claimed: a
 * transcoder that exits 0 and leaves a 0-byte file, or a WAV, is a transcoder that
 * failed, and only the bytes say so.
 */
object AudioContainers {

    /** `RIFF` … `WAVE`. */
    fun isWav(head: ByteArray): Boolean =
        head.size >= 12 &&
            head.tag(0) == "RIFF" &&
            head.tag(8) == "WAVE"

    /**
     * An ISO base-media file: a `ftyp` box at offset 4.
     *
     * The brand is deliberately NOT checked. afconvert stamps `M4A `, Android's
     * `MediaMuxer` stamps `mp42` or `isom`, and a phone that changes its muxer must not
     * turn into an unrecognised container here.
     */
    fun isMp4(head: ByteArray): Boolean = head.size >= 8 && head.tag(4) == "ftyp"

    fun of(head: ByteArray): VoiceNoteContainer? = when {
        isMp4(head) -> VoiceNoteContainer.M4A_AAC
        isWav(head) -> VoiceNoteContainer.WAV_PCM
        else -> null
    }

    /** Sniffs a file's first 16 bytes. Null when the file is missing, empty or unknown. */
    fun of(file: File): VoiceNoteContainer? =
        runCatching {
            FileWindow(file).use { w -> w.read(0, 16)?.let { of(it) } }
        }.getOrNull()

    private fun ByteArray.tag(at: Int): String =
        String(this, at, 4, Charsets.US_ASCII)
}

/** Byte-level PCM arithmetic. Pure, and therefore the part that gets tested. */
object Pcm {

    /**
     * Peak absolute sample of signed 16-bit little-endian PCM in `[offset, offset+length)`.
     *
     * **This is the load-bearing number in the whole package**, because it is the only
     * thing that separates "the microphone recorded silence" from "the microphone is not
     * recording at all". On macOS a process without the Microphone TCC grant does not get
     * an exception when it opens a line — it gets a line that reads perfectly, forever,
     * returning zeros. Byte counts look right. File size looks right. The duration looks
     * right. Only the sample values are wrong, and they are wrong in a way no length
     * check can see. See [AudioRecorder.stop].
     *
     * `-32768` has no positive counterpart in the range, so its absolute value is
     * clamped to 32767 rather than overflowing back to itself.
     */
    fun peakAbs16(buffer: ByteArray, offset: Int, length: Int): Int {
        val start = offset.coerceAtLeast(0)
        if (start >= buffer.size) return 0
        val available = buffer.size - start
        // Whole frames only. A trailing odd byte is half a sample and reading it as a
        // whole one invents a value that was never captured.
        val usable = minOf(length.coerceAtLeast(0), available) and 1.inv()
        val end = start + usable
        var peak = 0
        var i = start
        while (i + 2 <= end) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt() // signed on purpose: this IS the sample's sign
            val sample = (hi shl 8) or lo
            val abs = if (sample < 0) minOf(-sample, 32767) else sample
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }
}
