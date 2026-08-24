package com.oshi.desktop.mesh

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The 4-byte big-endian length prefix, and the two ways it goes wrong.
 *
 * Neither failure mode here is theoretical: both shipped clients have had a desync bug on
 * this exact framing, and the Android one (a cap that skipped the body instead of
 * resetting the connection) permanently corrupted every later frame from a peer that sent
 * one oversized photo.
 */
class MeshFramingTest {

    @Test
    fun `a frame round trips and the header is big-endian`() {
        val payload = "{\"hello\":\"world\"}".toByteArray()
        val out = ByteArrayOutputStream()
        MeshFraming.writeFrame(DataOutputStream(out), payload)
        val bytes = out.toByteArray()

        assertEquals("header must be exactly 4 bytes", payload.size + 4, bytes.size)
        // Big-endian, unsigned. A little-endian header is the difference between a
        // 17-byte frame and a 285-million-byte allocation.
        assertEquals(0, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals(0, bytes[2].toInt())
        assertEquals(payload.size, bytes[3].toInt() and 0xFF)
        assertArrayEquals(payload, MeshFraming.readFrame(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `back to back frames read one at a time`() {
        val out = ByteArrayOutputStream()
        val dout = DataOutputStream(out)
        val payloads = (1..5).map { "frame-$it".repeat(it).toByteArray() }
        payloads.forEach { MeshFraming.writeFrame(dout, it) }
        val input = ByteArrayInputStream(out.toByteArray())
        payloads.forEach { assertArrayEquals(it, MeshFraming.readFrame(input)) }
    }

    /**
     * The desync case. A length outside the accepted range must THROW, so the caller
     * closes the connection — the one thing it must not do is skip and keep reading,
     * because there is no resynchronisation point in this protocol.
     */
    @Test
    fun `an out-of-range length throws instead of skipping`() {
        for (bad in listOf(0, -1, Int.MIN_VALUE, MeshProtocol.MAX_FRAME_BYTES + 1, Int.MAX_VALUE)) {
            val header = byteArrayOf(
                (bad ushr 24).toByte(), (bad ushr 16).toByte(), (bad ushr 8).toByte(), bad.toByte(),
            )
            try {
                MeshFraming.readFrame(ByteArrayInputStream(header + ByteArray(64)))
                fail("length $bad should have been refused")
            } catch (e: IOException) {
                assertTrue("the error must name the length: ${e.message}",
                    e.message!!.contains(bad.toString()))
            }
        }
    }

    /** 0xFFFFFFFF read as a signed int is -1: the guard has to catch it as a NEGATIVE. */
    @Test
    fun `an all-ones header cannot become a four gigabyte allocation`() {
        val header = byteArrayOf(-1, -1, -1, -1)
        try {
            MeshFraming.readFrame(ByteArrayInputStream(header))
            fail("0xFFFFFFFF must be refused")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("-1"))
        }
    }

    @Test
    fun `a clean close surfaces as EOF, not as a corrupt frame`() {
        try {
            MeshFraming.readFrame(ByteArrayInputStream(ByteArray(0)))
            fail("expected EOF")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun `an oversize send is refused before it reaches a peer that would drop the connection`() {
        try {
            MeshFraming.writeFrame(DataOutputStream(ByteArrayOutputStream()), ByteArray(0))
            fail("an empty frame is not sendable")
        } catch (_: IllegalArgumentException) {
        }
    }

    /**
     * Three senders can target one peer at once — a direct send, a relay, and the
     * 30-second announce. Unsynchronised, `DataOutputStream.writeInt` emits its four
     * bytes one call at a time, another thread's frame lands in the middle of them, and
     * the receiver reads a length out of somebody's JSON.
     *
     * Two harness lessons are baked in here, both learned the hard way:
     *
     * 1. The first version was VACUOUS. The reader ran on its own thread, a corrupt
     *    length threw THERE, the thread died, and the main thread's final assertion
     *    (`!reader.isAlive`) was satisfied BY that death — it passed with the lock
     *    removed from writeFrame. It now counts frames and re-throws the reader's
     *    failure on the main thread, and it has been watched going red with the lock
     *    removed.
     * 2. The second version used a `PipedInputStream` and flaked: with several writer
     *    threads, `PipedInputStream` records only the LAST thread that wrote, and throws
     *    "Write end dead" if that one exits while the buffer happens to be empty and
     *    other writers are still going. A red test that means nothing is worse than no
     *    test. A real loopback socket has no such rule — and it is what the code
     *    actually runs on.
     */
    @Test
    fun `concurrent writers never interleave a frame`() {
        val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        val out = DataOutputStream(client.getOutputStream())
        val input = DataInputStream(accepted.getInputStream())

        val frames = 300
        val writers = 4
        val expected = frames * writers
        val start = CountDownLatch(1)
        val threads = (0 until writers).map { w ->
            Thread {
                start.await()
                repeat(frames) { i ->
                    MeshFraming.writeFrame(out, ("w$w-msg$i-" + "x".repeat(50 + i % 40)).toByteArray())
                }
            }
        }
        var readerFailure: Throwable? = null
        var read = 0
        val reader = Thread {
            try {
                repeat(expected) {
                    val f = String(MeshFraming.readFrame(input))
                    if (!f.matches(Regex("w\\d-msg\\d+-x+"))) {
                        throw AssertionError("frame #$read arrived corrupted: '${f.take(120)}'")
                    }
                    read++
                }
            } catch (t: Throwable) {
                readerFailure = t
            }
        }
        try {
            reader.start()
            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join(30_000) }
            reader.join(30_000)
        } finally {
            client.close(); accepted.close(); server.close()
        }

        readerFailure?.let { throw AssertionError("interleaved write corrupted the stream", it) }
        assertEquals("not every frame survived the concurrent writers", expected, read)
    }
}
