package com.oshi.desktop.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovered peers must not be immortal.
 *
 * A peer that disappears without a goodbye — laptop closed, cable pulled, process killed —
 * would otherwise sit in the peer list forever, offered to the user as something they can
 * message. The shipped clients lean on their OS resolver's own callbacks for this
 * (NsdManager's `onServiceLost`, NetServiceBrowser's `didRemove`); a hand-written
 * responder has to do it itself, which is why this is desktop-specific code and gets a
 * desktop-specific test.
 */
class MdnsExpiryTest {

    @Test
    fun `a peer that stops answering is dropped, and a live one is not`() {
        var now = 1_000_000L
        val lost = mutableListOf<String>()
        val found = mutableListOf<MdnsService.DiscoveredService>()

        val svc = MdnsService(
            instanceLabel = "OSHI-selftest",
            hostLabel = "oshi-selftest",
            port = 1234,
            txtEntries = listOf("pk=SELF", "name=self", "platform=desktop"),
            onServiceFound = { found.add(it) },
            onServiceLost = { lost.add(it) },
            clock = { now },
        )

        // Feed the browser a response for two peers, without any socket involved.
        svc.acceptForTest(responseFor("OSHI-alive", "10.0.0.2", 5001, "PK-ALIVE"))
        svc.acceptForTest(responseFor("OSHI-gone", "10.0.0.3", 5002, "PK-GONE"))
        assertEquals(2, found.size)

        // Four minutes later, only one of them is still answering.
        now += 240_000
        svc.acceptForTest(responseFor("OSHI-alive", "10.0.0.2", 5001, "PK-ALIVE"))

        // Past the cutoff for the silent one, not for the other.
        now += MdnsService.INSTANCE_TTL_MS - 120_000
        val dropped = svc.expireStale(now)
        assertEquals("only the silent peer may be dropped", listOf("oshi-gone"), dropped)
        assertEquals(listOf("oshi-gone"), lost)

        // And the live one goes too, once IT stops answering.
        assertTrue(svc.expireStale(now + MdnsService.INSTANCE_TTL_MS + 1).contains("oshi-alive"))
    }

    /** A complete announcement: PTR + SRV + TXT + A, the way a real responder sends it. */
    private fun responseFor(label: String, ip: String, port: Int, pk: String): MdnsCodec.Message {
        val type = MdnsCodec.Name.of(MeshProtocol.SERVICE_TYPE)
        val instance = MdnsCodec.Name(listOf(label) + type.labels)
        val host = MdnsCodec.Name(listOf("$label-host", "local"))
        val bytes = MdnsCodec.encodeResponse(
            answers = listOf(MdnsCodec.Record.Ptr(type, MdnsService.TTL_SHARED, instance)),
            additional = listOf(
                MdnsCodec.Record.Srv(instance, MdnsService.TTL_HOST, 0, 0, port, host),
                MdnsCodec.Record.Txt(instance, MdnsService.TTL_SHARED,
                    listOf("pk=$pk", "name=$label", "platform=android")),
                MdnsCodec.Record.A(host, MdnsService.TTL_HOST,
                    ip.split(".").map { it.toInt().toByte() }.toByteArray()),
            ),
        )
        return MdnsCodec.decode(bytes)!!
    }
}
