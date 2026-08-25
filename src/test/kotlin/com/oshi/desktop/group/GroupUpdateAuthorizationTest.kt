package com.oshi.desktop.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Authorization for inbound `📢GROUP_UPDATE📢` payloads — PARITY.md row 0.17's security half.
 *
 * The reference is the Swift, not this client's Kotlin:
 * `MessageGroupManager.authorizeGroupUpdate` (`OSHI/GroupMessaging.swift:863-905`) and
 * `applyGroupUpdate` (`:908-931`), with Android's port at `GroupUpdateAuthorizer.kt:49-139`
 * and its own suite at `GroupUpdateAuthorizationTest.kt`.
 *
 * Every input is a hand-written literal — rosters, sender keys and the expected `reason`
 * tags. Nothing under test builds its own fixture, so deleting a rule makes these fail
 * rather than move with the code.
 */
class GroupUpdateAuthorizationTest {

    private val adminKey = "AAAAadminkey"
    private val memberKey = "BBBBmemberkey"
    private val strangerKey = "ZZZZstrangerkey"

    private val members = listOf(adminKey, memberKey)
    private val admins = listOf(adminKey)

    // ---------------------------------------------------------------- standing

    @Test
    fun `a sender who is not a member is refused outright`() {
        val d = GroupUpdateAuthorizer.authorize(members, admins, GroupType.COLLABORATIVE, strangerKey)
        assertFalse("a non-member must not be able to write anything", d.apply)
        assertEquals("sender-not-a-member", d.reason)
        assertTrue(d.keepAdminSet)
        assertTrue(d.keepMembership)
        assertTrue(d.keepName)
    }

    @Test
    fun `a stranger is refused in an admin-only group too`() {
        val d = GroupUpdateAuthorizer.authorize(members, admins, GroupType.ADMIN_ONLY, strangerKey)
        assertFalse(d.apply)
        assertEquals("sender-not-a-member", d.reason)
    }

    @Test
    fun `a stranger is refused in a public group too`() {
        val d = GroupUpdateAuthorizer.authorize(members, admins, GroupType.PUBLIC, strangerKey)
        assertFalse(d.apply)
        assertEquals("sender-not-a-member", d.reason)
    }

    // ---------------------------------------------------------------- authority

    @Test
    fun `an admin may change everything`() {
        val d = GroupUpdateAuthorizer.authorize(members, admins, GroupType.ADMIN_ONLY, adminKey)
        assertTrue(d.apply)
        assertEquals("admin", d.reason)
        assertFalse("an admin's admin set is taken", d.keepAdminSet)
        assertFalse(d.keepMembership)
        assertFalse(d.keepName)
    }

    /**
     * The escalation this whole file exists to stop: `isAdmin` is a plain boolean in the
     * received JSON, so an ordinary member must never have their claim to it believed.
     */
    @Test
    fun `an ordinary member of an open group may not touch the admin set`() {
        val d = GroupUpdateAuthorizer.authorize(members, admins, GroupType.COLLABORATIVE, memberKey)
        assertTrue(d.apply)
        assertEquals("member-of-open-group", d.reason)
        assertTrue("a member must never promote themselves", d.keepAdminSet)
        assertFalse("but an open group lets any member add", d.keepMembership)
        assertFalse("and rename", d.keepName)
    }

    @Test
    fun `an ordinary member of an admin-only group may touch nothing structural`() {
        val d = GroupUpdateAuthorizer.authorize(members, admins, GroupType.ADMIN_ONLY, memberKey)
        assertTrue("cosmetic fields still get through", d.apply)
        assertEquals("member-of-admin-only-group", d.reason)
        assertTrue(d.keepAdminSet)
        assertTrue(d.keepMembership)
        assertTrue(d.keepName)
    }

    // ---------------------------------------------------------------- version

    @Test
    fun `an older definition is a replay and is ignored`() {
        val d = GroupUpdateAuthorizer.authorize(
            members, admins, GroupType.COLLABORATIVE, adminKey,
            currentStateVersion = 5, incomingStateVersion = 4,
        )
        assertFalse(d.apply)
        assertEquals("stale-version", d.reason)
    }

    @Test
    fun `an equal version is not a replay`() {
        val d = GroupUpdateAuthorizer.authorize(
            members, admins, GroupType.COLLABORATIVE, adminKey,
            currentStateVersion = 5, incomingStateVersion = 5,
        )
        assertTrue(d.apply)
        assertEquals("admin", d.reason)
    }

