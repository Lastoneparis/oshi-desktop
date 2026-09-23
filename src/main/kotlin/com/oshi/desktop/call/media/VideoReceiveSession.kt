package com.oshi.desktop.call.media

import java.io.Closeable
import java.io.File
import java.io.OutputStream

/**
 * The inbound half of a video call — PARITY.md row 2.1.
 *
 * ```
 * datagram → [VideoMediaFrame] open → [NonceReplayWindow] → [VideoReassembler]
 *          → [VideoFramePacket] parse → parameter sets → Annex-B → [VideoStreamSink]
 * ```
 *
 * **There is no outbound half, and that is a platform fact rather than an omission.** The
 * JDK has no camera API: `javax.sound` gave the audio path a microphone for free
 * ([CallAudioSession]), and there is no `javax.video`. Capturing a webcam on Windows and
 * Linux means a native library per platform, which is the dependency this row refuses on
 * the same grounds row 2.1 refused libwebrtc. So a desktop in a video call WATCHES.
 *
 * **And it does not display.** The JVM has no H.264 decoder either. What this class
 * produces is the authenticated, reassembled, parameter-set-corrected access units in
 * Annex-B — the shape `ffplay -`, `mpv -` and VLC read directly. The claim is exactly
 * that: the bytes are understood and written out intact; no frame is decoded and no pixel
 * is drawn in this app. Anything more honest-sounding than that would be a lie in the UI.
 *
 * One instance per call. Not thread-safe: drive it from the receive loop.
 */
