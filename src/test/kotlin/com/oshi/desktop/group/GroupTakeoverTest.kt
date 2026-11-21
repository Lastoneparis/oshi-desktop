package com.oshi.desktop.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __GROUP_TYPE_TAKEOVER_2026_11_20__ / __GROUP_VERSION_FREEZE_2026_11_20__ /
 * __GROUP_ADMIN_KICK_2026_11_20__ What a NON-admin's full definition may not change.
 */
class GroupTakeoverTest {
    private val admin = "AAAAadminkey"
    private val member = "BBBBmemberkey"
    private val evicted = "EEEEevictedkey"

    private fun def(type: GroupType, members: List<GroupMember>, v: Int? = 3) = GroupDefinition(
        groupId = "3f2a9c10-1b2c-4d5e-8f90-a1b2c3d4e5f6", name = "G", type = type, adminPublicKey = admin,
        members = members, createdAtUnixMillis = 1, lastActivityUnixMillis = 1,
        stateVersion = v, avatar = "a", pinnedMessageId = "p", description = "d",
        evictedMemberKeys = listOf(evicted),
    )
    private val roster = listOf(GroupMember(admin, joinedAtUnixMillis = 1, isAdmin = true), GroupMember(member, joinedAtUnixMillis = 1))

    private fun merge(current: GroupDefinition, incoming: GroupDefinition, sender: String) =
        GroupUpdateAuthorizer.apply(current, incoming, GroupUpdateAuthorizer.authorize(current, sender, incoming))

    @Test
    fun `a member of an admin-only group cannot open the group by changing its type`() {
        val cur = def(GroupType.ADMIN_ONLY, roster)
        val out = merge(cur, cur.copy(type = GroupType.COLLABORATIVE, avatar = "x", pinnedMessageId = "y", description = "z", stateVersion = 4), member)
        assertEquals(GroupType.ADMIN_ONLY, out.type)
        assertEquals("a", out.avatar)
        assertEquals("p", out.pinnedMessageId)
        assertEquals("d", out.description)
    }

    @Test
    fun `an admin still changes the type`() {
        val cur = def(GroupType.ADMIN_ONLY, roster)
        assertEquals(GroupType.COLLABORATIVE, merge(cur, cur.copy(type = GroupType.COLLABORATIVE, stateVersion = 4), admin).type)
    }

    @Test
    fun `a huge version cannot freeze later admin updates`() {
        val cur = def(GroupType.COLLABORATIVE, roster)
        val frozen = merge(cur, cur.copy(stateVersion = Int.MAX_VALUE), member)
        assertEquals(3 + GroupUpdateAuthorizer.MAX_VERSION_STEP, frozen.stateVersion)
        val next = frozen.copy(name = "admin rename", stateVersion = frozen.stateVersion!! + 1)
        assertTrue(GroupUpdateAuthorizer.authorize(frozen, admin, next).apply)
    }

    @Test
    fun `an open-group member cannot drop the admin or re-add an evicted key`() {
        val cur = def(GroupType.COLLABORATIVE, roster)
        val out = merge(cur, cur.copy(members = listOf(GroupMember(member, joinedAtUnixMillis = 1), GroupMember(evicted, joinedAtUnixMillis = 1)), stateVersion = 4), member)
        val keys = out.members.map { it.publicKey }
        assertTrue(admin in keys)
        assertTrue(evicted !in keys)
        assertTrue(out.members.first { it.publicKey == admin }.isAdmin)
    }
}
