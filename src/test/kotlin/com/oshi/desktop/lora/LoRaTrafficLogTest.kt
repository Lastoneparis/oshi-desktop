package com.oshi.desktop.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_LORA_TRAFFIC_2026_10_29__ The live traffic log iOS has and this client did not.
 *
 * The tests that matter here are the two RULES, not the formatting: an entry must never
 * carry a payload, and the buffer must never grow without bound. Everything else is a
 * convenience.
 */
class LoRaTrafficLogTest {

    private fun meshPacket(to: UInt, portnum: Long, payload: ByteArray, outbound: Boolean): ByteArray {
        val data = ProtoWriter().varint(1, portnum).bytes(2, payload).data
        val pkt = ProtoWriter()
            .fixed32(if (outbound) 2 else 1, to)   // to on the way out, from on the way in
            .bytes(4, data)
            .fixed32(6, 0x11223344u)
            .data
        return ProtoWriter().bytes(if (outbound) 1 else 2, pkt).data
    }

    // ------------------------------------------------------------------ THE PRIVACY RULE

    @Test
    fun `an entry never carries the payload`() {
        // The rule this class exists under. An OSHI envelope is ciphertext whose point is
        // that this process is the only place it is readable; copying it into a UI buffer
        // that a screenshot or a support bundle can pick up undoes that for no diagnostic
        // gain. A byte count answers every question the log is for.
        val secret = "ATTACK AT DAWN, the plans are in the barn".toByteArray()
        val log = LoRaTrafficLog()
        log.observe("→", meshPacket(LoRaProto.BROADCAST_ADDR, LoRaProto.PORT_TEXT, secret, outbound = true))
        val e = log.recent().single()
        assertEquals(secret.size, e.bytes)
        assertFalse("the summary must not contain the payload", e.summary.contains("ATTACK"))
        assertFalse(e.summary.contains("barn"))
        // And nothing in the entry at all holds it — checked field by field rather than
        // trusting the summary, because a later field would be rendered somewhere.
        val everything = listOf(e.direction, e.portnum?.toString(), e.node, e.bytes.toString(), e.summary)
            .filterNotNull().joinToString("|")
        assertFalse(everything.contains("ATTACK"))
    }

    // ------------------------------------------------------------------ THE BOUND

    @Test
    fun `the buffer is bounded and keeps the newest`() {
        // A radio on a busy mesh delivers tens of frames a minute, and this process runs
        // for days. Unbounded is a slow leak.
        val log = LoRaTrafficLog(capacity = 5)
        for (i in 1..20) log.record("←", LoRaProto.PORT_POSITION, bytes = i)
        val recent = log.recent()
        assertEquals(5, recent.size)
        assertEquals("newest first", 20, recent.first().bytes)
        assertEquals("oldest kept is the 16th", 16, recent.last().bytes)
    }

    // ------------------------------------------------------------------ reading a frame

    @Test
    fun `an outbound packet reports its portnum, size and destination`() {
        val log = LoRaTrafficLog()
        assertTrue(log.observe("→", meshPacket(LoRaProto.BROADCAST_ADDR, LoRaProto.PORT_POSITION, ByteArray(16), outbound = true)))
        val e = log.recent().single()
        assertEquals("→", e.direction)
        assertEquals(LoRaProto.PORT_POSITION, e.portnum)
        assertEquals(16, e.bytes)
        assertEquals("ALL", e.node)
        assertTrue(e.summary.contains("POSITION"))
    }

    @Test
    fun `an inbound packet names the sender, not the recipient`() {
        val log = LoRaTrafficLog()
        log.observe("←", meshPacket(0x766eb396u, LoRaProto.PORT_TEXT, ByteArray(7), outbound = false))
        val e = log.recent().single()
        assertEquals("←", e.direction)
        assertEquals("!766eb396", e.node)
        assertEquals(7, e.bytes)
    }

    @Test
    fun `a config burst is NOT traffic`() {
        // The handshake delivers a flood of config, my_info and node_info. Recording those
        // would bury the handful of frames somebody is actually looking for.
        val log = LoRaTrafficLog()
        val config = ProtoWriter().bytes(5, ProtoWriter().varint(1, 1).data).data   // FromRadio.config
        val myInfo = ProtoWriter().bytes(3, ProtoWriter().varint(1, 42).data).data  // FromRadio.my_info
        assertFalse(log.observe("←", config))
        assertFalse(log.observe("←", myInfo))
        assertTrue(log.recent().isEmpty())
    }

    @Test
    fun `portnums are named the way every other client names them`() {
        // So a line here can be matched against a capture from the official app.
        assertEquals("TEXT", LoRaTrafficLog.portName(1))
        assertEquals("POSITION", LoRaTrafficLog.portName(3))
        assertEquals("NODEINFO", LoRaTrafficLog.portName(4))
        assertEquals("ADMIN", LoRaTrafficLog.portName(6))
        assertEquals("OSHI", LoRaTrafficLog.portName(LoRaProto.PORT_OSHI))
        assertEquals("—", LoRaTrafficLog.portName(null))
        assertEquals("port 99", LoRaTrafficLog.portName(99))
    }

    @Test
    fun `junk is dropped rather than crashing the receive thread`() {
        val log = LoRaTrafficLog()
        assertFalse(log.observe("←", ByteArray(0)))
        assertFalse(log.observe("←", byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
        assertTrue(log.recent().isEmpty())
    }
}
