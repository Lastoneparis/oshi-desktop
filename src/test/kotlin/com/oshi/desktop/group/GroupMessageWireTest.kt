package com.oshi.desktop.group

import com.oshi.desktop.msg.WireClock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * The group MESSAGE payload and the v2 fan-out — PARITY.md row 0.17.
 *
 * References, all shipped source:
 *  - `OSHI/GroupMessaging.swift:2761-2762` (bare `JSONEncoder()`, then base64)
 *  - `OSHI-Android/.../service/GroupManager.kt:2294-2318` (the same object, key by key)
 *  - `OSHI-Android/.../network/v2/V2MessageRouter.kt:17-21` (bare base64, no prefix, and
 *    what a dropped `groupId` costs)
 *  - `OSHI/V2MessageRouter.swift:710-739` (the fan-out loop)
 *  - `OSHI-Android/.../service/GroupManager.kt:362-373` (the caption precedence)
 */
class GroupMessageWireTest {

    private val APPLE_OFFSET = 978307200.0
    private val tsMs = 1786622400000L                       // 2026-08-13T12:00:00Z
    private val groupId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val adminKey = "AAAAadminkey"
    private val memberKey = "BBBBmemberkey"

    private fun msg(
        body: String = "hello",
        mediaType: GroupMessageWire.GroupMediaType? = null,
        caption: String? = null,
    ) = GroupMessageWire.GroupMessagePayload(
        messageId = "msg-1",
        groupId = groupId,
        senderPublicKey = adminKey,
        body = body,
        timestampUnixMillis = tsMs,
        mediaType = mediaType,
        plaintextContent = caption,
    )

    // ================================================================= the message JSON

    /**
     * `timestamp` is a `Date` read by a BARE `JSONEncoder()` (`swift:2761`) — Apple-epoch
     * SECONDS. Not ISO-8601, which is what the group DEFINITION twenty lines away uses, and
     * not Unix millis. Both fields are called some flavour of `timestamp`.
     */
    @Test
    fun `group message timestamp is Apple-epoch seconds, not ISO-8601`() {
        val o = JSONObject(GroupMessageWire.encode(msg()))
        assertEquals((tsMs / 1000.0) - APPLE_OFFSET, o.getDouble("timestamp"), 0.001)
        assertTrue("a Unix-millis leak would be ~1.79e12", o.getDouble("timestamp") < 1e10)
        assertTrue("the group MESSAGE date is a number", o.get("timestamp") is Number)
        // …while the group DEFINITION's is a string. Same feature, same file, 20 lines apart.
        assertTrue(
            JSONObject(
                GroupUpdateWire.encodeDefinition(
                    GroupDefinition(
                        groupId, "n", GroupType.PUBLIC, adminKey,
                        listOf(GroupMember(adminKey, null, tsMs, true)), tsMs, tsMs,
                    ),
                ),
            ).get("createdAt") is String,
        )
    }

    /** Key set and order, against Android's builder (`GroupManager.kt:2294-2316`). */
    @Test
    fun `group message JSON carries the keys iOS Codable requires, in Android's order`() {
        val raw = GroupMessageWire.encode(msg())
        assertEquals(
            "{\"id\":\"msg-1\"," +
                "\"groupId\":\"3F2504E0-4F89-11D3-9A0C-0305E82C3301\"," +
                "\"senderPublicKey\":\"AAAAadminkey\"," +
                "\"encryptedContent\":\"aGVsbG8=\"," +
                "\"timestamp\":${WireClock.jsonNumber((tsMs / 1000.0) - APPLE_OFFSET)}," +
                "\"isRead\":false," +
                "\"messageChainIndex\":0," +
                "\"messageId\":\"msg-1\"," +
                "\"content\":\"hello\"," +
                "\"senderName\":\"\"}",
            raw,
        )
    }

    /** `encryptedContent` is base64 of the PLAINTEXT body. The name is a fossil (`swift:2554`). */
    @Test
    fun `encryptedContent is base64 of the plaintext body`() {
        val o = JSONObject(GroupMessageWire.encode(msg("hello")))
        assertEquals("hello", String(Base64.getDecoder().decode(o.getString("encryptedContent"))))
    }

