package com.oshi.desktop.app

import com.oshi.desktop.group.GroupMessageWire
import com.oshi.desktop.group.MentionWire
import com.oshi.desktop.ui.state.ChatShellModel
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __MENTIONS_2026_09_23__ `@name` through the real client, over the fixture relay. */
class MentionWiringTest {

    private val fx = WiringFixture()
    private val direct = Executor { it.run() }
    private val models = mutableListOf<ChatShellModel>()

    @After
    fun tearDown() {
        models.forEach { runCatching { it.close() } }
        fx.close()
    }

    @Test
    fun `a picked mention reaches every member and is kept only while its token is in the text`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val c = fx.client("c")
        val g = a.createGroup("trio", listOf(b.address, c.address))
        b.router.poll(); c.router.poll()

        val bee = MentionWire.Mention(b.address, "Bee")
        val cee = MentionWire.Mention(c.address, "Cee")
        // Cee was picked, then her token was deleted before sending: she is not mentioned.
        a.sendGroupText(g.groupId, "@Bee can you check this?", mentions = listOf(bee, cee))
        b.router.poll(); c.router.poll()

        assertEquals(listOf(bee), a.messages.messages(g.groupId).last().mentions)
        for (member in listOf(b, c)) {
            val row = member.messages.messages(g.groupId).last()
            assertEquals("@Bee can you check this?", row.content)   // prose, readable on any phone
            assertEquals(listOf(bee), row.mentions)
        }
    }

    @Test
    fun `a forged mention of a non-member or a hidden token is dropped on receipt`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val g = a.createGroup("duo", listOf(b.address))
        b.router.poll()
        val outsider = fx.client("x", publish = false).address

        fx.inbound(
            b, a.address,
            GroupMessageWire.encodeForEnvelope(
                GroupMessageWire.GroupMessagePayload(
                    messageId = java.util.UUID.randomUUID().toString().uppercase(), groupId = g.groupId,
                    senderPublicKey = a.address, body = "hello @Out and everyone",
                    timestampUnixMillis = System.currentTimeMillis(),
                    mentions = listOf(
                        MentionWire.Mention(outsider, "Out"),           // not in the roster
                        MentionWire.Mention(b.address, "Invisible"),     // token not in the text
                    ),
                )
            ),
            groupId = g.groupId,
        )
        val row = b.messages.messages(g.groupId).last()
        assertEquals("hello @Out and everyone", row.content)
        assertTrue(row.mentions.isEmpty())
    }

    @Test
    fun `a mention breaks through a muted group, a plain message does not, and the list shows the at badge`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val g = a.createGroup("loud", listOf(b.address))
        b.router.poll()
        b.setGroupMuted(g.groupId, true)

        val notifications = AtomicInteger()
        val m = ChatShellModel(b, direct, { notifications.incrementAndGet() }).also { models += it; it.attach() }

        a.sendGroupText(g.groupId, "plain chatter")
        b.router.poll()   // attach() routes every stored inbound message to m.onInbound
        assertEquals("a muted group notified for a plain message", 0, notifications.get())
        assertFalse(m.state.conversations.single { it.id.equals(g.groupId, true) }.mentionedYou)

        a.sendGroupText(g.groupId, "@Bee look", mentions = listOf(MentionWire.Mention(b.address, "Bee")))
        b.router.poll()
        assertEquals("a mention did not break through mute", 1, notifications.get())
        assertTrue(m.state.conversations.single { it.id.equals(g.groupId, true) }.mentionedYou)

        // Opening the group clears the badge.
        m.select(m.state.conversations.single { it.id.equals(g.groupId, true) }.id)
        assertFalse(m.state.conversations.single { it.id.equals(g.groupId, true) }.mentionedYou)
    }
}
