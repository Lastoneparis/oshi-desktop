package com.oshi.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopNotifierTest {

    @Test
    fun `Linux fallback is generic and has no message metadata`() {
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
}
