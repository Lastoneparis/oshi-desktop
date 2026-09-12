package com.oshi.desktop.call.transport

import com.oshi.desktop.call.media.CallAudio
import com.oshi.desktop.call.media.CallMediaFrame
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARITY.md row 2.1 — the media socket, over real loopback UDP.
 *
 * ============================================================ WHAT THIS DOES AND DOES NOT PROVE
 *
 * Two [MediaSocket]s on `127.0.0.1` exchange real datagrams through the kernel: pings
 * are answered, pongs are correlated, a pair is elected, sealed PCM crosses and is
 * opened. That is a genuine end-to-end media path and it is the first one this client
 * has ever had.
 *
 * It is **not** proof of a call. Loopback has no NAT, so nothing here exercises a hole
 * punch that has to open a mapping; there is no STUN server, so the srflx path is
 * exercised only against hand-built response bytes; and **no packet produced by this
 * package has ever reached an OSHI phone.** Every byte-level expectation in the sibling
 * files is anchored to the shipped Swift and Kotlin sources rather than to a capture,
 * and a source is not a radio.
 */
class MediaSocketTest {

    private val key = ByteArray(32) { (it * 7).toByte() }
    private val salt = byteArrayOf(0x11, 0x22, 0x33, 0x44)
    private val callIdString = "1B2C3D4E-5F60-7182-9394-A5B6C7D8E9F0"
    private val cid = HolePunch.deriveP2PCallId(callIdString)

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    private class Recorder : MediaSocket.Listener {
        val audio = ConcurrentLinkedQueue<CallMediaFrame.Decoded>()
        val drops = ConcurrentLinkedQueue<DropReason>()
        val rejects = ConcurrentLinkedQueue<MediaSocket.MediaRejection>()
        val srflx = ConcurrentLinkedQueue<StunBinding.Mapped>()
        val pings = ConcurrentLinkedQueue<InetSocketAddress>()
        val pongs = ConcurrentLinkedQueue<Pair<IceCandidate, Long>>()
        val gotAudio = CountDownLatch(1)

        override fun onAudio(frame: CallMediaFrame.Decoded, from: InetSocketAddress) {
            audio.add(frame); gotAudio.countDown()
        }

        override fun onDropped(reason: DropReason, from: InetSocketAddress?) { drops.add(reason) }
        override fun onMediaRejected(reason: MediaSocket.MediaRejection, from: InetSocketAddress) {
            rejects.add(reason)
        }

        override fun onReflexiveAddress(mapped: StunBinding.Mapped) { srflx.add(mapped) }
        override fun onPing(from: InetSocketAddress, learned: IceCandidate?) { pings.add(from) }
        override fun onPong(pair: IceCandidate, rttMs: Long) { pongs.add(pair to rttMs) }
    }

    private fun socket(isCaller: Boolean, rec: Recorder, callId: Long = cid): MediaSocket =
        MediaSocket(DatagramSocket(0, loopback), key, salt, isCaller, callId, rec)

    private fun candidateFor(s: MediaSocket) =
        IceCandidate(IceCandidateType.HOST, "127.0.0.1", s.localPort, IcePriority.ios(IceCandidateType.HOST, 4))

