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
import java.util.PriorityQueue
import kotlin.random.Random

/**
 * Video loss bench — the real encoder of this machine, the real fragmenter/seal
 * ([VideoSendSession]) and the real receiver ([VideoReceiveSession]), with a simulated
 * network in between: i.i.d. datagram loss, optional REORDERING (a share of datagrams
 * is held back 10-80 ms, so they overtake / are overtaken across frame boundaries), and
 * optionally a BOTTLENECK (a drop-tail queue of fixed capacity, the way a thin uplink
 * loses packets). The receiver's `0x0B` requests reach the encoder after a simulated
 * RTT. No network, no server. `OSHI_VIDEO_BENCH=1` to run.
 *
 * Per frame (by `frame_id`) it records whether the frame reached the decoder, in which
 * ORDER, and when. A frame is CLEAN when it reached the decoder in decode order and every
 * frame since the last delivered IDR did too — what a decoder that keeps going after a
 * hole (VideoToolbox/MediaCodec here) shows without smearing. FREEZE is the wall time
 * during which no clean frame was shown, counted in runs longer than 100 ms.
 */
class VideoLossBench {

    private val key = ByteArray(32) { (it * 7).toByte() }

    /** Camera-like content: a textured scene panning 3 px/frame plus per-pixel sensor noise. */
    private class Scene(val w: Int, val h: Int) {
        private val texW = w * 3
        private val tex = ByteArray(texW * h).also { t ->
            val r = Random(42)
            val coarse = IntArray((texW / 16 + 2) * (h / 16 + 2)) { r.nextInt(256) }
            val cw = texW / 16 + 2
            for (y in 0 until h) for (x in 0 until texW) {
                val c = coarse[(y / 16) * cw + x / 16]
                val fine = r.nextInt(48) - 24
                t[y * texW + x] = (c * 3 / 4 + 32 + fine).coerceIn(0, 255).toByte()
            }
        }
        private var seed = 12345

        fun fill(frame: AVFrame, t: Int, outW: Int = w, outH: Int = h) {
            avutil.av_frame_make_writable(frame)
            val shift = (t * 3) % (texW - w)
            val row = ByteArray(outW)
            val yp = frame.data(0); val ys = frame.linesize(0)
            for (y in 0 until outH) {
                val sy = y * h / outH
                val base = sy * texW + shift
                for (x in 0 until outW) {
                    seed = seed * 1103515245 + 12345
                    val n = ((seed ushr 16) and 7) - 3
                    row[x] = ((tex[base + x * w / outW].toInt() and 0xFF) + n).coerceIn(0, 255).toByte()
                }
                yp.position(y.toLong() * ys).put(row, 0, outW)
            }
            yp.position(0)
            val cRow = ByteArray(outW / 2) { 128.toByte() }
            for (plane in 1..2) {
                val p = frame.data(plane); val s = frame.linesize(plane)
                for (y in 0 until outH / 2) p.position(y.toLong() * s).put(cRow, 0, outW / 2)
                p.position(0)
            }
        }
    }

    /** One datagram in flight. */
    private class InFlight(val at: Long, val order: Long, val bytes: ByteArray)

    class Net(
        val lossPct: Double = 0.0,
        val reorderPct: Double = 0.0,
        /** Bottleneck in bit/s; 0 = none. */
        val capacityBps: Long = 0,
        /** Drop-tail queue depth of the bottleneck, in ms of its capacity. */
        val queueMs: Long = 120,
        val baseDelayMs: Long = 25,
    )

    private class Frame(val index: Int, val sentAt: Long, val key: Boolean, val frags: Int) {
        var deliveredAt = -1L
        var inOrder = true
    }

    class Report(
        val label: String,
        val framesSent: Int,
        val complete: Int,
        val clean: Int,
        val freezeMs: Long,
        val maxFreezeMs: Long,
        val kbps: Long,
        val datagrams: Int,
        val dropped: Int,
        val plis: Int,
        val idrs: Int,
        val p95LatencyMs: Long,
        val maxRaw: Int,
        val rungChanges: Int,
        val finalKbps: Long,
    ) {
        fun line() = "VIDEO_BENCH $label frames=$framesSent " +
            "complete=${"%.1f".format(complete * 100.0 / framesSent)}% " +
            "clean=${"%.1f".format(clean * 100.0 / framesSent)}% " +
            "freeze=${freezeMs}ms (max ${maxFreezeMs}ms) kbps=$kbps (end $finalKbps) " +
            "dgrams=$datagrams dropped=$dropped PLI=$plis IDR=$idrs lat95=${p95LatencyMs}ms " +
            "maxDgram=$maxRaw rungChanges=$rungChanges"
    }

