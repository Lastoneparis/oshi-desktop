package com.oshi.desktop.call.video

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVInputFormat
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avdevice
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.PointerPointer
import java.nio.ByteOrder

/**
 * One decoded picture, ready for the screen: packed `0xAARRGGBB`, row-major.
 */
class VideoImage(val width: Int, val height: Int, val argb: IntArray) {
    /** Rotate clockwise by `code × 90°` — the phones' 2-bit wire rotation hint. */
    fun rotated(code: Int): VideoImage = when (code and 3) {
        0 -> this
        2 -> VideoImage(width, height, IntArray(argb.size) { argb[argb.size - 1 - it] })
        else -> {
            val out = IntArray(argb.size)
            val cw = (code and 3) == 1
            for (y in 0 until height) for (x in 0 until width) {
                val nx = if (cw) height - 1 - y else y
                val ny = if (cw) x else width - 1 - x
                out[ny * height + nx] = argb[y * width + x]
            }
            VideoImage(height, width, out)
        }
    }

    /** Horizontal mirror — a self-view is shown mirrored, as in every video-call app. */
    fun mirrored(): VideoImage {
        val out = IntArray(argb.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) out[row + x] = argb[row + width - 1 - x]
        }
        return VideoImage(width, height, out)
    }
}

/**
 * Whether this machine can do video at all, and why not.
 *
 * The natives are a per-OS classifier jar (build.gradle.kts VIDEO block) and may be
 * absent — Windows on ARM has none — so the first touch of any `org.bytedeco` class can
 * throw `UnsatisfiedLinkError` / `NoClassDefFoundError`. That is caught ONCE, here, and
 * turned into a sentence; a call then carries audio and says video is unavailable.
 */
object FfmpegVideo {
    @Volatile private var loaded: Result<Unit>? = null

    @Synchronized
    fun load(): Result<Unit> {
        loaded?.let { return it }
        val r = runCatching {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
            // Touch the codec library the call actually needs (encode/decode/scale).
            // libavdevice is NOT loaded here — see [loadDevices].
            avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264)
            Unit
        }
        loaded = r
        return r
    }

    @Volatile private var devicesLoaded: Result<Unit>? = null

    /**
     * libavdevice — the camera inputs (avfoundation / dshow / v4l2) — loaded separately and
     * only when a camera is opened.
     *
     * It has native dependencies the codecs do not (on Linux: ALSA, X11/xcb, libv4l), and
     * on a machine without one of them JavaCPP reports `no jniavdevice in
     * java.library.path`. Measured on GitHub's ubuntu-latest: loading it inside [load]
     * turned that into "the video library did not load" and switched off the WHOLE video
     * path — this client could not even decode the peer's picture, nor send a synthetic
     * one. A machine that cannot open a camera can still show the other person's video.
     */
    @Synchronized
    fun loadDevices(): Result<Unit> {
        devicesLoaded?.let { return it }
        val r = load().mapCatching { avdevice.avdevice_register_all() }
        devicesLoaded = r
        return r
    }

    val available: Boolean get() = load().isSuccess

    fun unavailableReason(): String? = load().exceptionOrNull()?.let {
        "the video library did not load on this machine (${it.javaClass.simpleName}: ${it.message})"
    }

    fun err(code: Int): String {
        val buf = BytePointer(256L)
        avutil.av_strerror(code, buf, 256L)
        return "${buf.string} ($code)"
    }

    /** Names of the H.264 encoders present, best first. */
    fun encoderCandidates(): List<String> {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val preferred = when {
            os.contains("mac") -> listOf("h264_videotoolbox", "libopenh264")
            os.contains("win") -> listOf("libopenh264", "h264_mf")
            else -> listOf("libopenh264")
        }
        return preferred.filter { avcodec.avcodec_find_encoder_by_name(it) != null }
    }

    internal fun bytesOf(pkt: AVPacket): ByteArray {
        val out = ByteArray(pkt.size())
        pkt.data().capacity(pkt.size().toLong()).get(out)
        return out
    }
}

