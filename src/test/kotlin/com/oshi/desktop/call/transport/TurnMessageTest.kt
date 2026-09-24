package com.oshi.desktop.call.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.oshi.desktop.call.transport.WsRelayClient

/** PARITY.md row 2.1-t — the STUN/TURN codec, pinned to RFC 5769's published vectors. */
class TurnMessageTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").replace("\n", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** RFC 5769 §2.1: sample request with MESSAGE-INTEGRITY (key = the raw password) and FINGERPRINT. */
    private val rfc5769Request = hex(
        """
        000100582112a442b7e7a701bc34d686fa87dfae
        802200105354554e2074657374 20636c69656e74
        002400046e0001ff
        80290008932ff9b151263b36
        000600096576746a3a68367659202020
        000800149aeaa70cbfd8cb56781ef2b5b2d3f249c1b571a2
        80280004e57a3bcf
        """,
    )

    @Test
    fun rfc5769RequestVerifiesIntegrityAndFingerprint() {
        val m = TurnMessage.parse(rfc5769Request)
        assertNotNull(m)
        assertEquals(0x0001, m!!.type)
        assertTrue(m.fingerprintValid())
        assertTrue(m.integrityValid("VOkJxbRl1RmTxUk/WvJxBt".toByteArray()))
        assertFalse("a wrong key must fail", m.integrityValid("VOkJxbRl1RmTxUk/WvJxBu".toByteArray()))
    }

    @Test
    fun aFlippedByteFailsBothChecks() {
        val bad = rfc5769Request.copyOf()
        bad[30] = (bad[30].toInt() xor 1).toByte()
        val m = TurnMessage.parse(bad)!!
        assertFalse(m.fingerprintValid())
        assertFalse(m.integrityValid("VOkJxbRl1RmTxUk/WvJxBt".toByteArray()))
    }

    /** RFC 5769 §2.2: XOR-MAPPED-ADDRESS 192.0.2.1:32853 under transaction b7e7a701bc34d686fa87dfae. */
    @Test
    fun rfc5769XorAddressRoundTrips() {
        val tx = hex("b7e7a701bc34d686fa87dfae")
        val wire = hex("0001a147e112a643")
        assertEquals("192.0.2.1" to 32853, TurnMessage.decodeXorAddress(wire, tx))
        assertArrayEquals(wire, TurnMessage.encodeXorAddress("192.0.2.1", 32853, tx))
    }

    /** RFC 5769 §2.3: the IPv6 form, 2001:db8:1234:5678:11:2233:4455:6677 port 32853. */
    @Test
    fun rfc5769XorAddressV6() {
        val tx = hex("b7e7a701bc34d686fa87dfae")
        val wire = hex("0002a1470113a9faa5d3f179bc25f4b5bed2b9d9")
        val (ip, port) = TurnMessage.decodeXorAddress(wire, tx)!!
        assertEquals(32853, port)
        assertEquals("2001:db8:1234:5678:11:2233:4455:6677", ip)
        assertArrayEquals(wire, TurnMessage.encodeXorAddress(ip, port, tx))
    }

    @Test
    fun builtLongTermRequestVerifiesWithItsOwnKey() {
        val key = TurnMessage.longTermKey("1790149756:oshi", "oshi-messenger.com", "secret")
        val tx = TurnMessage.newTransactionId()
        val msg = TurnMessage.build(
            TurnMessage.ALLOCATE_REQUEST, tx,
            listOf(
                TurnMessage.Attr(TurnMessage.ATTR_REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)),
                TurnMessage.Attr(TurnMessage.ATTR_USERNAME, "1790149756:oshi".toByteArray()),
            ),
            key,
        )
        val m = TurnMessage.parse(msg)!!
        assertEquals(TurnMessage.ALLOCATE_REQUEST, m.type)
        assertArrayEquals(tx, m.txId)
        assertTrue(m.fingerprintValid())
        assertTrue(m.integrityValid(key))
        // FINGERPRINT must be the LAST attribute and MESSAGE-INTEGRITY right before it.
        assertEquals(0x80, msg[msg.size - 8].toInt() and 0xFF)
        assertEquals(0x28, msg[msg.size - 7].toInt() and 0xFF)
        assertEquals(TurnMessage.ATTR_MESSAGE_INTEGRITY, ((msg[msg.size - 32].toInt() and 0xFF) shl 8) or (msg[msg.size - 31].toInt() and 0xFF))
    }

    /** RFC 8489 §9.2.2: key = MD5("user:realm:pass"). Known value for a fixed input. */
    @Test
    fun longTermKeyIsMd5OfTheTriple() {
        val k = TurnMessage.longTermKey("user", "realm", "pass")
        assertArrayEquals(java.security.MessageDigest.getInstance("MD5").digest("user:realm:pass".toByteArray()), k)
        assertEquals(16, k.size)
    }

    @Test
    fun channelDataRoundTripsAndRejectsTruncation() {
        val f = TurnMessage.channelData(0x4001, byteArrayOf(0x31, 1, 2, 3))
        assertArrayEquals(hex("40010004 31010203"), f)
        val (ch, payload) = TurnMessage.parseChannelData(f)!!
        assertEquals(0x4001, ch)
        assertArrayEquals(byteArrayOf(0x31, 1, 2, 3), payload)
        assertNull(TurnMessage.parseChannelData(f, f.size - 1))
    }

    @Test
    fun channelDataNeverLooksLikeStunOrAnOshiPacket() {
        val f = TurnMessage.channelData(0x4000, ByteArray(20))
        assertFalse(TurnMessage.isStun(f))
        assertTrue(TurnMessage.isChannelData(f))
        // OSHI's own types: 0x31/0x32 punch, 0xF0-ish audio, 0xF1 video — none in 0x40..0x7F.
        for (b in listOf(0x31, 0x32, 0xF1, 0x05, 0x16)) {
            assertFalse(TurnMessage.isChannelData(byteArrayOf(b.toByte(), 0, 0, 0)))
        }
    }

    @Test
    fun errorCodeParses() {
        val tx = TurnMessage.newTransactionId()
        val msg = TurnMessage.build(
            TurnMessage.ALLOCATE_ERROR, tx,
            listOf(
                TurnMessage.Attr(TurnMessage.ATTR_ERROR_CODE, byteArrayOf(0, 0, 4, 1) + "Unauthorized".toByteArray()),
                TurnMessage.Attr(TurnMessage.ATTR_REALM, "oshi-messenger.com".toByteArray()),
                TurnMessage.Attr(TurnMessage.ATTR_NONCE, "abc".toByteArray()),
            ),
        )
        val m = TurnMessage.parse(msg)!!
        assertEquals(401, m.errorCode)
        assertEquals("oshi-messenger.com", m.realm)
        assertArrayEquals("abc".toByteArray(), m.nonce)
    }

    @Test
    fun credentialsParseTheProductionShapeAndNeverPrintThePassword() {
        val body = """{"username":"1790149756:oshi","password":"s3cr3t","ttl":3600,"uris":["turn:45.67.216.197:3478?transport=udp","turn:45.67.216.197:3478?transport=tcp"]}"""
        val c = TurnCredentials.parse(body, 1_000L)!!
        assertEquals("45.67.216.197", c.server.hostString)
        assertEquals(3478, c.server.port)
        assertEquals(1_000L + 3_600_000L, c.expiresAtMs)
        assertFalse(c.toString().contains("s3cr3t"))
        assertFalse(c.toString().contains("1790149756"))
        assertNull(TurnCredentials.parse("""{"username":"","password":""}""", 0))
    }

    @Test
    fun credentialsAreCachedUntilFiveMinutesBeforeExpiry() {
        var now = 0L
        var fetches = 0
        val src = TurnCredentials(
            fetcher = { _, _ -> fetches++; """{"username":"u$fetches","password":"p","ttl":600}""" },
            clock = { now },
        )
        assertEquals("u1", src.get()!!.username)
        now = 299_000L
        assertEquals("u1", src.get()!!.username)
        now = 301_000L
        assertEquals("u2", src.get()!!.username)
        assertEquals(2, fetches)
    }

    @Test
    fun udpRelayFramesMatchTheIosLayout() {
        val f = UdpRelayClient.encode(UdpRelayClient.TYPE_AUDIO, "RR", "SSS", "CID", byteArrayOf(9, 8))
        assertArrayEquals(byteArrayOf(1, 2, 'R'.code.toByte(), 'R'.code.toByte(), 3, 'S'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte(), 3, 'C'.code.toByte(), 'I'.code.toByte(), 'D'.code.toByte(), 9, 8), f)
        // Inbound drops the recipient field: [type][senderLen][sender][callIdLen][callId][payload].
        val inbound = byteArrayOf(2, 3, 1, 1, 1, 2, 7, 7, 0xF1.toByte(), 5)
        assertArrayEquals(byteArrayOf(0xF1.toByte(), 5), UdpRelayClient.decodeInbound(inbound))
        assertNull(UdpRelayClient.decodeInbound(byteArrayOf(1, 40, 1)))
        assertEquals("ab-_c", UdpRelayClient.toB64Url("ab+/c=="))
    }

    // ============================================================ TURN over TLS: stream framing

    @Test
    fun channelDataIsPaddedToFourOverAStreamAndTheLengthStaysUnpadded() {
        val f = TurnMessage.channelData(0x4000, byteArrayOf(1, 2, 3, 4, 5), pad = true)
        assertEquals(12, f.size)
        assertEquals(5, ((f[2].toInt() and 0xFF) shl 8) or (f[3].toInt() and 0xFF))
        assertArrayEquals(byteArrayOf(0, 0, 0), f.copyOfRange(9, 12))
        assertEquals(8, TurnMessage.channelData(0x4000, byteArrayOf(1, 2, 3, 4), pad = true).size)
    }

    @Test
    fun streamFramerCutsStunAndPaddedChannelDataAcrossArbitraryChunks() {
        val stun = TurnMessage.build(TurnMessage.ALLOCATE_REQUEST, TurnMessage.newTransactionId(), listOf(
            TurnMessage.Attr(TurnMessage.ATTR_REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)),
        ))
        val cd1 = TurnMessage.channelData(0x4001, byteArrayOf(0x31, 9, 9), pad = true)      // 3 → padded 4
        val cd2 = TurnMessage.channelData(0x4002, ByteArray(1137) { it.toByte() }, pad = true)
        val stream = stun + cd1 + cd2 + stun
        // Every split point, one byte at a time and in odd chunks.
        for (chunk in listOf(1, 3, 7, 64, stream.size)) {
            val framer = com.oshi.desktop.call.transport.StreamFramer()
            val got = ArrayList<ByteArray>()
            var o = 0
            while (o < stream.size) {
                val n = minOf(chunk, stream.size - o)
                got += framer.feed(stream.copyOfRange(o, o + n))
                o += n
            }
            assertEquals("chunk=$chunk", 4, got.size)
            assertArrayEquals(stun, got[0])
            // ChannelData comes out UNPADDED so its own length field matches.
            assertArrayEquals(byteArrayOf(0x40, 0x01, 0, 3, 0x31, 9, 9), got[1])
            assertEquals(4 + 1137, got[2].size)
            assertArrayEquals(ByteArray(1137) { it.toByte() }, TurnMessage.parseChannelData(got[2])!!.second)
            assertArrayEquals(stun, got[3])
        }
    }

    @Test
    fun streamFramerDropsGarbageInsteadOfWaitingForever() {
        val framer = com.oshi.desktop.call.transport.StreamFramer()
        assertTrue(framer.feed(byteArrayOf(0xC0.toByte(), 0, 0, 0)).isEmpty())
        val cd = TurnMessage.channelData(0x4000, byteArrayOf(1, 2, 3, 4), pad = true)
        assertEquals(1, framer.feed(cd).size)
    }

    // ============================================================ WebSocket relay

    @Test
    fun webSocketUrlKeepsTheTrailingSlashNginxNeeds() {
        assertEquals("wss://oshi-messenger.com/voip/", WsRelayClient.urlFor("https://oshi-messenger.com"))
        assertEquals("wss://oshi-messenger.com/voip/", WsRelayClient.urlFor("https://oshi-messenger.com/"))
        assertEquals("ws://127.0.0.1:8083/voip/", WsRelayClient.urlFor("http://127.0.0.1:8083"))
    }

    @Test
    fun webSocketRelayParsesTheServerBinaryFrames() {
        val c = WsRelayClient("AAA+/w==", "BBB=", "CID")
        val got = ArrayList<ByteArray>()
        c.onPayload = { got += it }
        c.handle(byteArrayOf(0x04, 0x01))
        assertTrue(c.registered)
        // Server → client: [type][senderLen][sender][callIdLen][callId][payload] (buildServerFrame).
        c.handle(byteArrayOf(0x02, 2, 'x'.code.toByte(), 'y'.code.toByte(), 1, 'c'.code.toByte(), 0xF1.toByte(), 7))
        c.handle(byteArrayOf(0x05, 0, 0, 0, 0, 0, 0, 0, 1))
        assertEquals(1, got.size)
        assertArrayEquals(byteArrayOf(0xF1.toByte(), 7), got[0])
        c.handle(byteArrayOf(0x04, 0x00))
        assertNotNull(c.refused)
        c.close()
    }
}
