package com.oshi.desktop.ui

import com.oshi.desktop.ui.components.AttachmentPanelSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraTileTest {
    @Test
    fun `the camera tile follows the gallery, and only where attachments travel`() {
        assertEquals(
            listOf("GALLERY", "CAMERA", "FILE", "VIDEO_NOTE"),
            AttachmentPanelSpec.tiles(true, gif = false, contact = false, poll = false, videoNote = true, disguise = false, camera = true).map { it.name },
        )
        assertEquals(emptyList<String>(), AttachmentPanelSpec.tiles(false, false, false, false, false, disguise = false, camera = true).map { it.name })
    }
}