/** Colour-space conversion between two fixed geometries. */
internal class Scaler(
    private val srcW: Int, private val srcH: Int, private val srcFmt: Int,
    val dstW: Int, val dstH: Int, private val dstFmt: Int,
) : AutoCloseable {
    private val sws: SwsContext = swscale.sws_getContext(
        srcW, srcH, srcFmt, dstW, dstH, dstFmt, swscale.SWS_BILINEAR,
        null, null, null as DoublePointer?,
    ) ?: error("sws_getContext refused $srcW×$srcH fmt=$srcFmt → $dstW×$dstH fmt=$dstFmt")

    val out: AVFrame = avutil.av_frame_alloc().apply {
        format(dstFmt); width(dstW); height(dstH)
        check(avutil.av_frame_get_buffer(this, 32) >= 0) { "av_frame_get_buffer failed" }
    }

    fun matches(w: Int, h: Int, fmt: Int) = w == srcW && h == srcH && fmt == srcFmt

    fun scale(src: AVFrame): AVFrame {
        avutil.av_frame_make_writable(out)
        swscale.sws_scale(sws, src.data(), src.linesize(), 0, srcH, out.data(), out.linesize())
        return out
    }

    /** [out] must be BGRA. Packed rows → ARGB ints (BGRA little-endian IS 0xAARRGGBB). */
    fun toImage(): VideoImage {
        val stride = out.linesize(0)
        val buf = out.data(0).capacity(stride.toLong() * dstH).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
        val px = IntArray(dstW * dstH)
        for (y in 0 until dstH) {
            buf.position(y * stride)
            buf.asIntBuffer().get(px, y * dstW, dstW)
        }
        return VideoImage(dstW, dstH, px)
    }

    override fun close() {
        swscale.sws_freeContext(sws)
        avutil.av_frame_free(out)
    }
}

/**
 * The H.264 encoder. Settings are the phones' (`VideoCallManager.swift:2035-2076`,
 * `VideoCallManager.kt:1103-1125`): 30 fps, a keyframe every 30 frames, no B-frames
 * (frame reordering off), real-time rate control. Main profile where the encoder offers it
 * (VideoToolbox, as on iPhone), Constrained Baseline on OpenH264 — a strict SUBSET of Main,
 * so every decoder that takes the phones' stream takes this one.
 */
