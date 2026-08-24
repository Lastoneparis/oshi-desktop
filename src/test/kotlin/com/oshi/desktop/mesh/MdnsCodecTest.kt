package com.oshi.desktop.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DNS-SD codec, checked against packets that two OTHER implementations actually put
 * on the wire.
 *
 * This is the one layer of the desktop mesh whose peer is not OSHI code: what has to be
 * accepted here is whatever Apple's mDNSResponder and Android's NsdManager emit. So the
 * fixtures are real captures taken on this LAN on 2026-08-24, not packets this codec
 * built for itself:
 *
 *   APPLE_RESPONSE   — mDNSResponder on macOS answering a browse for
 *                      `_oshi-mesh._tcp.local.`, for a service registered with
 *                      `dns-sd -R`. This is the same responder iOS runs.
 *   ANDROID_RESPONSE — a SHIPPED OSHI Android client's NsdManager announcing itself.
 *
 * Both had identifying values (public key, display name, hostname) replaced by
 * placeholders OF THE SAME LENGTH, so every compression pointer offset in the packet
 * still resolves and the structure is untouched. That is the whole point: a fixture whose
 * offsets were shifted would test a packet nobody ever sent.
 *
 * What these prove and what they do not: they prove this codec READS both stacks. They
 * cannot prove either stack reads what this codec WRITES — that was checked live, by
 * running the node and browsing for it from Apple's own tooling (see PLAN_MESH.md).
 */
class MdnsCodecTest {

    // --------------------------------------------------- reading Apple's responder

    @Test
    fun `decodes a real Apple mDNSResponder answer, compression pointers and all`() {
        val msg = MdnsCodec.decode(hex(APPLE_RESPONSE))
        assertNotNull("Apple's own response failed to decode", msg)
        assertTrue(msg!!.isResponse)

        val ptr = msg.allRecords.filterIsInstance<MdnsCodec.Record.Ptr>()
            .first { it.name.toString() == "_oshi-mesh._tcp.local." }
        // The instance label carries base64 characters — `/` and `+` — because it is
        // "OSHI-" + the first 8 characters of a public key. A codec that treated a name
        // as a dotted string, or that URL-decoded it, would corrupt exactly this.
        assertEquals("OSHI-AbCd12/+._oshi-mesh._tcp.local.", ptr.target.toString())

        val srv = msg.allRecords.filterIsInstance<MdnsCodec.Record.Srv>().first()
        assertEquals(45678, srv.port)
        assertEquals("MacBook-Pro-4.local.", srv.target.toString())

        val txt = msg.allRecords.filterIsInstance<MdnsCodec.Record.Txt>().first().asMap()
        assertEquals("AbCd12/+xyzKEYMATERIALbase64PADDING==", txt["pk"])
        assertEquals("Fixture Phone", txt["name"])   // a TXT value with a space in it
        assertEquals("android", txt["platform"])

        val a = msg.allRecords.filterIsInstance<MdnsCodec.Record.A>().first()
        assertEquals("192.168.1.11", a.ipString)

        // An AAAA rides in the same packet. iOS's resolver skips IPv6 addresses outright,
        // so this codec must keep the record without letting it displace the A.
        assertTrue("the AAAA must be kept, not choked on",
            msg.allRecords.any { it is MdnsCodec.Record.Other && it.type == 28 })
    }

    // --------------------------------------------------- reading Android's responder

