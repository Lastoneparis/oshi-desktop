package com.oshi.desktop.call.video

import com.oshi.desktop.call.media.VideoControlSession
import com.oshi.desktop.call.media.VideoFragment
import com.oshi.desktop.call.media.VideoReceiveSession
import com.oshi.desktop.call.media.VideoSendSession
import com.oshi.desktop.call.media.VideoStreamSink
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Video loss bench — the real encoder of this machine, the real fragmenter/seal
 * ([VideoSendSession]) and the real receiver ([VideoReceiveSession]), with i.i.d. datagram
 * loss in between and the receiver's `0x0B` requests fed back to the encoder after a
 * simulated RTT. No network, no server. `OSHI_VIDEO_BENCH=1` to run.
 *
 * Reports: datagram size distribution (raw `0xF1` envelope, and framed as the `:8089`
 * relay frames it up- and downstream), fragments per keyframe / P-frame, and for each loss
 * rate the share of frames that COMPLETE and the share that are CLEAN (complete AND every
 * frame since the last IDR complete — what a decoder that keeps going after a hole, like
 * VideoToolbox/MediaCodec here, shows without smearing).
 */
class VideoLossBench {

    private val key = ByteArray(32) { (it * 7).toByte() }

    /** Camera-like content: a textured scene panning 3 px/frame plus per-pixel sensor noise. */
    private class Scene(val w: Int, val h: Int) {
        private val texW = w * 3
        private val tex = ByteArray(texW * h).also { t ->
            val r = Random(42)
            // Blocky value noise at several scales so the encoder has real detail to spend on.
            val coarse = IntArray((texW / 16 + 2) * (h / 16 + 2)) { r.nextInt(256) }
            val cw = texW / 16 + 2
            for (y in 0 until h) for (x in 0 until texW) {
                val c = coarse[(y / 16) * cw + x / 16]
                val fine = r.nextInt(48) - 24
                t[y * texW + x] = (c * 3 / 4 + 32 + fine).coerceIn(0, 255).toByte()
            }
        }
        private var seed = 12345

        fun fill(frame: AVFrame, t: Int) {
            avutil.av_frame_make_writable(frame)
            val shift = (t * 3) % (texW - w)
            val row = ByteArray(w)
            val yp = frame.data(0); val ys = frame.linesize(0)
            for (y in 0 until h) {
                val base = y * texW + shift
                for (x in 0 until w) {
                    seed = seed * 1103515245 + 12345
                    val n = ((seed ushr 16) and 7) - 3
                    row[x] = ((tex[base + x].toInt() and 0xFF) + n).coerceIn(0, 255).toByte()
                }
                yp.position(y.toLong() * ys).put(row, 0, w)
            }
            yp.position(0)
            val cRow = ByteArray(w / 2) { 128.toByte() }
            for (plane in 1..2) {
                val p = frame.data(plane); val s = frame.linesize(plane)
                for (y in 0 until h / 2) p.position(y.toLong() * s).put(cRow, 0, w / 2)
                p.position(0)
            }
        }
    }

    private class Run(
        val lossPct: Double,
        val framesSent: Int,
        val complete: Int,
        val clean: Int,
        val datagrams: Int,
        val dropped: Int,
        val plis: Int,
        val idrsSent: Int,
        val sizes: List<Int>,
        val keyFrags: List<Int>,
        val pFrags: List<Int>,
        val bytes: Long,
        val maxFreezeFrames: Int,
    )

