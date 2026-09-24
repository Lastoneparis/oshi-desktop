package com.oshi.desktop.media

import com.oshi.desktop.call.video.FfmpegVideo
import com.oshi.desktop.call.video.Scaler
import com.oshi.desktop.call.video.VideoImage
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.PointerPointer
import java.io.File

/**
 * __VIDEO_NOTE_2026_09_24__ The round video note's container, both ways, on the FFmpeg this
 * client ALREADY bundles for calls (bytedeco `ffmpeg` 7.1.1, LGPL — build.gradle.kts VIDEO
 * block). No new dependency and no new native binary:
 *
 *  - H.264 through the SAME encoder names a video call uses ([FfmpegVideo.encoderCandidates]:
 *    VideoToolbox on macOS, OpenH264 elsewhere — both inside the existing natives);
 *  - AAC-LC through FFmpeg's own `aac` encoder (in libavcodec, no libfdk);
 *  - MP4 through libavformat's `mp4` muxer, `+faststart`, with `AVFMT_FLAG_BITEXACT` so no
 *    "Lavf…" software tag and no creation date are written (VIDEO_NOTE_SPEC §3: no device or
 *    software metadata);
 *  - demux + decode for playback through libavformat/libavcodec (audio down-mixed in Kotlin).
 *
 * Sizes are the spec's (§3): square ≤ 480×480, ~30 fps, AAC mono 44.1 kHz ~64 kbit/s,
 * ≤ 15.0 s — the writer REFUSES frames past the cap rather than trusting its caller.
 */
object VideoNoteFormat {
    /** iOS emits 480×480; the spec's ceiling. */
    const val SIDE = 480
    const val FPS = 30
    /** ~1.2 Mbit/s video + 64 kbit/s audio ≈ 2.4 MB for 15 s (spec §3). */
    const val VIDEO_BITRATE = 1_200_000L
    const val AUDIO_BITRATE = 64_000L
    const val SAMPLE_RATE = VoiceNoteFormat.SAMPLE_RATE
    /** Spec §3 hard limit. */
    const val MAX_DURATION_MS = VideoNoteWire.MAX_DURATION_MS
    /** Shorter than this is a mis-click, not a message. */
    const val MIN_DURATION_MS = 500L
    /** One frame at [FPS], in ms — the last frame's display time. */
    const val FRAME_MS = 1000L / FPS

