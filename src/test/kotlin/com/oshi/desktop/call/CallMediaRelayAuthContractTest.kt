package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.call.transport.RelayToken
import com.oshi.desktop.call.transport.RelayTokenSource
import com.oshi.desktop.call.transport.UdpRelayAuth
import com.oshi.desktop.call.transport.UdpRelayClient
import com.oshi.messenger.network.v2.OSHICryptoV2
import com.sun.net.httpserver.HttpServer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CALL_MEDIA_AUTH_DESKTOP_2026_09_23__ `docs/CALL_MEDIA_RELAY_AUTH_CONTRACT.md` (STABLE 1.0).
 *
 * §7 vectors T1-T3 byte for byte, then the client behaviour against a fake `:8089` that
 * verifies trailers with the keys [FakeCallServer] issued: ack only on `04 01`, nack 01 →
 * a new token, counter strictly increasing and restarting at 1 per token.
 */
class CallMediaRelayAuthContractTest {

    private val closeables = ArrayList<AutoCloseable>()
    @After fun tearDown() = closeables.reversed().forEach { runCatching { it.close() } }

    private val seed = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
    private val vectorIdentity: DesktopIdentity = run {
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        DesktopIdentity(
            OSHICryptoV2.X25519Pair(ByteArray(32), ByteArray(32) { 0xAB.toByte() }),
            priv.encoded,
            priv.generatePublicKey().encoded,
        )
    }
    private val identityB64u = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s"
    private val peerB64u = "zc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc0"
    private val vectorCallId = "00000000-0000-4000-8000-000000000001"
    private val ts = 1_790_000_000_000L

    // ============================================================ §7 vectors

    @Test fun `T1 POST relay-token — through the real client, on the wire`() {
        val captured = ConcurrentHashMap<String, String>()
        var body = ByteArray(0)
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                captured["path"] = ex.requestURI.rawPath
                body = ex.requestBody.readBytes()
                for (h in listOf("x-oshi-signature", "x-oshi-timestamp", "x-oshi-signing-pubkey")) {
                    ex.requestHeaders.getFirst(h)?.let { captured[h] = it }
                }
                val b = ("{\"tokenId\":\"AAECAwQFBgcICQoLDA0ODw\",\"macKey\":\"ERERERERERERERERERERERERERERERERERERERERERE\"," +
                    "\"expiresAt\":1790014400000,\"ttlMs\":14400000,\"udpPort\":8089,\"mode\":\"dual\",\"v\":1}").toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        closeables += AutoCloseable { http.stop(0) }
        val client = CallSignalClient("http://127.0.0.1:${http.address.port}", DesktopV2Signer(vectorIdentity), clockMs = { ts })
        // Standard base64 in, base64url on the wire.
        val reply = client.relayToken(vectorIdentity.userKey, "zc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc0=", vectorCallId)

        assertEquals("/api/call/relay-token", captured["path"])
        assertEquals(
            "{\"identity\":\"$identityB64u\",\"peer\":\"$peerB64u\",\"callId\":\"$vectorCallId\"}",
            String(body, Charsets.UTF_8),
        )
        assertEquals("d7d96a32479808485bfa046c6f90958321a4efa2df1e1e995f321f0826ea9fa0", DesktopV2Signer.sha256Hex(body))
        assertEquals(
            "POST\n/api/call/relay-token\nd7d96a32479808485bfa046c6f90958321a4efa2df1e1e995f321f0826ea9fa0\n1790000000000",
            DesktopV2Signer(vectorIdentity).canonicalString("POST", "/api/call/relay-token", body, "1790000000000"),
        )
        assertEquals(
            "MMiJUgXMwpDFQR7ZDZRWFZ4TathkQYx2kBOSE8l67niXpMK9nASLpki4SBcIFA4ta9NYyIpenRqvfzxs9GZYCA==",
            captured["x-oshi-signature"],
        )
        assertEquals("ebVWLo/mVPlAeLES6KmLp5AfhTrmlb7X4OORC60ElmQ=", captured["x-oshi-signing-pubkey"])
        val t = reply.token!!
        assertArrayEquals(hex("000102030405060708090a0b0c0d0e0f"), t.tokenId)
        assertArrayEquals(ByteArray(32) { 0x11 }, t.macKey)
        assertEquals(1_790_014_400_000L, t.expiresAtMs)
        assertEquals("dual", t.mode)
        assertFalse("toString must not carry the key", t.toString().contains("1111"))
    }

