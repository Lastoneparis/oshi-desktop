package com.oshi.desktop.call.media

/**
 * The video control lane — PARITY.md row 2.1-c.
 *
 * Five packet types ride the MEDIA channel alongside audio and video frames, and they do
 * not all ride it the same way:
 *
 * | type | meaning | body |
 * |---|---|---|
 * | `0x0B` | send me a keyframe (PLI) | `[type][timestamp 8 BE]` — **cleartext** |
 * | `0x0C` | I paused my camera | `[type][timestamp 8 BE]` — **cleartext** |
 * | `0x0D` | I resumed my camera | `[type][timestamp 8 BE]` — **cleartext** |
 * | `0x0E` | upgrade negotiation | media envelope, ONE sealed byte |
 * | `0x0F` | stop video | media envelope, ONE sealed byte |
 *
 * `OSHI-Android/…/EnhancedCallManager.kt:3150-3215` receives all five and `:5115-5265`,
 * `:5405-5470` send them; iOS's side is `VoiceCallManager.swift:10344-10493`.
 *
 * ============================================================ THREE OF THEM ARE CLEARTEXT
 *
 * `0x0B`, `0x0C` and `0x0D` carry no nonce, no tag and no signature. On both phones they
 * are read straight off the media socket and acted on: `0x0C` sets the remote-camera-off
 * placeholder, `0x0D` clears it AND makes the receiver ask the peer for a fresh IDR, and
 * `0x0B` calls the local encoder's force-IDR **with no inbound rate limit at all**
 * (`kt:3150-3178`). The token bucket both clients implement — one token per 350 ms,
 * burst 3 — is on the SENDING side only.
 *
 * So anyone who can put a datagram on the media port can flip a peer's camera indicator
 * or hold their encoder at keyframes indefinitely, which multiplies their uplink bitrate
 * for as long as the flood lasts. That is a property of the shipped protocol, not of this
 * client, and it is written down here because this is where the reading happened.
 *
 * This client does not inherit it. There is no encoder to force ([VideoReceiveSession]:
 * the JVM has no camera and no H.264), inbound `0x0B` is rate-limited before it is even
 * counted, and the cleartext types are allowed to change ONE display flag and nothing
 * else. No key, no state machine transition and no transport decision is reachable from
 * an unauthenticated packet.
 *
 * ============================================================ AND WE ANSWER "WATCH ONLY"
 *
 * A peer asking to upgrade to video gets `0x05` — accept, receive-only — never `0x02`.
 * `0x02` claims a camera, and the peer would sit in front of a black rectangle waiting
 * for frames that cannot exist. The cost is named rather than hidden: an Android build
 * from before `0x05` was added logs `DIAG_VUPGRADE_RX_UNKNOWN_CODE` and hangs in
 * `_isAwaitingVideoUpgrade` until its 15 s timeout reports "No answer" (`kt:193-200`).
 * A 15-second wait that ends in an accurate "no" beats an indefinite black rectangle.
 */
object VideoControl {

    /** `0x0B` — send me a keyframe. Cleartext. */
    const val KEYFRAME_REQUEST = 0x0B

    /** `0x0C` — the sender paused their camera. Cleartext. */
    const val VIDEO_PAUSED = 0x0C

    /** `0x0D` — the sender resumed their camera. Cleartext. */
    const val VIDEO_RESUMED = 0x0D

    /** `0x0E` — upgrade negotiation. Media envelope, one sealed byte. */
    const val VIDEO_UPGRADE = 0x0E

    /** `0x0F` — stop video. Media envelope, one sealed byte. Android also overloads this. */
    const val VIDEO_STOP = 0x0F

    /** type(1) + timestamp(8). */
    const val TOGGLE_SIZE = 9

    // The sealed sub-opcodes. `kt:189-202`, iOS `VoiceCallManager.swift:10748-10750`.
    const val VUPG_REQUEST = 0x01
    const val VUPG_ACCEPT = 0x02
    const val VUPG_DECLINE = 0x03
    const val VUPG_STOP = 0x04

    /** `0x05` — accept, receive-only. What this client answers with, always. */
    const val VUPG_ACCEPT_WATCH = 0x05
    const val VUPG_CAMERA_ON = 0x06
    const val VUPG_CAMERA_OFF = 0x07

    /**
     * OUTBOUND requests on the MEDIA channel: one token per 500 ms, burst 2 (≤ 2/s — the
     * rate a peer's encoder acts on). The sealed SIGNAL-lane copy is held to ≤ 1/s on top
     * ([com.oshi.desktop.call.video.CallVideoSession]), as on iOS and Android
     * (__CALL_VIDEO_SIGNAL_2026_09_23__; was iOS's 350 ms / burst 3, ≈ 170/min). One IDR
     * answers every request raised while it is in flight.
     */
    const val KEYFRAME_MIN_INTERVAL_MS = 500L
    const val KEYFRAME_BURST = 2

