package com.oshi.desktop.call

import com.oshi.desktop.call.media.CallAudio
import com.oshi.desktop.call.media.CallAudioSession
import com.oshi.desktop.call.media.PlaybackBuffer
import com.oshi.desktop.call.media.CallMediaFrame
import com.oshi.desktop.call.transport.IceCandidate
import com.oshi.desktop.call.transport.IceCandidateType
import com.oshi.desktop.call.transport.IcePriority
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARITY.md row 2.1 — the media JOINT, over real loopback UDP.
 *
 * ============================================================ WHAT THIS PROVES
 *
 * [MediaSocketTest][com.oshi.desktop.call.transport.MediaSocketTest] already proves the
 * socket and `CallMediaTest` already proves the codec. What neither could prove, because
 * no object owned both, is the thing this file is about: that a frame sealed by the
 * SENDING side's audio path leaves one process's socket, crosses `127.0.0.1`, is
 * classified as media, is handed to the RECEIVING side's [CallAudioSession] rather than
 * decoded twice, authenticates, passes exactly one replay window, and lands in the
 * speaker queue as the same 1 920 bytes that went in.
 *
 * ============================================================ WHAT IT DOES NOT PROVE
 *
 * Said here rather than in a commit message, because this file is where somebody will
 * look for the claim:
 *
 *  - **No microphone and no speaker are opened anywhere in this file.** Every leg is
 *    either transport-only or carries a [Deviceless] session: the codec, the replay
 *    window and the playback queue are the real ones, and only `start`/`stop` are stubbed.
 *    CI has no audio hardware and this machine's device is shared with other sessions.
 *  - **No NAT was traversed.** Loopback has no mapping to open, so the hole punch is
 *    exercised as a protocol and not as a traversal.
 *  - **No packet from this code has reached a phone, and no real STUN server has answered
 *    one.**
 *  - **No call has been made between two people.** "Audio crosses loopback in a test" is
 *    the whole claim.
 */
class CallMediaLegTest {

    private val key = ByteArray(32) { (it * 5 + 1).toByte() }
    private val salt = byteArrayOf(0x7A, 0x0B, 0x5C, 0x2D)
    private val callId = "9A8B7C6D-5E4F-3021-1122-334455667788"
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    private fun spec(isCaller: Boolean) = CallMediaSpec(callId, key, salt, isCaller)

    /**
     * A leg on `127.0.0.1` whose host candidate is its own loopback address.
     *
     * The real gatherer SKIPS loopback on purpose (both shipped clients do), so a
     * loopback test that used it would gather nothing, publish nothing, and silently test
     * none of the candidate path. See [CallMediaLeg]'s `hostCandidates` parameter.
     */
    private fun leg(
        isCaller: Boolean,
        audio: ((send: (ByteArray) -> Unit) -> CallAudioSession)? = null,
    ): CallMediaLeg = CallMediaLeg(
        spec = spec(isCaller),
        datagram = DatagramSocket(0, loopback),
        audioFor = audio,
        stunServer = null,
        hostCandidates = { s ->
            listOf(
                IceCandidate(
                    IceCandidateType.HOST, "127.0.0.1", s.localPort,
                    IcePriority.ios(IceCandidateType.HOST, 4),
                ),
            )
        },
    )

