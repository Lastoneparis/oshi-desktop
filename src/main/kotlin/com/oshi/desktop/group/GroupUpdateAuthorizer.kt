package com.oshi.desktop.group

/**
 * Who is allowed to change what about a group — the security half of PARITY.md row 0.17.
 *
 * Port of iOS `MessageGroupManager.authorizeGroupUpdate` / `applyGroupUpdate`
 * (`OSHI/GroupMessaging.swift:862-990`, called at `:943-957`) and Android's
 * `GroupUpdateAuthorizer` (`OSHI-Android/.../service/GroupUpdateAuthorizer.kt:49-139`).
 * Both platforms landed this on 2026-08-16 under the tag
 * `__GROUP_UPDATE_AUTHORIZATION_2026_08_16__`, and the reason both did is worth quoting
 * rather than paraphrasing, because it is exactly the shape a desktop port would
 * reintroduce by accident:
 *
 *   > Before this, `handleIncomingGroupUpdate` replaced the stored group WHOLESALE —
 *   > `groups[index] = updatedGroup` — with no check of any kind. `isAdmin` is a plain
 *   > boolean decoded from the received JSON, so any member (indeed anyone who could get a
 *   > definition to us) could promote themselves, rewrite the member list, or rename the
 *   > group. Every admin-gated feature, present and future, was therefore gated on a flag
 *   > the requester could grant themselves. — `OSHI/GroupMessaging.swift:834-841`
 *
 * ============================================================ THREE RULES
 *
 * Cheapest first, and each one DOWNGRADES rather than rejecting, except where noted:
 *
 *  1. **VERSION.** A definition older than ours is a replay; ignore it entirely. Compared
 *     only when both sides carry a `stateVersion`, because Android never emits one and
 *     neither does any iOS build before 2026-08-16 — see [GroupDefinition].
 *  2. **STANDING.** An update from someone who is not currently a member of OUR copy is
 *     ignored outright. This is the one hard reject.
 *  3. **AUTHORITY.** The admin set is only ever taken from a sender who is ALREADY an admin
 *     here. Membership and name follow the group's own policy: any member in a collaborative
 *     or public group, admins only in an admin-only group.
 *
 * A failed check downgrades instead of throwing the payload away because cosmetic fields —
 * picture, wallpaper, pin — legitimately arrive from ordinary members, and a hard reject
 * would trade a privilege-escalation hole for a sync outage.
 *
 * ============================================================ `sender == null`
 *
 * The legacy-transport case, and the one place this file is knowingly permissive. iOS's
 * mesh and cloud paths carry no authenticated sender; Android's VPS/IPFS route likewise
 * (`VPSClient.onGroupUpdateBroadcast` → `MessageRepository.kt:1059`). Both keep the
 * permissive behaviour for everything EXCEPT the admin set, which is never taken from an
 * unauthenticated update.
 *
 * **What this desktop client does about it is different, and stricter.** The desktop's own
 * ingest path is the v2 relay, where the envelope was decrypted with a specific peer's
 * ratchet — so the sender is KNOWN and there is no reason ever to call this with null.
 * [authorize] still implements the null branch, because a payload arriving over a transport
 * that lost the sender must behave the way the phones behave rather than crash; but
 * [requireAuthenticatedSender] exists so a caller can refuse the ambiguity outright, and
 * the v2 path uses it. See its doc.
 *
 * ============================================================ IDENTITY COMPARISON
 *
 * Every key comparison goes through [GroupIdentity.canonicalIdentity] — iOS's spelling, not
 * Android's. The difference and why the stricter one wins is written out in that file.
 *
 * Pure: no state, no I/O, no clock. Everything a decision depends on is an argument.
 */
object GroupUpdateAuthorizer {

    /** iOS `authorizeGroupUpdate` (`OSHI/GroupMessaging.swift:863-905`). */
    fun authorize(
        currentMemberKeys: List<String>,
        currentAdminKeys: List<String>,
        currentType: GroupType,
        sender: String?,
        currentStateVersion: Int? = null,
        incomingStateVersion: Int? = null,
    ): GroupUpdateDecision {
        // 1. Replay of an older definition. Both sides must carry a version, or this is
        //    inert — see GroupDefinition on why a defaulted 0 would strand every peer.
        if (currentStateVersion != null && incomingStateVersion != null &&
            incomingStateVersion < currentStateVersion
        ) {
            return GroupUpdateDecision(
                apply = false, keepAdminSet = true, keepMembership = true, keepName = true,
                reason = "stale-version",
            )
        }

        if (sender.isNullOrBlank()) {
            return GroupUpdateDecision(
                apply = true, keepAdminSet = true, keepMembership = false, keepName = false,
                reason = "unauthenticated-transport",
            )
        }

        val senderKey = GroupIdentity.canonicalIdentity(sender)

        // 2. Not one of us. The only hard reject.
        if (currentMemberKeys.none { GroupIdentity.canonicalIdentity(it) == senderKey }) {
            return GroupUpdateDecision(
                apply = false, keepAdminSet = true, keepMembership = true, keepName = true,
                reason = "sender-not-a-member",
            )
        }

        // 3. Authority.
        if (currentAdminKeys.any { GroupIdentity.canonicalIdentity(it) == senderKey }) {
            return GroupUpdateDecision(
                apply = true, keepAdminSet = false, keepMembership = false, keepName = false,
                reason = "admin",
            )
        }
        return when (currentType) {
            GroupType.COLLABORATIVE, GroupType.PUBLIC ->
                GroupUpdateDecision(
                    apply = true, keepAdminSet = true, keepMembership = false, keepName = false,
                    reason = "member-of-open-group",
                )
            GroupType.ADMIN_ONLY ->
                GroupUpdateDecision(
                    apply = true, keepAdminSet = true, keepMembership = true, keepName = true,
                    reason = "member-of-admin-only-group",
                )
        }
    }

