package com.oshi.desktop.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoRaAttachTest {
    @Test
    fun `TCP endpoint accepts a normal LAN hostname and default port`() {
        assertEquals(LoRaAttach.TcpEndpoint("meshtastic.local", 4403), LoRaAttach.tcpEndpoint("meshtastic.local"))
    }

    @Test
    fun `TCP endpoint permits IPv6 and its explicit valid port`() {
        assertEquals(LoRaAttach.TcpEndpoint("fd00::42", 4404), LoRaAttach.tcpEndpoint("fd00::42", 4404))
    }

    @Test
    fun `TCP endpoint rejects malformed hosts and out of range ports before reconnecting`() {
        assertNull(LoRaAttach.tcpEndpoint(""))
        assertNull(LoRaAttach.tcpEndpoint(" node.local"))
        assertNull(LoRaAttach.tcpEndpoint("node.local\nignored"))
        assertNull(LoRaAttach.tcpEndpoint("node.local", 0))
        assertNull(LoRaAttach.tcpEndpoint("node.local", 65_536))
    }
}
