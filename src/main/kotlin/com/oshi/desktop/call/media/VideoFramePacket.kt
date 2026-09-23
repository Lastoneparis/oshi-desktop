package com.oshi.desktop.call.media

/**
 * One complete video frame, as the shipped clients put it on the wire — PARITY.md row 2.1.
 *
 * ```
 * [flags(1)][timestamp(8 LE)][spsLen(2 LE)][sps][ppsLen(2 LE)][pps][frameData]
 * ```
 *
 * `OSHI/VideoCallManager.swift:2315-2352` builds it and `:2600-2660` parses it;
 * `OSHI-Android/…/service/VideoCallManager.kt:1243-1312` builds the same bytes and
 * `:754` parses them. This is the payload that row [VideoFragment] chops into ≤1100-byte
 * pieces, and it is what comes back out after reassembly.
 *
 * ============================================================ LITTLE-ENDIAN, AND WHY
 *
 * Every multi-byte field in THIS packet is little-endian, which is not the project's
 * habit: the relay envelope (row 0.2), the media envelope's sequence number and the video
 * fragment's `frame_id` are all big-endian. The reason is that iOS never chose an
 * endianness here — it writes `Data(bytes: &timestamp, count: 8)` over a native `UInt64`
 * (`swift:2332-2333`) and reads `load(as: UInt16.self)` back (`:2634`), so the format is
 * whatever ARM64 happens to be. Android matched it by setting
 * `packet.order(LITTLE_ENDIAN)` with the comment *"Match iOS native byte order"*
 * (`kt:1284`). So little-endian it is, and it is written out explicitly here rather than
 * inherited from a platform.
 *
 * ============================================================ THE PARAMETER SETS ARRIVE TWICE
 *
 * `sps`/`pps` are carried in the header fields AND again inside `frameData`, prefixed as
 * AVCC NAL units with 4-byte BIG-endian lengths (`swift:2217-2221`, `kt:1259-1281`) — two
 * different length encodings for the same two byte strings in one packet. That is not a
 * mistake to clean up: iOS's receiver prefers the EMBEDDED copy over the header copy
 * (`swift:2669-2670`, "embedded > metadata > cached > local"), because the header copy is
 * whatever the sender had cached when it built the packet while the embedded copy is what
 * the encoder actually emitted for THIS frame. [enrich] therefore writes both, and
 * [bestParameterSets] reproduces the same priority order.
 *
 * ============================================================ WHAT THIS CLIENT DOES WITH IT
 *
 * It does not decode it. The JVM has no H.264 decoder, no camera, and this row adds no
 * dependency to get one — so the desktop **receives** video and hands the access units to
 * [toAnnexB], which is the shape `ffplay`, `mpv` and VLC read directly. That is the whole
 * claim: the bytes are understood, reassembled, authenticated and written out; no pixel is
 * ever drawn in this app. [encode] exists to pin the wire shape in a test and has no
 * camera behind it — nothing in `app/` calls it, deliberately.
 */
object VideoFramePacket {

    /** bit 0 of the flags byte — this frame is a keyframe (IDR). */
    const val FLAG_KEYFRAME = 0x01

    /** flags(1) + timestamp(8) + spsLen(2) + ppsLen(2), with every optional field empty. */
    const val HEADER_SIZE = 13

    /**
     * A parsed frame packet.
     *
     * [frameData] is exactly the bytes that followed the parameter-set fields — AVCC,
     * still carrying whatever the sender embedded. Use [bestParameterSets] and
     * [stripParameterSets] rather than reading it raw.
     */
    class Parsed(
        val isKeyFrame: Boolean,
        /** Clockwise rotation the DISPLAY should apply: 0/1/2/3 → 0°/90°/180°/270°. */
        val rotationCode: Int,
        /** Unix milliseconds. See [VideoFramePacket] on why this one is not an Apple epoch. */
        val timestampMs: Long,
        val sps: ByteArray?,
        val pps: ByteArray?,
        val frameData: ByteArray,
    )