    /**
     * __VIDEO_ABR_2026_09_23__ INBOUND `0x0B` acted on at most once per 500 ms (~2/s), no
     * burst: one IDR serves every request raised while it is in flight, and a cleartext
     * flood can no longer hold the encoder at keyframes. The same rate on all three clients.
     */
    const val INBOUND_KEYFRAME_MIN_INTERVAL_MS = 500L
    const val INBOUND_KEYFRAME_BURST = 1

    /** True for the three types whose body is `[type][timestamp 8 BE]` in the clear. */
    fun isCleartextToggle(type: Int): Boolean =
        type == KEYFRAME_REQUEST || type == VIDEO_PAUSED || type == VIDEO_RESUMED

    /**
     * `[type][timestamp 8 BE]`.
     *
     * The timestamp is Unix MILLISECONDS, big-endian — a fifth epoch/encoding pair in the
     * call path, and the only one that is neither inside an AEAD nor covered by a
     * signature. Nothing may be decided from it; it is emitted because the shipped
     * receivers expect nine bytes.
     */
    fun encodeToggle(type: Int, nowMs: Long): ByteArray {
        require(isCleartextToggle(type)) { "0x%02X is not a cleartext toggle".format(type) }
        val out = ByteArray(TOGGLE_SIZE)
        out[0] = type.toByte()
        for (i in 0 until 8) out[1 + i] = ((nowMs ushr (56 - i * 8)) and 0xFF).toByte()
        return out
    }

    class Toggle(val type: Int, val timestampMs: Long)

    /** Parse a cleartext toggle, or null. Exactly nine bytes — a longer packet is media. */
    fun decodeToggle(data: ByteArray): Toggle? {
        if (data.size != TOGGLE_SIZE) return null
        val type = data[0].toInt() and 0xFF
        if (!isCleartextToggle(type)) return null
        var ts = 0L
        for (i in 1 until 9) ts = (ts shl 8) or (data[i].toLong() and 0xFF)
        return Toggle(type, ts)
    }

    /**
     * Seal one upgrade sub-opcode into a media envelope.
     *
     * The counter comes from the SHARED audio sequence, not a private one — Android says
     * why in as many words: *"Increment shared audio counter so this packet's nonce is
     * globally unique alongside audio packets — same key, never the same (salt+counter)"*
     * (`kt:5413-5415`). A private counter here would reuse audio's nonces under audio's
     * key, which is the failure the whole nonce scheme exists to prevent.
     */
    fun encodeUpgrade(
        sessionKey: ByteArray,
        baseSalt: ByteArray,
        isCaller: Boolean,
        sharedAudioSeq: Long,
        type: Int,
        code: Int,
    ): ByteArray {
        require(type == VIDEO_UPGRADE || type == VIDEO_STOP) { "0x%02X is not an upgrade type".format(type) }
        return CallMediaFrame.encode(
            sessionKey, baseSalt, isCaller, sharedAudioSeq, type, byteArrayOf(code.toByte()),
        )
    }

    class Upgrade(val type: Int, val code: Int, val seq: Long)

    /** Open an upgrade packet, or null. A body that is not exactly one byte is not one. */
    fun decodeUpgrade(sessionKey: ByteArray, frame: ByteArray): Upgrade? {
        if (frame.isEmpty()) return null
        val type = frame[0].toInt() and 0xFF
        if (type != VIDEO_UPGRADE && type != VIDEO_STOP) return null
        val opened = CallMediaFrame.decode(sessionKey, frame) ?: return null
        if (opened.pcm.size != 1) return null
        return Upgrade(type, opened.pcm[0].toInt() and 0xFF, opened.seq)
    }
}

/**
 * What the video control lane says about the far end, and what has to be sent back.
 *
 * One instance per call. Not thread-safe; drive it from the receive loop, like
 * [VideoReassembler].
 *
 * Every method returns the packets the CALLER must put on the wire. This class sends
 * nothing itself, for the reason Android had to fix in its own client: its keyframe
 * request called the LOCAL encoder, so the request never reached the wire while the peer
 * went on sending P-frames into a broken decoder (`kt:702-712`, audit A-4 / D-7). A
 * decision that cannot be seen leaving is indistinguishable from no decision.
 */
class VideoControlSession(private val nowMs: () -> Long) {

    /** The peer says their camera is off. A DISPLAY fact, and nothing more — it arrives unauthenticated. */
    var remoteCameraPaused = false
        private set

    /** The peer asked to move this call to video and has not been answered yet. */
    var upgradeRequested = false
        private set

    /** We asked the peer to move to video and have not heard back. */
    var awaitingUpgrade = false
        private set

    /** The peer accepted an upgrade but is receive-only — no frames will arrive from them. */
    var peerWatchOnly = false
        private set

    /** Video is live on this call, by either side's account. */
    var videoActive = false
        private set

    /** Inbound `0x0B` we refused to even count, because they arrived faster than the bucket. */
    var inboundKeyframeFloodDrops = 0L
        private set

    /** Inbound `0x0B` accepted (≤ 1 per 500 ms). [CallVideoSession] forces an IDR for each. */
    var inboundKeyframeRequests = 0L
        private set

    private var outboundTokens = VideoControl.KEYFRAME_BURST.toDouble()
    private var lastOutboundRequestMs = 0L
    private var inboundTokens = VideoControl.INBOUND_KEYFRAME_BURST.toDouble()
    private var lastInboundRequestMs = 0L

