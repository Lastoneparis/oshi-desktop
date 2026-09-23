package com.oshi.desktop.call.video

import com.oshi.messenger.service.VideoRateController

/** The shared rate controller (what Android and the desktop now run), wrapped for [VideoLossBench]. */
object BenchAdapters {
    fun make(ceiling: Int = H264Encoder.DEFAULT_BITRATE.toInt()): VideoLossBench.Adapter {
        var now = 0L
        val rc = VideoRateController(ceiling, nowMs = { now })
        return object : VideoLossBench.Adapter {
            override fun onPeerKeyframeRequest(nowMs: Long) { now = nowMs; rc.onPeerKeyframeRequest() }
            override fun onLossSample(lossPct: Double, nowMs: Long) { now = nowMs; rc.onLossSample(lossPct) }
            override fun tick(nowMs: Long): Triple<Long, Int, Int> {
                now = nowMs
                val d = rc.tick()
                return Triple(d.bitrateBps.toLong(), d.fps, d.scalePct)
            }
        }
    }
}
