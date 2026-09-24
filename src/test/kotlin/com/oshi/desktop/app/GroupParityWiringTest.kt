package com.oshi.desktop.app

import com.oshi.desktop.group.GroupPicture
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.msg.ReplyEnvelope
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** __GROUP_PARITY_2026_09_23__ replies through the real client, over the fixture relay. */
class GroupParityWiringTest {

    private val fx = WiringFixture()

    @After
    fun tearDown() = fx.close()

    @Test
    fun `a group reply reaches every member as the iOS envelope with its quote`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val c = fx.client("c")
        val g = a.createGroup("trio", listOf(b.address, c.address))
        b.router.poll(); c.router.poll()

        a.sendGroupText(g.groupId, "first")
        b.router.poll()
        val original = b.messages.messages(g.groupId).single()

        val quote = ReplyEnvelope.Quote(
            originalMessageId = original.id,
            originalSenderKey = a.address,
            originalText = "first",
            originalTimestampMs = original.sentAtMs,
        )
        val report = b.sendGroupText(g.groupId, "agreed", quote)
        assertEquals(2, report.sent)

        a.router.poll(); c.router.poll()
        for (member in listOf(a, c)) {
            val row = member.messages.messages(g.groupId).last()
            assertTrue("reply travelled without the envelope", row.content!!.startsWith(ReplyEnvelope.REPLY_PREFIX))
            val u = ReplyEnvelope.unwrap(row.content)!!
            assertEquals("agreed", u.content)
            assertEquals(original.id, u.quote!!.originalMessageId)
            assertEquals(a.address, u.quote!!.originalSenderKey)
        }
        // The sender's own row carries the envelope too, so its bubble draws the quote.
        assertEquals("agreed", ReplyEnvelope.unwrap(b.messages.messages(g.groupId).last().content)!!.content)
    }

    @Test
    fun `a direct reply is sent in the same envelope`() {
        val a = fx.client("a")
        val b = fx.client("b")
        assertEquals(OshiClient.SendOutcome.SENT, a.send(b.address, "hello b"))
        b.router.poll()
        val original = b.messages.messages(a.address).single()

        val quote = ReplyEnvelope.Quote(original.id, a.address, "hello b", original.sentAtMs)
        assertEquals(OshiClient.SendOutcome.SENT, b.send(a.address, "hi a", quote))
        a.router.poll()
        val u = ReplyEnvelope.unwrap(a.messages.messages(b.address).last().content)!!
        assertEquals("hi a", u.content)
        assertEquals(original.id, u.quote!!.originalMessageId)
    }

    @Test
    fun `an iPhone's 1 to 1 reply and forward are stored, not dropped as foreign sentinels`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        val reply = "💬REPLY💬{\"content\":\"ok\",\"replyTo\":{\"originalMessageId\":\"ABC\"," +
            "\"originalSenderKey\":\"${me.address}\",\"originalText\":\"hi\",\"originalTimestamp\":780000000.5}}"
        val forward = "➡️FORWARDED➡️{\"originalSenderName\":\"Ana\",\"content\":\"look\",\"isForwarded\":true,\"forwardCount\":1}"
        fx.inbound(me, peer, reply)
        fx.inbound(me, peer, forward)
        val rows = me.messages.messages(peer)
        assertEquals("a phone's reply or forward vanished", 2, rows.size)
        val unwrapped = rows.map { ReplyEnvelope.unwrap(it.content)!! }
        assertTrue(unwrapped.any { it.content == "ok" && it.quote?.originalMessageId == "ABC" })
        assertTrue(unwrapped.any { it.content == "look" && it.forwardedFrom == "Ana" })
    }

    @Test
    fun `a typed sentinel is still refused even when it is a reply`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val quote = ReplyEnvelope.Quote("x", a.address, "t", 0L)
        assertEquals(
            OshiClient.SendOutcome.REFUSED_CONTROL_PAYLOAD,
            a.send(b.address, ReplyEnvelope.wrap("smuggled", quote), quote),
        )
        val g = a.createGroup("pair", listOf(b.address))
        assertTrue(a.sendGroupText(g.groupId, ReplyEnvelope.wrap("smuggled", quote), quote).refusedControlPayload)
    }

    private fun png(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        for (x in 0 until w step 7) { g.color = java.awt.Color((x * 37) % 255, (x * 91) % 255, 200); g.fillRect(x, 0, 7, h) }
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    @Test
    fun `a picture is compressed to a JPEG under the phones' 500 KB cap`() {
        val jpeg = GroupPicture.prepare(png(3000, 2000))!!
        assertTrue(jpeg.size <= GroupPicture.MAX_BYTES)
        assertEquals(0xFF.toByte(), jpeg[0]); assertEquals(0xD8.toByte(), jpeg[1]) // JPEG SOI
        val back = ImageIO.read(jpeg.inputStream())
        assertEquals(GroupPicture.MAX_SIDE, maxOf(back.width, back.height))
        assertNull("a non-image was accepted", GroupPicture.prepare("not an image".toByteArray()))
    }

    @Test
    fun `a group picture reaches the members inside the definition`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val g = a.createGroup("pics", listOf(b.address))
        b.router.poll()

        val updated = a.setGroupPicture(g.groupId, png(800, 600))!!
        assertNotNull(updated.groupPictureBase64)
        assertEquals(a.address, updated.groupPictureUpdatedBy)
        b.router.poll()
        assertEquals(updated.groupPictureBase64, b.groups.get(g.groupId)!!.groupPictureBase64)
    }

    @Test
    fun `an ordinary member of an admin-only group cannot change the picture`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val g = a.createGroup("announce", listOf(b.address), GroupType.ADMIN_ONLY)
        b.router.poll()
        assertFalse(b.canEditGroupInfo(g.groupId))
        assertNull(b.setGroupPicture(g.groupId, png(64, 64)))
        assertTrue(a.canEditGroupInfo(g.groupId))
    }

    @Test
    fun `mute is local, survives a reload, and is never sent`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val g = a.createGroup("quiet", listOf(b.address))
        b.router.poll()
        b.setGroupMuted(g.groupId, true)
        assertTrue(b.isGroupMuted(g.groupId))
        // A later definition from the admin must not unmute it (isMuted is always ours).
        a.renameGroup(g.groupId, "quieter")
        b.router.poll()
        assertEquals("quieter", b.groups.get(g.groupId)!!.name)
        assertTrue("an incoming definition overwrote a local mute", b.isGroupMuted(g.groupId))
        assertFalse("mute leaked to another member", a.isGroupMuted(g.groupId))
    }

    @Test
    fun `a muted group stays muted after the store is reopened`() {
        val dir = java.nio.file.Files.createTempDirectory("oshi-mute").toFile()
        try {
            val f = java.io.File(dir, "groups.json")
            val def = com.oshi.desktop.group.GroupDefinition(
                groupId = "AAAA-0001", name = "n", type = GroupType.COLLABORATIVE, adminPublicKey = "k",
                members = listOf(com.oshi.desktop.group.GroupMember("k", joinedAtUnixMillis = 1_700_000_000_000L, isAdmin = true)),
                createdAtUnixMillis = 1_700_000_000_000L, lastActivityUnixMillis = 1_700_000_000_000L, stateVersion = 1,
            )
            GroupStore(f).put(def.copy(isMuted = true))
            GroupStore(f).put(def.copy(groupId = "BBBB-0002"))
            val reopened = GroupStore(f)
            assertTrue(reopened.get("AAAA-0001")!!.isMuted)
            assertFalse(reopened.get("BBBB-0002")!!.isMuted)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `joining by invite link sends an authenticated join and the admin adds us for everyone`() {
        val admin = fx.client("admin")
        val b = fx.client("b")
        val newcomer = fx.client("newcomer")
        val g = admin.createGroup("club", listOf(b.address))
        b.router.poll()

        val link = admin.groupInviteLink(g.groupId)!!
        val (outcome, gid) = newcomer.joinGroupFromInvite("Join my OSHI group: $link")
        assertEquals(OshiClient.JoinOutcome.REQUESTED, outcome)
        assertEquals("club", newcomer.groups.get(gid!!)!!.name) // stub exists at once

        admin.router.poll()
        assertTrue("the admin did not admit the joiner", admin.groups.get(g.groupId)!!.isMember(newcomer.address))
        newcomer.router.poll(); b.router.poll()
        assertTrue(newcomer.groups.get(g.groupId)!!.isMember(b.address))
        assertTrue("the other member never heard about the joiner", b.groups.get(g.groupId)!!.isMember(newcomer.address))

        // Now a real member: a message reaches them.
        admin.sendGroupText(g.groupId, "welcome")
        newcomer.router.poll()
        assertTrue(newcomer.messages.messages(g.groupId).any { it.content == "welcome" })
        assertEquals(OshiClient.JoinOutcome.ALREADY_MEMBER, newcomer.joinGroupFromInvite(link).first)
    }

    @Test
    fun `description and pin travel in the definition, and unpinning clears it on the members`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val g = a.createGroup("desc", listOf(b.address))
        b.router.poll()
        assertNotNull(a.setGroupDescription(g.groupId, "Saturday hikes, Alps"))
        a.sendGroupText(g.groupId, "meet at 8")
        b.router.poll()
        assertEquals("Saturday hikes, Alps", b.groups.get(g.groupId)!!.description)

        val msg = a.messages.messages(g.groupId).single { it.content == "meet at 8" }
        val pinned = a.setGroupPin(g.groupId, msg.id)!!
        assertEquals(msg.id.uppercase(), pinned.pinnedMessageId)
        assertEquals(a.address, pinned.pinnedBy)
        b.router.poll()
        assertTrue(b.groups.get(g.groupId)!!.pinnedMessageId.equals(msg.id, ignoreCase = true))
        a.setGroupPin(g.groupId, null)
        b.router.poll()
        assertNull(b.groups.get(g.groupId)!!.pinnedMessageId)
        assertNull("pinned a message we do not hold", a.setGroupPin(g.groupId, "00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun `delete for me hides one message on this device and a redelivery does not bring it back`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        fx.inbound(me, peer, "secret", msgId = "M-1")
        assertTrue(me.deleteMessageForMe(peer, "M-1"))
        assertTrue(me.history(peer).isEmpty())
        fx.inbound(me, peer, "secret", msgId = "M-1")
        assertTrue("a redelivered copy resurrected it", me.history(peer).isEmpty())
    }

    @Test
    fun `an admin's delete group removes it here only, and a member's re-share does not restore it`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val g = a.createGroup("gone", listOf(b.address))
        b.router.poll()
        a.sendGroupText(g.groupId, "hello")
        assertTrue(a.deleteGroupLocally(g.groupId))
        assertNull(a.groups.get(g.groupId))
        assertTrue(a.history(g.groupId).isEmpty())
        assertNotNull("iOS sends nothing: the members keep the group", b.groups.get(g.groupId))
        b.renameGroup(g.groupId, "still here")
        a.router.poll()
        assertNull("a member's broadcast brought the deleted group back", a.groups.get(g.groupId))
    }

    @Test
    fun `a blocked group stays blocked across a reload and a forward goes out in iOS's format`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val g = a.createGroup("noise", listOf(b.address))
        b.router.poll()
        assertNotNull(b.setGroupBlocked(g.groupId, true))
        assertTrue(b.isGroupBlocked(g.groupId))
        assertFalse("block leaked to another member", a.isGroupBlocked(g.groupId))

        a.sendGroupText(g.groupId, "forward me")
        val m = a.messages.messages(g.groupId).single()
        assertEquals(OshiClient.SendOutcome.SENT, a.forwardMessage(g.groupId, m.id, b.address))
        b.router.poll()
        val fwd = ReplyEnvelope.unwrap(b.history(a.address).last().content)!!
        assertEquals("forward me", fwd.content)
        assertNotNull(fwd.forwardedFrom)
    }

    @Test
    fun `a group typing ping is surfaced only when it names the member who delivered it`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        me.groups.put(
            com.oshi.desktop.group.GroupDefinition(
                groupId = "AAAA-0003", name = "t", type = GroupType.COLLABORATIVE, adminPublicKey = me.address,
                members = listOf(
                    com.oshi.desktop.group.GroupMember(me.address, joinedAtUnixMillis = 1_700_000_000_000L, isAdmin = true),
                    com.oshi.desktop.group.GroupMember(peer, joinedAtUnixMillis = 1_700_000_000_000L),
                ),
                createdAtUnixMillis = 1_700_000_000_000L, lastActivityUnixMillis = 1_700_000_000_000L, stateVersion = 1,
            )
        )
        val seen = mutableListOf<String>()
        me.onControl = { _, event, _ ->
            if (event is com.oshi.desktop.msg.ControlEvent.Typing) seen += event.payload.senderPublicKey
        }
        fun ping(claimed: String) = fx.inbound(
            me, peer,
            com.oshi.desktop.group.GroupMessageWire.encodeForEnvelope(
                com.oshi.desktop.group.GroupMessageWire.GroupMessagePayload(
                    messageId = java.util.UUID.randomUUID().toString().uppercase(), groupId = "AAAA-0003",
                    senderPublicKey = peer,
                    body = com.oshi.desktop.msg.TypingPayload.encode(claimed, true, System.currentTimeMillis(), groupId = "AAAA-0003"),
                    timestampUnixMillis = System.currentTimeMillis(),
                )
            ),
            groupId = "AAAA-0003",
        )
        ping(me.address)   // claims to be someone else
        ping(peer)         // honest
        assertEquals(listOf(peer), seen)
    }
}