    @Test
    fun `decodes a shipped Android client announcing itself`() {
        val msg = MdnsCodec.decode(hex(ANDROID_RESPONSE))
        assertNotNull("a real Android NSD announcement failed to decode", msg)

        val ptrs = msg!!.allRecords.filterIsInstance<MdnsCodec.Record.Ptr>()
            .filter { it.name.toString() == "_oshi-mesh._tcp.local." }
            .map { it.target.labels.first() }
        // TWO instances for ONE device: Android hit a name conflict with its own earlier
        // registration and published the renamed form WITHOUT withdrawing the first. This
        // is why MeshNode keys peers by the TXT `pk` and never by the instance label —
        // and why the renamed label, with its space and parentheses, has to survive
        // decoding intact.
        assertEquals(listOf("OSHI-F1xtUr3A", "OSHI-F1xtUr3A (2)"), ptrs)

        val srv = msg.allRecords.filterIsInstance<MdnsCodec.Record.Srv>().first()
        assertEquals(45779, srv.port)
        assertEquals("Android_TESTFIXT.local.", srv.target.toString())

        val txt = msg.allRecords.filterIsInstance<MdnsCodec.Record.Txt>().first().asMap()
        assertEquals("android", txt["platform"])
        assertEquals("F1xtUr3AndroidPeerKeyForParityTests00000000=", txt["pk"])

        assertEquals("192.168.1.26",
            msg.allRecords.filterIsInstance<MdnsCodec.Record.A>().first().ipString)
    }

    // --------------------------------------------------- what we emit

    @Test
    fun `our announcement round trips through our own decoder`() {
        val type = MdnsCodec.Name.of(MeshProtocol.SERVICE_TYPE)
        val instance = MdnsCodec.Name(listOf("OSHI-Ab+d12/x") + type.labels)
        val host = MdnsCodec.Name(listOf("oshi-deadbeef", "local"))
        val bytes = MdnsCodec.encodeResponse(
            answers = listOf(MdnsCodec.Record.Ptr(type, 4500, instance)),
            additional = listOf(
                MdnsCodec.Record.Srv(instance, 120, 0, 0, 54321, host),
                MdnsCodec.Record.Txt(instance, 4500, listOf("pk=AAA+/BBB=", "name=Hugo's Desktop", "platform=desktop")),
                MdnsCodec.Record.A(host, 120, byteArrayOf(192.toByte(), 168.toByte(), 1, 42)),
            ),
        )
        val back = MdnsCodec.decode(bytes) ?: error("we cannot read our own announcement")
        assertEquals(instance.toString(), back.answers.filterIsInstance<MdnsCodec.Record.Ptr>().first().target.toString())
        val srv = back.additional.filterIsInstance<MdnsCodec.Record.Srv>().first()
        assertEquals(54321, srv.port)
        assertEquals(host.toString(), srv.target.toString())
        assertEquals("Hugo's Desktop", back.additional.filterIsInstance<MdnsCodec.Record.Txt>().first().asMap()["name"])
        assertEquals("192.168.1.42", back.additional.filterIsInstance<MdnsCodec.Record.A>().first().ipString)
    }

    /**
     * The cache-flush bit decides whether a receiver REPLACES what it knows or ADDS to it.
     * On our unique records (SRV/TXT/A) it must be set, or a browser keeps a stale port
     * for us after a restart. On the PTR it must NOT be: a PTR is a shared record, and
     * flushing it tells every browser to forget the OTHER OSHI peers it had found.
     */
    @Test
    fun `cache flush is set on unique records and never on the shared PTR`() {
        val type = MdnsCodec.Name.of(MeshProtocol.SERVICE_TYPE)
        val instance = MdnsCodec.Name(listOf("OSHI-x") + type.labels)
        val host = MdnsCodec.Name(listOf("oshi-x", "local"))
        val bytes = MdnsCodec.encodeResponse(
            answers = listOf(MdnsCodec.Record.Ptr(type, 4500, instance)),
            additional = listOf(
                MdnsCodec.Record.Srv(instance, 120, 0, 0, 1234, host),
                MdnsCodec.Record.Txt(instance, 4500, listOf("pk=x")),
                MdnsCodec.Record.A(host, 120, byteArrayOf(10, 0, 0, 1)),
            ),
        )
        val classes = rrClasses(bytes)
        assertEquals("PTR must be a plain IN record", 0x0001, classes[0])
        for (i in 1..3) {
            assertEquals("unique record #$i must carry the cache-flush bit", 0x8001, classes[i])
        }
    }

