package com.oshi.desktop.lora

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __MESHCORE_INTEROP_DESKTOP_2026_11_25__ The desktop MeshCore LINK (thread, framing, pump, tick) against a
 * loopback radio that speaks the companion protocol with the USB/TCP '<' / '>' framing. Proves: the setup
 * ends ready with the radio's contacts and channels, a waiting DM reaches [MeshCoreLink.onInbound] under the
 * contact's key, a DM goes SENT → DELIVERED on the radio's ACK push, a long DM leaves as "(1/2)" "(2/2)"
 * at least [MeshCoreSession.PART_GAP_MS] apart, channel text is SENT, a join lands in a free slot, an OSHI
 * frame on "#oshi-bridge" reaches its callback, and [RadioFirmwareProbe] tells the two firmwares apart.
 * The same scenarios ran against two real T1000-E radios (see the commit message); this test says
 * nothing about a real radio by itself.
 */
class MeshCoreLinkTest {

    /** A MeshCore companion radio: slot 0 Public, slot 1 #oshi-bridge, one contact (Bob), one waiting DM. */
    class FakeMeshCoreRadio(private val waiting: List<ByteArray> = emptyList()) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val received = CopyOnWriteArrayList<ByteArray>()
        /** When each command arrived (ms), parallel to [received]. */
        val receivedAt = CopyOnWriteArrayList<Long>()
        val port: Int get() = server.localPort
        @Volatile var out: OutputStream? = null
        private val queue = ArrayDeque(waiting)
        private val slots = HashMap<Int, Pair<String, ByteArray>>().apply {
            put(0, MeshCore.PUBLIC_CHANNEL_NAME to MeshCore.PUBLIC_CHANNEL_SECRET)
            put(1, OshiBridgeWire.CHANNEL_NAME to OshiBridgeWire.CHANNEL_SECRET)
        }
        private var tag = 0x1000L
        /** Acknowledge every DM part at once (a recipient in range). */
        @Volatile var ackDirect = true

        init { Thread({ serve() }, "fake-meshcore").apply { isDaemon = true }.start() }

        @Synchronized
        fun reply(f: ByteArray) { out?.let { it.write(MeshCore.Serial.encode(f, MeshCore.Serial.FROM_RADIO)); it.flush() } }

