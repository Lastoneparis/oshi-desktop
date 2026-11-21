package com.oshi.desktop.lora

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The TCP attachment to a MeshCore companion radio — the MeshCore twin of [LoRaLink].
 *
 * MeshCore's WiFi companion firmware serves the companion protocol on TCP (port 5000 by default) with
 * the same framing as its USB serial one: app → radio '<' + len LE16 + frame, radio → app '>' + len LE16
 * + frame ([MeshCore.Serial]). The JDK has no Bluetooth and this project takes no serial dependency, so
 * this is the one MeshCore transport the desktop can reach at no cost — the same reasoning [LoRaAttach]
 * gives for Meshtastic over TCP.
 *
 * The conversation itself — APP_START, the "#oshi-bridge" channel slot, SYNC_NEXT_MESSAGE on every
 * MSG_WAITING, the "OB" envelope joiner — is [MeshCoreSession], shared byte-for-byte with Android. This
 * class only moves its bytes: one companion request in flight at a time, the next written when the
 * previous is answered (or has gone [REPLY_TIMEOUT_MS] unanswered, so one lost reply cannot wedge the
 * queue for the life of the connection).
 *
 * RECEIVE ONLY, like the Meshtastic lane: an OSHI envelope off MeshCore is sealed with the legacy Double
 * Ratchet this client does not implement (PARITY.md rows 0.16 / 0.27), so there is nothing to send.
 *
 * **UNVERIFIED AGAINST A REAL RADIO** — exercised only against a loopback fake in MeshCoreLinkTest.
 */
class MeshCoreLink(
    private val host: String,
    private val port: Int = TCP_PORT,
    /** Each OMP DATA frame another sender put on "#oshi-bridge", on the read thread. */
    private val onOmpFrame: (ByteArray, OshiMeshProtocol.DataFrame) -> Unit,
    private val log: (String) -> Unit = {},
    private val dial: (String, Int, Int) -> Socket = { h, p, timeoutMs ->
        Socket().apply { connect(InetSocketAddress(h, p), timeoutMs) }
    },
    private val connectTimeoutMs: Int = 8_000,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private var thread: Thread? = null
    private val session = MeshCoreSession()
    @Volatile private var awaitingSince = 0L

    val isConnected: Boolean get() = connected.get()
    /** Connected, with "#oshi-bridge" found or created. */
    val isReady: Boolean get() = connected.get() && session.isReady
    val channelIdx: Int? get() = session.channelIdx

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ runLoop() }, "oshi-meshcore-$host").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        closeQuietly()
        thread?.interrupt()
    }

    private fun runLoop() {
        var backoffMs = LoRaLink.INITIAL_BACKOFF_MS
        while (running.get()) {
            try {
                val s = dial(host, port, connectTimeoutMs)
                s.tcpNoDelay = true
                s.soTimeout = POLL_MS
                socket = s
                out = s.getOutputStream()
                connected.set(true)
                backoffMs = LoRaLink.INITIAL_BACKOFF_MS
                log("meshcore: attached to $host:$port")
                session.start()
                pump()
                read(s.getInputStream())
            } catch (e: Exception) {
                if (running.get()) log("meshcore: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                connected.set(false)
                session.reset()
                closeQuietly()
            }
            if (!running.get()) break
            sleeper(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(LoRaLink.MAX_BACKOFF_MS)
        }
    }

    /** Write the next companion request if none is waiting for its reply. */
    @Synchronized
    private fun pump() {
        val o = out ?: return
        val cmd = session.nextCommand() ?: return
        awaitingSince = System.currentTimeMillis()
        try {
            o.write(MeshCore.Serial.encode(cmd)); o.flush()
        } catch (e: IOException) {
            session.writeFailed()
            throw e
        }
    }

    private fun read(input: InputStream) {
        var buffer = ByteArray(0)
        val chunk = ByteArray(1024)
        while (running.get()) {
            val n = try {
                input.read(chunk)
            } catch (_: SocketTimeoutException) {
                if (awaitingSince != 0L && System.currentTimeMillis() - awaitingSince > REPLY_TIMEOUT_MS) {
                    log("meshcore: a request went unanswered; moving on")
                    awaitingSince = 0L
                    session.writeFailed()
                    pump()
                }
                continue
            }
            if (n < 0) throw IOException("radio closed the stream")
            buffer += chunk.copyOfRange(0, n)
            while (true) {
                val d = MeshCore.Serial.decode(buffer) ?: break
                buffer = buffer.copyOfRange(d.consumed, buffer.size)
                handle(d.payload)
            }
            if (buffer.size > 2 * MeshCore.Serial.MAX_FRAME_BYTES) {
                buffer = buffer.copyOfRange(buffer.size - MeshCore.Serial.MAX_FRAME_BYTES, buffer.size)
            }
        }
    }

    private fun handle(frame: ByteArray) {
        if (frame.isNotEmpty() && (frame[0].toInt() and 0xFF) < 0x80) awaitingSince = 0L
        for (e in session.onFrame(frame)) {
            when (e) {
                is MeshCoreSession.Event.Ready -> log("meshcore: ready, #oshi-bridge in slot ${session.channelIdx}")
                is MeshCoreSession.Event.NoFreeChannel ->
                    log("meshcore: every channel slot is in use; free one so OSHI can add #oshi-bridge")
                is MeshCoreSession.Event.Frame -> try {
                    onOmpFrame(e.frame, e.parsed)
                } catch (x: Exception) {
                    log("meshcore: handler threw on a frame: ${x.message}")
                }
            }
        }
        pump()
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
    }

    companion object {
        /** MeshCore companion_radio's TCP_PORT for WiFi builds. */
        const val TCP_PORT = 5000
        const val REPLY_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 1_000
    }
}
