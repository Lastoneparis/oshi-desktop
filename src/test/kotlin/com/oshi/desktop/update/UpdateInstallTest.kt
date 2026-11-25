package com.oshi.desktop.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** __DESKTOP_AUTO_UPDATE_2026_11_21__ which installer replaces this copy, and the command lines. */
class UpdateInstallTest {

    private val regOut = """

HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Uninstall\{0B1F7C1E-AAAA-4C5E-9F4F-1234567890AB}
    InstallLocation    REG_SZ    C:\Users\Ana\AppData\Local\OSHI\

HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Uninstall\OtherApp
    InstallLocation    REG_SZ    C:\Program Files\Other

End of search: 2 match(es) found.
""".replace("\n", "\r\n")

    private val noWhich: (String) -> String? = { null }

    /** The Linux cases spell Unix paths; `File` on the Windows CI leg would rewrite them. */
    private fun unixPaths() = assumeTrue(File.separatorChar == '/')
    private fun whichOf(vararg names: String): (String) -> String? = { n -> if (n in names) "/usr/bin/$n" else null }

    // ------------------------------------------------------------------ detection

    @Test fun `macOS never updates in-app`() {
        val m = UpdateInstall.detect(os = "mac os x", appPath = "/Applications/OSHI.app/Contents/MacOS/OSHI", run = { error("must not run") })
        assertTrue(m is UpdateInstall.Method.Unsupported)
    }

    @Test fun `no jpackage launcher - not a packaged install`() {
        assertTrue(UpdateInstall.detect(os = "linux", appPath = null, run = { error("must not run") }) is UpdateInstall.Method.Unsupported)
        assertTrue(UpdateInstall.detect(os = "windows 11", appPath = "", run = { error("must not run") }) is UpdateInstall.Method.Unsupported)
    }

    @Test fun `Windows - the launcher folder is an HKCU InstallLocation - MSI`() {
        var asked: List<String>? = null
        val m = UpdateInstall.detect(
            os = "windows 11", appPath = "C:\\Users\\Ana\\AppData\\Local\\OSHI\\OSHI.exe",
            run = { asked = it; UpdateInstall.Run(0, regOut) },
        )
        assertTrue(m.toString(), m is UpdateInstall.Method.WindowsMsi)
        assertEquals(InstallerKind.MSI, m.kind)
        assertEquals(listOf("query", UpdateInstall.HKCU_UNINSTALL, "/s", "/v", "InstallLocation"), asked!!.drop(1))
        assertTrue("absolute reg.exe, never a bare name", asked!![0].endsWith("reg.exe"))
    }

    @Test fun `Windows - an unpacked app image elsewhere is not upgraded by the MSI`() {
        val m = UpdateInstall.detect(os = "windows 11", appPath = "D:\\portable\\OSHI\\OSHI.exe", run = { UpdateInstall.Run(0, regOut) })
        assertTrue(m is UpdateInstall.Method.Unsupported)
        val none = UpdateInstall.detect(os = "windows 11", appPath = "C:\\x\\OSHI.exe", run = { UpdateInstall.Run(1, "ERROR: The system was unable to find the specified registry key or value.") })
        assertTrue(none is UpdateInstall.Method.Unsupported)
        assertTrue(UpdateInstall.detect(os = "windows 11", appPath = "C:\\x\\OSHI.exe", run = { null }) is UpdateInstall.Method.Unsupported)
    }

    @Test fun `Windows folder comparison ignores case, slashes, quotes and the trailing backslash`() {
        assertTrue(UpdateInstall.sameWindowsDir("C:\\Users\\Ana.B\\AppData\\Local\\OSHI\\", "c:/users/ana.b/appdata/local/oshi"))
        assertTrue(UpdateInstall.sameWindowsDir("\"C:\\Apps\\OSHI\"", "C:\\Apps\\OSHI"))
        assertFalse(UpdateInstall.sameWindowsDir("C:\\Apps\\OSHI2", "C:\\Apps\\OSHI"))
        assertFalse(UpdateInstall.sameWindowsDir("", ""))
        assertEquals(listOf("C:\\Users\\Ana\\AppData\\Local\\OSHI\\", "C:\\Program Files\\Other"), UpdateInstall.installLocations(regOut))
    }