    @Test
    fun `a query encodes as one question and nothing else`() {
        val q = MdnsCodec.Question(MdnsCodec.Name.of(MeshProtocol.SERVICE_TYPE), MdnsCodec.TYPE_PTR, MdnsCodec.CLASS_IN)
        val bytes = MdnsCodec.encodeQuery(listOf(q))
        val back = MdnsCodec.decode(bytes)!!
        assertTrue("a query must not have the response bit", !back.isResponse)
        assertEquals(1, back.questions.size)
        assertEquals("_oshi-mesh._tcp.local.", back.questions[0].name.toString())
        assertEquals(MdnsCodec.TYPE_PTR, back.questions[0].type)
        assertTrue(back.answers.isEmpty() && back.additional.isEmpty())
    }

    // --------------------------------------------------- hostile input

    /**
     * A compression pointer that points at itself. This is the classic mDNS
     * decompression bomb, it arrives unauthenticated from anyone on the LAN, and a
     * decoder that follows it never returns. Null, not a hang and not a throw that kills
     * the receive thread.
     */
    @Test
    fun `a self-referential compression pointer is refused, not followed`() {
        val bomb = hex(
            "000084000000000100000000" +   // header, one answer
                "c00c" +                   // NAME = a pointer to offset 12, which is this name
                "000c" +                   // PTR
                "0001" +                   // IN
                "00000e10" +               // ttl
                "0002" + "c00c"            // rdata: another pointer to itself
        )
        assertNull(MdnsCodec.decode(bomb)?.answers?.firstOrNull())
    }

    @Test
    fun `a truncated packet decodes to null instead of throwing`() {
        val full = hex(APPLE_RESPONSE)
        for (cut in listOf(4, 20, 60, full.size - 5)) {
            MdnsCodec.decode(full.copyOfRange(0, cut))   // must not throw
        }
        assertNull(MdnsCodec.decode(byteArrayOf(0, 0)))
    }

    /**
     * RFC 6763 §6.4: the first occurrence of a repeated key wins, an entry with no '='
     * is a boolean attribute, and keys are case-insensitive. Getting the precedence
     * backwards would let a second `pk=` later in the record silently redirect a peer.
     */
    @Test
    fun `TXT parsing follows the DNS-SD precedence rules`() {
        val txt = MdnsCodec.Record.Txt(MdnsCodec.Name.of("x.local."), 4500,
            listOf("pk=first", "pk=second", "PLATFORM=Desktop", "flagonly", "=novalue"))
        val map = txt.asMap()
        assertEquals("first", map["pk"])
        assertEquals("Desktop", map["platform"])
        assertEquals("", map["flagonly"])
        assertTrue("an entry with an empty key is not an attribute", !map.containsKey(""))
    }

    @Test
    fun `an empty TXT is emitted as one zero-length string, never as empty rdata`() {
        val bytes = MdnsCodec.encodeResponse(
            listOf(MdnsCodec.Record.Txt(MdnsCodec.Name.of("x.local."), 4500, emptyList()))
        )
        val rdLen = ((bytes[bytes.size - 3].toInt() and 0xFF) shl 8) or (bytes[bytes.size - 2].toInt() and 0xFF)
        assertEquals("a DNS-SD TXT rdata is never length 0", 1, rdLen)
        assertEquals(0, bytes[bytes.size - 1].toInt())
    }

    @Test
    fun `names compare case-insensitively but keep their bytes`() {
        val a = MdnsCodec.Name.of("_OSHI-Mesh._TCP.local.")
        val b = MdnsCodec.Name.of("_oshi-mesh._tcp.local.")
        assertTrue(a.equalsIgnoreCase(b))
        assertEquals("_OSHI-Mesh._TCP.local.", a.toString())
        assertTrue(MdnsCodec.Name(listOf("OSHI-x", "_oshi-mesh", "_tcp", "local")).endsWith(b))
    }