        private fun serve() {
            val s: Socket = try { server.accept() } catch (_: Exception) { return }
            out = s.getOutputStream()
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
                    handle(d.payload)
                }
            }
        }

        private fun handle(cmd: ByteArray) {
            received.add(cmd); receivedAt.add(System.currentTimeMillis())
            when (cmd[0].toInt() and 0xFF) {
                MeshCore.CMD_APP_START -> reply(selfInfo("Desk-Radio"))
                MeshCore.CMD_GET_CHANNEL -> {
                    val idx = cmd[1].toInt()
                    val (name, secret) = slots[idx] ?: ("" to ByteArray(16))
                    val nb = name.toByteArray()
                    reply(byteArrayOf(18, idx.toByte()) + nb + ByteArray(32 - nb.size) + secret)
                }
                MeshCore.CMD_SET_CHANNEL -> {
                    val idx = cmd[1].toInt()
                    var end = 2; while (end < 34 && cmd[end].toInt() != 0) end++
                    slots[idx] = String(cmd, 2, end - 2) to cmd.copyOfRange(34, 50)
                    reply(byteArrayOf(0))
                }
                MeshCore.CMD_GET_CONTACTS -> {
                    reply(byteArrayOf(2, 1, 0, 0, 0))
                    reply(contact(BOB, "Bob"))
                    reply(byteArrayOf(4, 0, 0, 0, 0))
                }
                MeshCore.CMD_SYNC_NEXT_MESSAGE -> reply(queue.removeFirstOrNull() ?: byteArrayOf(10))
                MeshCore.CMD_SEND_TXT_MSG -> {
                    val t = tag++
                    reply(byteArrayOf(6, 0) + le32(t) + le32(3000))
                    if (ackDirect) reply(byteArrayOf(0x82.toByte()) + le32(t) + le32(400))
                }
                else -> reply(byteArrayOf(0))
            }
        }

        override fun close() = server.close()
    }

    private val closers = ArrayList<AutoCloseable>()

    @After
    fun tearDown() = closers.forEach { runCatching { it.close() } }

    private fun link(radio: FakeMeshCoreRadio): MeshCoreLink =
        MeshCoreLink(LoRaChannel.Tcp("127.0.0.1", radio.port)).also { closers.add(AutoCloseable { it.stop() }) }

    private fun waitFor(ms: Long = 10_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun `setup reads contacts and channels and a waiting DM arrives under the contact's key`() {
        val dm = byteArrayOf(7) + BOB.copyOf(6) + byteArrayOf(0, 0) + le32(1_764_000_000) + "hello desk".toByteArray()
        val radio = FakeMeshCoreRadio(listOf(dm)).also { closers.add(it) }
        val link = link(radio)
        val inbound = CopyOnWriteArrayList<MeshCoreLink.Inbound>()
        link.onInbound = { inbound.add(it) }
        link.start()

        waitFor(what = "ready") { link.isReady }
        waitFor(what = "the waiting DM") { inbound.isNotEmpty() }
        assertEquals("Desk-Radio", link.selfName)
        assertEquals(1, link.bridgeSlot)
        assertEquals(listOf("Public"), link.userChannels.map { it.name })
        assertEquals(listOf("Bob"), link.contacts.map { it.name })
        assertEquals(MeshCoreThread.contactKey(BOB), inbound.single().conversationKey)
        assertEquals("hello desk", inbound.single().text)
        assertEquals(null, inbound.single().channelKey)
        assertNotNull(link.selfPublicKey)
        // A channel the owner configured is never rewritten.
        assertTrue(radio.received.none { (it[0].toInt() and 0xFF) == MeshCore.CMD_SET_CHANNEL })
    }

    @Test
    fun `a DM is SENT then DELIVERED, and a long one leaves as numbered parts paced apart`() {
        val radio = FakeMeshCoreRadio().also { closers.add(it) }
        val link = link(radio)
        val states = CopyOnWriteArrayList<Pair<String, MeshCoreSession.SendState>>()
        link.onSendState = { id, st -> states.add(id to st) }
        link.start()
        waitFor(what = "ready") { link.isReady }
        waitFor(what = "contacts") { link.contacts.isNotEmpty() }

        assertTrue(link.sendDirectText("m1", MeshCoreThread.contactKey(BOB), "short"))
        waitFor(what = "delivered") { "m1" to MeshCoreSession.SendState.DELIVERED in states }
        assertEquals(listOf(MeshCoreSession.SendState.SENT, MeshCoreSession.SendState.DELIVERED), states.filter { it.first == "m1" }.map { it.second })

        val long = "word ".repeat(50).trim()   // 249 characters: two radio messages
        assertTrue(link.sendDirectText("m2", MeshCoreThread.contactKey(BOB), long))
        waitFor(what = "long delivered") { "m2" to MeshCoreSession.SendState.DELIVERED in states }
        val parts = radio.received.withIndex().filter { (it.value[0].toInt() and 0xFF) == MeshCore.CMD_SEND_TXT_MSG }
            .map { it.index }.drop(1)
        assertEquals(2, parts.size)
        val texts = parts.map { String(radio.received[it], 13, radio.received[it].size - 13) }
        assertTrue(texts[0], texts[0].startsWith("(1/2) "))
        assertTrue(texts[1], texts[1].startsWith("(2/2) "))
        val gap = radio.receivedAt[parts[1]] - radio.receivedAt[parts[0]]
        assertTrue("parts went $gap ms apart", gap >= MeshCoreSession.PART_GAP_MS - 50)
    }

    @Test
    fun `channel text is SENT, a hashtag join lands in a free slot, an OSHI frame reaches its callback`() {
        val frame = OshiMeshProtocol.dataFrames(42u, CrossBridgeDedup.BROADCAST, "hello".toByteArray(), origin = 9u)!![0]
        val dg = OshiBridgeWire.split(frame, 9u, 3)!!.single()
        val bridgeData = byteArrayOf(27, 0x18, 0, 0, 1, 0, 0x4F, 0xFF.toByte(), dg.size.toByte()) + dg
        val radio = FakeMeshCoreRadio(listOf(bridgeData)).also { closers.add(it) }
        val link = link(radio)
        val states = CopyOnWriteArrayList<Pair<String, MeshCoreSession.SendState>>()
        val omp = CountDownLatch(1)
        val joins = CopyOnWriteArrayList<Pair<Int, Boolean>>()
        link.onSendState = { id, st -> states.add(id to st) }
        link.onOmpFrame = { if (it.parsed.msgId == 42u) omp.countDown() }
        link.onJoin = { slot, ok -> joins.add(slot to ok) }
        link.start()
        waitFor(what = "ready") { link.isReady }
        assertTrue("no OSHI frame", omp.await(10, TimeUnit.SECONDS))

        assertTrue(link.sendChannelText("c1", MeshCoreThread.channelKey("Public"), "hi all"))
        waitFor(what = "channel sent") { "c1" to MeshCoreSession.SendState.SENT in states }

        assertEquals(MeshCoreLink.JoinResult.QUEUED, link.joinHashtagChannel("desk-test"))
        waitFor(what = "join") { joins.isNotEmpty() }
        assertEquals(2 to true, joins.single())
        assertTrue(link.userChannels.any { it.name == "#desk-test" && it.secret.contentEquals(MeshCore.hashtagSecret("#desk-test")) })
        assertEquals(MeshCoreLink.JoinResult.ALREADY_JOINED, link.joinHashtagChannel("#desk-test"))
        assertEquals(MeshCoreLink.JoinResult.INVALID, link.joinHashtagChannel("two words"))
    }

    @Test
    fun `the probe tells MeshCore from Meshtastic from silence`() {
        val mc = FakeMeshCoreRadio().also { closers.add(it) }
        assertEquals(RadioFirmwareProbe.Firmware.MESHCORE,
            RadioFirmwareProbe.detect(LoRaChannel.Tcp("127.0.0.1", mc.port), meshCoreWaitMs = 2_000, meshtasticWaitMs = 500))

        val mt = FakeMeshtasticNode().also { closers.add(it) }
        Thread {
            if (mt.handshake.await(10, TimeUnit.SECONDS)) mt.push(ProtoWriter().varint(1, 7).data)
        }.apply { isDaemon = true }.start()
        assertEquals(RadioFirmwareProbe.Firmware.MESHTASTIC,
            RadioFirmwareProbe.detect(LoRaChannel.Tcp("127.0.0.1", mt.port), meshCoreWaitMs = 300, meshtasticWaitMs = 3_000))

        val silent = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).also { closers.add(it) }
        Thread { runCatching { silent.accept().getInputStream().readAllBytes() } }.apply { isDaemon = true }.start()
        assertEquals(RadioFirmwareProbe.Firmware.UNKNOWN,
            RadioFirmwareProbe.detect(LoRaChannel.Tcp("127.0.0.1", silent.localPort), meshCoreWaitMs = 200, meshtasticWaitMs = 200))

        val closedPort = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        assertEquals(RadioFirmwareProbe.Firmware.UNREACHABLE,
            RadioFirmwareProbe.detect(LoRaChannel.Tcp("127.0.0.1", closedPort, connectTimeoutMs = 500), meshCoreWaitMs = 100, meshtasticWaitMs = 100))
    }

    @Test
    fun `a radio that never answers is reported as not a MeshCore companion`() {
        val silent = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).also { closers.add(it) }
        Thread { runCatching { silent.accept().getInputStream().readAllBytes() } }.apply { isDaemon = true }.start()
        val link = MeshCoreLink(LoRaChannel.Tcp("127.0.0.1", silent.localPort)).also { closers.add(AutoCloseable { it.stop() }) }
        link.start()
        waitFor(ms = MeshCoreLink.NO_ANSWER_MS + 4_000, what = "NO_ANSWER") { link.problem == MeshCoreLink.Problem.NO_ANSWER }
        assertTrue(!link.isReady)
    }

    companion object {
        val BOB = ByteArray(32) { (0xB0 + it).toByte() }

        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

        fun selfInfo(name: String): ByteArray {
            val key = ByteArray(32) { (0x40 + it).toByte() }
            // code, adv type, tx power, max tx, key (32), then radio params up to offset 58, then the name.
            val head = byteArrayOf(5, 1, 22, 22) + key
            return head + ByteArray(58 - head.size) + name.toByteArray()
        }

        fun contact(key: ByteArray, name: String): ByteArray {
            val nb = name.toByteArray()
            return byteArrayOf(3) + key + byteArrayOf(MeshCore.ADV_TYPE_CHAT.toByte(), 0, 0xFF.toByte()) + ByteArray(64) +
                nb + ByteArray(32 - nb.size) + le32(1_764_000_000) + ByteArray(8) + le32(1_764_000_100)
        }
    }
}
