package com.oshi.desktop.call.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The send half of row 2.1-v, checked against THREE receivers: ours, and ports of the two
 * phones' receive paths written line-for-line from their source (see [IosOracle] and
 * [AndroidOracle]). The oracles are what a phone does with our bytes, so a green run here
 * means "an iPhone and an Android phone parse this", not merely "we agree with ourselves".
 */
class VideoSendSessionTest {

    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val sps = byteArrayOf(0x67, 0x4D, 0x40, 0x1E, 0xE8.toByte(), 0x14, 0x05, 0xFF.toByte())
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())
    private fun idr(n: Int) = byteArrayOf(0x65, 0x88.toByte()) + ByteArray(n) { (it % 251 + 1).toByte() }
    private fun pSlice(n: Int) = byteArrayOf(0x41, 0x9A.toByte()) + ByteArray(n) { (it % 13 + 1).toByte() }
    private val aud = byteArrayOf(0x09, 0xF0.toByte())
    private val sei = byteArrayOf(0x06, 0x05, 0x01, 0x02, 0x80.toByte())

    private fun annexB(vararg nals: ByteArray) = AnnexB.join(nals.toList())

    private fun capture(isCaller: Boolean = true): Pair<VideoSendSession, MutableList<ByteArray>> {
        val out = ArrayList<ByteArray>()
        return VideoSendSession(key, isCaller) { out += it; true } to out
    }

    @Test
    fun `an IDR with parameter sets round-trips through our own receiver byte for byte`() {
        val (tx, wire) = capture()
        val idr = idr(3000)
        tx.sendAccessUnit(annexB(aud, sps, pps, sei, idr), encoderSaysKey = true, timestampMs = 1234)
        assertEquals((3000 + 60) / VideoFragment.MAX_PAYLOAD + 1, wire.size)

        val params = ArrayList<Pair<ByteArray, ByteArray>>()
        val units = ArrayList<ByteArray>()
        val rx = VideoReceiveSession(key, sink = object : VideoStreamSink {
            override fun writeParameterSets(sps: ByteArray, pps: ByteArray) { params += sps to pps }
            override fun writeAccessUnit(bytes: ByteArray) { units += bytes }
        })
        var delivered: VideoReceiveSession.Result? = null
        for (d in wire) rx.onPacket(d).takeIf { it.delivered }?.let { delivered = it }
        assertNotNull(delivered)
        assertTrue(delivered!!.isKeyFrame)
        assertEquals(1, params.size)
        assertArrayEquals(sps, params[0].first)
        assertArrayEquals(pps, params[0].second)
        // The AUD and SEI are gone; the slice is intact.
        assertArrayEquals(annexB(idr), units.single())
    }

    @Test
    fun `P frames after the IDR still carry the cached SPS and PPS, as both phones send them`() {
        val (tx, wire) = capture()
        tx.sendAccessUnit(annexB(sps, pps, idr(100)), true, 1)
        wire.clear()
        tx.sendAccessUnit(annexB(pSlice(200)), false, 2)
        val frame = IosOracle.receive(key, wire)!!
        assertFalse(frame.isKeyFrame)
        assertArrayEquals(sps, frame.headerSps)
        assertArrayEquals(pps, frame.headerPps)
        assertArrayEquals(sps, frame.embeddedSps)
        assertEquals(1, frame.firstNalType)
    }

    @Test
    fun `the caller's video salt has the direction bit and every nonce has bit 63 set`() {
        for (caller in listOf(true, false)) {
            val (tx, wire) = capture(caller)
            tx.sendAccessUnit(annexB(sps, pps, idr(5000)), true, 1)
            for (d in wire) {
                val nonce = d.copyOfRange(VideoMediaFrame.HEADER_SIZE, VideoMediaFrame.HEADER_SIZE + 12)
                assertEquals(caller, (nonce[0].toInt() and 0x80) != 0)
                assertTrue("domain bit", (nonce[4].toInt() and 0x80) != 0)
            }
            // Counters are unique and start at 1.
            val counters = wire.map { VideoMediaFrame.decode(key, it)!!.counter }
            assertEquals((1L..wire.size).toList(), counters)
        }
    }

    @Test
    fun `restart draws a new salt and restarts the counter, so no nonce is ever reused`() {
        val (tx, wire) = capture()
        tx.sendAccessUnit(annexB(sps, pps, idr(10)), true, 1)
        tx.restart()
        tx.sendAccessUnit(annexB(sps, pps, idr(10)), true, 2)
        val nonces = wire.map { it.copyOfRange(9, 21).toList() }
        assertEquals(nonces.size, nonces.toSet().size)
        assertTrue(tx.keyframeWanted)
    }

    @Test
    fun `a frame that needs more than 255 fragments is dropped and asks for a keyframe`() {
        val (tx, wire) = capture()
        tx.keyframeWanted = false
        val sent = tx.sendAccessUnit(annexB(sps, pps, idr(VideoFragment.MAX_FRAME_BYTES)), true, 1)
        assertEquals(0, sent)
        assertTrue(wire.isEmpty())
        assertTrue(tx.keyframeWanted)
        assertEquals(1, tx.oversizeDropped)
    }

    @Test
    fun `the iOS receive path parses what we send`() {
        val (tx, wire) = capture()
        val idr = idr(4000)
        tx.sendAccessUnit(annexB(sps, pps, idr), true, 0x0102030405L)
        val f = IosOracle.receive(key, wire)!!
        assertTrue(f.isKeyFrame)
        assertEquals(0, f.rotationCode)
        assertEquals(5, f.firstNalType)
        assertArrayEquals(sps, f.headerSps)
        assertArrayEquals(pps, f.headerPps)
        assertArrayEquals(sps, f.embeddedSps)
        assertArrayEquals(pps, f.embeddedPps)
        // What iOS hands VTDecompressionSession: parameter sets stripped, AVCC slice left.
        assertArrayEquals(avcc(idr), f.cleaned)
    }

    @Test
    fun `the Android receive path parses what we send`() {
        val (tx, wire) = capture(isCaller = false)
        val idr = idr(2500)
        tx.sendAccessUnit(annexB(sps, pps, idr), true, 777L)
        val f = AndroidOracle.receive(key, wire)!!
        assertTrue(f.isKeyFrame)
        assertEquals(777L, f.timestampMs)
        assertArrayEquals(sps, f.sps)
        assertArrayEquals(pps, f.pps)
        // Android feeds MediaCodec the enriched AVCC; its first NALs are the parameter sets.
        val embedded = avcc(sps) + avcc(pps) + avcc(idr)
        assertArrayEquals(embedded, f.h264)
    }

    @Test
    fun `our receiver accepts iOS-shaped and Android-shaped emitter output`() {
        // Built the way each phone's SEND code builds it (see the oracles' emit functions).
        for (emit in listOf(IosOracle::emit, AndroidOracle::emit)) {
            val units = ArrayList<ByteArray>()
            val rx = VideoReceiveSession(key, sink = object : VideoStreamSink {
                override fun writeParameterSets(sps: ByteArray, pps: ByteArray) {}
                override fun writeAccessUnit(bytes: ByteArray) { units += bytes }
            })
            val idr = idr(2000)
            for (d in emit(key, sps, pps, avcc(idr), true, 7)) rx.onPacket(d)
            assertArrayEquals(annexB(idr), units.single())
        }
    }

    @Test
    fun `annex-B split handles three and four byte start codes`() {
        val data = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5)
        val nals = AnnexB.split(data)
        assertEquals(3, nals.size)
        assertArrayEquals(byteArrayOf(0x67, 1, 2), nals[0])
        assertArrayEquals(byteArrayOf(0x68, 3), nals[1])
        assertArrayEquals(byteArrayOf(0x65, 4, 5), nals[2])
    }

    private fun avcc(nal: ByteArray) = ByteBuffer.allocate(4).putInt(nal.size).array() + nal
}

