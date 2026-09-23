package com.oshi.desktop.call.media

/**
 * The SEND half of row 2.1-v: one encoded H.264 access unit in, sealed `0xF1` datagrams out.
 *
 * Pure — no camera, no codec, no socket. The encoder hands Annex-B bytes (what FFmpeg,
 * VideoToolbox-through-FFmpeg and OpenH264 all emit), and this class produces exactly what
 * the two phones produce, so their receivers cannot tell a desktop from a phone:
 *
 * 1. **Annex-B → AVCC.** Both phones convert before framing (`VideoCallManager.kt:1276-1330`
 *    `ensureAvccFormat`; iOS encodes AVCC natively). SPS (7) and PPS (8) are lifted out and
 *    cached; access-unit delimiters (9) are dropped — iOS's `VideoCallManager.swift` NAL
 *    walker hands every NAL to `CMSampleBuffer` and an AUD in the middle is a decode error on
 *    some VideoToolbox builds.
 * 2. **SPS/PPS in EVERY packet** — both in the header fields and embedded as AVCC NALs in
 *    front of the frame data ([VideoFramePacket.encode] does both). Android: "Include SPS/PPS
 *    in EVERY packet (matching iOS — only ~21B overhead)" (`kt:1300`). A receiver that joins
 *    late, or an Android decoder that reconfigures on a resolution change, needs nothing else.
 * 3. **Rotation code 0.** The desktop camera is upright landscape; nothing to rotate. iOS
 *    sends landscape at 640×360 itself (`swift:2000-2008`), so a landscape stream is a shape
 *    both phones already decode.
 * 4. **Fragments of [VideoFragment.MAX_PAYLOAD] bytes**, frame ids mod 2¹⁶.
 * 5. **One seal per fragment** under the call key with the VIDEO nonce: fresh salt with the
 *    direction bit, counter from 1 with bit 63 set ([VideoMediaFrame]).
 *
 * A frame larger than 255 fragments is DROPPED and a keyframe is scheduled — iOS's rule,
 * not Android's silent truncation (row 2.1-v, disagreement 2).
 */
class VideoSendSession(
    private val sessionKey: ByteArray,
    private val isCaller: Boolean,
    private val send: (ByteArray) -> Boolean,
) {
    private var salt = VideoMediaFrame.newSalt(isCaller)
    private var counter = 0L
    private var frameId = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /** Set when the encoder must emit an IDR next: oversize frame, keyframe request, (re)start. */
    @Volatile var keyframeWanted: Boolean = true

    var framesSent = 0L
        private set
    var fragmentsSent = 0L
        private set
    var oversizeDropped = 0L
        private set
    var bytesSent = 0L
        private set

    /**
     * A new video session inside the same call — camera toggled back on, or video started
     * after an upgrade. The phones redraw the salt and restart the counter here
     * (`kt:5367-5376`, `swift:264-266`), and so does this; the redraw is what keeps a
     * restarted counter from replaying the previous session's nonces.
     */
    @Synchronized
    fun restart() {
        salt = VideoMediaFrame.newSalt(isCaller)
        counter = 0L
        keyframeWanted = true
    }

    /**
     * Seal and send one access unit.
     *
     * @return the number of datagrams handed to [send]; 0 when the unit was dropped.
     */
    @Synchronized
    fun sendAccessUnit(annexB: ByteArray, encoderSaysKey: Boolean, timestampMs: Long): Int {
        val nals = AnnexB.split(annexB)
        if (nals.isEmpty()) return 0
        var idr = false
        val body = java.io.ByteArrayOutputStream(annexB.size + 16)
        for (nal in nals) {
            when (nal[0].toInt() and 0x1F) {
                7 -> sps = nal
                8 -> pps = nal
                6, 9 -> Unit // SEI, access-unit delimiter: no phone needs them, and iOS feeds every NAL to VideoToolbox
                else -> {
                    if ((nal[0].toInt() and 0x1F) == 5) idr = true
                    body.write(be32(nal.size)); body.write(nal)
                }
            }
        }
        val avcc = body.toByteArray()
        if (avcc.isEmpty()) return 0
        val key = idr || encoderSaysKey
        val packet = VideoFramePacket.encode(key, 0, timestampMs, sps, pps, avcc)
        if (packet.size > VideoFragment.MAX_FRAME_BYTES) {
            oversizeDropped++
            keyframeWanted = true
            return 0
        }
        val fragments = VideoFragment.fragment(frameId, packet)
        frameId = (frameId + 1) and 0xFFFF
        var sent = 0
        for (f in fragments) {
            counter++
            val envelope = VideoMediaFrame.encode(sessionKey, salt, counter, f)
            if (runCatching { send(envelope) }.getOrDefault(false)) {
                sent++
                bytesSent += envelope.size
            }
        }
        framesSent++
        fragmentsSent += sent
        return sent
    }

    private fun be32(v: Int) = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )
}

/** Annex-B start-code splitting. Accepts 3- and 4-byte start codes. */
object AnnexB {
    fun split(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>() // (start of start code, start of NAL)
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) { starts.add(i to i + 3); i += 3; continue }
                if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts.add(i to i + 4); i += 4; continue
                }
            }
            i++
        }
        val out = ArrayList<ByteArray>(starts.size)
        for (k in starts.indices) {
            val from = starts[k].second
            var to = if (k + 1 < starts.size) starts[k + 1].first else data.size
            // Trailing zero bytes belong to the next start code's zero_byte, not this NAL.
            while (to > from && data[to - 1].toInt() == 0 && k + 1 < starts.size) to--
            if (to > from) out.add(data.copyOfRange(from, to))
        }
        return out
    }

    fun join(nals: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (n in nals) { out.write(byteArrayOf(0, 0, 0, 1)); out.write(n) }
        return out.toByteArray()
    }
}
