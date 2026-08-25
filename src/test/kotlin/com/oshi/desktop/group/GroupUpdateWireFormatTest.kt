package com.oshi.desktop.group

import com.oshi.desktop.msg.WireClock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Byte shapes for every `📢GROUP_UPDATE📢` payload — PARITY.md row 0.17.
 *
 * **The reference is the SHIPPED source, never this client's own encoder.** Every literal
 * below was lifted from one of two places:
 *
 *  - Android's own wire-format test, `OSHI-Android/app/src/test/java/com/oshi/messenger/
 *    GroupWireFormatTest.kt`, which is itself written against the Swift and carries the
 *    literal fixture `{"id":"3F2504E0-…","name":"Team OSHI",…}` at its lines 202-212;
 *  - the Swift decoder it cites, `OSHI/GroupMessaging.swift:190-268` and `:1071-1157`.
 *
 * The fixture keys, the group ids, the ISO strings and the expected rejection reasons are
 * all hand-written here. Nothing under test builds its own expectation, so deleting a guard
 * makes these fail rather than move with the code.
 */
class GroupUpdateWireFormatTest {

    // The same instants Android's test uses (GroupWireFormatTest.kt:31-37), so the two
    // suites can be diffed line by line.
    private val createdAtMs = 1786622400000L      // 2026-08-13T12:00:00Z
    private val lastActivityMs = 1786626000000L   // +1h
    private val adminKey = "AAAAadminkey"
    private val memberKey = "BBBBmemberkey"
    private val groupId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"

    private fun sample(
        type: GroupType = GroupType.ADMIN_ONLY,
        picture: String? = null,
        wallpaper: String? = null,
        pinned: String? = null,
        members: List<GroupMember> = listOf(
            GroupMember(adminKey, null, createdAtMs, isAdmin = true),
            GroupMember(memberKey, "Bob", createdAtMs, isAdmin = false),
        ),
    ) = GroupDefinition(
        groupId = groupId,
        name = "Team OSHI",
        type = type,
        adminPublicKey = adminKey,
        members = members,
        createdAtUnixMillis = createdAtMs,
        lastActivityUnixMillis = lastActivityMs,
        groupPictureBase64 = picture,
        groupPictureUpdatedBy = picture?.let { adminKey },
        groupPictureUpdatedAtUnixMillis = picture?.let { lastActivityMs },
        groupWallpaperBase64 = wallpaper,
        groupWallpaperUpdatedBy = wallpaper?.let { memberKey },
        groupWallpaperUpdatedAtUnixMillis = wallpaper?.let { lastActivityMs },
        pinnedMessageId = pinned,
        pinnedBy = pinned?.let { adminKey },
    )

    private fun json(g: GroupDefinition = sample()) = JSONObject(GroupUpdateWire.encodeDefinition(g))

    // ================================================================= required keys

    /**
     * The four keys `MessageGroup.init(from:)` decodes NON-optionally
     * (`OSHI/GroupMessaging.swift:194-197`), plus the three the payload is useless without.
     * Any of the four missing or mistyped throws, the strict decode fails, and the payload
     * falls through to the minimal switch whose `default:` (`swift:1154`) drops it.
     */
    @Test
    fun `full group JSON carries every required iOS key`() {
        val o = json()
        listOf("id", "name", "type", "adminPublicKey", "members", "createdAt", "lastActivity")
            .forEach { assertTrue("missing '$it'", o.has(it)) }
        assertEquals("Team OSHI", o.getString("name"))
        assertEquals(adminKey, o.getString("adminPublicKey"))
    }

    /** iOS re-encodes `UUID` as an UPPERCASE uuidString (`GroupWireFormatTest.kt:92`). */
    @Test
    fun `group id is emitted as an uppercase UUID`() {
        assertEquals(groupId.uppercase(), json().getString("id"))
    }

    /** A non-UUID invite gid must survive verbatim (`GroupWireFormatTest.kt:98`). */
    @Test
    fun `non-UUID group id is left untouched`() {
        assertEquals("my-Custom-Gid", GroupIdentity.canonicalGroupId("my-Custom-Gid"))
    }

    /** `type` raw values — iOS's enum, `OSHI/GroupMessaging.swift:35-40`. */
    @Test
    fun `group type raw values match the iOS enum`() {
        assertEquals("admin_only", GroupType.ADMIN_ONLY.raw)
        assertEquals("collaborative", GroupType.COLLABORATIVE.raw)
        assertEquals("public", GroupType.PUBLIC.raw)
        assertEquals("admin_only", json().getString("type"))
    }

    /** An unknown type throws in Swift, so it is coerced (`GroupWireFormatTest.kt:116`). */
    @Test
    fun `unknown group type is coerced to a legal iOS raw value`() {
        assertEquals(GroupType.COLLABORATIVE, GroupType.coerceForWire("PRIVATE"))
        assertNull(GroupType.fromRaw("PRIVATE"))
    }

    // ================================================================= the date trap

    /**
     * The whole reason this row has a warning. `broadcastFullGroupUpdate` encodes with
     * `.iso8601` (`swift:2247`) and the decoder matches (`swift:934`).
     * `groupPictureUpdatedAt` / `groupWallpaperUpdatedAt` go through a bare
     * `decodeIfPresent(Date.self,…)` (`swift:215, :220`) NOT wrapped in `try?`, so a numeric
     * timestamp there throws and drops the ENTIRE group update, members included.
     *
     * Literals from `GroupWireFormatTest.kt:128-134`.
     */
    @Test
    fun `dates are ISO-8601 strings, never epoch numbers`() {
        val o = json(sample(picture = "aGVsbG8=", wallpaper = "d29ybGQ="))
        assertEquals("2026-08-13T12:00:00Z", o.getString("createdAt"))
        assertEquals("2026-08-13T13:00:00Z", o.getString("lastActivity"))
        assertEquals("2026-08-13T13:00:00Z", o.getString("groupPictureUpdatedAt"))
        assertEquals("2026-08-13T13:00:00Z", o.getString("groupWallpaperUpdatedAt"))
        assertTrue(o.get("createdAt") is String)
        assertTrue(o.get("groupWallpaperUpdatedAt") is String)
    }

    /** ISO-8601 round-trips through the ONE converter (`GroupWireFormatTest.kt:139`). */
    @Test
    fun `iso8601 round-trips through WireClock`() {
        assertEquals(createdAtMs, WireClock.fromIso8601(WireClock.toIso8601(createdAtMs)))
    }

    /** The emitter guard, on the encoder's own output. Must not throw. */
    @Test
    fun `assertNoNumericDates passes on a real payload`() {
        GroupUpdateWire.assertNoNumericDates(
            GroupUpdateWire.encodeDefinition(sample(picture = "aGVsbG8=", wallpaper = "d29ybGQ=")),
        )
    }

    /**
     * The guard, watched failing. A hand-written payload with an Apple-epoch NUMBER where
     * `.iso8601` wants a string — the exact byte an older Android emitter would have
     * produced.
     */
    @Test
    fun `assertNoNumericDates rejects a numeric picture timestamp`() {
        val bad = """{"id":"X","name":"n","type":"public","adminPublicKey":"a",
            |"createdAt":"2026-08-13T12:00:00Z","lastActivity":"2026-08-13T12:00:00Z",
            |"groupPictureData":"aGVsbG8=","groupPictureUpdatedAt":808300800}"""
            .trimMargin().replace("\n", "")
        try {
            GroupUpdateWire.assertNoNumericDates(bad)
            fail("a number in groupPictureUpdatedAt must be refused — it drops the whole roster on iOS")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("groupPictureUpdatedAt"))
            assertTrue(e.message!!.contains("iso8601"))
        }
    }

    /** The tolerated-but-still-wrong half: `createdAt` as a number. iOS survives it; we do not emit it. */
    @Test
    fun `assertNoNumericDates rejects a numeric createdAt too`() {
        val bad = """{"id":"X","name":"n","type":"public","adminPublicKey":"a","createdAt":808300800}"""
        try {
            GroupUpdateWire.assertNoNumericDates(bad)
            fail("createdAt must be an ISO string on the way out even though iOS tolerates a number")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("createdAt"))
        }
    }

    /** A member's `joinedAt` is the third date and the guard has to reach inside the array. */
    @Test
    fun `assertNoNumericDates reaches into the members array`() {
        val bad = """{"id":"X","name":"n","type":"public","adminPublicKey":"a",
            |"members":[{"publicKey":"a","joinedAt":808300800,"isAdmin":true}],
            |"createdAt":"2026-08-13T12:00:00Z"}"""
            .trimMargin().replace("\n", "")
        try {
            GroupUpdateWire.assertNoNumericDates(bad)
            fail("member joinedAt must be an ISO string")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("joinedAt"))
        }
    }

    /** The guard is on the shipping path, not only in this file. */
    @Test
    fun `the framed emitter applies the guard and the prefix`() {
        val framed = GroupUpdateWire.encodeDefinitionFramed(sample())
        assertTrue(framed.startsWith("📢GROUP_UPDATE📢"))
        assertTrue(JSONObject(framed.removePrefix("📢GROUP_UPDATE📢")).get("createdAt") is String)
    }

    // ================================================================= members

    /** Members carry `publicKey`, `joinedAt` and per-member `isAdmin` (`GroupWireFormatTest.kt:147`). */
    @Test
    fun `members carry publicKey, joinedAt and per-member isAdmin`() {
        val members = json().getJSONArray("members")
        assertEquals(2, members.length())
        val admin = members.getJSONObject(0)
        assertEquals(adminKey, admin.getString("publicKey"))
        assertTrue(admin.getBoolean("isAdmin"))
        assertEquals("2026-08-13T12:00:00Z", admin.getString("joinedAt"))
        val member = members.getJSONObject(1)
        assertEquals(memberKey, member.getString("publicKey"))
        assertFalse(member.getBoolean("isAdmin"))
        assertEquals("Bob", member.getString("alias"))
    }

    /** No alias ⇒ the key is ABSENT, never null — iOS reads it with `decodeIfPresent`. */
    @Test
    fun `member alias is omitted when unknown`() {
        assertFalse(json().getJSONArray("members").getJSONObject(0).has("alias"))
    }

    /** A lightweight broadcast must not emit empty keys (`GroupWireFormatTest.kt:168`). */
    @Test
    fun `picture, wallpaper and pin keys are absent when there is nothing to send`() {
        val o = json()
        assertFalse(o.has("groupPictureData"))
        assertFalse(o.has("groupWallpaperData"))
        assertFalse(o.has("pinnedMessageId"))
        assertFalse(o.has("stateVersion"))
    }

    @Test
    fun `picture, wallpaper and pin ride the same payload when present`() {
        val o = json(sample(picture = "aGVsbG8=", wallpaper = "d29ybGQ=", pinned = "11111111-2222-3333-4444-555555555555"))
        assertEquals("aGVsbG8=", o.getString("groupPictureData"))
        assertEquals(adminKey, o.getString("groupPictureUpdatedBy"))
        assertEquals("d29ybGQ=", o.getString("groupWallpaperData"))
        assertEquals(memberKey, o.getString("groupWallpaperUpdatedBy"))
        assertEquals("11111111-2222-3333-4444-555555555555", o.getString("pinnedMessageId"))
        assertEquals(adminKey, o.getString("pinnedBy"))
    }

    /** Mute is personal: iOS restores its own value (`swift:1028`), so ours is always false. */
    @Test
    fun `isMuted is always false on the wire`() {
        assertFalse(json(sample().copy(isMuted = true)).getBoolean("isMuted"))
    }

    // ================================================================= the literal fixture

    /**
     * The whole point of the file: a hand-written payload in the exact iOS shape, copied
     * from `GroupWireFormatTest.kt:202-212`.
     */
    @Test
    fun `literal fixture matches the iOS MessageGroup shape`() {
        val expected = JSONObject(
            """
            {"id":"3F2504E0-4F89-11D3-9A0C-0305E82C3301",
             "name":"Team OSHI",
             "type":"collaborative",
             "adminPublicKey":"AAAAadminkey",
             "members":[{"publicKey":"AAAAadminkey","joinedAt":"2026-08-13T12:00:00Z","isAdmin":true}],
             "createdAt":"2026-08-13T12:00:00Z",
             "lastActivity":"2026-08-13T12:00:00Z",
             "isMuted":false}
            """.trimIndent(),
        )
        val actual = json(
            sample(
                type = GroupType.COLLABORATIVE,
                members = listOf(GroupMember(adminKey, null, createdAtMs, isAdmin = true)),
            ).copy(lastActivityUnixMillis = createdAtMs),
        )
        assertEquals(
            expected.keys().asSequence().toSortedSet(),
            actual.keys().asSequence().toSortedSet(),
        )
        expected.keys().forEach { key ->
            assertEquals("mismatch on '$key'", expected.get(key).toString(), actual.get(key).toString())
        }
    }

    /** Key ORDER, so a byte diff against an Android payload stays meaningful (PLAN.md §4.3). */
    @Test
    fun `key order matches Android's builder`() {
        val raw = GroupUpdateWire.encodeDefinition(
            sample(members = listOf(GroupMember(adminKey, null, createdAtMs, isAdmin = true))),
        )
        assertEquals(
            "{\"id\":\"3F2504E0-4F89-11D3-9A0C-0305E82C3301\"," +
                "\"name\":\"Team OSHI\"," +
                "\"type\":\"admin_only\"," +
                "\"adminPublicKey\":\"AAAAadminkey\"," +
                "\"members\":[{\"publicKey\":\"AAAAadminkey\"," +
                "\"joinedAt\":\"2026-08-13T12:00:00Z\",\"isAdmin\":true}]," +
                "\"createdAt\":\"2026-08-13T12:00:00Z\"," +
                "\"lastActivity\":\"2026-08-13T13:00:00Z\"," +
                "\"isMuted\":false}",
            raw,
        )
    }

    // ================================================================= dispatch

    /** `type` here is the GROUP type, not an update kind (`GroupWireFormatTest.kt:234`). */
    @Test
    fun `full group payload is distinguishable from a minimal update`() {
        assertTrue(GroupUpdateWire.looksLikeDefinition(json()))
        assertFalse(
            GroupUpdateWire.looksLikeDefinition(
                JSONObject("""{"type":"member_added","groupId":"x","memberPublicKey":"y"}"""),
            ),
        )
        assertFalse(
            GroupUpdateWire.looksLikeDefinition(JSONObject("""{"type":"created","name":"n"}""")),
        )
    }

    // ================================================================= minimal shapes

    @Test
    fun `member_removed matches the keys iOS reads`() {
        val o = JSONObject(GroupUpdateWire.encodeMemberRemoved(groupId, memberKey))
        assertEquals("member_removed", o.getString("type"))
        assertEquals(groupId.uppercase(), o.getString("groupId"))
        assertEquals(memberKey, o.getString("memberPublicKey"))
    }

    @Test
    fun `group_renamed matches the keys iOS reads`() {
        val o = JSONObject(GroupUpdateWire.encodeGroupRenamed(groupId, "New Name"))
        assertEquals("group_renamed", o.getString("type"))
        assertEquals(groupId.uppercase(), o.getString("groupId"))
        assertEquals("New Name", o.getString("name"))
    }

    @Test
    fun `member_added and sync requests match the keys iOS reads`() {
        assertEquals(
            """{"type":"member_added","groupId":"${groupId.uppercase()}","memberPublicKey":"$memberKey"}""",
            GroupUpdateWire.encodeMemberAdded(groupId, memberKey),
        )
        assertEquals(
            """{"type":"member_sync_request","groupId":"${groupId.uppercase()}","requesterPublicKey":"$memberKey"}""",
            GroupUpdateWire.encodeMemberSyncRequest(groupId, memberKey),
        )
        assertEquals(
            """{"type":"sync_request","requesterKey":"$memberKey"}""",
            GroupUpdateWire.encodeSyncRequest(memberKey),
        )
    }

    /**
     * The `"created"` shape iOS emits at `swift:1877-1889` and cannot itself parse. Field
     * names are its own — `creatorPublicKey`, bare key arrays, `isPublic`.
     */
    @Test
    fun `created carries iOS's own field names, not the definition's`() {
        val o = JSONObject(
            GroupUpdateWire.encodeCreated(
                MinimalGroupUpdate.Created(
                    groupId = groupId, name = "Team OSHI", description = "",
                    creatorPublicKey = adminKey, memberKeys = listOf(adminKey, memberKey),
                    adminKeys = emptyList(), isPublic = true,
                ),
            ),
        )
        assertEquals("created", o.getString("type"))
        assertEquals(adminKey, o.getString("creatorPublicKey"))
        assertFalse("the created shape has no adminPublicKey", o.has("adminPublicKey"))
        assertFalse("the created shape has no type-as-GroupType", o.getString("type") == "public")
        assertTrue(o.getBoolean("isPublic"))
        assertEquals(adminKey, o.getJSONArray("members").getString(0))
        // Empty admins fall back to the creator — swift:1885.
        assertEquals(adminKey, o.getJSONArray("admins").getString(0))
    }

    @Test
    fun `minimal shapes round-trip`() {
        assertEquals(
            MinimalGroupUpdate.MemberRemoved(groupId.uppercase(), memberKey),
            GroupUpdateWire.decodeMinimal(GroupUpdateWire.encodeMemberRemoved(groupId, memberKey)),
        )
        assertEquals(
            MinimalGroupUpdate.GroupRenamed(groupId.uppercase(), "New Name"),
            GroupUpdateWire.decodeMinimal(GroupUpdateWire.encodeGroupRenamed(groupId, "New Name")),
        )
        assertEquals(
            MinimalGroupUpdate.SyncRequest(memberKey),
            GroupUpdateWire.decodeMinimal(GroupUpdateWire.encodeSyncRequest(memberKey)),
        )
        assertNull(GroupUpdateWire.decodeMinimal("""{"type":"who_knows","groupId":"x"}"""))
        assertNull(GroupUpdateWire.decodeMinimal("not json"))
    }

    // ================================================================= ingest

    @Test
    fun `a definition round-trips through encode and decode`() {
        val src = sample(picture = "aGVsbG8=", pinned = "11111111-2222-3333-4444-555555555555")
        val decoded = GroupUpdateWire.decodeDefinition(GroupUpdateWire.encodeDefinition(src))
        assertTrue(decoded is GroupUpdateWire.GroupUpdateDecode.Ok)
        val g = (decoded as GroupUpdateWire.GroupUpdateDecode.Ok).definition
        assertEquals(groupId.uppercase(), g.groupId)
        assertEquals("Team OSHI", g.name)
        assertEquals(GroupType.ADMIN_ONLY, g.type)
        assertEquals(listOf(adminKey, memberKey), g.memberKeys)
        assertEquals(listOf(adminKey), g.adminKeys)
        assertEquals(createdAtMs, g.createdAtUnixMillis)
        assertEquals(lastActivityMs, g.lastActivityUnixMillis)
        assertEquals("aGVsbG8=", g.groupPictureBase64)
        assertEquals(lastActivityMs, g.groupPictureUpdatedAtUnixMillis)
    }

    /** The prefix is optional on ingest because iOS omits it on half its paths. */
    @Test
    fun `decode accepts a body with or without the sentinel`() {
        val framed = GroupUpdateWire.encodeDefinitionFramed(sample())
        assertTrue(GroupUpdateWire.decodeFramed(framed) is GroupUpdateWire.GroupUpdateDecode.Ok)
        assertTrue(
            GroupUpdateWire.decodeFramed(framed.removePrefix("📢GROUP_UPDATE📢"))
                is GroupUpdateWire.GroupUpdateDecode.Ok,
        )
    }

    /** iOS tolerates an Apple-epoch NUMBER in `createdAt` (`swift:198-211`); so do we. */
    @Test
    fun `createdAt as an Apple-epoch number is accepted on ingest`() {
        val appleSeconds = WireClock.toAppleSeconds(createdAtMs)
        val body = """{"id":"$groupId","name":"n","type":"public","adminPublicKey":"$adminKey",
            |"createdAt":${WireClock.jsonNumber(appleSeconds)},"members":[]}""".trimMargin().replace("\n", "")
        val d = GroupUpdateWire.decodeDefinition(body)
        assertTrue("$d", d is GroupUpdateWire.GroupUpdateDecode.Ok)
        assertEquals(createdAtMs, (d as GroupUpdateWire.GroupUpdateDecode.Ok).definition.createdAtUnixMillis)
    }

    /**
     * The fatal one, from the other side: a number in `groupPictureUpdatedAt` throws on iOS
     * and the whole definition is dropped. This client says so instead of silently keeping
     * a group state every iPhone has already discarded.
     */
    @Test
    fun `a numeric picture timestamp is rejected on ingest, as iOS rejects it`() {
        val body = """{"id":"$groupId","name":"n","type":"public","adminPublicKey":"$adminKey",
            |"createdAt":"2026-08-13T12:00:00Z","groupPictureData":"aGVsbG8=",
            |"groupPictureUpdatedAt":808300800}""".trimMargin().replace("\n", "")
        val d = GroupUpdateWire.decodeDefinition(body)
        assertTrue("$d", d is GroupUpdateWire.GroupUpdateDecode.Rejected)
        assertEquals("non-string-date-groupPictureUpdatedAt", (d as GroupUpdateWire.GroupUpdateDecode.Rejected).reason)
    }

    /** An unknown `type` is an enum decode on iOS and takes the payload with it. */
    @Test
    fun `an unknown group type is rejected on ingest`() {
        val body = """{"id":"$groupId","name":"n","type":"private","adminPublicKey":"$adminKey",
            |"createdAt":"2026-08-13T12:00:00Z"}""".trimMargin().replace("\n", "")
        // looksLikeDefinition already refuses it — the same test iOS's dispatcher makes.
        assertTrue(GroupUpdateWire.decodeDefinition(body) is GroupUpdateWire.GroupUpdateDecode.NotADefinition)
    }

    /**
     * `adminPublicKey` is `container.decode(String.self, forKey:)` on iOS (`swift:197`), so
     * an explicit JSON `null` throws there just as a missing key does.
     *
     * Note the seam this walks: `looksLikeDefinition` mirrors Android's dispatcher
     * (`GroupManager.kt:257-260`), which uses `has("adminPublicKey")` — and `has` is TRUE
     * for an explicit null. So the payload IS dispatched as a definition and then refused by
     * the required-key check. Both halves are needed; the dispatcher alone would let it
     * through, and asserting the wrong one of the two here is how a reader would conclude
     * the null case is handled when it is not.
     */
    @Test
    fun `an explicitly null adminPublicKey is rejected, not merely undispatched`() {
        val body = """{"id":"$groupId","name":"n","type":"public","adminPublicKey":null,
            |"createdAt":"2026-08-13T12:00:00Z"}""".trimMargin().replace("\n", "")
        val d = GroupUpdateWire.decodeDefinition(body)
        assertTrue("$d", d is GroupUpdateWire.GroupUpdateDecode.Rejected)
        assertEquals("missing-adminPublicKey", (d as GroupUpdateWire.GroupUpdateDecode.Rejected).reason)
    }

    /** An entirely ABSENT `adminPublicKey` fails the dispatcher instead — the other half. */
    @Test
    fun `an absent adminPublicKey is not a definition at all`() {
        val body = """{"id":"$groupId","name":"n","type":"public","createdAt":"2026-08-13T12:00:00Z"}"""
        assertTrue(GroupUpdateWire.decodeDefinition(body) is GroupUpdateWire.GroupUpdateDecode.NotADefinition)
    }

    /**
     * iOS's own `{"type":"created"}` emitter sends members as bare key STRINGS
     * (`swift:1883`), and iOS keeps a per-member fallback for that shape (`swift:240-251`).
     */
    @Test
    fun `a members array of bare key strings is accepted`() {
        val body = """{"id":"$groupId","name":"n","type":"public","adminPublicKey":"$adminKey",
            |"createdAt":"2026-08-13T12:00:00Z","members":["$adminKey","$memberKey"]}"""
            .trimMargin().replace("\n", "")
        val d = GroupUpdateWire.decodeDefinition(body) as GroupUpdateWire.GroupUpdateDecode.Ok
        assertEquals(listOf(adminKey, memberKey), d.definition.memberKeys)
        assertTrue(d.definition.isAdmin(adminKey))
        assertFalse(d.definition.isAdmin(memberKey))
    }

    /** An empty roster is synthesised from `adminPublicKey` — iOS `swift:252-254`. */
    @Test
    fun `an empty roster synthesises the admin member`() {
        val body = """{"id":"$groupId","name":"n","type":"public","adminPublicKey":"$adminKey",
            |"createdAt":"2026-08-13T12:00:00Z","members":[]}""".trimMargin().replace("\n", "")
        val d = GroupUpdateWire.decodeDefinition(body) as GroupUpdateWire.GroupUpdateDecode.Ok
        assertEquals(listOf(adminKey), d.definition.memberKeys)
        assertTrue(d.definition.isAdmin(adminKey))
    }

    @Test
    fun `stateVersion is absent rather than zero when we have none`() {
        assertNull((GroupUpdateWire.decodeDefinition(GroupUpdateWire.encodeDefinition(sample()))
            as GroupUpdateWire.GroupUpdateDecode.Ok).definition.stateVersion)
        val withVersion = GroupUpdateWire.encodeDefinition(sample().copy(stateVersion = 7))
        assertEquals(7, JSONObject(withVersion).getInt("stateVersion"))
    }

    @Test
    fun `garbage is not a definition`() {
        assertNotNull(GroupUpdateWire.decodeDefinition("nonsense"))
        assertTrue(GroupUpdateWire.decodeDefinition("nonsense") is GroupUpdateWire.GroupUpdateDecode.NotADefinition)
    }
}
