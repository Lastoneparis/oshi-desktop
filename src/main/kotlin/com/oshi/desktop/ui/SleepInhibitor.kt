package com.oshi.desktop.ui

/**
 * __CALL_SLEEP_INHIBIT_2026_09_23__ Keep the machine awake while a call is live.
 *
 * On an iPhone the OS does this for us: an active CallKit call and its audio session keep
 * the device from sleeping, which is why iOS never sets `isIdleTimerDisabled` (grep: zero
 * sites). A desktop has no such notion of a call — a laptop on battery sleeps its display
 * after a few minutes and the whole system after a few more, mid-sentence. So the window
 * asks, for exactly as long as a call is connected, with each platform's own tool:
 *
 * - **macOS** `caffeinate -d -i -w <pid>` — display and idle sleep off; `-w` ties it to
 *   this JVM, so a crash cannot leave the Mac awake forever.
 * - **Linux** `systemd-inhibit --what=idle:sleep … sleep infinity` — the logind lock every
 *   desktop environment honours; absent (no systemd) means the call simply is not guarded.
 * - **Windows** a hidden PowerShell holding `SetThreadExecutionState(ES_CONTINUOUS |
 *   ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED)` on its own thread and exiting when this
 *   process does. The JVM cannot call the Win32 API without JNA, which this project does
 *   not ship; the helper process is the dependency-free way.
 *
 * Every failure is swallowed: an unguarded call is still a call.
 */
class SleepInhibitor(
    private val os: String = System.getProperty("os.name").orEmpty(),
    private val pid: Long = ProcessHandle.current().pid(),
    private val launch: (List<String>) -> Process? = { cmd ->
        runCatching { ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }.getOrNull()
    },
) {
    private var process: Process? = null
    private var hooked = false

    val active: Boolean @Synchronized get() = process?.isAlive == true

    @Synchronized
    fun hold() {
        if (process?.isAlive == true) return
        process = command()?.let(launch)
        // macOS (`-w`) and Windows (the pid loop) end with us on their own; Linux's
        // `sleep infinity` would not, so a quit mid-call must take it down.
        if (process != null && !hooked) {
            hooked = true
            runCatching { Runtime.getRuntime().addShutdownHook(Thread({ release() }, "oshi-sleep-release")) }
        }
    }

    @Synchronized
    fun release() {
        val p = process ?: return
        process = null
        runCatching { p.destroy() }
    }

    internal fun command(): List<String>? {
        val o = os.lowercase()
        return when {
            o.contains("mac") -> listOf("/usr/bin/caffeinate", "-d", "-i", "-w", pid.toString())
            o.contains("win") -> listOf(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                "Add-Type -Name P -Namespace W -MemberDefinition '[DllImport(\"kernel32.dll\")] " +
                    "public static extern uint SetThreadExecutionState(uint f);'; " +
                    "[W.P]::SetThreadExecutionState(0x80000003) | Out-Null; " +
                    "while (Get-Process -Id $pid -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 15 }",
            )
            else -> listOf(
                "systemd-inhibit", "--what=idle:sleep", "--who=OSHI", "--why=Call in progress", "--mode=block",
                "sleep", "infinity",
            )
        }
    }
}
