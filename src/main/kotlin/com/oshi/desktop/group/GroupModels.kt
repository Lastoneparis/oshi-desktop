package com.oshi.desktop.group

/**
 * The group data model of PARITY.md row 0.17, shaped by what the two shipped clients put on
 * the wire rather than by what a desktop app would find convenient.
 *
 * ============================================================ ONE GROUP, THREE PAYLOADS
 *
 * "A group" is not one thing on this wire. Three different JSON shapes all travel under the
 * `📢GROUP_UPDATE📢` sentinel, and a client that understands only one of them looks broken
 * in a way nobody can see:
 *
 *  1. **The full definition** — iOS's `MessageGroup`, this file's [GroupDefinition]. It is
 *     the only shape an iPhone ACTS on for anything structural: name, roster, per-member
 *     `isAdmin`, picture, wallpaper, pin (`OSHI/GroupMessaging.swift:929-1069`). Android's
 *     own comment on `buildIosMessageGroupJson` says every minimal shape it used to send
 *     "fell through to the `default:` at :992 and was dropped".
 *  2. **The minimal updates** — `{"type":"member_added"|"member_removed"|"group_renamed"|
 *     "member_sync_request"|"sync_request", …}`, handled by iOS's fallback switch
 *     (`swift:1071-1157`). See [MinimalGroupUpdate].
 *  3. **`{"type":"created", …}`** — a fourth shape with its OWN field names
 *     (`creatorPublicKey`, `members` as a bare key array, `isPublic`) that iOS EMITS
 *     (`OSHI/GroupMessaging.swift:1877-1900`) and **cannot itself parse** — `"created"` is
 *     not a case in its own switch, so an iPhone that receives one logs
 *     "Unknown group update type" and drops it. It exists solely for Android's
 *     `handleGroupCreated`. Modelled in [MinimalGroupUpdate.Created] and emitted only
 *     toward Android, because emitting it toward an iPhone is emitting nothing.
 *
 * ============================================================ WHAT `isAdmin` IS
 *
 * A plain boolean inside the received JSON. That is not a design this client chose and it
 * is the whole reason [GroupUpdateAuthorizer] exists: before 2026-08-16 both platforms took
 * the incoming definition wholesale, so anyone who could deliver one payload could promote
 * themselves. The field stays a boolean on the wire — changing it would talk to nobody —
 * and it is re-stamped from the local copy on ingest instead.
 *
 * ============================================================ FIELDS DELIBERATELY ABSENT
 *
 * - **`stateVersion`** is `Int?`, never `Int`. iOS's own note
 *   (`OSHI/GroupMessaging.swift:129-140`) is emphatic: Android and every shipped iOS build
 *   before 2026-08-16 encode no such key, so it decodes to `nil`, and a non-optional `Int`
 *   defaulting to 0 "would have made every legacy peer look permanently stale the moment
 *   our counter passed zero — the group would stop updating across platforms, silently."
 *   The replay comparison therefore runs only when BOTH sides carry one.
 * - **`isMuted` is not synced.** It is on the wire (iOS's `CodingKeys` include it) but iOS
 *   overwrites the received value with its own on every ingest (`swift:1028`), so Android
 *   pins it to `false` outbound (`GroupWireFormatTest.kt:195`). This client does the same
 *   and keeps the local value local.
 * - **No group key, no sender key, no epoch.** See [GroupFanout]. There is nothing in this
 *   model to hold one because the shipped clients hold none.
 */

/**
 * iOS `GroupType` (`OSHI/GroupMessaging.swift:35-40`). The raw values are the wire values
 * and there are exactly three of them; `type` is decoded with a plain
 * `container.decode(GroupType.self)` at `swift:195`, so a fourth spelling does not degrade
 * — it throws, and takes the entire group definition with it.
 */
enum class GroupType(val raw: String) {
    ADMIN_ONLY("admin_only"),
    COLLABORATIVE("collaborative"),
    PUBLIC("public");

    companion object {
        /** Exact match only. Null for anything iOS would throw on. */
        fun fromRaw(raw: String?): GroupType? = entries.firstOrNull { it.raw == raw }

        /**
         * What to EMIT when the local type is not one iOS knows.
         *
         * Android coerces to `"collaborative"` rather than shipping the unknown value
         * (`GroupManager.kt:194-197`, asserted at `GroupWireFormatTest.kt:116`), on the
         * grounds that a wrong-but-legal type costs one permission check while an illegal
         * one costs the whole payload. Same choice, same reason.
         */
        fun coerceForWire(raw: String?): GroupType = fromRaw(raw) ?: COLLABORATIVE
    }
}

/**
 * iOS `GroupMember` (`OSHI/GroupMessaging.swift:305-330`).
 *
 * `alias` is `decodeIfPresent` and must be ABSENT rather than `null` when unknown
 * (`GroupWireFormatTest.kt:163`). `joinedAt` has no Android equivalent, so both non-iOS
 * emitters fall back to the group's `createdAt` — which is also what iOS does when the key
 * is missing (`swift:234`).
 */
data class GroupMember(
    val publicKey: String,
    val alias: String? = null,
    val joinedAtUnixMillis: Long,
    val isAdmin: Boolean = false,
)

