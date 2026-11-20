package com.oshi.desktop.ui

import com.oshi.desktop.store.DesktopPaths
import java.util.concurrent.TimeUnit

/**
 * __OSHI_LINKS_ON_PC_2026_11_20__ Make `oshi://` links open THIS app on Windows.
 *
 * jpackage has no URL-protocol option, so the installed app claims the scheme itself, per user
 * (HKCU\Software\Classes\oshi — no admin rights, no UAC), pointing at the launcher it is running
 * from (`jpackage.app-path`). It runs off the UI thread, and only rewrites the keys when the
 * command differs (first run, or after the install moved). A development run (java.exe) never
 * registers. Linux claims the scheme in the .deb's .desktop file (MimeType=x-scheme-handler/oshi)
 * instead; macOS in Info.plist.
 *
 * The keys outlive an uninstall (the MSI does not know them); they then point at a missing exe
 * and Windows says so — the same as any app that registered a protocol at runtime.
 */
object ProtocolRegistration {
    const val KEY = "HKCU\\Software\\Classes\\oshi"

    fun ensureAsync() {
        if (!DesktopPaths.isWindows) return
        val exe = System.getProperty("jpackage.app-path")?.takeIf { it.endsWith(".exe", ignoreCase = true) } ?: return
        Thread({ runCatching { ensure(exe) } }, "oshi-protocol-registration").apply { isDaemon = true; start() }
    }

    internal fun commandValue(exe: String): String = "\"$exe\" \"%1\""

    /** The (Default) REG_SZ of `reg query <key> /ve`, or null. */
    internal fun parseDefault(regQueryOutput: String?): String? =
        regQueryOutput?.lineSequence()
            ?.firstNotNullOfOrNull { Regex("""^\s*\(\S+\)\s+REG_SZ\s+(.*)$""").find(it)?.groupValues?.get(1)?.trim() }

    /** PowerShell that writes the keys; every value comes from the environment (no argv quoting). */
    internal val SCRIPT: String =
        "\$k='HKCU:\\Software\\Classes\\oshi'; " +
            "New-Item -Path \$k -Force | Out-Null; " +
            "Set-ItemProperty -LiteralPath \$k -Name '(default)' -Value 'URL:OSHI'; " +
            "New-ItemProperty -LiteralPath \$k -Name 'URL Protocol' -Value '' -PropertyType String -Force | Out-Null; " +
            "New-Item -Path \"\$k\\DefaultIcon\" -Force | Out-Null; " +
            "Set-ItemProperty -LiteralPath \"\$k\\DefaultIcon\" -Name '(default)' -Value \$env:OSHI_ICON; " +
            "New-Item -Path \"\$k\\shell\\open\\command\" -Force | Out-Null; " +
            "Set-ItemProperty -LiteralPath \"\$k\\shell\\open\\command\" -Name '(default)' -Value \$env:OSHI_OPEN_CMD"

    private fun ensure(exe: String) {
        val want = commandValue(exe)
        val current = run(listOf("reg", "query", "$KEY\\shell\\open\\command", "/ve"), emptyMap())
        if (parseDefault(current) == want) return
        run(
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", SCRIPT),
            mapOf("OSHI_OPEN_CMD" to want, "OSHI_ICON" to "\"$exe\",0"),
        )
    }

    private fun run(cmd: List<String>, env: Map<String, String>): String? = runCatching {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        p.outputStream.close()
        val out = p.inputStream.readAllBytes().decodeToString()
        if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        if (p.exitValue() == 0) out else null
    }.getOrNull()
}