    @Test fun `T2 authenticated register counter 1`() {
        val d = UdpRelayAuth.datagram(
            UdpRelayClient.TYPE_REGISTER, peerB64u, identityB64u, vectorCallId,
            hex("000102030405060708090a0b0c0d0e0f"), 1, ByteArray(32) { 0x11 },
        )
        assertEquals(170, d.size)
        assertEquals(
            "042b7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63302b713675727136757271367572713675727136757271367572713675727136757271367572713675727136732430303030303030302d303030302d343030302d383030302d3030303030303030303030314f524131000102030405060708090a0b0c0d0e0f0000000000000001efcd414ee50798f79de486aaad32cdee",
            hexOf(d),
        )
    }

    @Test fun `T3 authenticated keepalive counter 2`() {
        val d = UdpRelayAuth.datagram(
            UdpRelayClient.TYPE_PING, peerB64u, identityB64u, vectorCallId,
            hex("000102030405060708090a0b0c0d0e0f"), 2, ByteArray(32) { 0x11 },
        )
        assertEquals(
            "052b7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63302b713675727136757271367572713675727136757271367572713675727136757271367572713675727136732430303030303030302d303030302d343030302d383030302d3030303030303030303030314f524131000102030405060708090a0b0c0d0e0f000000000000000218b0e57904a1ab0168acbcc9faf527f3",
            hexOf(d),
        )
    }

    // ============================================================ the token route

    @Test fun `the fake server issues a token only to a signed request, and refuses unsigned`() {
        val server = FakeCallServer().also { closeables += it }
        val a = identity("a"); val b = identity("b")
        val ok = CallSignalClient(server.baseUrl, DesktopV2Signer(a)).relayToken(a.userKey, b.userKey, "call-1")
        assertNotNull(ok.token)
        val unsigned = CallSignalClient(server.baseUrl, null).relayToken(a.userKey, b.userKey, "call-1")
        assertEquals(403, unsigned.code)
        assertEquals("unsigned", unsigned.reason)
        server.bind(a)
        val forged = CallSignalClient(server.baseUrl, DesktopV2Signer(b)).relayToken(a.userKey, b.userKey, "call-1")
        assertEquals(403, forged.code)
        assertEquals("mismatch", forged.reason)
        assertEquals(listOf("issued", "unsigned", "mismatch"), server.relayTokenVerdicts.toList())
    }

    @Test fun `a token near expiry is re-fetched, and re-fetches are spaced`() {
        var now = 0L
        var n = 0
        val src = RelayTokenSource({ n++; RelayToken(ByteArray(16) { n.toByte() }, ByteArray(32), now + 3_600_000L) }, { now }, 5_000L)
        val t1 = src.current()!!
        assertEquals(t1, src.current())
        now = 3_600_000L - RelayToken.REFRESH_BEFORE_MS
        val t2 = src.current()!!
        assertTrue(t1 !== t2)
        assertEquals(t2, src.refresh()) // inside 5 s: no new fetch
        assertEquals(2, n)
        now += 5_000L
        assertTrue(t2 !== src.refresh())
        assertEquals(3, n)
    }

    // ============================================================ the relay client

    @Test fun `registers carry a verified trailer, registered only on the exact 04 01 ack`() {
        val server = FakeCallServer().also { closeables += it }
        val a = identity("a"); val b = identity("b")
        val relay = FakeUdpRelay(server).also { closeables += it }
        relay.reply = { byteArrayOf(0x04, 0x02) } // NOT an ack
        val src = RelayTokenSource({ CallSignalClient(server.baseUrl, DesktopV2Signer(a)).relayToken(a.userKey, b.userKey, "call-1").token })
        val c = UdpRelayClient(a.userKey, b.userKey, "call-1", relay.address, tokens = src).also { closeables += it }
        c.start()
        assertTrue(waitFor { relay.verified.size >= 1 })
        Thread.sleep(200)
        assertFalse("04 02 is not an ack", c.registered)
        assertEquals(0L, c.legacyRegistersSent)

        relay.reply = { byteArrayOf(0x04, 0x01) }
        c.handle(byteArrayOf(0x04, 0x01))
        assertTrue(c.registered)
        val v = relay.verified.first()
        assertEquals(CallSignalClient.base64Url(a.userKey), v.sender)
        assertEquals(CallSignalClient.base64Url(b.userKey), v.recipient)
        assertEquals("call-1", v.callId)
        assertEquals(1L, v.counter)
        assertTrue(relay.rejected.isEmpty())
    }

