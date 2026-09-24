package com.oshi.desktop.ui.state

import com.oshi.desktop.i18n.catalogKey
import com.oshi.desktop.call.CallEndReason
import com.oshi.desktop.call.CallLane
import com.oshi.desktop.call.CallRefusal
import com.oshi.desktop.msg.CallSummary
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * What the window draws for a call, and the only door the window has into [CallLane]
 * (__DESKTOP_CALL_UI_2026_09_23__, PARITY.md row 2.1). No compose imports: this is the part
 * with tests (`CallScreenModelTest`, two real lanes over `FakeCallServer`).
 *
 * THE LANE DECIDES, THIS DESCRIBES. Busy, blocked, stale, glare and every timeout live in
 * `CallStateMachine` and are NOT re-decided here — a second call while one is up is declined
 * by the machine (iOS behaviour, RACE 4) and a blocked caller is refused before it rings
 * (`BlockPolicy.incomingCall`). This model therefore never starts a ring on anything but a
 * [CallLane.CallEvent.Ringing], and never stops caring about the current call because a
 * refused one went by.
 *
 * THE RING STOPS ON EVERY EXIT. Answer, decline, the 55 s callee watchdog, the caller's
 * cancel and a connected call all reach this class as `RingingStopped`, `Connected` or
 * `Ended`, and each of the three stops the ringer — not only the first, because a lost
 * event would otherwise ring over the next screen.
 *
 * NEVER A SILENT "CONNECTED". The lane ends a call whose audio devices or path could not
 * open, preceded by a `TransportProblem` naming why. That reason is kept and shown on the
 * ended screen; a connected call whose leg is still punching says "connecting audio",
 * never "connected".
 */
