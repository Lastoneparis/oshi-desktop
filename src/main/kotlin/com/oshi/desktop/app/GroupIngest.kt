package com.oshi.desktop.app

import com.oshi.desktop.group.GroupDefinition
import com.oshi.desktop.group.GroupIdentity
import com.oshi.desktop.group.GroupMember
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.group.GroupUpdateAuthorizer
import com.oshi.desktop.group.GroupUpdateWire
import com.oshi.desktop.group.MinimalGroupUpdate

/**
 * The consumer PARITY.md row 0.17's authorizer was written for — the desktop's ONE group
 * ingest path.
 *
 * ============================================================ WHY THIS FILE EXISTS
 *
 * [GroupUpdateAuthorizer] was landed as a pure decision object with no caller, and three of
 * its functions had none at all: [GroupUpdateAuthorizer.requireAuthenticatedSender],
 * [GroupUpdateAuthorizer.isSelfInRoster] and [GroupUpdateAuthorizer.restampAdminSet]. Their
 * own doc comments describe the guard each one is; a guard with no call site is not a guard,
 * it is a comment. This class is where all three are on the PATH — the first statement of
 * [ingest], the gate in front of materialising an unknown group, and the admin re-stamp on
 * every roster edit.
 *
 * ============================================================ THE ORDER, AND WHY
 *
 *  1. **Authenticated sender or nothing.** The desktop's only group transport is the v2
 *     relay, where `from` is the peer whose ratchet decrypted the envelope, not a claim in
 *     the JSON. So `sender == null` here is not a legacy peer — it is a payload that
 *     arrived somewhere it cannot have arrived. The phones keep the permissive branch
 *     because their mesh and VPS routes genuinely lose the sender; this client refuses,
 *     which is what [GroupUpdateAuthorizer.requireAuthenticatedSender] exists to let it do.
 *  2. **A full definition for a group we do not have** is the dangerous case, because there
 *     is no local roster to authorise against. Two gates, both required:
 *     - we must be in the incoming roster ([GroupUpdateAuthorizer.isSelfInRoster]) —
 *       otherwise any broadcast, including one a peer sends after we LEFT, re-creates the
 *       group we just walked out of;
 *     - the SENDER must be in it too, or a stranger can materialise a group in our client
 *       naming whoever they like.
 *  3. **A definition for a group we DO have** goes through
 *     [GroupUpdateAuthorizer.authorize] + [GroupUpdateAuthorizer.apply] unchanged. Nothing
 *     here re-implements those rules.
 *  4. **A minimal `{"type":…}` update** carries no roster, so it cannot be merged by
 *     [GroupUpdateAuthorizer.apply]. It is authorised by asking [GroupUpdateAuthorizer]
 *     what this sender may change — `authorize(current, sender, current)`, which compares a
 *     definition to itself so the version rule is inert and only STANDING and AUTHORITY
 *     speak — and then edited field by field, with the admin set re-stamped through
 *     [GroupUpdateAuthorizer.restampAdminSet] so a member the update introduces cannot
 *     arrive pre-promoted.
 *
 * ============================================================ WHAT IT REFUSES TO COPY
 *
 * `member_sync_request` **does not auto-add the requester**. iOS does, reasoning "they have
 * the group, so they're legitimate" (`GroupMessaging.swift:1113`), which is an
 * unauthenticated membership write and is recorded as a defect in PARITY.md row 0.17. Here a
 * sync request from someone already in the roster is answered with our copy of the
 * definition ([Result.respondTo]); a sync request from anyone else is dropped.
 *
 * Pure apart from [groups]: no clock, no network. What to SEND in response is returned, never
 * sent from here — the poll thread owns the network.
 */
