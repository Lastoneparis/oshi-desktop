package com.oshi.desktop.mesh

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The mesh wire contract, as executable guards.
 *
 * Two kinds of evidence live here and they are not equally strong, so they are labelled:
 *
 *  - **CAPTURED** — bytes emitted by the SHIPPED Android client (OSHI 1.x on a real
 *    phone), recorded off a real TCP mesh connection from this machine on 2026-08-24.
 *    A test that re-emits them byte for byte is the only parity claim in this file that
 *    does not depend on reading a sibling implementation correctly. The public key and
 *    display name were replaced with same-length placeholders; nothing else — not one
 *    byte of structure, key order, escaping or number formatting — was touched.
 *  - **DERIVED** — read out of the iOS and Android sources at test time. Weaker (it
 *    trusts a regex over source, not a running client) but it is what turns a future
 *    change in either tree into a red desktop build instead of a silent divergence.
 */
class MeshWireFormatTest {

    // ------------------------------------------------------------ CAPTURED

    /**
     * The three frames a shipped Android client sends within a second of a mesh peer
     * connecting: its identity, a group sync request, and its 2-hop gossip announce.
     */
    @Test
    fun `parses every frame the shipped Android client actually sent`() {
        val identity = MeshMessage.parse(hex(ANDROID_IDENTITY_EXCHANGE))
        assertNotNull("IDENTITY_EXCHANGE from a real phone failed to parse", identity)
        assertEquals("IDENTITY_EXCHANGE", identity!!.type)
        assertEquals("android", identity.platform)
        assertEquals("", identity.recipientPublicKey)
        assertEquals(1, identity.maxHops)
        // The inner payload is a JSON STRING, not an object, on both platforms.
        val inner = JSONObject(identity.payload)
        assertEquals(identity.senderPublicKey, inner.getString("publicKey"))
        assertEquals("android", inner.getString("platform"))

        val group = MeshMessage.parse(hex(ANDROID_GROUP_UPDATE))
        assertNotNull(group)
        assertEquals("GROUP_UPDATE", group!!.type)
        assertEquals(50, group.maxHops)
        assertEquals(listOf(group.senderPublicKey), group.seenBy)
        assertTrue("emoji payload lost", group.payload.startsWith("\uD83D\uDCE2GROUP_UPDATE"))

        val announce = MeshMessage.parse(hex(ANDROID_IDENTITY_ANNOUNCE))
        assertNotNull(announce)
        assertEquals("IDENTITY_ANNOUNCE", announce!!.type)
        val ann = JSONObject(announce.payload)
        assertEquals(2, ann.getInt("ttl"))
        assertEquals(0, ann.getInt("hops"))
        assertEquals(announce.senderPublicKey, ann.getString("originator"))
    }

    /**
     * THE STRONGEST GUARD IN THIS FILE. Parse a real Android frame, re-emit it from our
     * own writer, and require the bytes back exactly.
     *
     * This is what "wire compatible" has to mean, and it catches in one assertion every
     * failure the other tests check one at a time: a reordered key, a `1.7876011E12`
     * timestamp, an over-escaped emoji, a dropped empty `recipientPublicKey`, a space
     * after a colon. It also caught the thing that made this test worth writing — org.json
     * on Maven emits keys in HashMap order, so `JSONObject(...).toString()` would fail
     * this on some JVMs and pass on others.
     */
    @Test
    fun `re-emits the shipped Android frames byte for byte`() {
        for ((label, fixture) in listOf(
            "IDENTITY_EXCHANGE" to ANDROID_IDENTITY_EXCHANGE,
            "GROUP_UPDATE" to ANDROID_GROUP_UPDATE,
            "IDENTITY_ANNOUNCE" to ANDROID_IDENTITY_ANNOUNCE,
        )) {
            val bytes = hex(fixture)
            val parsed = MeshMessage.parse(bytes) ?: error("$label did not parse")
            assertArrayEquals(
                "$label: desktop re-emit differs from the bytes Android sent\n" +
                    "  android: ${String(bytes)}\n" +
                    "  desktop: ${String(parsed.toWireBytes())}",
                bytes, parsed.toWireBytes(),
            )
        }
    }

    // ------------------------------------------------------------ DERIVED

    /**
     * iOS decodes this type with a bare `JSONDecoder()` and every property is
     * non-Optional, so a key we omit is a frame the iPhone silently drops. This reads the
     * Swift struct and requires our emitter to produce exactly its stored properties —
     * no more (an extra key is harmless but means we drifted), no fewer (a missing key is
     * a dropped message).
     */
    @Test
    fun `emits exactly the keys the iOS Codable struct requires`() {
        val swift = File(iosRoot, "CrossPlatformMesh.swift")
        assumeTrue("iOS tree not present on this machine", swift.isFile)
        val body = swift.readText()
            .substringAfter("struct CrossPlatformMessage: Codable {")
            .substringBefore("func toJSON()")
        val declared = Regex("""^\s*(?:let|var)\s+(\w+)\s*:""", RegexOption.MULTILINE)
            .findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(
            "the Swift struct's stored properties changed — the desktop emitter must follow",
            declared, MeshMessage.REQUIRED_KEYS,
        )
        assertEquals(
            "emitted key set does not match the Swift struct",
            declared.toSet(), emittedKeys(sample()),
        )
    }

