package com.oshi.desktop.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __VIDEO_NOTE_2026_09_24__ The composer's typing rule, held headlessly (the composable is not). */
class ComposerChromeTest {

    @Test
    fun `typing hides the capture buttons and widens the field, clearing brings them back`() {
        val empty = ComposerChrome.of("")
        assertTrue(empty.captureButtonsVisible)
        assertFalse(empty.fieldExpanded)

        val typing = ComposerChrome.of("h")
        assertFalse("the video/voice/GIF buttons stayed while typing", typing.captureButtonsVisible)
        assertTrue(typing.fieldExpanded)

        // Whitespace is still typing (the user pressed a key); only an EMPTY field restores.
        assertFalse(ComposerChrome.of(" ").captureButtonsVisible)
        assertEquals(empty, ComposerChrome.of("hello".drop(5)))
    }

    @Test
    fun `a running voice recording keeps its Stop control whatever the draft says`() {
        val c = ComposerChrome.of("typed while recording", voiceRecording = true)
        assertTrue(c.captureButtonsVisible)
        assertFalse(c.fieldExpanded)
    }

    @Test
    fun `the recorder ring fills over the 15 second cap and never past it`() {
        assertEquals(0f, VideoNoteRing.fraction(0), 0f)
        assertEquals(0.5f, VideoNoteRing.fraction(7_500), 1e-6f)
        assertEquals(1f, VideoNoteRing.fraction(15_000), 0f)
        assertEquals(1f, VideoNoteRing.fraction(99_000), 0f)
        assertEquals(0f, VideoNoteRing.fraction(-5), 0f)
    }
}
