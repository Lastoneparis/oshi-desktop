package com.oshi.desktop.call.transport

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The client↔server leg under a [TurnClient] — PARITY.md row 2.1-t.
 *
 * Two kinds, as on iOS (`TurnClient.swift:276-300`): plain UDP to `45.67.216.197:3478`, and
 * TURN over TLS to `oshi-messenger.com:5349` (`VoiceCallManager.swift:14497-14505`). The
 * RELAYED leg is UDP either way (REQUESTED-TRANSPORT 17); only the hop to coturn changes.
 * On a stream there are no datagram boundaries, so [TlsLink] frames STUN by its length
 * field and ChannelData by its length rounded up to 4 (RFC 8656 §12.5), and pads outgoing
 * ChannelData to 4 — both exactly what `TurnClient.swift:342-365` and `:244-247` do.
 */
interface TurnLink : AutoCloseable {
    /** True when [send] must pad ChannelData to a 4-byte boundary (stream transports). */
    val padsChannelData: Boolean

    /** Opens the link and starts delivering whole messages to [onMessage]. Blocking connect. */
    fun start(onMessage: (ByteArray, Int) -> Unit)

    fun send(bytes: ByteArray): Boolean

    /** For logs: where this link goes. Never a credential. */
    val describe: String
}

/** Datagram link: one datagram = one message. */
class UdpLink(
    private val server: InetSocketAddress,
    private val socket: DatagramSocket = DatagramSocket(0),
) : TurnLink {
    private val closed = AtomicBoolean(false)
    private var rx: Thread? = null
    override val padsChannelData = false
    override val describe: String get() = "udp ${server.hostString}:${server.port}"

    private val target: InetSocketAddress by lazy {
        if (server.isUnresolved) InetSocketAddress(server.hostString, server.port) else server
    }

    override fun start(onMessage: (ByteArray, Int) -> Unit) {
        if (rx != null) return
        socket.soTimeout = 500
        rx = Thread({
            val buf = ByteArray(TurnClient.RECEIVE_BUFFER)
            while (!closed.get()) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    if (closed.get()) return@Thread
                    continue
                }
                runCatching { onMessage(buf, pkt.length) }
            }
        }, "oshi-turn-rx").apply { isDaemon = true; start() }
    }

    override fun send(bytes: ByteArray): Boolean =
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, target)); true }.getOrDefault(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        rx?.let { runCatching { it.join(600) } }
    }
}

/**
 * TURN over TLS. Certificate validation is ON and so is hostname verification — the JDK's
 * `SSLSocket` does NOT verify the hostname unless asked, so [start] sets the endpoint
 * identification algorithm to HTTPS. [host] must therefore be the certificate's name
 * (`oshi-messenger.com`), never an IP — the same constraint iOS states at `swift:279-281`.
 */
class TlsLink(
    private val host: String,
    private val port: Int,
    private val factory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val connectTimeoutMs: Int = 8_000,
) : TurnLink {
    private val closed = AtomicBoolean(false)
    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    private var rx: Thread? = null
    override val padsChannelData = true
    override val describe: String get() = "tls $host:$port"

    override fun start(onMessage: (ByteArray, Int) -> Unit) {
        if (socket != null) return
        val raw = java.net.Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
        val s = factory.createSocket(raw, host, port, true) as SSLSocket
        s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        s.startHandshake()
        socket = s
        out = s.outputStream
        val input = s.inputStream
        rx = Thread({ readLoop(input, onMessage) }, "oshi-turn-tls-rx").apply { isDaemon = true; start() }
    }

    private fun readLoop(input: InputStream, onMessage: (ByteArray, Int) -> Unit) {
        val framer = StreamFramer()
        val buf = ByteArray(16_384)
        while (!closed.get()) {
            val n = try { input.read(buf) } catch (_: Exception) { -1 }
            if (n < 0) return
            for (msg in framer.feed(buf, n)) runCatching { onMessage(msg, msg.size) }
        }
    }

    override fun send(bytes: ByteArray): Boolean {
        val o = out ?: return false
        return runCatching { synchronized(o) { o.write(bytes); o.flush() }; true }.getOrDefault(false)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        rx?.let { runCatching { it.join(600) } }
    }
}

/**
 * Cuts a TURN byte stream into messages. Pure, so the framing is unit-tested without a
 * socket. Top two bits `01` = ChannelData, total `4 + pad4(length)`; `00` = STUN, total
 * `20 + length`. A length that cannot be a TURN message drops the buffer (iOS resyncs the
 * same way, `swift:359-360`).
 */
class StreamFramer {
    private val pending = ByteArrayOutputStream()

    fun feed(chunk: ByteArray, n: Int = chunk.size): List<ByteArray> {
        pending.write(chunk, 0, n)
        val data = pending.toByteArray()
        val out = ArrayList<ByteArray>()
        var o = 0
        while (data.size - o >= 4) {
            val top = (data[o].toInt() and 0xC0) ushr 6
            val len = ((data[o + 2].toInt() and 0xFF) shl 8) or (data[o + 3].toInt() and 0xFF)
            val total = when (top) {
                0b01 -> 4 + ((len + 3) and 3.inv())
                0b00 -> { if (data.size - o < 20) break; 20 + len }
                else -> -1
            }
            if (total <= 0 || total > MAX_MESSAGE) { o = data.size; break }
            if (data.size - o < total) break
            // ChannelData: hand over the UNPADDED frame so the length field still matches.
            val keep = if (top == 0b01) 4 + len else total
            out += data.copyOfRange(o, o + keep)
            o += total
        }
        pending.reset()
        if (o < data.size) pending.write(data, o, data.size - o)
        return out
    }

    companion object {
        const val MAX_MESSAGE = 70_000
    }
}
