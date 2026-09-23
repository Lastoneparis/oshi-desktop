package com.oshi.desktop.call.video

import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil

/**
 * A [VideoSource] with no hardware behind it: moving colour bars at [fps], paced in real
 * time, YUV420P at [outW]×[outH].
 *
 * It exists for the one place a camera cannot be: a CI runner. GitHub's `windows-latest` is
 * the only Windows this project can test on and it has no webcam, so the Windows live call
 * bench sends these frames through the REAL encoder (the Windows FFmpeg natives), the real
 * fragmenter, seal, UDP socket and the peer's real decoder. What it does not exercise is
 * DirectShow itself — that is [CameraCapture], and it is said so wherever this is used.
 *
 * Never selected by the application.
 */
class SyntheticCamera(
    override val outW: Int = CameraCapture.DEFAULT_WIDTH,
    override val outH: Int = CameraCapture.DEFAULT_HEIGHT,
    private val fps: Int = 30,
) : VideoSource {
    override val deviceName: String = "synthetic test card"

    private val frame: AVFrame = avutil.av_frame_alloc().apply {
        format(avutil.AV_PIX_FMT_YUV420P); width(outW); height(outH)
        check(avutil.av_frame_get_buffer(this, 32) >= 0) { "av_frame_get_buffer failed" }
    }
    private var n = 0L
    private var nextAtNs = System.nanoTime()

    init {
        FfmpegVideo.load().getOrThrow()
    }

    override fun next(wantPreview: Boolean): CameraCapture.Captured {
        val wait = nextAtNs - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        nextAtNs = maxOf(nextAtNs + 1_000_000_000L / fps, System.nanoTime() - 1_000_000_000L / fps)

        avutil.av_frame_make_writable(frame)
        val shift = (n * 4 % outW).toInt()
        val yStride = frame.linesize(0); val uStride = frame.linesize(1); val vStride = frame.linesize(2)
        val yRow = ByteArray(outW); val uRow = ByteArray(outW / 2); val vRow = ByteArray(outW / 2)
        for (x in 0 until outW) {
            val bar = ((x + shift) % outW) * 8 / outW
            yRow[x] = (40 + bar * 25).toByte()
        }
        for (x in 0 until outW / 2) {
            val bar = ((x * 2 + shift) % outW) * 8 / outW
            uRow[x] = (bar * 32).toByte(); vRow[x] = (255 - bar * 32).toByte()
        }
        val yp = frame.data(0); val up = frame.data(1); val vp = frame.data(2)
        for (y in 0 until outH) yp.position(y.toLong() * yStride).put(*yRow)
        for (y in 0 until outH / 2) {
            up.position(y.toLong() * uStride).put(*uRow)
            vp.position(y.toLong() * vStride).put(*vRow)
        }
        yp.position(0); up.position(0); vp.position(0)
        n++

        val preview = if (wantPreview) {
            val w = CameraCapture.PREVIEW_WIDTH; val h = w * outH / outW
            VideoImage(w, h, IntArray(w * h) { i -> 0xFF000000.toInt() or (((i % w + shift / 2) % w) * 255 / w shl 8) })
        } else null
        return CameraCapture.Captured(frame, preview)
    }

    override fun close() {
        runCatching { avutil.av_frame_free(frame) }
    }
}
