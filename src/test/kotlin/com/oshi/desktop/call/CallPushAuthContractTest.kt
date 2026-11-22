package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.messenger.network.v2.OSHICryptoV2
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CALL_PUSH_AUTH_DESKTOP_2026_09_23__ `docs/CALL_PUSH_AUTH_CONTRACT.md` (STABLE 1.0).
 *
 * Two halves. The §6 vectors pin the BYTES (canonical string, signature, pubkey header) with
 * the contract's own fixed seed and timestamp, through the same [DesktopV2Signer] the `/v2`
 * transport uses — the server binds that key to the identity, so there is no second key.
 * The lane tests pin the BINDING: every request a real call makes (ring, poll, answer, end,
 * the sibling push) is signed by the key bound to the identity the request claims.
 */
class CallPushAuthContractTest {

    // ------------------------------------------------------------------ §6 fixtures

    private val seed = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
    private val vectorIdentity: DesktopIdentity = run {
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        DesktopIdentity(
            OSHICryptoV2.X25519Pair(ByteArray(32), ByteArray(32) { 0xAB.toByte() }),
            priv.encoded,
            priv.generatePublicKey().encoded,
        )
    }
    private val ts = 1_790_000_000_000L
    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private val servers = ArrayList<AutoCloseable>()

    @After fun tearDown() = servers.forEach { runCatching { it.close() } }

    @Test fun `the vector identity is the contract's`() {
        assertEquals("q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s=", vectorIdentity.userKey)
        assertEquals("q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s", CallSignalClient.base64Url(vectorIdentity.userKey))
        val h = DesktopV2Signer(vectorIdentity).sign("GET", "/", ByteArray(0), timestampMs = ts)
        assertEquals("ebVWLo/mVPlAeLES6KmLp5AfhTrmlb7X4OORC60ElmQ=", h["x-oshi-signing-pubkey"])
        assertEquals("1790000000000", h["x-oshi-timestamp"])
    }

    @Test fun `V1 POST api-call-signal`() {
        val body = ("{\"sender\":\"q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s\"," +
            "\"recipient\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"signal\":\"AA==\"," +
            "\"callId\":\"00000000-0000-4000-8000-000000000001\",\"type\":\"callEnd\"}").toByteArray()
        val signer = DesktopV2Signer(vectorIdentity)
        assertEquals("7eb060c7b241f3056a6070b998167d6f3063c3d7ea2da86a1da4f0b746602b81", DesktopV2Signer.sha256Hex(body))
        assertEquals(
            "POST\n/api/call/signal\n7eb060c7b241f3056a6070b998167d6f3063c3d7ea2da86a1da4f0b746602b81\n1790000000000",
            signer.canonicalString("POST", "/api/call/signal", body, "1790000000000"),
        )
        assertEquals(
            "t40jSdP7dJC4Tfa6E0+6j6m4LX9tCbtuYf6Jc6ryS4cCcPDzOuW/AGb2nwIvDyuC48uTwno0oWxfMHnkmdW2CQ==",
            signer.sign("POST", "/api/call/signal", body, timestampMs = ts)["x-oshi-signature"],
        )
    }

    @Test fun `V2 GET signals — through the real client, on the wire`() {
        val captured = ConcurrentHashMap<String, String>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                captured["path"] = ex.requestURI.rawPath
                captured["query"] = ex.requestURI.rawQuery.orEmpty()
                for (h in listOf("x-oshi-signature", "x-oshi-timestamp", "x-oshi-signing-pubkey", "User-Agent")) {
                    ex.requestHeaders.getFirst(h)?.let { captured[h] = it }
                }
                val b = "[]".toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        servers += AutoCloseable { http.stop(0) }
        val client = CallSignalClient(
            "http://127.0.0.1:${http.address.port}",
            DesktopV2Signer(vectorIdentity),
            clockMs = { ts },
        )
        assertEquals(emptyList<CallSignalEnvelope>(), client.poll(vectorIdentity.userKey, "dev-1"))
        assertEquals("/api/call/signals/q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s", captured["path"])
        assertEquals("deviceId=dev-1", captured["query"])
        assertEquals(
            "AfMWbSytNqos3cJ8kiJO2Vh84C7zIVOiHNhQZAG0tr8yZ3RQbu1ZFb0Vp78clm0EV9FB+Qj3bXAkQA7YCobUDg==",
            captured["x-oshi-signature"],
        )
        assertEquals("1790000000000", captured["x-oshi-timestamp"])
        assertEquals("ebVWLo/mVPlAeLES6KmLp5AfhTrmlb7X4OORC60ElmQ=", captured["x-oshi-signing-pubkey"])
        assertTrue("contract §7 User-Agent, got ${captured["User-Agent"]}", captured["User-Agent"].orEmpty().startsWith("OSHI-Desktop/"))
    }

