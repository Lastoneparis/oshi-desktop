package com.oshi.desktop.call.media

/**
 * Application-level fragmentation for video — PARITY.md row 2.1.
 *
 * ```
 * [0x01][frame_id(2 BE)][fragment_index(1)][total_fragments(1)][payload ≤ 1100]
 * ```
 *
 * `OSHI/VideoCallManager.swift:421-432` specifies it, `:2404-2430` emits it and
 * `:2471-2580` reassembles; `OSHI-Android/…/VideoCallManager.kt:62-75, :1324-1345, :638-745`
 * do the same. Every video frame uses this envelope — a frame small enough to fit in one
 * datagram is sent as `total=1, idx=0`, so a receiver needs no "is this fragmented?" test.
 *
 * **Each fragment is sealed independently** ([VideoMediaFrame]), which is the point of
 * fragmenting in the application rather than letting IP do it: losing one datagram costs
 * one fragment, and tampering with one fails only that fragment's tag. IP fragmentation
 * would make the whole frame depend on every datagram arriving.
 *
 * ============================================================ THE 255-FRAGMENT CLIFF
 *
 * `total_fragments` is ONE byte, so a frame can be at most 255 × 1100 = 280,500 bytes.
 * Android clamps the count with `.coerceAtMost(255)` and then loops `0 until total`
 * (`kt:1332-1345`): everything past 280,500 bytes is never transmitted, the receiver sees
 * `total=255`, collects 255 fragments, declares the frame COMPLETE and hands a truncated
 * H.264 access unit to the decoder. A silent tail amputation that looks like a decoder
 * bug. iOS found it and refuses instead — it drops the frame and forces an IDR
 * (`swift:2385-2398`).
 *
 * [fragment] takes iOS's side and throws: a frame that cannot be sent whole is not sent.
 * Measured reality says this never fires on a real encoder — iOS's own two-device
 * measurement over 219 keyframes puts p50 at 3 fragments, p90 at 6 and the worst frame of
 * the call at 16 (`OSHI/VoiceCallManager.swift:11168-11175`) — so the cliff is 16× above
 * the worst observed frame and only a runaway bitrate reaches it.
 */
object VideoFragment {

    /** Byte 0 of every video fragment. */
    const val MAGIC: Byte = 0x01

    /** magic(1) + frame_id(2) + idx(1) + total(1). */
    const val HEADER_SIZE = 5

    /** Payload budget per fragment. Header + type + seq + nonce + tag ≈ 1142 B, under a 1200 B MTU. */
    const val MAX_PAYLOAD = 1100

    /** `total_fragments` is one byte. */
    const val MAX_FRAGMENTS = 255

    /** The largest frame this format can carry: [MAX_FRAGMENTS] × [MAX_PAYLOAD]. */
    const val MAX_FRAME_BYTES = MAX_FRAGMENTS * MAX_PAYLOAD

    /**
     * Split one frame packet into fragments.
     *
     * @throws IllegalArgumentException when [packet] exceeds [MAX_FRAME_BYTES]. See the
     *   255-fragment note on why this is an error rather than a truncation.
     */
    fun fragment(frameId: Int, packet: ByteArray): List<ByteArray> {
        require(packet.isNotEmpty()) { "an empty frame is not a frame" }
        require(packet.size <= MAX_FRAME_BYTES) {
            "frame of ${packet.size} B needs ${(packet.size + MAX_PAYLOAD - 1) / MAX_PAYLOAD} fragments; " +
                "total_fragments is one byte, so $MAX_FRAGMENTS is the ceiling — drop the frame and force an IDR"
        }
        val id = frameId and 0xFFFF
        val total = (packet.size + MAX_PAYLOAD - 1) / MAX_PAYLOAD
        val out = ArrayList<ByteArray>(total)
        for (idx in 0 until total) {
            val start = idx * MAX_PAYLOAD
            val end = minOf(start + MAX_PAYLOAD, packet.size)
            val frag = ByteArray(HEADER_SIZE + (end - start))
            frag[0] = MAGIC
            frag[1] = ((id ushr 8) and 0xFF).toByte()
            frag[2] = (id and 0xFF).toByte()
            frag[3] = (idx and 0xFF).toByte()
            frag[4] = (total and 0xFF).toByte()
            packet.copyInto(frag, HEADER_SIZE, start, end)
            out.add(frag)
        }
        return out
    }

    /** A parsed fragment header. [payload] is the rest of the datagram. */
    class Header(val frameId: Int, val index: Int, val total: Int, val payload: ByteArray)

    /** Parse a fragment, or null when the header is not one. */
    fun parse(data: ByteArray): Header? {
        if (data.size < HEADER_SIZE + 1 || data[0] != MAGIC) return null
        val frameId = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val idx = data[3].toInt() and 0xFF
        val total = data[4].toInt() and 0xFF
        if (total < 1 || idx >= total) return null
        return Header(frameId, idx, total, data.copyOfRange(HEADER_SIZE, data.size))
    }
}