    /** [authorize] taking the roster straight off a decoded definition. */
    fun authorize(
        current: GroupDefinition,
        sender: String?,
        incoming: GroupDefinition,
    ): GroupUpdateDecision = authorize(
        currentMemberKeys = current.memberKeys,
        currentAdminKeys = current.adminKeys,
        currentType = current.type,
        sender = sender,
        currentStateVersion = current.stateVersion,
        incomingStateVersion = incoming.stateVersion,
    )

    /**
     * Fold [incoming] into [current] according to [decision], keeping every field the
     * decision protects. iOS `applyGroupUpdate` (`OSHI/GroupMessaging.swift:908-931`).
     *
     * Three things here are not obvious and all three are load-bearing:
     *
     *  - **`keepAdminSet` re-stamps rather than reverts.** It keeps the incoming ROSTER and
     *    rewrites each member's `isAdmin` from our copy, keyed by identity. Reverting to our
     *    whole member list instead would also throw away legitimate additions; re-stamping
     *    means a member the update introduces cannot arrive pre-promoted (`swift:913-921`).
     *  - **The picture and the wallpaper survive a lightweight broadcast.** iOS strips both
     *    blobs from a routine update to keep it ~1-2 KB (`swift:2225-2233`), so an absent
     *    blob means "not sent", never "removed". iOS restores the local one at
     *    `swift:1006-1016`; so does this. Treating absent as removed would make every
     *    periodic sync wipe the group picture.
     *  - **`isMuted` is always ours.** A personal preference, re-imposed at `swift:1028`.
     */
    fun apply(
        current: GroupDefinition,
        incoming: GroupDefinition,
        decision: GroupUpdateDecision,
    ): GroupDefinition {
        var merged = incoming
        if (decision.keepAdminSet) {
            val admins = current.members.filter { it.isAdmin }
                .map { GroupIdentity.canonicalIdentity(it.publicKey) }
                .toSet()
            merged = merged.copy(
                adminPublicKey = current.adminPublicKey,
                members = merged.members.map { m ->
                    m.copy(isAdmin = GroupIdentity.canonicalIdentity(m.publicKey) in admins)
                },
            )
        }
        if (decision.keepMembership) merged = merged.copy(members = current.members)
        if (decision.keepName) merged = merged.copy(name = current.name)

        // Absent blob == "not in this broadcast", never "deleted" (swift:1006-1016).
        if (merged.groupPictureBase64 == null && current.groupPictureBase64 != null) {
            merged = merged.copy(
                groupPictureBase64 = current.groupPictureBase64,
                groupPictureUpdatedAtUnixMillis = current.groupPictureUpdatedAtUnixMillis,
                groupPictureUpdatedBy = current.groupPictureUpdatedBy,
            )
        }
        if (merged.groupWallpaperBase64 == null && current.groupWallpaperBase64 != null) {
            merged = merged.copy(
                groupWallpaperBase64 = current.groupWallpaperBase64,
                groupWallpaperUpdatedAtUnixMillis = current.groupWallpaperUpdatedAtUnixMillis,
                groupWallpaperUpdatedBy = current.groupWallpaperUpdatedBy,
            )
        }
        return merged.copy(isMuted = current.isMuted)
    }

    /**
     * The admin half of [apply] on its own, for a caller that holds key lists rather than a
     * definition. Android `restampAdminSet` (`GroupUpdateAuthorizer.kt:124-128`).
     *
     * An empty [mergedMemberKeys] means the update carried no roster, so there is nothing to
     * key on and the local admin set stands unchanged.
     */
    fun restampAdminSet(currentAdminKeys: List<String>, mergedMemberKeys: List<String>): List<String> {
        if (mergedMemberKeys.isEmpty()) return currentAdminKeys
        val admins = currentAdminKeys.map(GroupIdentity::canonicalIdentity).toSet()
        return mergedMemberKeys.filter { GroupIdentity.canonicalIdentity(it) in admins }
    }

    /**
     * The ingest guard for an UNKNOWN group: never materialise a group whose roster does not
     * contain us. iOS `swift:1043-1048`, Android `isSelfInRoster`
     * (`GroupUpdateAuthorizer.kt:135-139`).
     *
     * Without it, any broadcast — including the one a peer sends back after we left —
     * re-creates the group we just walked out of.
     */
    fun isSelfInRoster(myPublicKey: String, memberKeys: List<String>): Boolean {
        if (myPublicKey.isBlank()) return false
        val mine = GroupIdentity.canonicalIdentity(myPublicKey)
        return memberKeys.any { GroupIdentity.canonicalIdentity(it) == mine }
    }

    /**
     * Refuse an update whose sender is unknown — the desktop's stricter stance on the
     * `sender == null` branch, and the reason it is safe to take here and was not on the
     * phones.
     *
     * The phones keep the permissive branch because they have live transports that lose the
     * sender: iOS's `BroadcastGroupUpdate` mesh post carries only bytes, and Android's VPS
     * group-update callback carries none either. Dropping those payloads would have broken a
     * shipped path. This client has no such transport: its group ingest is the v2 relay,
     * where the envelope's `from` is not a claim in the JSON but the peer whose ratchet
     * decrypted it. So an unauthenticated group update on desktop is not a legacy peer, it
     * is a payload that arrived somewhere it cannot have arrived — and the honest response
     * is to drop it rather than apply it with the admin set protected and hope.
     *
     * Returns true when the caller may proceed to [authorize].
     */
    fun requireAuthenticatedSender(sender: String?): Boolean = !sender.isNullOrBlank()
}