    @Test fun `nack 01 fetches a new token and the counter restarts at 1`() {
        val server = FakeCallServer().also { closeables += it }
        val a = identity("a"); val b = identity("b")
        val relay = FakeUdpRelay(server).also { closeables += it }
        var nackNext = true
        relay.reply = { if (nackNext) { nackNext = false; byteArrayOf(0x04, 0x00, 0x01) } else byteArrayOf(0x04, 0x01) }
        val src = RelayTokenSource(
            { CallSignalClient(server.baseUrl, DesktopV2Signer(a)).relayToken(a.userKey, b.userKey, "call-1").token },
            minRefetchMs = 0L,
        )
        val c = UdpRelayClient(a.userKey, b.userKey, "call-1", relay.address, tokens = src).also { closeables += it }
        c.start()
        assertTrue("re-registered with a new token", waitFor(5_000) { relay.verified.size >= 2 && c.registered })
        assertEquals(1, c.lastNack)
        assertEquals(2, src.fetches)
        val (first, second) = relay.verified.take(2)
        assertFalse(first.tokenHex == second.tokenHex)
        assertEquals(1L, second.counter)
        assertEquals(0L, c.legacyRegistersSent)
        assertEquals(2, server.relayTokens.size)
    }

    @Test fun `bad MAC twice gives the carrier up, never a token-less register`() {
        val server = FakeCallServer().also { closeables += it }
        val a = identity("a"); val b = identity("b")
        val relay = FakeUdpRelay(server).also { closeables += it }
        relay.reply = { byteArrayOf(0x04, 0x00, 0x02) }
        val src = RelayTokenSource(
            { CallSignalClient(server.baseUrl, DesktopV2Signer(a)).relayToken(a.userKey, b.userKey, "call-1").token },
            minRefetchMs = 0L,
        )
        val c = UdpRelayClient(a.userKey, b.userKey, "call-1", relay.address, tokens = src).also { closeables += it }
        c.start()
        assertTrue(waitFor(5_000) { c.authFailed })
        assertFalse(c.usable())
        assertEquals(0L, c.legacyRegistersSent)
        assertEquals(2, relay.verified.size)
    }

    @Test fun `with no token the legacy register still goes out (dual)`() {
        val server = FakeCallServer().also { closeables += it }
        val a = identity("a"); val b = identity("b")
        val relay = FakeUdpRelay(server).also { closeables += it }
        relay.reply = { byteArrayOf(0x04, 0x01) }
        val c = UdpRelayClient(a.userKey, b.userKey, "call-1", relay.address, tokens = RelayTokenSource({ null })).also { closeables += it }
        c.start()
        assertTrue(waitFor { c.registered })
        assertEquals(1L, c.legacyRegistersSent)
        assertEquals(1, relay.legacy.size)
    }

    @Test fun `a media leg fetches the call's token before its first register`() {
        val server = FakeCallServer().also { closeables += it }
        val a = identity("a"); val b = identity("b")
        val relay = FakeUdpRelay(server).also { closeables += it }
        relay.reply = { byteArrayOf(0x04, 0x01) }
        val client = CallSignalClient(server.baseUrl, DesktopV2Signer(a))
        val spec = CallMediaSpec(
            "call-7", ByteArray(32) { 1 }, byteArrayOf(1, 2, 3, 4), isCaller = true,
            selfKey = a.userKey, peerKey = b.userKey,
            relayToken = { client.relayToken(a.userKey, b.userKey, "call-7").token },
        )
        val leg = CallMediaLeg(
            spec, DatagramSocket(0, InetAddress.getLoopbackAddress()), null, null,
            relayConfig = CallRelayConfig(udpRelay = relay.address),
        ).also { closeables += it }
        leg.start()
        assertTrue(waitFor { leg.udpRelayClient?.registered == true })
        assertEquals(listOf("issued"), server.relayTokenVerdicts.toList())
        assertTrue(relay.legacy.isEmpty())
        assertEquals("call-7", relay.verified.first().callId)
    }

