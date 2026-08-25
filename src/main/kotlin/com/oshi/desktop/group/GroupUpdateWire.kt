package com.oshi.desktop.group

import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.WireClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * The `📢GROUP_UPDATE📢` codec — PARITY.md row 0.17's wire layer.
 *
 * ============================================================ THE FRAME
 *
 * `📢GROUP_UPDATE📢` + a JSON body, on the same `content` string as user prose, exactly
 * like every other sentinel payload (`ControlPrefix`). Both shipped clients agree on the
 * prefix (iOS `\u{1F4E2}GROUP_UPDATE\u{1F4E2}` at `OSHI/MessageManager.swift:5943`, Android
 * `GROUP_UPDATE_PREFIX` at `GroupManager.kt:53`) and both strip it and hand the remainder
 * to a JSON parser.
 *
 * **One shipped asymmetry, and it matters.** iOS's `broadcastFullGroupUpdate`
 * (`OSHI/GroupMessaging.swift:2218-2289`) does NOT prefix: it posts the raw `Data` of the
 * encoded `MessageGroup` to two `NotificationCenter` names and lets each transport wrap it.
 * Only `handleGroupSyncRequest` (`swift:1896`) writes the prefix itself. Android prefixes
 * everywhere. So an implementation that assumes "a group update is always prefixed" is
 * right about Android and wrong about half of iOS. [decode] therefore accepts a body with
 * or without the prefix, and [encode]* always writes it — accept both, emit one, the rule
 * this project applies everywhere.
 *
 * ============================================================ THE DATE TRAP
 *
 * This is the payload PARITY.md row 0.18 warned about from the other side, and it is worth
 * restating in the file that owns it.
 *
 * The group definition is the **only** payload in this project encoded with
 * `JSONEncoder.dateEncodingStrategy = .iso8601` (`OSHI/GroupMessaging.swift:2247`) and
 * decoded with the matching `.iso8601` (`swift:934`). Everything around it is Apple-epoch
 * seconds — including the `GroupMessage` that rides a v2 group envelope, which is encoded
 * by a BARE `JSONEncoder()` twenty lines away at `swift:2761`. Two payloads, one feature,
 * two date encodings, and no field name distinguishes them: both are called `timestamp`
 * or `createdAt`.
 *
 * The consequence is not symmetric across fields, and reading `MessageGroup.init(from:)`
 * (`swift:190-268`) is the only way to know that:
 *
 *  - `createdAt` / `lastActivity` are **tolerant**. `try? decode(Date)` then
 *    `try? decode(Double)` then `Date()` (`swift:198-211`) — a number here is read as
 *    Apple-epoch seconds and the payload survives.
 *  - `groupPictureUpdatedAt` / `groupWallpaperUpdatedAt` are **fatal**. Bare
 *    `try container.decodeIfPresent(Date.self, …)` (`swift:215, :220`), NOT wrapped in
 *    `try?`. `decodeIfPresent` tolerates ABSENCE, never a wrong type. A number there throws
 *    out of `init(from:)`, the whole strict decode fails, and the payload falls through to
 *    the minimal-JSON switch whose `default:` (`swift:1154`) logs and drops it. **The group
 *    name, the entire roster and every admin flag are lost to a mis-encoded picture
 *    timestamp.**
 *  - `joinedAt` inside a member is tolerant again (`swift:311-317`), and the per-member
 *    fallback path at `swift:240-251` re-parses members by hand if the strict array decode
 *    fails.
 *
 * So this file is strict where it EMITS and honest where it INGESTS:
 *  - [encodeDefinition] routes every date through [WireClock.toIso8601]. There is no code
 *    path that can put a number in a date field, and [DATE_KEYS_ISO] plus
 *    [assertNoNumericDates] make that a checked property rather than a hope.
 *  - [decodeDefinition] reproduces iOS's verdict exactly — including the rejection — and
 *    says WHY in [GroupUpdateDecode.Rejected.reason], rather than being quietly more
 *    tolerant than the strictest node on the network. A desktop that accepts what an iPhone
 *    drops does not "work better"; it disagrees about the group's state and never finds out.
 *
 * ============================================================ WHAT ELSE IS FATAL
 *
 * Four keys are decoded with a plain non-optional `decode` (`swift:194-197`) — `id`
 * (as a `UUID`), `name`, `type` (as the `GroupType` enum) and `adminPublicKey`. Any one of
 * them missing, null, or the wrong type sinks the payload the same way. `type` is the
 * sharpest of the four because it is an enum: `"private"` is a perfectly reasonable-looking
 * value that does not exist in `GroupType` (`swift:35-40`) and costs the whole definition.
 * Android coerces to `"collaborative"` on the way out for exactly this reason
 * (`GroupManager.kt:194-197`); so does [GroupType.coerceForWire].
 *
 * ============================================================ IOS AND ANDROID DISAGREE
 *
 * Recorded per the ledger's working rule 2, stricter side taken:
 *
 *  1. **Members array shape.** Android always emits `[{publicKey, alias?, joinedAt,
 *     isAdmin}]`. iOS's decoder accepts that AND a bare fallback where each member is an
 *     untyped dictionary (`swift:240-251`), and its OWN `{"type":"created"}` emitter sends
 *     members as a bare array of key STRINGS (`swift:1883`). This client emits the object
 *     form only — the one both decoders handle — and accepts the string form on ingest
 *     because iOS really does send it.
 *  2. **`joinedAt` when unknown.** iOS falls back to the group's `createdAt` (`swift:234`);
 *     Android hard-codes `createdAt` for every member because it has no per-member join time
 *     (`GroupManager.kt:203-206`). Same value, so no disagreement in practice — noted so the
 *     next reader does not "fix" one of them.
 *  3. **`alias` when unknown.** ABSENT on Android (`GroupWireFormatTest.kt:163`), and iOS
 *     reads it with `decodeIfPresent`, which would throw on an explicit `null`. Absent.
 *  4. **`isMuted`.** On the wire, but iOS re-imposes its own local value on ingest
 *     (`swift:1028`). Android pins it to `false` outbound. Pinned to `false` here too, and
 *     the local value is never taken from a peer.
 *  5. **`stateVersion`.** iOS-only, `Int?`, bumped in exactly one place. This client emits
 *     it when it has one and NEVER invents a 0 — see [GroupDefinition].
 */
