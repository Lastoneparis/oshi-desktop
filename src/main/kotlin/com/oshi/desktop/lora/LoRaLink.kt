package com.oshi.desktop.lora

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The TCP attachment to a Meshtastic node — PARITY.md row 0.27's missing half.
 *
 * [LoRaAttach] argued the transport choice and then deliberately stopped short of writing
 * one: *"No socket, no serial port and no reconnect loop is implemented here."* This is
 * that socket, and it is written to the same rule the rest of the package follows — the
 * codec is checked against the shipped trees, the LINK is checked against a server that
 * behaves like a radio, and neither is reported as the other.
 *
 * **STILL UNVERIFIED AGAINST A REAL RADIO.** Everything below is exercised against
 * `FakeMeshtasticNode`, which speaks Meshtastic's stream framing over a loopback socket.
 * That proves the framing, the reassembly, the handshake order and the reconnect — it
 * proves nothing about a device on a desk. Row 0.27 stays 🟡 until a board answers.
 *
 * ============================================================ WHY BLE IS NOT HERE
 *
 * Both phones attach over BLE and the JDK has no Bluetooth ([LoRaAttach] tabulates the
 * three transports). BLE is a PHONE constraint, not a protocol one: a node exposes the
 * same `ToRadio`/`FromRadio` protobufs on BLE, USB serial and TCP :4403, and the bytes
 * [LoRaProto.toRadioPacket] produces are identical on all three. So this reaches the same
 * radio an iPhone reaches, over the one transport that costs no dependency.
 *
 * ============================================================ THE HANDSHAKE IS NOT OPTIONAL
 *
 * A freshly-opened Meshtastic stream is SILENT. The node sends nothing until the client
 * asks for the config stream with `want_config_id` ([LoRaProto.wantConfig]), which is why
 * [start] sends one before anything else and why a link that skipped it would look like a
 * working socket with a dead radio on the other end — connected, and never a byte inbound.
 * The id is arbitrary and only has to be echoed back in the `config_complete_id` that ends
 * the burst; it is generated per connection so a reconnect cannot be confused with the
 * tail of the previous session's config.
 *
 * ============================================================ THE BUFFER OUTLIVES THE READ
 *
 * TCP is a byte stream: one `read()` can return half a frame, or three frames and a bit.
 * The accumulating buffer therefore lives across reads, and [StreamFraming.decode]'s
 * `consumed` — which counts any garbage skipped before the magic — is what advances it.
 * Draining in a loop after every read is what stops a fast burst from being processed one
 * frame per socket read.
 *
 * A frame is at most [LoRaAttach.StreamFraming.MAX_FRAME_BYTES]; the buffer is capped at
 * twice that plus a header. Beyond it the stream is desynchronised rather than busy, and
 * the oldest bytes are dropped instead of growing a buffer for ever on a peer that is
 * sending noise.
 */
class LoRaLink(
    private val host: String,
    private val port: Int = LoRaAttach.TCP_PORT,
    /** Called with each complete `FromRadio` protobuf, on the read thread. */
    private val onFromRadio: (ByteArray) -> Unit,
    private val log: (String) -> Unit = {},
    /** Injectable for tests; production opens a real socket. */
    private val dial: (String, Int, Int) -> Socket = { h, p, timeoutMs ->
        Socket().apply { connect(InetSocketAddress(h, p), timeoutMs) }
    },
    private val connectTimeoutMs: Int = 8_000,
    /** Sleep between reconnect attempts. Injectable so a test does not wait in real time. */
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {

    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private var thread: Thread? = null

    /** Reconnect attempts since the last successful connection — observable for tests. */
    val reconnects = AtomicInteger(0)

    val isConnected: Boolean get() = connected.get()

    /**
     * Open the link and keep it open.
     *
     * Returns immediately; the connection happens on the read thread, so a node that is
     * powered off does not block the caller. `isConnected` is the state, never the return.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ runLoop() }, "oshi-lora-$host").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        closeQuietly()
        thread?.interrupt()
    }

    /**
     * Frame and write one `ToRadio`.
     *
     * @return false when there is no live socket. **True is not delivery** — the kernel
     *         accepts writes into a socket whose peer has already gone, exactly the caveat
     *         [com.oshi.desktop.mesh.MeshFraming] records for the mesh. It means the bytes
     *         left this process.
     */
    fun send(toRadio: ByteArray): Boolean {
        val o = out ?: return false
        return try {
            val framed = LoRaAttach.StreamFraming.encode(toRadio)
            synchronized(o) { o.write(framed); o.flush() }
            true
        } catch (e: IOException) {
            log("lora: write failed: ${e.message}")
            // Drop the connection so the loop reconnects rather than spinning on a dead pipe.
            closeQuietly()
            false
        }
    }

    // ------------------------------------------------------------------ internals

    private fun runLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (running.get()) {
            try {
                val s = dial(host, port, connectTimeoutMs)
                s.tcpNoDelay = true
                socket = s
                out = s.getOutputStream()
                connected.set(true)
                backoffMs = INITIAL_BACKOFF_MS
                log("lora: attached to $host:$port")

                // The node is silent until asked. See the class doc.
                val configId = (System.nanoTime().toInt() and 0x7FFFFFFF).toUInt()
                if (!send(LoRaProto.wantConfig(configId))) throw IOException("handshake write failed")

                pump(s.getInputStream())
            } catch (e: Exception) {
                if (running.get()) log("lora: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                connected.set(false)
                closeQuietly()
            }
            if (!running.get()) break
            reconnects.incrementAndGet()
            sleeper(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** Read until the stream ends, draining every complete frame the buffer holds. */
    private fun pump(input: InputStream) {
        var buffer = ByteArray(0)
        val chunk = ByteArray(4096)
        while (running.get()) {
            val n = input.read(chunk)
            if (n < 0) throw IOException("node closed the stream")
            if (n == 0) continue
            buffer += chunk.copyOfRange(0, n)

            while (true) {
                val decoded = LoRaAttach.StreamFraming.decode(buffer) ?: break
                buffer = buffer.copyOfRange(decoded.consumed, buffer.size)
                try {
                    onFromRadio(decoded.protobuf)
                } catch (e: Exception) {
                    // One unreadable frame costs one frame, never the link — the same rule
                    // row 0.10 applies to one bad envelope in a relay page.
                    log("lora: handler threw on a frame: ${e.message}")
                }
            }

            if (buffer.size > MAX_BUFFER_BYTES) {
                // Desynchronised, not busy: no legal frame is this long, so the head cannot
                // contain one. Keeping the tail lets the next real magic re-sync.
                log("lora: ${buffer.size} bytes with no frame — resynchronising")
                buffer = buffer.copyOfRange(buffer.size - LoRaAttach.StreamFraming.MAX_FRAME_BYTES, buffer.size)
            }
        }
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
    }

    companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L

        /** Two frames plus a header: enough to hold a straddle, small enough to bound noise. */
        val MAX_BUFFER_BYTES =
            2 * LoRaAttach.StreamFraming.MAX_FRAME_BYTES + LoRaAttach.StreamFraming.HEADER_BYTES
    }
}
