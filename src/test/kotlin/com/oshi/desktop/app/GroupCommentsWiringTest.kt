package com.oshi.desktop.app

import com.oshi.desktop.group.GroupDefinition
import com.oshi.desktop.group.GroupMessageWire
import com.oshi.desktop.group.GroupSettings
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.msg.ReactionPayload
import com.oshi.desktop.msg.ReplyEnvelope
import com.oshi.desktop.store.Message
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __GROUP_COMMENTS_2026_11_21__ GROUP_INFO_SETTINGS_SPEC.md §2.12 through the REAL client over
 * the fixture relay: an admin posts, a member comments, both count it; comments stay out of the
 * timeline and the chat list; replies notify the comment's author only; orphans wait for their
 * post; admins delete any comment; the switch rides the settings object.
 */
class GroupCommentsWiringTest {

    private val fx = WiringFixture()

    @After
    fun tearDown() = fx.close()

    private class Channel(val a: OshiClient, val b: OshiClient, val g: GroupDefinition) {
        val gid get() = g.groupId
    }

    /** Admin [a], member [b], channel mode with comments on, delivered to [b]. */
    private fun channel(comments: Boolean = true): Channel {
        val a = fx.client("admin")
        val b = fx.client("member")
        val g = a.createGroup("Swiss Quant", listOf(b.address), GroupType.ADMIN_ONLY)
        b.router.poll()
        assertNotNull(a.setGroupPostPolicy(g.groupId, GroupSettings.PostPolicy.ADMINS))
        if (comments) assertNotNull(a.setGroupComments(g.groupId, true))
        b.router.poll()
        assertEquals(comments, b.groups.get(g.groupId)!!.effectiveSettings.commentsActive)
        return Channel(a, b, g)
    }

    private fun postOf(c: OshiClient, gid: String, text: String): Message =
        c.messages.messages(gid).single { ReplyEnvelope.unwrap(it.content)?.content ?: it.content == text }

    @Test
    fun `admin posts, member comments, both see the count and neither timeline shows the comment`() {
        val ch = channel()
        val notified = ArrayList<Pair<Message, Boolean>>()
        ch.a.onComment = { m, n -> notified += m to n }
        // onMessage is what raises the group's notification and unread badge: never for a comment.
        val timelineEvents = ArrayList<Message>()
        ch.a.onMessage = { m -> timelineEvents += m }
        assertEquals(1, ch.a.sendGroupText(ch.gid, "Daily note: BTC 40% long.").sent)
        ch.b.router.poll()
        val post = postOf(ch.b, ch.gid, "Daily note: BTC 40% long.")

        val r = ch.b.sendGroupComment(ch.gid, post.id, "Why 40 and not 50?")
        assertEquals(OshiClient.CommentSendResult.Outcome.SENT, r.outcome)
        ch.a.router.poll()

        for (c in listOf(ch.a, ch.b)) {
            assertEquals(mapOf(post.id.uppercase() to 1), c.commentCounts(ch.gid))
            val comment = c.comments(ch.gid, post.id).single()
            assertEquals("Why 40 and not 50?", ReplyEnvelope.unwrap(comment.content)!!.content)
            assertTrue("the post is the only timeline row", c.history(ch.gid).none { it.commentOn != null })
            assertEquals(1, c.history(ch.gid).count { it.id.equals(post.id, true) })
            // The chat list's preview is still the post (§2.12.5).
            val summary = c.conversations().single { it.conversationId.equals(ch.gid, true) }
            assertTrue(summary.lastMessage!!.id.equals(post.id, true))
        }
        // A top-level comment notifies nobody, not even the post's author.
        assertTrue("a comment went through onMessage", timelineEvents.none { it.commentOn != null })
        assertEquals(1, notified.size)
        assertFalse(notified.single().second)

        // The admin answers the member's comment: the member is notified, and it quotes C1.
        val c1 = ch.a.comments(ch.gid, post.id).single()
        val notifiedB = ArrayList<Pair<Message, Boolean>>()
        ch.b.onComment = { m, n -> notifiedB += m to n }
        assertEquals(OshiClient.CommentSendResult.Outcome.SENT, ch.a.sendGroupComment(ch.gid, post.id, "Vol targeting caps it at 40.", replyToCommentId = c1.id).outcome)
        ch.b.router.poll()
        assertTrue(notifiedB.single().second)
        val c2 = ch.b.comments(ch.gid, post.id).last()
        assertTrue(ReplyEnvelope.unwrap(c2.content)!!.quote!!.originalMessageId.equals(c1.id, true))
        assertEquals(2, ch.b.commentCounts(ch.gid)[post.id.uppercase()])
    }