object GroupUpdateWire {

    /** `📢GROUP_UPDATE📢`, from the one catalog. */
    const val PREFIX: String = ControlPrefix.GROUP_UPDATE

    /**
     * The two date keys whose wrong type costs the WHOLE definition on iOS
     * (`OSHI/GroupMessaging.swift:215, :220` — bare `decodeIfPresent`, no `try?`).
     *
     * Named as data rather than written into two `if`s so [assertNoNumericDates] and its
     * test can iterate the actual list. `createdAt`, `lastActivity` and `joinedAt` are
     * deliberately NOT in it: iOS tolerates a number in those, so a desktop that refused
     * one would be refusing a payload every iPhone accepts.
     */
    val DATE_KEYS_ISO: List<String> = listOf("groupPictureUpdatedAt", "groupWallpaperUpdatedAt")

    /** Every date key in the definition, fatal or not — used by [assertNoNumericDates]. */
    val DATE_KEYS_ALL: List<String> =
        DATE_KEYS_ISO + listOf("createdAt", "lastActivity")

    /** The four keys iOS decodes non-optionally (`swift:194-197`). */
    val REQUIRED_KEYS: List<String> = listOf("id", "name", "type", "adminPublicKey")

    // ================================================================= EMIT

    /**
     * The full iOS `MessageGroup` JSON — the only group-update shape an iPhone acts on for
     * anything structural.
     *
     * Key order is Android's `buildIosMessageGroupJson` (`GroupManager.kt:198-224`) so a
     * byte diff against a real Android payload stays meaningful. Every date goes through
     * [WireClock.toIso8601]; every optional blob is omitted rather than nulled.
     */
    fun encodeDefinition(group: GroupDefinition): String {
        val members = group.members.map { m ->
            GroupJson()
                .str("publicKey", m.publicKey)
                .optional("alias", m.alias?.takeIf { it.isNotEmpty() })
                .str("joinedAt", WireClock.toIso8601(m.joinedAtUnixMillis))
                .bool("isAdmin", m.isAdmin)
                .build()
        }
        val json = GroupJson()
            .str("id", GroupIdentity.canonicalGroupId(group.groupId))
            .str("name", group.name)
            .str("type", group.type.raw)
            .str("adminPublicKey", group.adminPublicKey)
            .arr("members", members)
            .str("createdAt", WireClock.toIso8601(group.createdAtUnixMillis))
            .str("lastActivity", WireClock.toIso8601(group.lastActivityUnixMillis))
            // Pinned false: iOS overwrites it locally anyway (swift:1028) and Android
            // pins it too. Muting is a personal preference, not group state.
            .bool("isMuted", false)
        group.groupPictureBase64?.takeIf { it.isNotEmpty() }?.let {
            json.str("groupPictureData", it)
            json.optional("groupPictureUpdatedBy", group.groupPictureUpdatedBy?.takeIf(String::isNotEmpty))
            group.groupPictureUpdatedAtUnixMillis?.let { ms ->
                json.str("groupPictureUpdatedAt", WireClock.toIso8601(ms))
            }
        }
        group.groupWallpaperBase64?.takeIf { it.isNotEmpty() }?.let {
            json.str("groupWallpaperData", it)
            json.optional("groupWallpaperUpdatedBy", group.groupWallpaperUpdatedBy?.takeIf(String::isNotEmpty))
            group.groupWallpaperUpdatedAtUnixMillis?.let { ms ->
                json.str("groupWallpaperUpdatedAt", WireClock.toIso8601(ms))
            }
        }
        group.pinnedMessageId?.takeIf { it.isNotEmpty() }?.let {
            json.str("pinnedMessageId", it)
            json.optional("pinnedBy", group.pinnedBy?.takeIf(String::isNotEmpty))
        }
        json.optional("avatar", group.avatar?.takeIf { it.isNotEmpty() })
        json.optionalInt("stateVersion", group.stateVersion)
        return json.build()
    }

