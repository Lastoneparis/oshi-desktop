package com.oshi.desktop.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.ReactionPreferences
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * __O_BUBBLE_MASCOT_2026_11_16__ The two things that decide whether a mascot moves:
 *
 *  - [systemReducedMotion] — the operating system's own "reduce motion" switch. This client
 *    had no notion of it before; Compose Desktop exposes none, so it is read once, off the UI
 *    thread, from the place each OS keeps it:
 *      macOS   `defaults read com.apple.universalaccess reduceMotion` = 1
 *      GNOME   `gsettings get org.gnome.desktop.interface enable-animations` = false
 *      Windows SystemParametersInfo(SPI_GETCLIENTAREAANIMATION) = FALSE ("Animation effects"
 *              off), via PowerShell; MinAnimate = 0 only as a fallback
 *    `-Doshi.reduceMotion=true|false` or `OSHI_REDUCE_MOTION=1|0` overrides the probe. A
 *    probe that fails or times out means "not reduced": the default every OS ships.
 *  - [animatedReactions] — the in-app "Animated OSHI reactions" switch (default ON),
 *    persisted by [ReactionPreferences].
 *
 * Mascots animate only when the first is false and the second is true ([mascotsAnimate]).
 */
object MotionPreference {

    @Volatile var systemReducedMotion: Boolean = overrideValue() ?: false
        private set

    @Volatile private var probed = false

    /** Start the OS probe on a daemon thread. Idempotent and never blocks the caller. */
    fun startProbe() {
        if (probed) return
        probed = true
        if (overrideValue() != null) return
        Thread({ systemReducedMotion = runCatching { probeOs() }.getOrNull() ?: false }, "oshi-motion-probe")
            .apply { isDaemon = true }.start()
    }

    /** Where the switch is stored; tests and renderers point it elsewhere before first use. */
    @Volatile var prefsFile: File? = null

    private val prefs: ReactionPreferences? by lazy {
        runCatching { ReactionPreferences(prefsFile ?: File(DesktopPaths.dataDir, ReactionPreferences.FILE_NAME)) }.getOrNull()
    }

    /** Snapshot state, so flipping the switch recomposes every chip at once. */
    val animatedReactions: MutableState<Boolean> by lazy {
        startProbe()
        mutableStateOf(prefs?.load()?.animatedMascots ?: true)
    }

    fun setAnimatedReactions(on: Boolean) {
        runCatching { prefs?.save(ReactionPreferences.Values(animatedMascots = on)) }
        animatedReactions.value = runCatching { prefs?.load()?.animatedMascots }.getOrNull() ?: on
    }

    /** Read inside composition: recomposes when the in-app switch changes. */
    val mascotsAnimate: Boolean get() = animatedReactions.value && !systemReducedMotion

    private fun overrideValue(): Boolean? {
        System.getProperty("oshi.reduceMotion")?.let { return it.equals("true", true) || it == "1" }
        System.getenv("OSHI_REDUCE_MOTION")?.let { return it == "1" || it.equals("true", true) }
        return null
    }

    private fun probeOs(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") -> run("defaults", "read", "com.apple.universalaccess", "reduceMotion")?.trim() == "1"
            // __WINDOWS_REDUCED_MOTION_2026_11_20__ Settings › Accessibility › Visual effects ›
            // "Animation effects" is SPI_GETCLIENTAREAANIMATION, which no registry value states
            // directly. MinAnimate (only the minimise/maximise animation) stays as the fallback
            // when PowerShell is unavailable.
            os.contains("win") -> windowsClientAreaAnimation(
                run(4000, com.oshi.desktop.store.WindowsTools.POWERSHELL, "-NoProfile", "-NonInteractive", "-Command", WINDOWS_SPI_SCRIPT),
            )?.let { !it } ?: (run(com.oshi.desktop.store.WindowsTools.REG, "query", "HKCU\\Control Panel\\Desktop\\WindowMetrics", "/v", "MinAnimate")
                ?.let { Regex("MinAnimate\\s+REG_SZ\\s+(\\S+)").find(it)?.groupValues?.get(1) == "0" } ?: false)
            else -> run("gsettings", "get", "org.gnome.desktop.interface", "enable-animations")?.trim() == "false"
        }
    }

    internal const val WINDOWS_SPI_SCRIPT =
        "Add-Type -Name S -Namespace W -MemberDefinition '[DllImport(\"user32.dll\")] public static extern bool " +
            "SystemParametersInfo(uint a, uint b, ref bool c, uint d);'; \$v = \$true; " +
            "[void][W.S]::SystemParametersInfo(0x1042, 0, [ref]\$v, 0); \$v"

    /** "True"/"False" from [WINDOWS_SPI_SCRIPT]; null when the probe said neither. */
    internal fun windowsClientAreaAnimation(output: String?): Boolean? =
        when (output?.trim()?.lines()?.lastOrNull()?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    private fun run(vararg cmd: String): String? = run(1500, *cmd)

    private fun run(timeoutMs: Long, vararg cmd: String): String? {
        val p = runCatching { ProcessBuilder(*cmd).redirectErrorStream(true).start() }.getOrNull() ?: return null
        return try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); null }
            else if (p.exitValue() != 0) null
            else p.inputStream.bufferedReader().readText()
        } catch (_: Exception) { null }
    }
}