    /** The caption rides `plaintextContent`, and only for media (`GroupManager.kt:2305-2311`). */
    @Test
    fun `the caption is emitted for media only`() {
        assertFalse(
            "duplicating a text body would make iOS draw it twice",
            JSONObject(GroupMessageWire.encode(msg("hi", caption = "hi"))).has("plaintextContent"),
        )
        assertEquals(
            "look at this",
            JSONObject(
                GroupMessageWire.encode(
                    msg("{}", GroupMessageWire.GroupMediaType.PHOTO, "look at this"),
                ),
            ).getString("plaintextContent"),
        )
    }

    /** The GROUP MediaType says `photo`, not `image` (`OSHI/GroupMessaging.swift:405-412`). */
    @Test
    fun `group media type uses the group spelling`() {
        assertEquals("photo", GroupMessageWire.GroupMediaType.PHOTO.raw)
        assertEquals(
            GroupMessageWire.GroupMediaType.PHOTO,
            GroupMessageWire.GroupMediaType.fromRaw("image"),
        )
        assertEquals(
            GroupMessageWire.GroupMediaType.CONTACT,
            GroupMessageWire.GroupMediaType.fromRaw("contact"),
        )
        assertNull(GroupMessageWire.GroupMediaType.fromRaw("sticker"))
    }

    /** The media blob, against iOS's own builder (`swift:2534-2546`). */
    @Test
    fun `media blob matches the iOS shape`() {
        assertEquals(
            """{"type":"media","mediaType":"photo","content":"aW1hZ2VieXRlcw==","size":11}""",
            GroupMessageWire.encodeMediaBlob(
                GroupMessageWire.GroupMediaType.PHOTO, "aW1hZ2VieXRlcw==", 11,
            ),
        )
        assertTrue(
            GroupMessageWire.encodeMediaBlob(
                GroupMessageWire.GroupMediaType.DOCUMENT, "eA==", 1, fileName = "a.pdf",
            ).endsWith(""""fileName":"a.pdf"}"""),
        )
    }

    /**
     * The envelope plaintext is BARE base64 — no `👥GROUP_MESSAGE👥`, no sentinel of any kind
     * (`V2MessageRouter.kt:18-20`).
     */
    @Test
    fun `the envelope plaintext is bare base64 with no sentinel prefix`() {
        val b64 = GroupMessageWire.encodeForEnvelope(msg())
        assertFalse(b64.startsWith("👥"))
        assertFalse(b64.startsWith("📢"))
        assertEquals(GroupMessageWire.encode(msg()), String(Base64.getDecoder().decode(b64)))
    }

    @Test
    fun `a group message round-trips through the envelope form`() {
        val back = GroupMessageWire.decodeFromEnvelope(GroupMessageWire.encodeForEnvelope(msg()))!!
        assertEquals("msg-1", back.messageId)
        assertEquals(groupId.uppercase(), back.groupId)
        assertEquals(adminKey, back.senderPublicKey)
        assertEquals("hello", back.body)
        assertEquals(tsMs, back.timestampUnixMillis)
    }

