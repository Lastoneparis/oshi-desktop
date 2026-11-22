package com.oshi.desktop.net

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.messenger.network.v2.OSHICryptoV2
import com.sun.net.httpserver.HttpServer
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * __DESKTOP_MESSAGE_PUSH_2026_09_23__ The wake push after a v2 send: signed per
 * docs/CALL_PUSH_AUTH_CONTRACT.md §1 (row 4, claimed identity = data.senderPublicKey), generic
 * content, ids only.
 */
class MessagePushClientTest {

    private val seed = ByteArray(32) { (it + 1).toByte() }   // the contract §6 seed 01..20
    private val identity: DesktopIdentity = run {
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        DesktopIdentity(
            OSHICryptoV2.X25519Pair(ByteArray(32), ByteArray(32) { 0xAB.toByte() }),
            priv.encoded, priv.generatePublicKey().encoded,
        )
    }
    private val me = identity.userKey
    private val peer = "IiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiI="

    private class Hit(val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun server(hits: MutableList<Hit>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val h = listOf("x-oshi-signature", "x-oshi-timestamp", "x-oshi-signing-pubkey", "User-Agent")
                    .mapNotNull { k -> ex.requestHeaders.getFirst(k)?.let { k to it } }.toMap()
                hits += Hit(ex.requestURI.rawPath, h, ex.requestBody.readBytes())
                val b = "{\"success\":true}".toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }

    private fun verify(hit: Hit) {
        val ts = hit.headers.getValue("x-oshi-timestamp")
        val canonical = "POST\n/push/send-push\n${DesktopV2Signer.sha256Hex(hit.body)}\n$ts"
        assertEquals("ebVWLo/mVPlAeLES6KmLp5AfhTrmlb7X4OORC60ElmQ=", hit.headers["x-oshi-signing-pubkey"])
        assertTrue(
            "signature must cover the exact body bytes and the path as sent",
            DesktopIdentity.verify(
                canonical.toByteArray(), Base64.getDecoder().decode(hit.headers.getValue("x-oshi-signature")),
                identity.signingPub,
            ),
        )
        assertTrue(hit.headers.getValue("User-Agent").startsWith("OSHI-Desktop/"))
    }

    @Test fun `1to1 wake is signed, generic and carries the sender key for local name resolution`() {
        val hits = CopyOnWriteArrayList<Hit>()
        val s = server(hits)
        try {
            val c = MessagePushClient("http://127.0.0.1:${s.address.port}", DesktopV2Signer(identity), me, synchronous = true)
            c.wakeDirect(peer, "M-1")
            assertEquals(1, hits.size)
            val hit = hits[0]
            assertEquals("/push/send-push", hit.path)
            verify(hit)
            val o = JSONObject(String(hit.body))
            assertEquals(peer, o.getString("recipientPublicKey"))
            assertEquals("message", o.getString("type"))
            assertEquals("New Message", o.getString("title"))
            val d = o.getJSONObject("data")
            assertEquals(me, d.getString("senderPublicKey"))   // contract §3 row 4 claimed identity
            assertEquals(me, d.getString("senderKey"))
            assertEquals("M-1", d.getString("messageId"))
            assertEquals("1", d.getString("v2"))
            // Only keys the server allow-list keeps; no name, no preview.
            val allowed = setOf("type", "senderPublicKey", "senderKey", "messageId", "groupId", "v2", "bodyLocKey", "titleLocKey")
            assertTrue(d.keySet().toString(), allowed.containsAll(d.keySet()))
        } finally { s.stop(0) }
    }

    @Test fun `group wake goes to each reached member except us, with the groupId and a generic title`() {
        val hits = CopyOnWriteArrayList<Hit>()
        val s = server(hits)
        try {
            val c = MessagePushClient("http://127.0.0.1:${s.address.port}", DesktopV2Signer(identity), me, synchronous = true)
            c.wakeGroup(listOf(peer, me, peer), "5E2A9C1B-7D3F-4B8A-9E6C-0F1D2A3B4C5D", "G-1")
            assertEquals(1, hits.size)
            verify(hits[0])
            val o = JSONObject(String(hits[0].body))
            assertEquals("group_message", o.getString("type"))
            assertEquals("OSHI", o.getString("title"))
            assertFalse(String(hits[0].body).contains("Team"))
            val d = o.getJSONObject("data")
            assertEquals("group_message", d.getString("type"))
            assertEquals("5E2A9C1B-7D3F-4B8A-9E6C-0F1D2A3B4C5D", d.getString("groupId"))
            assertEquals(me, d.getString("senderPublicKey"))
        } finally { s.stop(0) }
    }

    @Test fun `an unreachable push service never throws into the send path`() {
        val c = MessagePushClient("http://127.0.0.1:1", DesktopV2Signer(identity), me, synchronous = true)
        c.wakeDirect(peer, "M-2")   // logs, returns
        assertEquals(-1, c.post(c.directBody(peer, "M-3")))
    }
}