    /** An instance label containing a dot is legal DNS-SD and must not be split. */
    @Test
    fun `an instance label with a dot survives a round trip`() {
        val type = MdnsCodec.Name.of(MeshProtocol.SERVICE_TYPE)
        val weird = MdnsCodec.Name(listOf("Hugo's Mac. at home") + type.labels)
        val bytes = MdnsCodec.encodeResponse(listOf(MdnsCodec.Record.Ptr(type, 4500, weird)))
        val back = MdnsCodec.decode(bytes)!!.answers.filterIsInstance<MdnsCodec.Record.Ptr>().first()
        assertEquals(listOf("Hugo's Mac. at home", "_oshi-mesh", "_tcp", "local"), back.target.labels)
    }

    @Test
    fun `a goodbye is a TTL of zero`() {
        assertTrue(MdnsCodec.Record.Ptr(MdnsCodec.Name.of("a.local."), 0, MdnsCodec.Name.of("b.local.")).isGoodbye)
        assertTrue(!MdnsCodec.Record.Ptr(MdnsCodec.Name.of("a.local."), 1, MdnsCodec.Name.of("b.local.")).isGoodbye)
    }

    // --------------------------------------------------- helpers

    /** rrclass of each resource record, in order — the raw bytes, not what we meant to write. */
    private fun rrClasses(packet: ByteArray): List<Int> {
        val msg = MdnsCodec.decode(packet)!!
        val out = ArrayList<Int>()
        var pos = 12
        fun skipName() {
            while (true) {
                val len = packet[pos].toInt() and 0xFF
                if (len == 0) { pos++; return }
                if ((len and 0xC0) == 0xC0) { pos += 2; return }
                pos += 1 + len
            }
        }
        repeat(msg.questions.size) { skipName(); pos += 4 }
        repeat(msg.answers.size + msg.authority.size + msg.additional.size) {
            skipName()
            pos += 2
            out.add(((packet[pos].toInt() and 0xFF) shl 8) or (packet[pos + 1].toInt() and 0xFF))
            pos += 2 + 4
            val rd = ((packet[pos].toInt() and 0xFF) shl 8) or (packet[pos + 1].toInt() and 0xFF)
            pos += 2 + rd
        }
        return out
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    companion object {
        private const val APPLE_RESPONSE =
            "0000840000000001000000070a5f6f7368692d6d657368045f746370056c6f63616c00000c00010000119400100d4f53" +
            "48492d4162436431322f2bc00c0d4d6163426f6f6b2d50726f2d34c01c001c8001000011940010fe8000000000000014" +
            "44360864727a72c03d00018001000011940004c0a8010bc03d001c80010000119400102a01cb150099870014b0930099" +
            "ab36b5c02d0010800100001194004d28706b3d4162436431322f2b78797a4b45594d4154455249414c62617365363450" +
            "414444494e473d3d126e616d653d466978747572652050686f6e6510706c6174666f726d3d616e64726f6964c02d0021" +
            "800100001194000800000000b26ec03dc03d002f8001000011940008c03d000440000008c02d002f8001000011940009" +
            "c02d00050000800040"

        private const val ANDROID_RESPONSE =
            "00008400000000020000000a0a5f6f7368692d6d657368045f746370056c6f63616c00000c00010000119400100d4f53" +
            "48492d4631787455723341c00cc00c000c0001000011940014114f5348492d463178745572334120283229c00c10416e" +
            "64726f69645f5445535446495854c01c00018001000000780004c0a8011ac05d001c80010000007800102a01cb150099" +
            "8700acc96afffe6568e8c0490021800100000078000800000000b2d3c05dc02d0010800100001194004b2f706b3d4631" +
            "7874557233416e64726f6964506565724b6579466f72506172697479546573747330303030303030303d096e616d653d" +
            "7065657210706c6174666f726d3d616e64726f6964c049002f8001000000780009c04900050000800040c04900108001" +
            "00001194004b2f706b3d46317874557233416e64726f6964506565724b6579466f725061726974795465737473303030" +
            "30303030303d096e616d653d7065657210706c6174666f726d3d616e64726f6964c05d002f8001000000780008c05d00" +
            "0440000008c02d002f8001000000780009c02d00050000800040c02d0021800100000078000800000000b2d3c05dc05d" +
            "001c8001000000780010fe80000000000000acc96afffe6568e8"

    }
}
