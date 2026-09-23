package com.oshi.desktop.app

import com.oshi.desktop.group.GroupDefinition
import com.oshi.desktop.group.GroupMember
import com.oshi.desktop.group.GroupMessageWire
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.group.GroupUpdateAuthorizer
import com.oshi.desktop.group.GroupUpdateWire
import com.oshi.desktop.group.MinimalGroupUpdate
import com.oshi.desktop.msg.ReactionPayload
import com.oshi.desktop.msg.TypingPayload
import com.oshi.desktop.net.SentCopy
import com.oshi.desktop.net.V2Inbound
import com.oshi.messenger.network.v2.V2FileKeyMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * __GROUP_E2E_V2_2026_09_23__ `docs/GROUP_E2E_V2_SPEC.md` §9: the Desktop emitters and receivers
 * against the golden vectors `docs/fixtures/group_e2e_v2/vectors.json` (independent Python
 * reference). Equality is SEMANTIC (key order free, numbers |Δ| < 1e-6), as §9 specifies.
 */
class GroupE2EV2VectorsTest {

    private val v: JSONObject = JSONObject(locate().readText())
    private val c = v.getJSONObject("constants")
    private val A = c.getString("A")
    private val B = c.getString("B")
    private val C = c.getString("C")
    private val D = c.getString("D")
    private val gid = c.getString("groupId")
    private val tsMs = c.getLong("tsUnixMs")
    private val fx = WiringFixture()

    @After
    fun tearDown() = fx.close()