class GroupIngest(
    private val groups: GroupStore,
    private val selfAddress: () -> String,
) {

    /**
     * __DEVSYNC_REJOIN_2026_09_23__ The desktop's left-group tombstone: true for a group this account
     * LEFT (own-device sync state) and has not rejoined. Gate zero in front of materialising an
     * unknown group: a member whose roster still names us would otherwise walk us back in.
     */
    @Volatile var refusesGroup: (groupId: String) -> Boolean = { false }

    /** What [ingest] did. Every refusal is a distinct value so a refusal can be counted. */
    enum class Outcome {
        /** A group we had never seen, materialised. */
        CREATED,

        /** An existing group changed. */
        UPDATED,

        /** Understood, authorised, and the stored copy already said this. */
        NO_CHANGE,

        /** We are no longer in the roster: the group was removed locally. */
        LEFT,

        /** Answer with our copy — see [Result.respondTo]. Nothing was written. */
        SYNC_REQUESTED,

        /** No authenticated sender. See rule 1. */
        REJECTED_UNAUTHENTICATED,

        /** A new group whose roster does not contain us. */
        REJECTED_NOT_IN_ROSTER,

        /** A new group whose roster does not contain the sender. */
        REJECTED_SENDER_NOT_IN_ROSTER,

        /** [GroupUpdateAuthorizer] said `apply = false`. [Result.detail] carries its reason. */
        REJECTED_BY_AUTHORIZER,

        /** The sender is not permitted to change this field of this group. */
        REJECTED_NOT_PERMITTED,

        /** A minimal update naming a group this client has no copy of. */
        REJECTED_UNKNOWN_GROUP,

        /** Neither a definition nor a minimal update, or a definition an iPhone would throw on. */
        REJECTED_MALFORMED,
    }

    /**
     * @param respondTo the definition to broadcast back, set only for [Outcome.SYNC_REQUESTED].
     *   Returned rather than sent: this class does no I/O.
     */
    data class Result(
        val outcome: Outcome,
        val groupId: String?,
        val detail: String,
        val respondTo: GroupDefinition? = null,
    )

    fun ingest(plaintext: String, sender: String?): Result {
        // Rule 1 — the desktop's stricter stance on the `sender == null` branch.
        if (!GroupUpdateAuthorizer.requireAuthenticatedSender(sender)) {
            return Result(Outcome.REJECTED_UNAUTHENTICATED, null, "no authenticated sender on a group update")
        }
        val from = sender!!

        return when (val decoded = GroupUpdateWire.decodeFramed(plaintext)) {
            is GroupUpdateWire.GroupUpdateDecode.Ok -> definition(decoded.definition, from)
            is GroupUpdateWire.GroupUpdateDecode.Rejected ->
                Result(Outcome.REJECTED_MALFORMED, null, "definition rejected: ${decoded.reason}")
            is GroupUpdateWire.GroupUpdateDecode.NotADefinition -> minimal(plaintext, from)
        }
    }

    // ------------------------------------------------------------------ full definition

    private fun definition(incoming: GroupDefinition, sender: String): Result {
        val gid = GroupIdentity.canonicalGroupId(incoming.groupId)
        val current = groups.get(gid)

        if (current == null) {
            if (refusesGroup(gid)) {
                return Result(Outcome.REJECTED_NOT_PERMITTED, gid, "group left on this account (not rejoined)")
            }
            // Rule 2, gate one. Without it, a broadcast re-creates a group we LEFT.
            if (!GroupUpdateAuthorizer.isSelfInRoster(selfAddress(), incoming.memberKeys)) {
                return Result(Outcome.REJECTED_NOT_IN_ROSTER, gid, "we are not in the roster of an unknown group")
            }
            // __GROUP_E2E_V2_2026_09_23__ an admin evicted us: a stale roster still naming us must
            // not walk us back in (spec §5.1 evictedMemberKeys).
            if (incoming.isEvicted(selfAddress())) {
                return Result(Outcome.REJECTED_NOT_IN_ROSTER, gid, "we were evicted from this group")
            }
            // Rule 2, gate two. `authorize` cannot run this check for a group we do not
            // hold — there is no local roster for "sender-not-a-member" to consult — so the
            // equivalent standing check is made here against the INCOMING roster.
            if (!GroupUpdateAuthorizer.isSelfInRoster(sender, incoming.memberKeys)) {
                return Result(
                    Outcome.REJECTED_SENDER_NOT_IN_ROSTER, gid,
                    "the sender of an unknown group's definition is not in its own roster",
                )
            }
            groups.put(incoming)
            return Result(Outcome.CREATED, gid, "group created from ${sender.take(12)}…")
        }

        val decision = GroupUpdateAuthorizer.authorize(current, sender, incoming)
        if (!decision.apply) {
            return Result(Outcome.REJECTED_BY_AUTHORIZER, gid, decision.reason)
        }
        val merged = GroupUpdateAuthorizer.apply(current, incoming, decision)

        // Removal from a group arrives as an ordinary definition whose roster no longer
        // names us. Same guard as gate one, read the other way round.
        if (!GroupUpdateAuthorizer.isSelfInRoster(selfAddress(), merged.memberKeys)) {
            groups.delete(gid)
            return Result(Outcome.LEFT, gid, "removed from the roster by ${sender.take(12)}… (${decision.reason})")
        }

        if (merged == current) return Result(Outcome.NO_CHANGE, gid, decision.reason)
        groups.put(merged)
        return Result(Outcome.UPDATED, gid, decision.reason)
    }

    // ------------------------------------------------------------------ minimal updates

    private fun minimal(plaintext: String, sender: String): Result {
        val update = GroupUpdateWire.decodeMinimalFramed(plaintext)
            ?: return Result(Outcome.REJECTED_MALFORMED, null, "neither a definition nor a known minimal update")

        if (update is MinimalGroupUpdate.Created) return created(update, sender)

        // Every remaining shape names a group we must already hold. `sync_request` carries
        // no groupId at all and asks about every group — handled separately.
        if (update is MinimalGroupUpdate.SyncRequest) return syncRequest(update.requesterKey, sender)

        val gid = update.groupId?.let(GroupIdentity::canonicalGroupId)
            ?: return Result(Outcome.REJECTED_MALFORMED, null, "minimal update with no groupId")
        val current = groups.get(gid)
            ?: return Result(Outcome.REJECTED_UNKNOWN_GROUP, gid, "minimal update for a group we do not hold")

        // __GROUP_E2E_V2_2026_09_23__ spec §5.3 rule 4: `member_removed` naming the SENDER itself is
        // a leave, accepted from any current member whatever the group type (the sender is the
        // authenticated envelope `from`, so nobody can leave on someone else's behalf).
        if (update is MinimalGroupUpdate.MemberRemoved &&
            GroupIdentity.sameIdentity(update.memberPublicKey, sender)
        ) {
            if (!current.isMember(sender)) return Result(Outcome.NO_CHANGE, gid, "not a member")
            val remaining = current.members.filterNot { GroupIdentity.sameIdentity(it.publicKey, sender) }
            val admins = GroupUpdateAuthorizer.restampAdminSet(current.adminKeys, remaining.map { it.publicKey })
                .map(GroupIdentity::canonicalIdentity).toSet()
            groups.put(current.copy(members = remaining.map {
                it.copy(isAdmin = GroupIdentity.canonicalIdentity(it.publicKey) in admins)
            }))
            return Result(Outcome.UPDATED, gid, "${sender.take(12)}… left")
        }

        // A minimal update carries no roster, so `apply` has nothing to merge. Ask the
        // authorizer what this sender is allowed to change instead: comparing the current
        // definition to ITSELF makes the version rule inert, leaving STANDING and AUTHORITY.
        val decision = GroupUpdateAuthorizer.authorize(current, sender, current)
        if (!decision.apply) return Result(Outcome.REJECTED_BY_AUTHORIZER, gid, decision.reason)

        return when (update) {
            is MinimalGroupUpdate.MemberAdded -> {
                if (decision.keepMembership) {
                    return Result(Outcome.REJECTED_NOT_PERMITTED, gid, "membership: ${decision.reason}")
                }
                if (current.isMember(update.memberPublicKey)) {
                    return Result(Outcome.NO_CHANGE, gid, "already a member")
                }
                val added = GroupMember(
                    publicKey = update.memberPublicKey,
                    joinedAtUnixMillis = current.lastActivityUnixMillis,
                    // NEVER taken from the update. Re-stamped below, which is the whole
                    // point of restampAdminSet: an added member cannot arrive promoted.
                    isAdmin = false,
                )
                groups.put(restamped(current, current.members + added))
                Result(Outcome.UPDATED, gid, "member added by ${sender.take(12)}…")
            }

            is MinimalGroupUpdate.MemberRemoved -> {
                if (decision.keepMembership) {
                    return Result(Outcome.REJECTED_NOT_PERMITTED, gid, "membership: ${decision.reason}")
                }
                val remaining = current.members.filterNot {
                    GroupIdentity.sameIdentity(it.publicKey, update.memberPublicKey)
                }
                if (remaining.size == current.members.size) {
                    return Result(Outcome.NO_CHANGE, gid, "not a member")
                }
                if (!GroupUpdateAuthorizer.isSelfInRoster(selfAddress(), remaining.map { it.publicKey })) {
                    groups.delete(gid)
                    return Result(Outcome.LEFT, gid, "removed by ${sender.take(12)}…")
                }
                groups.put(restamped(current, remaining))
                Result(Outcome.UPDATED, gid, "member removed by ${sender.take(12)}…")
            }

            is MinimalGroupUpdate.GroupRenamed -> {
                if (decision.keepName) {
                    return Result(Outcome.REJECTED_NOT_PERMITTED, gid, "name: ${decision.reason}")
                }
                if (current.name == update.name) return Result(Outcome.NO_CHANGE, gid, "same name")
                groups.put(current.copy(name = update.name))
                Result(Outcome.UPDATED, gid, "renamed by ${sender.take(12)}…")
            }

            is MinimalGroupUpdate.MemberSyncRequest -> {
                // NOT iOS's behaviour. See WHAT IT REFUSES TO COPY.
                if (!current.isMember(update.requesterPublicKey)) {
                    return Result(
                        Outcome.REJECTED_NOT_PERMITTED, gid,
                        "member_sync_request from a non-member — refusing iOS's auto-add " +
                            "(GroupMessaging.swift:1113 is an unauthenticated membership write)",
                    )
                }
                Result(Outcome.SYNC_REQUESTED, gid, "roster requested", respondTo = current)
            }

            else -> Result(Outcome.REJECTED_MALFORMED, gid, "unhandled minimal update")
        }
    }

    /**
     * `{"type":"created"}` — the Android-shaped payload iOS emits and cannot itself read
     * (PARITY.md row 0.17). It carries a roster, so it goes through the SAME unknown-group
     * gates a definition does; it is not a shortcut past them.
     */
    private fun created(update: MinimalGroupUpdate.Created, sender: String): Result {
        val gid = GroupIdentity.canonicalGroupId(update.groupId)
        if (groups.get(gid) != null) return Result(Outcome.NO_CHANGE, gid, "already have this group")
        if (refusesGroup(gid)) return Result(Outcome.REJECTED_NOT_PERMITTED, gid, "group left on this account (not rejoined)")
        if (!GroupUpdateAuthorizer.isSelfInRoster(selfAddress(), update.memberKeys)) {
            return Result(Outcome.REJECTED_NOT_IN_ROSTER, gid, "we are not in the roster of a created group")
        }
        if (!GroupUpdateAuthorizer.isSelfInRoster(sender, update.memberKeys)) {
            return Result(Outcome.REJECTED_SENDER_NOT_IN_ROSTER, gid, "creator is not in the roster they sent")
        }
        val admins = GroupUpdateAuthorizer.restampAdminSet(update.adminKeys, update.memberKeys).toSet()
        val now = System.currentTimeMillis()
        val def = GroupDefinition(
            groupId = gid,
            name = update.name,
            type = if (update.isPublic) GroupType.PUBLIC else GroupType.COLLABORATIVE,
            adminPublicKey = update.creatorPublicKey,
            members = update.memberKeys.map {
                GroupMember(publicKey = it, joinedAtUnixMillis = now, isAdmin = it in admins)
            },
            createdAtUnixMillis = now,
            lastActivityUnixMillis = now,
            groupPictureBase64 = update.groupImageBase64,
        )
        groups.put(def)
        return Result(Outcome.CREATED, gid, "group created from a `created` payload")
    }

    /**
     * `{"type":"sync_request"}` — no groupId: it asks for every group we think the requester
     * belongs to. Answered only for groups the requester is ALREADY in, for the same reason
     * `member_sync_request` is.
     */
    private fun syncRequest(requesterKey: String, sender: String): Result {
        if (!GroupIdentity.sameIdentity(requesterKey, sender)) {
            return Result(
                Outcome.REJECTED_NOT_PERMITTED, null,
                "sync_request whose requesterKey is not the authenticated sender",
            )
        }
        val mine = groups.all().firstOrNull { it.isMember(sender) }
            ?: return Result(Outcome.REJECTED_NOT_PERMITTED, null, "sync_request from a peer in none of our groups")
        return Result(Outcome.SYNC_REQUESTED, mine.groupId, "roster requested", respondTo = mine)
    }

    /**
     * Rebuild a definition around a new roster with the admin flags taken from OUR copy.
     *
     * [GroupUpdateAuthorizer.restampAdminSet] is the shipped rule
     * (`GroupUpdateAuthorizer.kt:124-128`): filter our admin list down to keys that are
     * still in the merged roster. Anyone the update introduced is therefore not an admin,
     * and anyone it removed stops being one.
     */
    private fun restamped(current: GroupDefinition, roster: List<GroupMember>): GroupDefinition {
        val admins = GroupUpdateAuthorizer
            .restampAdminSet(current.adminKeys, roster.map { it.publicKey })
            .map(GroupIdentity::canonicalIdentity)
            .toSet()
        return current.copy(
            members = roster.map { it.copy(isAdmin = GroupIdentity.canonicalIdentity(it.publicKey) in admins) },
        )
    }
}