    /** `0:07`, the shape both phones print. */
    fun formatDuration(ms: Long): String {
        val total = ((ms + 500) / 1000L).coerceAtLeast(0L)
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
}

/** The outcome of [VideoNoteMp4Writer.finish]. */
data class VideoNoteFile(val file: File, val durationMs: Long, val side: Int, val hasAudio: Boolean, val videoEncoder: String)

/**
 * Encode as the frames arrive, mux once at the end.
 *
 * H.264 packets are kept IN MEMORY (≤ 15 s × 1.2 Mbit/s ≈ 2.3 MB) and the audio is handed
 * over as PCM at [finish]; the MP4 is written in one pass then. That keeps the capture thread
 * free of a muxer and the file never exists half-written: a cancelled note leaves nothing on disk.
 */
class VideoNoteMp4Writer(
    val side: Int = VideoNoteFormat.SIDE,
    val fps: Int = VideoNoteFormat.FPS,
    private val maxDurationMs: Long = VideoNoteFormat.MAX_DURATION_MS,
    names: List<String> = FfmpegVideo.encoderCandidates(),
) : AutoCloseable {
    val encoderName: String
    private val ctx: AVCodecContext
    private val pkt: AVPacket = avcodec.av_packet_alloc()
    private val packets = ArrayList<Encoded>()
    private var lastPts = -1L
    private var closed = false

    /** Frames accepted so far. */
    var frames = 0
        private set

    private class Encoded(val data: ByteArray, val pts: Long, val dts: Long, val key: Boolean)

    init {
        FfmpegVideo.load().getOrThrow()
        require(side in 16..VideoNoteFormat.SIDE && side % 2 == 0) { "side must be even and ≤ ${VideoNoteFormat.SIDE}: $side" }
        var opened: Pair<String, AVCodecContext>? = null
        val failures = ArrayList<String>()
        for (n in names) {
            val codec = avcodec.avcodec_find_encoder_by_name(n) ?: continue
            val c = avcodec.avcodec_alloc_context3(codec)
            c.width(side); c.height(side)
            c.pix_fmt(avutil.AV_PIX_FMT_YUV420P)
            // Millisecond clock: capture timestamps are real, not a frame counter.
            c.time_base(avutil.av_make_q(1, 1000))
            c.framerate(avutil.av_make_q(fps, 1))
            c.gop_size(fps)
            c.max_b_frames(0)
            c.bit_rate(VideoNoteFormat.VIDEO_BITRATE)
            c.rc_max_rate(VideoNoteFormat.VIDEO_BITRATE * 3 / 2)
            c.rc_buffer_size(VideoNoteFormat.VIDEO_BITRATE.toInt())
            // MP4 wants SPS/PPS in the avcC box, i.e. out of band.
            c.flags(c.flags() or avcodec.AV_CODEC_FLAG_GLOBAL_HEADER)
            val opts = AVDictionary(null)
            if (n == "h264_videotoolbox") {
                avutil.av_dict_set(opts, "profile", "main", 0)
                avutil.av_dict_set(opts, "realtime", "1", 0)
                avutil.av_dict_set(opts, "allow_sw", "1", 0)
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
        encoderName = o.first
        ctx = o.second
    }

    /** True while another frame at [ptsMs] still fits under the cap (its display time included). */
    fun accepts(ptsMs: Long): Boolean = !closed && ptsMs >= 0 && ptsMs + VideoNoteFormat.FRAME_MS <= maxDurationMs

    /**
     * Encode one YUV420P [side]×[side] frame captured [ptsMs] after the start. Returns false —
     * and encodes nothing — once the frame would end past the 15 s cap: THE HARD CAP LIVES HERE,
     * not in a UI timer. Timestamps are forced strictly increasing.
     */
    fun addFrame(yuv: AVFrame, ptsMs: Long): Boolean {
        if (!accepts(ptsMs)) return false
        val pts = if (ptsMs <= lastPts) lastPts + 1 else ptsMs
        if (!accepts(pts)) return false
        lastPts = pts
        yuv.pts(pts)
        yuv.pict_type(avutil.AV_PICTURE_TYPE_NONE)
        val rc = avcodec.avcodec_send_frame(ctx, yuv)
        if (rc < 0) throw IllegalStateException("encoder refused a frame: ${FfmpegVideo.err(rc)}")
        drain()
        frames++
        return true
    }

    private fun drain() {
        while (true) {
            val r = avcodec.avcodec_receive_packet(ctx, pkt)
            if (r == avutil.AVERROR_EAGAIN() || r == avutil.AVERROR_EOF) break
            if (r < 0) throw IllegalStateException("encoder failed: ${FfmpegVideo.err(r)}")
            val dts = if (pkt.dts() == avutil.AV_NOPTS_VALUE) pkt.pts() else pkt.dts()
            packets += Encoded(bytesOf(pkt), pkt.pts(), dts, (pkt.flags() and avcodec.AV_PKT_FLAG_KEY) != 0)
            avcodec.av_packet_unref(pkt)
        }
    }

    /** Video duration so far: last frame's time plus its display time. */
    val durationMs: Long get() = if (lastPts < 0) 0 else minOf(lastPts + VideoNoteFormat.FRAME_MS, maxDurationMs)

    /**
     * Flush the encoder, AAC-encode [pcm16Mono] (little-endian s16 at [sampleRate]; null = no
     * audio track), truncate the audio to the picture's length, and write [out].
     * A failure deletes [out]: a half-written MP4 is never left behind.
     */
    fun finish(out: File, pcm16Mono: ByteArray?, sampleRate: Int = VideoNoteFormat.SAMPLE_RATE): VideoNoteFile {
        check(!closed) { "writer already finished" }
        check(frames > 0) { "no frame was captured" }
        avcodec.avcodec_send_frame(ctx, null as AVFrame?)
        drain()
        closed = true
        val videoMs = durationMs
        // Truncate the audio to the picture — never longer than the note, never past the cap.
        val pcm = pcm16Mono?.let {
            val maxBytes = (videoMs * sampleRate / 1000L).toInt() * 2
            if (it.size > maxBytes) it.copyOf(maxBytes) else it
        }?.takeIf { it.size >= 2 * 1024 }
        try {
            mux(out, pcm, sampleRate, videoMs)
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        return VideoNoteFile(out, videoMs, side, pcm != null, encoderName)
    }

    private fun mux(out: File, pcm: ByteArray?, sampleRate: Int, videoMs: Long) {
        val audio = pcm?.let { AacEncoded.encode(it, sampleRate) }
        val fmt = AVFormatContext(null)
        var rc = avformat.avformat_alloc_output_context2(fmt, null, "mp4", out.absolutePath)
        check(rc >= 0 && !fmt.isNull) { "no mp4 muxer: ${FfmpegVideo.err(rc)}" }
        var pb: AVIOContext? = null
        try {
            // No "Lavf" encoder string, no wall-clock creation time (spec §3).
            fmt.flags(fmt.flags() or AVFMT_FLAG_BITEXACT)
            val vs = avformat.avformat_new_stream(fmt, null)
            check(avcodec.avcodec_parameters_from_context(vs.codecpar(), ctx) >= 0) { "video parameters" }
            vs.time_base(ctx.time_base())
            val vtb: AVRational = ctx.time_base()
            var atb: AVRational? = null
            val asIndex = if (audio != null) {
                val st = avformat.avformat_new_stream(fmt, null)
                check(avcodec.avcodec_parameters_copy(st.codecpar(), audio.params) >= 0) { "audio parameters" }
                st.time_base(avutil.av_make_q(1, sampleRate))
                atb = avutil.av_make_q(1, sampleRate)
                st.index()
            } else -1

            pb = AVIOContext(null)
            rc = avformat.avio_open(pb, out.absolutePath, avformat.AVIO_FLAG_WRITE)
            check(rc >= 0) { "cannot open ${out.name} for writing: ${FfmpegVideo.err(rc)}" }
            fmt.pb(pb)
            val opts = AVDictionary(null)
            avutil.av_dict_set(opts, "movflags", "+faststart", 0)
            rc = avformat.avformat_write_header(fmt, opts)
            avutil.av_dict_free(opts)
            check(rc >= 0) { "mp4 header: ${FfmpegVideo.err(rc)}" }

            // Merge the two queues by presentation time (in seconds) and let libavformat
            // interleave; ≤ 15 s of packets, so ordering in memory costs nothing.
            data class Out(val t: Double, val stream: Int, val data: ByteArray, val pts: Long, val dts: Long, val dur: Long, val key: Boolean, val tb: AVRational)
            val queue = ArrayList<Out>(packets.size + (audio?.packets?.size ?: 0))
            packets.forEachIndexed { i, p ->
                val next = packets.getOrNull(i + 1)?.pts ?: videoMs
                queue += Out(p.dts / 1000.0, vs.index(), p.data, p.pts, p.dts, (next - p.pts).coerceAtLeast(1), p.key, vtb)
            }
            audio?.packets?.forEach { a ->
                queue += Out(a.pts.toDouble() / sampleRate, asIndex, a.data, a.pts, a.pts, a.duration, true, atb!!)
            }
            queue.sortBy { it.t }
            val wp = avcodec.av_packet_alloc()
            try {
                for (q in queue) {
                    check(avcodec.av_new_packet(wp, q.data.size) >= 0) { "packet alloc" }
                    wp.data().capacity(q.data.size.toLong()).put(*q.data)
                    wp.pts(q.pts); wp.dts(q.dts); wp.duration(q.dur)
                    wp.stream_index(q.stream)
                    if (q.key) wp.flags(wp.flags() or avcodec.AV_PKT_FLAG_KEY)
                    avcodec.av_packet_rescale_ts(wp, q.tb, fmt.streams(q.stream).time_base())
                    rc = avformat.av_interleaved_write_frame(fmt, wp)
                    avcodec.av_packet_unref(wp)
                    check(rc >= 0) { "mp4 write: ${FfmpegVideo.err(rc)}" }
                }
            } finally {
                avcodec.av_packet_free(wp)
            }
            rc = avformat.av_write_trailer(fmt)
            check(rc >= 0) { "mp4 trailer: ${FfmpegVideo.err(rc)}" }
        } finally {
            audio?.close()
            if (pb != null && !pb.isNull) runCatching { avformat.avio_closep(pb) }
            fmt.pb(null as AVIOContext?)
            avformat.avformat_free_context(fmt)
        }
    }

    override fun close() {
        closed = true
        runCatching { avcodec.avcodec_free_context(ctx) }
        runCatching { avcodec.av_packet_free(pkt) }
        packets.clear()
    }

    internal companion object {
        /** libavformat's `AVFMT_FLAG_BITEXACT` (avformat.h: 0x0400) — a #define bytedeco does not map. */
        const val AVFMT_FLAG_BITEXACT = 0x0400

        fun bytesOf(p: AVPacket): ByteArray {
            val b = ByteArray(p.size())
            p.data().capacity(p.size().toLong()).get(b)
            return b
        }
    }
}

/** AAC-LC mono via FFmpeg's native `aac` encoder: s16 PCM in, packets + codec parameters out. */
internal class AacEncoded private constructor(
    val params: org.bytedeco.ffmpeg.avcodec.AVCodecParameters,
    val packets: List<Packet>,
) : AutoCloseable {
    class Packet(val data: ByteArray, val pts: Long, val duration: Long)

    override fun close() { avcodec.avcodec_parameters_free(params) }

    companion object {
        fun encode(pcm: ByteArray, sampleRate: Int): AacEncoded {
            val codec = avcodec.avcodec_find_encoder(avcodec.AV_CODEC_ID_AAC)
                ?: throw IllegalStateException("this FFmpeg build has no AAC encoder")
            val c = avcodec.avcodec_alloc_context3(codec)
            val frame = avutil.av_frame_alloc()
            val pkt = avcodec.av_packet_alloc()
            try {
                c.sample_fmt(avutil.AV_SAMPLE_FMT_FLTP)
                c.sample_rate(sampleRate)
                avutil.av_channel_layout_default(c.ch_layout(), 1)
                c.bit_rate(VideoNoteFormat.AUDIO_BITRATE)
                c.time_base(avutil.av_make_q(1, sampleRate))
                c.flags(c.flags() or avcodec.AV_CODEC_FLAG_GLOBAL_HEADER)
                val rc = avcodec.avcodec_open2(c, codec, null as AVDictionary?)
                check(rc >= 0) { "AAC encoder would not open: ${FfmpegVideo.err(rc)}" }
                val n = c.frame_size().takeIf { it > 0 } ?: 1024
                frame.nb_samples(n); frame.format(avutil.AV_SAMPLE_FMT_FLTP); frame.sample_rate(sampleRate)
                avutil.av_channel_layout_copy(frame.ch_layout(), c.ch_layout())
                check(avutil.av_frame_get_buffer(frame, 0) >= 0) { "audio frame buffer" }

                val out = ArrayList<Packet>()
                fun drain() {
                    while (true) {
                        val r = avcodec.avcodec_receive_packet(c, pkt)
                        if (r == avutil.AVERROR_EAGAIN() || r == avutil.AVERROR_EOF) break
                        check(r >= 0) { "AAC encode: ${FfmpegVideo.err(r)}" }
                        out += Packet(VideoNoteMp4Writer.bytesOf(pkt), pkt.pts(), pkt.duration())
                        avcodec.av_packet_unref(pkt)
                    }
                }
                val samples = pcm.size / 2
                val floats = FloatArray(n)
                var at = 0
                while (at < samples) {
                    val count = minOf(n, samples - at)
                    for (i in 0 until n) {
                        floats[i] = if (i < count) {
                            val o = (at + i) * 2
                            ((pcm[o].toInt() and 0xFF) or (pcm[o + 1].toInt() shl 8)).toShort() / 32768f
                        } else 0f
                    }
                    avutil.av_frame_make_writable(frame)
                    FloatPointer(frame.data(0)).put(floats, 0, n)
                    frame.nb_samples(n)
                    frame.pts(at.toLong())
                    check(avcodec.avcodec_send_frame(c, frame) >= 0) { "AAC refused a frame" }
                    drain()
                    at += count
                }
                avcodec.avcodec_send_frame(c, null as AVFrame?)
                drain()
                val params = avcodec.avcodec_parameters_alloc()
                avcodec.avcodec_parameters_from_context(params, c)
                return AacEncoded(params, out)
            } finally {
                avutil.av_frame_free(frame)
                avcodec.av_packet_free(pkt)
                avcodec.avcodec_free_context(c)
            }
        }
    }
}

/**
 * Demux + decode a received (or just recorded) note for playback: square pictures scaled to
 * [targetSide], and audio as s16 mono at [VideoNoteFormat.SAMPLE_RATE]. Reads a PLAINTEXT
 * file — the caller decrypts it to `media-tmp/` and deletes it after [close].
 *
 * Hostile input: a stranger chose these bytes. Dimensions are capped ([MAX_SOURCE_SIDE]),
 * decoding errors skip a packet instead of throwing, and [next] stops at [maxDurationMs]
 * of media whatever the container claims.
 */
class VideoNoteReader(
    file: File,
    private val targetSide: Int = 240,
    private val maxDurationMs: Long = VideoNoteFormat.MAX_DURATION_MS + 1_000,
) : AutoCloseable {
    sealed class Item(val ptsMs: Long) {
        class Picture(ptsMs: Long, val image: VideoImage) : Item(ptsMs)
        class Sound(ptsMs: Long, val pcm16Mono: ByteArray) : Item(ptsMs)
    }

    private val fmt = AVFormatContext(null)
    private val vIndex: Int
    private val aIndex: Int
    private val vDec: AVCodecContext
    private val aDec: AVCodecContext?
    private val pkt = avcodec.av_packet_alloc()
    private val frame = avutil.av_frame_alloc()
    private var scaler: Scaler? = null
    private val pending = ArrayDeque<Item>()
    private var eof = false

    /** Container duration in ms, or null when it does not say. */
    val durationMs: Long?
    val width: Int
    val height: Int
    val hasAudio: Boolean get() = aDec != null

    init {
        FfmpegVideo.load().getOrThrow()
        var rc = avformat.avformat_open_input(fmt, file.absolutePath, null, null as AVDictionary?)
        check(rc >= 0) { "not a playable video (${FfmpegVideo.err(rc)})" }
        try {
            avformat.avformat_find_stream_info(fmt, null as PointerPointer<*>?)
            vIndex = avformat.av_find_best_stream(fmt, avutil.AVMEDIA_TYPE_VIDEO, -1, -1, null as org.bytedeco.ffmpeg.avcodec.AVCodec?, 0)
            check(vIndex >= 0) { "the file has no video stream" }
            val vpar = fmt.streams(vIndex).codecpar()
            width = vpar.width(); height = vpar.height()
            check(width in 1..MAX_SOURCE_SIDE && height in 1..MAX_SOURCE_SIDE) { "video is ${width}×$height, over this player's ${MAX_SOURCE_SIDE}px ceiling" }
            vDec = openDecoder(vpar) ?: throw IllegalStateException("no decoder for this video codec")
            aIndex = avformat.av_find_best_stream(fmt, avutil.AVMEDIA_TYPE_AUDIO, -1, -1, null as org.bytedeco.ffmpeg.avcodec.AVCodec?, 0)
            aDec = if (aIndex >= 0) openDecoder(fmt.streams(aIndex).codecpar()) else null
            durationMs = fmt.duration().takeIf { it > 0 && it != avutil.AV_NOPTS_VALUE }?.let { it / 1000 }
        } catch (e: Throwable) {
            avformat.avformat_close_input(fmt)
            throw e
        }
    }

    private fun openDecoder(par: org.bytedeco.ffmpeg.avcodec.AVCodecParameters): AVCodecContext? {
        val codec = avcodec.avcodec_find_decoder(par.codec_id()) ?: return null
        val c = avcodec.avcodec_alloc_context3(codec)
        avcodec.avcodec_parameters_to_context(c, par)
        c.thread_count(1)
        if (avcodec.avcodec_open2(c, codec, null as AVDictionary?) < 0) { avcodec.avcodec_free_context(c); return null }
        return c
    }

    /** Next decoded item in file order, or null at the end. */
    fun next(): Item? {
        while (pending.isEmpty() && !eof) readOne()
        return pending.removeFirstOrNull()
    }

    /** The first picture only — the bubble's still. */
    fun firstPicture(): VideoImage? {
        while (true) {
            val it = next() ?: return null
            if (it is Item.Picture) return it.image
        }
    }

    private fun readOne() {
        val rc = avformat.av_read_frame(fmt, pkt)
        if (rc < 0) {
            eof = true
            flush(vDec, vIndex); aDec?.let { flush(it, aIndex) }
            return
        }
        try {
            when (pkt.stream_index()) {
                vIndex -> decode(vDec, vIndex, pkt)
                aIndex -> aDec?.let { decode(it, aIndex, pkt) }
            }
        } finally {
            avcodec.av_packet_unref(pkt)
        }
    }

    private fun flush(c: AVCodecContext, index: Int) = decode(c, index, null)

    private fun decode(c: AVCodecContext, index: Int, p: AVPacket?) {
        if (avcodec.avcodec_send_packet(c, p) < 0) return   // a damaged packet is skipped, not fatal
        while (true) {
            val r = avcodec.avcodec_receive_frame(c, frame)
            if (r < 0) break
            val tb = fmt.streams(index).time_base()
            val ts = frame.best_effort_timestamp().takeIf { it != avutil.AV_NOPTS_VALUE } ?: frame.pts()
            val ms = if (ts == avutil.AV_NOPTS_VALUE) 0L else avutil.av_rescale_q(ts, tb, avutil.av_make_q(1, 1000))
            if (ms > maxDurationMs) { eof = true; break }
            if (index == vIndex) pending += Item.Picture(ms, picture(frame)) else sound(frame)?.let { pending += Item.Sound(ms, it) }
            avutil.av_frame_unref(frame)
        }
    }

    private fun picture(f: AVFrame): VideoImage {
        // Centre-crop to a square before scaling: a note from a client that sent 16:9 is
        // still drawn round without being squashed.
        val w = f.width(); val h = f.height()
        if (w != h) {
            val d = kotlin.math.abs(w - h) / 2
            if (w > h) { f.crop_left(d.toLong()); f.crop_right((w - h - d).toLong()) }
            else { f.crop_top(d.toLong()); f.crop_bottom((h - w - d).toLong()) }
            avutil.av_frame_apply_cropping(f, avutil.AV_FRAME_CROP_UNALIGNED)
        }
        val s = scaler?.takeIf { it.matches(f.width(), f.height(), f.format()) } ?: run {
            scaler?.close()
            Scaler(f.width(), f.height(), f.format(), targetSide, targetSide, avutil.AV_PIX_FMT_BGRA).also { scaler = it }
        }
        s.scale(f)
        return s.toImage()
    }

    /**
     * Decoded audio → s16 mono at [VideoNoteFormat.SAMPLE_RATE], in Kotlin. The formats an AAC
     * (or any phone) decoder produces — float/s16, planar or packed — are down-mixed here and a
     * foreign rate is linearly resampled. (libswresample was tried first and crashed natively
     * inside `swr_convert` through the bytedeco binding; a 15 s mono note does not need it.)
     */
    private fun sound(f: AVFrame): ByteArray? {
        if (aDec == null) return null
        val n = f.nb_samples()
        val ch = f.ch_layout().nb_channels().coerceIn(1, 8)
        if (n <= 0) return null
        val fmt = f.format()
        val planar = avutil.av_sample_fmt_is_planar(fmt) != 0
        val mono = FloatArray(n)
        when (avutil.av_get_packed_sample_fmt(fmt)) {
            avutil.AV_SAMPLE_FMT_FLT -> {
                if (planar) for (c in 0 until ch) {
                    val p = FloatPointer(f.data(c)).capacity(n.toLong())
                    for (i in 0 until n) mono[i] += p.get(i.toLong())
                } else {
                    val p = FloatPointer(f.data(0)).capacity(n.toLong() * ch)
                    for (i in 0 until n) for (c in 0 until ch) mono[i] += p.get((i * ch + c).toLong())
                }
            }
            avutil.AV_SAMPLE_FMT_S16 -> {
                if (planar) for (c in 0 until ch) {
                    val p = org.bytedeco.javacpp.ShortPointer(f.data(c)).capacity(n.toLong())
                    for (i in 0 until n) mono[i] += p.get(i.toLong()) / 32768f
                } else {
                    val p = org.bytedeco.javacpp.ShortPointer(f.data(0)).capacity(n.toLong() * ch)
                    for (i in 0 until n) for (c in 0 until ch) mono[i] += p.get((i * ch + c).toLong()) / 32768f
                }
            }
            else -> return null   // not a format a note's decoder produces; play the picture silently
        }
        val rate = f.sample_rate().takeIf { it > 0 } ?: VideoNoteFormat.SAMPLE_RATE
        val outN = if (rate == VideoNoteFormat.SAMPLE_RATE) n else (n.toLong() * VideoNoteFormat.SAMPLE_RATE / rate).toInt()
        val out = ByteArray(outN * 2)
        for (o in 0 until outN) {
            val v = if (outN == n) mono[o] else {
                val x = o.toDouble() * rate / VideoNoteFormat.SAMPLE_RATE
                val i0 = x.toInt().coerceAtMost(n - 1); val i1 = (i0 + 1).coerceAtMost(n - 1)
                val t = (x - i0).toFloat()
                mono[i0] * (1 - t) + mono[i1] * t
            }
            val sample = ((v / ch) * 32767f).toInt().coerceIn(-32768, 32767)
            out[2 * o] = (sample and 0xFF).toByte(); out[2 * o + 1] = (sample shr 8).toByte()
        }
        return out
    }

    override fun close() {
        runCatching { scaler?.close() }
        runCatching { avcodec.avcodec_free_context(vDec) }
        runCatching { aDec?.let { avcodec.avcodec_free_context(it) } }
        runCatching { avcodec.av_packet_free(pkt) }
        runCatching { avutil.av_frame_free(frame) }
        runCatching { avformat.avformat_close_input(fmt) }
    }

    companion object {
        /** A note is ≤ 480 px; anything past 1920 is not a note, whatever it is. */
        const val MAX_SOURCE_SIDE = 1920
    }
}
