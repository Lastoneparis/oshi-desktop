package com.oshi.desktop.call.video

import com.oshi.desktop.call.media.NonceReplayWindow
import com.oshi.desktop.call.media.VideoControl
import com.oshi.desktop.call.media.VideoControlSession
import com.oshi.desktop.call.media.VideoMediaFrame
import com.oshi.desktop.call.media.VideoReceiveSession
import com.oshi.desktop.call.media.VideoSendSession
import com.oshi.desktop.call.media.VideoStreamSink
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Video for ONE call — PARITY.md rows 2.1-v and 2.1-c, both halves.
 *
 * ```
 *  camera ─▶ H264Encoder ─▶ VideoSendSession ─▶ sealed 0xF1 ─▶ media socket ─▶ phone
 *  phone ─▶ media socket ─▶ onMedia(0xF1) ─▶ VideoReceiveSession ─▶ H264Decoder ─▶ remoteFrame
 *                          onMedia(0x0B/0x0C/0x0D, 0x0E/0x0F) ─▶ VideoControlSession
 * ```
 *
 * When a video call connects, the phones start their camera on BOTH sides without any
 * further negotiation (`EnhancedCallManager.kt:1909-1912, :2995-3000`; iOS the same from
 * `isCurrentCallVideo`), so [start] does exactly that. The upgrade lane (`0x0E`) is for a
 * VOICE call becoming video, and is answered `0x02` (accept and share my camera) when a
 * camera opened, `0x05` (watch only) when it did not.
 *
 * Camera on/off is announced BOTH ways the phones announce it: the sealed
 * `0x0E`+`0x06`/`0x07` (iOS `swift:11568-11578`, Android `kt:6170-6184` — what both
 * receivers act on) and the 9-byte cleartext `0x0C`/`0x0D` Android also emits on the media
 * channel (`kt:5612-5640`). iOS reads a 9-byte `0x0D` as a video toggle, never as its
 * in-band hang-up, which is always a ≥29-byte sealed box (`swift:12671-12680`).
 *
 * Threads: [onMedia] runs on the socket's receive thread and never decodes there — decoded
 * work goes through a small bounded queue to one decode thread, so a slow frame can delay
 * a picture but never an audio datagram behind it.
 */
