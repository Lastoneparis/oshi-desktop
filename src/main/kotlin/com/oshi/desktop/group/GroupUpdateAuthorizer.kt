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

    const val REASON_ADMIN = "admin"

    /** __GROUP_VERSION_FREEZE_2026_11_20__ the largest forward jump one update may make. */
    const val MAX_VERSION_STEP = 1024

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
                reason = REASON_ADMIN,
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

        // __GROUP_TYPE_TAKEOVER_2026_11_20__ The TYPE decides who may change everything else, so
        // only an admin may change it. Before: `merged = incoming` carried a non-admin's `type`
        // through, so a member of an ADMIN_ONLY group sent `type = COLLABORATIVE` once and from
        // then on passed the "member-of-open-group" rule — add/remove anyone, rename, drop the admin.
        if (decision.reason != REASON_ADMIN) merged = merged.copy(type = current.type)
        if (decision.reason != REASON_ADMIN && !decision.keepMembership) {
            // __GROUP_ADMIN_KICK_2026_11_20__ an open-group member's roster may add and drop
            // members, but cannot drop an admin or bring back a key an admin evicted.
            val have = merged.members.map { GroupIdentity.canonicalIdentity(it.publicKey) }.toSet()
            val keptAdmins = current.members.filter { it.isAdmin && GroupIdentity.canonicalIdentity(it.publicKey) !in have }
            val evicted = current.evictedMemberKeys.map(GroupIdentity::canonicalIdentity).toSet()
            val currentKeys = current.members.map { GroupIdentity.canonicalIdentity(it.publicKey) }.toSet()
            merged = merged.copy(
                members = merged.members.filter {
                    val k = GroupIdentity.canonicalIdentity(it.publicKey)
                    k !in evicted || k in currentKeys
                } + keptAdmins,
            )
        }
        // In an ADMIN_ONLY group a member changes nothing about the group itself: picture,
        // wallpaper, avatar, pinned message and description stay the admin's.
        if (decision.keepName) {
            merged = merged.copy(
                groupPictureBase64 = current.groupPictureBase64,
                groupPictureUpdatedAtUnixMillis = current.groupPictureUpdatedAtUnixMillis,
                groupPictureUpdatedBy = current.groupPictureUpdatedBy,
                groupWallpaperBase64 = current.groupWallpaperBase64,
                groupWallpaperUpdatedAtUnixMillis = current.groupWallpaperUpdatedAtUnixMillis,
                groupWallpaperUpdatedBy = current.groupWallpaperUpdatedBy,
                avatar = current.avatar,
                pinnedMessageId = current.pinnedMessageId,
                pinnedBy = current.pinnedBy,
                description = current.description,
            )
        }

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
        // __GROUP_E2E_V2_2026_09_23__ spec §5.1/§5.3: evictedMemberKeys is a UNION that never
        // shrinks (only an admin's list is taken at all); local stateVersion = max(local, incoming);
        // an absent description is "not in this broadcast".
        val evicted = if (decision.keepMembership || decision.keepAdminSet) current.evictedMemberKeys
            else (current.evictedMemberKeys + merged.evictedMemberKeys)
        // __GROUP_VERSION_FREEZE_2026_11_20__ The incoming version is sender-chosen. One
        // definition at Int.MAX_VALUE made every later admin update "stale-version" forever.
        // A version may move forward, but only by a bounded step.
        val version = listOfNotNull(
            current.stateVersion,
            incoming.stateVersion?.let { v ->
                val base = current.stateVersion ?: 0
                if (v.toLong() > base.toLong() + MAX_VERSION_STEP) base + MAX_VERSION_STEP else v
            },
        ).maxOrNull()
        return merged.copy(
            isMuted = current.isMuted,
            evictedMemberKeys = evicted.distinctBy(GroupIdentity::canonicalIdentity),
            stateVersion = version,
            description = merged.description ?: current.description,
            settings = mergeSettings(current.settings, incoming.settings, decision),
        )
    }

    /**
     * __GROUP_INFO_SETTINGS_2026_11_20__ GROUP_INFO_SETTINGS_SPEC §2.1 receiver rule:
     *  1. key absent ⇒ keep ours (an old build re-emits the definition without it);
     *  2. present from an ADMIN of our copy ⇒ replace, `dissolved` OR-ed (never back to false);
     *  3. present from anyone else (a member of ANY group type, or an unauthenticated
     *     transport) ⇒ ignored as a whole (`settings-not-admin`).
     */
    fun mergeSettings(current: GroupSettings?, incoming: GroupSettings?, decision: GroupUpdateDecision): GroupSettings? {
        if (incoming == null) return current
        if (decision.reason != REASON_ADMIN) return current
        return incoming.copy(dissolved = incoming.dissolved || current?.dissolved == true)
    }

    /** True when [incoming] carried settings that [mergeSettings] refused (for the log). */
    fun settingsRefused(incoming: GroupDefinition, decision: GroupUpdateDecision): Boolean =
        incoming.settings != null && decision.reason != REASON_ADMIN

    // ------------------------------------------------------------------ joins (spec §3.3)

    /** What the admin side does with a join (§3.3). [reason] is asserted by name in tests. */
    data class JoinDecision(val verdict: Verdict, val reason: String, val group: GroupDefinition? = null) {
        enum class Verdict {
            /** Add the requester, stateVersion+1, broadcast to the new roster. */
            ACCEPT,
            /** Already a member: answer with our copy, change nothing. */
            ANSWER,
            REFUSE,
        }
    }

    /** At most this many accepted public-link joins per group per hour per device (§3.3 rule 6). */
    const val JOINS_PER_HOUR = 60

    /**
     * __GROUP_INFO_SETTINGS_2026_11_20__ §3.3, `join_request` (by handle). Pure.
     *
     * @param groups every group this device holds (the handle is looked up among them).
     * @param acceptedLastHour how many joins this device accepted for a group in the past hour.
     */
    fun authorizeHandleJoin(
        sender: String?,
        requester: String,
        handle: String,
        groups: List<GroupDefinition>,
        self: String,
        acceptedLastHour: (groupId: String) -> Int,
        isBlocked: (String) -> Boolean = { false },
    ): JoinDecision {
        if (sender.isNullOrBlank()) return JoinDecision(JoinDecision.Verdict.REFUSE, "unauthenticated")
        if (!GroupIdentity.sameIdentity(requester, sender)) return JoinDecision(JoinDecision.Verdict.REFUSE, "body-names-a-third-party")
        val h = GroupSettings.wireHandle(handle) ?: return JoinDecision(JoinDecision.Verdict.REFUSE, "unknown-handle")
        val g = groups.firstOrNull { it.settings?.isPublic == true && it.settings.handle == h }
            ?: return JoinDecision(JoinDecision.Verdict.REFUSE, "unknown-handle")
        if (!g.isAdmin(self)) return JoinDecision(JoinDecision.Verdict.REFUSE, "not-admin", g)
        if (g.isDissolved) return JoinDecision(JoinDecision.Verdict.REFUSE, "dissolved", g)
        if (g.isEvicted(sender) || isBlocked(sender)) return JoinDecision(JoinDecision.Verdict.REFUSE, "banned", g)
        if (g.isMember(sender)) return JoinDecision(JoinDecision.Verdict.ANSWER, "member", g)
        if (acceptedLastHour(g.groupId) >= JOINS_PER_HOUR) return JoinDecision(JoinDecision.Verdict.REFUSE, "rate-limited", g)
        return JoinDecision(JoinDecision.Verdict.ACCEPT, "joined-via-link", g)
    }

    /**
     * __GROUP_INFO_SETTINGS_2026_11_20__ §3.3, `member_sync_request` from a NON-member (an invite
     * link join). Rules 1-3 are the shipped ones (unauthenticated / third party / evicted or
     * blocked), then: dissolved ⇒ refuse; a group with an invite token admits only the
     * matching token (constant time) — that is what "Reset link" revokes; without a token the
     * legacy rule stands. On desktop "may add members" through a link = we are an admin (the
     * 1.6 rule: only an admin answers an invite join).
     */
    fun authorizeInviteJoin(
        sender: String?,
        requester: String,
        group: GroupDefinition,
        presentedToken: String?,
        self: String,
        isBlocked: (String) -> Boolean = { false },
    ): JoinDecision {
        if (sender.isNullOrBlank()) return JoinDecision(JoinDecision.Verdict.REFUSE, "unauthenticated")
        if (!GroupIdentity.sameIdentity(requester, sender)) return JoinDecision(JoinDecision.Verdict.REFUSE, "body-names-a-third-party")
        if (group.isEvicted(sender) || isBlocked(sender)) return JoinDecision(JoinDecision.Verdict.REFUSE, "banned", group)
        if (group.isMember(sender)) return JoinDecision(JoinDecision.Verdict.ANSWER, "member", group)
        if (group.isDissolved) return JoinDecision(JoinDecision.Verdict.REFUSE, "dissolved", group)
        val token = group.settings?.inviteToken
        if (token != null && !GroupSettings.tokensMatch(token, presentedToken)) {
            return JoinDecision(JoinDecision.Verdict.REFUSE, "invite-revoked", group)
        }
        if (!group.isAdmin(self)) return JoinDecision(JoinDecision.Verdict.REFUSE, "not-admin", group)
        return JoinDecision(JoinDecision.Verdict.ACCEPT, if (token != null) "token" else "legacy", group)
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
