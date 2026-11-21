package com.oshi.desktop.feata

import com.oshi.desktop.call.media.CallVoiceEffects
import com.oshi.desktop.media.VoiceEffectRender
import com.oshi.desktop.media.WavWriter
import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.ProfilePhotoWire
import com.oshi.desktop.msg.ProfileUpdateWire
import com.oshi.desktop.store.ContactPhotoStore
import com.oshi.desktop.store.MediaType
import com.oshi.desktop.ui.state.ForwardSelection
import com.oshi.desktop.ui.state.GalleryCategories
import com.oshi.desktop.ui.state.GalleryCategory
import com.oshi.desktop.ui.state.GroupCandidateRow
import com.oshi.desktop.ui.state.LinkItemRow
import com.oshi.desktop.ui.state.MediaItemRow
import com.oshi.desktop.ui.state.Reactors
import com.oshi.desktop.ui.state.ViewOnceRules
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO

/** __MAC_PARITY_A_2027__ the pure rules behind the six Mac-parity features. */
class MacParityARulesTest {

    // ------------------------------------------------------------------ who reacted

    @Test
    fun `reactors list you first, then names sorted, most-chosen reaction first`() {
        val me = "ME"
        val groups = Reactors.groups(
            mapOf("👍" to setOf("b", "ME", "a"), "❤️" to setOf("c"), "😂" to emptySet()),
            isMe = { it == me }, label = { it.uppercase() }, you = "You",
        )
        assertEquals(listOf("👍", "❤️"), groups.map { it.emoji })
        assertEquals(listOf("You", "A", "B"), groups[0].names)
        assertEquals(4, Reactors.total(groups))
    }

    // ------------------------------------------------------------------ forward to many

    @Test
    fun `forward search matches a name in any case but a key only exactly`() {
        val rows = listOf(GroupCandidateRow("AbCdEf==", "Zoé"), GroupCandidateRow("xyz==", "Bob"))
        assertEquals(listOf("Zoé"), ForwardSelection.filter(rows, "zo").map { it.label })
        assertEquals(listOf("Zoé"), ForwardSelection.filter(rows, "AbCd").map { it.label })
        assertTrue(ForwardSelection.filter(rows, "abcd").isEmpty())
        assertEquals(2, ForwardSelection.filter(rows, "  ").size)
    }

    @Test
    fun `forward selection toggles and is capped`() {
        var sel = emptyList<String>()
        sel = ForwardSelection.toggle(sel, "a"); sel = ForwardSelection.toggle(sel, "b")
        assertEquals(listOf("a", "b"), sel)
        sel = ForwardSelection.toggle(sel, "a")
        assertEquals(listOf("b"), sel)
        val full = (1..ForwardSelection.MAX_RECIPIENTS).map { "p$it" }
        assertEquals(full, ForwardSelection.toggle(full, "one-too-many"))
    }

    // ------------------------------------------------------------------ view once

    @Test
    fun `only photos, videos and voice notes go view-once, never a round video note`() {
        assertTrue(ViewOnceRules.canSendViewOnce(MediaType.IMAGE))
        assertTrue(ViewOnceRules.canSendViewOnce(MediaType.VIDEO))
        assertTrue(ViewOnceRules.canSendViewOnce(MediaType.AUDIO))
        assertFalse(ViewOnceRules.canSendViewOnce(MediaType.VIDEO, videoNote = true))
        assertFalse(ViewOnceRules.canSendViewOnce(MediaType.DOCUMENT))
        assertFalse(ViewOnceRules.canSendViewOnce(MediaType.CONTACT))
    }