/**
 * iOS `MessageGroup` (`OSHI/GroupMessaging.swift:76-160`), as it appears on the wire.
 *
 * Only the fields that cross the wire are here. Everything iOS keeps that is local-only
 * (`currentUserPublicKey`, the computed permission properties) is a UI concern and is not
 * modelled: this row is the byte layer.
 *
 * Dates are Unix millis in memory and ISO-8601 on the wire; the conversion is
 * [com.oshi.desktop.msg.WireClock]'s and nowhere else's.
 */
data class GroupDefinition(
    val groupId: String,
    val name: String,
    val type: GroupType,
    val adminPublicKey: String,
    val members: List<GroupMember>,
    val createdAtUnixMillis: Long,
    val lastActivityUnixMillis: Long,
    val groupPictureBase64: String? = null,
    val groupPictureUpdatedAtUnixMillis: Long? = null,
    val groupPictureUpdatedBy: String? = null,
    val groupWallpaperBase64: String? = null,
    val groupWallpaperUpdatedAtUnixMillis: Long? = null,
    val groupWallpaperUpdatedBy: String? = null,
    val isMuted: Boolean = false,
    val pinnedMessageId: String? = null,
    val pinnedBy: String? = null,
    val avatar: String? = null,
    /** See the file doc: `Int?`, never `Int`, and never defaulted to 0. */
    val stateVersion: Int? = null,
) {
    /** Member keys in wire order. */
    val memberKeys: List<String> get() = members.map { it.publicKey }

    /** Keys of members carrying `isAdmin` in this copy. */
    val adminKeys: List<String> get() = members.filter { it.isAdmin }.map { it.publicKey }

    fun isMember(publicKey: String): Boolean =
        members.any { GroupIdentity.sameIdentity(it.publicKey, publicKey) }

    fun isAdmin(publicKey: String): Boolean =
        members.any { it.isAdmin && GroupIdentity.sameIdentity(it.publicKey, publicKey) }
}

/**
 * iOS `GroupUpdateDecision` (`OSHI/GroupMessaging.swift:292-302`), Android's port at
 * `GroupUpdateAuthorizer.kt:9-16`.
 *
 * `apply == false` means ignore the update entirely; each `keep*` names a field taken from
 * OUR copy instead of the sender's. [reason] is a machine-readable tag, asserted by name in
 * the tests on both shipped platforms and here — a decision that changes its mind about
 * WHY is a decision that changed.
 */
data class GroupUpdateDecision(
    val apply: Boolean,
    val keepAdminSet: Boolean,
    val keepMembership: Boolean,
    val keepName: Boolean,
    val reason: String,
)

/**
 * The four-and-a-bit `{"type":…}` shapes iOS's fallback switch understands
 * (`OSHI/GroupMessaging.swift:1071-1157`), plus the `"created"` shape it emits and cannot
 * read.
 *
 * Deliberately NOT modelled as a map of strings: `member_added` and `member_removed` differ
 * from each other only by that one word, and iOS's handlers for them do opposite things.
 */
sealed interface MinimalGroupUpdate {
    val groupId: String?

    /** `{"type":"member_added","groupId":…,"memberPublicKey":…}` — iOS `swift:1084`. */
    data class MemberAdded(override val groupId: String, val memberPublicKey: String) :
        MinimalGroupUpdate

    /** `{"type":"member_removed","groupId":…,"memberPublicKey":…}` — iOS `swift:1124`. */
    data class MemberRemoved(override val groupId: String, val memberPublicKey: String) :
        MinimalGroupUpdate

    /** `{"type":"group_renamed","groupId":…,"name":…}` — iOS `swift:1134`. */
    data class GroupRenamed(override val groupId: String, val name: String) : MinimalGroupUpdate

    /**
     * `{"type":"member_sync_request","groupId":…,"requesterPublicKey":…}` — iOS `swift:1105`.
     *
     * Note what iOS does with it: it **auto-adds the requester to the group** if they are
     * not already a member ("they have the group, so they're legitimate", `swift:1113`) and
     * then broadcasts the full roster back. That is an unauthenticated membership write, and
     * it is why this client never sends one to a group it is not already in.
     */
    data class MemberSyncRequest(
        override val groupId: String,
        val requesterPublicKey: String,
    ) : MinimalGroupUpdate

    /**
     * `{"type":"sync_request","requesterKey":…}` — iOS `swift:1147`. No `groupId`: it asks
     * for EVERY group the responder thinks the requester belongs to.
     */
    data class SyncRequest(val requesterKey: String) : MinimalGroupUpdate {
        override val groupId: String? get() = null
    }

    /**
     * `{"type":"created", …}` — the Android-only shape iOS emits at `swift:1877-1889` and
     * drops on receipt. Its field names are its own: `creatorPublicKey` (not
     * `adminPublicKey`), `members`/`admins` as bare key arrays (not member objects),
     * `isPublic` (not `type`), `groupImage` (not `groupPictureData`).
     */
    data class Created(
        override val groupId: String,
        val name: String,
        val description: String,
        val creatorPublicKey: String,
        val memberKeys: List<String>,
        val adminKeys: List<String>,
        val isPublic: Boolean,
        val groupImageBase64: String? = null,
    ) : MinimalGroupUpdate
}