    /** An implausible instant stops at the emitter — PLAN.md §4.2's strict half. */
    @Test
    fun `a Unix-millis timestamp is refused by the emitter`() {
        try {
            GroupMessageWire.encode(msg().copy(timestampUnixMillis = 99_999_999_999_999L))
            fail("an out-of-window instant must not reach the wire")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("2100"))
        }
    }

    // ================================================================= caption precedence

    private val mediaJson =
        """{"type":"media","mediaType":"photo","content":"aW1hZ2VieXRlcw==","size":11}"""

    /**
     * The P0. iOS sends a captioned group photo with the caption in `plaintextContent`
     * (`swift:2368`) and the media JSON in `encryptedContent` (`:2323-2336`). An ordered
     * fallback lets the caption win, the media branch never runs, and the image bytes are
     * discarded forever.
     */
    @Test
    fun `a captioned iOS photo resolves to the media JSON, not the caption`() {
        assertEquals(mediaJson, GroupMessageWire.resolveIncomingBody("", "look at this", mediaJson))
    }

    /** The uncaptioned case worked before and must keep working — which is why this survived testing. */
    @Test
    fun `an uncaptioned iOS photo still resolves to the media JSON`() {
        assertEquals(mediaJson, GroupMessageWire.resolveIncomingBody("", "", mediaJson))
    }

    @Test
    fun `a captioned Android photo resolves to the media JSON`() {
        assertEquals(mediaJson, GroupMessageWire.resolveIncomingBody("look at this", "", mediaJson))
    }

    @Test
    fun `plain text still follows the original fallback order`() {
        assertEquals("hi", GroupMessageWire.resolveIncomingBody("hi", "ignored", "ignored"))
        assertEquals("hi", GroupMessageWire.resolveIncomingBody("", "hi", "ignored"))
        assertEquals("hi", GroupMessageWire.resolveIncomingBody("", "", "hi"))
    }

    /** A `{`-prefixed body that is not a media envelope must not be mistaken for one. */
    @Test
    fun `non-media JSON is not promoted over the caption`() {
        assertEquals(
            "caption",
            GroupMessageWire.resolveIncomingBody("caption", "", """{"type":"location","lat":1.0}"""),
        )
    }

    // ================================================================= message ids

    /**
     * iOS re-renders an Android-minted lowercase UUID in UPPERCASE and targets every
     * reaction / receipt / deletion at that form. A plain `==` dropped all of them in both
     * directions (`GroupManager.kt:3629-3641`).
     */
    @Test
    fun `message ids compare case-insensitively and are stored verbatim`() {
        assertTrue(
            GroupMessageWire.sameMessageId(
                "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
                "3F2504E0-4F89-11D3-9A0C-0305E82C3301",
            ),
        )
        // Stored verbatim: the encoder does not case-fold the id it was handed.
        assertEquals(
            "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
            JSONObject(GroupMessageWire.encode(msg().copy(messageId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301")))
                .getString("id"),
        )
    }

    // ================================================================= fan-out

    /** N members, N ciphertexts, no group key. iOS `V2MessageRouter.swift:714`. */
    @Test
    fun `fan-out is every member except us, in roster order`() {
        assertEquals(
            listOf(memberKey, "CCC="),
            GroupFanout.plan(listOf(adminKey, memberKey, "CCC="), adminKey),
        )
    }

    /**
     * Self-exclusion by canonical identity, not raw equality — the deliberate divergence
     * from both phones. Under a raw `!=` this client would encrypt a copy of every group
     * message to itself.
     */
    @Test
    fun `we exclude ourselves across base64url and padding skew`() {
        assertEquals(
            listOf(memberKey),
            GroupFanout.plan(listOf("q83vqw==", memberKey), "q83vqw"),
        )
    }

    /** The guard, and the failure it prevents. */
    @Test
    fun `a group envelope must carry a groupId`() {
        assertEquals(
            mapOf("type" to "group", "groupId" to groupId.uppercase()),
            GroupFanout.envelopeFields(groupId),
        )
        listOf(null, "", "   ").forEach { bad ->
            try {
                GroupFanout.requireGroupId(bad)
                fail("groupId='$bad' must be refused — it routes the message into the 1:1 thread")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("groupId"))
                assertTrue(e.message!!.contains("1:1"))
            }
        }
    }

    /** The guard is on the shipping path: `envelopeFields` cannot produce a blank groupId. */
    @Test
    fun `envelopeFields applies the guard`() {
        try {
            GroupFanout.envelopeFields("")
            fail("envelopeFields must run requireGroupId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("groupId"))
        }
    }

    /** The relay validates `type`; `"group"` is one of exactly two legal values. */
    @Test
    fun `the group envelope type is the one the relay validates`() {
        assertEquals("group", GroupFanout.ENVELOPE_TYPE_GROUP)
    }
}
