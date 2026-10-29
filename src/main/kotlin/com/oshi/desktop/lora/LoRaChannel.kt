package com.oshi.desktop.lora

import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * How [LoRaLink] reaches a radio — the one thing that differs between a socket and a
 * plugged-in board, factored out so everything that was hard to get right is shared.
 *
 * The handshake order, the buffer that outlives the read, the drain loop, the desync
 * resynchronisation and the reconnect backoff are all transport-independent and all
 * already tested against `FakeMeshtasticNode`. A second copy of them for serial would be a
 * second place for the same four bugs, so [LoRaLink] keeps them and takes a channel.
 *
 * A channel is a FACTORY, not a connection: [open] is called again on every reconnect.
 */
interface LoRaChannel {

    /** For logs and the read thread's name. Must not contain anything secret. */
    val label: String

    /** Open a connection, or throw. Never returns null — a failure is the exception. */
    fun open(): Conn

    class Conn(
        val input: InputStream,
        val output: OutputStream,
        private val closer: () -> Unit,
    ) {
        fun close() = closer()
    }

    /**
     * TCP :4403 — a WiFi-capable board, `meshtasticd`, or a phone bridging.
     *
     * `dial` stays injectable for the same reason it was a constructor parameter before:
     * the tests drive a loopback `FakeMeshtasticNode` and must not open a real socket.
     */
    class Tcp(
        private val host: String,
        private val port: Int = LoRaAttach.TCP_PORT,
        private val connectTimeoutMs: Int = 8_000,
        private val dial: (String, Int, Int) -> Socket = { h, p, t ->
            Socket().apply { connect(InetSocketAddress(h, p), t) }
        },
    ) : LoRaChannel {

        override val label: String get() = "$host:$port"

        override fun open(): Conn {
            val s = dial(host, port, connectTimeoutMs)
            s.tcpNoDelay = true
            return Conn(s.getInputStream(), s.getOutputStream()) { try { s.close() } catch (_: Exception) {} }
        }
    }

    /**
     * A USB serial device — the transport a person expects when the radio is on the desk.
     *
     * **One descriptor, not two.** The device is opened once through [RandomAccessFile] in
     * `"rw"` mode and both streams are built on that single `FileDescriptor`. Opening the
     * path twice would be two `open(2)` calls on one tty: on macOS the second can fail
     * outright, and where it succeeds the two descriptors have independent termios state,
     * so configuring one leaves the other cooked.
     *
     * **Configure after opening.** See [LoRaSerial]: a macOS cu device reverts to its
     * initial termios when the last descriptor closes, so `stty` must run while ours is
     * open. If it fails the open is ABORTED rather than continued on a cooked tty, because
     * a cooked tty does not fail loudly — it eats 0x11, rewrites 0x0D and echoes our own
     * frames back at the parser, which reads as a radio talking nonsense.
     */
    class Serial(
        private val path: String,
        private val baud: Int = LoRaSerial.DEFAULT_BAUD,
        /** Injectable so a test can assert the argv and the abort-on-failure rule. */
        private val configure: (String, Int) -> Boolean = ::sttyConfigure,
    ) : LoRaChannel {

        override val label: String get() = path

        override fun open(): Conn {
            if (!LoRaSerial.isSupported()) throw IOException("serial attach is POSIX-only on this build")
            val raf = RandomAccessFile(path, "rw")
            val closer = { try { raf.close() } catch (_: Exception) {} }
            try {
                if (!configure(path, baud)) throw IOException("stty failed on $path")
            } catch (e: Exception) {
                closer()
                throw if (e is IOException) e else IOException("serial configure failed: ${e.message}", e)
            }
            return Conn(FileInputStream(raf.fd), FileOutputStream(raf.fd), closer)
        }
    }

    companion object {

        /**
         * Run `stty` against the device. Returns false on a non-zero exit or a timeout.
         *
         * `exec`-style argv, never a shell string: the path has already been through
         * [LoRaSerial.devicePath], and passing a list keeps that guarantee independent of
         * how strict that check stays. The 5 s cap exists because `stty` on a device that
         * has just been unplugged can block in the kernel.
         */
        fun sttyConfigure(path: String, baud: Int): Boolean {
            val argv = LoRaSerial.sttyCommand(path, baud)
            val p = ProcessBuilder(argv).redirectErrorStream(true).start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return false }
            return p.exitValue() == 0
        }
    }
}
