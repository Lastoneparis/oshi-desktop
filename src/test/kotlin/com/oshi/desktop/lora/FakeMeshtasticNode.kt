package com.oshi.desktop.lora

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Meshtastic node, in this process, over a real loopback socket.
 *
 * Not a mock of [LoRaLink]: mocking the thing under test would assert that the code we
 * wrote calls the code we wrote, which is the failure mode `ClientWiringTest` exists to
 * avoid. This speaks the actual stream protocol — `[0x94][0xC3][len BE16][protobuf]` —
 * so the link is exercised against bytes rather than against an expectation.
 *
 * What it deliberately does NOT model: LoRa itself. Airtime, duty cycle, the 237-byte
 * payload ceiling and packet loss are properties of a radio, and pretending to have them
 * here would be the "verified" claim row 0.27 refuses to make.
 */
class FakeMeshtasticNode : AutoCloseable {

    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val accept: Thread

    /** Every `ToRadio` protobuf the client has framed and sent, in order. */
    val received = CopyOnWriteArrayList<ByteArray>()

    /** Connections accepted so far — a reconnect increments it. */
    val connections = AtomicInteger(0)

    /** Fires when the first `ToRadio` of a connection arrives (the want_config handshake). */
    @Volatile var handshake = CountDownLatch(1)

    @Volatile private var current: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var running = true

    val port: Int get() = server.localPort
    val host: String get() = "127.0.0.1"

    init {
        accept = Thread({
            while (running) {
                val s = try { server.accept() } catch (e: Exception) { break }
                s.tcpNoDelay = true
                current = s
                out = s.getOutputStream()
                connections.incrementAndGet()
                Thread({ read(s) }, "fake-node-read").apply { isDaemon = true }.start()
            }
        }, "fake-node-accept").apply { isDaemon = true }
        accept.start()
    }

    /** Frame and push one `FromRadio` at the client. */
    fun push(protobuf: ByteArray) {
        val o = out ?: return
        val framed = LoRaAttach.StreamFraming.encode(protobuf)
        synchronized(o) { o.write(framed); o.flush() }
    }

    /** Write raw bytes — for the desync and straddle cases, which framing cannot express. */
    fun pushRaw(bytes: ByteArray) {
        val o = out ?: return
        synchronized(o) { o.write(bytes); o.flush() }
    }

    /** Drop the connection the way a radio losing power does: no FIN handshake niceties. */
    fun dropConnection() {
        handshake = CountDownLatch(1)
        try { current?.close() } catch (_: Exception) {}
        current = null
        out = null
    }

    private fun read(s: Socket) {
        var buffer = ByteArray(0)
        val chunk = ByteArray(4096)
        try {
            while (running) {
                val n = s.getInputStream().read(chunk)
                if (n < 0) break
                buffer += chunk.copyOfRange(0, n)
                while (true) {
                    val d = LoRaAttach.StreamFraming.decode(buffer) ?: break
                    buffer = buffer.copyOfRange(d.consumed, buffer.size)
                    received.add(d.protobuf)
                    handshake.countDown()
                }
            }
        } catch (_: Exception) {
            // A closed socket is how this ends; it is not a failure of the fake.
        }
    }

    override fun close() {
        running = false
        try { current?.close() } catch (_: Exception) {}
        try { server.close() } catch (_: Exception) {}
    }
}
