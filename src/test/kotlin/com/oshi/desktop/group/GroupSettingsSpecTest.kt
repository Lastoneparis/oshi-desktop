package com.oshi.desktop.group

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __GROUP_INFO_SETTINGS_2026_11_20__ GROUP_INFO_SETTINGS_SPEC.md §6, the pure half: the shared
 * vectors in `docs/fixtures/group_settings_v1/` (the same files Android and iOS test against),
 * the receiver rules of §2.1/§2.2, the join decision table of §3.3, and the §2.3/§2.4 filters.
 */
class GroupSettingsSpecTest {

    // Walk up: ../docs in the monorepo, ./docs in the public repo (whose root IS the desktop project).
    private val fixtures = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "docs/fixtures/group_settings_v1") }.first { it.isDirectory }
    private fun fixture(name: String) = File(fixtures, name).readText(Charsets.UTF_8)

    private val admin = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    private val member = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8="
    private val stranger = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8="

    private fun decode(json: String): GroupDefinition =
        (GroupUpdateWire.decodeDefinition(json) as GroupUpdateWire.GroupUpdateDecode.Ok).definition

    private fun merge(current: GroupDefinition, incoming: GroupDefinition, sender: String?): GroupDefinition {
        val d = GroupUpdateAuthorizer.authorize(current, sender, incoming)
        assertTrue("authorizer refused: ${d.reason}", d.apply)
        return GroupUpdateAuthorizer.apply(current, incoming, d)
    }

    // ------------------------------------------------------------------ vectors

    @Test
    fun `definition_with_settings decodes every subkey`() {
        val g = decode(fixture("definition_with_settings.json"))
        val s = g.settings!!
        assertEquals(GroupSettings.Visibility.PUBLIC, s.visibility)
        assertEquals("swiss_quant", s.handle)
        assertTrue(s.isPublic)
        assertTrue(s.showPreview)
        assertEquals("q1w2e3r4t5y6u7i8o9p0aA", s.inviteToken)
        assertEquals(GroupSettings.ReactionMode.SOME, s.reactions)
        assertEquals(listOf("👍", "❤️", "🔥"), s.allowedReactions)
        assertEquals("Bienvenue ! Lisez le message épinglé.", s.welcome)
        assertEquals(GroupSettings.PostPolicy.ADMINS, s.postPolicy)
        assertFalse(s.adminDMs)
        assertFalse(s.dissolved)
        assertEquals("Quant research, daily notes.", g.description)
    }

    @Test
    fun `settings survive an encode-decode round trip with the same JSON values`() {
        val raw = fixture("definition_with_settings.json")
        val g = decode(raw)
        val again = decode(GroupUpdateWire.encodeDefinition(g))
        assertEquals(g.settings, again.settings)
        // Same JSON object as the vector's, key for key (order is not part of the contract).
        val ours = JSONObject(GroupUpdateWire.encodeDefinition(g)).getJSONObject("settings")
        val theirs = JSONObject(raw).getJSONObject("settings")
        // __GROUP_COMMENTS_2026_11_21__ plus `comments` (§2.12.1), which this vector predates:
        // always emitted, so an admin's save on this build never resets it on a receiver.
        // __CHANNEL_WEB_2026_11_26__ same for `webPreview` / `webIndexing` (§2.13).
        assertEquals(theirs.keySet() + "comments" + "webPreview" + "webIndexing", ours.keySet())
        assertEquals(false, ours.get("comments"))
        assertEquals(false, ours.get("webPreview"))
        assertEquals(false, ours.get("webIndexing"))
        for (k in theirs.keySet()) assertEquals(k, theirs.get(k).toString(), ours.get(k).toString())
    }

    @Test
    fun `definition_bad_settings is sanitised as the spec says`() {
        val s = decode(fixture("definition_bad_settings.json")).settings!!
        assertEquals(GroupSettings.Visibility.PRIVATE, s.visibility)
        assertNull(s.handle)
        assertFalse(s.showPreview)
        assertNull(s.inviteToken)
        assertEquals(GroupSettings.ReactionMode.ALL, s.reactions)
        assertEquals(12, s.allowedReactions.size)
        assertEquals(1000, s.welcome.length)
        assertEquals(GroupSettings.PostPolicy.EVERYONE, s.postPolicy)
        assertTrue(s.adminDMs)
        assertFalse(s.dissolved)
        // futureKey dropped, v emitted as the version we speak.
        val out = JSONObject(GroupSettings.encode(s))
        assertFalse(out.has("futureKey"))
        assertEquals(1, out.getInt("v"))
    }

    @Test
    fun `definition_without_settings keeps ours and applies the name`() {
        val ours = decode(fixture("definition_with_settings.json"))
        val old = decode(fixture("definition_without_settings.json"))
        assertNull(old.settings)
        val merged = merge(ours, old, admin)
        assertEquals(ours.settings, merged.settings)
        assertEquals("Swiss Quant (renamed by iOS 1.0.43)", merged.name)
        // Absent description = keep.
        assertEquals("Quant research, daily notes.", merged.description)
    }

    @Test
    fun `a group that never had settings emits no settings key`() {
        val g = decode(fixture("definition_without_settings.json"))
        assertFalse(JSONObject(GroupUpdateWire.encodeDefinition(g)).has("settings"))
        // and the type stays a type every shipped build knows (rule 0.4)
        assertEquals("admin_only", JSONObject(GroupUpdateWire.encodeDefinition(decode(fixture("definition_with_settings.json")))).getString("type"))
    }

    // ------------------------------------------------------------------ receiver rules (§2.1)

    private fun group(type: GroupType, settings: GroupSettings? = null, v: Int = 5) = GroupDefinition(
        groupId = "5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9", name = "G", type = type, adminPublicKey = admin,
        members = listOf(GroupMember(admin, joinedAtUnixMillis = 0, isAdmin = true), GroupMember(member, joinedAtUnixMillis = 0)),
        createdAtUnixMillis = 0, lastActivityUnixMillis = 0, stateVersion = v, settings = settings,
    )

    private val configured = GroupSettings(postPolicy = GroupSettings.PostPolicy.ADMINS, welcome = "hi")

    @Test
    fun `settings present from an admin replace ours`() {
        val cur = group(GroupType.ADMIN_ONLY, configured)
        val next = GroupSettings(reactions = GroupSettings.ReactionMode.NONE)
        assertEquals(next, merge(cur, cur.copy(settings = next, stateVersion = 6), admin).settings)
    }

    @Test
    fun `settings from a non-admin are ignored in admin_only AND collaborative groups`() {
        for (type in GroupType.entries) {
            val cur = group(type, configured)
            val forged = GroupSettings(postPolicy = GroupSettings.PostPolicy.EVERYONE, visibility = GroupSettings.Visibility.PUBLIC, handle = "hijack_me")
            val out = merge(cur, cur.copy(settings = forged, stateVersion = 6), member)
            assertEquals("$type: a member changed the settings", configured, out.settings)
            assertTrue(GroupUpdateAuthorizer.settingsRefused(cur.copy(settings = forged), GroupUpdateAuthorizer.authorize(cur, member, cur)))
        }
    }

    @Test
    fun `a non-admin cannot dissolve, change type, or create settings where there were none`() {
        val cur = group(GroupType.COLLABORATIVE, null)
        val out = merge(cur, cur.copy(type = GroupType.PUBLIC, settings = GroupSettings(dissolved = true), stateVersion = 6), member)
        assertNull(out.settings)
        assertEquals(GroupType.COLLABORATIVE, out.type)
        assertFalse(out.isDissolved)
    }

    @Test
    fun `unauthenticated settings are ignored`() {
        val cur = group(GroupType.COLLABORATIVE, configured)
        val out = merge(cur, cur.copy(settings = GroupSettings(), stateVersion = 6), null)
        assertEquals(configured, out.settings)
    }

    @Test
    fun `dissolved is sticky`() {
        val cur = group(GroupType.ADMIN_ONLY, GroupSettings(dissolved = true))
        val out = merge(cur, cur.copy(settings = GroupSettings(dissolved = false), stateVersion = 6), admin)
        assertTrue(out.isDissolved)
    }

    // ------------------------------------------------------------------ description (§2.2)

    @Test
    fun `an empty description clears it and an absent one keeps it`() {
        val cur = group(GroupType.ADMIN_ONLY).copy(description = "old")
        assertEquals("", merge(cur, cur.copy(description = "", stateVersion = 6), admin).description)
        assertEquals("old", merge(cur, cur.copy(description = null, stateVersion = 6), admin).description)
        // the wire keeps "" as "", and once cleared it is still emitted
        val wire = GroupUpdateWire.encodeDefinition(cur.copy(description = ""))
        assertEquals("", JSONObject(wire).getString("description"))
        assertEquals("", decode(wire).description)
    }

    @Test
    fun `a description over 500 characters is truncated on receive`() {
        val wire = GroupUpdateWire.encodeDefinition(group(GroupType.ADMIN_ONLY).copy(description = "d".repeat(900)))
        assertEquals(500, decode(wire).description!!.length)
    }

    // ------------------------------------------------------------------ joins (§3.3)

    private fun publicGroup(dissolved: Boolean = false, evicted: List<String> = emptyList()) =
        group(GroupType.ADMIN_ONLY, GroupSettings(visibility = GroupSettings.Visibility.PUBLIC, handle = "swiss_quant", dissolved = dissolved))
            .copy(evictedMemberKeys = evicted)

    private fun handleJoin(
        sender: String? = stranger, requester: String = stranger, handle: String = "Swiss_Quant",
        groups: List<GroupDefinition> = listOf(publicGroup()), self: String = admin, accepted: Int = 0,
    ) = GroupUpdateAuthorizer.authorizeHandleJoin(sender, requester, handle, groups, self, { accepted })

    @Test
    fun `join_request decision table`() {
        assertEquals("joined-via-link", handleJoin().reason)
        assertEquals(GroupUpdateAuthorizer.JoinDecision.Verdict.ACCEPT, handleJoin().verdict)
        assertEquals("unauthenticated", handleJoin(sender = null).reason)
        assertEquals("body-names-a-third-party", handleJoin(requester = member).reason)
        assertEquals("unknown-handle", handleJoin(handle = "other_one").reason)
        assertEquals("unknown-handle", handleJoin(handle = "x!").reason)
        assertEquals("unknown-handle", handleJoin(groups = listOf(group(GroupType.ADMIN_ONLY, GroupSettings(handle = "swiss_quant")))).reason)
        assertEquals("not-admin", handleJoin(self = member).reason)
        assertEquals("dissolved", handleJoin(groups = listOf(publicGroup(dissolved = true))).reason)
        assertEquals("banned", handleJoin(groups = listOf(publicGroup(evicted = listOf(stranger)))).reason)
        val m = handleJoin(sender = member, requester = member)
        assertEquals(GroupUpdateAuthorizer.JoinDecision.Verdict.ANSWER, m.verdict)
        assertEquals("rate-limited", handleJoin(accepted = GroupUpdateAuthorizer.JOINS_PER_HOUR).reason)
        assertEquals("joined-via-link", handleJoin(accepted = GroupUpdateAuthorizer.JOINS_PER_HOUR - 1).reason)
    }

    private fun inviteJoin(group: GroupDefinition, token: String?, sender: String? = stranger, requester: String = stranger, self: String = admin) =
        GroupUpdateAuthorizer.authorizeInviteJoin(sender, requester, group, token, self)

    @Test
    fun `member_sync_request decision table with and without a token`() {
        val legacy = group(GroupType.COLLABORATIVE)
        assertEquals("legacy", inviteJoin(legacy, null).reason)
        assertEquals("legacy", inviteJoin(legacy, "anything").reason)
        assertEquals("not-admin", inviteJoin(legacy, null, self = member).reason)
        assertEquals("unauthenticated", inviteJoin(legacy, null, sender = null).reason)
        assertEquals("body-names-a-third-party", inviteJoin(legacy, null, requester = member).reason)
        assertEquals("banned", inviteJoin(legacy.copy(evictedMemberKeys = listOf(stranger)), null).reason)
        assertEquals("dissolved", inviteJoin(group(GroupType.COLLABORATIVE, GroupSettings(dissolved = true)), null).reason)

        val tokened = group(GroupType.COLLABORATIVE, GroupSettings(inviteToken = "q1w2e3r4t5y6u7i8o9p0aA"))
        assertEquals("token", inviteJoin(tokened, "q1w2e3r4t5y6u7i8o9p0aA").reason)
        assertEquals("invite-revoked", inviteJoin(tokened, null).reason)
        assertEquals("invite-revoked", inviteJoin(tokened, "q1w2e3r4t5y6u7i8o9p0aB").reason)
    }

    // ------------------------------------------------------------------ filters (§2.3, §2.4)

    @Test
    fun `reaction filter`() {
        assertTrue(GroupSettings().allowsReaction("🖕"))
        assertFalse(GroupSettings(reactions = GroupSettings.ReactionMode.NONE).allowsReaction("👍"))
        val some = GroupSettings(reactions = GroupSettings.ReactionMode.SOME, allowedReactions = listOf("👍", "❤️"))
        assertTrue(some.allowsReaction("👍"))
        assertTrue("the FE0F variant is the same heart", some.allowsReaction("❤"))
        assertFalse(some.allowsReaction("🔥"))
        assertFalse("some with an empty list behaves as none", GroupSettings(reactions = GroupSettings.ReactionMode.SOME).allowsReaction("👍"))
    }

    @Test
    fun `post policy`() {
        val g = group(GroupType.COLLABORATIVE, GroupSettings(postPolicy = GroupSettings.PostPolicy.ADMINS))
        assertTrue(g.mayPost(admin))
        assertFalse(g.mayPost(member))
        assertTrue(group(GroupType.COLLABORATIVE).mayPost(member))
        assertFalse("nobody posts in a deleted group", group(GroupType.COLLABORATIVE, GroupSettings(dissolved = true)).mayPost(admin))
    }

    // ------------------------------------------------------------------ links

    @Test
    fun `public links in every accepted spelling`() {
        val h = GroupPublicLink.Parsed.Handle("swiss_quant")
        assertEquals(h, GroupPublicLink.parse("https://oshi-messenger.com/g/swiss_quant"))
        assertEquals(h, GroupPublicLink.parse("join us: https://www.oshi-messenger.org/g/Swiss_Quant ok"))
        assertEquals(h, GroupPublicLink.parse("oshi://g/swiss_quant"))
        assertEquals(h, GroupPublicLink.parse("oshi://group/join?h=swiss_quant"))
        assertEquals(GroupPublicLink.Parsed.Invalid, GroupPublicLink.parse("https://oshi-messenger.com/g/ab"))
        assertNull("a private invite is not a public link", GroupPublicLink.parse("oshi://group/join?gid=5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9&inviter=x"))
        assertNull(GroupPublicLink.parse("https://oshi-messenger.com/group?gid=5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9"))
        assertNull(GroupPublicLink.parse("https://evil.example/g/swiss_quant"))
        assertEquals("https://oshi-messenger.com/g/swiss_quant", GroupPublicLink.link("swiss_quant"))
    }

    @Test
    fun `private links carry the token and old links still parse`() {
        val link = GroupInvite.link("5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9", "G", admin, "q1w2e3r4t5y6u7i8o9p0aA")
        assertEquals("q1w2e3r4t5y6u7i8o9p0aA", GroupInvite.parse(link)!!.token)
        assertEquals(admin, GroupInvite.parse(link)!!.inviter)
        assertNull(GroupInvite.parse(GroupInvite.link("5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9", "G", admin))!!.token)
    }

    @Test
    fun `invite token is 16 random bytes in base64url and compares only to itself`() {
        val t = GroupSettings.newInviteToken()
        assertEquals(22, t.length)
        assertTrue(t.matches(Regex("[A-Za-z0-9_-]+")))
        assertTrue(GroupSettings.tokensMatch(t, t))
        assertFalse(GroupSettings.tokensMatch(t, null))
        assertFalse(GroupSettings.tokensMatch(t, t.dropLast(1) + if (t.last() == 'A') "B" else "A"))
    }

    @Test
    fun `groupRef is the spec's hash of the upper-cased id`() {
        // sha256("oshi-group-handle-v1:5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9"), computed independently.
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("oshi-group-handle-v1:5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, GroupSettings.groupRef("5f1e2d3c-4b5a-4978-8695-a4b3c2d1e0f9"))
        assertEquals(64, expected.length)
    }

    @Test
    fun `join_request and the tokened member_sync_request round-trip`() {
        val jr = GroupUpdateWire.encodeJoinRequest("swiss_quant", stranger)
        assertEquals(MinimalGroupUpdate.JoinRequest("swiss_quant", stranger), GroupUpdateWire.decodeMinimal(jr))
        val sync = GroupUpdateWire.encodeMemberSyncRequest("5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9", stranger, "q1w2e3r4t5y6u7i8o9p0aA")
        assertEquals("q1w2e3r4t5y6u7i8o9p0aA", (GroupUpdateWire.decodeMinimal(sync) as MinimalGroupUpdate.MemberSyncRequest).inviteToken)
        // a token-less request is byte-identical to the shipped one
        assertFalse(JSONObject(GroupUpdateWire.encodeMemberSyncRequest("5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9", stranger)).has("inviteToken"))
        assertNotNull(JSONObject(GroupUpdateWire.encodeDeleted("5f1e2d3c-4b5a-4978-8695-a4b3c2d1e0f9")).getString("groupId"))
    }

    // ------------------------------------------------------------------ recent events (§2.8)

    @Test
    fun `recent events diff names each change and its actor, and prunes after 48 h`() {
        val before = group(GroupType.ADMIN_ONLY, null)
        val after = before.copy(
            name = "G2",
            members = before.members.map { if (it.publicKey == member) it.copy(isAdmin = true) else it } + GroupMember(stranger, joinedAtUnixMillis = 0),
            settings = GroupSettings(reactions = GroupSettings.ReactionMode.NONE, inviteToken = "q1w2e3r4t5y6u7i8o9p0aA", welcome = "w"),
        )
        val ev = GroupEvents.diff(before, after, admin, 1_000L)
        val kinds = ev.map { it.kind }.toSet()
        assertEquals(
            setOf(GroupEvents.Kind.RENAMED, GroupEvents.Kind.ADMIN_PROMOTED, GroupEvents.Kind.MEMBER_ADDED, GroupEvents.Kind.REACTIONS_CHANGED, GroupEvents.Kind.INVITE_LINK_RESET, GroupEvents.Kind.WELCOME_CHANGED),
            kinds,
        )
        assertTrue(ev.all { it.actor == admin })
        val left = GroupEvents.diff(before, before.copy(members = before.members.filter { it.publicKey != member }), member, 0)
        assertEquals(GroupEvents.Kind.MEMBER_LEFT, left.single().kind)
        assertTrue(GroupEvents.prune(ev, 1_000L + GroupEvents.TTL_MS + 1).isEmpty())
        assertEquals(GroupEvents.MAX_PER_GROUP, GroupEvents.prune(List(600) { GroupEvents.Event(it.toLong(), admin, GroupEvents.Kind.RENAMED) }, 600).size)
    }
}
