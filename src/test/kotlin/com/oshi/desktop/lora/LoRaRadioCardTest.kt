package com.oshi.desktop.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * __DESKTOP_TOOLS_2027__ The radio card's numbers come from the wire, so the wire is what is
 * tested: `NodeInfo.device_metrics` (field 6), `TELEMETRY_APP` (portnum 67) and the
 * `rx_snr` / `rx_rssi` our radio stamps on every packet. Field numbers from mesh.proto and
 * telemetry.proto; a wrong one reads plausible garbage, which is why each is pinned here.
 */
class LoRaRadioCardTest {

    // ------------------------------------------------------------ a tiny protobuf writer
    private fun varint(out: ByteArrayOutputStream, v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); return }
            out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
        }
    }
    private fun tag(out: ByteArrayOutputStream, field: Int, wire: Int) = varint(out, ((field shl 3) or wire).toLong())
    private fun msg(build: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(build).toByteArray()
    private fun ByteArrayOutputStream.v(field: Int, value: Long) { tag(this, field, 0); varint(this, value) }
    private fun ByteArrayOutputStream.f32(field: Int, bits: Int) {
        tag(this, field, 5); for (i in 0 until 4) write((bits ushr (8 * i)) and 0xFF)
    }
    private fun ByteArrayOutputStream.fl(field: Int, value: Float) = f32(field, value.toRawBits())
    private fun ByteArrayOutputStream.bytes(field: Int, b: ByteArray) { tag(this, field, 2); varint(this, b.size.toLong()); write(b) }

    private val me = 0x766eb397L
    private val other = 0x4b88a86dL
    private val now = 1_800_000_000_000L

    private fun myInfo() = msg { bytes(3, msg { v(1, me) }) }

    private fun metrics(battery: Long?, voltage: Float?, chUtil: Float?, airTx: Float?, uptime: Long?) = msg {
        battery?.let { v(1, it) }; voltage?.let { fl(2, it) }; chUtil?.let { fl(3, it) }; airTx?.let { fl(4, it) }; uptime?.let { v(5, it) }
    }

    @Test
    fun `node info device metrics fill our radio's card`() {
        val roster = LoRaNodeRoster()
        roster.observeFromRadio(myInfo(), now)
        roster.observeFromRadio(msg { bytes(4, msg { v(1, me); bytes(6, metrics(76, 3.98f, 12.4f, 3.1f, 4_000)) }) }, now)
        val m = roster.myMetrics()!!
        assertEquals(76, m.batteryLevel)
        assertEquals(3.98f, m.voltage!!, 0.001f)
        assertEquals(12.4f, m.channelUtilization!!, 0.001f)
        assertEquals(3.1f, m.airUtilTx!!, 0.001f)
        assertEquals(4_000L, m.uptimeSeconds)
    }

    @Test
    fun `a later partial report keeps what it does not mention`() {
        val roster = LoRaNodeRoster()
        roster.observeFromRadio(myInfo(), now)
        roster.observeFromRadio(msg { bytes(4, msg { v(1, me); bytes(6, metrics(76, 3.98f, null, null, null)) }) }, now)
        // TELEMETRY_APP from our own node with only airtime: battery must survive.
        val telemetry = msg { f32(1, 1); bytes(2, metrics(null, null, 20f, 4.5f, null)) }
        val packet = msg { f32(1, me.toInt()); bytes(4, msg { v(1, 67); bytes(2, telemetry) }) }
        roster.observeFromRadio(msg { bytes(2, packet) }, now + 1000)
        val m = roster.myMetrics()!!
        assertEquals("battery kept", 76, m.batteryLevel)
        assertEquals(4.5f, m.airUtilTx!!, 0.001f)
        assertEquals(20f, m.channelUtilization!!, 0.001f)
    }

    @Test
    fun `the last packet from another node gives signal, ours does not`() {
        val roster = LoRaNodeRoster()
        roster.observeFromRadio(myInfo(), now)
        val fromOther = msg { f32(1, other.toInt()); fl(8, 6.25f); v(12, (-97L) and 0xFFFFFFFFFFFFFFFFUL.toLong()) }
        roster.observeFromRadio(msg { bytes(2, fromOther) }, now)
        val rx = roster.lastRx()!!
        assertEquals(-97, rx.rssi)
        assertEquals(6.25f, rx.snr!!, 0.001f)
        // Our own echo carries no signal reading that means anything.
        val fromMe = msg { f32(1, me.toInt()); fl(8, 11f); v(12, (-20L) and 0xFFFFFFFFFFFFFFFFUL.toLong()) }
        roster.observeFromRadio(msg { bytes(2, fromMe) }, now + 5)
        assertEquals("our own packet must not overwrite the last heard signal", -97, roster.lastRx()!!.rssi)
    }

    @Test
    fun `implausible values are dropped rather than shown`() {
        val roster = LoRaNodeRoster()
        roster.observeFromRadio(myInfo(), now)
        roster.observeFromRadio(msg { bytes(4, msg { v(1, me); bytes(6, metrics(250, 99f, 140f, -3f, null)) }) }, now)
        val m = roster.myMetrics()!!
        assertNull(m.batteryLevel); assertNull(m.voltage); assertNull(m.channelUtilization); assertNull(m.airUtilTx)
    }

    @Test
    fun `signal bars and airtime budget`() {
        fun card(snr: Float?, rssi: Int?, region: LoRaAdmin.Region?) = LoRaRadioCard(
            0u, null, 101, null, null, null, null, rssi, snr, null, region, null, null, false, false, 0,
        )
        assertEquals(4, card(6f, null, null).signalBars)
        assertEquals(1, card(-10f, null, null).signalBars)
        assertEquals(3, card(null, -100, null).signalBars)
        assertNull(card(null, null, null).signalBars)
        assertTrue(card(null, null, null).onExternalPower)
        assertEquals(10f, card(null, null, LoRaAdmin.Region.EU868).airtimeLimitPercent, 0f)
    }
}