    /** Poll rather than sleep a fixed amount — a fixed sleep is a flake on a loaded box. */
    private fun waitFor(timeoutMs: Long = 3_000, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    // ============================================================== the headline

    /**
     * Two legs punch on loopback, elect a pair, and carry a 20 ms PCM frame end to end —
     * sealed by the caller's direction, opened by the callee's replay window.
     *
     * The frame is built with exactly the parameters [CallAudioSession]'s capture pump
     * uses (`sessionKey, baseSalt, isCaller, seq, TYPE_PCM_48K`) and pushed through
     * [CallMediaLeg.sendSealed], which IS the lambda that pump is handed. Nothing is
     * mocked: the bytes go through the kernel.
     */
    @Test
    fun `two legs punch on loopback and carry sealed pcm end to end`() {
        var received: CallAudioSession? = null
        val a = leg(isCaller = true)
        val b = leg(isCaller = false, audio = { send ->
            Deviceless(key, salt, false, send).also { received = it }
        })
        try {
            a.start()
            b.start()

            // Each side learns the other's address exactly as an `ice_candidate` signal
            // would deliver it — through the public API, not a back door.
            assertEquals(1, a.addRemoteCandidates(b.localCandidates()))
            assertEquals(1, b.addRemoteCandidates(a.localCandidates()))

            assertTrue(
                "no pair went live after probing",
                waitFor {
                    a.tick(); b.tick()
                    Thread.sleep(20)
                    a.selected != null && b.selected != null
                },
            )
            assertEquals(b.socket.localPort, a.selected!!.port)

            val pcm = ByteArray(CallAudio.BYTES_PER_FRAME) { ((it * 3) % 251).toByte() }
            val sealed = CallMediaFrame.encode(key, salt, true, 1, CallMediaFrame.TYPE_PCM_48K, pcm)
            assertTrue("nothing left the socket", a.sendSealed(sealed))

            assertTrue("the frame never arrived", waitFor { b.framesAccepted.get() == 1L })
            assertEquals("and none were refused", 0L, b.framesRefused.get())

            // ONE REPLAY WINDOW, NOT TWO: the leg took the sealed bytes, so the socket
            // itself decoded nothing and its own counter stayed at zero.
            assertEquals(0L, b.socket.framesPlayed.get())

            // The bytes that reached the speaker queue are the bytes that went in.
            val played = received!!.playback.take(500L)
            assertNotNull("nothing reached the speaker queue", played)
            assertArrayEquals(pcm, played)

            assertEquals(1L, a.socket.framesSent.get())
            assertTrue(a.socket.pongsCorrelated.get() > 0)
            assertTrue(b.socket.pingsAnswered.get() > 0)
        } finally {
            a.close(); b.close()
        }
    }

    /**
     * A frame sealed under a DIFFERENT key does not authenticate, is counted as refused,
     * and never reaches the speaker queue.
     *
     * The control matters as much as the assertion: the same leg accepts a correctly
     * sealed frame immediately afterwards, so this cannot pass by refusing everything.
     */
    @Test
    fun `a forged frame is refused and a real one on the same leg is not`() {
        var session: CallAudioSession? = null
        val b = leg(isCaller = false, audio = { send ->
            Deviceless(key, salt, false, send).also { session = it }
        })
        try {
            val from = InetSocketAddress(loopback, 40_000)
            val forged = CallMediaFrame.encode(
                ByteArray(32) { 0x33 }, salt, true, 1, CallMediaFrame.TYPE_PCM_48K, ByteArray(64),
            )
            b.socket.handle(forged, forged.size, from)
            assertEquals(1L, b.framesRefused.get())
            assertEquals(0L, b.framesAccepted.get())
            assertEquals(0, session!!.playback.size())

            val good = CallMediaFrame.encode(key, salt, true, 1, CallMediaFrame.TYPE_PCM_48K, ByteArray(64))
            b.socket.handle(good, good.size, from)
            assertEquals(1L, b.framesAccepted.get())
            assertEquals(1, session!!.playback.size())
        } finally {
            b.close()
        }
    }

    /** The same frame twice is played once — the audio session's window, not the socket's. */
    @Test
    fun `a replayed frame is refused the second time`() {
        val b = leg(isCaller = false, audio = { send -> Deviceless(key, salt, false, send) })
        try {
            val from = InetSocketAddress(loopback, 40_000)
            val frame = CallMediaFrame.encode(key, salt, true, 9, CallMediaFrame.TYPE_PCM_48K, ByteArray(64))
            b.socket.handle(frame, frame.size, from)
            b.socket.handle(frame, frame.size, from)
            assertEquals(1L, b.framesAccepted.get())
            assertEquals(1L, b.framesRefused.get())
        } finally {
            b.close()
        }
    }

    /**
     * A transport-only leg is a genuinely different object: with no audio session the
     * socket decodes as it always did and `framesPlayed` moves instead.
     *
     * This is the control for the headline's `framesPlayed == 0` assertion — without it,
     * that zero could mean "the hook fired" or "nothing arrived at all".
     */
    @Test
    fun `a transport-only leg lets the socket decode`() {
        val b = leg(isCaller = false)
        try {
            val frame = CallMediaFrame.encode(key, salt, true, 1, CallMediaFrame.TYPE_PCM_48K, ByteArray(64))
            b.socket.handle(frame, frame.size, InetSocketAddress(loopback, 40_000))
            assertEquals(1L, b.socket.framesPlayed.get())
            assertEquals(0L, b.framesAccepted.get())
        } finally {
            b.close()
        }
    }

    // ============================================================== candidates

    /** The local set is published as soon as it is gathered, and STUN adds to it later. */
    @Test
    fun `starting a leg publishes its host candidates once`() {
        val published = ArrayList<List<IceCandidate>>()
        val a = leg(isCaller = true)
        try {
            a.onLocalCandidates = { published += it }
            a.start()
            assertEquals(1, published.size)
            assertEquals(1, published[0].size)
            assertEquals("127.0.0.1", published[0][0].ip)
            assertEquals(a.socket.localPort, published[0][0].port)
            assertEquals(IceCandidateType.HOST, published[0][0].type)
            assertEquals(1L, a.candidatePublishes.get())
        } finally {
            a.close()
        }
    }

    /** Start is idempotent: a second call does not re-gather or re-publish. */
    @Test
    fun `start is idempotent`() {
        var publishes = 0
        val a = leg(isCaller = true)
        try {
            a.onLocalCandidates = { publishes++ }
            a.start()
            a.start()
            assertEquals(1, publishes)
        } finally {
            a.close()
        }
    }

    /**
     * ============================================================ THE SRFLX PUBLISH, AND ITS BOUND
     *
     * A STUN Binding Response that this socket's own transaction produced adds a SRFLX
     * candidate and publishes the grown set — that second publish is the only way a peer
     * on another network ever learns a reachable address, and nothing else in this package
     * exercises it end to end.
     *
     * The bound is asserted alongside it, because `publish` costs an HTTP POST to a
     * rate-limited call server and an injected response for a transaction we DID send can
     * drive it. Four publishes land; the fifth does not. The control — the fourth still
     * landing — is what makes this a test of the cap rather than of "publishing stops".
     */
    @Test
    fun `an srflx answer publishes a grown candidate set, bounded`() {
        val published = ArrayList<List<IceCandidate>>()
        val a = leg(isCaller = true)
        val stun = DatagramSocket(0, loopback)
        try {
            stun.soTimeout = 2_000
            a.onLocalCandidates = { published += it }
            a.start()
            assertEquals("the host gather is publish 1", 1, published.size)

            // Five distinct mappings. Each needs a transaction id THIS socket actually
            // sent, recovered off the wire exactly as MediaSocketTest recovers one.
            for (n in 0 until 5) {
                a.socket.requestSrflx(java.net.InetSocketAddress(loopback, stun.localPort))
                val buf = ByteArray(64)
                val pkt = java.net.DatagramPacket(buf, buf.size)
                stun.receive(pkt)
                val tx = com.oshi.desktop.call.transport.StunBinding.transactionId(buf, pkt.offset, pkt.length)!!
                val resp = stunResponse(tx, port = 51_000 + n)
                a.socket.handle(resp, resp.size, java.net.InetSocketAddress(loopback, stun.localPort))
            }

            assertEquals(
                "publishes are bounded at ${CallMediaLeg.MAX_CANDIDATE_PUBLISHES}",
                CallMediaLeg.MAX_CANDIDATE_PUBLISHES, a.candidatePublishes.get(),
            )
            assertEquals(CallMediaLeg.MAX_CANDIDATE_PUBLISHES.toInt(), published.size)

            // The set that WAS published grew, and it grew with srflx entries.
            val last = published.last()
            assertTrue("the srflx candidates must be signalled", last.size > 1)
            assertTrue(last.any { it.type == IceCandidateType.SRFLX && it.ip == "203.0.113.42" })
            // And the local set kept growing even after publishing stopped, so the cap
            // throttles the SIGNAL and never the gather.
            assertEquals(6, a.localCandidates().size)
        } finally {
            stun.close()
            a.close()
        }
    }

    /** A Binding Success Response for `203.0.113.42:[port]` under [tx]. */
    private fun stunResponse(tx: ByteArray, port: Int): ByteArray {
        // XOR-MAPPED-ADDRESS: family 0x01, port XOR the top 16 bits of the magic cookie,
        // address XOR the whole cookie. 203.0.113.42 ^ 2112A442 = EA12D568.
        val xport = port xor 0x2112
        val attr = byteArrayOf(
            0x00, 0x20, 0x00, 0x08, 0x00, 0x01,
            ((xport ushr 8) and 0xFF).toByte(), (xport and 0xFF).toByte(),
            0xEA.toByte(), 0x12, 0xD5.toByte(), 0x68,
        )
        val out = ByteArray(20 + attr.size)
        out[0] = 0x01; out[1] = 0x01
        out[2] = 0; out[3] = attr.size.toByte()
        out[4] = 0x21; out[5] = 0x12; out[6] = 0xA4.toByte(); out[7] = 0x42
        tx.copyInto(out, 8)
        attr.copyInto(out, 20)
        return out
    }

    // ============================================================== teardown

    /**
     * ============================================================ REQUIREMENT (d)
     *
     * A session whose device refuses to open must take the whole leg down and let the
     * exception out. CI can never produce that device, which is why
     * [CallAudioSession.start] is `open` — see its doc.
     *
     * Both halves are asserted: the exception propagates (so [CallLane] can end the call)
     * AND the socket is closed (so a failed call does not leak a bound UDP port on top of
     * the microphone it did not manage to open).
     */
    @Test
    fun `a device that will not open closes the socket and rethrows`() {
        val stopped = AtomicBoolean(false)
        val a = CallMediaLeg(
            spec = spec(true),
            datagram = DatagramSocket(0, loopback),
            audioFor = { send -> RefusingSession(send, stopped) },
            stunServer = null,
            hostCandidates = { emptyList() },
        )
        val thrown = runCatching { a.start() }.exceptionOrNull()
        assertTrue(
            "the device failure must reach the caller, got $thrown",
            thrown is javax.sound.sampled.LineUnavailableException,
        )
        assertTrue("the leg must be closed", a.isClosed)
        assertTrue("stop() must run even on the failure path", stopped.get())
        // The UDP port is released too: a closed DatagramSocket reports -1.
        assertEquals(-1, a.socket.localPort)
        assertFalse("a closed leg sends nothing", a.sendSealed(ByteArray(64)))
    }

    /** Close releases both halves, is idempotent, and leaves nothing selected. */
    @Test
    fun `close is idempotent and releases the audio session`() {
        val stopped = AtomicBoolean(false)
        val a = CallMediaLeg(
            spec = spec(true),
            datagram = DatagramSocket(0, loopback),
            audioFor = { send -> RecordingSession(send, stopped) },
            stunServer = null,
            hostCandidates = { emptyList() },
        )
        a.start()
        a.close()
        a.close()
        assertTrue(stopped.get())
        assertTrue(a.isClosed)
        assertNull(a.selected)
        // A tick on a closed leg is a no-op rather than an exception: the probe timer can
        // fire once more after teardown by construction.
        assertNull(a.tick())
    }

    /** A closed leg accepts no more remote candidates — nothing to probe from. */
    @Test
    fun `a closed leg takes no more candidates`() {
        val a = leg(isCaller = true)
        a.start()
        a.close()
        assertEquals(
            0,
            a.addRemoteCandidates(
                listOf(IceCandidate(IceCandidateType.HOST, "127.0.0.1", 9, 0)),
            ),
        )
    }

    // ============================================================== fixtures

    /**
     * A real session — real codec, real replay window, real [PlaybackBuffer] — with the
     * DEVICE stubbed out.
     *
     * Not squeamishness, and the reason is a bug this file already caught once: the real
     * `start()` opens the microphone AND starts the render thread, and that render thread
     * **drains the playback queue at 50 fps**. The first version of these tests called the
     * real `start()`, grabbed the shared hardware on the machine running them, and then
     * found an empty queue a millisecond after the frame landed — an assertion failure
     * that looked like "the audio never arrived" and was actually "the speaker already
     * played it". On CI, where no device exists, the same call would have thrown instead.
     *
     * So the device is stubbed and everything downstream of it is real.
     */
    private open class Deviceless(
        key: ByteArray,
        salt: ByteArray,
        isCaller: Boolean,
        send: (ByteArray) -> Unit,
        private val stopped: AtomicBoolean? = null,
    ) : CallAudioSession(key, salt, isCaller, send) {
        override fun start() = Unit
        override fun stop() { stopped?.set(true) }
    }

    /** A session whose device refuses to open. See REQUIREMENT (d). */
    private class RefusingSession(send: (ByteArray) -> Unit, stopped: AtomicBoolean) :
        Deviceless(ByteArray(32), ByteArray(4), true, send, stopped) {
        override fun start(): Unit = throw javax.sound.sampled.LineUnavailableException("no device here")
    }

    /** A session that opens and closes without touching hardware. */
    private class RecordingSession(send: (ByteArray) -> Unit, stopped: AtomicBoolean) :
        Deviceless(ByteArray(32), ByteArray(4), true, send, stopped)
}
