package com.oshi.desktop.app

import com.oshi.desktop.group.GroupDefinition
import com.oshi.desktop.group.GroupEvents
import com.oshi.desktop.group.GroupHandleRegistry
import com.oshi.desktop.group.GroupMessageWire
import com.oshi.desktop.group.GroupSettings
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.group.GroupUpdateWire
import com.oshi.desktop.msg.ReactionPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __GROUP_INFO_SETTINGS_2026_11_20__ GROUP_INFO_SETTINGS_SPEC.md through the REAL client, over
 * the fixture relay: what an admin sets reaches the members; what a member forges does not
 * apply; channel mode and reaction limits are enforced by the receiver; reset links revoke;
 * a public-link join completes (against a stub registry — nothing here talks to production);
 * "Delete for everyone" leaves the members a read-only group.
 */
class GroupSettingsWiringTest {

    private val fx = WiringFixture()

    @After
    fun tearDown() = fx.close()

    /** The registry of §4 in memory, shared by every client of a test. */
    class StubRegistry : GroupHandleRegistry {
        val records = LinkedHashMap<String, Triple<String, List<String>, GroupHandleRegistry.Preview?>>()
        val deletes = ArrayList<String>()
        var failWrites = false
        override fun lookup(handle: String): GroupHandleRegistry.Lookup =
            records[handle]?.let { GroupHandleRegistry.Lookup.Found(handle, it.second, it.third) } ?: GroupHandleRegistry.Lookup.NotFound
        override fun register(handle: String, groupRef: String, joinKeys: List<String>, controllers: List<String>, preview: GroupHandleRegistry.Preview?): GroupHandleRegistry.Write {
            if (failWrites) return GroupHandleRegistry.Write.Failed("offline")
            val cur = records[handle]
            if (cur != null && cur.first != groupRef) return GroupHandleRegistry.Write.Taken("handle-taken")
            records.entries.removeIf { it.value.first == groupRef && it.key != handle }
            records[handle] = Triple(groupRef, joinKeys, preview)
            return GroupHandleRegistry.Write.Ok
        }
        override fun unregister(handle: String): GroupHandleRegistry.Write {
            if (failWrites) return GroupHandleRegistry.Write.Failed("offline")
            deletes += handle; records.remove(handle); return GroupHandleRegistry.Write.Ok
        }
    }

    private fun trio(type: GroupType = GroupType.ADMIN_ONLY): Triple<OshiClient, OshiClient, GroupDefinition> {
        val a = fx.client("admin")
        val b = fx.client("member")
        val g = a.createGroup("club", listOf(b.address), type)
        b.router.poll()
        assertNotNull("the member never got the group", b.groups.get(g.groupId))
        return Triple(a, b, g)
    }

    @Test
    fun `settings an admin sets reach the member, and the member cannot set any`() {
        val (a, b, g) = trio(GroupType.COLLABORATIVE)
        assertNotNull(a.setGroupPostPolicy(g.groupId, GroupSettings.PostPolicy.ADMINS))
        assertNotNull(a.setGroupReactions(g.groupId, GroupSettings.ReactionMode.SOME, listOf("👍", "🔥")))
        assertNotNull(a.setGroupWelcome(g.groupId, "Read the pinned message"))
        assertNotNull(a.setGroupAdminDMs(g.groupId, false))
        b.router.poll()
        val s = b.groups.get(g.groupId)!!.settings!!
        assertEquals(GroupSettings.PostPolicy.ADMINS, s.postPolicy)
        assertEquals(listOf("👍", "🔥"), s.allowedReactions)
        assertEquals("Read the pinned message", s.welcome)
        assertFalse(s.adminDMs)

        // The member's own client refuses every admin setting…
        assertNull(b.setGroupPostPolicy(g.groupId, GroupSettings.PostPolicy.EVERYONE))
        assertNull(b.setGroupType(g.groupId, GroupType.PUBLIC))
        assertNull(b.resetGroupInviteLink(g.groupId))
        assertFalse(b.dissolveGroup(g.groupId))
        assertEquals(OshiClient.PublicLinkOutcome.NOT_ADMIN, b.setGroupPublic(g.groupId, "my_handle", false))
        // …and a hand-made definition carrying settings is ignored by the admin's device.
        val forged = b.groups.get(g.groupId)!!.let { it.copy(stateVersion = (it.stateVersion ?: 0) + 1, settings = GroupSettings(dissolved = true), type = GroupType.PUBLIC) }
        fx.inbound(a, b.address, GroupUpdateWire.encodeDefinitionFramed(forged))
        val atAdmin = a.groups.get(g.groupId)!!
        assertEquals(GroupSettings.PostPolicy.ADMINS, atAdmin.settings!!.postPolicy)
        assertFalse(atAdmin.isDissolved)
        assertEquals(GroupType.COLLABORATIVE, atAdmin.type)
        assertTrue(fx.logs.any { it.contains("settings-not-admin") })
    }