private fun gcmOpen(key: ByteArray, nonce: ByteArray, ctAndTag: ByteArray): ByteArray? = runCatching {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    c.doFinal(ctAndTag)
}.getOrNull()

private fun gcmSeal(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    return c.doFinal(plain)
}

/** Fragment reassembly as BOTH phones do it: `[0x01][frameId 2 BE][idx][total][payload]`. */
private fun reassemble(fragments: List<ByteArray>): ByteArray? {
    var total = -1
    val parts = HashMap<Int, ByteArray>()
    for (f in fragments) {
        if (f.size <= 5 || f[0].toInt() != 0x01) return null
        val idx = f[3].toInt() and 0xFF
        val t = f[4].toInt() and 0xFF
        if (t < 1 || idx >= t) return null
        total = t
        parts[idx] = f.copyOfRange(5, f.size)
    }
    if (parts.size != total) return null
    return (0 until total).fold(ByteArray(0)) { acc, i -> acc + parts[i]!! }
}

/**
 * iOS, from `VoiceCallManager.swift:11147-11165` (peel `[0xF1][seq 8]`), `VideoCallManager.swift`
 * `decryptPacket` (`:3392`, nonce = first 12, tag = last 16), `receiveVideoPacket` (`:2625`,
 * fragment header) and the packet parse at `:2787-2860` (LE lengths, embedded AVCC SPS/PPS,
 * `stripParameterSetNALs`, `extractFirstNALType`).
 */
