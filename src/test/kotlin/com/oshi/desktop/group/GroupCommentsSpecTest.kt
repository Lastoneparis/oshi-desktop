package com.oshi.desktop.group

import com.oshi.desktop.msg.ReplyEnvelope
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.TimestampSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * __GROUP_COMMENTS_2026_11_21__ GROUP_INFO_SETTINGS_SPEC.md §2.12, the pure half: the shared
 * vectors (`docs/fixtures/group_settings_v1/definition_with_comments.json` + `comment_*.json`,
 * the same files iOS and Android test against), the receive rules of §2.12.4 in their order,
 * orphans, flood, the §2.12.5 notification rule and the settings round trip (§2.1 rule 2).
 */
class GroupCommentsSpecTest {

    // Walk up: ../docs in the monorepo, ./docs in the public repo (whose root IS the desktop project).
    private val fixtures = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "docs/fixtures/group_settings_v1") }.first { it.isDirectory }
    private fun fixture(name: String) = File(fixtures, name).readText(Charsets.UTF_8)

    private val admin = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    private val member = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8="
    private val postId = "0A1B2C3D-0000-4000-8000-000000000001"
    private val c1Id = "0A1B2C3D-0000-4000-8000-000000000002"

    private fun definition(): GroupDefinition =
        (GroupUpdateWire.decodeDefinition(fixture("definition_with_comments.json")) as GroupUpdateWire.GroupUpdateDecode.Ok).definition

    private fun payload(name: String) = GroupMessageWire.decode(fixture(name))!!

    /** A tiny receiver: the group's stored rows, and the pure rules deciding what goes in. */
    private class Receiver(var group: GroupDefinition) {
        val stored = ArrayList<Message>()
        val flood = GroupComments.RateWindow()
        fun parent(id: String) = stored.firstOrNull { GroupMessageWire.sameMessageId(it.id, id) }

        fun receive(p: GroupMessageWire.GroupMessagePayload, from: String = p.senderPublicKey): GroupComments.Verdict {
            val key = GroupIdentity.canonicalIdentity(from)
            val v = GroupComments.decide(
                from, p.commentOn, GroupComments.kindOf(p.body, p.mediaType != null), group, ::parent, flood.count(key),
            )
            when (v) {
                is GroupComments.Verdict.Accept -> { flood.note(key); stored += row(p, from, v.postId) }
                GroupComments.Verdict.Ordinary -> stored += row(p, from, null)
                else -> Unit
            }
            return v
        }

        fun row(p: GroupMessageWire.GroupMessagePayload, from: String, commentOn: String?) = Message(
            id = p.messageId, conversationId = p.groupId, senderAddress = from, recipientAddress = p.groupId,
            fromMe = false, content = p.body, sentAtMs = p.timestampUnixMillis,
            sentAtSource = TimestampSource.RELAY_ENVELOPE_MS, deliveryStatus = DeliveryStatus.DELIVERED,
            commentOn = commentOn,
        )
    }

    private fun drop(reason: String) = GroupComments.Verdict.Drop(reason)

    // ------------------------------------------------------------------ settings

    @Test
    fun `definition_with_comments decodes comments true and is Active`() {
        val s = definition().settings!!
        assertTrue(s.comments)
        assertEquals(GroupSettings.PostPolicy.ADMINS, s.postPolicy)
        assertTrue(s.commentsActive)
        assertFalse(s.copy(postPolicy = GroupSettings.PostPolicy.EVERYONE).commentsActive)
        assertFalse(s.copy(comments = false).commentsActive)
        // Missing subkey ⇒ the default (false), as for every §2.1 subkey.
        assertFalse(GroupSettings.parse(JSONObject("""{"v":1,"postPolicy":"admins"}"""))!!.comments)
        assertFalse(GroupSettings.parse(JSONObject("""{"v":1,"comments":"yes"}"""))!!.comments)
    }

    @Test
    fun `the settings emitter carries comments, so an admin's save never resets it`() {
        val raw = fixture("definition_with_comments.json")
        val g = definition()
        val ours = JSONObject(GroupUpdateWire.encodeDefinition(g)).getJSONObject("settings")
        val theirs = JSONObject(raw).getJSONObject("settings")
        assertEquals(theirs.keySet(), ours.keySet())
        assertEquals(true, ours.get("comments"))
        // An admin edits something else (here: the welcome) on THIS build: comments survives.
        val edited = g.copy(settings = g.settings!!.copy(welcome = "new"), stateVersion = 9)
        val atReceiver = GroupUpdateAuthorizer.apply(g, decodeAgain(edited), GroupUpdateAuthorizer.authorize(g, admin, decodeAgain(edited)))
        assertTrue(atReceiver.settings!!.comments)
        // And "Who can post" back to everyone keeps the value (hidden, not cleared — §2.12.6).
        val everyone = g.copy(settings = g.settings!!.copy(postPolicy = GroupSettings.PostPolicy.EVERYONE), stateVersion = 9)
        assertTrue(decodeAgain(everyone).settings!!.comments)
        assertFalse(decodeAgain(everyone).settings!!.commentsActive)
    }

    private fun decodeAgain(g: GroupDefinition) =
        (GroupUpdateWire.decodeDefinition(GroupUpdateWire.encodeDefinition(g)) as GroupUpdateWire.GroupUpdateDecode.Ok).definition

    @Test
    fun `turning comments on or off is a recent event`() {
        val g = definition()
        val off = g.copy(settings = g.settings!!.copy(comments = false))
        val ev = GroupEvents.diff(off, g, admin, 1L).single()
        assertEquals(GroupEvents.Kind.COMMENTS_CHANGED, ev.kind)
        assertEquals("comments_changed", ev.kind.wire)
    }

    // ------------------------------------------------------------------ the vectors, in order

    @Test
    fun `the comment vectors applied in order give the spec's verdicts`() {
        val r = Receiver(definition())
        assertEquals(GroupComments.Verdict.Ordinary, r.receive(payload("comment_post.json")))
        assertEquals(GroupComments.Verdict.Accept(postId), r.receive(payload("comment_top_level.json")))
        val c1 = r.stored.last()
        assertEquals(postId, c1.commentOn)
        val env1 = ReplyEnvelope.unwrap(c1.content)!!
        assertEquals("Why 40 and not 50?", env1.content)
        assertEquals("no quote in the thread: replyTo == commentOn", postId, env1.quote!!.originalMessageId)

        assertEquals(GroupComments.Verdict.Accept(postId), r.receive(payload("comment_reply_to_comment.json")))
        val env2 = ReplyEnvelope.unwrap(r.stored.last().content)!!
        assertEquals("Vol targeting caps it at 40.", env2.content)
        assertEquals(c1Id, env2.quote!!.originalMessageId)

        assertEquals(drop("not-admin"), r.receive(payload("comment_member_plain_post.json")))
        assertEquals(drop("bad-parent"), r.receive(payload("comment_on_a_comment.json")))
        assertEquals(drop("comment-kind"), r.receive(payload("comment_with_media.json")))
        assertEquals(3, r.stored.size)
    }

    @Test
    fun `comments off - a member's comment is dropped, an admin's becomes a post`() {
        val g = definition().let { it.copy(settings = it.settings!!.copy(comments = false)) }
        val r = Receiver(g)
        r.receive(payload("comment_post.json"))
        assertEquals(drop("comments-off"), r.receive(payload("comment_top_level.json")))
        // The admin's own `commentOn` message is an ordinary post (commentOn ignored).
        assertEquals(GroupComments.Verdict.Ordinary, r.receive(payload("comment_reply_to_comment.json")))
        assertNull(r.stored.last().commentOn)
    }

    @Test
    fun `in an everyone group commentOn is ignored - an ordinary reply`() {
        val g = definition().let { it.copy(settings = it.settings!!.copy(postPolicy = GroupSettings.PostPolicy.EVERYONE)) }
        val r = Receiver(g)
        r.receive(payload("comment_post.json"))
        assertEquals(GroupComments.Verdict.Ordinary, r.receive(payload("comment_top_level.json")))
        assertNull(r.stored.last().commentOn)
        assertEquals(GroupComments.Verdict.Ordinary, r.receive(payload("comment_member_plain_post.json")))
        // Media with commentOn in an everyone group: an ordinary message too (rule 2 before 4a).
        assertEquals(GroupComments.Verdict.Ordinary, r.receive(payload("comment_with_media.json")))
    }

    @Test
    fun `a comment before its post is held, and the post's arrival releases it`() {
        val r = Receiver(definition())
        val orphans = GroupComments.Orphans<GroupMessageWire.GroupMessagePayload>()
        val c1 = payload("comment_top_level.json")
        assertEquals(GroupComments.Verdict.Hold, r.receive(c1))
        orphans.hold(c1.groupId, c1.commentOn!!, c1)
        assertTrue(r.stored.isEmpty())
        val p = payload("comment_post.json")
        assertEquals(GroupComments.Verdict.Ordinary, r.receive(p))
        // Released case-insensitively by the post's id, then re-run through rule 4.
        val released = orphans.release(p.groupId, p.messageId.lowercase())
        assertEquals(listOf(c1), released)
        assertEquals(GroupComments.Verdict.Accept(postId), r.receive(released.single()))
        assertEquals(0, orphans.size(p.groupId))
    }

    @Test
    fun `a post by a member from before channel mode cannot be commented`() {
        val r = Receiver(definition())
        // The post P, but authored by M (kept from before channel mode): stored directly.
        r.stored += r.row(payload("comment_post.json"), member, null)
        assertEquals(drop("parent-not-admin"), r.receive(payload("comment_top_level.json")))
    }

    @Test
    fun `an evicted member's comment is dropped as banned`() {
        val r = Receiver(definition().let { it.copy(evictedMemberKeys = listOf(member)) })
        r.receive(payload("comment_post.json"))
        assertEquals(drop("banned"), r.receive(payload("comment_top_level.json")))
    }

    @Test
    fun `a deleted post takes no comment`() {
        val r = Receiver(definition())
        r.receive(payload("comment_post.json"))
        r.stored[0] = r.stored[0].copy(isDeletedForEveryone = true, content = null)
        assertEquals(drop("bad-parent"), r.receive(payload("comment_top_level.json")))
    }

    @Test
    fun `location, check-in, poll and media JSON are not comments`() {
        assertEquals(GroupComments.Kind.TEXT, GroupComments.kindOf("plain", false))
        assertEquals(GroupComments.Kind.TEXT, GroupComments.kindOf(payload("comment_top_level.json").body, false))
        assertEquals(GroupComments.Kind.NOT_TEXT, GroupComments.kindOf("plain", true))
        assertEquals(GroupComments.Kind.NOT_TEXT, GroupComments.kindOf("📍LOCATION📍{\"latitude\":1}", false))
        assertEquals(GroupComments.Kind.NOT_TEXT, GroupComments.kindOf("🛡️CHECK_IN🛡️{}", false))
        assertEquals(GroupComments.Kind.NOT_TEXT, GroupComments.kindOf("📊POLL📊{}", false))
        assertEquals(GroupComments.Kind.NOT_TEXT, GroupComments.kindOf("{\"type\":\"media\",\"mediaType\":\"photo\"}", false))
        val wrappedLocation = ReplyEnvelope.wrap("📍LOCATION📍{}", ReplyEnvelope.Quote(postId, admin, "x", 1_763_600_000_000L))
        assertEquals(GroupComments.Kind.NOT_TEXT, GroupComments.kindOf(wrappedLocation, false))
    }

    @Test
    fun `more than 30 comments a minute from one sender is a flood`() {
        var now = 1_000_000L
        val r = Receiver(definition())
        val flood = GroupComments.RateWindow(clock = { now })
        r.receive(payload("comment_post.json"))
        val c1 = payload("comment_top_level.json")
        fun next(i: Int) = GroupComments.decide(member, c1.commentOn, GroupComments.Kind.TEXT, r.group, r::parent, flood.count(member))
        repeat(30) { i -> assertTrue(next(i) is GroupComments.Verdict.Accept); flood.note(member) }
        assertEquals(drop("flood"), next(31))
        now += GroupComments.WINDOW_MS + 1
        assertTrue(next(32) is GroupComments.Verdict.Accept)
    }

    @Test
    fun `the sender limit is 20 a minute, then refused without queueing`() {
        var now = 0L
        val w = GroupComments.RateWindow(clock = { now })
        repeat(20) { assertTrue(w.tryAcquire("g", GroupComments.SENDER_LIMIT_PER_MINUTE)) }
        assertFalse(w.tryAcquire("g", GroupComments.SENDER_LIMIT_PER_MINUTE))
        assertTrue("another group has its own budget", w.tryAcquire("h", GroupComments.SENDER_LIMIT_PER_MINUTE))
        now = 60_001
        assertTrue(w.tryAcquire("g", GroupComments.SENDER_LIMIT_PER_MINUTE))
    }

    @Test
    fun `orphans are capped at 50 per group, oldest first, and expire after 24 h`() {
        var now = 0L
        val o = GroupComments.Orphans<Int>(clock = { now })
        repeat(55) { i -> o.hold("g", "P$i", i) }
        assertEquals(50, o.size("g"))
        assertTrue("the oldest went first", o.release("g", "P0").isEmpty())
        assertEquals(listOf(54), o.release("g", "p54"))
        now = GroupComments.ORPHAN_TTL_MS + 1
        assertEquals(0, o.size("g"))
        assertTrue(o.release("g", "P53").isEmpty())
    }

    // ------------------------------------------------------------------ encode

    @Test
    fun `a sender building C1 produces commentOn = P and a reply envelope naming P`() {
        val body = ReplyEnvelope.wrap(
            "Why 40 and not 50?",
            ReplyEnvelope.Quote(postId.lowercase(), admin, "Daily note: BTC weekly position 40% long.", 1_763_632_800_000L),
        )
        val json = GroupMessageWire.encode(
            GroupMessageWire.GroupMessagePayload(
                messageId = c1Id, groupId = "5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9", senderPublicKey = member,
                body = body, timestampUnixMillis = 1_763_632_860_000L, commentOn = postId.lowercase(),
            )
        )
        val o = JSONObject(json)
        assertEquals("emitted UPPERCASE", postId, o.getString("commentOn"))
        val decoded = String(Base64.getDecoder().decode(o.getString("encryptedContent")), Charsets.UTF_8)
        assertTrue(decoded.startsWith("💬REPLY💬"))
        assertTrue(ReplyEnvelope.unwrap(decoded)!!.quote!!.originalMessageId.equals(postId, ignoreCase = true))
        // And it decodes back to the same comment.
        assertEquals(postId, GroupMessageWire.decode(json)!!.commentOn)
        // A plain message carries no key at all (byte-identical to before for everything else).
        val plain = GroupMessageWire.encode(GroupMessageWire.GroupMessagePayload(c1Id, "5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9", member, "x", 1_763_632_860_000L))
        assertFalse(plain.contains("commentOn"))
        // Every vector decodes `commentOn` as written.
        assertEquals(postId, payload("comment_top_level.json").commentOn)
        assertNull(payload("comment_post.json").commentOn)
    }

    // ------------------------------------------------------------------ notifications

    @Test
    fun `only a reply to my comment, or a mention of me, notifies`() {
        val r = Receiver(definition())
        r.receive(payload("comment_post.json"))
        r.receive(payload("comment_top_level.json"))
        r.receive(payload("comment_reply_to_comment.json"))
        val c1 = r.stored[1]
        val c2 = r.stored[2]
        // On M's device C1 is M's own comment: A's reply notifies M.
        val atM = r.stored.map { if (it.id == c1.id) it.copy(fromMe = true) else it }
        fun lookup(rows: List<Message>) = { id: String -> rows.firstOrNull { GroupMessageWire.sameMessageId(it.id, id) } }
        assertTrue(GroupComments.notifiesMe(c2, lookup(atM), member))
        // The top-level comment notifies nobody, not even the post's author.
        assertFalse(GroupComments.notifiesMe(c1, lookup(r.stored), admin))
        // A third person is not notified by a reply to somebody else's comment.
        assertFalse(GroupComments.notifiesMe(c2, lookup(r.stored), "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8="))
        // A mention reaches the person mentioned.
        val mention = c1.copy(mentions = listOf(MentionWire.Mention(admin, "Hugo")))
        assertTrue(GroupComments.notifiesMe(mention, lookup(r.stored), admin))
    }

    @Test
    fun `sender side - only a stored, live, admin-authored post can be commented`() {
        val r = Receiver(definition())
        r.receive(payload("comment_post.json"))
        r.receive(payload("comment_top_level.json"))
        val g = r.group
        assertTrue(GroupComments.mayCommentOn(g, r.stored[0], member))
        assertFalse("a comment is not a post", GroupComments.mayCommentOn(g, r.stored[1], member))
        assertFalse(GroupComments.mayCommentOn(g, null, member))
        assertFalse(GroupComments.mayCommentOn(g.copy(settings = g.settings!!.copy(comments = false)), r.stored[0], member))
        assertFalse(GroupComments.mayCommentOn(g, r.stored[0].copy(isDeletedForEveryone = true), member))
    }

    @Test
    fun `push wakes only the author of the comment answered`() {
        val r = Receiver(definition())
        r.receive(payload("comment_post.json"))
        r.receive(payload("comment_top_level.json"))
        val c1 = r.stored[1]
        val reached = listOf(member, "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8=")
        assertEquals(listOf(member), GroupComments.wakeTargets(c1, reached, admin))
        assertTrue("a top-level comment wakes nobody", GroupComments.wakeTargets(null, reached, admin).isEmpty())
        assertTrue("never ourselves", GroupComments.wakeTargets(c1.copy(fromMe = true), reached, member).isEmpty())
        assertTrue("not reached, not woken", GroupComments.wakeTargets(c1, listOf(admin), admin).isEmpty())
    }
}
