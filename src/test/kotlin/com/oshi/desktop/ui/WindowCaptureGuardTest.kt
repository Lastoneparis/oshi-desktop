package com.oshi.desktop.ui

import com.oshi.desktop.store.ScreenCapturePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** __SCREEN_CAPTURE_PROTECTION_2026_11_20__ Android's default and the Win32 values. */
class WindowCaptureGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `never set is ON and an explicit choice is respected`() {
        assertTrue(ScreenCapturePreference.resolve(null))
        assertFalse(ScreenCapturePreference.resolve(false))
        assertTrue(ScreenCapturePreference.resolve(true))
    }

    @Test
    fun `the choice survives a restart`() {
        ScreenCapturePreference.attach(tmp.root)
        assertTrue(ScreenCapturePreference.enabled)
        assertNull(ScreenCapturePreference.read(File(tmp.root, ScreenCapturePreference.FILE_NAME)))
        ScreenCapturePreference.set(false)
        ScreenCapturePreference.attach(tmp.root)
        assertFalse(ScreenCapturePreference.enabled)
        ScreenCapturePreference.set(true)
    }

    @Test
    fun `protection asks for exclude-from-capture first, then monitor, and off clears it`() {
        // WDA_EXCLUDEFROMCAPTURE = 0x11 (Windows 10 2004+), WDA_MONITOR = 0x1 (older): winuser.h
        assertEquals(listOf(0x11, 0x1), WindowCaptureGuard.affinities(true))
        assertEquals(listOf(0x0), WindowCaptureGuard.affinities(false))
    }

    @Test
    fun `the main window title carries no name`() {
        val src = File("src/main/kotlin/com/oshi/desktop/ui/DesktopWindow.kt").readText()
        assertFalse("window titles are logged by monitoring tools", src.contains("title = \"\${t(\"identity.app_name\")} — \${client.displayName}\""))
    }
}
