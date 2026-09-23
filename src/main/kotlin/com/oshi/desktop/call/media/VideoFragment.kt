package com.oshi.desktop.call.media

/**
 * Application-level fragmentation for video — PARITY.md row 2.1.
 *
 * ```
 * [0x01][frame_id(2 BE)][fragment_index(1)][total_fragments(1)][payload ≤ 1100; we send ≤ 1000]
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

    /**
     * Payload budget per fragment — a SENDER choice only: every receiver (iOS, Android, this
     * one) concatenates whatever sizes arrive, so lowering it needs no peer upgrade.
     *
     * __VIDEO_MTU_2026_09_23__ 1100 → 1000. The old "1142 B, under a 1200 B MTU" counted the
     * `0xF1` envelope and forgot the carrier: the `:8089` relay adds
     * `[t][len][recip 43-44][len][sender 43-44][len][callId 36]` = 126-128 B upstream and
     * 82-84 B downstream, so a full fragment left as a 1270 B UDP payload — 1318 B on IPv6,
     * above the 1280 B minimum MTU, fragmented (and dropped) by exactly the VPNs and
     * carriers the audio path lost to (1957 B/pkt). It also broke the server's
     * `UDP_SAFE_DATAGRAM = 1200` check downstream (1225 B), so one-way video was mirrored
     * over WS as well. At 1000: 1042 B envelope, ≤ 1170 B relay-up, ≤ 1126 B relay-down,
     * ≤ 1218 B on IPv6. Cost: ~+9 % datagrams, header overhead 3.8 % → 4.2 %.
     */
    const val MAX_PAYLOAD = 1000

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
 * Reassembles fragments into whole frame packets — a thin desktop face on the shared
 * [com.oshi.messenger.service.VideoReorderReassembler] (the Android file, compiled here).
 *
 * __VIDEO_REORDER_2026_09_23__ This used to hold ONE frame in flight, like both phones:
 * the first fragment of frame N+1 abandoned an unfinished N and asked for a keyframe, and
 * the late fragments of N were then refused. One datagram overtaking another across a
 * frame boundary cost a frame and an IDR exactly as a loss did (`VideoLossBench`, 5 %
 * reordering, no loss: 82.6 % complete, 59 % clean). Now:
 *
 * - fragments are kept per `frame_id` and a frame completes in any arrival order;
 * - whole frames are RELEASED in `frame_id` order (a P-frame decoded before its
 *   reference is garbage) — [Outcome.released], possibly several per datagram;
 * - a missing frame is waited for at most 2 newer frames / 100 ms, then skipped as lost
 *   ([lostFrames]); a keyframe request follows only when the next released frame is not
 *   itself a keyframe ([Outcome.requestKeyframe]);
 * - a keyframe behind a hole is released at once;
 * - anything at or before the last released id is [Reason.LATE] — including a repeat of
 *   the frame just released, which both phones used to decode twice;
 * - at most 32 unfinished frames are held, whatever order a hostile stream sends ids in.
 *
 * Not thread-safe on its own — one instance per call, driven from the receive loop.
 */
class VideoReassembler(
    clock: () -> Long = System::currentTimeMillis,
    holdFrames: Int = com.oshi.messenger.service.VideoReorderReassembler.DEFAULT_HOLD_FRAMES,
    holdMs: Long = com.oshi.messenger.service.VideoReorderReassembler.DEFAULT_HOLD_MS,
) {
    private val core = com.oshi.messenger.service.VideoReorderReassembler(holdFrames, holdMs, nowMs = clock)

    enum class Reason {
        /** Not a fragment, or a header that contradicts itself. */
        MALFORMED,

        /** For a frame already released or skipped. */
        LATE,

        /** Stored; nothing is released yet. */
        BUFFERED,

        /** Two different `total_fragments` for one `frame_id`. The entry is gone. */
        TOTAL_MISMATCH,

        /** [Outcome.frame] (and [Outcome.released]) hold whole frame packets. */
        COMPLETE,
    }

    class Released(val frameId: Int, val frame: ByteArray, val isKeyFrame: Boolean, val lostBefore: Int)

    class Outcome(
        val reason: Reason,
        val frameId: Int = -1,
        /** The FIRST released frame packet, when one was released. */
        val frame: ByteArray? = null,
        /** True when a reference frame was lost for good and the peer should send an IDR. */
        val requestKeyframe: Boolean = false,
        /** Every frame released by this datagram, in decode order. */
        val released: List<Released> = emptyList(),
    )

    /** Frames handed out whole. */
    val completedFrames: Long get() = core.completedFrames

    /** Frames skipped as lost — a missing frame never fires a completion, so it is counted here. */
    val lostFrames: Long get() = core.lostFrames

    /** Frames that completed while an older one was still missing (reordering absorbed). */
    val reorderedFrames: Long get() = core.reorderedFrames

    /** Completed ÷ (completed + lost), as a percentage. 100 when nothing has arrived yet. */
    fun completionRate(): Double = core.completionRate()

    fun offer(data: ByteArray): Outcome = map(core.offer(data))

    /** Release what the clock alone allows (the hold window expired with no new datagram). */
    fun poll(): Outcome = map(core.poll())

    private fun map(o: com.oshi.messenger.service.VideoReorderReassembler.Outcome): Outcome {
        val rel = o.released.map { Released(it.frameId, it.packet, it.isKeyFrame, it.lostBefore) }
        val reason = when (o.reason) {
            com.oshi.messenger.service.VideoReorderReassembler.Reason.MALFORMED -> Reason.MALFORMED
            com.oshi.messenger.service.VideoReorderReassembler.Reason.LATE -> Reason.LATE
            com.oshi.messenger.service.VideoReorderReassembler.Reason.BUFFERED -> Reason.BUFFERED
            com.oshi.messenger.service.VideoReorderReassembler.Reason.TOTAL_MISMATCH -> Reason.TOTAL_MISMATCH
            com.oshi.messenger.service.VideoReorderReassembler.Reason.RELEASED -> Reason.COMPLETE
        }
        val first = rel.firstOrNull()
        return Outcome(reason, first?.frameId ?: o.frameId, first?.frame, o.requestKeyframe, rel)
    }

    /** In-flight entries, for tests and diagnostics. */
    fun pending(): Int = core.pending()
}
