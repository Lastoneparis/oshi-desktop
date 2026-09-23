package com.oshi.desktop.app

import com.oshi.desktop.group.GroupDefinition
import com.oshi.desktop.group.GroupMember
import com.oshi.desktop.group.GroupMessageWire
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.group.GroupUpdateWire
import com.oshi.desktop.msg.ControlPrefix
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row 0.17's ingest path, and specifically the three functions that had no production
 * caller at all: `requireAuthenticatedSender`, `isSelfInRoster` and `restampAdminSet`.
 *
 * Each of the first three tests here fails if its guard is removed from [GroupIngest], which
 * is the only sense in which those functions are now guards rather than API.
 */
class GroupWiringTest {

    private val fx = WiringFixture()

    @After
    fun tearDown() = fx.close()

    private fun definition(
        groupId: String,
        members: List<Pair<String, Boolean>>,
        name: String = "crew",
        admin: String = members.first().first,
        version: Int? = 1,
    ): GroupDefinition {
        val now = 1_700_000_000_000L
        return GroupDefinition(
            groupId = groupId,
            name = name,
            type = GroupType.COLLABORATIVE,
            adminPublicKey = admin,
            members = members.map { (k, isAdmin) ->
                GroupMember(publicKey = k, joinedAtUnixMillis = now, isAdmin = isAdmin)
            },
            createdAtUnixMillis = now,
            lastActivityUnixMillis = now,
            stateVersion = version,
        )
    }

    // ============================================================ requireAuthenticatedSender

    @Test
    fun `a group update with no authenticated sender is refused outright`() {
        val me = fx.client("me")
        val ingest = GroupIngest(me.groups) { me.address }
        val def = definition("g1", listOf("peer" to true, me.address to false))

        val result = ingest.ingest(GroupUpdateWire.encodeDefinitionFramed(def), sender = null)

        assertEquals(GroupIngest.Outcome.REJECTED_UNAUTHENTICATED, result.outcome)
        assertTrue("an unauthenticated update created a group", me.groups.all().isEmpty())
    }

    // ============================================================ isSelfInRoster

    @Test
    fun `an unknown group whose roster does not contain us is never materialised`() {
        val me = fx.client("me")
        val ingest = GroupIngest(me.groups) { me.address }
        val def = definition("g2", listOf("peer" to true, "someone-else" to false))

        val result = ingest.ingest(GroupUpdateWire.encodeDefinitionFramed(def), sender = "peer")

        assertEquals(GroupIngest.Outcome.REJECTED_NOT_IN_ROSTER, result.outcome)
        assertNull("a group we are not in was created", me.groups.get("g2"))
    }

    @Test
    fun `an update whose merged roster drops us removes the group locally`() {
        val me = fx.client("me")
        val ingest = GroupIngest(me.groups) { me.address }
        val original = definition("g3", listOf("peer" to true, me.address to false))
        me.groups.put(original)

        val without = original.copy(members = original.members.filter { it.publicKey == "peer" }, stateVersion = 2)
        val result = ingest.ingest(GroupUpdateWire.encodeDefinitionFramed(without), sender = "peer")

        assertEquals(GroupIngest.Outcome.LEFT, result.outcome)
        assertNull("we were removed from a group and kept it", me.groups.get("g3"))
    }

    // ============================================================ left-group tombstone (devsync)

    @Test
    fun `a group left on this account is not re-created by a member's re-share, unless rejoined`() {
        // __DEVSYNC_REJOIN_2026_09_23__ the desktop's only tombstone is the devsync left-groups state.
        val me = fx.client("me")
        val ingest = GroupIngest(me.groups) { me.address }
        var left = true
        ingest.refusesGroup = { gid -> left && gid.equals("g9", ignoreCase = true) }
        val def = definition("g9", listOf("peer" to true, me.address to false))

        val refused = ingest.ingest(GroupUpdateWire.encodeDefinitionFramed(def), sender = "peer")
        assertEquals(GroupIngest.Outcome.REJECTED_NOT_PERMITTED, refused.outcome)
        assertNull("a member's re-share walked us back into a group we left", me.groups.get("g9"))

        left = false // the user rejoined on another device of the account
        val created = ingest.ingest(GroupUpdateWire.encodeDefinitionFramed(def), sender = "peer")
        assertEquals(GroupIngest.Outcome.CREATED, created.outcome)
        assertNotNull(me.groups.get("g9"))
    }

    // ============================================================ restampAdminSet

    @Test
    fun `a member added by a minimal update cannot arrive pre-promoted`() {
        val me = fx.client("me")
        val ingest = GroupIngest(me.groups) { me.address }
        me.groups.put(definition("g4", listOf(me.address to true, "peer" to false)))

        val result = ingest.ingest(
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeMemberAdded("g4", "newcomer"),
            sender = "peer",
        )

        assertEquals(GroupIngest.Outcome.UPDATED, result.outcome)
        val g = me.groups.get("g4")!!
        assertEquals(3, g.members.size)
        assertFalse("the newcomer arrived as an admin", g.isAdmin("newcomer"))
        assertTrue("our own admin flag was lost in the re-stamp", g.isAdmin(me.address))
    }