object IosOracle {
    class Frame(
        val isKeyFrame: Boolean, val rotationCode: Int,
        val headerSps: ByteArray?, val headerPps: ByteArray?,
        val embeddedSps: ByteArray?, val embeddedPps: ByteArray?,
        val firstNalType: Int, val cleaned: ByteArray,
    )

    fun receive(key: ByteArray, wire: List<ByteArray>): Frame? {
        val frags = wire.map { env ->
            require((env[0].toInt() and 0xFF) == 0xF1)
            val data = env.copyOfRange(9, env.size)
            if (data.size <= 28) return null
            gcmOpen(key, data.copyOfRange(0, 12), data.copyOfRange(12, data.size)) ?: return null
        }
        val pt = reassemble(frags) ?: return null
        if (pt.size <= 13) return null
        var o = 0
        val flags = pt[o].toInt() and 0xFF; o += 1
        o += 8
        val spsLen = (pt[o].toInt() and 0xFF) or ((pt[o + 1].toInt() and 0xFF) shl 8); o += 2
        var sps: ByteArray? = null
        if (spsLen > 0 && o + spsLen <= pt.size) { sps = pt.copyOfRange(o, o + spsLen); o += spsLen }
        if (o + 2 > pt.size) return null
        val ppsLen = (pt[o].toInt() and 0xFF) or ((pt[o + 1].toInt() and 0xFF) shl 8); o += 2
        var pps: ByteArray? = null
        if (ppsLen > 0 && o + ppsLen <= pt.size) { pps = pt.copyOfRange(o, o + ppsLen); o += ppsLen }
        if (o >= pt.size) return null
        val frameData = pt.copyOfRange(o, pt.size)
        var eSps: ByteArray? = null; var ePps: ByteArray? = null
        var first = 0; var hasSlice = false; var idr = false
        val cleaned = java.io.ByteArrayOutputStream()
        var off = 0
        while (off + 4 < frameData.size) {
            val len = ByteBuffer.wrap(frameData, off, 4).int
            if (len <= 0 || len >= frameData.size - off) break
            val start = off + 4
            val t = frameData[start].toInt() and 0x1F
            if (first == 0) first = t
            if (t == 5) idr = true
            if (t in 1..4) hasSlice = true
            if (t == 7) eSps = frameData.copyOfRange(start, start + len)
            if (t == 8) ePps = frameData.copyOfRange(start, start + len)
            if (t != 7 && t != 8) cleaned.write(frameData, off, 4 + len)
            off = start + len
        }
        val nalType = if (idr) 5 else if (hasSlice) 1 else first
        return Frame((flags and 1) != 0, (flags shr 1) and 3, sps, pps, eSps, ePps, nalType, cleaned.toByteArray())
    }