    private fun vec(section: String, name: String): JSONObject {
        val arr = v.getJSONArray(section)
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).getString("name") == name) return arr.getJSONObject(i)
        error("no vector $section/$name")
    }

    private fun b64json(s: String) = JSONObject(String(Base64.getDecoder().decode(s), Charsets.UTF_8))

    // ------------------------------------------------------------------ content emit

    private fun emitContent(id: String, body: String) = GroupMessageWire.encodeForEnvelope(
        GroupMessageWire.GroupMessagePayload(
            messageId = id, groupId = gid, senderPublicKey = A, body = body, timestampUnixMillis = tsMs,
        )
    )

    @Test
    fun `content vectors - our GroupMessage equals the reference`() {
        for (name in listOf("text", "reply", "reaction_add", "reaction_remove", "typing")) {
            val x = vec("content", name)
            val gm = x.getJSONObject("groupMessage")
            val ours = b64json(emitContent(gm.getString("id"), x.getString("body")))
            assertSemEq(name, gm, ours)
        }
    }

    @Test
    fun `content vectors - our reaction and typing bodies equal the reference bodies`() {
        val add = vec("content", "reaction_add").getString("body")
        val ours = ReactionPayload.encode(
            messageId = c.getString("messageId"), emoji = "👍", senderPublicKey = A,
            isAdding = true, atUnixMillis = tsMs, senderName = "Alice",
        )
        assertSemEq("reaction body", JSONObject(add.substringAfter("🔥REACTION🔥")), JSONObject(ours.substringAfter("🔥REACTION🔥")))
        assertTrue(ours.startsWith("🔥REACTION🔥"))
        val typing = vec("content", "typing").getString("body")
        val oursT = TypingPayload.encode(senderPublicKey = A, isTyping = true, atUnixMillis = tsMs, groupId = gid, senderName = "Alice")
        val prefix = "⌨️TYPING⌨️"
        assertTrue(oursT.startsWith(prefix))
        assertSemEq("typing body", JSONObject(typing.removePrefix(prefix)), JSONObject(oursT.removePrefix(prefix)))
    }

    @Test
    fun `system vectors - edit and delete`() {
        val e = vec("content", "edit").getJSONObject("groupMessage")
        val ed = e.getJSONObject("systemMessageData")
        val ours = b64json(
            GroupMessageWire.encodeForEnvelope(
                GroupMessageWire.GroupMessagePayload(
                    messageId = e.getString("id"), groupId = gid, senderPublicKey = GroupMessageWire.SYSTEM_SENDER,
                    body = "", timestampUnixMillis = tsMs, systemMessageType = GroupMessageWire.SYSTEM_EDITED,
                    systemMessageData = mapOf(
                        "actorPublicKey" to ed.getString("actorPublicKey"),
                        "editedMessageId" to ed.getString("editedMessageId"),
                        "editedContent" to ed.getString("editedContent"),
                    ),
                )
            )
        )
        assertSemEq("edit", e, ours)
        val d = vec("content", "delete").getJSONObject("groupMessage")
        val dd = d.getJSONObject("systemMessageData")
        val oursD = b64json(
            GroupMessageWire.encodeForEnvelope(
                GroupMessageWire.GroupMessagePayload(
                    messageId = d.getString("id"), groupId = gid, senderPublicKey = GroupMessageWire.SYSTEM_SENDER,
                    body = "", timestampUnixMillis = tsMs, systemMessageType = GroupMessageWire.SYSTEM_DELETED,
                    systemMessageData = mapOf(
                        "actorPublicKey" to dd.getString("actorPublicKey"),
                        "deletedMessageId" to dd.getString("deletedMessageId"),
                    ),
                )
            )
        )
        assertSemEq("delete", d, oursD)
    }

    @Test
    fun `content vectors - receivers decode the reference plaintext`() {
        val arr = v.getJSONArray("content")
        for (i in 0 until arr.length()) {
            val x = arr.getJSONObject(i)
            val p = GroupMessageWire.decodeFromEnvelope(x.getString("plaintext"))
            assertNotNull(x.getString("name"), p)
            val gm = x.getJSONObject("groupMessage")
            assertEquals(gm.getString("id"), p!!.messageId)
            assertEquals(gid, p.groupId)
            assertEquals(gm.getString("senderPublicKey"), p.senderPublicKey)
            assertTrue("ts ${p.timestampUnixMillis}", kotlin.math.abs(p.timestampUnixMillis - tsMs) <= 1)
            if (x.has("body")) assertEquals(x.getString("name"), x.getString("body"), p.body)
            if (gm.has("systemMessageType")) {
                assertTrue(p.isSystem)
                assertEquals(gm.getString("systemMessageType"), p.systemMessageType)
                assertEquals(gm.getJSONObject("systemMessageData").getString("actorPublicKey"), p.systemMessageData!!["actorPublicKey"])
            }
        }
    }

    // ------------------------------------------------------------------ media

    @Test
    fun `media vector - groupMessage emit and key-message parse`() {
        val x = vec("media", "photo")
        val gm = x.getJSONObject("groupMessage")
        val ours = b64json(
            GroupMessageWire.encodeForEnvelope(
                GroupMessageWire.GroupMessagePayload(
                    messageId = gm.getString("id"), groupId = gid, senderPublicKey = A, body = "",
                    timestampUnixMillis = tsMs, mediaType = GroupMessageWire.GroupMediaType.PHOTO,
                    plaintextContent = "sunset", mediaFileName = "photo.jpg",
                )
            )
        )
        assertSemEq("photo groupMessage", gm, ours)
        val key = V2FileKeyMessage.parse(x.getJSONObject("keyMessage").toString())
        assertNotNull(key)
        val inner = GroupMessageWire.decodeFromEnvelope(key!!.groupMessage!!)!!
        assertEquals(gm.getString("id"), inner.messageId)
        assertEquals("sunset", inner.plaintextContent)
        assertEquals("photo.jpg", inner.mediaFileName)
        assertEquals(GroupMessageWire.GroupMediaType.PHOTO, inner.mediaType)
    }

    // ------------------------------------------------------------------ state

    @Test
    fun `state vectors - definitions round-trip to the reference JSON`() {
        for (name in listOf("create", "rename", "add_member", "remove_member", "promote_admin", "pin", "avatar_emoji", "picture")) {
            val x = vec("state", name)
            val pt = x.getString("plaintext")
            assertTrue(pt.startsWith(GroupUpdateWire.PREFIX))
            val dec = GroupUpdateWire.decodeFramed(pt)
            assertTrue("$name: $dec", dec is GroupUpdateWire.GroupUpdateDecode.Ok)
            val def = (dec as GroupUpdateWire.GroupUpdateDecode.Ok).definition
            val ours = JSONObject(GroupUpdateWire.encodeDefinitionFramed(def).removePrefix(GroupUpdateWire.PREFIX))
            assertSemEq(name, x.getJSONObject("json"), ours)
        }
        val removed = (GroupUpdateWire.decodeFramed(vec("state", "remove_member").getString("plaintext")) as GroupUpdateWire.GroupUpdateDecode.Ok).definition
        assertTrue(removed.isEvicted(C))
        assertEquals(4, removed.stateVersion)
    }

    @Test
    fun `state vectors - companions and requests`() {
        assertSemEq("created_minimal", vec("state", "created_minimal").getJSONObject("json"), JSONObject(
            GroupUpdateWire.encodeCreated(MinimalGroupUpdate.Created(gid, "Team", "weekly sync", A, listOf(A, B, C), listOf(A), false))))
        assertSemEq("rename_minimal", vec("state", "rename_minimal").getJSONObject("json"), JSONObject(GroupUpdateWire.encodeGroupRenamed(gid, "Team 2")))
        assertSemEq("add_member_minimal", vec("state", "add_member_minimal").getJSONObject("json"), JSONObject(GroupUpdateWire.encodeMemberAdded(gid, D)))
        assertSemEq("remove_member_minimal", vec("state", "remove_member_minimal").getJSONObject("json"), JSONObject(GroupUpdateWire.encodeMemberRemoved(gid, C)))
        assertSemEq("leave", vec("state", "leave").getJSONObject("json"), JSONObject(GroupUpdateWire.encodeMemberRemoved(gid, B)))
        assertSemEq("member_sync_request", vec("state", "member_sync_request").getJSONObject("json"), JSONObject(GroupUpdateWire.encodeMemberSyncRequest(gid, B)))
        assertSemEq("sync_request", vec("state", "sync_request").getJSONObject("json"), JSONObject(GroupUpdateWire.encodeSyncRequest(B)))
        for (name in listOf("created_minimal", "rename_minimal", "add_member_minimal", "remove_member_minimal", "leave", "member_sync_request", "sync_request")) {
            assertNotNull(name, GroupUpdateWire.decodeMinimalFramed(vec("state", name).getString("plaintext")))
        }
    }

    /**
     * __GROUP_COMPAT_IGNORE_2026_09_23__ An Android rename emits definition + `group_renamed` + its
     * legacy `{"type":"updated"}`; the last one used to come out REJECTED_MALFORMED. Replay the
     * spec's rename sequence as B and check nothing in it is refused.
     */
    @Test
    fun `rename sequence from Android - definition, rename_minimal and legacy updated are all accepted`() {
        val me = fx.client("me")
        val ingest = GroupIngest(me.groups) { B }
        assertEquals(GroupIngest.Outcome.CREATED, ingest.ingest(vec("state", "create").getString("plaintext"), A).outcome)
        assertEquals(GroupIngest.Outcome.UPDATED, ingest.ingest(vec("state", "rename").getString("plaintext"), A).outcome)
        assertEquals("Team 2", me.groups.get(gid)!!.name)
        // Companion after the definition: already applied ⇒ NO_CHANGE, never malformed.
        assertEquals(GroupIngest.Outcome.NO_CHANGE, ingest.ingest(vec("state", "rename_minimal").getString("plaintext"), A).outcome)
        // The exact frame Android 1.6.25 GroupManager.notifyGroupUpdated sent after a rename.
        val legacy = GroupUpdateWire.PREFIX + JSONObject().put("type", "updated").put("groupId", gid)
            .put("name", "Team 2").put("description", "").toString()
        val r = ingest.ingest(legacy, A)
        assertEquals(r.detail, GroupIngest.Outcome.IGNORED, r.outcome)
        assertEquals("Team 2", me.groups.get(gid)!!.name)
        // The spec's optional read receipts: ignored, not malformed.
        assertEquals(GroupIngest.Outcome.IGNORED, ingest.ingest(vec("state", "read_receipt_batch").getString("plaintext"), B).outcome)
        // Garbage and a modelled type missing its fields stay malformed.
        assertEquals(GroupIngest.Outcome.REJECTED_MALFORMED, ingest.ingest(GroupUpdateWire.PREFIX + "not json", A).outcome)
        assertEquals(GroupIngest.Outcome.REJECTED_MALFORMED,
            ingest.ingest(GroupUpdateWire.PREFIX + "{\"type\":\"group_renamed\",\"name\":\"x\"}", A).outcome)
    }

    // ------------------------------------------------------------------ sent copy

    @Test
    fun `sent-copy vector`() {
        val x = vec("sentCopy", "group_text_self_copy").getJSONObject("plaintext")
        val ours = JSONObject(String(SentCopy.encode(SentCopy.conversationGroup(gid), x.getString("message"), x.getString("msgId"), tsMs), Charsets.UTF_8))
        assertSemEq("sent-copy", x, ours)
        val parsed = SentCopy.parse(x.toString().toByteArray())!!
        assertEquals(gid, parsed.groupId)
        // The message IS the exact content plaintext our own sender would produce.
        assertSemEq("sent-copy message", b64json(x.getString("message")), b64json(emitContent(x.getString("msgId"), "hello group ✓")))
    }

    // ------------------------------------------------------------------ receiver rules

    private fun seedGroup(me: OshiClient, members: List<String>, admin: String = A, version: Int? = 5) {
        me.groups.put(
            GroupDefinition(
                groupId = gid, name = "Team", type = GroupType.COLLABORATIVE, adminPublicKey = admin,
                members = (members + me.address).map { GroupMember(it, joinedAtUnixMillis = tsMs, isAdmin = it == admin) },
                createdAtUnixMillis = tsMs, lastActivityUnixMillis = tsMs, stateVersion = version,
            )
        )
    }

    private fun deliver(me: OshiClient, from: String, text: String, groupId: String? = gid) =
        me.dispatch(V2Inbound(from = from, msgId = "m-" + java.util.UUID.randomUUID(), ts = tsMs, groupId = groupId, text = text))

    private fun b64(o: JSONObject) = Base64.getEncoder().encodeToString(o.toString().toByteArray(Charsets.UTF_8))

    @Test
    fun `receiver - sender mismatch and system actor mismatch are dropped`() {
        val me = fx.client("me")
        seedGroup(me, listOf(A, B))
        val sm = vec("receiver", "sender_mismatch")
        deliver(me, sm.getString("envelopeFrom"), b64(sm.getJSONObject("groupMessage")))
        assertTrue(me.messages.messages(gid).isEmpty())

        // A real message from A, then B tries to edit it by claiming A as the actor.
        deliver(me, A, vec("content", "text").getString("plaintext"))
        assertEquals("hello group ✓", me.messages.messages(gid).single().content)
        val am = vec("receiver", "system_actor_mismatch")
        deliver(me, am.getString("envelopeFrom"), b64(am.getJSONObject("groupMessage")))
        assertEquals("hello group ✓", me.messages.messages(gid).single().content)

        // The genuine edit from A applies; a reaction never becomes a bubble.
        deliver(me, A, vec("content", "edit").getString("plaintext"))
        assertEquals("hello group (edited)", me.messages.messages(gid).single().content)
        deliver(me, A, vec("content", "reaction_add").getString("plaintext"))
        deliver(me, A, vec("content", "typing").getString("plaintext"))
        assertEquals(1, me.messages.messages(gid).size)
        deliver(me, A, vec("content", "delete").getString("plaintext"))
        assertTrue(me.messages.messages(gid).single().isDeletedForEveryone)
    }

    @Test
    fun `receiver - a group update on the group channel is dropped, never applied`() {
        val me = fx.client("me")
        val x = vec("receiver", "group_update_on_group_channel")
        val create = JSONObject(vec("state", "create").getJSONObject("json").toString())
        create.put("members", JSONArray().put(JSONObject().put("publicKey", A).put("joinedAt", c.getString("iso")).put("isAdmin", true))
            .put(JSONObject().put("publicKey", me.address).put("joinedAt", c.getString("iso")).put("isAdmin", false)))
        deliver(me, x.getString("envelopeFrom"), x.getString("plaintext"))
        deliver(me, A, GroupUpdateWire.PREFIX + create.toString())            // on the GROUP channel
        assertNull(me.groups.get(gid))
        assertTrue(me.messages.messages(gid).isEmpty())
        deliver(me, A, GroupUpdateWire.PREFIX + create.toString(), groupId = null) // on the state channel
        assertNotNull(me.groups.get(gid))
    }

    @Test
    fun `receiver - content for an unknown group is held and replayed when the definition lands`() {
        val me = fx.client("me")
        deliver(me, A, vec("content", "text").getString("plaintext"))
        assertNull(me.groups.get(gid))
        assertTrue(me.messages.messages(gid).isEmpty())
        val create = JSONObject(vec("state", "create").getJSONObject("json").toString())
        create.put("members", JSONArray().put(JSONObject().put("publicKey", A).put("joinedAt", c.getString("iso")).put("isAdmin", true))
            .put(JSONObject().put("publicKey", me.address).put("joinedAt", c.getString("iso")).put("isAdmin", false)))
        deliver(me, A, GroupUpdateWire.PREFIX + create.toString(), groupId = null)
        assertEquals("hello group ✓", me.messages.messages(gid).single().content)
    }

    @Test
    fun `receiver - evicted sender is dropped, self-leave accepted in any group type`() {
        val me = fx.client("me")
        seedGroup(me, listOf(A, B, C))
        me.groups.put(me.groups.get(gid)!!.copy(type = GroupType.ADMIN_ONLY, evictedMemberKeys = listOf(D)))
        // B (non-admin, admin_only group) leaves: accepted.
        deliver(me, B, vec("state", "leave").getString("plaintext"), groupId = null)
        assertFalse(me.groups.get(gid)!!.isMember(B))
        // C (non-admin) cannot remove A in an admin_only group.
        deliver(me, C, GroupUpdateWire.PREFIX + GroupUpdateWire.encodeMemberRemoved(gid, A), groupId = null)
        assertTrue(me.groups.get(gid)!!.isMember(A))
    }

    /**
     * Interop 2026-09-23 (iOS-created group, Desktop non-admin rename answered "unknown group"):
     * a group materialised from an iOS definition — iOS admin, lowercase/url-safe spellings of
     * our key and of the id allowed — must accept a rename from ANY member when collaborative
     * (GROUP_E2E_V2_SPEC §5.2), and still refuse it when admin_only.
     */
    @Test
    fun `a non-admin member can rename an iOS-created collaborative group`() {
        val me = fx.client("me")
        val create = JSONObject(vec("state", "create").getJSONObject("json").toString())
        val urlSafeMe = me.address.replace('+', '-').replace('/', '_').trimEnd('=')
        create.put("id", gid.lowercase())
        create.put("members", JSONArray()
            .put(JSONObject().put("publicKey", A).put("joinedAt", c.getString("iso")).put("isAdmin", true))
            .put(JSONObject().put("publicKey", urlSafeMe).put("joinedAt", c.getString("iso")).put("isAdmin", false)))
        deliver(me, A, GroupUpdateWire.PREFIX + create.toString(), groupId = null)
        val held = me.groups.get(gid)
        assertNotNull("the iOS definition must materialise the group", held)
        val renamed = me.renameGroup(gid.lowercase(), "Team renamed by member")
        assertNotNull("collaborative: any member may rename (spec §5.2)", renamed)
        assertEquals("Team renamed by member", me.groups.get(gid)!!.name)
        assertEquals(2, renamed!!.stateVersion)

        me.groups.put(me.groups.get(gid)!!.copy(type = GroupType.ADMIN_ONLY))
        assertNull("admin_only: a non-admin may not rename", me.renameGroup(gid, "nope"))
    }

    @Test
    fun `receiver - version cases`() {
        for (name in listOf("stale_definition", "equal_definition", "unversioned_definition")) {
            val x = vec("receiver", name)
            val local = if (x.isNull("local")) null else x.getInt("local")
            val incoming = if (x.isNull("incoming")) null else x.getInt("incoming")
            val d = GroupUpdateAuthorizer.authorize(listOf(A, B), listOf(A), GroupType.COLLABORATIVE, A, local, incoming)
            assertEquals(name, x.getBoolean("apply"), d.apply)
        }
    }

    @Test
    fun `apply keeps max version and the eviction union`() {
        val base = GroupDefinition(gid, "T", GroupType.COLLABORATIVE, A, listOf(GroupMember(A, joinedAtUnixMillis = 0, isAdmin = true), GroupMember(B, joinedAtUnixMillis = 0)),
            0, 0, stateVersion = 5, evictedMemberKeys = listOf(C))
        val incoming = base.copy(stateVersion = 6, evictedMemberKeys = listOf(D))
        val merged = GroupUpdateAuthorizer.apply(base, incoming, GroupUpdateAuthorizer.authorize(base, A, incoming))
        assertEquals(6, merged.stateVersion)
        assertTrue(merged.isEvicted(C) && merged.isEvicted(D))
    }

    // ------------------------------------------------------------------ helpers

    private fun assertSemEq(label: String, expected: Any?, actual: Any?) {
        val diff = semDiff("", expected, actual)
        assertNull("$label: $diff\nexpected=$expected\nactual=$actual", diff)
    }

    private fun semDiff(path: String, e: Any?, a: Any?): String? {
        if (e is JSONObject && a is JSONObject) {
            val keys = e.keySet() + a.keySet()
            for (k in keys) {
                if (!e.has(k)) return "$path.$k unexpected"
                if (!a.has(k)) return "$path.$k missing"
                semDiff("$path.$k", e.opt(k), a.opt(k))?.let { return it }
            }
            return null
        }
        if (e is JSONArray && a is JSONArray) {
            if (e.length() != a.length()) return "$path length ${e.length()} != ${a.length()}"
            for (i in 0 until e.length()) semDiff("$path[$i]", e.opt(i), a.opt(i))?.let { return it }
            return null
        }
        if (e is Number && a is Number) {
            return if (kotlin.math.abs(e.toDouble() - a.toDouble()) < 1e-6) null else "$path $e != $a"
        }
        if (e == JSONObject.NULL && a == JSONObject.NULL) return null
        return if (e == a) null else "$path '$e' != '$a'"
    }

    companion object {
        fun locate(): File {
            var d: File? = File(System.getProperty("user.dir")).absoluteFile
            while (d != null) {
                val f = File(d, "docs/fixtures/group_e2e_v2/vectors.json")
                if (f.isFile) return f
                d = d.parentFile
            }
            error("docs/fixtures/group_e2e_v2/vectors.json not found above ${System.getProperty("user.dir")}")
        }
    }
}
