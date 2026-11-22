package com.oshi.desktop.call.bench

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * __CALL_FLUIDITY_BENCH_2026_11_21__ A local stand-in for the `:8089` UDP media relay with a
 * [NetworkImpairment] on each direction. Never production: it binds 127.0.0.1 on a free port.
 *
 * Behaves like `ServerVPS/call_server.js` 1090-1117 where it matters to a client: the
 * sender's endpoint is learnt from EVERY datagram (so a client that moved is found on its
 * next packet), a register is answered `04 01`, `05` pings are swallowed, and `01`/`02`
 * media is forwarded as `[type][senderLen][sender][callIdLen][callId][payload]`. No tokens
 * (clients run the legacy token-less register), no dedup (each client sends one copy).
 *
 * Impairment is applied per SENDER, at the time the relay receives the datagram.
 */
class ImpairedUdpRelay(private val impairmentFor: (sender: String) -> NetworkImpairment) : AutoCloseable {
    private val socket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    val address: InetSocketAddress get() = InetSocketAddress(InetAddress.getLoopbackAddress(), socket.localPort)
    private val endpoints = ConcurrentHashMap<String, SocketAddress>()
    private val nets = ConcurrentHashMap<String, NetworkImpairment>()
    private val closed = AtomicBoolean(false)
    private val t0 = System.nanoTime()
    private class Pending(val atNs: Long, val order: Long, val data: ByteArray, val to: String)
    private val queue = PriorityQueue<Pending>(compareBy<Pending>({ it.atNs }, { it.order }))
    private var order = 0L
    private val lock = Object()

    @Volatile var forwarded = 0L; private set
    @Volatile var dropped = 0L; private set

    fun nowMs(): Double = (System.nanoTime() - t0) / 1e6

    private val rx = Thread({
        val buf = ByteArray(4096)
        while (!closed.get()) {
            val p = DatagramPacket(buf, buf.size)
            try { socket.receive(p) } catch (_: Exception) { continue }
            handle(p.data.copyOf(p.length), p.socketAddress)
        }
    }, "bench-relay-rx").apply { isDaemon = true; start() }

    private val tx = Thread({
        while (!closed.get()) {
            var next: Pending? = null
            synchronized(lock) {
                val h = queue.peek()
                if (h != null && h.atNs <= System.nanoTime()) next = queue.poll()
            }
            val n = next
            if (n == null) { LockSupport.parkNanos(200_000); continue }
            val ep = endpoints[n.to] ?: continue
            runCatching { socket.send(DatagramPacket(n.data, n.data.size, ep)) }
            forwarded++
        }
    }, "bench-relay-tx").apply { isDaemon = true; start() }

    private fun handle(m: ByteArray, from: SocketAddress) {
        if (m.size < 4) return
        var o = 0
        val type = m[o++].toInt() and 0xFF
        val rl = m[o++].toInt() and 0xFF; if (o + rl > m.size) return
        val recip = String(m, o, rl); o += rl
        val sl = m[o++].toInt() and 0xFF; if (o + sl > m.size) return
        val sender = String(m, o, sl); o += sl
        val cl = m[o++].toInt() and 0xFF; if (o + cl > m.size) return
        val callId = m.copyOfRange(o, o + cl); o += cl
        endpoints[sender] = from
        if (type == 0x04) { runCatching { socket.send(DatagramPacket(byteArrayOf(4, 1), 2, from)) }; return }
        if (type != 0x01 && type != 0x02) return
        val payload = m.copyOfRange(o, m.size)
        val net = nets.getOrPut(sender) { impairmentFor(sender) }
        val at = net.route(nowMs())
        if (at == null) { dropped++; return }
        val s = sender.toByteArray()
        val out = byteArrayOf(type.toByte(), s.size.toByte()) + s + byteArrayOf(cl.toByte()) + callId + payload
        synchronized(lock) { queue.add(Pending(t0 + (at * 1e6).toLong(), order++, out, recip)) }
    }

    override fun close() {
        closed.set(true)
        socket.close()
    }
}
