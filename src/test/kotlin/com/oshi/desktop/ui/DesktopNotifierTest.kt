package com.oshi.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopNotifierTest {

    @Test
    fun `Linux fallback is generic and translated`() {
        val saved = com.oshi.desktop.i18n.Strings.activeTag
        try {
            com.oshi.desktop.i18n.Strings.activeTag = "fr"
            val body = LinuxDesktopNotification.command("Linux", executable = true)!!.last()
            org.junit.Assert.assertNotEquals("New encrypted message", body)
            org.junit.Assert.assertFalse(body.startsWith("⟦"))
        } finally {
            com.oshi.desktop.i18n.Strings.activeTag = saved
        }
    }

    @Test
    fun `Linux fallback is generic and has no message metadata`() {
        com.oshi.desktop.i18n.Strings.activeTag = "en"
        assertEquals(
            listOf(
                "/usr/bin/notify-send",
                "--app-name=OSHI",
                "--urgency=normal",
                "OSHI",
                "New encrypted message",
            ),
            LinuxDesktopNotification.command("Linux", executable = true),
        )
    }

    @Test
    fun `fallback is absent outside Linux and when notify-send is unavailable`() {
        assertNull(LinuxDesktopNotification.command("Windows 11", executable = true))
        assertNull(LinuxDesktopNotification.command("Linux", executable = false))
    }

    /** __LINUX_NOTIFICATION_CLICK_2026_11_21__ the click action rides as an option before the text. */
    @Test
    fun `Linux notification carries a default action only when asked`() {
        com.oshi.desktop.i18n.Strings.activeTag = "en"
        val cmd = LinuxDesktopNotification.command("Linux", executable = true, body = "b", clickLabel = "Open OSHI")!!
        assertEquals(listOf("/usr/bin/notify-send", "--app-name=OSHI", "--urgency=normal", "--action=default=Open OSHI", "OSHI", "b"), cmd)
    }

    @Test
    fun `the action option is used only when notify-send lists it`() {
        // libnotify 0.7.9 (Ubuntu 22.04) has no --action: passing it there shows NOTHING.
        org.junit.Assert.assertFalse(LinuxDesktopNotification.helpListsAction("Usage:\n  notify-send [OPTION?] <SUMMARY> [BODY]\n  -u, --urgency=LEVEL\n  -h, --hint=TYPE:NAME:VALUE"))
        org.junit.Assert.assertTrue(LinuxDesktopNotification.helpListsAction("  -A, --action=[NAME=]Text...     Specifies the actions to display to the user."))
    }
}