    /**
     * Rate adaptation hook for the bench: told about every keyframe request the far end
     * sends and about the receiver's loss, asked each 500 ms for a (bitrate, fps, scale).
     */
    interface Adapter {
        fun onPeerKeyframeRequest(nowMs: Long)
        fun onLossSample(lossPct: Double, nowMs: Long)
        /** @return bitrate bps, fps, resolution divisor ×100 (100 = full). */
        fun tick(nowMs: Long): Triple<Long, Int, Int>
    }

    fun run(
        label: String,
        net: Net,
        bitrate: Long,
        frames: Int,
        seed: Int,
        adapter: Adapter? = null,
        rttMs: Long = 100,
    ): Report {
        val fullW = CameraCapture.DEFAULT_WIDTH; val fullH = CameraCapture.DEFAULT_HEIGHT
        val rnd = Random(seed)
        val scene = Scene(fullW, fullH)
        var clock = 0L
        val outbox = ArrayList<ByteArray>()
        val tx = VideoSendSession(key, true) { outbox.add(it); true }
        val control = VideoControlSession { clock }
        val rx = VideoReceiveSession(key, sink = object : VideoStreamSink {
            override fun writeParameterSets(sps: ByteArray, pps: ByteArray) {}
            override fun writeAccessUnit(bytes: ByteArray) {}
        }, clock = { clock })
        val net0 = PriorityQueue<InFlight>(compareBy<InFlight>({ it.at }, { it.order }))
        var order = 0L
        var queueFreeAt = 0L // bottleneck: when the link finishes the bytes already queued
        val framesById = HashMap<Int, Frame>()
        val frameList = ArrayList<Frame>()
        var dropped = 0; var datagrams = 0; var plis = 0; var idrs = 0; var bytes = 0L; var maxRaw = 0
        val plisAt = ArrayDeque<Long>()
        var lastDeliveredIndex = -1
        var curBitrate = bitrate; var curFps = 30; var curScale = 100
        var rungChanges = 0
        var lastTick = 0L
        var enc: H264Encoder? = null
        var frame: AVFrame? = null
        var encW = 0; var encH = 0
        var forceNext = true
        var nextCaptureAt = 0L
        val tickMs = 1000L / 30

        fun openEncoder() {
            enc?.close(); frame?.let { avutil.av_frame_free(it) }
            encW = (fullW * curScale / 100) and -2
            encH = (fullH * curScale / 100) and -2
            enc = H264Encoder(encW, encH, bitrate = curBitrate * 30 / curFps, fps = 30)
            frame = avutil.av_frame_alloc().apply {
                format(avutil.AV_PIX_FMT_YUV420P); width(encW); height(encH)
                check(avutil.av_frame_get_buffer(this, 32) >= 0)
            }
            forceNext = true
        }
        openEncoder()

        fun deliverUpTo(t: Long) {
            while (net0.isNotEmpty() && net0.peek().at <= t) {
                val p = net0.poll()
                clock = p.at
                val r = rx.onPacket(p.bytes)
                for ((fid, _) in delivered(r)) {
                    val f = framesById[fid] ?: continue
                    if (f.deliveredAt < 0) {
                        f.deliveredAt = clock
                        f.inOrder = f.index > lastDeliveredIndex
                        if (f.index > lastDeliveredIndex) lastDeliveredIndex = f.index
                    }
                }
                if (r.requestKeyframe && control.requestKeyframe().isNotEmpty()) {
                    plis++
                    plisAt.addLast(clock + rttMs / 2) // the request travels back to the sender
                }
            }
        }

        var sentFrames = 0
        var t = 0L
        val endAt = frames * tickMs
        while (t < endAt) {
            deliverUpTo(t)
            clock = t
            // Keyframe requests that have reached the sender by now.
            while (plisAt.isNotEmpty() && plisAt.first() <= t) {
                plisAt.removeFirst(); forceNext = true
                adapter?.onPeerKeyframeRequest(t)
            }
            if (adapter != null && t - lastTick >= 500) {
                lastTick = t
                // Exactly what CallVideoSession does: datagram loss from the nonce counter
                // (symmetric-link assumption, like iOS), a rebuild on every rung change,
                // the encoder asked for bitrate×30/fps because it is fed only [fps] frames.
                rx.takeLossSample()?.let { adapter.onLossSample(it, t) }
                val (b, fps, sc) = adapter.tick(t)
                if (sc != curScale || b != curBitrate || fps != curFps) {
                    curScale = sc; curBitrate = b; curFps = fps; rungChanges++
                    openEncoder()
                }
            }
            if (t >= nextCaptureAt) {
                nextCaptureAt += 1000L / curFps
                val i = sentFrames
                scene.fill(frame!!, i, encW, encH)
                val force = forceNext
                forceNext = false
                for (au in enc!!.encode(frame!!, force)) {
                    outbox.clear()
                    val fid = tx.framesSent.toInt() and 0xFFFF
                    tx.sendAccessUnit(au.annexB, au.key || force, t)
                    if (outbox.isEmpty()) continue
                    val isKey = au.key || force
                    if (isKey) idrs++
                    val f = Frame(frameList.size, t, isKey, outbox.size)
                    framesById[fid] = f; frameList += f
                    var k = 0
                    for (d in outbox) {
                        datagrams++; bytes += d.size; maxRaw = maxOf(maxRaw, d.size)
                        if (rnd.nextDouble() * 100.0 < net.lossPct) { dropped++; k++; continue }
                        var at = t + net.baseDelayMs + k / 4 // pacing: 4 datagrams per ms
                        if (net.capacityBps > 0) {
                            val serialize = d.size * 8L * 1000 / net.capacityBps
                            val start = maxOf(at, queueFreeAt)
                            if (start - at > net.queueMs) { dropped++; k++; continue } // drop-tail
                            queueFreeAt = start + maxOf(1L, serialize)
                            at = queueFreeAt
                        }
                        if (rnd.nextDouble() * 100.0 < net.reorderPct) at += 10 + rnd.nextLong(71)
                        net0.add(InFlight(at, order++, d))
                        k++
                    }
                }
                sentFrames++
            }
            t += tickMs
        }
        deliverUpTo(Long.MAX_VALUE)
        enc?.close(); frame?.let { avutil.av_frame_free(it) }

        // Clean / freeze accounting, in send order.
        var chainOk = false
        var complete = 0; var clean = 0
        var freeze = 0L; var maxFreeze = 0L; var runStart = -1L
        val lat = ArrayList<Long>()
        for (f in frameList) {
            val got = f.deliveredAt >= 0 && f.inOrder
            if (f.deliveredAt >= 0) { complete++; lat += f.deliveredAt - f.sentAt }
            chainOk = if (!got) false else if (f.key) true else chainOk
            val isClean = got && chainOk
            if (isClean) {
                clean++
                if (runStart >= 0) {
                    val d = f.sentAt - runStart
                    if (d > 100) { freeze += d; maxFreeze = maxOf(maxFreeze, d) }
                    runStart = -1
                }
            } else if (runStart < 0) runStart = f.sentAt
        }
        if (runStart >= 0) {
            val d = endAt - runStart
            if (d > 100) { freeze += d; maxFreeze = maxOf(maxFreeze, d) }
        }
        lat.sort()
        val p95 = if (lat.isEmpty()) 0 else lat[((lat.size - 1) * 0.95).toInt()]
        return Report(
            label, frameList.size, complete, clean, freeze, maxFreeze,
            bytes * 8 / (endAt / 1000).coerceAtLeast(1) / 1000, datagrams, dropped, plis, idrs, p95, maxRaw,
            rungChanges, curBitrate / 1000,
        )
    }