    /**
     * [encodeDefinition] behind the `📢GROUP_UPDATE📢` sentinel, ready to be a `content` —
     * **with [assertNoNumericDates] applied**.
     *
     * There is deliberately no unguarded sibling of this function. An
     * `encodeDefinitionFramed` that skipped the check and a `checkedDefinitionFrame` that
     * applied it would be two spellings of the same call, one of them wrong, and the wrong
     * one shorter — which is how a guard ends up declared, tested, and bypassed on the
     * shipping path. One framed emitter, guard inside it.
     */
    fun encodeDefinitionFramed(group: GroupDefinition): String {
        val body = encodeDefinition(group)
        assertNoNumericDates(body)
        return PREFIX + body
    }

    /** `{"type":"member_added","groupId":…,"memberPublicKey":…}` — iOS `swift:1084`. */
    fun encodeMemberAdded(groupId: String, memberPublicKey: String): String =
        GroupJson()
            .str("type", "member_added")
            .str("groupId", GroupIdentity.canonicalGroupId(groupId))
            .str("memberPublicKey", memberPublicKey)
            .build()

    /**
     * `{"type":"member_removed","groupId":…,"memberPublicKey":…}` — iOS `swift:1124`,
     * Android `buildMemberRemovedJson` (`GroupManager.kt:262-267`), byte-asserted at
     * `GroupWireFormatTest.kt:252-255`.
     */
    fun encodeMemberRemoved(groupId: String, memberPublicKey: String): String =
        GroupJson()
            .str("type", "member_removed")
            .str("groupId", GroupIdentity.canonicalGroupId(groupId))
            .str("memberPublicKey", memberPublicKey)
            .build()

    /**
     * `{"type":"group_renamed","groupId":…,"name":…}` — iOS `swift:1134`, Android
     * `buildGroupRenamedJson` (`GroupManager.kt:270-275`).
     */
    fun encodeGroupRenamed(groupId: String, name: String): String =
        GroupJson()
            .str("type", "group_renamed")
            .str("groupId", GroupIdentity.canonicalGroupId(groupId))
            .str("name", name)
            .build()

    /**
     * `{"type":"member_sync_request","groupId":…,"requesterPublicKey":…}` — iOS `swift:1105`.
     *
     * Read [MinimalGroupUpdate.MemberSyncRequest]'s doc before sending one: on iOS this
     * payload AUTO-ADDS the requester to the group.
     */
    fun encodeMemberSyncRequest(groupId: String, requesterPublicKey: String): String =
        GroupJson()
            .str("type", "member_sync_request")
            .str("groupId", GroupIdentity.canonicalGroupId(groupId))
            .str("requesterPublicKey", requesterPublicKey)
            .build()

    /** `{"type":"sync_request","requesterKey":…}` — iOS `swift:1147`. No groupId. */
    fun encodeSyncRequest(requesterKey: String): String =
        GroupJson()
            .str("type", "sync_request")
            .str("requesterKey", requesterKey)
            .build()