    @Test fun `a leg with no udp relay still fetches the token`() {
        val server = FakeCallServer().also { closeables += it }
        val a = identity("a"); val b = identity("b")
        val client = CallSignalClient(server.baseUrl, DesktopV2Signer(a))
        val spec = CallMediaSpec(
            "call-8", ByteArray(32) { 1 }, byteArrayOf(1, 2, 3, 4), isCaller = false,
            selfKey = a.userKey, peerKey = b.userKey,
            relayToken = { client.relayToken(a.userKey, b.userKey, "call-8").token },
        )
        val leg = CallMediaLeg(
            spec, DatagramSocket(0, InetAddress.getLoopbackAddress()), null, null,
            relayConfig = CallRelayConfig(),
        ).also { closeables += it }
        leg.start()
        assertTrue(waitFor { server.relayTokenVerdicts.isNotEmpty() })
        assertEquals("issued", server.relayTokenVerdicts.first())
        assertNull(leg.udpRelayClient)
    }

    // ============================================================ fixtures

    /** A `:8089` that verifies §3/§4 trailers against [FakeCallServer]'s issued tokens. */
    private class FakeUdpRelay(val server: FakeCallServer) : AutoCloseable {
        data class Seen(val sender: String, val recipient: String, val callId: String, val tokenHex: String, val counter: Long)
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val address = InetSocketAddress(InetAddress.getLoopbackAddress(), socket.localPort)
        val verified = CopyOnWriteArrayList<Seen>()
        val rejected = CopyOnWriteArrayList<String>()
        val legacy = CopyOnWriteArrayList<String>()
        @Volatile var reply: () -> ByteArray = { byteArrayOf(0x04, 0x01) }
        private val lastCounter = ConcurrentHashMap<String, Long>()
        private val t = Thread {
            val buf = ByteArray(2048)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { socket.receive(p) }.onFailure { return@Thread }
                val d = buf.copyOf(p.length)
                var o = 1
                val rl = d[o++].toInt() and 0xFF; val r = String(d, o, rl); o += rl
                val sl = d[o++].toInt() and 0xFF; val s = String(d, o, sl); o += sl
                val cl = d[o++].toInt() and 0xFF; val c = String(d, o, cl); o += cl
                val payload = d.copyOfRange(o, d.size)
                if (payload.isEmpty()) legacy += s
                else if (payload.size != UdpRelayAuth.TRAILER_LEN || !payload.copyOf(4).contentEquals(UdpRelayAuth.MAGIC)) rejected += "shape"
                else {
                    val idHex = payload.copyOfRange(4, 20).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                    val counter = ByteBuffer.wrap(payload, 20, 8).long
                    val tok = server.relayTokens[idHex]
                    val mac = tok?.let { UdpRelayAuth.mac(it.macKey, d.copyOf(d.size - 16)) }
                    when {
                        tok == null -> rejected += "unknown"
                        !mac!!.contentEquals(d.copyOfRange(d.size - 16, d.size)) -> rejected += "mac"
                        counter <= (lastCounter[idHex] ?: 0L) -> rejected += "replay"
                        tok.identity != s || tok.peer != r || tok.callId != c -> rejected += "binding"
                        else -> { lastCounter[idHex] = counter; verified += Seen(s, r, c, idHex, counter) }
                    }
                }
                val out = reply()
                runCatching { socket.send(DatagramPacket(out, out.size, p.socketAddress)) }
            }
        }.apply { isDaemon = true; start() }

        override fun close() { socket.close() }
    }

    private fun identity(who: String): DesktopIdentity {
        fun bytes(label: String) = MessageDigest.getInstance("SHA-256").digest("relay-auth-test|$who|$label".toByteArray())
        val x = X25519PrivateKeyParameters(bytes("x25519"), 0)
        val e = Ed25519PrivateKeyParameters(bytes("ed25519"), 0)
        return DesktopIdentity(OSHICryptoV2.X25519Pair(x.encoded, x.generatePublicKey().encoded), e.encoded, e.generatePublicKey().encoded)
    }

    private fun waitFor(timeoutMs: Long = 3_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (cond()) return true; Thread.sleep(10) }
        return cond()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
