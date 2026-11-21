package com.oshi.desktop.mergeab

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.app.WiringFixture
import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.ProfileUpdateWire
import com.oshi.desktop.store.PrivacyPreferences
import com.oshi.desktop.ui.state.ChatShellModel
import org.json.JSONObject
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
 * Integration of Mac-parity A (profile photos, reply privately) with Mac-parity B (privacy
 * settings, member menu) — the seams neither branch could test alone.
 */
class MergeABIntegrationTest {
    private val fx = WiringFixture()
    private val models = ArrayList<ChatShellModel>()
    private val saved = com.oshi.desktop.i18n.Strings.activeTag
    private val tmp = Files.createTempDirectory("oshi-mergeab").toFile()

    @Before fun en() {
        com.oshi.desktop.i18n.Strings.activeTag = "en"
        PrivacyPreferences.preview(PrivacyPreferences.Values())
    }
    @After fun done() {
        models.forEach { runCatching { it.close() } }
        fx.close(); tmp.deleteRecursively()
        com.oshi.desktop.i18n.Strings.activeTag = saved
        PrivacyPreferences.preview(PrivacyPreferences.Values())
    }

    private fun model(c: OshiClient) = ChatShellModel(c, Executor { it.run() }).also { models += it; it.attach() }

    private fun png(name: String): File {
        val img = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = java.awt.Color(200, 60, 90); g.fillRect(10, 10, 300, 220); g.dispose()
        return File(tmp, name).also { ImageIO.write(img, "png", it) }
    }

    @Test
    fun `Profile Photo off keeps the picture off the wire`() {
        val photo = "QUJD"
        PrivacyPreferences.preview(PrivacyPreferences.Values(profilePhotoVisible = false))
        val off = JSONObject(ControlPrefix.strip(ProfileUpdateWire.encode("Zoé", photoBase64 = PrivacyPreferences.outgoingProfilePhoto(photo))))
        assertFalse(off.has("profileImageData"))
        PrivacyPreferences.preview(PrivacyPreferences.Values(profilePhotoVisible = true))
        val on = JSONObject(ControlPrefix.strip(ProfileUpdateWire.encode("Zoé", photoBase64 = PrivacyPreferences.outgoingProfilePhoto(photo))))
        assertEquals(photo, on.getString("profileImageData"))
    }

    @Test
    fun `flipping Profile Photo off withdraws the picture a contact holds, and on gives it back`() {
        val a = fx.client("a"); val b = fx.client("b")
        fx.seedOutgoing(a, b.address, "hi", "hi")
        a.contacts.seen(b.address, System.currentTimeMillis())
        b.contacts.seen(a.address, System.currentTimeMillis())
        model(a)   // registers the privacy listener
        assertTrue(a.setOwnPhoto(png("me.png").readBytes()).ok)
        repeat(2) { b.router.poll() }
        assertNotNull(b.photos.get(a.address))

        PrivacyPreferences.update { it.copy(profilePhotoVisible = false) }
        repeat(2) { b.router.poll() }
        assertNull("contact still holds the picture after Profile Photo went off", b.photos.get(a.address))
        assertNotNull("our own picture is kept locally", a.ownPhotoBase64)

        PrivacyPreferences.update { it.copy(profilePhotoVisible = true) }
        repeat(2) { b.router.poll() }
        assertEquals(a.ownPhotoBase64, b.photos.get(a.address))
    }

    @Test
    fun `member menu Reply privately opens the member's 1-1 without a quote`() {
        val a = fx.client("a"); val b = fx.client("b")
        val g = a.createGroup("crew", listOf(b.address))
        val m = model(a)
        m.select(g.groupId)
        m.replyPrivatelyToMember(b.address)
        assertEquals(b.address, m.state.selectedId)
        assertNull(m.state.replyingTo)
        // Ourselves: nothing happens.
        m.select(g.groupId)
        m.replyPrivatelyToMember(a.address)
        assertEquals(g.groupId, m.state.selectedId)
    }
}
