package com.oshi.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The menu modifier is the host's, and Quit is the OS's business on macOS.
 *
 * Both rules come from a measurement, not from a style guide, and both are invisible to
 * every other test in this suite because they only show up in a menu bar on a particular
 * operating system.
 *
 * `meta` is Command on macOS. Everywhere else it is the Windows or Super key, which belongs
 * to the window manager — a menu item bound there prints a chord beside itself that either
 * does nothing or fights the desktop environment. Nothing goes red either way.
 *
 * And on macOS the application menu is the SYSTEM's: read out of the running process with
 * System Events, it holds `About OSHI, Services, Hide OSHI, Hide Others, Show All, Quit
 * OSHI, Quit and Keep Windows`, with Command-Q on its Quit, and clicking that item ended
 * the process. So this app's own Quit item must not claim the same chord — one process, one
 * Command-Q. Off macOS no such menu exists and the app's Quit is the only one there is.
 *
 * The host is a PARAMETER rather than a read of `os.name`, so both platforms' rules are
 * checked on whichever machine runs the suite. A test that could only assert the behaviour
 * of the host it happens to be on would be green on a Mac and silent about Windows.
 */
class MenuShortcutTest {

    @Test
    fun `the menu modifier is Command on macOS and Control elsewhere`() {
        // Compared as whole values: KeyShortcut's fields are internal to Compose, and
        // equality is the part that matters anyway — "the same chord a user would press".
        assertEquals(
            "macOS must use Command, not Control",
            KeyShortcut(Key.R, meta = true),
            menuShortcut(Key.R, isMac = true),
        )
        assertEquals(
            "Windows and Linux must use Control: meta is the Windows/Super key there, and " +
                "it belongs to the window manager.",
            KeyShortcut(Key.R, ctrl = true),
            menuShortcut(Key.R, isMac = false),
        )
        assertNotEquals(
            "the two hosts must not end up with the same chord",
            menuShortcut(Key.R, isMac = true),
            menuShortcut(Key.R, isMac = false),
        )
    }

    @Test
    fun `macOS leaves Quit to the system menu, everywhere else binds Control-Q`() {
        assertNull(
            "macOS already binds Command-Q to its own working Quit item; a second binding " +
                "puts one chord on two shutdown paths in one process.",
            quitShortcut(isMac = true),
        )
        assertEquals(
            "off macOS the app's Quit is the only one there is",
            KeyShortcut(Key.Q, ctrl = true),
            quitShortcut(isMac = false),
        )
    }
}