    /**
     * `{"type":"created", …}` — the shape iOS emits (`swift:1877-1889`) and cannot read.
     *
     * Key order and names are iOS's dictionary literal. Note `members`/`admins` are bare
     * key arrays here, not member objects, and `isPublic` replaces `type` — this shape
     * shares no field naming with [encodeDefinition] beyond `name`.
     */
    fun encodeCreated(update: MinimalGroupUpdate.Created): String {
        val json = GroupJson()
            .str("type", "created")
            .str("groupId", GroupIdentity.canonicalGroupId(update.groupId))
            .str("name", update.name)
            .str("description", update.description)
            .str("creatorPublicKey", update.creatorPublicKey)
            .strArr("members", update.memberKeys)
            .strArr(
                "admins",
                update.adminKeys.ifEmpty { listOf(update.creatorPublicKey) },
            )
            .bool("isPublic", update.isPublic)
        update.groupImageBase64?.takeIf { it.isNotEmpty() }?.let { json.str("groupImage", it) }
        return json.build()
    }

    // ================================================================= INGEST

    /** The verdict on an inbound definition, reproducing iOS's decode exactly. */
    sealed interface GroupUpdateDecode {
        data class Ok(val definition: GroupDefinition) : GroupUpdateDecode

        /**
         * iOS would have thrown out of `MessageGroup.init(from:)` and dropped this payload
         * at `swift:1154`. [reason] is a machine tag, asserted by name in the tests.
         */
        data class Rejected(val reason: String) : GroupUpdateDecode

        /** Not a definition at all — try [decodeMinimal]. */
        data class NotADefinition(val reason: String) : GroupUpdateDecode
    }

    /**
     * Android's dispatch test: is this the full definition or a minimal `{"type":…}` update?
     * `GroupManager.looksLikeIosMessageGroup` (`GroupManager.kt:257-260`), asserted at
     * `GroupWireFormatTest.kt:235-243`.
     *
     * Note it keys on `type` being a GROUP type — `admin_only`/`collaborative`/`public` —
     * which is the same key a minimal update uses for something else entirely
     * (`member_added`, `created`). One key, two meanings, distinguished only by its value.
     */
    fun looksLikeDefinition(o: JSONObject): Boolean =
        o.has("name") && o.has("adminPublicKey") &&
            GroupType.fromRaw(o.optString("type", null)) != null

    /** [decodeDefinition] on a body that may or may not carry the sentinel. */
    fun decodeFramed(content: String): GroupUpdateDecode =
        decodeDefinition(content.removePrefix(PREFIX))

    /**
     * Decode a full definition the way an iPhone does — including refusing what an iPhone
     * refuses. See the class doc's DATE TRAP section for why this does not "just be
     * tolerant".
     */
    fun decodeDefinition(body: String): GroupUpdateDecode {
        val o = runCatching { JSONObject(body) }.getOrNull()
            ?: return GroupUpdateDecode.NotADefinition("not-json")
        if (!looksLikeDefinition(o)) return GroupUpdateDecode.NotADefinition("not-a-definition")

        // The four non-optional keys (swift:194-197).
        for (k in REQUIRED_KEYS) {
            if (!o.has(k) || o.isNull(k)) return GroupUpdateDecode.Rejected("missing-$k")
        }
        val id = o.opt("id") as? String ?: return GroupUpdateDecode.Rejected("missing-id")
        val name = o.opt("name") as? String ?: return GroupUpdateDecode.Rejected("missing-name")
        val adminPublicKey = o.opt("adminPublicKey") as? String
            ?: return GroupUpdateDecode.Rejected("missing-adminPublicKey")
        val type = GroupType.fromRaw(o.opt("type") as? String)
            ?: return GroupUpdateDecode.Rejected("unknown-type")

        // The fatal date fields. A number here throws on iOS and takes the roster with it,
        // so this client reports it rather than silently keeping a definition an iPhone
        // has already discarded.
        for (k in DATE_KEYS_ISO) {
            if (o.has(k) && !o.isNull(k) && o.opt(k) !is String) {
                return GroupUpdateDecode.Rejected("non-string-date-$k")
            }
        }

        val createdAt = tolerantDate(o, "createdAt") ?: return GroupUpdateDecode.Rejected("missing-createdAt")
        val lastActivity = tolerantDate(o, "lastActivity") ?: createdAt

        val members = decodeMembers(o.opt("members"), fallbackJoinedAt = createdAt, adminPublicKey = adminPublicKey)

        return GroupUpdateDecode.Ok(
            GroupDefinition(
                groupId = GroupIdentity.canonicalGroupId(id),
                name = name,
                type = type,
                adminPublicKey = adminPublicKey,
                members = members,
                createdAtUnixMillis = createdAt,
                lastActivityUnixMillis = lastActivity,
                groupPictureBase64 = o.opt("groupPictureData") as? String,
                groupPictureUpdatedAtUnixMillis = (o.opt("groupPictureUpdatedAt") as? String)
                    ?.let(WireClock::fromIso8601),
                groupPictureUpdatedBy = o.opt("groupPictureUpdatedBy") as? String,
                groupWallpaperBase64 = o.opt("groupWallpaperData") as? String,
                groupWallpaperUpdatedAtUnixMillis = (o.opt("groupWallpaperUpdatedAt") as? String)
                    ?.let(WireClock::fromIso8601),
                groupWallpaperUpdatedBy = o.opt("groupWallpaperUpdatedBy") as? String,
                isMuted = false,
                pinnedMessageId = o.opt("pinnedMessageId") as? String,
                pinnedBy = o.opt("pinnedBy") as? String,
                avatar = o.opt("avatar") as? String,
                stateVersion = (o.opt("stateVersion") as? Number)?.toInt(),
            ),
        )
    }