    /** Every frame the receive session released to the decoder on this datagram. */
    private fun delivered(r: VideoReceiveSession.Result): List<Pair<Int, Boolean>> =
        r.frames.map { it.frameId to it.isKeyFrame }

    private fun preconditions() {
        assumeTrue("set OSHI_VIDEO_BENCH=1", System.getenv("OSHI_VIDEO_BENCH") == "1")
        assumeTrue("FFmpeg natives: ${FfmpegVideo.unavailableReason()}", FfmpegVideo.available)
    }

    @Test
    fun lossBench() {
        preconditions()
        val frames = (System.getenv("OSHI_VIDEO_BENCH_FRAMES") ?: "900").toInt()
        println("VIDEO_BENCH enc=${FfmpegVideo.encoderCandidates().firstOrNull()} fragPayload=${VideoFragment.MAX_PAYLOAD}")
        for (reorder in listOf(0.0, 5.0)) {
            for (loss in listOf(0.0, 1.0, 3.0, 5.0)) {
                println(run("loss=$loss% reorder=$reorder% fixed-1200k", Net(loss, reorder), 1_200_000L, frames, seed = 7).line())
                if (loss > 0 || reorder > 0) {
                    println(run("loss=$loss% reorder=$reorder% adaptive", Net(loss, reorder), 1_200_000L, frames, seed = 7,
                        adapter = BenchAdapters.make()).line())
                }
            }
        }
    }

    /**
     * A thin uplink: a drop-tail bottleneck below the encoder's bitrate, plus 1 % random
     * loss. Without adaptation the queue overflows on every keyframe and most P-frames.
     */
    @Test
    fun bottleneckBench() {
        preconditions()
        val frames = (System.getenv("OSHI_VIDEO_BENCH_FRAMES") ?: "900").toInt()
        for (cap in listOf(400_000L, 700_000L)) {
            val net = Net(lossPct = 1.0, reorderPct = 2.0, capacityBps = cap)
            println(run("cap=${cap / 1000}k fixed-1200k", net, 1_200_000L, frames, seed = 11).line())
            println(run("cap=${cap / 1000}k adaptive", net, 1_200_000L, frames, seed = 11, adapter = BenchAdapters.make()).line())
        }
    }
}
