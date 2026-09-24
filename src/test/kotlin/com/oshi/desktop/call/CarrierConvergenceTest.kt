package com.oshi.desktop.call

import com.oshi.desktop.call.media.CallAudio
import com.oshi.desktop.call.media.CallAudioSession
import com.oshi.desktop.call.media.CallMediaFrame
import com.oshi.desktop.call.transport.IceCandidate
import com.oshi.desktop.call.transport.IceCandidateType
import com.oshi.desktop.call.transport.IcePriority
import com.oshi.desktop.call.transport.TxCarrierPolicy
import com.oshi.desktop.call.transport.UdpRelayClient
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two failures measured with REAL phones on 2026-09-23, reproduced on loopback with a
 * forwarding `:8089` relay:
 *
 *  - **Samsung shape**: the direct pair is selected and pongs, but the peer's media never
 *    reaches us on it (ours reaches the peer fine). Before the fix we kept sending on P2P,
 *    the peer's P2P stayed "healthy" and it kept sending direct into the void: about
 *    5 accepted frames/s instead of 50.
 *  - **iPhone shape**: the selected pair carries no media in either direction.
 *
 * Both legs run the same policy the phones run, so the convergence shown here is the one a
 * phone and the desktop now reach together.
 */
class CarrierConvergenceTest {

    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val salt = byteArrayOf(0x11, 0x22, 0x33, 0x44)
    private val callId = "C0FFEE00-1111-4222-8333-444455556666"
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val keyA = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE="
    private val keyB = "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="