    /** Same guard against the other shipped platform's data class. */
    @Test
    fun `emits exactly the keys the Android data class declares`() {
        val kt = File(androidRoot, "app/src/main/java/com/oshi/messenger/network/mesh/CrossPlatformMesh.kt")
        assumeTrue("Android tree not present on this machine", kt.isFile)
        val body = kt.readText()
            .substringAfter("data class CrossPlatformMessage(")
            .substringBefore(")")
        val declared = Regex("""^\s*val\s+(\w+)\s*:""", RegexOption.MULTILINE)
            .findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(declared, MeshMessage.REQUIRED_KEYS)
    }

    // ------------------------------------------------------------ emitter rules

    @Test
    fun `a broadcast emits an empty recipient rather than omitting the key`() {
        val json = JSONObject(String(sample(recipient = "").toWireBytes()))
        assertTrue("recipientPublicKey must be PRESENT even when empty", json.has("recipientPublicKey"))
        assertEquals("", json.getString("recipientPublicKey"))
        assertTrue("no key may be JSON null", MeshMessage.REQUIRED_KEYS.none { json.isNull(it) })
    }

    @Test
    fun `timestamp is emitted as integer milliseconds`() {
        val ts = 1787601120588.0
        val wire = String(sample(ts = ts).toWireBytes())
        assertTrue("timestamp must not be exponential or fractional: $wire",
            wire.contains("\"timestamp\":1787601120588,"))
        // …and the value must be MILLISECONDS, not seconds and not the Apple 2001 epoch.
        assertEquals(ts, JSONObject(wire).getDouble("timestamp"), 0.0)
    }

    @Test
    fun `emit is deterministic across repeats`() {
        val m = sample()
        val first = m.toWireBytes()
        repeat(50) { assertArrayEquals("emit is not deterministic", first, m.toWireBytes()) }
    }

    /**
     * The escaper case that cost this project a message: a caption of `C:\Users` through a
     * hand-rolled escaper that handled quotes and newlines but not backslashes produced
     * invalid JSON — iOS stored the whole base64 photo as a text bubble, Android dropped
     * the message. This asserts the round trip through the SHARED escaper instead.
     */
    @Test
    fun `escapes backslashes quotes and control characters`() {
        val nasty = "C:\\Users\\hugo — say \"hi\"\n\ttab\u0007bell"
        val wire = sample(payload = nasty, name = nasty).toWireBytes()
        val back = MeshMessage.parse(wire) ?: error("our own escaped frame did not re-parse")
        assertEquals(nasty, back.payload)
        assertEquals(nasty, back.senderName)
    }

    @Test
    fun `an emoji payload survives as UTF-8, not as an escape`() {
        val m = sample(payload = "\uD83D\uDCE2GROUP_UPDATE\uD83D\uDCE2{}")
        val wire = m.toWireBytes()
        assertTrue("emoji must go on the wire as UTF-8 bytes",
            String(wire, Charsets.UTF_8).contains("\uD83D\uDCE2GROUP_UPDATE"))
        assertEquals(m.payload, MeshMessage.parse(wire)!!.payload)
    }

    // ------------------------------------------------------------ reader rules

    @Test
    fun `parse tolerates an unknown key a future platform adds`() {
        val withExtra = String(sample().toWireBytes())
            .replaceFirst("{", "{\"edited\":true,\"reactions\":[1,2,3],")
        val m = MeshMessage.parse(withExtra.toByteArray())
        assertNotNull("an unknown field must not cost us the frame", m)
        assertEquals("hello", m!!.payload)
    }

    @Test
    fun `parse returns null rather than throwing on garbage and on a missing id`() {
        assertNull(MeshMessage.parse("not json at all".toByteArray()))
        assertNull(MeshMessage.parse(ByteArray(0)))
        val noId = String(sample().toWireBytes()).replaceFirst("\"id\":", "\"notId\":")
        assertNull("a frame with no id cannot be de-duplicated and must be refused",
            MeshMessage.parse(noId.toByteArray()))
    }

    @Test
    fun `seenBy round trips including the empty case`() {
        assertEquals(emptyList<String>(), MeshMessage.parse(sample().toWireBytes())!!.seenBy)
        val two = sample(seenBy = listOf("a+/=", "b"))
        assertEquals(listOf("a+/=", "b"), MeshMessage.parse(two.toWireBytes())!!.seenBy)
    }