class CallScreenModel(
    private val lane: CallLane,
    /** Display name for an address (alias, nickname, or short key). */
    private val labelFor: (String) -> String,
    private val ringer: CallRinger = CallRinger.SILENT,
    /** Store the finished call's history row. `outgoing` decides whose side it sits on. */
    private val recordSummary: (peer: String, content: String, outgoing: Boolean) -> Unit = { _, _, _ -> },
    /** After a finished call: the rating policy's turn (`CallRatingClient.callDidEnd`). */
    private val afterCall: (callId: String, peer: String, durationSeconds: Long, endedNormally: Boolean) -> Unit =
        { _, _, _, _ -> },
    /** Lane commands send HTTP — never on the UI thread. */
    private val worker: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "oshi-call-ui").apply { isDaemon = true }
    },
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * __CALL_PARITY_2026_09_23__ An incoming call ended unanswered — the desktop's missed-call
     * notification (iOS gets it from CallKit's Recents). `label` is for the UI only.
     */
    private val onMissedCall: (peer: String, label: String) -> Unit = { _, _ -> },
    /** True while a call is connected: the window holds the machine awake ([com.oshi.desktop.ui.SleepInhibitor]). */
    private val onCallLive: (Boolean) -> Unit = {},
) {
    enum class Phase { INCOMING, OUTGOING, ANSWERING, CONNECTED, ENDED }

    /** Audio under a connected call, from `CallLane.mediaDiagnostics()` — never a guess. */
    enum class Audio { NONE, CONNECTING, FLOWING }

    data class CallScreen(
        val phase: Phase,
        val peer: String,
        val label: String,
        val initials: String,
        val callId: String?,
        val outgoing: Boolean,
        /** OUTGOING only: false while dialling, true once ringback started. */
        val ringingBack: Boolean = false,
        val connectedAtMs: Long? = null,
        val muted: Boolean = false,
        val audio: Audio = Audio.NONE,
        val framesSent: Long = 0,
        val framesReceived: Long = 0,
        /** ENDED only: why, as an iOS `call.ended.*` / `call.*` key. */
        val endedKey: String? = null,
        /** The lane's own words when a call died on this machine (devices, path, server). */
        val problem: String? = null,
        val endedAtMs: Long? = null,
        /** A video call, or a voice call that became one. */
        val video: Boolean = false,
        /** Our camera is sending. */
        val cameraOn: Boolean = false,
        /** Why our camera is not sending, when the user asked for it. */
        val cameraProblem: String? = null,
        /** The peer turned their camera off (`0x0C` / `0x0E`+`0x07`). */
        val remoteCameraOff: Boolean = false,
        /** The peer asked to turn this voice call into video (`0x0E`+`0x01`). */
        val upgradeRequested: Boolean = false,
        /** Our microphone gives only digital silence — OS microphone access is off. */
        val micBlocked: Boolean = false,
        /** Nothing from the peer for [CallLiveness.RECONNECT_AFTER_MS] — iOS `call.quality.reconnecting`. */
        val reconnecting: Boolean = false,
        /** Video on, peer camera on, but no new picture — iOS `call.video.rx.stalled`. */
        val videoStalled: Boolean = false,
        /** Audio still arriving (shown beside a stalled picture: `call.video.rx.audio_ok`). */
        val audioFlowing: Boolean = false,
        /** What carries the call now — iOS's transport badge. Null before a path exists. */
        val carrier: CallCarrier? = null,
        /** The call screen is collapsed to a bar so the app can be used (iOS `call.minimize`). */
        val minimized: Boolean = false,
    ) {
        fun durationSeconds(nowMs: Long): Long = connectedAtMs?.let { ((nowMs - it) / 1000).coerceAtLeast(0) } ?: 0
    }

    private val lock = Any()
    private var current: CallScreen? = null
    private val liveness = CallLiveness()
    private var lastRelaySent = 0L
    private var lastWsSent = 0L
    private var live = false

    @Volatile var onChange: (CallScreen?) -> Unit = {}

    val screen: CallScreen? get() = synchronized(lock) { current }

    // ------------------------------------------------------------------ commands (UI)

    /** Dial a 1:1 peer. Refused locally — the reason shown — when the lane will not. */
    fun call(peer: String, video: Boolean = false) {
        synchronized(lock) {
            if (current != null && current!!.phase != Phase.ENDED) return
            current = screenFor(peer, Phase.OUTGOING, callId = null, outgoing = true).copy(video = video)
        }
        publish()
        worker.execute {
            when (val d = lane.call(peer, clock(), video)) {
                is CallLane.Dialled.Ringing -> update { it.copy(callId = d.callId) }
                is CallLane.Dialled.Refused -> {
                    ringer.stop()
                    update {
                        it.copy(
                            phase = Phase.ENDED,
                            endedAtMs = clock(),
                            endedKey = when (d.refusal) {
                                CallRefusal.BLOCKED -> catalogKey("call.error.blocked")
                                // __BLOCKED_BY_PEER_2026_09_24__ the peer blocks us: nothing was sent.
                                CallRefusal.UNAVAILABLE -> catalogKey("call.error.unavailable")
                                else -> catalogKey("call.ended.network")
                            },
                            problem = if (d.refusal == CallRefusal.BLOCKED || d.refusal == CallRefusal.UNAVAILABLE) null else d.why,
                        )
                    }
                }
            }
        }
    }

    fun answer() {
        ringer.stop()
        update { if (it.phase == Phase.INCOMING) it.copy(phase = Phase.ANSWERING) else it }
        worker.execute { lane.answer(clock()) }
    }

    fun decline() {
        ringer.stop()
        worker.execute { lane.decline(clock()) }
    }

    /** Hang up, or cancel an outgoing call that is still ringing. */
    fun hangUp() {
        ringer.stop()
        worker.execute { lane.hangUp(CallEndReason.HUNG_UP, clock()) }
    }

    fun toggleMute() {
        val muted = update { it.copy(muted = !it.muted) }?.muted ?: return
        lane.setMuted(muted)
    }

    /** Close the ended screen now (it also closes itself — see [tick]). */
    /** Camera button. Also the way a voice call asks to become video (`0x0E`+`0x01`). */
    fun toggleCamera() {
        val s = screen ?: return
        if (s.phase != Phase.CONNECTED) return
        worker.execute {
            val v = lane.video() ?: return@execute
            if (!v.active) {
                v.requestUpgrade()
                update { it.copy(video = true) }
            } else {
                v.setCameraEnabled(!v.cameraWanted)
            }
            refreshVideo()
        }
    }

    /** Reopen the camera on the device now chosen in `CallDevices` (iOS "switch camera"). */
    fun switchCamera() {
        worker.execute { lane.video()?.switchCamera(); refreshVideo() }
    }

    /** Collapse the call screen to a bar, or bring it back (iOS `call.minimize` / `.restore`). */
    fun setMinimized(minimized: Boolean) {
        update { if (it.phase == Phase.CONNECTED || it.phase == Phase.OUTGOING) it.copy(minimized = minimized) else it.copy(minimized = false) }
    }

    /** Answer the peer's request to add video: [shareCamera] = `0x02`, else `0x05`. */
    fun answerVideoRequest(accept: Boolean, shareCamera: Boolean = true) {
        worker.execute {
            val v = lane.video() ?: return@execute
            if (accept) v.acceptUpgrade(shareCamera) else v.declineUpgrade()
            refreshVideo()
        }
    }

    private fun refreshVideo() {
        val v = lane.video() ?: return
        update {
            if (it.phase != Phase.CONNECTED) it else it.copy(
                video = it.video || v.active,
                cameraOn = v.cameraRunning,
                cameraProblem = if (v.cameraWanted && !v.cameraRunning) v.cameraProblem else null,
                remoteCameraOff = v.remoteCameraOff,
                upgradeRequested = v.upgradeRequested,
            )
        }
    }

    /** [CallLiveness] + [CallCarrier] from the leg's counters, once a second while connected. */
    private fun refreshLiveness(nowMs: Long, d: CallLane.MediaDiagnostics) {
        val v = lane.video()
        val leg = lane.media
        val videoActive = v != null && v.active && !v.remoteCameraOff
        val packets = d.framesAccepted + d.framesRefused + (v?.videoPacketsIn ?: 0L)
        val status = liveness.observe(nowMs, packets, d.framesAccepted, videoActive, v?.remoteFrameCount ?: 0L)
        val relaySent = leg?.relaySent?.get() ?: 0L
        val wsSent = leg?.wsSent?.get() ?: 0L
        val carrier = CallCarrier.of(leg?.selected?.type, relaySent - lastRelaySent, wsSent - lastWsSent)
        lastRelaySent = relaySent; lastWsSent = wsSent
        update {
            if (it.phase != Phase.CONNECTED) it else it.copy(
                reconnecting = status.reconnecting,
                videoStalled = status.videoStalled,
                audioFlowing = status.audioFlowing,
                carrier = carrier ?: it.carrier,
            )
        }
    }

    fun dismiss() {
        synchronized(lock) { if (current?.phase == Phase.ENDED) current = null }
        publish()
    }

    /**
     * Once a second from the window: refresh the audio status and counters of a connected
     * call, and retire an ended screen after [ENDED_SHOW_MS].
     */
    fun tick(nowMs: Long = clock()) {
        val s = screen ?: return
        when (s.phase) {
            Phase.CONNECTED -> {
                val d = lane.mediaDiagnostics()
                val audio = when (d.status) {
                    CallLane.MediaStatus.ACTIVE -> Audio.FLOWING
                    CallLane.MediaStatus.WAITING_FOR_PATH -> Audio.CONNECTING
                    else -> Audio.NONE
                }
                update { if (it.phase == Phase.CONNECTED) it.copy(audio = audio, framesSent = d.framesSent, framesReceived = d.framesAccepted, micBlocked = d.micLooksBlocked && !it.muted) else it }
                refreshVideo()
                refreshLiveness(nowMs, d)
            }
            Phase.ENDED -> if (nowMs - (s.endedAtMs ?: nowMs) >= ENDED_SHOW_MS) {
                synchronized(lock) { if (current === s) current = null }
                publish()
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ events (poll thread)

    fun onEvent(event: CallLane.CallEvent) {
        when (event) {
            is CallLane.CallEvent.Ringing -> {
                if (event.incoming) {
                    synchronized(lock) {
                        current = screenFor(event.peer, Phase.INCOMING, event.callId, outgoing = false)
                            .copy(video = event.video)
                    }
                    publish()
                    ringer.startIncoming()
                } else {
                    synchronized(lock) {
                        current = (current ?: screenFor(event.peer, Phase.OUTGOING, event.callId, outgoing = true))
                            .let { it.copy(ringingBack = true, callId = event.callId, video = it.video || event.video) }
                    }
                    publish()
                    ringer.startRingback()
                }
            }
            CallLane.CallEvent.RingingStopped -> ringer.stop()
            is CallLane.CallEvent.Connected -> {
                ringer.stop()
                update {
                    it.copy(
                        phase = Phase.CONNECTED,
                        callId = event.callId,
                        connectedAtMs = it.connectedAtMs ?: clock(),
                        audio = if (event.noAudio) Audio.NONE else Audio.CONNECTING,
                    )
                }
            }
            is CallLane.CallEvent.TransportProblem -> update {
                if (it.phase == Phase.ENDED) it else it.copy(problem = event.detail)
            }
            is CallLane.CallEvent.Ended -> onEnded(event)
            // Refused calls (BUSY while we talk, BLOCKED, stale, duplicate…) never touch the
            // screen: the machine already answered the peer, and the call on screen is still ours.
            is CallLane.CallEvent.Refused -> Unit
        }
    }

    private fun onEnded(event: CallLane.CallEvent.Ended) {
        ringer.stop()
        val now = clock()
        var finished: CallScreen? = null
        synchronized(lock) {
            val s = current?.takeIf { it.callId == null || it.callId == event.callId }
                ?: screenFor(event.peer, Phase.ENDED, event.callId, outgoing = !incomingFromLog(event))
            finished = s
            current = s.copy(
                phase = Phase.ENDED,
                endedAtMs = now,
                endedKey = endedKeyFor(event.reason, event.wasConnected, s.outgoing, s.problem),
            )
        }
        publish()
        val s = finished ?: return
        if (!s.outgoing && screen?.endedKey == catalogKey("call.missed")) runCatching { onMissedCall(event.peer, s.label) }
        val duration = if (event.wasConnected) s.durationSeconds(now) else 0
        recordSummary(event.peer, CallSummary.forEndedCall(s.outgoing, event.wasConnected, event.reason, duration), s.outgoing)
        if (event.wasConnected) {
            afterCall(event.callId, event.peer, duration, s.problem == null && event.reason != CallEndReason.NETWORK_ERROR)
        }
    }

    private fun incomingFromLog(event: CallLane.CallEvent.Ended): Boolean =
        lane.calls().lastOrNull { it.callId == event.callId }?.incoming ?: !lane.isOutgoing

    // ------------------------------------------------------------------ helpers

    private fun screenFor(peer: String, phase: Phase, callId: String?, outgoing: Boolean): CallScreen {
        val label = labelFor(peer)
        return CallScreen(phase, peer, label, initials(label), callId, outgoing)
    }

    private fun update(f: (CallScreen) -> CallScreen): CallScreen? {
        val next = synchronized(lock) {
            val cur = current ?: return null
            f(cur).also { current = it }
        }
        publish()
        return next
    }

    private fun publish() {
        val s = screen
        val nowLive = s?.phase == Phase.CONNECTED
        if (nowLive != live) {
            live = nowLive
            if (nowLive) { liveness.reset(); lastRelaySent = 0L; lastWsSent = 0L }
            runCatching { onCallLive(nowLive) }
        }
        onChange(s)
    }

    companion object {
        /** How long the ended screen stays, like iOS's "Call Ended" beat before dismissing. */
        const val ENDED_SHOW_MS = 2_500L

        fun initials(label: String): String =
            label.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
                .joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }

        /** The iOS key for why a call ended, from where the user was when it did. */
        fun endedKeyFor(reason: CallEndReason, wasConnected: Boolean, outgoing: Boolean, problem: String?): String = when {
            problem != null && reason in setOf(CallEndReason.NETWORK_ERROR, CallEndReason.CONNECTION_LOST) -> catalogKey("call.ended.network")
            reason == CallEndReason.DECLINED -> catalogKey("call.ended.declined")
            reason == CallEndReason.NO_ANSWER -> if (outgoing) catalogKey("call.ended.noanswer") else catalogKey("call.missed")
            !wasConnected && !outgoing && reason != CallEndReason.ANSWERED_ELSEWHERE -> catalogKey("call.missed")
            reason == CallEndReason.NETWORK_ERROR -> catalogKey("call.ended.network")
            reason == CallEndReason.PEER_DISCONNECTED || reason == CallEndReason.CONNECTION_LOST -> catalogKey("call.ended.disconnected")
            else -> catalogKey("call.ended.hungup")
        }
    }
}

/** Local ring and ringback. The window's is a looped tone; tests and headless builds are silent. */
interface CallRinger {
    fun startIncoming()
    fun startRingback()
    fun stop()

    companion object {
        val SILENT = object : CallRinger {
            override fun startIncoming() {}
            override fun startRingback() {}
            override fun stop() {}
        }
    }
}