    /** iOS's SEND shape (`VideoCallManager.swift:2395-2545` + `VoiceCallManager.swift:11125-11141`). */
    fun emit(key: ByteArray, sps: ByteArray, pps: ByteArray, avcc: ByteArray, key0: Boolean, ts: Long): List<ByteArray> {
        val enriched = ByteBuffer.allocate(8 + sps.size + pps.size).putInt(sps.size).put(sps).putInt(pps.size).put(pps).array() + avcc
        val packet = ByteBuffer.allocate(13 + sps.size + pps.size + enriched.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(if (key0) 1 else 0).putLong(ts).putShort(sps.size.toShort()).put(sps).putShort(pps.size.toShort()).put(pps).put(enriched).array()
        return VideoFragment.fragment(42, packet).mapIndexed { i, frag ->
            val salt = byteArrayOf(0x93.toByte(), 1, 2, 3)
            val nonce = salt + ByteBuffer.allocate(8).putLong((i + 1L) or Long.MIN_VALUE).array()
            byteArrayOf(0xF1.toByte()) + ByteBuffer.allocate(8).putLong(i + 1L).array() + nonce + gcmSeal(key, nonce, frag)
        }
    }
}

/**
 * Android, from `EnhancedCallManager.kt` `decryptVideoPacket` (`:5586`, nonce = first 12)
 * and `VideoCallManager.kt` `receiveRawVideoData` (`:640-800`: fragment header, then
 * `[flags][ts 8 LE][spsLen 2 LE][sps][ppsLen 2 LE][pps][frameData]`).
 */
object AndroidOracle {
    class Frame(val isKeyFrame: Boolean, val timestampMs: Long, val sps: ByteArray?, val pps: ByteArray?, val h264: ByteArray)

    fun receive(key: ByteArray, wire: List<ByteArray>): Frame? {
        val frags = wire.map { env ->
            val data = env.copyOfRange(9, env.size)
            if (data.size < 28) return null
            gcmOpen(key, data.copyOfRange(0, 12), data.copyOfRange(12, data.size)) ?: return null
        }
        val data = reassemble(frags) ?: return null
        var offset = 0
        val flags = data[offset].toInt() and 0xFF; offset += 1
        var ts = 0L
        for (i in 0..7) ts = ts or ((data[offset + i].toLong() and 0xFF) shl (i * 8))
        offset += 8
        val spsLength = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8); offset += 2
        val sps = if (spsLength > 0 && offset + spsLength <= data.size) data.copyOfRange(offset, offset + spsLength).also { offset += spsLength } else null
        val ppsLength = if (offset + 2 <= data.size) (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) else 0
        offset += 2
        val pps = if (ppsLength > 0 && offset + ppsLength <= data.size) data.copyOfRange(offset, offset + ppsLength).also { offset += ppsLength } else null
        val h264 = if (offset < data.size) data.copyOfRange(offset, data.size) else return null
        return Frame((flags and 1) != 0, ts, sps, pps, h264)
    }

    /** Android's SEND shape (`VideoCallManager.kt:1276-1370` + `EnhancedCallManager.kt:4923-4945`, LE seq). */
    fun emit(key: ByteArray, sps: ByteArray, pps: ByteArray, avcc: ByteArray, key0: Boolean, ts: Long): List<ByteArray> {
        val enriched = ByteBuffer.allocate(8 + sps.size + pps.size).putInt(sps.size).put(sps).putInt(pps.size).put(pps).array() + avcc
        val packet = ByteBuffer.allocate(13 + sps.size + pps.size + enriched.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(if (key0) 1 else 0).putLong(ts).putShort(sps.size.toShort()).put(sps).putShort(pps.size.toShort()).put(pps).put(enriched).array()
        return VideoFragment.fragment(7, packet).mapIndexed { i, frag ->
            val salt = byteArrayOf(0x11, 1, 2, 3)
            val nonce = salt + ByteBuffer.allocate(8).putLong((i + 1L) or Long.MIN_VALUE).array()
            byteArrayOf(0xF1.toByte()) + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(i + 1L).array() + nonce + gcmSeal(key, nonce, frag)
        }
    }
}
