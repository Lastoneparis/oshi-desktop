package com.oshi.desktop.ui

import java.io.File
import java.util.Locale

/**
 * __DESKTOP_BACKGROUND_2026_09_23__ "Open OSHI at login" — PARITY.md row 2.3.
 *
 * There is no push on this client (row 2.3 says why), so the only way a call or a message
 * reaches a desktop user who never opened the window today is a process that was STARTED
 * for them. This writes the per-user, no-admin autostart entry each OS already has:
 *
 *  - macOS: `~/Library/LaunchAgents/com.oshi.desktop.plist` (`RunAtLoad`, no `KeepAlive` —
 *    a Quit from the tray must stay quit until the next login);
 *  - Windows: `HKCU\Software\Microsoft\Windows\CurrentVersion\Run\OSHI`, via `reg.exe`;
 *  - Linux: `~/.config/autostart/oshi-desktop.desktop` (XDG autostart).
 *
 * Every entry launches with [HIDDEN_FLAG], so a login starts OSHI in the tray instead of
 * throwing a window at the person. OFF by default: nothing is written until the toggle is used.
 *
 * The command is the one THIS process was started with ([ProcessHandle.Info]), because the
 * same class runs from a jpackage bundle, an installed .exe and `./oshi.sh run` — guessing an
 * install path would register a launcher that does not exist on half of them.
 */
object LoginItem {
    const val HIDDEN_FLAG = "--hidden"
    const val LABEL = "com.oshi.desktop"
    private const val WIN_RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val WIN_VALUE = "OSHI"

    enum class Os { MAC, WINDOWS, LINUX, OTHER }

    fun os(name: String = System.getProperty("os.name", "")): Os {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.contains("mac") || n.contains("darwin") -> Os.MAC
            n.contains("win") -> Os.WINDOWS
            n.contains("linux") -> Os.LINUX
            else -> Os.OTHER
        }
    }

    private val home: File get() = File(System.getProperty("user.home"))
    internal fun macPlist(homeDir: File = home) = File(homeDir, "Library/LaunchAgents/$LABEL.plist")
    internal fun linuxDesktop(homeDir: File = home) = File(homeDir, ".config/autostart/oshi-desktop.desktop")

    /**
     * The argv that relaunches this client hidden, or null when it cannot be known. A packaged
     * macOS app is relaunched through its `.app` (so Launch Services owns it and the Dock icon
     * is OSHI's), anything else by its exact launcher + arguments.
     */
    fun launchCommand(): List<String>? {
        val info = ProcessHandle.current().info()
        val command = info.command().orElse(null) ?: return null
        val appBundle = Regex("^(.*?\\.app)/Contents/").find(command)?.groupValues?.get(1)
        if (os() == Os.MAC && appBundle != null) {
            return listOf("/usr/bin/open", "-a", appBundle, "--args", HIDDEN_FLAG)
        }
        val args = info.arguments().orElse(null)?.toList()
            ?: return windowsFallback(command)
        return listOf(command) + args.filterNot { it == HIDDEN_FLAG } + HIDDEN_FLAG
    }

    /**
     * Windows never reports a process's arguments — `ProcessHandle.Info.arguments()` is
     * ALWAYS empty there (the JDK does not read another process's PEB) — so the generic
     * path above returned null and "Open at login" failed on every Windows machine with
     * "This system does not say how OSHI was started".
     *
     * The installed app is `…\OSHI\OSHI.exe`, the jpackage launcher, which hosts the JVM
     * in-process: `command` IS the thing to start, and `--hidden` alone is enough (Main
     * treats it as `--ui`). A development run is `java.exe` and is rebuilt from the
     * class path and main class this JVM was started with.
     */
    internal fun windowsFallback(
        command: String,
        classPath: String? = System.getProperty("java.class.path"),
        javaCommand: String? = System.getProperty("sun.java.command"),
    ): List<String>? {
        val exe = command.substringAfterLast('\\').substringAfterLast('/').lowercase(Locale.ROOT)
        if (exe != "java.exe" && exe != "javaw.exe" && exe != "java") return listOf(command, HIDDEN_FLAG)
        val main = javaCommand?.trim()?.split(' ')?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val cp = classPath?.takeIf { it.isNotEmpty() } ?: return null
        // javaw: no console window flashing up at every login.
        val launcher = if (exe == "java.exe") command.dropLast("java.exe".length) + "javaw.exe" else command
        return listOf(launcher, "-cp", cp, main, "--ui", HIDDEN_FLAG)
    }

    fun isEnabled(): Boolean = when (os()) {
        Os.MAC -> macPlist().isFile
        Os.LINUX -> linuxDesktop().isFile
        Os.WINDOWS -> runCatching {
            val p = ProcessBuilder("reg", "query", WIN_RUN_KEY, "/v", WIN_VALUE).redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            p.waitFor() == 0
        }.getOrDefault(false)
        Os.OTHER -> false
    }

    /** Returns null on success, or a sentence saying why it could not be done. */
    fun setEnabled(enabled: Boolean): String? = runCatching {
        if (!enabled) {
            when (os()) {
                Os.MAC -> macPlist().delete()
                Os.LINUX -> linuxDesktop().delete()
                Os.WINDOWS -> run("reg", "delete", WIN_RUN_KEY, "/v", WIN_VALUE, "/f")
                Os.OTHER -> Unit
            }
            return null
        }
        val cmd = launchCommand() ?: return "This system does not say how OSHI was started, so there is nothing to register."
        when (os()) {
            Os.MAC -> macPlist().apply { parentFile.mkdirs() }.writeText(plist(cmd))
            Os.LINUX -> linuxDesktop().apply { parentFile.mkdirs() }.writeText(desktopEntry(cmd))
            Os.WINDOWS -> run("reg", "add", WIN_RUN_KEY, "/v", WIN_VALUE, "/t", "REG_SZ", "/d", windowsCommandLine(cmd), "/f")
            Os.OTHER -> return "Open at login is not available on this operating system."
        }
        null
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    private fun run(vararg argv: String) {
        val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val out = p.inputStream.readAllBytes().decodeToString()
        check(p.waitFor() == 0) { out.trim().ifEmpty { "${argv[0]} failed" } }
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    internal fun plist(cmd: List<String>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
        appendLine("""<plist version="1.0"><dict>""")
        appendLine("  <key>Label</key><string>$LABEL</string>")
        appendLine("  <key>ProgramArguments</key><array>")
        cmd.forEach { appendLine("    <string>${xml(it)}</string>") }
        appendLine("  </array>")
        appendLine("  <key>RunAtLoad</key><true/>")
        appendLine("  <key>ProcessType</key><string>Interactive</string>")
        appendLine("""</dict></plist>""")
    }

    /** XDG `Exec=` quoting: every argument double-quoted, with `"`, `` ` ``, `$` and `\` escaped. */
    internal fun desktopEntry(cmd: List<String>): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        appendLine("Name=OSHI")
        appendLine("Exec=" + cmd.joinToString(" ") { a ->
            "\"" + a.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$") + "\""
        })
        appendLine("X-GNOME-Autostart-enabled=true")
        appendLine("NoDisplay=true")
    }

    internal fun windowsCommandLine(cmd: List<String>): String =
        cmd.joinToString(" ") { a -> if (a.any { it == ' ' || it == '"' }) "\"" + a.replace("\"", "\\\"") + "\"" else a }
}
