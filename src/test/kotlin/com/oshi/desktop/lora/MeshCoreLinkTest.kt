package com.oshi.desktop.lora

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MeshCore LINK against a loopback radio that speaks the companion protocol's serial framing. Proves the
 * framing both ways, the one-request-at-a-time setup ending on "#oshi-bridge", and that a frame another
 * phone put on the channel reaches the callback. **Says nothing about a real radio.**
 */
class MeshCoreLinkTest {

    /** Channel 0 "Public", channel 1 "#oshi-bridge"; one waiting OSHI datagram after the first sync. */
    private class FakeMeshCoreRadio(private val datagram: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val received = CopyOnWriteArrayList<ByteArray>()
        val port: Int get() = server.localPort
        @Volatile private var delivered = false

        init {
            Thread({ serve() }, "fake-meshcore").apply { isDaemon = true }.start()
        }

        private fun serve() {
            val s = try { server.accept() } catch (_: Exception) { return }
            val out = s.getOutputStream()
            fun reply(f: ByteArray) { out.write(MeshCore.Serial.encode(f, MeshCore.Serial.FROM_RADIO)); out.flush() }
            val input: InputStream = s.getInputStream()
            var buf = ByteArray(0)
            val chunk = ByteArray(512)
            while (true) {
                val n = try { input.read(chunk) } catch (_: Exception) { break }
                if (n < 0) break
                buf += chunk.copyOfRange(0, n)
                while (true) {
                    val d = MeshCore.Serial.decode(buf, MeshCore.Serial.TO_RADIO) ?: break
                    buf = buf.copyOfRange(d.consumed, buf.size)
                    val cmd = d.payload
                    received.add(cmd)
                    when (cmd[0].toInt() and 0xFF) {
                        MeshCore.CMD_APP_START ->
                            reply(byteArrayOf(5, 1, 22, 22, 0x44, 0x33, 0x22, 0x11) + ByteArray(48))
                        MeshCore.CMD_GET_CHANNEL -> {
                            val idx = cmd[1].toInt()
                            val (name, secret) = if (idx == 1) OshiBridgeWire.CHANNEL_NAME to OshiBridgeWire.CHANNEL_SECRET
                                else "Public" to ByteArray(16) { 7 }
                            val nb = name.toByteArray()
                            reply(byteArrayOf(18, idx.toByte()) + nb + ByteArray(32 - nb.size) + secret)
                        }
                        MeshCore.CMD_SYNC_NEXT_MESSAGE -> if (!delivered) {
                            delivered = true
                            reply(byteArrayOf(27, 0x18, 0, 0, 1, 0, 0x4F, 0xFF.toByte(), datagram.size.toByte()) + datagram)
                        } else reply(byteArrayOf(10))
                        else -> reply(byteArrayOf(0))
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private val closers = ArrayList<AutoCloseable>()

    @After
    fun tearDown() = closers.forEach { runCatching { it.close() } }

    @Test
    fun `setup finds the bridge channel and a foreign frame reaches the callback`() {
        val frame = OshiMeshProtocol.dataFrames(42u, CrossBridgeDedup.BROADCAST, "hello".toByteArray(), origin = 9u)!![0]
        val radio = FakeMeshCoreRadio(OshiBridgeWire.split(frame, 9u, 3)!!.single()).also { closers.add(it) }
        val got = CopyOnWriteArrayList<OshiMeshProtocol.DataFrame>()
        val arrived = CountDownLatch(1)
        val link = MeshCoreLink("127.0.0.1", radio.port, onOmpFrame = { _, f -> got.add(f); arrived.countDown() })
        closers.add(AutoCloseable { link.stop() })
        link.start()

        assertTrue("no OSHI frame came off the fake radio", arrived.await(10, TimeUnit.SECONDS))
        assertEquals(9u, got.single().origin)
        assertEquals(42u, got.single().msgId)
        assertTrue(link.isReady)
        assertEquals(1, link.channelIdx)
        // The conversation, in order: introduce ourselves, inspect slots 0 and 1, then collect waiting messages.
        assertArrayEquals(MeshCore.appStart().copyOfRange(0, 8), radio.received[0].copyOfRange(0, 8))
        assertArrayEquals(MeshCore.getChannel(0), radio.received[1])
        assertArrayEquals(MeshCore.getChannel(1), radio.received[2])
        assertArrayEquals(MeshCore.syncNextMessage, radio.received[3])
        // A channel the owner configured is never rewritten.
        assertTrue(radio.received.none { (it[0].toInt() and 0xFF) == MeshCore.CMD_SET_CHANNEL })
    }
}