    @Test
    fun `view-once looks follow the phones`() {
        assertEquals(ViewOnceRules.Look.NONE, ViewOnceRules.look(false, false, false, false, true))
        assertEquals(ViewOnceRules.Look.SENDER, ViewOnceRules.look(true, true, false, false, true))
        assertEquals(ViewOnceRules.Look.SEALED, ViewOnceRules.look(true, false, false, false, true))
        assertEquals(ViewOnceRules.Look.OPEN, ViewOnceRules.look(true, false, true, true, true))
        // Opened earlier (another visit): gone, even if the file has not been wiped yet.
        assertEquals(ViewOnceRules.Look.VIEWED, ViewOnceRules.look(true, false, true, false, true))
        assertEquals(ViewOnceRules.Look.VIEWED, ViewOnceRules.look(true, false, false, false, false))
    }

    // ------------------------------------------------------------------ gallery by type

    private fun item(id: String, kind: MediaType) = MediaItemRow(id, "/x/$id", kind, id, "12:00", false)

    @Test
    fun `gallery sorts attachments into the five iOS categories`() {
        val items = listOf(item("p", MediaType.IMAGE), item("v", MediaType.VIDEO), item("a", MediaType.AUDIO),
            item("d", MediaType.DOCUMENT), item("c", MediaType.CONTACT))
        assertEquals(listOf("d", "c"), GalleryCategories.media(items, GalleryCategory.DOCUMENTS).map { it.messageId })
        assertEquals(listOf("a"), GalleryCategories.media(items, GalleryCategory.AUDIO).map { it.messageId })
        val links = listOf(LinkItemRow("m#u", "https://oshi.example/x", "oshi.example", "12:00", true))
        assertEquals(1, GalleryCategories.count(items, links, GalleryCategory.LINKS))
        assertEquals(GalleryCategory.LINKS, GalleryCategories.firstNonEmpty(emptyList(), links))
        assertEquals(GalleryCategory.PHOTOS, GalleryCategories.firstNonEmpty(emptyList(), emptyList()))
    }

    @Test
    fun `links are found in prose without the punctuation around them`() {
        val found = GalleryCategories.links("See https://example.org/a?b=1, and (www.oshi-messenger.com). Done!")
        assertEquals(listOf("https://example.org/a?b=1", "www.oshi-messenger.com"), found)
        assertEquals("example.org", GalleryCategories.host(found[0]))
        assertEquals("oshi-messenger.com", GalleryCategories.host(found[1]))
        assertEquals("https://www.oshi-messenger.com", GalleryCategories.openable(found[1]))
        assertTrue(GalleryCategories.links("no link here, just example.org").isEmpty())
    }

    // ------------------------------------------------------------------ profile photo wire

