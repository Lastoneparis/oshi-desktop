package com.oshi.desktop.ui.components

import com.oshi.desktop.store.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard: **this window never draws a picture it was not entitled to draw.**
 *
 * `MediaPresentation.inlineImage` is the one boolean in the drawing layer whose wrong value
 * is a privacy failure rather than a cosmetic one — a view-once row rendered eagerly shows a
 * sender's photo they asked to be shown once. Everything below exists to hold that boolean,
 * with an injected filesystem probe so it runs on a machine with no display and no disk.
 *
 * No test here touches Compose. See `DesktopWindow`'s NOT COVERED BY ANY TEST section for
 * what that leaves uncovered, which is the drawing itself.
 */
class MediaPresentationTest {

    private val present: (String) -> Long? = { 4096L }
    private val absent: (String) -> Long? = { null }

    // ------------------------------------------------------------------ no attachment

    @Test
    fun `a row with no attachment has no presentation`() {
        assertNull(MediaPresentation.of(null, probe = present))
        assertNull(MediaPresentation.of("", probe = present))
        assertNull(MediaPresentation.of("   ", probe = present))
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    fun `an image that is on disk inlines`() {
        val p = MediaPresentation.of("[image] /var/oshi/media/cat.jpg", probe = present)!!
        assertEquals(MediaType.IMAGE, p.kind)
        assertEquals("/var/oshi/media/cat.jpg", p.path)
        assertEquals("cat.jpg", p.fileName)
        assertTrue(p.inlineImage)
        assertNull(p.note)
    }

    @Test
    fun `photo is accepted as an alias for image because Android emits it`() {
        // `MediaType.fromWire` maps it; asserted here so a rename over there is caught by a
        // test that describes the consequence — a peer's photo drawn as an opaque file card.
        assertTrue(MediaPresentation.of("[photo] /m/a.jpg", probe = present)!!.inlineImage)
    }

    // ------------------------------------------------------------------ THE GUARDS

    @Test
    fun `a view-once image does NOT inline until it is revealed`() {
        val sealed = MediaPresentation.of("[image] /m/secret.jpg", viewOnce = true, probe = present)!!
        assertFalse("a sealed view-once row must never render its bytes", sealed.inlineImage)
        assertTrue(sealed.sealedViewOnce)
        assertEquals(MediaPresentation.SEALED_NOTE, sealed.note)

        val opened = MediaPresentation.of("[image] /m/secret.jpg", viewOnce = true, revealed = true, probe = present)!!
        assertTrue("revealing must actually reveal", opened.inlineImage)
        assertFalse(opened.sealedViewOnce)
    }

    @Test
    fun `a non-image never inlines however plausible its name`() {
        // A receiver reads the mediaType FIELD, never the extension — PARITY.md row 0.12, and
        // the live-phone defect recorded there is exactly this confusion in reverse.
        for (wire in listOf("document", "video", "audio", "contact", "location", "unknown")) {
            val p = MediaPresentation.of("[$wire] /m/looks-like.png", probe = present)!!
            assertFalse("$wire must not inline", p.inlineImage)
        }
    }

    @Test
    fun `a file that is not on disk never inlines and says so`() {
        val p = MediaPresentation.of("[image] /m/gone.jpg", probe = absent)!!
        assertFalse(p.inlineImage)
        assertEquals(MediaPresentation.MISSING_FILE_NOTE, p.note)
    }

    @Test
    fun `an image over the decode cap never inlines and says so`() {
        val overCap: (String) -> Long? = { MediaPresentation.MAX_INLINE_BYTES + 1 }
        val p = MediaPresentation.of("[image] /m/huge.jpg", probe = overCap)!!
        assertFalse(p.inlineImage)
        assertEquals(MediaPresentation.OVERSIZE_NOTE, p.note)

        val atCap: (String) -> Long? = { MediaPresentation.MAX_INLINE_BYTES }
        assertTrue("the cap is inclusive", MediaPresentation.of("[image] /m/ok.jpg", probe = atCap)!!.inlineImage)
    }

    @Test
    fun `an unparseable reference is shown verbatim and never inlined`() {
        val p = MediaPresentation.of("/m/no-type-prefix.jpg", probe = present)!!
        assertTrue(p.unparseable)
        assertFalse(p.inlineImage)
        assertEquals("/m/no-type-prefix.jpg", p.path)
        assertEquals(MediaPresentation.UNPARSEABLE_NOTE, p.note)
    }

    @Test
    fun `a path containing the delimiter is not split on it`() {
        val weird = "[image] /m/album ] 2026/shot.jpg"
        val p = MediaPresentation.of(weird, probe = present)!!
        assertEquals("/m/album ] 2026/shot.jpg", p.path)
        assertEquals("shot.jpg", p.fileName)
    }

    // ------------------------------------------------------------------ names and labels

    @Test
    fun `a Windows path yields a Windows file name`() {
        assertEquals("shot.png", MediaPresentation.nameOf("""C:\Users\a\OSHI\media\shot.png"""))
        assertEquals("shot.png", MediaPresentation.nameOf("/home/a/media/shot.png"))
        assertEquals("shot.png", MediaPresentation.nameOf("shot.png"))
    }

    @Test
    fun `a card never calls a non-image a photo`() {
        assertFalse(MediaPresentation.cardLabel(MediaType.DOCUMENT).lowercase().contains("image"))
        assertTrue(MediaPresentation.cardLabel(MediaType.AUDIO).contains("click to play"))
        assertTrue(MediaPresentation.cardLabel(MediaType.VIDEO).contains("no player"))
    }

    // ------------------------------------------------------------------ ViewOnceFacts

    @Test
    fun `unknown ids are not view-once and the empty lookup claims nothing`() {
        assertFalse(ViewOnceFacts.NONE.isViewOnce("anything"))
        val facts = ViewOnceFacts.of(setOf("m1"))
        assertTrue(facts.isViewOnce("m1"))
        assertFalse(facts.isViewOnce("m2"))
    }

    @Test
    fun `presentation is not null when there is any attachment at all`() {
        assertNotNull(MediaPresentation.of("[document] /m/x.pdf", probe = absent))
    }
}