    @Test
    fun `an old build's definition without settings does not wipe them`() {
        val (a, b, g) = trio()
        a.setGroupPostPolicy(g.groupId, GroupSettings.PostPolicy.ADMINS)
        b.router.poll()
        // The admin renames on an old iPhone: same definition, no `settings` key.
        val old = a.groups.get(g.groupId)!!.let { it.copy(name = "renamed", settings = null, stateVersion = (it.stateVersion ?: 0) + 1) }
        assertFalse(GroupUpdateWire.encodeDefinition(old).contains("\"settings\""))
        fx.inbound(b, a.address, GroupUpdateWire.encodeDefinitionFramed(old))
        val atMember = b.groups.get(g.groupId)!!
        assertEquals("renamed", atMember.name)
        assertEquals(GroupSettings.PostPolicy.ADMINS, atMember.settings!!.postPolicy)
    }

    @Test
    fun `channel mode drops a member's post at the receiver and refuses it at the sender`() {
        val (a, b, g) = trio()
        a.setGroupPostPolicy(g.groupId, GroupSettings.PostPolicy.ADMINS)
        b.router.poll()
        assertFalse(b.mayPostInGroup(g.groupId))
        assertTrue(b.sendGroupText(g.groupId, "hello?").refusedControlPayload)
        // An old build ignores the setting and sends anyway: the admin's device drops it.
        val gid = g.groupId
        val payload = GroupMessageWire.encodeForEnvelope(
            GroupMessageWire.GroupMessagePayload(
                messageId = "OLD-1", groupId = gid, senderPublicKey = b.address, body = "posted from an old build",
                timestampUnixMillis = System.currentTimeMillis(),
            )
        )
        fx.inbound(a, b.address, payload, groupId = gid)
        assertTrue(a.messages.messages(gid).none { it.content == "posted from an old build" })
        // The admin still posts, and the member receives it.
        assertEquals(1, a.sendGroupText(gid, "announcement").sent)
        b.router.poll()
        assertTrue(b.messages.messages(gid).any { it.content == "announcement" })
    }

    @Test
    fun `a reaction the group does not allow is dropped on receive, a removal always passes`() {
        val (a, b, g) = trio()
        val gid = g.groupId
        a.sendGroupText(gid, "vote")
        b.router.poll()
        val msg = b.messages.messages(gid).single()
        a.setGroupReactions(gid, GroupSettings.ReactionMode.SOME, listOf("👍"))
        b.router.poll()
        assertNull("the sender UI path refused nothing", b.sendGroupReaction(gid, msg.id, "🔥"))
        fun reaction(emoji: String, adding: Boolean) = GroupMessageWire.encodeForEnvelope(
            GroupMessageWire.GroupMessagePayload(
                messageId = "R-$emoji-$adding", groupId = gid, senderPublicKey = b.address,
                body = ReactionPayload.encode(messageId = msg.id, emoji = emoji, senderPublicKey = b.address, isAdding = adding, atUnixMillis = System.currentTimeMillis(), senderName = "member"),
                timestampUnixMillis = System.currentTimeMillis(),
            )
        )
        fx.inbound(a, b.address, reaction("🔥", true), groupId = gid)
        assertTrue(a.messages.messages(gid).single().reactions.isEmpty())
        fx.inbound(a, b.address, reaction("👍", true), groupId = gid)
        assertEquals(setOf("👍"), a.messages.messages(gid).single().reactions.keys)
        fx.inbound(a, b.address, reaction("👍", false), groupId = gid)
        assertTrue(a.messages.messages(gid).single().reactions.isEmpty())
    }

    @Test
    fun `reset link revokes the old link and the new one works`() {
        val a = fx.client("admin")
        val g = a.createGroup("club", emptyList())
        val oldLink = a.groupInviteLink(g.groupId)!!
        assertNotNull(a.resetGroupInviteLink(g.groupId))
        val newLink = a.groupInviteLink(g.groupId)!!
        assertTrue(newLink.contains("&t="))

        val late = fx.client("late")
        late.joinGroupFromInvite(oldLink)
        a.router.poll()
        assertFalse("an old link still admitted someone", a.groups.get(g.groupId)!!.isMember(late.address))

        val fresh = fx.client("fresh")
        fresh.joinGroupFromInvite(newLink)
        a.router.poll()
        assertTrue(a.groups.get(g.groupId)!!.isMember(fresh.address))
    }

