package com.oshi.desktop.feata

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.app.WiringFixture
import com.oshi.desktop.msg.ReplyEnvelope
import com.oshi.desktop.store.MediaType
import com.oshi.desktop.ui.state.ChatShellModel
import com.oshi.desktop.ui.state.Severity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.imageio.ImageIO

/**
 * __MAC_PARITY_A_2027__ The features end to end between two or three desktop clients over a
 * local relay — the same wire the phones read (no phone is on the other end here).
 */
class MacParityAWiringTest {
    private val fx = WiringFixture()
    private val models = ArrayList<ChatShellModel>()
    private val saved = com.oshi.desktop.i18n.Strings.activeTag
    private val tmp = Files.createTempDirectory("oshi-feata").toFile()

    @Before fun en() { com.oshi.desktop.i18n.Strings.activeTag = "en" }
    @After fun done() {
        models.forEach { runCatching { it.close() } }
        fx.close(); tmp.deleteRecursively()
        com.oshi.desktop.i18n.Strings.activeTag = saved
    }

    private fun model(c: OshiClient) = ChatShellModel(c, Executor { it.run() }).also { models += it; it.attach() }

    private fun png(name: String, w: Int = 640, h: Int = 480): File {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = java.awt.Color(30, 160, 90); g.fillOval(10, 10, w - 20, h - 20); g.dispose()
        return File(tmp, name).also { ImageIO.write(img, "png", it) }
    }

    @Test
    fun `one message forwarded to two people arrives at both as a forward`() {
        val a = fx.client("a"); val b = fx.client("b"); val c = fx.client("c")
        fx.seedIncoming(a, b.address, "orig-1", "the plan for Thursday")
        a.contacts.seen(c.address, System.currentTimeMillis())
        val m = model(a)
        m.select(b.address)
        m.forwardMessageToMany("orig-1", listOf(b.address, c.address))
        assertEquals(Severity.OK, m.state.notice?.severity)
        assertTrue(m.state.notice!!.text, m.state.notice!!.text.contains("2"))
        for (peer in listOf(b, c)) {
            assertTrue(peer.router.poll() >= 1)
            val got = peer.messages.messages(a.address).last()
            val env = ReplyEnvelope.unwrap(got.content)!!
            assertEquals("the plan for Thursday", env.content)
            assertNotNull("not marked forwarded", env.forwardedFrom)
        }
    }

    @Test
    fun `reply privately opens the member's 1-1 quoting the group message`() {
        val a = fx.client("a"); val b = fx.client("b")
        val g = a.createGroup("crew", listOf(b.address))
        a.messages.append(com.oshi.desktop.store.Message(
            id = "g-msg-1", conversationId = g.groupId, senderAddress = b.address, recipientAddress = a.address,
            fromMe = false, content = "who has the van keys?", sentAtMs = System.currentTimeMillis(),
            sentAtSource = com.oshi.desktop.store.TimestampSource.RELAY_ENVELOPE_MS,
        ))
        val m = model(a)
        m.select(g.groupId)
        val row = m.state.thread!!.messages.single { it.id == "g-msg-1" }
        assertEquals(b.address, row.senderAddress)
        m.beginPrivateReply("g-msg-1")
        assertEquals(b.address, m.state.selectedId)
        assertEquals("who has the van keys?", m.state.replyingTo?.text)
        m.draft("I do"); m.send()
        assertTrue(b.router.poll() >= 1)
        val got = b.messages.messages(a.address).last()
        val env = ReplyEnvelope.unwrap(got.content)
        // b's client unwraps on display; the stored row keeps the quote it carried.
        val text = env?.content ?: got.content
        assertEquals("I do", text)
    }

    @Test
    fun `a picture set here reaches a contact and is drawn on their list row`() {
        val a = fx.client("a"); val b = fx.client("b")
        fx.seedOutgoing(a, b.address, "hi", "hi")           // the phones' gate: only people we wrote to
        a.contacts.seen(b.address, System.currentTimeMillis())
        b.contacts.seen(a.address, System.currentTimeMillis())
        val u = a.setOwnPhoto(png("me.png").readBytes())
        assertTrue(u.ok); assertEquals(1, u.sent)
        assertNotNull(a.ownPhotoBase64)
        repeat(2) { b.router.poll() }
        val pic = b.photos.get(a.address)!!
        assertEquals(a.ownPhotoBase64, pic)
        val mb = model(b)
        assertEquals(pic, mb.state.conversations.firstOrNull { it.id == a.address }?.pictureBase64 ?: b.photos.get(a.address))
        // Removing it tells them too (iOS: absent = no picture).
        assertTrue(a.setOwnPhoto(null).ok)
        repeat(2) { b.router.poll() }
        assertNull(b.photos.get(a.address))
    }

    @Test
    fun `a view-once photo arrives flagged, opens once and its bytes are destroyed`() {
        val a = fx.client("a"); val b = fx.client("b")
        assertEquals(OshiClient.SendOutcome.SENT, a.sendFile(b.address, png("once.png"), MediaType.IMAGE, viewOnce = true))
        assertTrue(a.messages.messages(b.address).last().isViewOnce)
        assertEquals(1, b.router.poll())
        val got = b.messages.messages(a.address).last()
        assertTrue("the receiver lost isViewOnce", got.isViewOnce)
        val file = File(got.mediaRef!!)
        assertTrue(file.isFile)
        assertTrue(b.markViewOnceOpened(a.address, got.id))
        assertFalse("opens once", b.markViewOnceOpened(a.address, got.id))
        assertEquals(1, b.wipeOpenedViewOnce(a.address))
        assertFalse("bytes survived", file.exists())
        val after = b.messages.messages(a.address).last()
        assertTrue(after.viewOnceOpened); assertNull(after.mediaRef)
        // A document is never sent view-once, whatever the caller asks.
        val doc = File(tmp, "notes.txt").apply { writeText("plain") }
        a.sendFile(b.address, doc, MediaType.DOCUMENT, viewOnce = true)
        assertFalse(a.messages.messages(b.address).last().isViewOnce)
    }

    @Test
    fun `who reacted names each person`() {
        val a = fx.client("a"); val b = fx.client("b")
        fx.seedOutgoing(a, b.address, "r1", "hello")
        a.messages.setReaction(b.address, "r1", b.address, "👍")
        a.messages.setReaction(b.address, "r1", a.address, "👍")
        a.contacts.seen(b.address, System.currentTimeMillis()); a.contacts.setDisplayName(b.address, "Bob")
        val m = model(a); m.select(b.address)
        val row = m.state.thread!!.messages.single { it.id == "r1" }
        assertEquals(listOf("You", "Bob"), row.reactors.single().names)
    }
}