    @Test fun `Linux - dpkg owns the launcher - deb`() {
        unixPaths()
        val launcher = "/opt/oshi-desktop/oshi-desktop/bin/OSHI"
        val m = UpdateInstall.detect(
            os = "linux", appPath = launcher, env = { null }, which = whichOf("dpkg", "rpm"),
            run = { cmd ->
                when {
                    cmd[0].endsWith("dpkg") -> UpdateInstall.Run(0, "oshi-desktop: $launcher\n")
                    else -> error("rpm must not be asked once dpkg answered")
                }
            },
        )
        assertEquals(UpdateInstall.Method.LinuxDeb(File(launcher)), m)
    }

    @Test fun `Linux - rpm owns it - rpm, dpkg present but not the owner is skipped`() {
        unixPaths()
        val launcher = "/opt/oshi-desktop/oshi-desktop/bin/OSHI"
        val m = UpdateInstall.detect(
            os = "linux", appPath = launcher, env = { null }, which = whichOf("dpkg", "rpm"),
            run = { cmd ->
                if (cmd[0].endsWith("dpkg")) UpdateInstall.Run(1, "dpkg-query: no path found matching pattern $launcher\n")
                else { assertEquals(listOf("/usr/bin/rpm", "-qf", "--queryformat", "%{NAME}\\n", launcher), cmd); UpdateInstall.Run(0, "oshi-desktop\n") }
            },
        )
        assertEquals(UpdateInstall.Method.LinuxRpm(File(launcher)), m)
    }

    @Test fun `Linux - another package owning the path, an AppImage, or nothing - unsupported`() {
        val l = "/opt/oshi-desktop/oshi-desktop/bin/OSHI"
        assertTrue(UpdateInstall.detect(os = "linux", appPath = l, env = { null }, which = whichOf("dpkg"),
            run = { UpdateInstall.Run(0, "something-else: $l") }) is UpdateInstall.Method.Unsupported)
        assertTrue(UpdateInstall.detect(os = "linux", appPath = l, env = { if (it == "APPIMAGE") "/home/a/OSHI.AppImage" else null },
            which = whichOf("dpkg"), run = { error("no") }) is UpdateInstall.Method.Unsupported)
        assertTrue(UpdateInstall.detect(os = "linux", appPath = l, env = { null }, which = noWhich, run = { error("no") }) is UpdateInstall.Method.Unsupported)
    }

    @Test fun `dpkg owner parsing drops the architecture qualifier`() {
        assertEquals("oshi-desktop", UpdateInstall.dpkgOwner("oshi-desktop:amd64: /opt/oshi-desktop/oshi-desktop/bin/OSHI"))
        assertEquals("oshi-desktop", UpdateInstall.dpkgOwner("oshi-desktop: /opt/x"))
        assertEquals(null, UpdateInstall.dpkgOwner("dpkg-query: no path found"))
    }

    // ------------------------------------------------------------------ Windows command

    @Test fun `Windows - msiexec passive, logged, into the same folder, then relaunch`() {
        val method = UpdateInstall.Method.WindowsMsi(File("C:\\Users\\O'Neil\\AppData\\Local\\OSHI\\"), File("C:\\Users\\O'Neil\\AppData\\Local\\OSHI\\OSHI.exe"))
        val msi = File("C:\\Users\\O'Neil\\AppData\\Local\\Temp\\OSHI-update\\oshi-desktop-1.6.1.msi")
        val script = UpdateInstall.windowsScript(4242, msi, method, "C:\\Windows\\System32\\msiexec.exe",
            File("C:\\t\\msi.log"), File("C:\\t\\last-install-result.txt"))
        assertTrue(script, script.contains("Wait-Process -Id 4242 -Timeout 60"))
        assertTrue(script, script.contains("-FilePath 'C:\\Windows\\System32\\msiexec.exe'"))
        // Single quotes in a path are doubled inside a PowerShell literal.
        assertTrue(script, script.contains("/i \"C:\\Users\\O''Neil\\AppData\\Local\\Temp\\OSHI-update\\oshi-desktop-1.6.1.msi\" /passive /norestart /l*v \"C:\\t\\msi.log\""))
        // No trailing backslash before the closing quote.
        assertTrue(script, script.contains("INSTALLDIR=\"C:\\Users\\O''Neil\\AppData\\Local\\OSHI\"'"))
        assertTrue(script, script.contains("Start-Process -FilePath 'C:\\Users\\O''Neil\\AppData\\Local\\OSHI\\OSHI.exe'"))
        assertTrue(script, script.contains("Remove-Item -LiteralPath"))
        assertTrue("the install runs before the relaunch", script.indexOf("msiexec") < script.indexOf("OSHI.exe'"))

        val cmd = UpdateInstall.windowsCommand(script, "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe")
        assertEquals("-EncodedCommand", cmd[cmd.size - 2])
        assertEquals(script, String(Base64.getDecoder().decode(cmd.last()), Charsets.UTF_16LE))
        assertTrue(cmd.containsAll(listOf("-NoProfile", "-NonInteractive", "Hidden")))
    }

