package com.oshi.desktop.media

import com.oshi.desktop.call.video.FfmpegVideo
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import java.io.File

/**
 * A real video file of any shape, for the tests that need one that is NOT square.
 *
 * This project's only MP4 writer is the NOTE writer, and it is square by contract
 * (`require(side …)`). Widening it to make a fixture would change the note format in order
 * to test something that is not the note format. So this writes a bare Annex-B H.264
 * elementary stream instead: libavformat demuxes one perfectly well, which is all
 * [VideoNoteReader] needs, and it costs no muxer.
 *
 * The frames are ENCODED by the same natives the app ships, not faked, because the thing
 * under test is a decode.
 */
object TestClips {

    /** True when this machine can encode at all — the tests `assumeTrue` on it. */
    fun canEncode(): Boolean = FfmpegVideo.load().isSuccess && FfmpegVideo.encoderCandidates().isNotEmpty()

    /**
     * Write [frames] moving frames at [width]×[height] to [out].
     *
     * [chromaU]/[chromaV] are the constant chroma planes. The default 128/128 is neutral
     * grey; a caller that has to FIND the decoded picture inside a screenshot passes a
     * strongly coloured pair instead, so the pixels it is looking for cannot be confused
     * with the window's own greys.
     */
    fun rawH264(
        out: File,
        width: Int,
        height: Int,
        frames: Int = 12,
        chromaU: Int = 128,
        chromaV: Int = 128,
    ) {
        FfmpegVideo.load().getOrThrow()
        val ctx: AVCodecContext = FfmpegVideo.encoderCandidates().firstNotNullOfOrNull { name ->
            val codec = avcodec.avcodec_find_encoder_by_name(name) ?: return@firstNotNullOfOrNull null
            val c = avcodec.avcodec_alloc_context3(codec)
            c.width(width); c.height(height)
            c.pix_fmt(avutil.AV_PIX_FMT_YUV420P)
            c.time_base(avutil.av_make_q(1, 30))
            c.framerate(avutil.av_make_q(30, 1))
            c.gop_size(12)
            c.bit_rate(400_000)
            // NO global header: the SPS/PPS must ride in-band, because there is no container
            // here to carry them in an extradata box.
            if (avcodec.avcodec_open2(c, codec, null as AVDictionary?) < 0) {
                avcodec.avcodec_free_context(c); null
            } else c
        } ?: return

        val frame: AVFrame = avutil.av_frame_alloc().apply {
            format(avutil.AV_PIX_FMT_YUV420P); width(width); height(height)
            check(avutil.av_frame_get_buffer(this, 32) >= 0)
        }
        val pkt = avcodec.av_packet_alloc()
        out.outputStream().buffered().use { sink ->
            fun drain() {
                while (avcodec.avcodec_receive_packet(ctx, pkt) >= 0) {
                    val b = ByteArray(pkt.size())
                    pkt.data().capacity(pkt.size().toLong()).asByteBuffer().get(b)
                    sink.write(b)
                    avcodec.av_packet_unref(pkt)
                }
            }
            val u = ByteArray(width / 2) { chromaU.toByte() }
            val v = ByteArray(width / 2) { chromaV.toByte() }
            for (i in 0 until frames) {
                avutil.av_frame_make_writable(frame)
                // A gradient that MOVES, so the encoder cannot collapse the clip into a
                // single frame and leave the decoder with nothing to scale.
                val row = ByteArray(width) { x -> ((x + i * 9) % 160 + 60).toByte() }
                for (y in 0 until height) frame.data(0).position(y.toLong() * frame.linesize(0)).put(*row)
                for (y in 0 until height / 2) {
                    frame.data(1).position(y.toLong() * frame.linesize(1)).put(*u)
                    frame.data(2).position(y.toLong() * frame.linesize(2)).put(*v)
                }
                frame.data(0).position(0); frame.data(1).position(0); frame.data(2).position(0)
                frame.pts(i.toLong())
                if (avcodec.avcodec_send_frame(ctx, frame) >= 0) drain()
            }
            avcodec.avcodec_send_frame(ctx, null as AVFrame?)
            drain()
        }
        avcodec.av_packet_free(pkt)
        avutil.av_frame_free(frame)
        avcodec.avcodec_free_context(ctx)
    }
}
