package com.oshi.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** __DESKTOP_BACKGROUND_2026_09_23__ The pure halves of the tray/login-item/notification work. */
class BackgroundPresenceTest {

    @Test
    fun `macOS notification passes text as an argument, never inside the script`() {
        val hostile = "\" & do shell script \"rm -rf ~\" & \""
        val cmd = MacNotification.command("Mac OS X", executable = true, body = hostile, sound = "Submarine")!!
        assertEquals("/usr/bin/osascript", cmd[0])
        val scripts = cmd.zipWithNext().filter { it.first == "-e" }.map { it.second }
        assertTrue(scripts.none { it.contains(hostile) })
        assertEquals(listOf("OSHI", hostile, "Submarine"), cmd.takeLast(3))
    }

    @Test
    fun `macOS notification is absent elsewhere`() {
        assertNull(MacNotification.command("Linux", executable = true, body = "x", sound = null))
        assertNull(MacNotification.command("Mac OS X", executable = false, body = "x", sound = null))
    }

    @Test
    fun `launch agent runs at load, does not keep alive, and escapes xml`() {
        val plist = LoginItem.plist(listOf("/opt/a&b/java", "-jar", "x<y>.jar", LoginItem.HIDDEN_FLAG))
        assertTrue(plist.contains("<string>/opt/a&amp;b/java</string>"))
        assertTrue(plist.contains("<string>x&lt;y&gt;.jar</string>"))
        assertTrue(plist.contains("<key>RunAtLoad</key><true/>"))
        assertFalse(plist.contains("KeepAlive"))
        assertTrue(plist.contains("<string>--hidden</string>"))
    }

    @Test
    fun `xdg autostart quotes every argument`() {
        val entry = LoginItem.desktopEntry(listOf("/usr/bin/java", "-Dx=\$HOME", "a \"b\""))
        assertTrue(entry.contains("Exec=\"/usr/bin/java\" \"-Dx=\\\$HOME\" \"a \\\"b\\\"\""))
    }

    @Test
    fun `windows run value quotes paths with spaces`() {
        assertEquals(
            "\"C:\\Program Files\\OSHI\\OSHI.exe\" --hidden",
            LoginItem.windowsCommandLine(listOf("C:\\Program Files\\OSHI\\OSHI.exe", "--hidden")),
        )
    }

    @Test
    fun `windows reports no arguments, so the installed launcher alone is registered`() {
        assertEquals(
            listOf("C:\\Users\\a\\AppData\\Local\\OSHI\\OSHI.exe", LoginItem.HIDDEN_FLAG),
            LoginItem.windowsFallback("C:\\Users\\a\\AppData\\Local\\OSHI\\OSHI.exe", "ignored", "ignored"),
        )
    }

    @Test
    fun `windows dev run is rebuilt from class path and main class, under javaw`() {
        assertEquals(
            listOf("C:\\jdk\\bin\\javaw.exe", "-cp", "a.jar;b.jar", "com.oshi.desktop.MainKt", "--ui", LoginItem.HIDDEN_FLAG),
            LoginItem.windowsFallback("C:\\jdk\\bin\\java.exe", "a.jar;b.jar", "com.oshi.desktop.MainKt --ui"),
        )
        assertNull(LoginItem.windowsFallback("C:\\jdk\\bin\\java.exe", null, "x"))
    }

    @Test
    fun `os detection`() {
        assertEquals(LoginItem.Os.MAC, LoginItem.os("Mac OS X"))
        assertEquals(LoginItem.Os.WINDOWS, LoginItem.os("Windows 11"))
        assertEquals(LoginItem.Os.LINUX, LoginItem.os("Linux"))
    }
}
