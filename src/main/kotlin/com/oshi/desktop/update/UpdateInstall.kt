package com.oshi.desktop.update

import com.oshi.desktop.store.ExecutableLookup
import com.oshi.desktop.store.WindowsTools
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * __DESKTOP_AUTO_UPDATE_2026_11_21__ How THIS copy of OSHI was installed, and so which
 * installer can replace it.
 *
 *  * **Windows → always the MSI.** jpackage's `.exe` is a bootstrapper around the very same MSI
 *    (same `--win-upgrade-uuid`, same per-user scope), so an install made from either is a
 *    major upgrade for the MSI, and `msiexec` is the one with documented unattended flags
 *    (`/passive`, `/norestart`, `/l*v`, properties). Detected by finding this launcher's folder
 *    as an `InstallLocation` under HKCU's Uninstall key (jpackage's MSI sets
 *    `ARPINSTALLLOCATION=[INSTALLDIR]`, per-user — `--win-per-user-install`). A copy run from
 *    anywhere else (an unzipped app image, the smoke run's staging) is [Method.Unsupported].
 *  * **Linux → the format that OWNS the launcher**: `dpkg -S` says `oshi-desktop: …` → .deb,
 *    `rpm -qf` names `oshi-desktop` → .rpm. AppImage, a tarball, a source build → unsupported.
 *  * **macOS → never.** The Mac app ships through the Mac App Store.
 *
 * Unsupported is not an error: the prompt still appears and opens the download page.
 */
object UpdateInstall {

    sealed class Method {
        /** [installDir] is the folder holding OSHI.exe; [launcher] is OSHI.exe. */
        data class WindowsMsi(val installDir: File, val launcher: File) : Method()
        data class LinuxDeb(val launcher: File) : Method()
        data class LinuxRpm(val launcher: File) : Method()
        data class Unsupported(val why: String) : Method()

        val kind: InstallerKind?
            get() = when (this) {
                is WindowsMsi -> InstallerKind.MSI
                is LinuxDeb -> InstallerKind.DEB
                is LinuxRpm -> InstallerKind.RPM
                is Unsupported -> null
            }
    }

    data class Run(val exit: Int, val out: String)

    /** Runs a short command with no shell; null when it could not start or took > 20 s. */
    fun runShort(cmd: List<String>): Run? = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else Run(p.exitValue(), out)
    } catch (_: Exception) {
        null
    }

    const val PACKAGE = "oshi-desktop"
    const val HKCU_UNINSTALL = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall"

    fun detect(
        os: String = System.getProperty("os.name").orEmpty().lowercase(),
        appPath: String? = System.getProperty("jpackage.app-path"),
        env: (String) -> String? = System::getenv,
        which: (String) -> String? = { ExecutableLookup.which(it) },
        run: (List<String>) -> Run? = ::runShort,
    ): Method {
        val windows = os.contains("win")
        val mac = os.contains("mac") || os.contains("darwin")
        if (mac) return Method.Unsupported("macOS updates come from the Mac App Store")
        if (appPath.isNullOrBlank()) return Method.Unsupported("not a packaged install (no jpackage.app-path)")
        val launcher = File(appPath)
        if (windows) {
            // By string, not File.parentFile: the same rule must hold when tests run on Unix.
            val dirPath = appPath.replace('/', '\\').substringBeforeLast('\\', "")
            if (dirPath.isEmpty()) return Method.Unsupported("launcher has no folder")
            val dir = File(dirPath)
            val r = run(listOf(WindowsTools.REG, "query", HKCU_UNINSTALL, "/s", "/v", "InstallLocation"))
                ?: return Method.Unsupported("reg query failed")
            return if (installLocations(r.out).any { sameWindowsDir(it, dir.path) }) Method.WindowsMsi(dir, launcher)
            else Method.Unsupported("${dir.path} is not an installed OSHI (no Uninstall entry)")
        }
        if (!env("APPIMAGE").isNullOrBlank()) return Method.Unsupported("AppImage")
        which("dpkg")?.let { dpkg ->
            run(listOf(dpkg, "-S", launcher.path))?.let { r -> if (r.exit == 0 && dpkgOwner(r.out) == PACKAGE) return Method.LinuxDeb(launcher) }
        }
        which("rpm")?.let { rpm ->
            run(listOf(rpm, "-qf", "--queryformat", "%{NAME}\\n", launcher.path))?.let { r ->
                if (r.exit == 0 && r.out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } == PACKAGE) return Method.LinuxRpm(launcher)
            }
        }
        return Method.Unsupported("${launcher.path} is not owned by a $PACKAGE .deb or .rpm")
    }

    /** `InstallLocation    REG_SZ    C:\…\OSHI\` lines of `reg query … /s /v InstallLocation`. */
    fun installLocations(regOutput: String): List<String> =
        regOutput.lineSequence().mapNotNull { line ->
            Regex("^\\s+InstallLocation\\s+REG_(?:EXPAND_)?SZ\\s+(.+?)\\s*$").find(line)?.groupValues?.get(1)
        }.toList()

    fun sameWindowsDir(a: String, b: String): Boolean {
        fun norm(s: String) = s.trim().trim('"').replace('/', '\\').trimEnd('\\').lowercase()
        return norm(a).isNotEmpty() && norm(a) == norm(b)
    }

    /** `oshi-desktop: /opt/oshi-desktop/oshi-desktop/bin/OSHI` → `oshi-desktop` (arch qualifier dropped). */
    fun dpkgOwner(out: String): String? {
        // `<pkg>[:arch][, <pkg2>…]: /absolute/path` — the ": /" also rules out error lines.
        val line = out.lineSequence().firstOrNull { it.contains(": /") } ?: return null
        return line.substringBefore(": /").split(',').first().trim().substringBefore(':')
    }

    // ------------------------------------------------------------------ Windows

    /**
     * The PowerShell that runs AFTER this process has exited: wait for [pid] (60 s at most — the
     * MSI's own `OshiCloseRunningApp` ends a straggler anyway, see platform/windows/packaging/
     * main.wxs), install with a progress bar and no questions, then start OSHI again whatever
     * the result (on failure the MSI rolled back, so the old version starts), record the exit
     * code, and delete the installer.
     *
     * `INSTALLDIR` is passed so an install the user put in a custom folder is upgraded where it
     * is. No trailing backslash: msiexec would read `\"` as an escaped quote.
     */
    fun windowsScript(pid: Long, msi: File, method: Method.WindowsMsi, msiexec: String, log: File, result: File): String {
        fun q(s: String) = "'" + s.replace("'", "''") + "'"
        val dir = method.installDir.path.trimEnd('\\', '/')
        val args = "/i \"${msi.path}\" /passive /norestart /l*v \"${log.path}\" INSTALLDIR=\"$dir\""
        return listOf(
            "\$ErrorActionPreference = 'Continue'",
            "Wait-Process -Id $pid -Timeout 60 -ErrorAction SilentlyContinue",
            "\$code = -1",
            "try { \$p = Start-Process -FilePath ${q(msiexec)} -ArgumentList ${q(args)} -Wait -PassThru; \$code = \$p.ExitCode } catch { \$code = -2 }",
            "Set-Content -LiteralPath ${q(result.path)} -Value \$code -ErrorAction SilentlyContinue",
            "Remove-Item -LiteralPath ${q(msi.path)} -Force -ErrorAction SilentlyContinue",
            "if (Test-Path -LiteralPath ${q(method.launcher.path)}) { Start-Process -FilePath ${q(method.launcher.path)} }",
        ).joinToString("\r\n")
    }

    /** `powershell … -EncodedCommand <UTF-16LE base64>`: no quoting of paths through CreateProcess at all. */
    fun windowsCommand(script: String, powershell: String = WindowsTools.POWERSHELL): List<String> = listOf(
        powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
        "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)),
    )

    // ------------------------------------------------------------------ Linux

    /** The privileged install, tried in order. Each is `pkexec <absolute tool> …`. */
    fun linuxCommands(pkg: File, method: Method, which: (String) -> String?): List<List<String>> {
        val pkexec = which("pkexec") ?: return emptyList()
        val path = pkg.path
        val out = ArrayList<List<String>>()
        when (method) {
            is Method.LinuxDeb -> {
                // __APT_LOCK_WAIT_2026_11_21__ unattended-upgrades holds the dpkg lock for minutes
                // on a stock Ubuntu; without a wait apt-get failed at once, then dpkg -i failed on
                // the same lock — two password prompts for nothing. Wait for it instead.
                which("apt-get")?.let { out += listOf(pkexec, it, "-o", "DPkg::Lock::Timeout=300", "install", "-y", path) }
                which("dpkg")?.let { out += listOf(pkexec, it, "-i", path) }
            }
            is Method.LinuxRpm -> {
                which("dnf")?.let { out += listOf(pkexec, it, "install", "-y", path) }
                which("zypper")?.let { out += listOf(pkexec, it, "--non-interactive", "install", "--allow-unsigned-rpm", path) }
                which("rpm")?.let { out += listOf(pkexec, it, "-U", path) }
            }
            else -> {}
        }
        // Tried in order; a REFUSAL (pkexecRefused) stops there — the person said no, and a
        // second password dialog for the fallback would be asking again.
        return out
    }

    /** What the person can paste into a terminal when the in-app install could not run. */
    fun manualCommand(pkg: File, method: Method, which: (String) -> String? = { null }): String {
        val p = shellQuote(pkg.path)
        return when (method) {
            is Method.LinuxDeb -> if (which("apt-get") != null || which("dpkg") == null) "sudo apt-get install $p" else "sudo dpkg -i $p"
            is Method.LinuxRpm -> when {
                which("dnf") != null -> "sudo dnf install $p"
                which("zypper") != null -> "sudo zypper install --allow-unsigned-rpm $p"
                which("rpm") != null -> "sudo rpm -U $p"
                else -> "sudo dnf install $p"
            }
            else -> ""
        }
    }

    fun shellQuote(s: String): String =
        if (s.matches(Regex("[A-Za-z0-9_./+:@-]+"))) s else "'" + s.replace("'", "'\\''") + "'"

    /** pkexec: 126 = the person closed the password dialog, 127 = not authorised / no agent. */
    fun pkexecRefused(exit: Int) = exit == 126 || exit == 127

    /**
     * Start the updated OSHI once this process ([pid]) is gone: a second launch while this one
     * runs would only bring THIS window forward (InstanceActivation) and then exit.
     */
    fun linuxRelaunchCommand(pid: Long, launcher: File, which: (String) -> String?): List<String> {
        val sh = listOf("/bin/sh", "-c",
            "i=0; while kill -0 \"\$1\" 2>/dev/null && [ \$i -lt 120 ]; do sleep 0.5; i=\$((i+1)); done; exec \"\$2\"",
            "oshi-relaunch", pid.toString(), launcher.path)
        return which("setsid")?.let { listOf(it) + sh } ?: sh
    }
}