class CallVideoSession(
    private val sessionKey: ByteArray,
    private val baseSalt: ByteArray,
    private val isCaller: Boolean,
    private val sendDatagram: (ByteArray) -> Boolean,
    /** The AUDIO session's sequence — `0x0E`/`0x0F` are sealed on the shared audio counter. */
    private val nextAudioSeq: (() -> Long)?,
    private val cameraFactory: (() -> VideoSource)? = { CameraCapture() },
    /** width, height, bitrate (bit/s). */
    private val encoderFactory: (Int, Int, Long) -> H264Encoder = { w, h, b -> H264Encoder(w, h, bitrate = b) },
    private val decoderFactory: (() -> H264Decoder)? = { H264Decoder() },
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    val control = VideoControlSession(clock)
    val sender = VideoSendSession(sessionKey, isCaller, sendDatagram)
    private val closed = AtomicBoolean(false)
    private val upgradeReplay = NonceReplayWindow()

    // ---------------------------------------------------------------- state the UI reads

    /** The last picture from the peer, already rotated upright. */
    @Volatile var remoteFrame: VideoImage? = null
        private set
    /** The last self-view picture (mirrored), while the camera runs. */
    @Volatile var localFrame: VideoImage? = null
        private set
    /** Bumped on every new remote picture so a UI can poll cheaply. */
    @Volatile var remoteFrameCount = 0L
        private set
    @Volatile var localFrameCount = 0L
        private set
    /** The user wants the camera on. */
    @Volatile var cameraWanted = false
        private set
    /** The camera is actually delivering frames. */
    @Volatile var cameraRunning = false
        private set
    /** Why the camera is not running, in a sentence the user can act on. */
    @Volatile var cameraProblem: String? = null
        private set
    @Volatile var encoderName: String? = null
        private set
    /** Video is part of this call (it began as a video call, or an upgrade was accepted). */
    @Volatile var active = false
        private set

    val remoteCameraOff: Boolean get() = control.remoteCameraPaused
    val upgradeRequested: Boolean get() = control.upgradeRequested

    // ---------------------------------------------------------------- receive

    private val decodeQueue = ArrayBlockingQueue<Job>(8)
    private sealed class Job {
        class Params(val sps: ByteArray, val pps: ByteArray) : Job()
        class Unit(val annexB: ByteArray, val rotation: Int) : Job()
    }

    private val receiver = VideoReceiveSession(sessionKey, clock = clock, sink = object : VideoStreamSink {
        override fun writeParameterSets(sps: ByteArray, pps: ByteArray) {
            // Never dropped: a lost parameter set would make every later frame undecodable.
            val p = Job.Params(sps, pps)
            lastParams = p
            decodeQueue.clear()
            decodeQueue.offer(p)
        }
        override fun writeAccessUnit(bytes: ByteArray) {
            if (!decodeQueue.offer(Job.Unit(bytes, lastRotation))) {
                // Decoder behind: drop the backlog and ask for a clean restart point.
                // The parameter sets go back in FIRST — the receive session writes them once
                // and again only when they change, so clearing them here left the decoder
                // without an SPS for the rest of the call (CI 35868599749: 642 frames
                // delivered to a Windows caller whose decoder started late, 0 decoded).
                decodeQueue.clear()
                lastParams?.let { decodeQueue.offer(it) }
                keyframeRequestPending = true
            }
        }
    })
    @Volatile private var lastRotation = 0
    @Volatile private var lastParams: Job.Params? = null
    @Volatile private var keyframeRequestPending = false
    private var decodeThread: Thread? = null

    var videoPacketsIn = 0L
        private set

    /**
     * Offer one media datagram. Returns true when it was video or video control (consumed),
     * false for everything else (audio, in-band hang-up) so the caller routes it on.
     */
    /** One line of receive-side counters, for logs and benches. */
    fun rxStats(): String =
        "vIn=$videoPacketsIn delivered=${receiver.delivered} rejected=${receiver.rejected} " +
            "replayed=${receiver.replayed} preIdr=${receiver.droppedBeforeIdr} decoded=$remoteFrameCount"

    fun onMedia(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || closed.get()) return false
        val type = bytes[0].toInt() and 0xFF
        when {
            type == VideoMediaFrame.TYPE_VIDEO -> {
                videoPacketsIn++
                if (!active) active = true // a peer sending video IS a video call
                ensureDecoder()
                val r = synchronized(receiver) { receiver.onPacket(bytes) }
                lastRotation = receiver.lastRotationCode
                if (r.requestKeyframe || keyframeRequestPending) {
                    keyframeRequestPending = false
                    askPeerForKeyframe()
                }
                return true
            }
            VideoControl.isCleartextToggle(type) && bytes.size == VideoControl.TOGGLE_SIZE -> {
                val t = VideoControl.decodeToggle(bytes) ?: return true
                val before = control.inboundKeyframeRequests
                emitPli(control.onToggle(t)) // non-empty for 0x0D: the peer resumed on P-frames
                if (control.inboundKeyframeRequests != before) {
                    // __VIDEO_ABR_2026_09_23__ ≤ 2/s (the control session's bucket): force
                    // an IDR, and tell the rate controller — the peer's only report on our uplink.
                    sender.keyframeWanted = true
                    rate?.onPeerKeyframeRequest()
                }
                return true
            }
            type == VideoControl.VIDEO_UPGRADE || type == VideoControl.VIDEO_STOP -> {
                val u = VideoControl.decodeUpgrade(sessionKey, bytes) ?: return true
                val seqKey = ByteArray(9).also { k ->
                    k[0] = type.toByte()
                    for (i in 0 until 8) k[1 + i] = ((u.seq ushr (56 - i * 8)) and 0xFF).toByte()
                }
                if (!upgradeReplay.accept(seqKey)) return true
                val wasActive = control.videoActive
                control.onUpgrade(u)
                when {
                    u.type == VideoControl.VIDEO_STOP || u.code == VideoControl.VUPG_STOP -> {
                        active = false
                        stopCamera()
                    }
                    (u.code == VideoControl.VUPG_ACCEPT || u.code == VideoControl.VUPG_ACCEPT_WATCH) && !wasActive -> {
                        // Our upgrade request was accepted: video starts both ways.
                        active = true
                        if (cameraWanted) startCamera()
                    }
                    u.code == VideoControl.VUPG_CAMERA_ON -> askPeerForKeyframe()
                }
                return true
            }
        }
        return false
    }

    /**
     * __VIDEO_PLI_SIGNAL_2026_09_23__ A copy of every keyframe request for the SIGNAL lane
     * (wired by [com.oshi.desktop.call.CallLane]). iOS up to b145 reads `0x0B` only as a
     * sealed call signal and drops the 9-byte media-channel form, so without this copy a
     * released iPhone never heard a desktop's request and the desktop's decoder waited for
     * the iPhone's 1 s GOP after every loss. Android sends both copies the same way.
     */
    @Volatile var signalKeyframeRequest: (() -> Unit)? = null
    @Volatile private var lastSignalPliAt = 0L

    /** Ask the peer for an IDR: media channel (bucketed), plus a signal copy at most 1/s. */
    private fun askPeerForKeyframe() = emitPli(control.requestKeyframe())

    private fun emitPli(out: List<ByteArray>) {
        out.forEach { sendDatagram(it) }
        if (out.isEmpty()) return
        val now = clock()
        val hook = signalKeyframeRequest ?: return
        if (now - lastSignalPliAt < SIGNAL_PLI_MIN_INTERVAL_MS) return
        lastSignalPliAt = now
        runCatching { hook() }
    }

    private fun ensureDecoder() {
        if (decodeThread != null || decoderFactory == null) return
        synchronized(this) {
            if (decodeThread != null) return
            decodeThread = Thread({ decodeLoop() }, "oshi-video-decode").apply { isDaemon = true; start() }
        }
    }

    private fun decodeLoop() {
        val decoder = runCatching { decoderFactory!!.invoke() }.getOrElse {
            log("video: no decoder — ${it.message}")
            return
        }
        decoder.use { d ->
            while (!closed.get()) {
                val job = decodeQueue.poll(100, TimeUnit.MILLISECONDS)
                if (job == null) {
                    // __VIDEO_REORDER_2026_09_23__ frames held behind a hole go out on the
                    // clock when no newer datagram arrives to release them.
                    val r = synchronized(receiver) { receiver.poll() }
                    if (r.requestKeyframe) keyframeRequestPending = true
                    continue
                }
                when (job) {
                    is Job.Params -> d.setParameterSets(job.sps, job.pps)
                    is Job.Unit -> {
                        val errorsBefore = d.errors
                        for (img in d.decode(job.annexB)) {
                            remoteFrame = img.rotated(job.rotation)
                            remoteFrameCount++
                        }
                        if (d.errors != errorsBefore) keyframeRequestPending = true
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- send

    private var captureThread: Thread? = null
    private val captureStop = AtomicBoolean(false)

    /** A video call just connected: video on, camera on (as the phones do). */
    fun start(videoCall: Boolean) {
        if (!videoCall) return
        active = true
        setCameraEnabled(true, announce = false)
    }

    /** Camera button. Announces the change to the peer. */
    fun setCameraEnabled(on: Boolean, announce: Boolean = true) {
        cameraWanted = on
        if (on) {
            if (active) startCamera()
            if (announce) announceCamera(true)
        } else {
            stopCamera()
            if (announce) announceCamera(false)
        }
    }

    /**
     * __CALL_DEVICES_2026_09_23__ The iPhone's "switch camera", desktop-shaped: reopen the
     * camera on the device now chosen in [com.oshi.desktop.call.media.CallDevices]. Nothing is
     * announced — to the peer the camera never went off, the stream just continues from a
     * new keyframe (a new encoder starts on one).
     */
    fun switchCamera() {
        if (!cameraWanted || !active) return
        stopCamera()
        startCamera()
    }

    /** Ask the peer to turn this voice call into a video call (`0x0E`/`0x01`). */
    fun requestUpgrade(): Boolean {
        val seq = nextAudioSeq ?: return false
        cameraWanted = true
        return sendDatagram(
            VideoControl.encodeUpgrade(sessionKey, baseSalt, isCaller, seq(), VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_REQUEST),
        )
    }

    /**
     * Answer the peer's upgrade request. `0x02` when we will share a camera, `0x05` when the
     * user chose watch-only or no camera can open — never `0x02` without a camera.
     */
    fun acceptUpgrade(shareCamera: Boolean): Boolean {
        val seq = nextAudioSeq ?: return false
        if (control.acceptUpgradeAsWatcher() == null) return false
        active = true
        var share = shareCamera
        if (share) {
            cameraWanted = true
            startCamera(waitForOpen = true)
            // `0x02` claims a camera: only when frames are actually flowing from one.
            if (!cameraRunning) share = false
        }
        val code = if (share) VideoControl.VUPG_ACCEPT else VideoControl.VUPG_ACCEPT_WATCH
        return sendDatagram(
            VideoControl.encodeUpgrade(sessionKey, baseSalt, isCaller, seq(), VideoControl.VIDEO_UPGRADE, code),
        )
    }

    fun declineUpgrade(): Boolean {
        val seq = nextAudioSeq ?: return false
        val code = control.declineUpgrade() ?: return false
        return sendDatagram(
            VideoControl.encodeUpgrade(sessionKey, baseSalt, isCaller, seq(), VideoControl.VIDEO_UPGRADE, code),
        )
    }

    private fun announceCamera(on: Boolean) {
        nextAudioSeq?.let { seq ->
            sendDatagram(
                VideoControl.encodeUpgrade(
                    sessionKey, baseSalt, isCaller, seq(), VideoControl.VIDEO_UPGRADE,
                    if (on) VideoControl.VUPG_CAMERA_ON else VideoControl.VUPG_CAMERA_OFF,
                ),
            )
        }
        sendDatagram(VideoControl.encodeToggle(if (on) VideoControl.VIDEO_RESUMED else VideoControl.VIDEO_PAUSED, clock()))
    }

    @Synchronized
    private fun startCamera(waitForOpen: Boolean = false) {
        if (captureThread != null || closed.get() || cameraFactory == null) return
        cameraProblem = null
        if (!FfmpegVideo.available) {
            cameraProblem = FfmpegVideo.unavailableReason()
            return
        }
        captureStop.set(false)
        sender.restart()
        val ready = java.util.concurrent.CountDownLatch(1)
        captureThread = Thread({ captureLoop(ready) }, "oshi-video-capture").apply { isDaemon = true; start() }
        // Only the upgrade answer waits (a refusal must be known before choosing 0x02 or
        // 0x05). Call setup never does: it runs on the lane's signalling path.
        if (waitForOpen) ready.await(4, TimeUnit.SECONDS)
    }

    @Synchronized
    private fun stopCamera() {
        val t = captureThread ?: return
        captureStop.set(true)
        captureThread = null
        runCatching { t.join(1500) }
        cameraRunning = false
        localFrame = null
    }

    // ---------------------------------------------------------------- rate adaptation

    /**
     * __VIDEO_ABR_2026_09_23__ Send-side adaptation — the shared
     * [com.oshi.messenger.service.VideoRateController] (the Android file, compiled here), on
     * the same signals as iOS: loss of the call (here: the peer's video datagrams, from the
     * nonce counter), and every keyframe request the peer sends. Step down fast, up slowly,
     * 150 kbit/s floor, 1.2 Mbit/s ceiling, and fps/resolution rungs. FFmpeg's H.264
     * encoders cannot change rate live, so a rung change rebuilds the encoder (one IDR).
     */
    @Volatile var rate: com.oshi.messenger.service.VideoRateController? = null
        private set

    /** The encoder's current bitrate, bit/s (0 when the camera is off). */
    @Volatile var sendBitrate = 0L
        private set
    @Volatile var sendFps = 0
        private set

    private fun captureLoop(ready: java.util.concurrent.CountDownLatch) {
        var camera: VideoSource? = null
        var encoder: H264Encoder? = null
        var scaler: Scaler? = null
        try {
            camera = cameraFactory!!.invoke()
            val rc = com.oshi.messenger.service.VideoRateController(H264Encoder.DEFAULT_BITRATE.toInt(), nowMs = clock)
            rate = rc
            var bitrate = H264Encoder.DEFAULT_BITRATE
            var scalePct = 100
            var fps = 30
            encoder = encoderFactory(camera.outW, camera.outH, bitrate)
            encoderName = encoder.name
            sendBitrate = bitrate; sendFps = fps
            cameraRunning = true
            log("video: camera ${camera.deviceName} open, encoder ${encoder.name} ${camera.outW}x${camera.outH}")
            ready.countDown()
            var n = 0L
            var lastTick = clock()
            var lastEncoded = 0L
            while (!captureStop.get() && !closed.get()) {
                val shot = camera.next(wantPreview = n % 2 == 0L) ?: continue
                shot.preview?.let { localFrame = it.mirrored(); localFrameCount++ }
                n++
                val now = clock()
                if (now - lastTick >= com.oshi.messenger.service.VideoRateController.TICK_MS) {
                    lastTick = now
                    synchronized(receiver) { receiver.takeLossSample() }?.let { rc.onLossSample(it) }
                    val d = rc.tick()
                    if (d.bitrateBps.toLong() != bitrate || d.scalePct != scalePct || d.fps != fps) {
                        bitrate = d.bitrateBps.toLong()
                        scalePct = d.scalePct
                        fps = d.fps
                        val w = (camera.outW * scalePct / 100) and -2
                        val h = (camera.outH * scalePct / 100) and -2
                        runCatching { encoder?.close() }
                        encoder = null
                        runCatching { scaler?.close() }
                        scaler = if (scalePct < 100) {
                            Scaler(camera.outW, camera.outH, org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P,
                                w, h, org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P)
                        } else null
                        // The encoder is told 30 fps and fed [fps]: its per-frame budget is
                        // bitrate/30, so ask for bitrate×30/fps to land on the rung's bitrate.
                        encoder = encoderFactory(w, h, bitrate * 30 / fps)
                        sender.keyframeWanted = true // a new encoder starts on an IDR anyway; say so
                        log("video: rate rung ${d.rung} — ${bitrate / 1000} kbit/s, ${d.fps} fps, ${w}x$h (target ${d.targetBps / 1000}k)")
                    }
                    sendBitrate = bitrate; sendFps = fps
                }
                // The rung's frame rate: preview keeps every frame, the encoder only these.
                if (fps < 30 && now - lastEncoded < 1000L / fps - 4) continue
                lastEncoded = now
                val force = sender.keyframeWanted
                if (force) sender.keyframeWanted = false
                val input = scaler?.scale(shot.yuv) ?: shot.yuv
                for (au in encoder!!.encode(input, force)) {
                    sender.sendAccessUnit(au.annexB, au.key, now)
                }
            }
        } catch (t: Throwable) {
            cameraProblem = t.message ?: t.javaClass.simpleName
            log("video: camera stopped — $cameraProblem")
        } finally {
            cameraRunning = false
            rate = null
            sendBitrate = 0; sendFps = 0
            ready.countDown()
            runCatching { encoder?.close() }
            runCatching { scaler?.close() }
            runCatching { camera?.close() }
        }
    }

    private companion object {
        /** The signal-lane copy of a keyframe request: at most one a second (server budget). */
        const val SIGNAL_PLI_MIN_INTERVAL_MS = 1_000L
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopCamera()
        decodeThread?.let { runCatching { it.join(500) } }
        decodeThread = null
    }
}
