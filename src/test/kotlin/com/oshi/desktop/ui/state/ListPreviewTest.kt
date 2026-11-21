package com.oshi.desktop.ui.state

import com.oshi.desktop.msg.AliveCheckInPayload
import com.oshi.desktop.place.LocationPayload
import com.oshi.desktop.store.MediaType
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.TimestampSource
import com.oshi.desktop.ui.OshiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_1_6_PARITY__ The conversation list's line for the last message.
 *
 * The 1.5.1 screenshot that started this: a voice note from an Android phone, previewed as
 * `[audio] note vocale été.m4a`. These pin what replaced it.
 */
class ListPreviewTest {

    private fun m(content: String?, type: MediaType? = null, ref: String? = null, durationMs: Long? = null, videoNote: Boolean = false, deleted: Boolean = false) = Message(
        id = "id", conversationId = "c", senderAddress = "s", recipientAddress = "r", fromMe = false,
        content = content, mediaType = type, mediaRef = ref, sentAtMs = 1_763_480_000_000L, sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
        mediaDurationMs = durationMs, videoNote = videoNote, isDeletedForEveryone = deleted,
    )

    private fun of(msg: Message) = ListPreview.of(msg) { it.content.orEmpty() }

    @Test fun `a voice note is a microphone, Voice message and its length - never the file name`() {
        val p = of(m("note vocale été.m4a", MediaType.AUDIO, "/data/media/abc-note vocale été.m4a", durationMs = 7_400))
        assertEquals(ListPreview.Kind.VOICE, p.kind)
        assertFalse(p.text, p.text.contains("[audio]"))
        assertFalse(p.text, p.text.contains(".m4a"))
        assertTrue(p.text, p.text.endsWith("0:07"))
    }

    @Test fun `a voice note with no announced duration says only Voice message`() {
        val p = of(m("x.m4a", MediaType.AUDIO, "/x.m4a"))
        assertEquals(ListPreview.Kind.VOICE, p.kind)
        assertFalse(p.text.contains(":"))
    }

    @Test fun `durations round to the second and carry minutes`() {
        assertEquals("0:01", ListPreview.duration(600))
        assertEquals("1:05", ListPreview.duration(65_000))
        assertEquals("12:00", ListPreview.duration(720_000))
        assertEquals(null, ListPreview.duration(0))
        assertEquals(null, ListPreview.duration(null))
    }

    @Test fun `a photo shows its caption, and Photo when the body is only the file name`() {
        assertEquals("Look!", of(m("Look!", MediaType.IMAGE, "/m/a.jpg")).text)
        val named = of(m("IMG_2041.HEIC", MediaType.IMAGE, "/m/b-IMG_2041.HEIC"))
        assertEquals(ListPreview.Kind.PHOTO, named.kind)
        assertFalse(named.text.contains("IMG_2041"))
    }

    @Test fun `a document shows its name`() {
        val p = of(m("contract.pdf", MediaType.DOCUMENT, "/m/c-contract.pdf"))
        assertEquals(ListPreview.Kind.FILE, p.kind)
        assertEquals("contract.pdf", p.text)
    }

    @Test fun `a location ping from a phone shows its address, not the payload`() {
        // The exact shape iOS `LocationPingPickerView.send()` produces: one-shot, no session.
        val wire = "📍LOCATION📍{\"latitude\":48.8566,\"longitude\":2.3522,\"timestamp\":785000000.5,\"isLive\":false,\"address\":\"Place de l'Hôtel de Ville\",\"isUpdate\":false,\"isStopped\":false}"
        assertTrue(LocationPayload.decode(wire) != null)
        val p = of(m(wire))
        assertEquals(ListPreview.Kind.LOCATION, p.kind)
        assertEquals("Place de l'Hôtel de Ville", p.text)
    }

    @Test fun `a check-in is a heart and a sentence, never JSON`() {
        val wire = AliveCheckInPayload.Event(AliveCheckInPayload.Kind.CONFIRMED, 1_763_480_000.0, 86_400).encode()
        val p = of(m(wire))
        assertEquals(ListPreview.Kind.ALIVE, p.kind)
        assertFalse(p.text.contains("{"))
    }

    @Test fun `a deleted message says so, in the catalog's words`() {
        val p = of(m(null, deleted = true))
        assertEquals(ListPreview.Kind.DELETED, p.kind)
        assertFalse(p.text.contains("(deleted)"))
    }

    @Test fun `text is one line and capped`() {
        val p = of(m("a\nb" + "x".repeat(200)))
        assertEquals(ListPreview.Kind.TEXT, p.kind)
        assertFalse(p.text.contains('\n'))
        assertEquals(ListPreview.MAX, p.text.length)
    }

    // ------------------------------------------------------------------ monogram

    @Test fun `monogram matches the phones for ordinary names`() {
        assertEquals("BP", OshiTheme.monogram("Bob PC", "A"))
        assertEquals("HU", OshiTheme.monogram("Hugo", "A"))
        // Phones split on spaces only.
        assertEquals("JE", OshiTheme.monogram("Jean-Luc", "A"))
    }

    @Test fun `monogram skips emoji and punctuation instead of drawing half a character`() {
        assertEquals("AL", OshiTheme.monogram("😀 Alice", "Q"))
        assertEquals("BO", OshiTheme.monogram("(Bob)", "Q"))
        assertEquals("AL", OshiTheme.monogram("@alice", "Q"))
    }

    @Test fun `a contact with no name takes its letters from the address`() {
        val addr = "Auk9VKn2bd1XvJ9rQ+/=="
        assertEquals("AU", OshiTheme.monogram("Auk9VKn2bd1XvJ9r…", addr))
        assertEquals("AU", OshiTheme.monogram("", addr))
        assertEquals("?", OshiTheme.monogram("", "+/="))
    }
}