    @Test
    fun `a removed admin stops being one in the re-stamped roster`() {
        val me = fx.client("me")
        val ingest = GroupIngest(me.groups) { me.address }
        me.groups.put(definition("g5", listOf(me.address to true, "peer" to true, "third" to false)))

        ingest.ingest(
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeMemberRemoved("g5", "peer"),
            sender = "peer",
        )

        val g = me.groups.get("g5")!!
        assertEquals(listOf(me.address, "third"), g.memberKeys)
        assertEquals("the admin set was not re-stamped against the new roster", listOf(me.address), g.adminKeys)
    }

    // ============================================================ the authorizer itself

    @Test
    fun `an update from someone who is not a member is the one hard reject`() {
        val me = fx.client("me")
        val ingest = GroupIngest(me.groups) { me.address }
        me.groups.put(definition("g6", listOf(me.address to true, "peer" to false)))

        val hostile = definition("g6", listOf(me.address to false, "attacker" to true), version = 9)
        val result = ingest.ingest(GroupUpdateWire.encodeDefinitionFramed(hostile), sender = "attacker")

        assertEquals(GroupIngest.Outcome.REJECTED_BY_AUTHORIZER, result.outcome)
        assertEquals("sender-not-a-member", result.detail)
        assertTrue("a non-member promoted themselves", me.groups.get("g6")!!.isAdmin(me.address))
    }

    @Test
    fun `an ordinary member cannot hand themselves the admin set`() {
        val me = fx.client("me")
        val ingest = GroupIngest(me.groups) { me.address }
        me.groups.put(definition("g7", listOf(me.address to true, "peer" to false)))

        val grab = definition("g7", listOf(me.address to false, "peer" to true), version = 2)
        val result = ingest.ingest(GroupUpdateWire.encodeDefinitionFramed(grab), sender = "peer")

        assertEquals(GroupIngest.Outcome.UPDATED, result.outcome)
        val g = me.groups.get("g7")!!
        assertTrue("the admin set was taken from a non-admin sender", g.isAdmin(me.address))
        assertFalse(g.isAdmin("peer"))
    }

    @Test
    fun `a non-admin cannot locally mutate a group roster or name`() {
        val me = fx.client("me")
        // __GROUP_E2E_V2_2026_09_23__ admin_only: in a collaborative/public group any member may
        // add and rename (GROUP_E2E_V2_SPEC §5.2), so the refusal is asserted where it applies.
        val original = definition("g10", listOf("peer" to true, me.address to false), name = "original")
            .copy(type = GroupType.ADMIN_ONLY)
        me.groups.put(original)

        assertNull(me.addGroupMember("g10", "newcomer"))
        assertNull(me.removeGroupMember("g10", "peer"))
        assertNull(me.renameGroup("g10", "rewritten"))

        assertEquals(original, me.groups.get("g10"))
    }

    @Test
    fun `only an admin can change roles and the creator cannot be demoted`() {
        val me = fx.client("me")
        val original = definition("g11", listOf(me.address to true, "peer" to false))
        me.groups.put(original)

        assertTrue(me.setGroupMemberAdmin("g11", "peer", true)!!.isAdmin("peer"))
        assertNull(me.setGroupMemberAdmin("g11", me.address, false))
        assertTrue(me.groups.get("g11")!!.isAdmin(me.address))

        val member = fx.client("member")
        member.groups.put(definition("g12", listOf("peer" to true, member.address to false)))
        assertNull(member.setGroupMemberAdmin("g12", member.address, true))
    }

    @Test
    fun `a member_sync_request from a non-member is refused unless it is an authenticated invite join to an admin`() {
        val me = fx.client("me")
        val ingest = GroupIngest(me.groups) { me.address }
        me.groups.put(definition("g8", listOf(me.address to true, "admin2" to true)))

        // Naming someone else: nobody can join on another key's behalf.
        val spoofed = ingest.ingest(
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeMemberSyncRequest("g8", "victim"),
            sender = "stranger",
        )
        assertEquals(GroupIngest.Outcome.REJECTED_NOT_PERMITTED, spoofed.outcome)
        assertFalse(me.groups.get("g8")!!.isMember("victim"))

        // We are not an admin of this one: nothing is written.
        me.groups.put(definition("g8b", listOf("boss" to true, me.address to false)))
        val notAdmin = ingest.ingest(
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeMemberSyncRequest("g8b", "stranger"),
            sender = "stranger",
        )
        assertEquals(GroupIngest.Outcome.REJECTED_NOT_PERMITTED, notAdmin.outcome)
        assertFalse(me.groups.get("g8b")!!.isMember("stranger"))

        // A blocked key is never admitted.
        ingest.isBlocked = { it == "blocked" }
        val blocked = ingest.ingest(
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeMemberSyncRequest("g8", "blocked"),
            sender = "blocked",
        )
        assertEquals(GroupIngest.Outcome.REJECTED_NOT_PERMITTED, blocked.outcome)

        // The invite path (GROUP_E2E_V2_SPEC §5.3): the authenticated requester, to an admin.
        val joined = ingest.ingest(
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeMemberSyncRequest("g8", "stranger"),
            sender = "stranger",
        )
        assertEquals(GroupIngest.Outcome.UPDATED, joined.outcome)
        val g = me.groups.get("g8")!!
        assertTrue(g.isMember("stranger"))
        assertFalse("a joiner arrived as admin", g.isAdmin("stranger"))
        assertEquals(2, g.stateVersion)
        assertNotNull(joined.respondTo)
        assertNotNull("the other members were not told", joined.broadcast)
    }