    @Test fun `V3 and V4 — the signer reproduces the remaining canonical strings`() {
        val signer = DesktopV2Signer(vectorIdentity)
        assertEquals(
            "igxm0cDqwPYMtl7uE98/RLHFkyol29VKmoMiFEv+nqJz+N6vnHOBuB8b+Htg8Mcsqv0Wt9nTRzIKY3e8NvfBCQ==",
            signer.sign("GET", "/voip/", ByteArray(0), timestampMs = ts)["x-oshi-signature"],
        )
        val body = "{\"publicKey\":\"q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s=\",\"deviceId\":\"dev-1\"}".toByteArray()
        assertEquals("159004baa70dfde3466d8846958f44260c76cdbf79853c4d4179be14175dcaad", DesktopV2Signer.sha256Hex(body))
        assertEquals(
            "M5hYgsOthXPkLqfvNK4yZfjW1vE5CNA1Puz8I/oXMWeJZ6ydy9FWtZsqDssG3xA86yzboJ1AsPqTyA1EnytoAQ==",
            signer.sign("POST", "/push/unregister-device", body, timestampMs = ts)["x-oshi-signature"],
        )
        assertEquals(emptyHash, DesktopV2Signer.sha256Hex(ByteArray(0)))
    }

    @Test fun `the signed path is the full path requested`() {
        val c = CallSignalClient("http://127.0.0.1:1", null)
        assertEquals("/api/call/signal", c.signedPathFor("/api/call/signal"))
        assertEquals("http://127.0.0.1:1/push/signal-answered", c.signalAnsweredUrl)
    }

    // ------------------------------------------------------------------ binding, end to end

    private fun fake(): FakeCallServer = FakeCallServer().also { servers += it }

    private fun lane(server: FakeCallServer, identity: DesktopIdentity, signer: DesktopIdentity = identity): CallLane {
        val deviceId = "dev-" + UUID.randomUUID().toString().take(8)
        return CallLane(
            myAddress = identity.userKey,
            myPrivateKey = identity.identity.priv,
            transport = CallSignalClient(server.baseUrl, DesktopV2Signer(signer)),
            deviceId = deviceId,
            machine = CallStateMachine(identity.userKey, null, deviceId),
        )
    }

    private fun pollUntil(lane: CallLane, want: (CallState) -> Boolean): CallState {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            lane.pollOnce(); if (want(lane.state)) return lane.state; Thread.sleep(40)
        }
        return lane.state
    }

    @Test fun `every request of a real call is signed by the key bound to the identity it claims`() {
        val server = fake()
        val a = DesktopIdentity.generate().also(server::bind)
        val b = DesktopIdentity.generate().also(server::bind)
        val caller = lane(server, a)
        val callee = lane(server, b)

        assertTrue(caller.siblingPushUrl!!.startsWith(server.baseUrl))
        assertTrue(caller.call(b.userKey) is CallLane.Dialled.Ringing)
        assertEquals(CallState.RINGING, pollUntil(callee) { it == CallState.RINGING })
        callee.answer()
        pollUntil(caller) { it != CallState.RINGING }
        caller.hangUp()
        pollUntil(callee) { it == CallState.IDLE || it == CallState.ENDED }

        // The sibling push runs on a daemon thread.
        val deadline = System.currentTimeMillis() + 5_000
        while (server.signalAnsweredBodies.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertTrue(server.bindingVerdicts.size >= 4)
        assertEquals("call server: every request bound", emptyList<String>(), server.bindingVerdicts.filter { it != "bound" })
        assertTrue("/end was called", server.seenPaths.any { it == "POST" to "/end" })
        assertEquals(listOf("POST" to "/signal-answered"), server.seenPushPaths.toList())
        assertEquals(listOf("bound"), server.pushVerdicts.toList())
        assertEquals(b.userKey, JSONObject(server.signalAnsweredBodies.single()).getString("identity"))
    }

    @Test fun `end carries the sender it must prove`() {
        val server = fake()
        val a = DesktopIdentity.generate().also(server::bind)
        val client = CallSignalClient(server.baseUrl, DesktopV2Signer(a))
        assertTrue(client.endCall("call-1", a.userKey))
        assertEquals(listOf("bound"), server.bindingVerdicts.toList())
    }

    @Test fun `a key bound to another identity is refused, never retried unsigned`() {
        val server = fake()
        val victim = DesktopIdentity.generate().also(server::bind)
        val attacker = DesktopIdentity.generate().also(server::bind)
        val forged = CallSignalClient(server.baseUrl, DesktopV2Signer(attacker))
        assertEquals(null, forged.poll(victim.userKey, "dev-x"))
        assertEquals(listOf("mismatch"), server.bindingVerdicts.toList())
        assertFalse(server.signatureVerdicts.contains("unsigned"))
        assertEquals(403, forged.signalAnswered(victim.userKey, "c", "dev-x"))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

}