    private fun jpeg(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = java.awt.Color(120, 40, 200); g.fillRect(0, 0, w, h / 2); g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    @Test
    fun `our picture becomes a centred JPEG square that Android will accept`() {
        val out = ProfilePhotoWire.prepare(jpeg(1600, 900))!!
        assertTrue(out.size <= ProfilePhotoWire.TARGET_BYTES)
        assertTrue(out.size <= ProfilePhotoWire.MAX_INBOUND_BYTES)
        val back = ImageIO.read(out.inputStream())
        assertEquals(back.width, back.height)
        assertTrue(back.width <= ProfilePhotoWire.SIDE)
        assertNull(ProfilePhotoWire.prepare("not an image".toByteArray()))
    }

    @Test
    fun `the profile update carries profileImageData only when we have one`() {
        val with = JSONObject(ControlPrefix.strip(ProfileUpdateWire.encode("Zoé", photoBase64 = "AAAA")))
        assertEquals("AAAA", with.getString("profileImageData"))
        // iOS requires these three, so they must survive the new key.
        assertEquals("profile_update", with.getString("type")); assertTrue(with.has("lastSeen")); assertTrue(with.has("version"))
        assertFalse(JSONObject(ControlPrefix.strip(ProfileUpdateWire.encode("Zoé"))).has("profileImageData"))
    }

    @Test
    fun `an inbound picture follows iOS - absent or null clears, oversize or garbage is refused`() {
        val pic = ProfilePhotoWire.prepare(jpeg(300, 300))!!
        val b64 = Base64.getEncoder().encodeToString(pic)
        val set = ProfilePhotoWire.read(ControlPrefix.PROFILE_UPDATE + JSONObject().put("type", "profile_update").put("profileImageData", b64))
        assertTrue(set is ProfilePhotoWire.Read.Set)
        assertArrayEquals(pic, Base64.getDecoder().decode((set as ProfilePhotoWire.Read.Set).base64))
        assertEquals(ProfilePhotoWire.Read.Clear, ProfilePhotoWire.read(ControlPrefix.PROFILE_UPDATE + """{"type":"profile_update","profileImageData":null}"""))
        assertEquals(ProfilePhotoWire.Read.Clear, ProfilePhotoWire.read(ControlPrefix.PROFILE_UPDATE + """{"type":"profile_update"}"""))
        val huge = Base64.getEncoder().encodeToString(ByteArray(ProfilePhotoWire.MAX_INBOUND_BYTES + 10) { 0xFF.toByte() })
        assertEquals(ProfilePhotoWire.Read.Refused, ProfilePhotoWire.read(JSONObject().put("profileImageData", huge)))
        val notImage = Base64.getEncoder().encodeToString("hello, this is not a picture".toByteArray())
        assertEquals(ProfilePhotoWire.Read.Refused, ProfilePhotoWire.read(JSONObject().put("profileImageData", notImage)))
        assertEquals(ProfilePhotoWire.Read.NotProfile, ProfilePhotoWire.read("just text"))
    }

    @Test
    fun `the photo store is sealed, keyed by canonical identity, and cleared with the account`() {
        val dir = Files.createTempDirectory("oshi-photos").toFile()
        try {
            val key = ByteArray(32) { it.toByte() }
            val s = ContactPhotoStore(dir, key)
            val std = "ab+/" + "A".repeat(39) + "="
            val url = std.replace('+', '-').replace('/', '_').trimEnd('=')
            assertTrue(s.set(std, "QUJD"))
            assertFalse("same picture again costs no write", s.set(std, "QUJD"))
            assertEquals("QUJD", ContactPhotoStore(dir, key).get(url))
            dir.listFiles()!!.forEach { assertFalse("plaintext on disk", it.readText().contains("QUJD")) }
            assertTrue(s.set(ContactPhotoStore.SELF, "U0VMRg=="))
            s.clear()
            assertNull(ContactPhotoStore(dir, key).get(std))
            assertNull(s.get(ContactPhotoStore.SELF))
        } finally { dir.deleteRecursively() }
    }

    // ------------------------------------------------------------------ voice effects

    private fun wav(dir: File, samples: ShortArray): File {
        val pcm = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v -> pcm[2 * i] = (v.toInt() and 0xFF).toByte(); pcm[2 * i + 1] = ((v.toInt() shr 8) and 0xFF).toByte() }
        return File(dir, "in.wav").apply { writeBytes(WavWriter.header(pcm.size.toLong()) + pcm) }
    }

    @Test
    fun `a voice effect keeps the recording's length and header, and changes the samples`() {
        val dir = Files.createTempDirectory("oshi-fx").toFile()
        try {
            val n = 44_100
            val src = wav(dir, ShortArray(n) { (8000 * kotlin.math.sin(2 * Math.PI * 220 * it / 44_100.0)).toInt().toShort() })
            for (e in CallVoiceEffects.Effect.entries.filter { it != CallVoiceEffects.Effect.NONE }) {
                val out = File(dir, "out-${e.wire}.wav")
                VoiceEffectRender.apply(src, e, out)
                assertEquals("$e changed the length", src.length(), out.length())
                assertArrayEquals(src.readBytes().copyOf(WavWriter.HEADER_BYTES), out.readBytes().copyOf(WavWriter.HEADER_BYTES))
                assertFalse("$e did nothing", src.readBytes().contentEquals(out.readBytes()))
            }
            val same = File(dir, "none.wav")
            VoiceEffectRender.apply(src, CallVoiceEffects.Effect.NONE, same)
            assertArrayEquals(src.readBytes(), same.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
