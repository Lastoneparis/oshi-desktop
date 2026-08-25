package com.oshi.desktop.net

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.DesktopEnvelope
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.store.PrekeyStore
import com.oshi.messenger.network.v2.OSHICryptoV2
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The V2 transport against a real HTTP server that checks our signatures the way the
 * relay does.
 *
 * PARITY.md rows 0.8 to 0.11. Every assertion here is one of the documented ways this
 * transport fails silently — a 401 that reads like clock skew, a pull that loses a whole
 * response to one bad element, a bundle accepted without checking who signed it.
 */
class V2TransportTest {

    private val dir: File = Files.createTempDirectory("oshi-v2-test").toFile()
    private val relay = FakeRelay()
    private val vault = KeyVault.open(File(dir, "v.json"), InMemorySecretStore())
    private val identity = DesktopIdentity.generate()
    private val signer = DesktopV2Signer(identity)
    private val http = V2Http(signer, relay.baseUrl)
    private val prekeys = PrekeyStore(vault)

    @After
    fun tearDown() {
        relay.close()
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ signing

    @Test
    fun `a POST is signed over the exact bytes it sends`() {
        val body = """{"hello":"world","n":1}""".toByteArray()
        http.postJson("/v2/messages", body)

        val rec = relay.last()
        assertEquals("POST", rec.method)
        assertEquals("/v2/messages", rec.path)
        assertTrue("the body arrived altered", rec.body.contentEquals(body))
        assertTrue("signature does not verify over the sent bytes",
            FakeRelay.verifySignature(rec, body))
        assertEquals("the Ed25519 signing key must be published, not the address",
            Base64.getEncoder().encodeToString(identity.signingPub), rec.signingPubKey)
    }

    @Test
    fun `a GET hashes zero bytes, not the empty JSON string`() {
        http.get("/v2/keys/AAAA/count")
        val rec = relay.last()
        assertTrue("a GET must hash ZERO bytes", FakeRelay.verifySignature(rec, ByteArray(0)))
        assertTrue("it must not hash the two-byte empty JSON string",
            !FakeRelay.verifySignature(rec, DesktopV2Signer.EMPTY_JSON_STRING_BODY))
    }

    /**
     * The query is on the URL and NOT in the signature. Get this wrong in either
     * direction and every paged pull 401s, while every unpaged call keeps working — which
     * is exactly the shape that gets diagnosed as a clock problem.
     */
    @Test
    fun `the query string is sent but never signed`() {
        http.get("/v2/messages/AAAA", query = "after=42")
        val rec = relay.last()
        assertEquals("after=42", rec.query)
        assertTrue("the signature must cover the PATH ONLY", FakeRelay.verifySignature(rec, ByteArray(0)))

        // …and the same request signed WITH the query must not verify — otherwise this
        // test would pass whatever the client did.
        val withQuery = FakeRelay.Recorded(
            rec.method, rec.path + "?" + rec.query, rec.query, rec.headers, rec.body,
        )
        assertTrue("signing the query too would also have verified — the test proves nothing",
            !FakeRelay.verifySignature(withQuery, ByteArray(0)))
    }

    /**
     * A base64 identity contains `+`, `/` and `=`, and all three must be percent-encoded
     * in a path segment. A normal URL encoder leaves `=` alone and turns a space into
     * `+`; either breaks every identity-in-path route.
     */
    @Test
    fun `an identity in the path is percent-encoded to the last character`() {
        val client = V2MessagesClient(http)
        val address = "ab+cd/ef=="
        client.pull(address, 0)

        val rec = relay.last()
        assertEquals("/v2/messages/ab%2Bcd%2Fef%3D%3D", rec.path)
        val segment = rec.path.removePrefix("/v2/messages/")
        assertTrue("a raw '+', '/' or '=' survived into the identity segment: $segment",
            segment.none { it == '+' || it == '/' || it == '=' })
        assertTrue("the server must be able to verify what we signed",
            FakeRelay.verifySignature(rec, ByteArray(0)))
    }

    @Test
    fun `the account route hashes the two-byte empty JSON string and carries the address`() {
        relay.responder = { 200 to """{"complete":true,"erased":{"prekeys":true}}""" }
        V2AccountClient(http).deleteAccount()

        val rec = relay.last()
        assertEquals("DELETE", rec.method)
        assertTrue("this route hashes the 2-byte string \"\"",
            FakeRelay.verifySignature(rec, DesktopV2Signer.EMPTY_JSON_STRING_BODY))
        assertEquals("x-oshi-user must be the X25519 ADDRESS", identity.userKey, rec.userHeader)
    }

    @Test
    fun `the address header rides only where it belongs`() {
        http.postJson("/v2/messages", "{}".toByteArray())
        assertNull("a relay send must not carry x-oshi-user", relay.last().userHeader)

        http.delete("/v2/account")
        assertEquals(identity.userKey, relay.last().userHeader)
    }

    // ------------------------------------------------------------------ messages

    @Test
    fun `send returns the ids the relay accepted, and nothing on an error`() {
        relay.responder = { 200 to """{"accepted":["m1","m2"]}""" }
        val client = V2MessagesClient(http)
        assertEquals(listOf("m1", "m2"), client.send(envelope("m1")))

        relay.responder = { 500 to "nope" }
        assertEquals(emptyList<String>(), client.send(envelope("m2")))
    }

    @Test
    fun `send posts the deterministic wire bytes, and they are what was signed`() {
        relay.responder = { 200 to """{"accepted":["m1"]}""" }
        val env = envelope("m1")
        V2MessagesClient(http).send(env)

        val rec = relay.last()
        assertTrue("the posted body is not the envelope's own wire bytes",
            rec.body.contentEquals(env.toWireBytes()))
        assertTrue(FakeRelay.verifySignature(rec, env.toWireBytes()))
    }

    /**
     * One malformed envelope must cost one message, not the whole response. Android's
     * strict reader took an entire array down on a single unknown field, and a pull
     * carries every message that arrived since the last ack.
     */
    @Test
    fun `a malformed envelope in a pull does not take the rest with it`() {
        val good = envelope("good-1").toJson()
        relay.responder = {
            200 to JSONObject()
                .put("maxSeq", 7)
                .put("messages", org.json.JSONArray()
                    .put(good)
                    .put(JSONObject().put("msgId", "broken").put("no", "header or ciphertext"))
                    .put(JSONObject(good.toString()).put("msgId", "good-2")))
                .toString()
        }
        val pull = V2MessagesClient(http).pull(identity.userKey, 0)
        assertNotNull(pull)
        assertEquals(listOf("good-1", "good-2"), pull!!.messages.map { it.msgId })
        assertEquals(7L, pull.maxSeq)
    }

    /** "Failed" and "nothing new" must not look alike: one may advance a cursor, the other may not. */
    @Test
    fun `a failed pull is null, an empty pull is an empty list`() {
        relay.responder = { 503 to "" }
        assertNull(V2MessagesClient(http).pull(identity.userKey, 0))

        relay.responder = { 200 to """{"messages":[],"maxSeq":12}""" }
        val empty = V2MessagesClient(http).pull(identity.userKey, 0)
        assertNotNull(empty)
        assertEquals(0, empty!!.messages.size)
        assertEquals(12L, empty.maxSeq)
    }

    @Test
    fun `ack posts the sequence number it was given`() {
        relay.responder = { 200 to "{}" }
        assertTrue(V2MessagesClient(http).ack(identity.userKey, 99))
        val rec = relay.last()
        assertTrue(rec.path.endsWith("/ack"))
        assertEquals(99L, JSONObject(rec.bodyText).getLong("upToSeq"))
        assertTrue(FakeRelay.verifySignature(rec, rec.body))
    }

    // ------------------------------------------------------------------ keys

    @Test
    fun `publish sends a bundle whose SPK signature verifies against the published signing key`() {
        relay.responder = { 200 to """{"remaining":20}""" }
        val remaining = V2KeysClient(http, identity, prekeys).publish(oneTimeCount = 3)
        assertEquals(20, remaining)

        val body = JSONObject(relay.last().bodyText)
        assertEquals(identity.userKey, body.getString("userKey"))
        assertEquals("identityKey IS the address", identity.userKey, body.getString("identityKey"))

        val spk = body.getJSONObject("signedPreKey")
        val spkPub = Base64.getDecoder().decode(spk.getString("key"))
        val sig = Base64.getDecoder().decode(spk.getString("signature"))
        val signingKey = Base64.getDecoder().decode(body.getString("signingKey"))
        assertTrue("the SPK signature must be Ed25519 over the RAW 32 bytes of the SPK public key",
            DesktopIdentity.verify(spkPub, sig, signingKey))

        assertEquals(3, body.getJSONArray("oneTimePreKeys").length())
        assertTrue("publishing must be recorded", prekeys.hasPublished())
    }

    @Test
    fun `fetchBundle accepts a well-formed bundle`() {
        val peer = PeerBundle()
        relay.responder = { 200 to peer.json().toString() }
        val bundle = V2KeysClient(http, identity, prekeys).fetchBundle(peer.address)
        assertNotNull("a valid bundle was rejected", bundle)
        assertTrue(bundle!!.identityKey.contentEquals(peer.identity.pub))
        assertEquals(peer.spkId, bundle.signedPreKeyId)
        assertNotNull(bundle.oneTimePreKey)
    }

    /** A bundle whose SPK is signed by someone else is a key substitution. */
    @Test
    fun `fetchBundle rejects a bad signed-prekey signature`() {
        val peer = PeerBundle()
        relay.responder = {
            val j = peer.json()
            j.getJSONObject("signedPreKey").put("signature",
                Base64.getEncoder().encodeToString(ByteArray(64) { 1 }))
            200 to j.toString()
        }
        assertNull(V2KeysClient(http, identity, prekeys).fetchBundle(peer.address))
    }

    /**
     * TOFU: in OSHI the address IS the X25519 identity, so a bundle answering with a
     * different identity key is whoever served it substituting their own.
     */
    @Test
    fun `fetchBundle rejects an identity key that is not the address asked for`() {
        val peer = PeerBundle()
        val other = OSHICryptoV2.generateX25519()
        relay.responder = {
            200 to peer.json().put("identityKey", Base64.getEncoder().encodeToString(other.pub)).toString()
        }
        assertNull(V2KeysClient(http, identity, prekeys).fetchBundle(peer.address))
    }

    /** A drained one-time-prekey pool is normal, not a failure: X3DH proceeds without one. */
    @Test
    fun `fetchBundle accepts a bundle with no one-time prekey left`() {
        val peer = PeerBundle()
        relay.responder = { 200 to peer.json().apply { remove("oneTimePreKey") }.toString() }
        val bundle = V2KeysClient(http, identity, prekeys).fetchBundle(peer.address)
        assertNotNull(bundle)
        assertNull(bundle!!.oneTimePreKey)
        assertNull(bundle.oneTimePreKeyId)
    }

    @Test
    fun `remainingCount reads the server's number`() {
        relay.responder = { 200 to """{"remaining":7}""" }
        assertEquals(7, V2KeysClient(http, identity, prekeys).remainingCount())
        relay.responder = { 404 to "" }
        assertNull(V2KeysClient(http, identity, prekeys).remainingCount())
    }

    // ------------------------------------------------------------------ account

    @Test
    fun `a partial deletion is a receipt, not a failure`() {
        relay.responder = {
            207 to """{"complete":false,"erased":{"prekeys":true,"relay":{"envelopes":3},
                "sync":{"items":1},"blobs":{"deleted":2,"outboundLeft":1}},
                "remote":{"ipfs":{"ok":false},"push":{"ok":true}}}"""
        }
        val receipt = V2AccountClient(http).deleteAccount().getOrThrow()
        assertTrue("207 must not be treated as an error", !receipt.complete)
        assertEquals(3, receipt.relayEnvelopes)
        assertEquals(1, receipt.outboundBlobsLeft)
        assertEquals(listOf("ipfs"), receipt.unreachable)

        relay.responder = { 500 to "" }
        assertTrue(V2AccountClient(http).deleteAccount().isFailure)
    }

    // ------------------------------------------------------------------ helpers

    private fun envelope(id: String) = DesktopEnvelope(
        msgId = id,
        from = identity.userKey,
        to = "PEER+key/with=padding",
        header = ByteArray(40) { it.toByte() },
        ciphertext = ByteArray(32) { (it * 3).toByte() },
        ts = 1787601120588L,
    )

    /** A peer's published bundle, correctly signed by that peer. */
    private class PeerBundle {
        val identity = OSHICryptoV2.generateX25519()
        val signing = DesktopIdentity.generate()
        val spk = OSHICryptoV2.generateX25519()
        val spkId = "spk-1"
        val opk = OSHICryptoV2.generateX25519()
        val address: String get() = Base64.getEncoder().encodeToString(identity.pub)

        fun json(): JSONObject {
            val b64 = Base64.getEncoder()
            return JSONObject()
                .put("identityKey", b64.encodeToString(identity.pub))
                .put("signingKey", b64.encodeToString(signing.signingPub))
                .put("signedPreKey", JSONObject()
                    .put("keyId", spkId)
                    .put("key", b64.encodeToString(spk.pub))
                    .put("signature", b64.encodeToString(signing.sign(spk.pub))))
                .put("oneTimePreKey", JSONObject()
                    .put("keyId", "opk-1")
                    .put("key", b64.encodeToString(opk.pub)))
        }
    }
}