    /**
     * The compatibility half. Android emits no `stateVersion` at all and neither does any
     * iOS build before 2026-08-16, so a one-sided comparison would make every legacy peer
     * look permanently stale and the group would stop updating across platforms, silently
     * (`OSHI/GroupMessaging.swift:129-140`).
     */
    @Test
    fun `a one-sided version is never compared`() {
        assertEquals(
            "admin",
            GroupUpdateAuthorizer.authorize(
                members, admins, GroupType.COLLABORATIVE, adminKey,
                currentStateVersion = 99, incomingStateVersion = null,
            ).reason,
        )
        assertEquals(
            "admin",
            GroupUpdateAuthorizer.authorize(
                members, admins, GroupType.COLLABORATIVE, adminKey,
                currentStateVersion = null, incomingStateVersion = 1,
            ).reason,
        )
    }

    // ---------------------------------------------------------------- unauthenticated

    @Test
    fun `an unauthenticated transport never hands over the admin set`() {
        listOf(null, "", "   ").forEach { sender ->
            val d = GroupUpdateAuthorizer.authorize(members, admins, GroupType.COLLABORATIVE, sender)
            assertTrue(d.apply)
            assertEquals("unauthenticated-transport", d.reason)
            assertTrue("sender=$sender must not carry an admin set", d.keepAdminSet)
        }
    }

    /** The desktop's stricter stance — see [GroupUpdateAuthorizer.requireAuthenticatedSender]. */
    @Test
    fun `requireAuthenticatedSender refuses what the phones tolerate`() {
        assertFalse(GroupUpdateAuthorizer.requireAuthenticatedSender(null))
        assertFalse(GroupUpdateAuthorizer.requireAuthenticatedSender(""))
        assertFalse(GroupUpdateAuthorizer.requireAuthenticatedSender("  "))
        assertTrue(GroupUpdateAuthorizer.requireAuthenticatedSender(adminKey))
    }

    // ---------------------------------------------------------------- key skew

    /**
     * Base64url/padding skew is real enough that Android carries dedicated code for it
     * (PLAN_MESH.md §7 defect 2). An admin whose key arrives in the other spelling must
     * still be an admin, or a routine sync silently demotes them.
     */
    @Test
    fun `an admin is recognised across base64url and padding skew`() {
        val padded = "q83vqw=="          // valid base64
        val urlish = "q83vqw"            // same bytes, unpadded
        val d = GroupUpdateAuthorizer.authorize(
            currentMemberKeys = listOf(padded, memberKey),
            currentAdminKeys = listOf(padded),
            currentType = GroupType.ADMIN_ONLY,
            sender = urlish,
        )
        assertEquals("admin", d.reason)
    }

    /** …but a string that is not base64 after folding is NOT folded onto anything. */
    @Test
    fun `a non-base64 lookalike is not folded onto a real key`() {
        assertEquals("!!!not-base64!!!", GroupIdentity.canonicalIdentity("!!!not-base64!!!"))
        val d = GroupUpdateAuthorizer.authorize(
            currentMemberKeys = listOf(adminKey), currentAdminKeys = listOf(adminKey),
            currentType = GroupType.ADMIN_ONLY, sender = "!!!not-base64!!!",
        )
        assertEquals("sender-not-a-member", d.reason)
    }

    // ---------------------------------------------------------------- apply

    private fun def(
        members: List<GroupMember>,
        type: GroupType = GroupType.COLLABORATIVE,
        name: String = "Team",
        picture: String? = null,
    ) = GroupDefinition(
        groupId = "3F2504E0-4F89-11D3-9A0C-0305E82C3301",
        name = name, type = type, adminPublicKey = adminKey, members = members,
        createdAtUnixMillis = 1786622400000L, lastActivityUnixMillis = 1786622400000L,
        groupPictureBase64 = picture,
    )