    @Test
    fun `comments are refused when off, on a comment, and past 20 a minute`() {
        val ch = channel(comments = false)
        ch.a.sendGroupText(ch.gid, "post")
        ch.b.router.poll()
        val post = postOf(ch.b, ch.gid, "post")
        assertEquals(OshiClient.CommentSendResult.Outcome.NOT_ALLOWED, ch.b.sendGroupComment(ch.gid, post.id, "hi").outcome)

        ch.a.setGroupComments(ch.gid, true)
        ch.b.router.poll()
        val first = ch.b.sendGroupComment(ch.gid, post.id, "1")
        assertEquals(OshiClient.CommentSendResult.Outcome.SENT, first.outcome)
        assertEquals("a comment is not a post", OshiClient.CommentSendResult.Outcome.NOT_ALLOWED,
            ch.b.sendGroupComment(ch.gid, first.messageId!!, "on a comment").outcome)
        assertEquals(OshiClient.CommentSendResult.Outcome.EMPTY, ch.b.sendGroupComment(ch.gid, post.id, "   ").outcome)
        repeat(19) { i -> assertEquals(OshiClient.CommentSendResult.Outcome.SENT, ch.b.sendGroupComment(ch.gid, post.id, "c$i").outcome) }
        assertEquals(OshiClient.CommentSendResult.Outcome.TOO_FAST, ch.b.sendGroupComment(ch.gid, post.id, "21st").outcome)
        assertEquals(20, ch.b.comments(ch.gid, post.id).size)
    }

    private fun forged(ch: Channel, id: String, from: String, body: String, commentOn: String?, media: GroupMessageWire.GroupMediaType? = null) =
        GroupMessageWire.encodeForEnvelope(
            GroupMessageWire.GroupMessagePayload(
                messageId = id, groupId = ch.gid, senderPublicKey = from, body = body,
                timestampUnixMillis = System.currentTimeMillis(), commentOn = commentOn, mediaType = media,
            )
        )

    private fun quoteOf(m: Message) = ReplyEnvelope.Quote(m.id, m.senderAddress, m.content.orEmpty(), m.sentAtMs)

    @Test
    fun `the receiver applies the rules - plain post, media, comments off, orphan`() {
        val ch = channel()
        ch.a.sendGroupText(ch.gid, "post")
        val post = postOf(ch.a, ch.gid, "post")
        val wrapped = ReplyEnvelope.wrap("hello", quoteOf(post))

        // A member's plain post is still dropped (§2.4), and a media "comment" too.
        fx.inbound(ch.a, ch.b.address, forged(ch, "PLAIN-1", ch.b.address, "hello everyone", null), groupId = ch.gid)
        fx.inbound(ch.a, ch.b.address, forged(ch, "MEDIA-1", ch.b.address, "", post.id, GroupMessageWire.GroupMediaType.PHOTO), groupId = ch.gid)
        assertTrue(ch.a.messages.messages(ch.gid).none { it.id == "PLAIN-1" || it.id == "MEDIA-1" })

        // A comment that overtook its post is held, then stored when the post arrives.
        fx.inbound(ch.a, ch.b.address, forged(ch, "EARLY-1", ch.b.address, ReplyEnvelope.wrap("early", ReplyEnvelope.Quote("POST-LATE", ch.a.address, "x", post.sentAtMs)), "POST-LATE"), groupId = ch.gid)
        assertTrue(ch.a.messages.messages(ch.gid).none { it.id == "EARLY-1" })
        // The post itself (by the admin; here delivered as another admin device would) arrives.
        val second = fx.client("admin2")
        ch.a.addGroupMember(ch.gid, second.address)
        ch.a.setGroupMemberAdmin(ch.gid, second.address, true)
        fx.inbound(ch.a, second.address, forged(ch, "post-late", second.address, "late post", null), groupId = ch.gid)
        assertEquals("post-late", ch.a.messages.messages(ch.gid).single { it.id == "EARLY-1" }.commentOn)

        // Comments off: the member's comment is dropped, the admin's becomes a post.
        ch.a.setGroupComments(ch.gid, false)
        ch.b.router.poll()
        fx.inbound(ch.a, ch.b.address, forged(ch, "OFF-1", ch.b.address, wrapped, post.id), groupId = ch.gid)
        assertTrue(ch.a.messages.messages(ch.gid).none { it.id == "OFF-1" })
        fx.inbound(ch.b, ch.a.address, forged(ch, "OFF-ADMIN", ch.a.address, wrapped, post.id), groupId = ch.gid)
        val adminPost = ch.b.messages.messages(ch.gid).single { it.id == "OFF-ADMIN" }
        assertNull(adminPost.commentOn)
        assertTrue(ch.b.history(ch.gid).any { it.id == "OFF-ADMIN" })
    }