    /**
     * `relayedBy` is the only mutation on the wire object, and both of its effects are
     * load-bearing: without the hop bump a message never reaches `maxHops`, and without
     * the `seenBy` entry the same node relays the same message forever.
     */
    @Test
    fun `relayedBy bumps the hop count and records this node`() {
        val relayed = sample().relayedBy("ME=")
        assertEquals(1, relayed.hopCount)
        assertEquals(listOf("ME="), relayed.seenBy)
        assertEquals("relaying must not touch anything else", sample().id, relayed.id)
    }

    // ------------------------------------------------------------ helpers

    private fun sample(
        payload: String = "hello",
        recipient: String = "PEER+key/withPadding=",
        ts: Double = 1787601120588.0,
        name: String = "Desktop",
        seenBy: List<String> = emptyList(),
    ) = MeshMessage(
        id = "7b4ed673-8e6c-4452-9c8b-443785b14266",
        type = MeshProtocol.TYPE_TEXT,
        senderPublicKey = "SENDER+key/withPadding=",
        senderName = name,
        recipientPublicKey = recipient,
        payload = payload,
        timestamp = ts,
        hopCount = 0,
        maxHops = MeshProtocol.MAX_HOPS,
        seenBy = seenBy,
        platform = MeshProtocol.PLATFORM,
    )

    private fun emittedKeys(m: MeshMessage): Set<String> =
        JSONObject(String(m.toWireBytes())).keys().asSequence().toSet()

    private val androidRoot = File(System.getProperty("oshi.android.root") ?: "../OSHI-Android")
    private val iosRoot = File(System.getProperty("oshi.ios.root") ?: "../OSHI")

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    companion object {
        private const val ANDROID_IDENTITY_EXCHANGE =
            "7b226964223a2237623465643637332d386536632d343435322d396338622d343433373835623134323636222c227479" +
            "7065223a224944454e544954595f45584348414e4745222c2273656e6465725075626c69634b6579223a224631787455" +
            "7233416e64726f6964506565724b6579466f72506172697479546573747330303030303030303d222c2273656e646572" +
            "4e616d65223a2270656572222c22726563697069656e745075626c69634b6579223a22222c227061796c6f6164223a22" +
            "7b5c227075626c69634b65795c223a5c2246317874557233416e64726f6964506565724b6579466f7250617269747954" +
            "6573747330303030303030303d5c222c5c226e616d655c223a5c22706565725c222c5c22706c6174666f726d5c223a5c" +
            "22616e64726f69645c227d222c2274696d657374616d70223a313738373630313132303538382c22686f70436f756e74" +
            "223a302c226d6178486f7073223a312c227365656e4279223a5b5d2c22706c6174666f726d223a22616e64726f696422" +
            "7d"

        private const val ANDROID_GROUP_UPDATE =
            "7b226964223a2235643238393363322d366635392d343138362d616430642d633438303063376561323261222c227479" +
            "7065223a2247524f55505f555044415445222c2273656e6465725075626c69634b6579223a2246317874557233416e64" +
            "726f6964506565724b6579466f72506172697479546573747330303030303030303d222c2273656e6465724e616d6522" +
            "3a2270656572222c22726563697069656e745075626c69634b6579223a225130465156465653525652465531524c5256" +
            "6c665a47567a613352766346397759584a7064486c664d4441774d4441774d44413d222c227061796c6f6164223a22f0" +
            "9f93a247524f55505f555044415445f09f93a27b5c22747970655c223a5c2273796e635f726571756573745c222c5c22" +
            "7265717565737465724b65795c223a5c2246317874557233416e64726f6964506565724b6579466f7250617269747954" +
            "6573747330303030303030303d5c227d222c2274696d657374616d70223a313738373630313132303538382c22686f70" +
            "436f756e74223a302c226d6178486f7073223a35302c227365656e4279223a5b2246317874557233416e64726f696450" +
            "6565724b6579466f72506172697479546573747330303030303030303d225d2c22706c6174666f726d223a22616e6472" +
            "6f6964227d"

        private const val ANDROID_IDENTITY_ANNOUNCE =
            "7b226964223a2261656537626363632d376131612d343135392d626539612d376562313765353065633666222c227479" +
            "7065223a224944454e544954595f414e4e4f554e4345222c2273656e6465725075626c69634b6579223a224631787455" +
            "7233416e64726f6964506565724b6579466f72506172697479546573747330303030303030303d222c2273656e646572" +
            "4e616d65223a2270656572222c22726563697069656e745075626c69634b6579223a22222c227061796c6f6164223a22" +
            "7b5c226f726967696e61746f725c223a5c2246317874557233416e64726f6964506565724b6579466f72506172697479" +
            "546573747330303030303030303d5c222c5c22646973706c61794e616d655c223a5c22706565725c222c5c22706c6174" +
            "666f726d5c223a5c22616e64726f69645c222c5c22686f70735c223a302c5c2274746c5c223a327d222c2274696d6573" +
            "74616d70223a313738373630313132303539302c22686f70436f756e74223a302c226d6178486f7073223a322c227365" +
            "656e4279223a5b5d2c22706c6174666f726d223a22616e64726f6964227d"

    }
}
