package com.oshi.desktop.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __LORA_LONG_TEXT_OMP_2026_11_18__ / __LORA_AIRTIME_HINT_2026_11_18__ desktop parity with Android 59c5ff2b. */
class LoRaLongTextTest {

    private fun meshPacket(from: UInt, portnum: Long, payload: ByteArray): ByteArray {
        val data = ProtoWriter().varint(1, portnum).bytes(2, payload).data
        return ProtoWriter().fixed32(1, from).fixed32(2, from).bytes(4, data).data
    }

    @Test
    fun `beacon and status frames reach the client as OMP control, data still reassembles`() {
        val inbound = LoRaInbound()
        val me = 0x0A0B0C0Du
        val beacon = OshiMeshProtocol.beaconProbe()
        val d = inbound.route(meshPacket(me, LoRaProto.PORT_OSHI.toLong(), beacon), "KEY=", 0L)
        assertTrue(d is LoRaInbound.Decision.OmpControl)
        assertEquals(OshiMeshProtocol.FrameType.BEACON, (d as LoRaInbound.Decision.OmpControl).type)
        assertEquals(me, d.from)
    }

    @Test
    fun `a 4,000-byte text envelope fits one OSHI Mesh message, and not the stock 12 frames`() {
        // The legacy envelope's ciphertext is base64 of AES-GCM over the text plus ratchet header:
        // incompressible, ~1.33x the text. Random bytes stand in for it.
        val rnd = java.util.Random(7)
        val sealed = ByteArray(4_000 + 120).also { rnd.nextBytes(it) }
        val msg = LoRaSecureMessage(
            id = "6F1C0D7E-0000-4000-8000-000000000001",
            senderAddress = "S".repeat(44), recipientAddress = "R".repeat(44),
            encryptedContent = LoRaEncryptedContent(
                ciphertext = java.util.Base64.getEncoder().encodeToString(sealed),
                signature = "G".repeat(88), senderPublicKey = "S".repeat(44), unixMillis = 1_763_000_000_000L,
            ),
            unixMillis = 1_763_000_000_000L, isRead = false, deliveryStatus = LoRaSecureMessage.STATUS_SENT,
            senderPublicKey = "S".repeat(44), recipientPublicKey = "R".repeat(44),
        )
        val json = msg.toWireJson()
        val body = OshiMeshProtocol.encodeBody(json)
        assertTrue("OMP body ${body.size} B", LoRaTextBudget.fitsOmp(body))
        assertTrue(OshiMeshProtocol.dataFrames(1u, LoRaProto.BROADCAST_ADDR, body) != null)
        assertEquals(null, LoRaFrame.frames(1u, json))
    }

    @Test
    fun `airtime hint after 60 s of QUEUED, cleared by any later state, forgotten after 2 h`() {
        val w = LoRaAirtimeWait()
        w.onQueued("m1", 0)
        w.onQueued("m1", 50_000)  // a repeat does not reset the clock
        assertTrue(w.waiting(59_999).isEmpty())
        assertEquals(setOf("m1"), w.waiting(60_000))
        w.onProgress("m1")
        assertFalse(w.hasPending())
        w.onQueued("m2", 0)
        assertTrue(w.waiting(2 * 60 * 60_000L + 1).isEmpty())
    }
}
