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
    /** Frames to wait before asking for another IDR. ~1 s at 30 fps. */
    private val keyframeRequestIntervalFrames: Int = 30,
) {

    private val replay = NonceReplayWindow()
    private val reassembler = VideoReassembler()

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

    /**
     * The result of one datagram.
     *
     * [requestKeyframe] is a REQUEST FOR THE PEER — packet type `0x0B` on the wire, empty
     * body. It is not a poke at a local encoder: Android shipped that mistake, calling its
     * own `requestKeyFrame()` so the request never reached the wire while the peer went on
     * sending P-frames into a broken decoder (`kt:702-712`, audit A-4 / D-7). Sending it
     * is the caller's job; this class only says when.
     */
    class Result(
        val delivered: Boolean,
        val isKeyFrame: Boolean = false,
        val frameId: Int = -1,
        val requestKeyframe: Boolean = false,
        /** The Annex-B access unit, when one was produced. Also written to the sink. */
        val annexB: ByteArray? = null,
    )

    /** Feed one `0xF1` envelope straight off the transport. */
    fun onPacket(envelope: ByteArray): Result {
        val opened = VideoMediaFrame.decode(sessionKey, envelope)
        if (opened == null) { rejected++; return Result(false) }
        if (!replay.accept(opened.nonce)) { replayed++; return Result(false) }
        if (sinceKeyframeRequest < Int.MAX_VALUE) sinceKeyframeRequest++

        val outcome = reassembler.offer(opened.fragment)
        val abandoned = outcome.requestKeyframe
        if (outcome.reason != VideoReassembler.Reason.COMPLETE || outcome.frame == null) {
            return Result(false, requestKeyframe = throttle(abandoned))
        }

        val parsed = VideoFramePacket.decode(outcome.frame)
        if (parsed == null) { rejected++; return Result(false, requestKeyframe = throttle(abandoned)) }
        lastRotationCode = parsed.rotationCode

        val (sps, pps) = VideoFramePacket.bestParameterSets(parsed, cachedSps, cachedPps)
        if (sps != null) cachedSps = sps
        if (pps != null) cachedPps = pps

        val body = VideoFramePacket.stripParameterSets(parsed.frameData)
        val idr = VideoFramePacket.isIdr(parsed.frameData) || parsed.isKeyFrame

        if (!sawIdr) {
            if (!idr || sps == null || pps == null) {
                droppedBeforeIdr++
                return Result(false, requestKeyframe = throttle(true))
            }
            sink?.writeParameterSets(sps, pps)
            writtenSps = sps; writtenPps = pps
            sawIdr = true
        } else if (idr && sps != null && pps != null &&
            (!sps.contentEquals(writtenSps) || !pps.contentEquals(writtenPps))
        ) {
            // The peer's encoder was rebuilt — an iPhone recreates VideoToolbox on every
            // rotation (`swift:1666`), so 360×640 becomes 640×360 mid-call. A decoder that
            // keeps the first SPS decodes the new IDR against the wrong geometry.
            sink?.writeParameterSets(sps, pps)
            writtenSps = sps; writtenPps = pps
        }

        val annexB = VideoFramePacket.toAnnexB(body)
        if (annexB.isEmpty()) { rejected++; return Result(false, requestKeyframe = throttle(abandoned)) }
        sink?.writeAccessUnit(annexB)
        delivered++
        return Result(true, idr, outcome.frameId, throttle(abandoned), annexB)
    }

    /**
     * At most one keyframe request per [keyframeRequestIntervalFrames] packets.
     *
     * Without it a stream that never carries an IDR asks for one on every single
     * datagram — a request storm aimed at the peer that is already struggling.
     */
    private fun throttle(want: Boolean): Boolean {
        if (!want) return false
        if (sinceKeyframeRequest < keyframeRequestIntervalFrames) return false
        sinceKeyframeRequest = 0
        return true
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