    /**
     * Build one frame packet.
     *
     * [rotationCode] is the hint the RECEIVER applies. Both clients now stamp 0 because
     * both pre-rotate the buffer upright before encoding, and Android shipped the other
     * choice for a while: stamping `sensorOrientation / 90` on an already-upright frame
     * made iOS rotate it a second time, so remote video arrived sideways on every
     * Android→iOS video call (`kt:1288-1302`, audit D-12; iOS does the same and says so
     * at `swift:1709-1712`). A non-zero code here is therefore a claim that the frame is
     * NOT upright.
     */
    fun encode(
        isKeyFrame: Boolean,
        rotationCode: Int,
        timestampMs: Long,
        sps: ByteArray?,
        pps: ByteArray?,
        avccFrame: ByteArray,
    ): ByteArray {
        require(rotationCode in 0..3) { "rotation code is 2 bits: 0..3" }
        val enriched = enrich(sps, pps, avccFrame)
        val spsLen = sps?.size ?: 0
        val ppsLen = pps?.size ?: 0
        require(spsLen <= 0xFFFF && ppsLen <= 0xFFFF) { "parameter set does not fit a 16-bit length" }
        val out = ByteArray(HEADER_SIZE + spsLen + ppsLen + enriched.size)
        var o = 0
        out[o++] = (((if (isKeyFrame) FLAG_KEYFRAME else 0)) or (rotationCode shl 1)).toByte()
        for (i in 0 until 8) out[o++] = ((timestampMs ushr (i * 8)) and 0xFF).toByte()
        out[o++] = (spsLen and 0xFF).toByte()
        out[o++] = ((spsLen ushr 8) and 0xFF).toByte()
        if (sps != null) { sps.copyInto(out, o); o += sps.size }
        out[o++] = (ppsLen and 0xFF).toByte()
        out[o++] = ((ppsLen ushr 8) and 0xFF).toByte()
        if (pps != null) { pps.copyInto(out, o); o += pps.size }
        enriched.copyInto(out, o)
        return out
    }

    /**
     * Parse one frame packet, or null.
     *
     * **Stricter than iOS on a truncated parameter set.** iOS reads `spsLen`, and if the
     * declared length runs past the end of the buffer it silently leaves `spsData` nil
     * and DOES NOT ADVANCE the offset (`swift:2637-2641`), so parsing continues over the
     * SPS bytes as though they were the PPS length — a corrupt packet becomes a
     * plausible-looking frame with garbage attached rather than a rejected one. Here a
     * length that does not fit fails the packet. Nothing is lost by being strict: a frame
     * whose header cannot be trusted has nothing decodable in it either.
     *
     * The minimum is 14 bytes, not 13 — a header with no frame data behind it is not a
     * frame, and iOS rejects it the same way (`guard parseTarget.count > 13`).
     */
    fun decode(bytes: ByteArray): Parsed? {
        if (bytes.size <= HEADER_SIZE) return null
        var o = 0
        val flags = bytes[o++].toInt() and 0xFF
        val isKeyFrame = (flags and FLAG_KEYFRAME) != 0
        val rotationCode = (flags shr 1) and 0x3
        var ts = 0L
        for (i in 0 until 8) ts = ts or ((bytes[o++].toLong() and 0xFF) shl (i * 8))

        val spsLen = le16(bytes, o) ?: return null
        o += 2
        if (o + spsLen > bytes.size) return null
        val sps = if (spsLen > 0) bytes.copyOfRange(o, o + spsLen) else null
        o += spsLen

        val ppsLen = le16(bytes, o) ?: return null
        o += 2
        if (o + ppsLen > bytes.size) return null
        val pps = if (ppsLen > 0) bytes.copyOfRange(o, o + ppsLen) else null
        o += ppsLen

        if (o >= bytes.size) return null
        return Parsed(isKeyFrame, rotationCode, ts, sps, pps, bytes.copyOfRange(o, bytes.size))
    }

    private fun le16(b: ByteArray, o: Int): Int? {
        if (o + 2 > b.size) return null
        return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    }

    // ──────────────────────────────────────────────────────────────── AVCC

    /** `[len(4 BE)][sps][len(4 BE)][pps]` prepended to [avccFrame], when both exist. */
    fun enrich(sps: ByteArray?, pps: ByteArray?, avccFrame: ByteArray): ByteArray {
        if (sps == null || pps == null) return avccFrame
        val out = ByteArray(8 + sps.size + pps.size + avccFrame.size)
        var o = 0
        o = putBe32(out, o, sps.size); sps.copyInto(out, o); o += sps.size
        o = putBe32(out, o, pps.size); pps.copyInto(out, o); o += pps.size
        avccFrame.copyInto(out, o)
        return out
    }

    private fun putBe32(out: ByteArray, off: Int, v: Int): Int {
        out[off] = ((v ushr 24) and 0xFF).toByte()
        out[off + 1] = ((v ushr 16) and 0xFF).toByte()
        out[off + 2] = ((v ushr 8) and 0xFF).toByte()
        out[off + 3] = (v and 0xFF).toByte()
        return off + 4
    }