    /**
     * `keepAdminSet` re-stamps, it does not revert: the incoming roster is kept and each
     * member's `isAdmin` is rewritten from OUR copy, so a member the update introduces
     * cannot arrive pre-promoted (`OSHI/GroupMessaging.swift:913-921`).
     */
    @Test
    fun `a new member cannot arrive pre-promoted`() {
        val current = def(
            listOf(
                GroupMember(adminKey, null, 1L, isAdmin = true),
                GroupMember(memberKey, null, 1L, isAdmin = false),
            ),
        )
        val incoming = def(
            listOf(
                GroupMember(adminKey, null, 1L, isAdmin = true),
                GroupMember(memberKey, null, 1L, isAdmin = true),   // self-promotion
                GroupMember(strangerKey, null, 1L, isAdmin = true), // arrives pre-promoted
            ),
        )
        val d = GroupUpdateAuthorizer.authorize(current, memberKey, incoming)
        assertEquals("member-of-open-group", d.reason)
        val merged = GroupUpdateAuthorizer.apply(current, incoming, d)
        assertEquals(
            "the new member is accepted",
            listOf(adminKey, memberKey, strangerKey), merged.memberKeys,
        )
        assertEquals("but only the real admin is an admin", listOf(adminKey), merged.adminKeys)
        assertEquals(adminKey, merged.adminPublicKey)
    }

    @Test
    fun `an admin-only group keeps its roster and name from an ordinary member`() {
        val current = def(
            listOf(
                GroupMember(adminKey, null, 1L, isAdmin = true),
                GroupMember(memberKey, null, 1L, isAdmin = false),
            ),
            type = GroupType.ADMIN_ONLY, name = "Real Name",
        )
        val incoming = def(
            listOf(GroupMember(strangerKey, null, 1L, isAdmin = true)),
            type = GroupType.ADMIN_ONLY, name = "Hijacked",
        )
        val d = GroupUpdateAuthorizer.authorize(current, memberKey, incoming)
        val merged = GroupUpdateAuthorizer.apply(current, incoming, d)
        assertEquals("Real Name", merged.name)
        assertEquals(listOf(adminKey, memberKey), merged.memberKeys)
    }

    /**
     * A lightweight broadcast strips the blobs (`OSHI/GroupMessaging.swift:2225-2233`), so
     * an ABSENT picture means "not sent", never "removed" — iOS restores the local one at
     * `swift:1006-1016`. Treating absent as removed would make every periodic sync wipe the
     * group picture.
     */
    @Test
    fun `a lightweight broadcast does not erase the group picture`() {
        val current = def(listOf(GroupMember(adminKey, null, 1L, isAdmin = true)), picture = "aGVsbG8=")
        val incoming = def(listOf(GroupMember(adminKey, null, 1L, isAdmin = true)), picture = null)
        val d = GroupUpdateAuthorizer.authorize(current, adminKey, incoming)
        assertEquals("aGVsbG8=", GroupUpdateAuthorizer.apply(current, incoming, d).groupPictureBase64)
    }

    @Test
    fun `mute is always ours`() {
        val current = def(listOf(GroupMember(adminKey, null, 1L, isAdmin = true))).copy(isMuted = true)
        val incoming = def(listOf(GroupMember(adminKey, null, 1L, isAdmin = true))).copy(isMuted = false)
        val d = GroupUpdateAuthorizer.authorize(current, adminKey, incoming)
        assertTrue(GroupUpdateAuthorizer.apply(current, incoming, d).isMuted)
    }

    @Test
    fun `restampAdminSet keeps the local admin set when the update carried no roster`() {
        assertEquals(admins, GroupUpdateAuthorizer.restampAdminSet(admins, emptyList()))
        assertEquals(
            listOf(adminKey),
            GroupUpdateAuthorizer.restampAdminSet(admins, listOf(adminKey, memberKey, strangerKey)),
        )
    }

    // ---------------------------------------------------------------- self in roster

    /**
     * iOS's ingest guard for an UNKNOWN group (`swift:1043-1048`): never materialise a group
     * whose roster does not contain us, or the broadcast a peer sends back after we left
     * re-creates the group we just walked out of.
     */
    @Test
    fun `a group we are not in is never materialised`() {
        assertFalse(GroupUpdateAuthorizer.isSelfInRoster(memberKey, listOf(adminKey, strangerKey)))
        assertTrue(GroupUpdateAuthorizer.isSelfInRoster(memberKey, listOf(adminKey, memberKey)))
        assertFalse("a blank key is nobody", GroupUpdateAuthorizer.isSelfInRoster("", listOf("")))
    }

    @Test
    fun `self is recognised in the roster across key skew`() {
        assertTrue(GroupUpdateAuthorizer.isSelfInRoster("q83vqw", listOf("q83vqw==")))
    }
}
