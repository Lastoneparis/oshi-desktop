package com.oshi.desktop.call.media

import com.oshi.desktop.call.CallEndReason

/**
 * The phones' IN-BAND hang-up: a `callEnd` that rides the live media carriers (P2P, TURN,
 * `:8089`, WebSocket) instead of the signalling server.
 *
 * ```
 * [0x0D][seq(8 BE)][nonce(12) ‖ ciphertext ‖ tag(16)]      plaintext = end reason, UTF-8
 * ```
 *
 * Byte for byte the shape of an audio frame ([CallMediaFrame]), under the call's session key
 * and drawing its counter from the SHARED audio sequence, so its nonce can never collide with
 * an audio or video-control nonce. iOS `VoiceCallManager.swift` `CALL_END_INBAND_TYPE`,
 * `sendInBandCallEnd` (`:11438-11475`, a burst of 3, each freshly sealed) and the receive arm
 * in `receiveAudio` (`:12749-12780`); Android `EnhancedCallManager.kt` `CALL_END_INBAND_TYPE`
 * (`:103-117`), `sendInBandCallEnd` (`:6041-6104`) and the receive arm (`:3483-3512`).
 *
 * It exists because the signalled `callEnd` can be lost — a zombie WebSocket, a poll that
 * never returns — and a peer that never hears it stays "connected" to nobody. Before this,
 * the desktop dropped the phones' in-band copy (it failed the audio session's type check and
 * was counted as a refused frame), so exactly that failure stranded the desktop.
 *
 * ============================================================ THE BYTE IS SHARED, ON PURPOSE
 *
 * `0x0D` is ALSO Android's cleartext "camera resumed" toggle on the same channel:
 * `[0x0D][timestamp 8 BE]`, exactly nine bytes, no nonce, no tag ([VideoControl.VIDEO_RESUMED]).
 * Both phones separate the two by STRUCTURE: the hang-up carries a sealed box, so it is at
 * least 9 + 12 + 16 bytes; iOS guards `payload.count > 28`, Android `size > 9` and then a
 * decrypt. [decode] requires the sealed size AND a successful AEAD open, so a nine-byte
 * toggle — which anyone on the path can forge — can never end a call.
 */
object InBandCallEnd {

    /** `0x0D` on the media channel, when it carries a sealed box. */
    const val TYPE = 0x0D

    /** header(9) + nonce(12) + tag(16): the smallest packet that can be a hang-up. */
    const val MIN_SIZE = CallMediaFrame.HEADER_SIZE + 12 + 16

    /** Both phones send the packet this many times, each sealed with a fresh counter. */
    const val BURST = 3

    /** Seal one in-band hang-up. [seq] must come from the call's shared audio sequence. */
    fun encode(
        sessionKey: ByteArray,
        baseSalt: ByteArray,
        isCaller: Boolean,
        seq: Long,
        reason: CallEndReason,
    ): ByteArray = CallMediaFrame.encode(
        sessionKey, baseSalt, isCaller, seq, TYPE, reason.wire.toByteArray(Charsets.UTF_8),
    )

    /** An opened in-band hang-up. [seq] is the AUTHENTICATED counter from inside the nonce. */
    class Opened(val reason: CallEndReason, val seq: Long, val nonceSalt: ByteArray)

    /** True when this packet is shaped like a hang-up (type byte and sealed size). */
    fun looksLike(frame: ByteArray): Boolean =
        frame.size >= MIN_SIZE && (frame[0].toInt() and 0xFF) == TYPE

    /**
     * Open one in-band hang-up, or null when it is not one: wrong type, too short to hold a
     * sealed box (the nine-byte camera toggle), or failing the AEAD under this call's key
     * (forged, corrupted, or from another call — every call has its own session key).
     *
     * An unknown or empty reason reads as [CallEndReason.HUNG_UP], as on iOS.
     */
    fun decode(sessionKey: ByteArray, frame: ByteArray): Opened? {
        if (!looksLike(frame)) return null
        val opened = CallMediaFrame.decode(sessionKey, frame) ?: return null
        val reason = CallEndReason.fromWire(String(opened.pcm, Charsets.UTF_8)) ?: CallEndReason.HUNG_UP
        return Opened(reason, opened.seq, frame.copyOfRange(CallMediaFrame.HEADER_SIZE, CallMediaFrame.HEADER_SIZE + 4))
    }
}
