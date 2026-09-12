package com.oshi.desktop.media

import java.io.File

/**
 * How long is this voice note, without decoding it.
 *
 * ============================================================ WHY THIS HAS TO EXIST
 *
 * The duration is not carried anywhere else. Established in [VoiceNoteFormat]'s doc: the
 * v2 wire sends the raw container bytes and nothing beside them, iOS's `AudioMessageView`
 * falls through to "duration 0" for every inbound note
 * (`OSHI/AudioMessageView.swift:165-179`), and Android goes to the container itself with
 * `MediaMetadataRetriever` (`VoiceNoteAudio.probeDurationSeconds`,
 * `OSHI-Android/…/ui/media/VoiceNoteAudio.kt:191-205`). A desktop bubble that wants to
 * print `0:07` has exactly one source of truth and it is the file header.
 *
 * ============================================================ HEADERS ONLY
 *
 * Both parsers seek. Neither reads sample data, neither decodes, and neither loads the
 * file into RAM — a 5-minute note is 26 MB of WAV and a list of thirty of them must not
 * cost 800 MB to draw. WAV costs two seeks; MPEG-4 costs one seek per top-level box until
 * `moov`, which afconvert and `MediaMuxer` both place near the front, and which is
 * bounded at [MAX_BOXES] regardless.
 *
 * ============================================================ WHAT IT DOES NOT DO
 *
 * It does not decode AAC, so for an m4a it reports the CONTAINER's duration
 * (`mvhd.duration / mvhd.timescale`). That is the same number `MediaMetadataRetriever`
 * and `AVAsset` report, and it includes the encoder's priming/padding — an afconvert
 * encode of exactly 2.000 s reports 2.066 s (measured). Both phones show that same
 * inflated number for their own notes, so matching it is parity, not error.
 */
object AudioDurationProbe {

    /** A bound on the top-level box walk, so a malformed file cannot spin. */
    const val MAX_BOXES = 512

    /**
     * @property durationMs floor-rounded milliseconds.
     * @property sampleRate null when the container does not state it at this level
     *   (MPEG-4 keeps it in `stsd`, deeper than this parser goes).
     */
    data class Info(
        val container: VoiceNoteContainer,
        val durationMs: Long,
        val sampleRate: Int? = null,
        val channels: Int? = null,
    )

    /** Null when the file is missing, unreadable, or not a container this understands. */
    fun of(file: File): Info? =
        runCatching { FileWindow(file).use { of(it) } }.getOrNull()

    fun of(window: ByteWindow): Info? {
        val head = window.read(0, 16) ?: return null
        return when (AudioContainers.of(head)) {
            VoiceNoteContainer.WAV_PCM -> wav(window)
            VoiceNoteContainer.M4A_AAC -> mp4(window)
            null -> null
        }
    }

    // ------------------------------------------------------------------ RIFF / WAVE

    /**
     * Duration from `data` chunk size ÷ `fmt ` byte rate.
     *
     * The two traps, both of which cost a wrong number rather than an exception:
     *
     *  1. **A `data` size of 0, or one that overruns the file.** Written by anything that
     *     streams a WAV without seeking back to patch the header. The chunk is then "the
     *     rest of the file" and is treated as such.
     *  2. **`WAVE_FORMAT_EXTENSIBLE` (0xFFFE).** `afconvert -f WAVE` emits it, and it is
     *     what an inbound decode produces. It is still linear PCM and its `nAvgBytesPerSec`
     *     is still in the same place, so the format tag is not checked at all — the byte
     *     rate is, and a byte rate of zero is the only disqualifier.
     */
    fun wav(window: ByteWindow): Info? {
        var pos = 12L
        var byteRate = 0L
        var sampleRate: Int? = null
        var channels: Int? = null
        var dataBytes: Long? = null
        var boxes = 0

        while (pos + 8 <= window.size && boxes++ < MAX_BOXES) {
            val header = window.read(pos, 8) ?: break
            val id = String(header, 0, 4, Charsets.US_ASCII)
            val declared = le32(header, 4)
            val body = pos + 8

            if (id == "fmt " && declared >= 16) {
                val fmt = window.read(body, 16) ?: break
                channels = le16(fmt, 2)
                sampleRate = le32(fmt, 4).toInt()
                byteRate = le32(fmt, 8)
            } else if (id == "data") {
                val remaining = window.size - body
                // Trap 1: a size of 0, or one that cannot fit, means "to the end".
                dataBytes = if (declared <= 0L || declared > remaining) remaining else declared
                break
            }

            // RIFF chunks are word-aligned: an odd size carries one pad byte.
            val advance = declared + (declared and 1L)
            if (advance <= 0L) break
            pos = body + advance
        }

        val bytes = dataBytes ?: return null
        if (byteRate <= 0L) return null
        return Info(
            container = VoiceNoteContainer.WAV_PCM,
            durationMs = bytes * 1000L / byteRate,
            sampleRate = sampleRate,
            channels = channels,
        )
    }