/**
 * Reassembles fragments into whole frame packets.
 *
 * Not thread-safe on its own — one instance per call, driven from the receive loop, the
 * same contract [PlaybackBuffer]'s producer side has.
 *
 * ============================================================ WHAT IT REFUSES, AND WHY
 *
 * - **A fragment for a frame older than the last completed one** is dropped, using
 *   circular distance so a `frame_id` wrap at 65536 does not read as 65535 frames of
 *   lateness (`swift:2494-2506`, `kt:666-676`).
 * - **A fragment for the frame we JUST completed** is dropped too, which both phones
 *   allow through: their test is `dist > 32768`, and `dist == 0` is the id they already
 *   finished, so a duplicated single-fragment frame is decoded twice. On the phones the
 *   AES-GCM replay window catches it first ([VideoMediaFrame]); here it is refused at
 *   both layers rather than at one.
 * - **A second `total` for a frame already in flight** discards the whole entry: one of
 *   the two headers is lying and there is no way to tell which.
 * - **Any in-flight frame older than an arriving one** is discarded, because a frame
 *   whose fragments stopped arriving will never complete and holding it only delays the
 *   decoder. That discard sets [Outcome.requestKeyframe]: the peer must be asked for a
 *   fresh IDR, since our reference frame is gone. Android calls this out as a bug it
 *   shipped — the old code poked its OWN encoder instead of sending `0x0B` to the peer,
 *   so the request never reached the wire (`kt:702-712`, audit A-4 / D-7).
 */
class VideoReassembler {

    enum class Reason {
        /** Not a fragment, or a header that contradicts itself. */
        MALFORMED,

        /** For a frame already completed, or older than one. */
        LATE,

        /** Stored; the frame is not whole yet. */
        BUFFERED,

        /** Two different `total_fragments` for one `frame_id`. The entry is gone. */
        TOTAL_MISMATCH,

        /** [Outcome.frame] is a whole frame packet. */
        COMPLETE,
    }

    class Outcome(
        val reason: Reason,
        val frameId: Int = -1,
        val frame: ByteArray? = null,
        /** True when an in-flight frame was abandoned and the peer should send an IDR. */
        val requestKeyframe: Boolean = false,
    )

    private class Entry(val total: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var received = 0
    }

    private val inFlight = LinkedHashMap<Int, Entry>()
    private var lastCompleted = -1

    /** Frames handed out whole. */
    var completedFrames = 0L
        private set

    /**
     * Frames that were never completed, counted from the gaps between completed ids.
     *
     * Fragment loss is the dominant failure on UDP and it is INVISIBLE to a completion
     * callback — a frame missing one of its fragments simply never fires. iOS added the
     * same gap counter for that reason and named the hole in its comment: "fragment loss
     * was NEVER detected" (`swift:467-497`).
     */
    var lostFrames = 0L
        private set

    /** Completed ÷ (completed + lost), as a percentage. 100 when nothing has arrived yet. */
    fun completionRate(): Double {
        val seen = completedFrames + lostFrames
        return if (seen == 0L) 100.0 else completedFrames * 100.0 / seen
    }

    fun offer(data: ByteArray): Outcome {
        val h = VideoFragment.parse(data) ?: return Outcome(Reason.MALFORMED)

        if (lastCompleted >= 0) {
            val dist = (h.frameId - lastCompleted) and 0xFFFF
            if (dist == 0 || dist > 32768) return Outcome(Reason.LATE, h.frameId)
        }

        if (h.total == 1) return complete(h.frameId, h.payload, false)

        // Abandon every in-flight frame this one is newer than.
        var abandoned = false
        val it = inFlight.entries.iterator()
        while (it.hasNext()) {
            val id = it.next().key
            if (id == h.frameId) continue
            val dist = (h.frameId - id) and 0xFFFF
            if (dist in 1..32768) { it.remove(); abandoned = true }
        }

        val entry = inFlight.getOrPut(h.frameId) { Entry(h.total) }
        if (entry.total != h.total) {
            inFlight.remove(h.frameId)
            return Outcome(Reason.TOTAL_MISMATCH, h.frameId, requestKeyframe = abandoned)
        }
        if (entry.parts[h.index] == null) {
            entry.parts[h.index] = h.payload
            entry.received++
        }
        if (entry.received < entry.total) {
            return Outcome(Reason.BUFFERED, h.frameId, requestKeyframe = abandoned)
        }

        var size = 0
        for (p in entry.parts) size += p?.size ?: 0
        val whole = ByteArray(size)
        var pos = 0
        for (p in entry.parts) if (p != null) { p.copyInto(whole, pos); pos += p.size }
        inFlight.remove(h.frameId)
        return complete(h.frameId, whole, abandoned)
    }

    private fun complete(frameId: Int, frame: ByteArray, abandoned: Boolean): Outcome {
        if (lastCompleted >= 0) {
            val gap = ((frameId - lastCompleted) and 0xFFFF) - 1
            if (gap in 1..1000) lostFrames += gap.toLong()
        }
        lastCompleted = frameId
        completedFrames++
        return Outcome(Reason.COMPLETE, frameId, frame, abandoned)
    }

    /** In-flight entries, for tests and diagnostics. */
    fun pending(): Int = inFlight.size
}
