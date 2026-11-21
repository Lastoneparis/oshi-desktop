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
        // __AUTOSTART_EXEC_ESCAPING_2026_11_21__ quoting escape, THEN the string escape (\\ doubled):
        // on disk  Exec="/usr/bin/java" "-Dx=\\$HOME" "a \\"b\\""  — GLib rejects a lone \$ or \".
        assertTrue(entry, entry.contains("Exec=\"/usr/bin/java\" \"-Dx=\\\\\$HOME\" \"a \\\\\"b\\\\\"\""))
        assertTrue(entry, entry.contains("TryExec=/usr/bin/java\n"))
    }

    @Test
    fun `xdg autostart doubles a percent sign and a backslash`() {
        val entry = LoginItem.desktopEntry(listOf("/opt/o/bin/OSHI", "--home", "/home/a/50%\\x"))
        // 50%\x  ->  quoting: 50%\\x  ->  string: 50%\\\\x  ->  field code: 50%%\\\\x
        assertTrue(entry, entry.contains("\"--home\" \"/home/a/50%%\\\\\\\\x\""))
    }

    @Test
    fun `autostart never re-registers the oshi link this launch was opened with`() {
        assertEquals(
            listOf("/opt/oshi-desktop/oshi-desktop/bin/OSHI", "--home", "/h", LoginItem.HIDDEN_FLAG),
            LoginItem.relaunchArgs(
                "/opt/oshi-desktop/oshi-desktop/bin/OSHI",
                listOf("oshi://add?key=abc", "--home", "/h", LoginItem.HIDDEN_FLAG),
            ),
        )
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

    /**
     * __AUTOSTART_EXEC_ESCAPING_2026_11_21__ The entry as GLIB reads it — GNOME's own parser, so
     * the escaping is judged by the program that runs it. Linux only, and only where python3-gi
     * is installed (the linux-qa workflow installs it); skipped elsewhere.
     */
    @Test
    fun `GLib parses the autostart entry back to the exact argv`() {
        org.junit.Assume.assumeTrue("Linux only", System.getProperty("os.name").lowercase().contains("linux"))
        val probe = runCatching {
            ProcessBuilder("python3", "-c", "import gi; gi.require_version('GLib', '2.0'); from gi.repository import GLib")
                .redirectErrorStream(true).start().waitFor()
        }.getOrDefault(-1)
        org.junit.Assume.assumeTrue("python3-gi not installed", probe == 0)
        val argv = listOf("/opt/oshi-desktop/oshi-desktop/bin/OSHI", "--home", "/home/a/My \"OSHI\" \$HOME `x` 50% \\d", LoginItem.HIDDEN_FLAG)
        val f = java.io.File.createTempFile("oshi-autostart", ".desktop")
        f.writeText(LoginItem.desktopEntry(argv))
        val script = """
import sys, json, gi
gi.require_version('GLib', '2.0')
from gi.repository import GLib
kf = GLib.KeyFile(); kf.load_from_file(sys.argv[1], GLib.KeyFileFlags.NONE)
exec_ = kf.get_string('Desktop Entry', 'Exec')
exec_ = exec_.replace('%%', '%')
ok, argv = GLib.shell_parse_argv(exec_)
print(json.dumps({'argv': argv, 'tryexec': kf.get_string('Desktop Entry', 'TryExec')}))
"""
        val p = ProcessBuilder("python3", "-c", script, f.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
        val o = org.json.JSONObject(out.trim().lines().last())
        val parsed = (0 until o.getJSONArray("argv").length()).map { o.getJSONArray("argv").getString(it) }
        assertEquals(argv, parsed)
        assertEquals(argv[0], o.getString("tryexec"))
        f.delete()
    }
}