    // ------------------------------------------------------------------- ISO / MPEG-4

    /**
     * Duration from `moov` → `mvhd`.
     *
     * `moov` is looked for among the TOP-LEVEL boxes and is not assumed to be first:
     * afconvert writes `ftyp moov free mdat` (verified on a real encode), but a muxer that
     * cannot seek writes `ftyp mdat moov` and a note from such a phone must still measure.
     *
     * Box sizing has three forms and all three appear in the wild: a 32-bit size, `1`
     * meaning "a 64-bit size follows", and `0` meaning "to the end of the file". A size
     * below the header length is corruption and stops the walk — without that check a
     * size of 0-after-header-adjustment walks the same offset forever.
     */
    fun mp4(window: ByteWindow): Info? {
        var pos = 0L
        var boxes = 0
        while (pos + 8 <= window.size && boxes++ < MAX_BOXES) {
            val box = readBoxHeader(window, pos) ?: return null
            if (box.type == "moov") {
                return mvhd(window, box.bodyStart, minOf(pos + box.size, window.size))
            }
            val next = pos + box.size
            if (next <= pos) return null
            pos = next
        }
        return null
    }

    private fun mvhd(window: ByteWindow, from: Long, until: Long): Info? {
        var pos = from
        var boxes = 0
        while (pos + 8 <= until && boxes++ < MAX_BOXES) {
            val box = readBoxHeader(window, pos) ?: return null
            if (box.type == "mvhd") return mvhdFields(window, box.bodyStart)
            val next = pos + box.size
            if (next <= pos) return null
            pos = next
        }
        return null
    }

    /**
     * `mvhd` version 0 packs creation/modification as 32-bit and duration as 32-bit;
     * version 1 widens all three to 64-bit and moves `timescale` from +12 to +20.
     * Reading a v1 box with v0 offsets yields a plausible-looking, entirely wrong number,
     * which is why the version byte is read first and not assumed.
     *
     * A duration of `0xFFFFFFFF` (v0) is the spec's "unknown" and becomes null rather
     * than 27 hours.
     */
    private fun mvhdFields(window: ByteWindow, bodyStart: Long): Info? {
        val versionAndFlags = window.read(bodyStart, 4) ?: return null
        val timescale: Long
        val duration: Long
        when (versionAndFlags[0].toInt() and 0xFF) {
            0 -> {
                val f = window.read(bodyStart + 4, 16) ?: return null
                timescale = be32(f, 8)
                duration = be32(f, 12)
                if (duration == 0xFFFFFFFFL) return null
            }
            1 -> {
                val f = window.read(bodyStart + 4, 28) ?: return null
                timescale = be32(f, 16)
                duration = be64(f, 20)
                if (duration < 0L) return null
            }
            else -> return null
        }
        if (timescale <= 0L || duration <= 0L) return null
        return Info(
            container = VoiceNoteContainer.M4A_AAC,
            durationMs = duration * 1000L / timescale,
        )
    }

    private data class Box(val type: String, val size: Long, val bodyStart: Long)

    private fun readBoxHeader(window: ByteWindow, at: Long): Box? {
        val header = window.read(at, 8) ?: return null
        val type = String(header, 4, 4, Charsets.US_ASCII)
        val declared = be32(header, 0)
        return when {
            declared == 1L -> {
                val large = window.read(at + 8, 8) ?: return null
                val size = be64(large, 0)
                if (size < 16L) null else Box(type, size, at + 16)
            }
            // 0 means "everything left". Legal, and only ever on the last box.
            declared == 0L -> Box(type, window.size - at, at + 8)
            declared < 8L -> null
            else -> Box(type, declared, at + 8)
        }
    }

    // -------------------------------------------------------------------- primitives

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or
            ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or
            (b[at + 3].toLong() and 0xFF)

    private fun be64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }
}