    /** Poll rather than sleep a fixed amount — a fixed sleep is a flake on a loaded box. */
    private fun waitFor(timeoutMs: Long = 3_000, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    // ============================================================== the whole path

    /**
     * Caller and callee punch to each other on loopback, elect a pair, and carry one
     * 20 ms PCM frame end to end.
     */
    @Test
    fun `two sockets punch, select a pair, and carry audio`() {
        val ra = Recorder()
        val rb = Recorder()
        val a = socket(isCaller = true, rec = ra)
        val b = socket(isCaller = false, rec = rb)
        try {
            a.start(); b.start()
            a.addRemoteCandidate(candidateFor(b))
            b.addRemoteCandidate(candidateFor(a))

            assertTrue(
                "no pair went live after probing",
                waitFor {
                    a.probeTick(); b.probeTick()
                    Thread.sleep(20)
                    a.updateSelection() != null && b.updateSelection() != null
                },
            )
            assertEquals(b.localPort, a.selectedRemote!!.port)

            val pcm = ByteArray(CallAudio.BYTES_PER_FRAME) { (it % 251).toByte() }
            assertTrue(a.sendPcm(pcm))
            assertTrue("the frame never arrived", rb.gotAudio.await(3, TimeUnit.SECONDS))

            val got = rb.audio.poll()
            assertArrayEquals(pcm, got.pcm)
            assertEquals(CallMediaFrame.TYPE_PCM_48K, got.audioType)
            assertEquals(1L, got.seq)
            assertEquals(1L, a.framesSent.get())
            assertEquals(1L, b.framesPlayed.get())
            assertTrue(a.pongsCorrelated.get() > 0)
            assertTrue(b.pingsAnswered.get() > 0)
        } finally {
            a.close(); b.close()
        }
    }

    /**
     * ============================================================ THE DIRECTION CONVENTION
     *
     * Both halves are the point, and the second one is the finding.
     *
     * With the roles right, the callee transmits under the salt with byte 0 XOR `0xA5`
     * (`VoiceCallManager.swift:3141-3156`) so the two directions can never emit the same
     * nonce at the same counter. With the callee's flag INVERTED, the frames still
     * decode perfectly — because receiving reads the nonce off the wire and never
     * consults a salt (`swift:13205 let nonceData = data.prefix(12)`) — and the two
     * directions emit **byte-identical nonces under one key**.
     *
     * That contradicts [CallMediaFrame]'s own header, which says an inverted flag makes
     * the call "silent in both directions". It does not. It makes the call sound
     * perfect and breaks the cipher, which is the version with no symptom.
     */
    @Test
    fun `the responder salt separates the directions and inverting it collides them`() {
        val callerFrame = CallMediaFrame.encode(key, salt, true, 1, CallMediaFrame.TYPE_PCM_48K, ByteArray(4))
        val calleeFrame = CallMediaFrame.encode(key, salt, false, 1, CallMediaFrame.TYPE_PCM_48K, ByteArray(4))
        val nonceOf = { f: ByteArray -> f.copyOfRange(CallMediaFrame.HEADER_SIZE, CallMediaFrame.HEADER_SIZE + 12) }

        assertFalse(
            "caller and callee must not share a nonce at the same counter",
            nonceOf(callerFrame).contentEquals(nonceOf(calleeFrame)),
        )
        assertEquals(
            (salt[0].toInt() xor 0xA5).toByte(),
            nonceOf(calleeFrame)[0],
        )

        // The inverted callee. Same key, same counter, same salt as the caller.
        val invertedCallee = CallMediaFrame.encode(key, salt, true, 1, CallMediaFrame.TYPE_PCM_48K, ByteArray(4))
        assertArrayEquals(
            "an inverted isCaller reuses the caller's exact nonce under one key",
            nonceOf(callerFrame), nonceOf(invertedCallee),
        )
        // ...and it decodes anyway, which is why nothing would ever report it.
        assertNotNull(CallMediaFrame.decode(key, invertedCallee))
    }

    /**
     * ============================================================ ONE COUNTER, OR NONE
     *
     * `sendPcm` owns a [com.oshi.desktop.call.media.MediaSequence]; `sendSealed` takes
     * frames a [com.oshi.desktop.call.media.CallAudioSession] sealed with its own. Two
     * counters under one key and one salt repeat a nonce, which is the same GCM break
     * the directional salt exists to prevent, reached from the other side. The socket
     * refuses rather than emitting the duplicate.
     */
    @Test
    fun `a socket refuses to mix sealed and unsealed sends`() {
        val rec = Recorder()
        val s = socket(isCaller = true, rec = rec)
        try {
            s.addRemoteCandidate(IceCandidate(IceCandidateType.HOST, "127.0.0.1", 9, 0))
            s.sendPcm(ByteArray(4))
            val thrown = runCatching { s.sendSealed(ByteArray(64)) }.exceptionOrNull()
            assertTrue("expected an IllegalStateException, got $thrown", thrown is IllegalStateException)

            val other = socket(isCaller = true, rec = Recorder())
            other.sendSealed(ByteArray(64))
            assertTrue(runCatching { other.sendPcm(ByteArray(4)) }.exceptionOrNull() is IllegalStateException)
            other.close()
        } finally {
            s.close()
        }
    }

    /** With no live pair there is nowhere to send, and that must not read as success. */
    @Test
    fun `sending with no selected pair reports false rather than pretending`() {
        val s = socket(isCaller = true, rec = Recorder())
        try {
            assertNull(s.updateSelection())
            assertFalse(s.sendPcm(ByteArray(CallAudio.BYTES_PER_FRAME)))
            assertEquals(0L, s.framesSent.get())
        } finally {
            s.close()
        }
    }

    // ============================================================== cid_mismatch

    /**
     * A ping stamped with a foreign call id is dropped as `cid_mismatch` and gets NO
     * pong — `P2PTransport.swift:1388-1394`. The wildcard half is asserted alongside it
     * so the test cannot pass by dropping everything.
     */
    @Test
    fun `a foreign call id is dropped and zero and ours are answered`() {
        val rec = Recorder()
        val s = socket(isCaller = false, rec = rec)
        try {
            val from = InetSocketAddress(loopback, 40000)
            val nonce = ByteArray(12) { 1 }

            s.handle(HolePunch.buildPing(cid + 1, nonce, 1L), HolePunch.PACKET_SIZE, from)
            assertEquals(DropReason.CID_MISMATCH, rec.drops.poll())
            assertEquals(0L, s.pingsAnswered.get())

            s.handle(HolePunch.buildPing(0L, nonce, 1L), HolePunch.PACKET_SIZE, from)
            s.handle(HolePunch.buildPing(cid, nonce, 1L), HolePunch.PACKET_SIZE, from)
            assertEquals(2L, s.pingsAnswered.get())
        } finally {
            s.close()
        }
    }

    // ============================================================== inbound media

    @Test
    fun `a frame that does not authenticate is rejected and not played`() {
        val rec = Recorder()
        val s = socket(isCaller = true, rec = rec)
        try {
            val frame = CallMediaFrame.encode(
                ByteArray(32) { 0x55 }, salt, false, 1, CallMediaFrame.TYPE_PCM_48K, ByteArray(64),
            )
            s.handle(frame, frame.size, InetSocketAddress(loopback, 40000))
            assertEquals(MediaSocket.MediaRejection.NOT_AUTHENTIC, rec.rejects.poll())
            assertEquals(0L, s.framesPlayed.get())
        } finally {
            s.close()
        }
    }

    @Test
    fun `a replayed frame is rejected the second time`() {
        val rec = Recorder()
        val s = socket(isCaller = true, rec = rec)
        try {
            val frame = CallMediaFrame.encode(key, salt, false, 7, CallMediaFrame.TYPE_PCM_48K, ByteArray(64))
            val from = InetSocketAddress(loopback, 40000)
            s.handle(frame, frame.size, from)
            s.handle(frame, frame.size, from)
            assertEquals(1L, s.framesPlayed.get())
            assertEquals(MediaSocket.MediaRejection.REPLAYED, rec.rejects.poll())
        } finally {
            s.close()
        }
    }

    /**
     * `0x05` AAC-ELD and `0x16` OshiCodec authenticate fine and have no JVM decoder.
     * Playing their bytes as PCM is white noise, so they are counted separately from
     * both silence and corruption.
     */
    @Test
    fun `an undecodable codec is separated from a broken frame`() {
        val rec = Recorder()
        val s = socket(isCaller = true, rec = rec)
        try {
            val aac = CallMediaFrame.encode(key, salt, false, 1, CallMediaFrame.TYPE_AAC_ELD, ByteArray(64))
            s.handle(aac, aac.size, InetSocketAddress(loopback, 40000))
            assertEquals(MediaSocket.MediaRejection.UNDECODABLE_CODEC, rec.rejects.poll())
            assertEquals(0L, s.framesPlayed.get())
        } finally {
            s.close()
        }
    }

    /**
     * The hook a [com.oshi.desktop.call.media.CallAudioSession] wiring uses: taking the
     * sealed bytes means exactly one replay window exists in the call instead of two
     * that disagree about which frames are fresh.
     */
    @Test
    fun `a listener can take the sealed bytes and stop the socket decoding`() {
        val taken = ConcurrentLinkedQueue<ByteArray>()
        val listener = object : MediaSocket.Listener {
            override fun onSealedMedia(sealed: ByteArray, from: InetSocketAddress): Boolean {
                taken.add(sealed); return true
            }
        }
        val s = MediaSocket(DatagramSocket(0, loopback), key, salt, true, cid, listener)
        try {
            val frame = CallMediaFrame.encode(key, salt, false, 1, CallMediaFrame.TYPE_PCM_48K, ByteArray(64))
            s.handle(frame, frame.size, InetSocketAddress(loopback, 40000))
            assertArrayEquals(frame, taken.poll())
            assertEquals(0L, s.framesPlayed.get())
        } finally {
            s.close()
        }
    }

    // ============================================================== srflx on this socket

    /**
     * The reply is demuxed off the MEDIA socket, which is the whole point: a NAT mapping
     * belongs to a five-tuple, so the srflx worth advertising is the one discovered on
     * the socket the audio leaves from (`P2PTransport.swift:617-627`).
     *
     * A response whose transaction id we never sent is dropped — without that check,
     * anyone able to guess the five-tuple could name our srflx candidate.
     */
    @Test
    fun `a stun response is accepted only for a transaction this socket sent`() {
        val rec = Recorder()
        val s = socket(isCaller = true, rec = rec)
        try {
            val from = InetSocketAddress(loopback, 3478)
            val foreign = stunResponse(ByteArray(12) { 0x77 })
            s.handle(foreign, foreign.size, from)
            assertEquals(DropReason.UNKNOWN_STUN_TRANSACTION, rec.drops.poll())
            assertTrue(rec.srflx.isEmpty())

            // Send a real request from this socket to a port nothing listens on, then
            // hand back a response bearing the transaction id it actually used.
            s.requestSrflx(InetSocketAddress(loopback, 1))
            val sentTx = lastRequestTxId(s)
            val ours = stunResponse(sentTx)
            s.handle(ours, ours.size, from)
            assertEquals(StunBinding.Mapped("203.0.113.42", 51234), rec.srflx.poll())
        } finally {
            s.close()
        }
    }

    /** Build a Binding Success Response for 203.0.113.42:51234 under [tx]. */
    private fun stunResponse(tx: ByteArray): ByteArray {
        val attr = byteArrayOf(0x00, 0x20, 0x00, 0x08, 0x00, 0x01,
            0xE9.toByte(), 0x30, 0xEA.toByte(), 0x12, 0xD5.toByte(), 0x68)
        val out = ByteArray(20 + attr.size)
        out[0] = 0x01; out[1] = 0x01
        out[2] = 0; out[3] = attr.size.toByte()
        out[4] = 0x21; out[5] = 0x12; out[6] = 0xA4.toByte(); out[7] = 0x42
        tx.copyInto(out, 8)
        attr.copyInto(out, 20)
        return out
    }

    /** The socket does not expose its outstanding ids; read it out of the datagram it sent. */
    private fun lastRequestTxId(s: MediaSocket): ByteArray {
        // requestSrflx() went to 127.0.0.1:1 and was discarded by the kernel, so the id
        // is recovered from the only place it is still knowable: the socket's own set,
        // via a probe response for every candidate id is impossible — instead re-issue
        // against a listening socket and read the request off the wire.
        val server = DatagramSocket(0, loopback)
        try {
            server.soTimeout = 2000
            s.requestSrflx(InetSocketAddress(loopback, server.localPort))
            val buf = ByteArray(64)
            val pkt = java.net.DatagramPacket(buf, buf.size)
            server.receive(pkt)
            return StunBinding.transactionId(buf, pkt.offset, pkt.length)!!
        } finally {
            server.close()
        }
    }

    /**
     * ============================================================ A BOUNDED SET
     *
     * iOS bounds its outstanding transaction ids at eight and evicts the oldest
     * (`P2PTransport.swift:639-641`, `if stunTxIds.count > 8 { stunTxIds.removeFirst() }`).
     * A set that grows one entry per retransmit is a slow leak on a lossy path, and
     * every entry in it is an id an injected response may claim.
     *
     * The bound is observable without exposing the set: after nine requests the FIRST
     * transaction must no longer be honoured, while the ninth still is. The control —
     * eight requests, first still honoured — is what makes this a test of the bound
     * rather than of eviction in general. It exists because a mutation run removed the
     * eviction loop and the suite stayed GREEN: the guard was real, reachable, and had
     * no fixture at all.
     */
    @Test
    fun `outstanding stun transactions are bounded at eight, oldest evicted`() {
        fun txIdsAfter(requests: Int): Pair<MediaSocket, List<ByteArray>> {
            val server = DatagramSocket(0, loopback)
            server.soTimeout = 2000
            val s = socket(isCaller = true, rec = Recorder())
            val ids = ArrayList<ByteArray>()
            repeat(requests) {
                s.requestSrflx(InetSocketAddress(loopback, server.localPort))
                val buf = ByteArray(64)
                val pkt = java.net.DatagramPacket(buf, buf.size)
                server.receive(pkt)
                ids.add(StunBinding.transactionId(buf, pkt.offset, pkt.length)!!)
            }
            server.close()
            return s to ids
        }

        // Control: exactly eight fit, so the first is still ours.
        val (eight, eightIds) = txIdsAfter(MediaSocket.MAX_OUTSTANDING_STUN)
        try {
            val resp = stunResponse(eightIds.first())
            eight.handle(resp, resp.size, InetSocketAddress(loopback, 3478))
            assertEquals(0L, eight.packetsDropped.get())
        } finally {
            eight.close()
        }

        // Nine: the first is evicted and its response is no longer honoured...
        val (nine, nineIds) = txIdsAfter(MediaSocket.MAX_OUTSTANDING_STUN + 1)
        try {
            val first = stunResponse(nineIds.first())
            nine.handle(first, first.size, InetSocketAddress(loopback, 3478))
            assertEquals(1L, nine.packetsDropped.get())
            // ...while the newest still is, so the eviction took the OLDEST.
            val last = stunResponse(nineIds.last())
            nine.handle(last, last.size, InetSocketAddress(loopback, 3478))
            assertEquals(1L, nine.packetsDropped.get())
        } finally {
            nine.close()
        }
    }

    // ============================================================== local candidates

    /**
     * Host candidates carry the port audio leaves from, and never a loopback or
     * link-local address — both clients skip the same ones (`swift:1703`, `swift:1717`,
     * `kt:640`). A link-local candidate wastes a probe slot on an address no peer
     * off the segment can answer.
     */
    @Test
    fun `host candidates use the media port and skip loopback and link-local`() {
        val s = MediaSocket(DatagramSocket(0), key, salt, true, cid)
        try {
            for (c in s.hostCandidates()) {
                assertEquals(s.localPort, c.port)
                assertEquals(IceCandidateType.HOST, c.type)
                assertFalse(c.ip.startsWith("127."))
                assertFalse(c.ip.startsWith("fe80:"))
                assertEquals(IcePriority.ios(IceCandidateType.HOST, c.family), c.priority)
            }
        } finally {
            s.close()
        }
    }

    @Test
    fun `a bad key or salt size is refused at construction`() {
        assertTrue(
            runCatching { MediaSocket(DatagramSocket(0), ByteArray(31), salt, true, cid) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { MediaSocket(DatagramSocket(0), key, ByteArray(3), true, cid) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `close is idempotent`() {
        val s = socket(isCaller = true, rec = Recorder())
        s.start()
        s.close()
        s.close()
        assertNull(s.selectedRemote)
    }

    // ============================================================== the demux itself

    @Test
    fun `a sealed pcm frame classifies as media, not as stun`() {
        val frame = CallMediaFrame.encode(key, salt, true, 1, CallMediaFrame.TYPE_PCM_48K, ByteArray(1920))
        val got = MediaDemux.classify(frame, frame.size, cid) { true }
        assertTrue("$got", got is InboundPacket.Media)
    }

    @Test
    fun `an empty datagram is dropped`() {
        assertEquals(
            InboundPacket.Dropped(DropReason.EMPTY),
            MediaDemux.classify(ByteArray(0), 0, cid) { false },
        )
    }

    /**
     * `0x40..0x7F` is TURN ChannelData (RFC 5766 §11.4, top two bits `01`). Recognised
     * and dropped with its own reason rather than handed to the media codec, where it
     * would fail to authenticate and be miscounted as broken audio — a wrong diagnosis
     * for a call that is actually on a relay this package does not implement.
     */
    @Test
    fun `the turn channel data range is recognised and not treated as media`() {
        for (first in intArrayOf(0x40, 0x5A, 0x7F)) {
            val d = ByteArray(40); d[0] = first.toByte()
            assertEquals(
                "first byte 0x%02x".format(first),
                InboundPacket.Dropped(DropReason.TURN_CHANNEL_DATA_UNSUPPORTED),
                MediaDemux.classify(d, d.size, cid) { false },
            )
        }
        // 0x3F and 0x80 are outside the range and stay media.
        for (first in intArrayOf(0x3F, 0x80)) {
            val d = ByteArray(40); d[0] = first.toByte()
            assertTrue(MediaDemux.classify(d, d.size, cid) { false } is InboundPacket.Media)
        }
    }

    /**
     * ============================================================ THE WHOLE BOUNDARY
     *
     * Every length from 1 to 28 is malformed for both `0x31` and `0x32`; 29 is the first
     * that parses. Both shipped clients gate on the same number (`swift:1601 guard
     * ping.count >= 29`, `kt:1055 ByteArray(29)`).
     *
     * This sweeps the boundary instead of sampling one length, because the single-sample
     * version it replaces could not fail: a mutation run removed [MediaDemux]'s explicit
     * `packet.size < PACKET_SIZE` check for the pong branch and the suite stayed GREEN —
     * `readNonce` and `readTimestamp` had already refused the packet. The redundant check
     * was deleted and this is what pins the rule now.
     */
    @Test
    fun `every length below twenty-nine is malformed for a ping and a pong`() {
        for (type in listOf(HolePunch.TYPE_PING, HolePunch.TYPE_PONG)) {
            for (n in 1 until HolePunch.PACKET_SIZE) {
                val d = ByteArray(n); d[0] = type
                assertEquals(
                    "type 0x%02x length %d".format(type, n),
                    InboundPacket.Dropped(DropReason.MALFORMED_PING),
                    MediaDemux.classify(d, n, cid) { false },
                )
            }
        }
        // 29 is the first length that parses — with a matching call id for the ping and
        // any call id for the pong.
        val ping = HolePunch.buildPing(cid, ByteArray(12) { 2 }, 8L)
        assertTrue(MediaDemux.classify(ping, ping.size, cid) { false } is InboundPacket.Ping)
        val pong = HolePunch.buildPong(cid, ByteArray(12) { 2 }, 8L)
        assertTrue(MediaDemux.classify(pong, pong.size, cid) { false } is InboundPacket.Pong)
    }

    /** A pong's call id is never checked — the nonce is the correlator (`swift:1411-1415`). */
    @Test
    fun `a pong with a foreign call id still classifies as a pong`() {
        val pong = HolePunch.buildPong(cid + 999, ByteArray(12) { 3 }, 5L)
        val got = MediaDemux.classify(pong, pong.size, cid) { false }
        assertTrue("$got", got is InboundPacket.Pong)
    }

    /** Only the first [length] bytes are considered — a reused receive buffer has a tail. */
    @Test
    fun `the declared length bounds the packet`() {
        val buf = ByteArray(2048)
        val ping = HolePunch.buildPing(cid, ByteArray(12) { 9 }, 4L)
        ping.copyInto(buf)
        val got = MediaDemux.classify(buf, ping.size, cid) { false }
        assertTrue(got is InboundPacket.Ping)
        assertEquals(HolePunch.PACKET_SIZE, (got as InboundPacket.Ping).pong.size)
    }
}