class H264Encoder(
    val width: Int,
    val height: Int,
    val fps: Int = 30,
    val bitrate: Long = DEFAULT_BITRATE,
    names: List<String> = FfmpegVideo.encoderCandidates(),
) : AutoCloseable {
    val name: String
    private val ctx: AVCodecContext
    private val pkt: AVPacket = avcodec.av_packet_alloc()
    private var pts = 0L

    init {
        var opened: Pair<String, AVCodecContext>? = null
        val failures = ArrayList<String>()
        for (n in names) {
            val codec: AVCodec = avcodec.avcodec_find_encoder_by_name(n) ?: continue
            val c = avcodec.avcodec_alloc_context3(codec)
            c.width(width); c.height(height)
            c.pix_fmt(avutil.AV_PIX_FMT_YUV420P)
            c.time_base(avutil.av_make_q(1, fps))
            c.framerate(avutil.av_make_q(fps, 1))
            c.gop_size(KEYFRAME_INTERVAL)
            c.max_b_frames(0)
            c.bit_rate(bitrate)
            c.rc_max_rate(bitrate * 3 / 2)
            c.rc_buffer_size(bitrate.toInt())
            c.flags(c.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
            val opts = AVDictionary(null)
            if (n == "h264_videotoolbox") {
                avutil.av_dict_set(opts, "profile", "main", 0)
                avutil.av_dict_set(opts, "realtime", "1", 0)
                avutil.av_dict_set(opts, "allow_sw", "1", 0)
                avutil.av_dict_set(opts, "prio_speed", "1", 0)
            }
            if (n == "libopenh264") {
                avutil.av_dict_set(opts, "profile", "constrained_baseline", 0)
                avutil.av_dict_set(opts, "allow_skip_frames", "0", 0)
            }
            val rc = avcodec.avcodec_open2(c, codec, opts)
            avutil.av_dict_free(opts)
            if (rc >= 0) { opened = n to c; break }
            failures += "$n: ${FfmpegVideo.err(rc)}"
            avcodec.avcodec_free_context(c)
        }
        val o = opened ?: throw IllegalStateException("no H.264 encoder would open (${failures.ifEmpty { listOf("none present") }})")
        name = o.first
        ctx = o.second
    }

    class Encoded(val annexB: ByteArray, val key: Boolean)

    /** [frame] must be YUV420P at width×height. */
    fun encode(frame: AVFrame, forceKey: Boolean): List<Encoded> {
        frame.pts(pts++)
        frame.pict_type(if (forceKey) avutil.AV_PICTURE_TYPE_I else avutil.AV_PICTURE_TYPE_NONE)
        frame.key_frame(if (forceKey) 1 else 0)
        val rc = avcodec.avcodec_send_frame(ctx, frame)
        if (rc < 0) throw IllegalStateException("encoder refused a frame: ${FfmpegVideo.err(rc)}")
        return drain()
    }

    private fun drain(): List<Encoded> {
        val out = ArrayList<Encoded>(1)
        while (true) {
            val r = avcodec.avcodec_receive_packet(ctx, pkt)
            if (r == avutil.AVERROR_EAGAIN() || r == avutil.AVERROR_EOF) break
            if (r < 0) throw IllegalStateException("encoder failed: ${FfmpegVideo.err(r)}")
            out += Encoded(FfmpegVideo.bytesOf(pkt), (pkt.flags() and avcodec.AV_PKT_FLAG_KEY) != 0)
            avcodec.av_packet_unref(pkt)
        }
        return out
    }

    override fun close() {
        runCatching { avcodec.avcodec_free_context(ctx) }
        runCatching { avcodec.av_packet_free(pkt) }
    }

    companion object {
        /** iOS `.medium` / Android `MEDIUM` — 1.2 Mbit/s. */
        const val DEFAULT_BITRATE = 1_200_000L
        /** `kVTCompressionPropertyKey_MaxKeyFrameInterval = 30`, `KEY_I_FRAME_INTERVAL = 1` s at 30 fps. */
        const val KEYFRAME_INTERVAL = 30
    }
}

/**
 * The H.264 decoder: Annex-B access units in, [VideoImage]s out. Geometry changes
 * (a phone rotating) are followed — the output scaler is rebuilt on a new size.
 */
class H264Decoder : AutoCloseable {
    private val ctx: AVCodecContext
    private val pkt: AVPacket = avcodec.av_packet_alloc()
    private val frame: AVFrame = avutil.av_frame_alloc()
    private var scaler: Scaler? = null
    private var pendingParams: ByteArray? = null

    var decoded = 0L
        private set
    var errors = 0L
        private set

    init {
        val codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264)
            ?: throw IllegalStateException("this FFmpeg build has no H.264 decoder")
        ctx = avcodec.avcodec_alloc_context3(codec)
        ctx.flags(ctx.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
        ctx.thread_count(1)
        val rc = avcodec.avcodec_open2(ctx, codec, null as AVDictionary?)
        check(rc >= 0) { "H.264 decoder would not open: ${FfmpegVideo.err(rc)}" }
    }

    fun setParameterSets(sps: ByteArray, pps: ByteArray) {
        pendingParams = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps
    }

    /** Decode one Annex-B access unit. Returns the pictures it completed (usually one). */
    fun decode(annexB: ByteArray): List<VideoImage> {
        val bytes = pendingParams?.let { pendingParams = null; it + annexB } ?: annexB
        val data = BytePointer(*bytes)
        pkt.data(data); pkt.size(bytes.size)
        val out = ArrayList<VideoImage>(1)
        try {
            val rc = avcodec.avcodec_send_packet(ctx, pkt)
            if (rc < 0) { errors++; return out }
            while (true) {
                val r = avcodec.avcodec_receive_frame(ctx, frame)
                if (r == avutil.AVERROR_EAGAIN() || r == avutil.AVERROR_EOF) break
                if (r < 0) { errors++; break }
                out += toImage(frame)
                decoded++
            }
        } finally {
            pkt.data(null as BytePointer?); pkt.size(0)
            data.close()
        }
        return out
    }

    private fun toImage(f: AVFrame): VideoImage {
        val s = scaler?.takeIf { it.matches(f.width(), f.height(), f.format()) } ?: run {
            scaler?.close()
            Scaler(f.width(), f.height(), f.format(), f.width(), f.height(), avutil.AV_PIX_FMT_BGRA).also { scaler = it }
        }
        s.scale(f)
        return s.toImage()
    }

    override fun close() {
        runCatching { scaler?.close() }
        runCatching { avcodec.avcodec_free_context(ctx) }
        runCatching { avcodec.av_packet_free(pkt) }
        runCatching { avutil.av_frame_free(frame) }
    }
}

/**
 * The laptop camera, through libavdevice: `avfoundation` on macOS, `dshow` on Windows,
 * `video4linux2` on Linux. Frames are delivered as YUV420P at [outW]×[outH] (for the
 * encoder) plus an optional BGRA preview for the self-view.
 */
/**
 * Where a call's outgoing pictures come from: the camera, or — on a machine that has none,
 * such as a CI runner — [SyntheticCamera]. Frames are YUV420P at [outW]×[outH].
 */
interface VideoSource : AutoCloseable {
    val outW: Int
    val outH: Int
    val deviceName: String
    /** Block for the next frame; null on a transient failure; throws when the source is gone. */
    fun next(wantPreview: Boolean): CameraCapture.Captured?
}

class CameraCapture(
    override val outW: Int = DEFAULT_WIDTH,
    override val outH: Int = DEFAULT_HEIGHT,
    val fps: Int = 30,
    /**
     * __VIDEO_NOTE_2026_09_24__ Centre-crop every camera frame to a square BEFORE scaling, so a
     * round video note (outW == outH) is not squashed from the camera's 16:9 / 4:3. Default
     * off: a call keeps exactly the pipeline it had.
     */
    private val cropSquare: Boolean = false,
) : VideoSource {
    private val fmtCtx: AVFormatContext
    private val decCtx: AVCodecContext
    private val streamIndex: Int
    private val pkt: AVPacket = avcodec.av_packet_alloc()
    private val raw: AVFrame = avutil.av_frame_alloc()
    private var yuv: Scaler? = null
    private var preview: Scaler? = null
    override val deviceName: String

    init {
        FfmpegVideo.loadDevices().onFailure {
            throw CameraUnavailable("the camera library did not load on this machine (${it.javaClass.simpleName}: ${it.message})")
        }
        val os = System.getProperty("os.name").orEmpty().lowercase()
        // __CALL_DEVICES_2026_09_23__ the camera the user picked, while it is still present.
        val chosen = com.oshi.desktop.call.media.CallDevices.camera
        val (fmtName, device) = when {
            os.contains("mac") -> "avfoundation" to "default:none"
            os.contains("win") -> "dshow" to "video=${
                chosen?.takeIf { c -> dshowCameras().any { it.first == c } }
                    ?: firstDshowCamera() ?: throw CameraUnavailable(NO_CAMERA_WINDOWS)
            }"
            else -> "video4linux2" to (chosen?.takeIf { java.io.File(it).exists() } ?: "/dev/video0")
        }
        deviceName = device
        val input: AVInputFormat = avformat.av_find_input_format(fmtName)
            ?: throw IllegalStateException("this FFmpeg build has no '$fmtName' camera input")
        var ctx: AVFormatContext? = null
        var lastErr = 0
        // The exact size first, then whatever the camera offers. avfoundation refuses a
        // size or rate the device does not list rather than scaling to it.
        //
        // dshow (Windows): most USB/laptop webcams list 1280×720@30 ONLY as MJPEG, and
        // dshow without `vcodec` picks the raw pin, which then refuses that size — so MJPEG
        // is tried first there. `rtbufsize` is raised from dshow's 3 MB default, which at
        // 720p fills in a few frames and makes FFmpeg drop with "real-time buffer too full".
        val attempts: List<Pair<String?, String?>> =
            if (fmtName == "dshow") listOf("1280x720" to "mjpeg", "1280x720" to null, "640x480" to "mjpeg", "640x480" to null, null to null)
            else listOf("1280x720" to null, "640x480" to null, null to null)
        for ((size, vcodec) in attempts) {
            val c = AVFormatContext(null)
            val opts = AVDictionary(null)
            avutil.av_dict_set(opts, "framerate", fps.toString(), 0)
            if (size != null) avutil.av_dict_set(opts, "video_size", size, 0)
            if (fmtName == "avfoundation") avutil.av_dict_set(opts, "pixel_format", "nv12", 0)
            if (fmtName == "dshow") {
                avutil.av_dict_set(opts, "rtbufsize", "64M", 0)
                if (vcodec != null) avutil.av_dict_set(opts, "vcodec", vcodec, 0)
            }
            val rc = avformat.avformat_open_input(c, device, input, opts)
            avutil.av_dict_free(opts)
            if (rc >= 0) { ctx = c; break }
            lastErr = rc
        }
        fmtCtx = ctx ?: throw CameraUnavailable(
            "the camera would not open (${FfmpegVideo.err(lastErr)}) — " + when (fmtName) {
                "dshow" -> "it is in use by another app (Teams, Zoom, the Camera app…), or camera " +
                    "access is off: Windows Settings → Privacy & security → Camera → " +
                    "\"Let desktop apps access your camera\""
                "avfoundation" -> "on macOS this is usually Camera permission: " +
                    "System Settings → Privacy & Security → Camera"
                else -> "check that /dev/video0 exists and that you are in the 'video' group"
            },
        )
        avformat.avformat_find_stream_info(fmtCtx, null as PointerPointer<*>?)
        var idx = -1
        for (i in 0 until fmtCtx.nb_streams()) {
            if (fmtCtx.streams(i).codecpar().codec_type() == avutil.AVMEDIA_TYPE_VIDEO) { idx = i; break }
        }
        check(idx >= 0) { "the camera input has no video stream" }
        streamIndex = idx
        val par = fmtCtx.streams(idx).codecpar()
        val codec = avcodec.avcodec_find_decoder(par.codec_id())
            ?: throw IllegalStateException("no decoder for camera codec ${par.codec_id()}")
        decCtx = avcodec.avcodec_alloc_context3(codec)
        avcodec.avcodec_parameters_to_context(decCtx, par)
        check(avcodec.avcodec_open2(decCtx, codec, null as AVDictionary?) >= 0) { "camera decoder would not open" }
    }

    class Captured(val yuv: AVFrame, val preview: VideoImage?)

    /**
     * Block for the next camera frame. [wantPreview] also renders a small BGRA copy.
     * Returns null on a transient read failure; throws when the device is gone.
     */
    override fun next(wantPreview: Boolean): Captured? {
        while (true) {
            val rc = avformat.av_read_frame(fmtCtx, pkt)
            if (rc == avutil.AVERROR_EAGAIN()) { Thread.sleep(2); continue }
            if (rc < 0) throw CameraUnavailable("the camera stopped delivering frames (${FfmpegVideo.err(rc)})")
            try {
                if (pkt.stream_index() != streamIndex) continue
                if (avcodec.avcodec_send_packet(decCtx, pkt) < 0) return null
                if (avcodec.avcodec_receive_frame(decCtx, raw) < 0) return null
            } finally {
                avcodec.av_packet_unref(pkt)
            }
            if (cropSquare && raw.width() != raw.height()) {
                val rw = raw.width(); val rh = raw.height()
                val d = kotlin.math.abs(rw - rh) / 2
                if (rw > rh) { raw.crop_left(d.toLong()); raw.crop_right((rw - rh - d).toLong()) }
                else { raw.crop_top(d.toLong()); raw.crop_bottom((rh - rw - d).toLong()) }
                avutil.av_frame_apply_cropping(raw, avutil.AV_FRAME_CROP_UNALIGNED)
            }
            val w = raw.width(); val h = raw.height(); val f = raw.format()
            val y = yuv?.takeIf { it.matches(w, h, f) } ?: run {
                yuv?.close(); Scaler(w, h, f, outW, outH, avutil.AV_PIX_FMT_YUV420P).also { yuv = it }
            }
            val yuvFrame = y.scale(raw)
            val img = if (wantPreview) {
                val p = preview?.takeIf { it.matches(w, h, f) } ?: run {
                    preview?.close()
                    Scaler(w, h, f, PREVIEW_WIDTH, PREVIEW_WIDTH * outH / outW / 2 * 2, avutil.AV_PIX_FMT_BGRA).also { preview = it }
                }
                p.scale(raw); p.toImage()
            } else null
            return Captured(yuvFrame, img)
        }
    }

    override fun close() {
        runCatching { avcodec.avcodec_free_context(decCtx) }
        runCatching { avformat.avformat_close_input(fmtCtx) }
        runCatching { avcodec.av_packet_free(pkt) }
        runCatching { avutil.av_frame_free(raw) }
        runCatching { yuv?.close() }
        runCatching { preview?.close() }
    }

    class CameraUnavailable(message: String) : IllegalStateException(message)

    companion object {
        /**
         * 640×360 — iOS's `.medium` resolution in its landscape orientation
         * (`swift:2000-2008` swaps 360×640 to 640×360 when the phone is sideways), so the
         * geometry is one the phones already produce and decode.
         */
        const val DEFAULT_WIDTH = 640
        const val DEFAULT_HEIGHT = 360
        const val PREVIEW_WIDTH = 320

        const val NO_CAMERA_WINDOWS = "no camera found — none is connected, or Windows is " +
            "hiding it: Settings → Privacy & security → Camera → \"Let desktop apps access your camera\""

        /**
         * Every DirectShow video input, as `device_name` (the moniker `dshow` accepts after
         * `video=`, unique even for two identical webcams) → friendly description.
         * Empty when there is none — never throws, so a CI runner with no camera can call it.
         */
        fun dshowCameras(): List<Pair<String, String>> = runCatching {
            FfmpegVideo.loadDevices().getOrThrow()
            val input = avformat.av_find_input_format("dshow") ?: return emptyList()
            val list = org.bytedeco.ffmpeg.avdevice.AVDeviceInfoList(null)
            val n = avdevice.avdevice_list_input_sources(input, null as String?, null as AVDictionary?, list)
            if (n <= 0 || list.isNull) return emptyList()
            try {
                (0 until list.nb_devices()).map { list.devices(it) }
                    .filter { d -> (0 until d.nb_media_types()).any { d.media_types().get(it.toLong()) == avutil.AVMEDIA_TYPE_VIDEO } }
                    .mapNotNull { d -> d.device_name()?.string?.let { it to (d.device_description()?.string ?: it) } }
            } finally {
                avdevice.avdevice_free_list_devices(list)
            }
        }.getOrDefault(emptyList())

        private fun firstDshowCamera(): String? = dshowCameras().firstOrNull()?.first
    }
}