class VideoReceiveSession(
    private val sessionKey: ByteArray,
    private val sink: VideoStreamSink? = null,
    /**
     * Datagrams to wait before asking for another IDR.
     *
     * __VIDEO_REORDER_2026_09_23__ 30 → 8. The request is now raised only for a reference
     * frame that is truly lost (not for a reordered one), and the control session's token
     * bucket still bounds what reaches the wire; at 30 datagrams (~5 frames of a 1 Mbit/s
     * stream) a second loss inside the same second went unanswered until the peer's GOP.
     */
    private val keyframeRequestIntervalFrames: Int = 8,
    clock: () -> Long = System::currentTimeMillis,
) {

    private val replay = NonceReplayWindow()
    private val reassembler = VideoReassembler(clock)

    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null
    private var sawIdr = false
    private var writtenSps: ByteArray? = null
    private var writtenPps: ByteArray? = null
    private var sinceKeyframeRequest = Int.MAX_VALUE

    /** Envelopes that were not video, or did not authenticate. */
    var rejected = 0L
        private set

    /** Envelopes refused by the nonce window. */
    var replayed = 0L
        private set

    /** Whole frames handed to the sink. */
    var delivered = 0L
        private set

    /**
     * Frames dropped because no IDR has arrived yet.
     *
     * A P-frame before the first keyframe references a picture the receiver never had, so
     * decoding it produces garbage. iOS gates on the same `hasDecodedIDR`
     * (`OSHI/VideoCallManager.swift:270`); the difference is that iOS has a decoder to
     * gate and this has a file.
     */
    var droppedBeforeIdr = 0L
        private set

    /** Frames that never completed, inferred from `frame_id` gaps. */
    val lostFrames: Long get() = reassembler.lostFrames

    /** Completed ÷ (completed + lost), as a percentage. */
    fun completionRate(): Double = reassembler.completionRate()

    /** One frame released to the decoder. */
    class DeliveredFrame(val frameId: Int, val isKeyFrame: Boolean, val annexB: ByteArray)

    /**
     * The result of one datagram.
     *
     * [requestKeyframe] is a REQUEST FOR THE PEER — packet type `0x0B` on the wire, empty
     * body. It is not a poke at a local encoder: Android shipped that mistake, calling its
     * own `requestKeyFrame()` so the request never reached the wire while the peer went on
     * sending P-frames into a broken decoder (`kt:702-712`, audit A-4 / D-7). Sending it
     * is the caller's job; this class only says when.
     *
     * One datagram may release SEVERAL frames (a late fragment that completes a held-back
     * frame lets the ones queued behind it go too): [frames] has them all, in decode
     * order; [delivered]/[isKeyFrame]/[frameId]/[annexB] describe the first.
     */
    class Result(
        val delivered: Boolean,
        val isKeyFrame: Boolean = false,
        val frameId: Int = -1,
        val requestKeyframe: Boolean = false,
        /** The Annex-B access unit, when one was produced. Also written to the sink. */
        val annexB: ByteArray? = null,
        val frames: List<DeliveredFrame> = emptyList(),
    )

    /** Feed one `0xF1` envelope straight off the transport. */
    fun onPacket(envelope: ByteArray): Result {
        val opened = VideoMediaFrame.decode(sessionKey, envelope)
        if (opened == null) { rejected++; return Result(false) }
        if (!replay.accept(opened.nonce)) { replayed++; return Result(false) }
        if (sinceKeyframeRequest < Int.MAX_VALUE) sinceKeyframeRequest++
        meterLoss(opened)
        return release(reassembler.offer(opened.fragment))
    }

    // __VIDEO_ABR_2026_09_23__ Datagram loss of the peer's video, from the nonce counter —
    // one per fragment, from 1, on every current sender (iOS, Android, desktop), inside the
    // AEAD so it cannot be forged. The header `seq` is useless for this (its byte order
    // depends on the phone). A new salt is a restarted sender: the meter restarts with it.
    // A counter is SETTLED only once [LOSS_LAG] newer ones have arrived, so a reordered
    // datagram still in flight is not read as lost (it was, at 5 % reordering: the
    // controller stepped a loss-free link down).
    private var meterSalt = 0
    private var meterMax = -1L
    private var meterBase = 0L
    private val meterPending = java.util.PriorityQueue<Long>()
    private var settledBound = 0L
    private var settledReceived = 0L
    private var sampleBound = 0L
    private var sampleReceived = 0L

    private fun meterLoss(o: VideoMediaFrame.Decoded) {
        if (!o.hasVideoDomainBit) return
        val salt = ((o.nonce[0].toInt() and 0xFF) shl 24) or ((o.nonce[1].toInt() and 0xFF) shl 16) or
            ((o.nonce[2].toInt() and 0xFF) shl 8) or (o.nonce[3].toInt() and 0xFF)
        val c = o.counter
        if (meterMax < 0 || salt != meterSalt) {
            meterSalt = salt; meterMax = c; meterBase = c - 1
            meterPending.clear()
            settledBound = meterBase; settledReceived = 0; sampleBound = meterBase; sampleReceived = 0
        }
        if (c <= settledBound) { settledReceived++; return } // late, but it did arrive
        meterPending.add(c)
        if (c > meterMax) meterMax = c
        val bound = meterMax - LOSS_LAG
        if (bound > settledBound) {
            settledBound = bound
            while (meterPending.isNotEmpty() && meterPending.peek() <= bound) { meterPending.poll(); settledReceived++ }
        }
    }

    /**
     * Datagram loss (%) of the peer's video since the previous call, or null when fewer
     * than [minDatagrams] have settled — too few to say anything.
     */
    fun takeLossSample(minDatagrams: Int = 20): Double? {
        if (meterMax < 0) return null
        val dExp = settledBound - sampleBound
        if (dExp < minDatagrams) return null
        val dRec = settledReceived - sampleReceived
        sampleBound = settledBound; sampleReceived = settledReceived
        return ((dExp - dRec).coerceAtLeast(0) * 100.0 / dExp)
    }

    /** Release frames whose hold window expired while no datagram arrived. */
    fun poll(): Result = release(reassembler.poll())

    private fun release(outcome: VideoReassembler.Outcome): Result {
        var wantKey = outcome.requestKeyframe
        val out = ArrayList<DeliveredFrame>(outcome.released.size)
        for (r in outcome.released) {
            val d = decodeFrame(r.frameId, r.frame)
            if (d == null) { wantKey = true; continue }
            out += d
            // An IDR delivered after a hole already IS the recovery.
            if (d.isKeyFrame) { wantKey = false; owedKeyframe = false }
        }
        val first = out.firstOrNull()
        return Result(first != null, first?.isKeyFrame ?: false, first?.frameId ?: outcome.frameId,
            throttle(wantKey), first?.annexB, out)
    }

    private fun decodeFrame(frameId: Int, frame: ByteArray): DeliveredFrame? {
        val parsed = VideoFramePacket.decode(frame)
        if (parsed == null) { rejected++; return null }
        lastRotationCode = parsed.rotationCode

        val (sps, pps) = VideoFramePacket.bestParameterSets(parsed, cachedSps, cachedPps)
        if (sps != null) cachedSps = sps
        if (pps != null) cachedPps = pps

        val body = VideoFramePacket.stripParameterSets(parsed.frameData)
        val idr = VideoFramePacket.isIdr(parsed.frameData) || parsed.isKeyFrame

        if (!sawIdr) {
            if (!idr || sps == null || pps == null) {
                droppedBeforeIdr++
                return null
            }
            sink?.writeParameterSets(sps, pps)
            writtenSps = sps; writtenPps = pps
            sawIdr = true
        } else if (idr && sps != null && pps != null &&
            (!sps.contentEquals(writtenSps) || !pps.contentEquals(writtenPps))
        ) {
            // The peer's encoder was rebuilt — an iPhone recreates VideoToolbox on every
            // rotation (`swift:1666`), and a desktop or Android sender stepping down its
            // resolution ladder does the same. A decoder that keeps the first SPS decodes
            // the new IDR against the wrong geometry.
            sink?.writeParameterSets(sps, pps)
            writtenSps = sps; writtenPps = pps
        }

        val annexB = VideoFramePacket.toAnnexB(body)
        if (annexB.isEmpty()) { rejected++; return null }
        sink?.writeAccessUnit(annexB)
        delivered++
        return DeliveredFrame(frameId, idr, annexB)
    }

    /**
     * At most one keyframe request per [keyframeRequestIntervalFrames] packets.
     *
     * Without it a stream that never carries an IDR asks for one on every single
     * datagram — a request storm aimed at the peer that is already struggling.
     */
    private fun throttle(want: Boolean): Boolean {
        // A request the throttle holds back is OWED, not forgotten: the loss that raised it
        // happened once, and no later datagram would raise it again. An IDR clears the debt.
        if (want) owedKeyframe = true
        if (!owedKeyframe) return false
        if (sinceKeyframeRequest < keyframeRequestIntervalFrames) return false
        sinceKeyframeRequest = 0
        owedKeyframe = false
        return true
    }
    private var owedKeyframe = false

    private companion object {
        /** Datagrams (~100 ms of a 1 Mbit/s stream) a counter waits before it may count as lost. */
        const val LOSS_LAG = 32L
    }

    /** The rotation the DISPLAY would have to apply, from the last frame that carried one. */
    var lastRotationCode = 0
        private set
}

/** Where a received video stream goes. */
interface VideoStreamSink {
    /** Called once, before the first access unit, with the parameter sets in Annex-B. */
    fun writeParameterSets(sps: ByteArray, pps: ByteArray)

    /** One Annex-B access unit. */
    fun writeAccessUnit(bytes: ByteArray)
}

/**
 * Writes the stream to a `.h264` Annex-B elementary stream.
 *
 * Playable with `ffplay -f h264 <file>` or `mpv <file>` once the call ends, and readable
 * while it is still being written. **Plaintext video on disk**, which is the same trade
 * row 0.13 made for the message store and is stated here for the same reason: it is a
 * decision, not an oversight. Nothing creates one of these unless a caller asks for it.
 */
class VideoFileSink(private val out: OutputStream) : VideoStreamSink, Closeable {

    constructor(file: File) : this(file.outputStream().buffered())

    override fun writeParameterSets(sps: ByteArray, pps: ByteArray) {
        out.write(START_CODE); out.write(sps)
        out.write(START_CODE); out.write(pps)
        out.flush()
    }

    override fun writeAccessUnit(bytes: ByteArray) {
        out.write(bytes)
        out.flush()
    }

    override fun close() = out.close()

    private companion object {
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