    /**
     * `createdAt` / `lastActivity` / `joinedAt`: an ISO-8601 string, or an Apple-epoch
     * number, or absent. iOS's own ladder (`swift:198-211`, `:311-317`) in the same order.
     *
     * The number branch goes through [WireClock.toUnixMillis], which is where the
     * Apple-epoch offset lives — this file does not know the constant and must not learn it.
     */
    private fun tolerantDate(o: JSONObject, key: String): Long? {
        if (!o.has(key) || o.isNull(key)) return null
        (o.opt(key) as? String)?.let { return WireClock.fromIso8601(it) }
        (o.opt(key) as? Number)?.let { return WireClock.toUnixMillis(it.toDouble()) }
        return null
    }

    /**
     * The member array, in all three shapes that actually arrive:
     *  - objects (`{publicKey, alias?, joinedAt, isAdmin}`) — Android and modern iOS;
     *  - bare key strings — iOS's own `{"type":"created"}` emitter (`swift:1883`), and the
     *    per-member fallback path iOS keeps for it (`swift:240-251`);
     *  - absent or empty — iOS synthesises a single admin member from `adminPublicKey`
     *    (`swift:252-254, :260-262`), which is the one place a roster is invented rather
     *    than read, and it is copied here because a group with an empty roster is a group
     *    whose admin checks all return false.
     */
    private fun decodeMembers(raw: Any?, fallbackJoinedAt: Long, adminPublicKey: String): List<GroupMember> {
        val arr = raw as? JSONArray
        val out = ArrayList<GroupMember>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                when (val e = arr.opt(i)) {
                    is JSONObject -> {
                        val pk = e.opt("publicKey") as? String ?: continue
                        out.add(
                            GroupMember(
                                publicKey = pk,
                                alias = (e.opt("alias") as? String)?.takeIf { it.isNotEmpty() },
                                joinedAtUnixMillis = memberJoinedAt(e) ?: fallbackJoinedAt,
                                isAdmin = (e.opt("isAdmin") as? Boolean) ?: false,
                            ),
                        )
                    }
                    is String -> out.add(
                        GroupMember(
                            publicKey = e,
                            joinedAtUnixMillis = fallbackJoinedAt,
                            isAdmin = GroupIdentity.sameIdentity(e, adminPublicKey),
                        ),
                    )
                    else -> Unit
                }
            }
        }
        if (out.isEmpty()) {
            return listOf(
                GroupMember(
                    publicKey = adminPublicKey,
                    joinedAtUnixMillis = fallbackJoinedAt,
                    isAdmin = true,
                ),
            )
        }
        return out
    }

    private fun memberJoinedAt(e: JSONObject): Long? {
        (e.opt("joinedAt") as? String)?.let { return WireClock.fromIso8601(it) }
        (e.opt("joinedAt") as? Number)?.let { return WireClock.toUnixMillis(it.toDouble()) }
        return null
    }

    /** The minimal `{"type":…}` shapes, on a body that may or may not carry the sentinel. */
    fun decodeMinimalFramed(content: String): MinimalGroupUpdate? =
        decodeMinimal(content.removePrefix(PREFIX))

    /** iOS's fallback switch (`swift:1071-1157`), plus the `created` shape it emits. */
    fun decodeMinimal(body: String): MinimalGroupUpdate? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val type = o.opt("type") as? String ?: return null
        fun gid(): String? = (o.opt("groupId") as? String)?.let(GroupIdentity::canonicalGroupId)
        fun member(): String? = o.opt("memberPublicKey") as? String
        return when (type) {
            "member_added" -> {
                val g = gid() ?: return null; val m = member() ?: return null
                MinimalGroupUpdate.MemberAdded(g, m)
            }
            "member_removed" -> {
                val g = gid() ?: return null; val m = member() ?: return null
                MinimalGroupUpdate.MemberRemoved(g, m)
            }
            "group_renamed" -> {
                val g = gid() ?: return null
                val n = o.opt("name") as? String ?: return null
                MinimalGroupUpdate.GroupRenamed(g, n)
            }
            "member_sync_request" -> {
                val g = gid() ?: return null
                val r = o.opt("requesterPublicKey") as? String ?: return null
                MinimalGroupUpdate.MemberSyncRequest(g, r)
            }
            "sync_request" -> {
                val r = (o.opt("requesterKey") as? String)?.takeIf { it.isNotEmpty() } ?: return null
                MinimalGroupUpdate.SyncRequest(r)
            }
            "created" -> {
                val g = gid() ?: return null
                val n = o.opt("name") as? String ?: return null
                val creator = o.opt("creatorPublicKey") as? String ?: return null
                MinimalGroupUpdate.Created(
                    groupId = g,
                    name = n,
                    description = (o.opt("description") as? String).orEmpty(),
                    creatorPublicKey = creator,
                    memberKeys = stringArray(o.opt("members")),
                    adminKeys = stringArray(o.opt("admins")),
                    isPublic = (o.opt("isPublic") as? Boolean) ?: false,
                    groupImageBase64 = (o.opt("groupImage") as? String)?.takeIf { it.isNotEmpty() },
                )
            }
            else -> null
        }
    }

    private fun stringArray(raw: Any?): List<String> {
        val arr = raw as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.opt(it) as? String }
    }

    // ================================================================= THE EMIT GUARD

    /**
     * **The guard this row exists to have.** Throws when [json] carries a NUMBER in any date
     * key of the group definition.
     *
     * Called by [encodeDefinitionFramed] — the only framed emitter — and
     * by the tests. It is not defence against this file's own encoder — that encoder cannot
     * produce a number in a date field, since every one of them goes through
     * [WireClock.toIso8601]. It is defence against the next edit: a "small optimisation" that
     * writes `createdAt` as an epoch would look correct in every unit test that round-trips
     * through this same file, would decode fine on Android, and would silently delete the
     * group's entire roster on every iPhone in the conversation.
     *
     * It checks [DATE_KEYS_ALL], not just the fatal two, even though iOS tolerates a number
     * in `createdAt`. The tolerated case is still wrong — it means a number reached a
     * `.iso8601` decoder and only survived because someone wrote a `try?` — and the point of
     * an emitter guard is to be tighter than the network's tolerance, not equal to it.
     */
    fun assertNoNumericDates(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull()
            ?: throw IllegalArgumentException("group definition is not JSON")
        for (k in DATE_KEYS_ALL) {
            if (o.has(k) && !o.isNull(k) && o.opt(k) !is String) {
                throw IllegalStateException(
                    "'$k' is ${o.opt(k)!!::class.java.simpleName}, not a String. The group " +
                        "definition is decoded with dateDecodingStrategy = .iso8601 " +
                        "(OSHI/GroupMessaging.swift:934); a number here throws out of " +
                        "MessageGroup.init(from:) and the WHOLE definition — name, roster, " +
                        "admin flags — is dropped at swift:1154.",
                )
            }
        }
        val members = o.opt("members") as? JSONArray ?: return
        for (i in 0 until members.length()) {
            val m = members.opt(i) as? JSONObject ?: continue
            if (m.has("joinedAt") && !m.isNull("joinedAt") && m.opt("joinedAt") !is String) {
                throw IllegalStateException(
                    "member[$i].joinedAt is not a String — same .iso8601 decoder, same cost.",
                )
            }
        }
    }

}