    /** A legacy (token-less) `:8089` that acks registers and forwards media by recipient. */
    private class ForwardingRelay : AutoCloseable {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val address = InetSocketAddress(InetAddress.getLoopbackAddress(), socket.localPort)
        private val where = ConcurrentHashMap<String, SocketAddress>()
        private val t = Thread {
            val buf = ByteArray(4096)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { socket.receive(p) }.onFailure { return@Thread }
                val d = buf.copyOf(p.length)
                val type = d[0].toInt() and 0xFF
                var o = 1
                val rl = d[o++].toInt() and 0xFF; val r = String(d, o, rl); o += rl
                val sl = d[o++].toInt() and 0xFF; val s = String(d, o, sl); o += sl
                val cl = d[o++].toInt() and 0xFF; val c = String(d, o, cl); o += cl
                where[s] = p.socketAddress
                if (type == UdpRelayClient.TYPE_REGISTER) {
                    val ack = byteArrayOf(0x04, 0x01)
                    runCatching { socket.send(DatagramPacket(ack, 2, p.socketAddress)) }
                    continue
                }
                val to = where[r] ?: continue
                val sb = s.toByteArray(); val cb = c.toByteArray()
                val out = byteArrayOf(type.toByte(), sb.size.toByte()) + sb + byteArrayOf(cb.size.toByte()) + cb + d.copyOfRange(o, d.size)
                runCatching { socket.send(DatagramPacket(out, out.size, to)) }
            }
        }.apply { isDaemon = true; start() }
        override fun close() = socket.close()
    }

    private class Deviceless(key: ByteArray, salt: ByteArray, isCaller: Boolean, send: (ByteArray) -> Unit) :
        CallAudioSession(key, salt, isCaller, send) {
        override fun start() = Unit
        override fun stop() = Unit
    }

    private class Side(val leg: CallMediaLeg, val send: (ByteArray) -> Unit, val isCaller: Boolean)

    private fun side(isCaller: Boolean, relay: ForwardingRelay): Side {
        var send: ((ByteArray) -> Unit)? = null
        val leg = CallMediaLeg(
            spec = CallMediaSpec(
                callId, key, salt, isCaller,
                selfKey = if (isCaller) keyA else keyB,
                peerKey = if (isCaller) keyB else keyA,
            ),
            datagram = DatagramSocket(0, loopback),
            audioFor = { s -> send = s; Deviceless(key, salt, isCaller, s) },
            stunServer = null,
            hostCandidates = { s ->
                listOf(IceCandidate(IceCandidateType.HOST, "127.0.0.1", s.localPort, IcePriority.ios(IceCandidateType.HOST, 4)))
            },
            relayConfig = CallRelayConfig(udpRelay = relay.address),
        )
        return Side(leg, send!!, isCaller)
    }

    /**
     * Run both legs at up to 50 fps for [ms]. Returns, for the LAST 2 s, the fraction of the
     * peer's frames each side accepted (the box may not sustain 50 fps; the ratio is what
     * a listener hears).
     */
    private fun run(a: Side, b: Side, ms: Long): Pair<Double, Double> {
        val pcm = ByteArray(CallAudio.BYTES_PER_FRAME)
        var seq = 1L
        val start = System.currentTimeMillis()
        var aMark = 0L; var bMark = 0L; var seqMark = 0L; var marked = false
        while (System.currentTimeMillis() - start < ms) {
            a.leg.tick(); b.leg.tick()
            for (i in 0 until 5) {
                a.send(CallMediaFrame.encode(key, salt, true, seq, CallMediaFrame.TYPE_PCM_48K, pcm))
                b.send(CallMediaFrame.encode(key, salt, false, seq, CallMediaFrame.TYPE_PCM_48K, pcm))
                seq++
                Thread.sleep(20)
            }
            if (!marked && System.currentTimeMillis() - start >= ms - 2_000) {
                aMark = a.leg.framesAccepted.get(); bMark = b.leg.framesAccepted.get(); seqMark = seq; marked = true
            }
        }
        val sent = (seq - seqMark).toDouble().coerceAtLeast(1.0)
        return (a.leg.framesAccepted.get() - aMark) / sent to (b.leg.framesAccepted.get() - bMark) / sent
    }

    private fun connect(a: Side, b: Side) {
        a.leg.start(); b.leg.start()
        a.leg.addRemoteCandidates(b.leg.localCandidates())
        b.leg.addRemoteCandidates(a.leg.localCandidates())
        val deadline = System.currentTimeMillis() + 3_000
        while ((a.leg.selected == null || b.leg.selected == null) && System.currentTimeMillis() < deadline) {
            a.leg.tick(); b.leg.tick(); Thread.sleep(20)
        }
        assertTrue("no pair selected", a.leg.selected != null && b.leg.selected != null)
        // Let both relays register before media starts.
        val d2 = System.currentTimeMillis() + 3_000
        while (!(a.leg.udpRelayClient?.usable() == true && b.leg.udpRelayClient?.usable() == true) && System.currentTimeMillis() < d2) Thread.sleep(20)
    }

    @Test(timeout = 30_000)
    fun `samsung shape - one-way direct path converges on the relay and audio flows both ways`() {
        ForwardingRelay().use { relay ->
            val a = side(true, relay); val b = side(false, relay)
            try {
                connect(a, b)
                a.leg.dropInboundP2pMediaForTest = true   // B's direct media never reaches A
                val (aLast2s, bLast2s) = run(a, b, 11_000)
                println("[convergence] samsung shape: A ${a.leg.carrierStats()} | B ${b.leg.carrierStats()} | last 2 s accepted A=$aLast2s B=$bLast2s")
                assertEquals(TxCarrierPolicy.Carrier.UDP_RELAY, a.leg.txPolicy.current)
                assertEquals("the peer followed", TxCarrierPolicy.Carrier.UDP_RELAY, b.leg.txPolicy.current)
                assertTrue("A hears B again ($aLast2s of B's frames in the last 2 s)", aLast2s >= 0.9)
                assertTrue("B still hears A ($bLast2s)", bLast2s >= 0.9)
            } finally { a.leg.close(); b.leg.close() }
        }
    }

    @Test(timeout = 30_000)
    fun `iphone shape - a silent pair in both directions falls to the relay`() {
        ForwardingRelay().use { relay ->
            val a = side(true, relay); val b = side(false, relay)
            try {
                connect(a, b)
                a.leg.dropInboundP2pMediaForTest = true
                b.leg.dropInboundP2pMediaForTest = true
                val (aLast2s, bLast2s) = run(a, b, 11_000)
                println("[convergence] iphone shape: A ${a.leg.carrierStats()} | B ${b.leg.carrierStats()} | last 2 s accepted A=$aLast2s B=$bLast2s")
                assertTrue("A hears B ($aLast2s)", aLast2s >= 0.9)
                assertTrue("B hears A ($bLast2s)", bLast2s >= 0.9)
            } finally { a.leg.close(); b.leg.close() }
        }
    }

    @Test(timeout = 30_000)
    fun `a healthy direct pair keeps everything on p2p`() {
        ForwardingRelay().use { relay ->
            val a = side(true, relay); val b = side(false, relay)
            try {
                connect(a, b)
                val (aLast2s, bLast2s) = run(a, b, 7_000)
                println("[convergence] healthy: A ${a.leg.carrierStats()} | B ${b.leg.carrierStats()}")
                assertEquals(TxCarrierPolicy.Carrier.P2P, a.leg.txPolicy.current)
                assertEquals(0L, a.leg.relaySent.get())
                assertTrue("A=$aLast2s B=$bLast2s", aLast2s >= 0.9 && bLast2s >= 0.9)
            } finally { a.leg.close(); b.leg.close() }
        }
    }
}
