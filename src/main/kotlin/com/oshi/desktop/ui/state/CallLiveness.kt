package com.oshi.desktop.ui.state

import com.oshi.desktop.call.transport.IceCandidateType

/**
 * __CALL_LIVENESS_2026_09_23__ What a connected call looks like from the counters: is the
 * other end still reaching us, and is their picture still moving? PARITY.md row 2.1-d.
 *
 * iOS arms "Reconnecting" (`CallReconnectState.mediaSilence`, `call.quality.reconnecting`)
 * after `connectionLossThreshold` = 2.5 s without AUDIO, and ends the call after
 * `maxDisconnectionTime` = 12 s. Two deliberate differences, both because the desktop talks
 * to Android too:
 *
 * - **Liveness is ANY authenticated media, not audio.** Android 1.6.25 stops sending audio
 *   while muted (`EnhancedCallManager.kt:4439`, `:5062`) and sends only a `0x06` keepalive
 *   every 3 s. Keyed on audio, a muted Android peer would read as a dead line; keyed on any
 *   packet — audio, the keepalive (counted as a refused audio frame), video — it does not.
 *   That also sets the threshold: 4 s, above the keepalive period, not iOS's 2.5 s.
 * - **No auto hang-up here.** The call already ends on a dead path: `CallLane`'s
 *   `MEDIA_PATH_TIMEOUT_MS` watchdog. Ending on 12 s of silence as iOS does would hang up on
 *   every muted Android peer that loses one keepalive.
 *
 * The remote-video flags mirror iOS `VideoCallView`: `call.video.rx.stalled` when frames
 * stop, and `call.video.rx.audio_ok` beside it while audio is still flowing — the sentence
 * that stops a person hanging up a call that is only missing its picture.
 */
class CallLiveness(
    private val reconnectAfterMs: Long = RECONNECT_AFTER_MS,
    private val stallAfterMs: Long = STALL_AFTER_MS,
) {
    data class Status(
        val reconnecting: Boolean = false,
        val videoStalled: Boolean = false,
        val audioFlowing: Boolean = false,
    )

    private var lastPackets = -1L
    private var lastPacketChangeMs = 0L
    private var sawMedia = false

    private var lastAudio = -1L
    private var lastAudioChangeMs = 0L

    private var lastDecoded = -1L
    private var lastDecodedChangeMs = 0L
    private var videoSinceMs = -1L

    /**
     * @param packets every authenticated-or-not media packet that reached the call so far
     *   (audio accepted + refused + video datagrams): any increase means the peer is alive.
     * @param audioFrames audio frames that authenticated and played.
     * @param videoActive the call is carrying video and the peer's camera is on.
     * @param decodedFrames the peer's pictures decoded so far.
     */
    fun observe(nowMs: Long, packets: Long, audioFrames: Long, videoActive: Boolean, decodedFrames: Long): Status {
        if (packets != lastPackets) {
            if (lastPackets >= 0 && packets > lastPackets) sawMedia = true
            if (lastPackets < 0 && packets > 0) sawMedia = true
            lastPackets = packets
            lastPacketChangeMs = nowMs
        }
        if (audioFrames != lastAudio) { lastAudio = audioFrames; lastAudioChangeMs = nowMs }
        if (decodedFrames != lastDecoded) { lastDecoded = decodedFrames; lastDecodedChangeMs = nowMs }
        if (!videoActive) videoSinceMs = -1L else if (videoSinceMs < 0L) videoSinceMs = nowMs

        val reconnecting = sawMedia && nowMs - lastPacketChangeMs >= reconnectAfterMs
        val audioFlowing = audioFrames > 0 && nowMs - lastAudioChangeMs < AUDIO_FRESH_MS
        // A picture that stopped, or one that never started STALL_AFTER_MS after video began.
        val sinceLastPicture = nowMs - maxOf(lastDecodedChangeMs, videoSinceMs)
        val videoStalled = videoActive && videoSinceMs >= 0 && sinceLastPicture >= stallAfterMs && !reconnecting
        return Status(reconnecting, videoStalled, audioFlowing)
    }

    fun reset() {
        lastPackets = -1L; lastPacketChangeMs = 0L; sawMedia = false
        lastAudio = -1L; lastAudioChangeMs = 0L
        lastDecoded = -1L; lastDecodedChangeMs = 0L; videoSinceMs = -1L
    }

    companion object {
        /** Above Android's 3 s muted keepalive; see the class doc. */
        const val RECONNECT_AFTER_MS = 4_000L
        const val STALL_AFTER_MS = 3_000L
        const val AUDIO_FRESH_MS = 2_000L
    }
}

/**
 * The carrier under a connected call, named the way iOS's call screen names it
 * (`VoiceCallManager.TransportPath`: "P2P", "TURN Relay", "UDP Relay", "WebSocket") — a
 * technical label, not translated on iOS either. Priority follows what is CARRYING media:
 * the selected pair while P2P is healthy, else whichever relay sent most recently.
 */
enum class CallCarrier(val label: String) {
    P2P("P2P"),
    TURN("TURN Relay"),
    UDP_RELAY("UDP Relay"),
    WEBSOCKET("WebSocket"),
    ;

    companion object {
        fun of(selected: IceCandidateType?, relaySentDelta: Long, wsSentDelta: Long): CallCarrier? = when {
            wsSentDelta > 0 && wsSentDelta >= relaySentDelta -> WEBSOCKET
            relaySentDelta > 0 -> UDP_RELAY
            selected == IceCandidateType.RELAY -> TURN
            selected != null -> P2P
            else -> null
        }
    }
}