    private fun run(lossPct: Double, bitrate: Long, frames: Int, rttFrames: Int, seed: Int): Run {
        val w = CameraCapture.DEFAULT_WIDTH; val h = CameraCapture.DEFAULT_HEIGHT
        val rnd = Random(seed)
        val scene = Scene(w, h)
        val sizes = ArrayList<Int>()
        var pending = ArrayList<ByteArray>()
        val tx = VideoSendSession(key, true) { pending.add(it); sizes += it.size; true }
        var clock = 0L
        val control = VideoControlSession { clock }
        val rx = VideoReceiveSession(key, sink = object : VideoStreamSink {
            override fun writeParameterSets(sps: ByteArray, pps: ByteArray) {}
            override fun writeAccessUnit(bytes: ByteArray) {}
        })
        val frame = avutil.av_frame_alloc().apply {
            format(avutil.AV_PIX_FMT_YUV420P); width(w); height(h)
            check(avutil.av_frame_get_buffer(this, 32) >= 0)
        }
        var complete = 0; var clean = 0; var dropped = 0; var datagrams = 0; var plis = 0; var idrs = 0
        val keyFrags = ArrayList<Int>(); val pFrags = ArrayList<Int>()
        val forceAt = ArrayDeque<Int>()
        var chainOk = false // every frame since the last delivered IDR arrived
        var freeze = 0; var maxFreeze = 0
        var bytes = 0L
        H264Encoder(w, h, bitrate = bitrate).use { enc ->
            for (i in 0 until frames) {
                clock = i * 33L
                scene.fill(frame, i)
                var force = i == 0
                while (forceAt.isNotEmpty() && forceAt.first() <= i) { forceAt.removeFirst(); force = true }
                for (au in enc.encode(frame, force)) {
                    pending = ArrayList()
                    tx.sendAccessUnit(au.annexB, au.key || force, clock)
                    val n = pending.size
                    if (au.key) { keyFrags += n; idrs++ } else pFrags += n
                    var deliveredThis = false
                    var idrThis = false
                    for (d in pending) {
                        datagrams++
                        bytes += d.size
                        if (rnd.nextDouble() * 100.0 < lossPct) { dropped++; continue }
                        val r = rx.onPacket(d)
                        if (r.delivered) { deliveredThis = true; idrThis = r.isKeyFrame }
                        if (r.requestKeyframe) {
                            if (control.requestKeyframe().isNotEmpty()) {
                                plis++
                                forceAt.addLast(i + rttFrames)
                            }
                        }
                    }
                    if (deliveredThis) {
                        complete++
                        chainOk = if (idrThis) true else chainOk
                        if (chainOk) clean++
                    } else {
                        chainOk = false
                    }
                    if (deliveredThis && chainOk) freeze = 0 else { freeze++; maxFreeze = maxOf(maxFreeze, freeze) }
                }
            }
        }
        avutil.av_frame_free(frame)
        return Run(lossPct, frames, complete, clean, datagrams, dropped, plis, idrs, sizes, keyFrags, pFrags, bytes, maxFreeze)
    }

    private fun pct(xs: List<Int>, p: Double): Int {
        if (xs.isEmpty()) return 0
        val s = xs.sorted()
        return s[((s.size - 1) * p).toInt()]
    }

    @Test
    fun lossBench() {
        assumeTrue("set OSHI_VIDEO_BENCH=1", System.getenv("OSHI_VIDEO_BENCH") == "1")
        assumeTrue("FFmpeg natives: ${FfmpegVideo.unavailableReason()}", FfmpegVideo.available)
        // Relay framing: up = [t][43][key43][43][key43][36][uuid36] = 126 B (44-char keys: 128),
        // down = [t][43][key43][36][uuid36] = 82 B. IPv4 +28, IPv6 +48.
        val relayUp = 1 + 1 + 44 + 1 + 44 + 1 + 36
        val relayDown = 1 + 1 + 44 + 1 + 36
        val frames = (System.getenv("OSHI_VIDEO_BENCH_FRAMES") ?: "300").toInt()
        for (bitrate in listOf(600_000L, 1_200_000L)) {
            for (loss in listOf(0.0, 1.0, 3.0, 5.0)) {
                val r = run(loss, bitrate, frames, rttFrames = 3, seed = 7)
                val maxRaw = r.sizes.maxOrNull() ?: 0
                val full = r.sizes.count { it == maxRaw }
                println(
                    "VIDEO_BENCH enc=${FfmpegVideo.encoderCandidates().firstOrNull()} bitrate=${bitrate / 1000}k " +
                        "fragPayload=${VideoFragment.MAX_PAYLOAD} loss=${loss}% frames=${r.framesSent} " +
                        "complete=${"%.1f".format(r.complete * 100.0 / r.framesSent)}% " +
                        "clean=${"%.1f".format(r.clean * 100.0 / r.framesSent)}% maxFreeze=${r.maxFreezeFrames}f " +
                        "dgrams=${r.datagrams} (${r.datagrams * 30 / r.framesSent}/s) dropped=${r.dropped} PLI=${r.plis} IDR=${r.idrsSent} " +
                        "kbps=${r.bytes * 8 * 30 / r.framesSent / 1000} " +
                        "raw p50/p90/max=${pct(r.sizes, .5)}/${pct(r.sizes, .9)}/$maxRaw full=${full * 100 / r.sizes.size}% " +
                        "relayUp=${maxRaw + relayUp} relayDown=${maxRaw + relayDown} ipv6Up=${maxRaw + relayUp + 48} " +
                        "keyFrags p50/max=${pct(r.keyFrags, .5)}/${r.keyFrags.maxOrNull()} pFrags p50/p90/max=${pct(r.pFrags, .5)}/${pct(r.pFrags, .9)}/${r.pFrags.maxOrNull()}",
                )
            }
        }
    }
}