    @Test
    fun `a member_sync_request from a member is answered with our copy`() {
        val me = fx.client("me")
        val ingest = GroupIngest(me.groups) { me.address }
        me.groups.put(definition("g9", listOf(me.address to true, "peer" to false)))

        val result = ingest.ingest(
            ControlPrefix.GROUP_UPDATE + GroupUpdateWire.encodeMemberSyncRequest("g9", "peer"),
            sender = "peer",
        )

        assertEquals(GroupIngest.Outcome.SYNC_REQUESTED, result.outcome)
        assertNotNull("nothing to respond with", result.respondTo)
        assertEquals("g9", result.respondTo!!.groupId)
    }

    // ============================================================ group messages

    @Test
    fun `a group message is filed under the group, not the 1 to 1 thread`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        me.groups.put(definition("g10", listOf(me.address to true, peer to false)))

        val plaintext = GroupMessageWire.encodeForEnvelope(
            GroupMessageWire.GroupMessagePayload(
                messageId = "gm-1",
                groupId = "g10",
                senderPublicKey = peer,
                body = "standup in 5",
                timestampUnixMillis = System.currentTimeMillis(),
            )
        )
        fx.inbound(me, peer, plaintext, groupId = "g10")

        assertTrue("it landed in the 1:1 thread as base64 garbage", me.messages.messages(peer).isEmpty())
        assertEquals("standup in 5", me.messages.messages("g10").single().content)
    }

    @Test
    fun `a group message from a non-member is dropped`() {
        val me = fx.client("me")
        me.groups.put(definition("g11", listOf(me.address to true)))

        val plaintext = GroupMessageWire.encodeForEnvelope(
            GroupMessageWire.GroupMessagePayload(
                messageId = "gm-2",
                groupId = "g11",
                senderPublicKey = "stranger",
                body = "let me in",
                timestampUnixMillis = System.currentTimeMillis(),
            )
        )
        fx.inbound(me, "stranger", plaintext, groupId = "g11")

        assertTrue(me.messages.messages("g11").isEmpty())
    }

    /**
     * An iPhone uppercases every UUID it round-trips, so the second copy of a message that
     * reached us over two transports arrives with a different spelling of the same id.
     */
    @Test
    fun `a group message id is deduped case-insensitively`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        me.groups.put(definition("g12", listOf(me.address to true, peer to false)))

        fun deliver(id: String) = fx.inbound(
            me, peer,
            GroupMessageWire.encodeForEnvelope(
                GroupMessageWire.GroupMessagePayload(
                    messageId = id, groupId = "g12", senderPublicKey = peer,
                    body = "once", timestampUnixMillis = 1_700_000_000_000L,
                )
            ),
            groupId = "g12",
        )
        deliver("abc-def")
        deliver("ABC-DEF")

        assertEquals("the uppercase twin became a second row", 1, me.messages.messages("g12").size)
    }

    // ============================================================ fan-out

    @Test
    fun `sending to a group fans out one ciphertext per member and one message id`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val c = fx.client("c")

        val g = a.createGroup("trio", listOf(b.address, c.address))
        b.router.poll()
        c.router.poll()
        assertEquals("b never got the definition", 1, b.groups.all().size)
        assertEquals("c never got the definition", 1, c.groups.all().size)

        val report = a.sendGroupText(g.groupId, "morning")
        assertEquals(2, report.recipients)
        assertEquals(2, report.sent)

        b.router.poll()
        c.router.poll()
        val atB = b.messages.messages(g.groupId).single()
        val atC = c.messages.messages(g.groupId).single()
        assertEquals("morning", atB.content)
        assertEquals("one logical message must keep ONE id across the fan-out", atB.id, atC.id)
    }

    @Test
    fun `a blocked member is skipped by the fan-out`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val c = fx.client("c")
        val g = a.createGroup("trio", listOf(b.address, c.address))
        a.block(c.address)

        val report = a.sendGroupText(g.groupId, "not for c")

        assertEquals(listOf(c.address), report.skippedBlocked)
        assertEquals(1, report.sent)
        c.router.poll()
        assertTrue("a blocked member received a group message", c.messages.messages(g.groupId).isEmpty())
    }
}