    // ------------------------------------------------------------------ Linux commands

    @Test fun `Linux deb - pkexec apt-get, then dpkg`() {
        unixPaths()
        val pkg = File("/home/a/.local/share/oshi/updates/oshi-desktop-1.6.1.deb")
        val m = UpdateInstall.Method.LinuxDeb(File("/opt/oshi-desktop/oshi-desktop/bin/OSHI"))
        assertEquals(
            listOf(
                listOf("/usr/bin/pkexec", "/usr/bin/apt-get", "-o", "DPkg::Lock::Timeout=300", "install", "-y", pkg.path),
                listOf("/usr/bin/pkexec", "/usr/bin/dpkg", "-i", pkg.path),
            ),
            UpdateInstall.linuxCommands(pkg, m, whichOf("pkexec", "apt-get", "dpkg")),
        )
        assertEquals("sudo apt-get install /home/a/.local/share/oshi/updates/oshi-desktop-1.6.1.deb", UpdateInstall.manualCommand(pkg, m, whichOf("apt-get", "dpkg")))
        assertEquals("sudo dpkg -i /home/a/.local/share/oshi/updates/oshi-desktop-1.6.1.deb", UpdateInstall.manualCommand(pkg, m, whichOf("dpkg")))
    }

    @Test fun `Linux rpm - dnf, zypper, then rpm`() {
        unixPaths()
        val pkg = File("/home/a/My Files/oshi-desktop-1.6.1.rpm")
        val m = UpdateInstall.Method.LinuxRpm(File("/opt/oshi-desktop/oshi-desktop/bin/OSHI"))
        val cmds = UpdateInstall.linuxCommands(pkg, m, whichOf("pkexec", "dnf", "zypper", "rpm"))
        assertEquals(listOf("/usr/bin/pkexec", "/usr/bin/dnf", "install", "-y", pkg.path), cmds[0])
        assertEquals(listOf("/usr/bin/pkexec", "/usr/bin/zypper", "--non-interactive", "install", "--allow-unsigned-rpm", pkg.path), cmds[1])
        assertEquals(listOf("/usr/bin/pkexec", "/usr/bin/rpm", "-U", pkg.path), cmds[2])
        assertEquals("sudo dnf install '/home/a/My Files/oshi-desktop-1.6.1.rpm'", UpdateInstall.manualCommand(pkg, m, whichOf("dnf")))
        assertEquals("sudo zypper install --allow-unsigned-rpm '/home/a/My Files/oshi-desktop-1.6.1.rpm'", UpdateInstall.manualCommand(pkg, m, whichOf("zypper", "rpm")))
    }

    @Test fun `no pkexec - nothing to run, the person gets the command`() {
        val m = UpdateInstall.Method.LinuxDeb(File("/opt/x/bin/OSHI"))
        assertTrue(UpdateInstall.linuxCommands(File("/tmp/a.deb"), m, whichOf("apt-get", "dpkg")).isEmpty())
        assertTrue(UpdateInstall.pkexecRefused(126))
        assertTrue(UpdateInstall.pkexecRefused(127))
        assertFalse(UpdateInstall.pkexecRefused(100))
    }

    @Test fun `shell quoting of odd paths`() {
        assertEquals("/a/b.deb", UpdateInstall.shellQuote("/a/b.deb"))
        assertEquals("'/a b/it'\\''s.deb'", UpdateInstall.shellQuote("/a b/it's.deb"))
        assertEquals("'/a/\$(rm -rf ~).deb'", UpdateInstall.shellQuote("/a/\$(rm -rf ~).deb"))
    }

    @Test fun `Linux relaunch waits for this PID, passing paths as arguments not code`() {
        unixPaths()
        val cmd = UpdateInstall.linuxRelaunchCommand(77, File("/opt/oshi desktop/bin/OSHI"), whichOf("setsid"))
        assertEquals("/usr/bin/setsid", cmd[0])
        assertEquals("/bin/sh", cmd[1])
        assertEquals(listOf("oshi-relaunch", "77", "/opt/oshi desktop/bin/OSHI"), cmd.takeLast(3))
        assertFalse("the launcher path is never spliced into the script", cmd[3].contains("/opt/"))
        assertEquals("/bin/sh", UpdateInstall.linuxRelaunchCommand(77, File("/x"), noWhich)[0])
    }
}