    @Test
    fun `an admin deletes any comment, a member only their own, and reactions on comments apply`() {
        val ch = channel()
        ch.a.sendGroupText(ch.gid, "post")
        ch.b.router.poll()
        val post = postOf(ch.b, ch.gid, "post")
        val c = ch.b.sendGroupComment(ch.gid, post.id, "mine").messageId!!
        ch.a.router.poll()

        // The member cannot delete the admin's post (shipped rule; refused at the receiver too).
        assertNull(ch.b.deleteGroupMessage(ch.gid, post.id))
        // A member reacts to a comment (reactions are not posts).
        assertNotNull(ch.a.sendGroupReaction(ch.gid, c, "👍"))
        ch.b.router.poll()
        assertEquals(setOf("👍"), ch.b.comments(ch.gid, post.id).single().reactions.keys)
        fx.inbound(ch.a, ch.b.address, GroupMessageWire.encodeForEnvelope(
            GroupMessageWire.GroupMessagePayload(
                messageId = "R-1", groupId = ch.gid, senderPublicKey = ch.b.address,
                body = ReactionPayload.encode(messageId = c, emoji = "🔥", senderPublicKey = ch.b.address, isAdding = true, atUnixMillis = System.currentTimeMillis(), senderName = "member"),
                timestampUnixMillis = System.currentTimeMillis(),
            )
        ), groupId = ch.gid)
        assertEquals(setOf("👍", "🔥"), ch.a.comments(ch.gid, post.id).single().reactions.keys)

        // The admin (not the author) deletes it for everyone.
        assertNotNull(ch.a.deleteGroupMessage(ch.gid, c))
        ch.b.router.poll()
        assertTrue(ch.b.comments(ch.gid, post.id).single().isDeletedForEveryone)
        assertTrue("a deleted comment is not counted", ch.b.commentCounts(ch.gid).isEmpty())
    }

    @Test
    fun `an edited comment keeps its quote`() {
        val ch = channel()
        ch.a.sendGroupText(ch.gid, "post")
        ch.b.router.poll()
        val post = postOf(ch.b, ch.gid, "post")
        val id = ch.b.sendGroupComment(ch.gid, post.id, "frist").messageId!!
        ch.a.router.poll()
        assertNotNull(ch.b.editGroupMessage(ch.gid, id, "first"))
        ch.a.router.poll()
        for (c in listOf(ch.a, ch.b)) {
            val env = ReplyEnvelope.unwrap(c.comments(ch.gid, post.id).single().content)!!
            assertEquals("first", env.content)
            assertTrue(env.quote!!.originalMessageId.equals(post.id, true))
        }
    }

    @Test
    fun `an admin's settings save keeps comments on`() {
        val ch = channel()
        ch.a.setGroupWelcome(ch.gid, "hello")
        ch.a.setGroupPostPolicy(ch.gid, GroupSettings.PostPolicy.EVERYONE)
        ch.b.router.poll()
        val s = ch.b.groups.get(ch.gid)!!.settings!!
        assertTrue("hidden with Who can post = everyone, but kept", s.comments)
        assertFalse(s.commentsActive)
        ch.a.setGroupPostPolicy(ch.gid, GroupSettings.PostPolicy.ADMINS)
        ch.b.router.poll()
        assertTrue(ch.b.groups.get(ch.gid)!!.effectiveSettings.commentsActive)
    }
}