    /** Requests the caller was told to send but which the bucket swallowed. */
    var outboundKeyframeSwallowed = 0L
        private set

    /**
     * Handle one cleartext toggle.
     *
     * @return the packets to send. Non-empty for `0x0D` only: a peer that resumed its
     *   camera is sending P-frames against a picture we no longer have, so the fresh IDR
     *   has to be asked for. Both clients do the same (`kt:3157-3167`).
     */
    fun onToggle(t: VideoControl.Toggle): List<ByteArray> = when (t.type) {
        VideoControl.VIDEO_PAUSED -> { remoteCameraPaused = true; emptyList() }
        VideoControl.VIDEO_RESUMED -> {
            remoteCameraPaused = false
            requestKeyframe()
        }
        VideoControl.KEYFRAME_REQUEST -> {
            // Counted here; the caller forces the IDR ([CallVideoSession]) when the count moved.
            if (!admitInboundKeyframe()) inboundKeyframeFloodDrops++ else inboundKeyframeRequests++
            emptyList()
        }
        else -> emptyList()
    }

    /**
     * Handle one sealed upgrade packet.
     *
     * An inbound REQUEST is never answered automatically. Both phones raise a banner and
     * wait for a person (`kt:3201-3222`), and a client that auto-accepted would put its
     * user in a video call they did not agree to. The answer is [acceptUpgradeAsWatcher]
     * or [declineUpgrade], called by whatever asked them.
     */
    fun onUpgrade(u: VideoControl.Upgrade): List<ByteArray> {
        if (u.type == VideoControl.VIDEO_STOP) return stop()
        return when (u.code) {
            VideoControl.VUPG_REQUEST -> {
                if (videoActive) return emptyList()
                upgradeRequested = true
                emptyList()
            }
            VideoControl.VUPG_ACCEPT, VideoControl.VUPG_ACCEPT_WATCH -> {
                awaitingUpgrade = false
                peerWatchOnly = u.code == VideoControl.VUPG_ACCEPT_WATCH
                videoActive = true
                emptyList()
            }
            VideoControl.VUPG_DECLINE -> { awaitingUpgrade = false; emptyList() }
            VideoControl.VUPG_STOP -> stop()
            VideoControl.VUPG_CAMERA_ON -> { remoteCameraPaused = false; emptyList() }
            VideoControl.VUPG_CAMERA_OFF -> { remoteCameraPaused = true; emptyList() }
            // An unknown sub-opcode is not an upgrade. Say nothing rather than guess:
            // answering 0x02 to a code we did not understand would claim a camera.
            else -> { unknownCodes++; emptyList() }
        }
    }

    /** Sub-opcodes neither client documents. Counted, never acted on. */
    var unknownCodes = 0L
        private set

    /**
     * Answer a pending upgrade request with **accept-receive-only**, never plain accept.
     *
     * @return the sub-opcode to seal and send, or null when nothing is pending.
     */
    fun acceptUpgradeAsWatcher(): Int? {
        if (!upgradeRequested) return null
        upgradeRequested = false
        videoActive = true
        return VideoControl.VUPG_ACCEPT_WATCH
    }

    /** Decline a pending upgrade. */
    fun declineUpgrade(): Int? {
        if (!upgradeRequested) return null
        upgradeRequested = false
        return VideoControl.VUPG_DECLINE
    }

    private fun stop(): List<ByteArray> {
        videoActive = false
        upgradeRequested = false
        awaitingUpgrade = false
        peerWatchOnly = false
        remoteCameraPaused = false
        return emptyList()
    }

    /**
     * Ask the peer for a keyframe, if the bucket allows it.
     *
     * @return one packet, or nothing. [VideoReceiveSession] decides WHEN this is needed;
     *   this decides how often it may be said.
     */
    fun requestKeyframe(): List<ByteArray> {
        val now = nowMs()
        val elapsed = now - lastOutboundRequestMs
        if (elapsed > 0) {
            outboundTokens = minOf(
                VideoControl.KEYFRAME_BURST.toDouble(),
                outboundTokens + elapsed.toDouble() / VideoControl.KEYFRAME_MIN_INTERVAL_MS,
            )
        }
        if (outboundTokens < 1.0) { outboundKeyframeSwallowed++; return emptyList() }
        outboundTokens -= 1.0
        lastOutboundRequestMs = now
        return listOf(VideoControl.encodeToggle(VideoControl.KEYFRAME_REQUEST, now))
    }

    private fun admitInboundKeyframe(): Boolean {
        val now = nowMs()
        val elapsed = now - lastInboundRequestMs
        if (elapsed > 0) {
            inboundTokens = minOf(
                VideoControl.INBOUND_KEYFRAME_BURST.toDouble(),
                inboundTokens + elapsed.toDouble() / VideoControl.INBOUND_KEYFRAME_MIN_INTERVAL_MS,
            )
        }
        if (inboundTokens < 1.0) return false
        inboundTokens -= 1.0
        lastInboundRequestMs = now
        return true
    }
}
