package com.oshi.desktop.mesh

import com.oshi.desktop.group.PublicGroupDirectory
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** __DESKTOP_GROUP_DISCOVERY_2027__ a PUBLIC_GROUP_AD crosses a real loopback link. */
class MeshBroadcastTest {
    private val nodes = mutableListOf<MeshNode>()

    @After fun tearDown() { nodes.forEach { it.stop() } }

    private fun node(name: String, key: String) =
        MeshNode(key, name, log = {}).also { nodes.add(it); it.start(enableDiscovery = false) }

    @Test
    fun `a broadcast reaches every direct peer with an empty recipient, and the ad parses`() {
        val a = node("A", "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=")
        val b = node("B", "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=")
        val got = LinkedBlockingQueue<MeshMessage>()
        b.onMessage = { got.add(it) }
        a.connectToPeer("127.0.0.1", b.listenPort, "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=")
        val deadline = System.currentTimeMillis() + 10_000
        while (!a.isConnectedTo("QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=") && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val payload = """{"groupId":"6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b","groupName":"Hall","adminPublicKey":"QUFB","memberCount":3,"avatar":null,"timestamp":816000000}"""
        assertEquals(1, a.broadcast(payload, PublicGroupDirectory.TYPE))
        val m = got.poll(10, TimeUnit.SECONDS) ?: error("B heard nothing")
        assertEquals(PublicGroupDirectory.TYPE, m.type)
        assertEquals("", m.recipientPublicKey)
        assertEquals(MeshNode.BROADCAST_MAX_HOPS, m.maxHops)
        assertTrue(PublicGroupDirectory().offer(m.payload, m.senderPublicKey, 0) != null)
    }
}
