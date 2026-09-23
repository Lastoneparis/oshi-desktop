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
    private val encoderFactory: (Int, Int) -> H264Encoder = { w, h -> H264Encoder(w, h) },
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

    private val receiver = VideoReceiveSession(sessionKey, sink = object : VideoStreamSink {
        override fun writeParameterSets(sps: ByteArray, pps: ByteArray) {
            // Never dropped: a lost parameter set would make every later frame undecodable.
            decodeQueue.clear()
            decodeQueue.offer(Job.Params(sps, pps))
        }
        override fun writeAccessUnit(bytes: ByteArray) {
            if (!decodeQueue.offer(Job.Unit(bytes, lastRotation))) {
                // Decoder behind: drop the backlog and ask for a clean restart point.
                decodeQueue.clear()
                keyframeRequestPending = true
            }
        }
    })
    @Volatile private var lastRotation = 0
    @Volatile private var keyframeRequestPending = false
    private var decodeThread: Thread? = null

    var videoPacketsIn = 0L
        private set

    /**
     * Offer one media datagram. Returns true when it was video or video control (consumed),
     * false for everything else (audio, in-band hang-up) so the caller routes it on.
     */
    fun onMedia(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || closed.get()) return false
        val type = bytes[0].toInt() and 0xFF
        when {
            type == VideoMediaFrame.TYPE_VIDEO -> {
                videoPacketsIn++
                if (!active) active = true // a peer sending video IS a video call
                ensureDecoder()
                val r = receiver.onPacket(bytes)
                lastRotation = receiver.lastRotationCode
                if (r.requestKeyframe || keyframeRequestPending) {
                    keyframeRequestPending = false
                    control.requestKeyframe().forEach { sendDatagram(it) }
                }
                return true
            }
            VideoControl.isCleartextToggle(type) && bytes.size == VideoControl.TOGGLE_SIZE -> {
                val t = VideoControl.decodeToggle(bytes) ?: return true
                val before = control.inboundKeyframeRequests
                control.onToggle(t).forEach { sendDatagram(it) }
                if (control.inboundKeyframeRequests != before) sender.keyframeWanted = true
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
                    u.code == VideoControl.VUPG_CAMERA_ON -> control.requestKeyframe().forEach { sendDatagram(it) }
                }
                return true
            }
        }
        return false
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
                val job = decodeQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
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

    private fun captureLoop(ready: java.util.concurrent.CountDownLatch) {
        var camera: VideoSource? = null
        var encoder: H264Encoder? = null
        try {
            camera = cameraFactory!!.invoke()
            encoder = encoderFactory(camera.outW, camera.outH)
            encoderName = encoder.name
            cameraRunning = true
            log("video: camera ${camera.deviceName} open, encoder ${encoder.name} ${camera.outW}x${camera.outH}")
            ready.countDown()
            var n = 0L
            while (!captureStop.get() && !closed.get()) {
                val shot = camera.next(wantPreview = n % 2 == 0L) ?: continue
                shot.preview?.let { localFrame = it.mirrored(); localFrameCount++ }
                val force = sender.keyframeWanted
                if (force) sender.keyframeWanted = false
                for (au in encoder.encode(shot.yuv, force)) {
                    sender.sendAccessUnit(au.annexB, au.key, clock())
                }
                n++
            }
        } catch (t: Throwable) {
            cameraProblem = t.message ?: t.javaClass.simpleName
            log("video: camera stopped — $cameraProblem")
        } finally {
            cameraRunning = false
            ready.countDown()
            runCatching { encoder?.close() }
            runCatching { camera?.close() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopCamera()
        decodeThread?.let { runCatching { it.join(500) } }
        decodeThread = null
    }
}