    @Test
    fun `a public link join completes through the admin's device and shows the welcome once`() {
        val registry = StubRegistry()
        val a = fx.client("admin").also { it.handleRegistry = registry }
        val g = a.createGroup("Swiss Quant", emptyList())
        a.setGroupWelcome(g.groupId, "Bienvenue !")
        assertEquals(OshiClient.PublicLinkOutcome.INVALID, a.setGroupPublic(g.groupId, "no", true))
        assertEquals(OshiClient.PublicLinkOutcome.OK, a.setGroupPublic(g.groupId, "Swiss_Quant", true))
        assertEquals("swiss_quant", a.groups.get(g.groupId)!!.settings!!.handle)
        assertEquals(listOf(a.address), registry.records["swiss_quant"]!!.second)
        assertEquals("Swiss Quant", registry.records["swiss_quant"]!!.third!!.name)

        // Another group cannot take the handle.
        val other = a.createGroup("other", emptyList())
        registry.records["swiss_quant"] = registry.records["swiss_quant"]!!.copy(first = "someone-else")
        assertEquals(OshiClient.PublicLinkOutcome.TAKEN, a.setGroupPublic(other.groupId, "swiss_quant", false))
        registry.records["swiss_quant"] = registry.records["swiss_quant"]!!.copy(first = GroupSettings.groupRef(g.groupId))

        val joiner = fx.client("joiner").also { it.handleRegistry = registry }
        assertEquals(OshiClient.PublicLookup.Invalid, joiner.lookupPublicGroup("https://oshi-messenger.com/g/x"))
        assertEquals(OshiClient.PublicLookup.NotFound, joiner.lookupPublicGroup("https://oshi-messenger.com/g/nobody_here"))
        val found = joiner.lookupPublicGroup("https://oshi-messenger.com/g/swiss_quant") as OshiClient.PublicLookup.Found
        assertTrue("nothing may be sent before the person confirms", joiner.groups.all().isEmpty())
        assertEquals(1, joiner.requestPublicJoin(found.result))

        a.router.poll()
        assertTrue(a.groups.get(g.groupId)!!.isMember(joiner.address))
        assertTrue(a.groupEvents(g.groupId).any { it.kind == GroupEvents.Kind.JOINED_VIA_LINK && it.actor == joiner.address })
        joiner.router.poll()
        val joined = joiner.groups.get(g.groupId)
        assertNotNull("the joiner never materialised the group", joined)
        assertEquals("Bienvenue !", joiner.groupWelcomeShown(g.groupId))
        assertTrue("a member sees no admin log", joiner.groupEvents(g.groupId).isEmpty())

        // Public → Private unregisters the handle.
        assertNotNull(a.setGroupPrivate(g.groupId))
        assertTrue(registry.deletes.contains("swiss_quant"))
        assertFalse(a.groups.get(g.groupId)!!.settings!!.isPublic)
    }

    @Test
    fun `a failed unregister is retried later`() {
        val registry = StubRegistry()
        val a = fx.client("admin").also { it.handleRegistry = registry }
        val g = a.createGroup("club", emptyList())
        assertEquals(OshiClient.PublicLinkOutcome.OK, a.setGroupPublic(g.groupId, "club_link", false))
        registry.failWrites = true
        a.setGroupPrivate(g.groupId)
        assertEquals(setOf("club_link"), a.groups.pendingUnregister())
        registry.failWrites = false
        a.maintainPublicHandles()
        assertTrue(a.groups.pendingUnregister().isEmpty())
        assertTrue(registry.deletes.contains("club_link"))
    }

    @Test
    fun `delete for everyone leaves the member a read-only group`() {
        val (a, b, g) = trio(GroupType.COLLABORATIVE)
        assertTrue(a.dissolveGroup(g.groupId))
        assertNull(a.groups.get(g.groupId))
        b.router.poll()
        val atMember = b.groups.get(g.groupId)!!
        assertTrue(atMember.isDissolved)
        assertFalse(b.mayPostInGroup(g.groupId))
        assertTrue(b.sendGroupText(g.groupId, "anyone?").refusedControlPayload)
        assertNull("a deleted group has no invite link", b.groupInviteLink(g.groupId))
    }

    @Test
    fun `unban removes this device's tombstone only, and a removed member is listed until then`() {
        val (a, b, g) = trio()
        a.removeGroupMember(g.groupId, b.address)
        assertTrue(a.groups.get(g.groupId)!!.isEvicted(b.address))
        assertNull("a member cannot unban", fx.client("x").unbanGroupMember(g.groupId, b.address))
        assertFalse(a.unbanGroupMember(g.groupId, b.address)!!.isEvicted(b.address))
        val kinds = a.groupEvents(g.groupId).map { it.kind }
        assertTrue(kinds.contains(GroupEvents.Kind.MEMBER_REMOVED))
    }

    @Test
    fun `in an admin-only group only admins see the invite link`() {
        val (a, b, g) = trio(GroupType.ADMIN_ONLY)
        assertNotNull(a.groupInviteLink(g.groupId))
        assertNull(b.groupInviteLink(g.groupId))
        val (_, d, h) = trio(GroupType.COLLABORATIVE)
        assertNotNull(d.groupInviteLink(h.groupId))
    }
}