    /**
     * Walk the AVCC NAL units in [frameData], calling [visit] with (type, offset, length)
     * for each. Stops at the first length that does not fit — a truncated tail ends the
     * walk rather than throwing, because a frame that lost its last fragment is exactly
     * what this has to survive.
     */
    private inline fun walkNals(frameData: ByteArray, visit: (Int, Int, Int) -> Unit) {
        var o = 0
        while (o + 4 <= frameData.size) {
            var len = 0
            for (i in 0 until 4) len = (len shl 8) or (frameData[o + i].toInt() and 0xFF)
            // Do not add an attacker-controlled 32-bit length: `o + 4 + len` can wrap
            // negative and turn an oversized NAL into an out-of-bounds visit.
            if (len <= 0 || len > frameData.size - o - 4) return
            visit(frameData[o + 4].toInt() and 0x1F, o + 4, len)
            o += 4 + len
        }
    }

    /**
     * The NAL type of the first slice in [frameData] — 5 means IDR.
     *
     * Types 1..5 are slices; anything else (7 SPS, 8 PPS, 6 SEI) is skipped, so the answer
     * describes the picture rather than whatever the encoder happened to put first.
     * Returns -1 when there is no slice at all, where iOS falls back to reading byte 4
     * blind (`swift:2309`) and reports a parameter set as if it were a picture.
     */
    fun firstNalType(frameData: ByteArray): Int {
        var found = -1
        walkNals(frameData) { t, _, _ -> if (found < 0 && t in 1..5) found = t }
        return found
    }

    /** True when [frameData] carries an IDR slice (NAL type 5). */
    fun isIdr(frameData: ByteArray): Boolean = firstNalType(frameData) == 5

    /** The SPS (NAL 7) and PPS (NAL 8) embedded in [frameData], each without its length prefix. */
    fun embeddedParameterSets(frameData: ByteArray): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        walkNals(frameData) { t, off, len ->
            if (t == 7 && sps == null) sps = frameData.copyOfRange(off, off + len)
            if (t == 8 && pps == null) pps = frameData.copyOfRange(off, off + len)
        }
        return sps to pps
    }

    /**
     * Embedded > header > cached, which is iOS's own order (`swift:2665-2670`).
     *
     * [cachedSps]/[cachedPps] are what an earlier keyframe in the same call supplied. A
     * stream that never carried a parameter set returns nulls and is undecodable — that
     * is a true answer, not a failure to look.
     */
    fun bestParameterSets(
        parsed: Parsed,
        cachedSps: ByteArray? = null,
        cachedPps: ByteArray? = null,
    ): Pair<ByteArray?, ByteArray?> {
        val (embSps, embPps) = embeddedParameterSets(parsed.frameData)
        return (embSps ?: parsed.sps ?: cachedSps) to (embPps ?: parsed.pps ?: cachedPps)
    }

    /**
     * [frameData] with the SPS (7) and PPS (8) NAL units removed.
     *
     * **SEI (6) is kept**, which iOS calls out as deliberate (`swift:2676-2677`): the
     * decoder may need it for timing and recovery-point information, and dropping "not a
     * picture" wholesale would take it with the parameter sets.
     */
    fun stripParameterSets(frameData: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(frameData.size)
        walkNals(frameData) { t, off, len ->
            if (t != 7 && t != 8) {
                out.write(byteArrayOf(
                    ((len ushr 24) and 0xFF).toByte(), ((len ushr 16) and 0xFF).toByte(),
                    ((len ushr 8) and 0xFF).toByte(), (len and 0xFF).toByte(),
                ))
                out.write(frameData, off, len)
            }
        }
        return out.toByteArray()
    }

    /**
     * AVCC → Annex-B: every 4-byte length prefix becomes the start code `00 00 00 01`.
     *
     * This is the only thing this client can do with a decoded video stream, and it is
     * enough to be useful: the result is a `.h264` elementary stream that `ffplay`, `mpv`
     * and VLC open directly. Prepending the parameter sets is the CALLER's job — see
     * [VideoStreamSink], which writes them once ahead of the first IDR.
     */
    fun toAnnexB(frameData: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(frameData.size + 16)
        walkNals(frameData) { _, off, len ->
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(frameData, off, len)
        }
        return out.toByteArray()
    }
}
