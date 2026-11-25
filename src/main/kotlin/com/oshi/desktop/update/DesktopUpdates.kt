package com.oshi.desktop.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oshi.desktop.net.DesktopCover
import com.oshi.desktop.net.DesktopOhttp
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.ExecutableLookup
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * __DESKTOP_AUTO_UPDATE_2026_11_21__ "A new version is available — Update now / Later", on
 * Windows and Linux. macOS: nothing at all (the Mac app updates through the Mac App Store).
 *
 * Timeline: one check [FIRST_CHECK_DELAY_MS] after start, then every [CHECK_INTERVAL_MS] while
 * the process runs (it usually runs for weeks, hidden in the tray). A failed check is silent.
 * "Later" snoozes THAT version for 3 days ([UpdatePreferences]); "Check for updates" in
 * Settings → About ignores the snooze.
 *
 * PRIVACY RELAY / DISGUISE. Neither can carry this request: the OHTTP gateway forwards
 * `/v2/` only (ServerVPS/ohttp-gateway/gateway.js) and the cover transport wraps v2 calls
 * only, while the manifest is a static file under `/downloads/`. A background request that
 * went DIRECT anyway would undo, every 12 hours, the very thing those switches promise. So
 * while either is on, AUTOMATIC checks are paused and Settings says so; a check the person
 * starts by hand still goes direct, because they asked for it there and then.
 */
class UpdateController(
    val current: Version?,
    private val isMac: Boolean,
    private val channel: UpdateChannel,
    private val prefs: UpdatePreferences,
    private val updatesDir: File,
    private val check: (UpdateChannel, Version) -> CheckResult,
    private val privacyPaused: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Ends the process when the window's own quit did not (tests replace it). */
    private val hardExit: () -> Unit = { Runtime.getRuntime().exit(0) },
) {
    enum class Phase {
        IDLE, CHECKING, UP_TO_DATE, CHECK_FAILED, AVAILABLE,
        DOWNLOADING, INSTALLING, RESTARTING,
        /** The download did not match the signed manifest; it was deleted. */
        VERIFY_FAILED,
        DOWNLOAD_FAILED,
        /** Downloaded and verified, but OSHI could not run the installer: the person finishes it. */
        MANUAL,
        /** This copy cannot update itself (not installed from an OSHI installer): website. */
        UNSUPPORTED,
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val manifest: UpdateManifest? = null,
        val checkedAtMs: Long? = null,
        val downloaded: Long = 0,
        val total: Long = 0,
        val manualCommand: String? = null,
        val manualFolder: File? = null,
        /** The dialog is open. */
        val prompt: Boolean = false,
        /** Set at start when the previous in-app install did not finish (Windows exit code). */
        val lastInstallFailed: Boolean = false,
    )

    var state by mutableStateOf(State())
        private set

    /** Windows/Linux with a version this build can compare. */
    val supported: Boolean get() = !isMac && current != null
    val isTestChannel: Boolean get() = channel.isTest
    fun automaticChecksPaused(): Boolean = privacyPaused()

    /** The window's quit; null = no window, `System.exit`. */
    @Volatile var onQuit: (() -> Unit)? = null
    /** Opens a URL / a folder. Replaced by the window (and by tests). */
    @Volatile var openUrl: (String) -> Unit = { url -> com.oshi.desktop.ui.openInBrowser(url) }
    @Volatile var openFolder: (File) -> Unit = { dir -> openExternally { java.awt.Desktop.getDesktop().open(dir) } }

    private val busy = AtomicBoolean(false)
    private val cancel = AtomicBoolean(false)
    private var executor: ScheduledExecutorService? = null

    // ------------------------------------------------------------------ checking

    /** Start the schedule. Idempotent; does nothing on macOS or without a comparable version. */
    @Synchronized fun start(firstDelayMs: Long = FIRST_CHECK_DELAY_MS) {
        if (!supported || executor != null) return
        state = state.copy(lastInstallFailed = readLastInstallFailed())
        executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "oshi-update").apply { isDaemon = true } }.also { ex ->
            ex.scheduleWithFixedDelay({ runCatching { automaticCheck() } }, firstDelayMs, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() { synchronized(this) { executor?.shutdownNow(); executor = null } }

    private fun automaticCheck() {
        if (privacyPaused()) return
        runCheck(manual = false)
    }

    /** Settings → "Check for updates". Off the calling thread. */
    fun checkNow() {
        if (!supported) return
        background { runCheck(manual = true) }
    }

    /** Synchronous; the scheduler thread and [checkNow] call it. */
    fun runCheck(manual: Boolean) {
        val cur = current ?: return
        if (!busy.compareAndSet(false, true)) return
        try {
            val s0 = state
            // Never step on a download / install in progress, nor reopen a closed dialog under
            // the person's cursor while they are mid-way through one.
            if (s0.phase in ACTIVE) return
            if (manual) state = s0.copy(phase = Phase.CHECKING)
            val result = check(channel, cur)
            val now = clock()
            state = when (result) {
                is CheckResult.Available -> {
                    val show = manual || !prefs.isSnoozed(result.manifest.version, now)
                    state.copy(phase = Phase.AVAILABLE, manifest = result.manifest, checkedAtMs = now,
                        prompt = state.prompt || show)
                }
                is CheckResult.UpToDate -> state.copy(phase = Phase.UP_TO_DATE, manifest = null, checkedAtMs = now, prompt = false)
                // Silent: an automatic failure leaves whatever was known; a manual one says "could not check".
                is CheckResult.Failed -> if (manual) state.copy(phase = Phase.CHECK_FAILED) else state
            }
        } finally {
            busy.set(false)
        }
    }

    // ------------------------------------------------------------------ the dialog

    /** "Later": not this version again for 3 days. */
    fun later() {
        state.manifest?.let { prefs.snooze(it.version, clock()) }
        state = state.copy(prompt = false, phase = if (state.manifest != null) Phase.AVAILABLE else state.phase)
    }

    /** Close the dialog from a terminal state (failed, manual, unsupported). */
    fun dismiss() {
        val s = state
        if (s.phase in ACTIVE) return
        state = s.copy(prompt = false, phase = if (s.manifest != null) Phase.AVAILABLE else Phase.IDLE)
    }

    fun openPrompt() { if (state.manifest != null && state.phase !in ACTIVE) state = state.copy(prompt = true) }

    fun cancelDownload() { cancel.set(true) }

    fun openDownloadPage() { openUrl(state.manifest?.page ?: DOWNLOAD_PAGE) }

    /** "Update now". */
    fun updateNow(
        detect: () -> UpdateInstall.Method = { UpdateInstall.detect() },
        download: (UpdateFile, File, (Long, Long) -> Unit, () -> Boolean) -> File = { f, dest, p, c ->
            UpdateDownloader.download(UpdateCheck.httpClient(), f, dest, p, c)
        },
        installer: Installer = SystemInstaller,
    ) {
        val m = state.manifest ?: return
        if (state.phase in ACTIVE) return
        cancel.set(false)
        state = state.copy(phase = Phase.DOWNLOADING, downloaded = 0, total = 0, prompt = true)
        background {
            val method = runCatching(detect).getOrElse { UpdateInstall.Method.Unsupported(it.toString()) }
            val kind = method.kind
            val file = kind?.let { m.files[it] }
            if (kind == null || file == null) {
                state = state.copy(phase = Phase.UNSUPPORTED)
                return@background
            }
            state = state.copy(total = file.size)
            runCatching { DesktopPaths.ensurePrivateDir(updatesDir) }
            val dest = File(updatesDir, "oshi-desktop-${m.version}.${kind.extension}")
            val pkg = try {
                download(file, dest, { done, total -> state = state.copy(downloaded = done, total = total) }, { cancel.get() })
            } catch (e: UpdateDownloader.Cancelled) {
                state = state.copy(phase = Phase.AVAILABLE, downloaded = 0)
                return@background
            } catch (e: UpdateDownloader.VerificationFailed) {
                dest.delete()
                state = state.copy(phase = Phase.VERIFY_FAILED)
                return@background
            } catch (e: Exception) {
                dest.delete()
                state = state.copy(phase = Phase.DOWNLOAD_FAILED)
                return@background
            }
            install(method, pkg, m.version, installer)
        }
    }

    private fun install(method: UpdateInstall.Method, pkg: File, version: Version, installer: Installer) {
        when (method) {
            is UpdateInstall.Method.WindowsMsi -> {
                val ok = installer.startWindows(method, pkg, File(updatesDir, "msi-$version.log"), File(updatesDir, LAST_RESULT))
                if (ok) restart() else manual(pkg, command = null)
            }
            is UpdateInstall.Method.LinuxDeb, is UpdateInstall.Method.LinuxRpm -> {
                state = state.copy(phase = Phase.INSTALLING)
                when (installer.installLinux(method, pkg, File(updatesDir, "install-$version.log"))) {
                    LinuxOutcome.INSTALLED -> {
                        runCatching { pkg.delete() }
                        installer.relaunchLinux(method)
                        restart()
                    }
                    LinuxOutcome.REFUSED, LinuxOutcome.FAILED, LinuxOutcome.NO_PKEXEC ->
                        manual(pkg, command = installer.manualCommand(pkg, method))
                }
            }
            is UpdateInstall.Method.Unsupported -> state = state.copy(phase = Phase.UNSUPPORTED)
        }
    }

    private fun manual(pkg: File, command: String?) {
        state = state.copy(phase = Phase.MANUAL, manualCommand = command, manualFolder = pkg.parentFile)
        pkg.parentFile?.let { runCatching { openFolder(it) } }
    }

    /** Leave the dialog on "restarting" for a moment, then quit for real (not to the tray). */
    private fun restart() {
        state = state.copy(phase = Phase.RESTARTING)
        Thread.sleep(1_500)
        val q = onQuit
        // Belt and braces: if something keeps the JVM alive after the window is gone, the
        // installer (Windows) or the relaunch (Linux) is waiting on this PID.
        Thread({ Thread.sleep(20_000); hardExit() }, "oshi-update-exit").apply { isDaemon = true }.start()
        if (q != null) javax.swing.SwingUtilities.invokeLater { q() } else hardExit()
    }

    private fun readLastInstallFailed(): Boolean {
        val f = File(updatesDir, LAST_RESULT)
        if (!f.isFile) return false
        val code = runCatching { f.readText().trim().toInt() }.getOrNull()
        runCatching { f.delete() }
        return code != null && code != 0 && code != 3010
    }

    private fun background(block: () -> Unit) {
        Thread({ runCatching(block) }, "oshi-update-action").apply { isDaemon = true }.start()
    }

    enum class LinuxOutcome { INSTALLED, REFUSED, FAILED, NO_PKEXEC }

    /** The side effects, separable so the flow is testable without starting installers. */
    interface Installer {
        /** Start the detached PowerShell; true when it started. */
        fun startWindows(method: UpdateInstall.Method.WindowsMsi, msi: File, log: File, result: File): Boolean
        fun installLinux(method: UpdateInstall.Method, pkg: File, log: File): LinuxOutcome
        fun relaunchLinux(method: UpdateInstall.Method)
        fun manualCommand(pkg: File, method: UpdateInstall.Method): String
    }

    object SystemInstaller : Installer {
        private val which: (String) -> String? = { ExecutableLookup.which(it) }

        override fun startWindows(method: UpdateInstall.Method.WindowsMsi, msi: File, log: File, result: File): Boolean = try {
            val script = UpdateInstall.windowsScript(
                ProcessHandle.current().pid(), msi, method, com.oshi.desktop.store.WindowsTools.system32("msiexec.exe"), log, result,
            )
            ProcessBuilder(UpdateInstall.windowsCommand(script))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        } catch (_: Exception) {
            false
        }

        override fun installLinux(method: UpdateInstall.Method, pkg: File, log: File): LinuxOutcome {
            val cmds = UpdateInstall.linuxCommands(pkg, method, which)
            if (cmds.isEmpty()) return LinuxOutcome.NO_PKEXEC
            for (cmd in cmds) {
                val exit = try {
                    val p = ProcessBuilder(cmd).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
                    p.outputStream.close()
                    if (!p.waitFor(20, TimeUnit.MINUTES)) { p.destroyForcibly(); -1 } else p.exitValue()
                } catch (_: Exception) {
                    -1
                }
                if (exit == 0) return LinuxOutcome.INSTALLED
                if (UpdateInstall.pkexecRefused(exit)) return LinuxOutcome.REFUSED
            }
            return LinuxOutcome.FAILED
        }

        override fun relaunchLinux(method: UpdateInstall.Method) {
            val launcher = when (method) {
                is UpdateInstall.Method.LinuxDeb -> method.launcher
                is UpdateInstall.Method.LinuxRpm -> method.launcher
                else -> return
            }
            runCatching {
                ProcessBuilder(UpdateInstall.linuxRelaunchCommand(ProcessHandle.current().pid(), launcher, which))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }
        }

        override fun manualCommand(pkg: File, method: UpdateInstall.Method): String = UpdateInstall.manualCommand(pkg, method, which)
    }

    companion object {
        const val FIRST_CHECK_DELAY_MS = 30_000L
        const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
        const val DOWNLOAD_PAGE = "https://oshi-messenger.com/desktop"
        const val LAST_RESULT = "last-install-result.txt"
        val ACTIVE = setOf(Phase.DOWNLOADING, Phase.INSTALLING, Phase.RESTARTING)

        private fun openExternally(block: () -> Unit) {
            Thread({ runCatching { if (java.awt.Desktop.isDesktopSupported()) block() } }, "oshi-update-open").apply { isDaemon = true }.start()
        }

        /** The running version: jpackage's, else the jar's (`Implementation-Version`). */
        fun runningVersion(): Version? = Version.parse(
            System.getProperty("jpackage.app-version") ?: UpdateController::class.java.`package`?.implementationVersion,
        )

        /**
         * Where installers are downloaded. Windows: the user's temp folder (local, not the
         * roaming profile `%APPDATA%\OSHI`). Elsewhere, or with `OSHI_HOME`: `<profile>/updates`,
         * owner-only — the package is handed to a root process afterwards.
         */
        fun defaultUpdatesDir(): File =
            if (DesktopPaths.isWindows && System.getenv("OSHI_HOME").isNullOrBlank()) {
                File(System.getProperty("java.io.tmpdir"), "OSHI-update")
            } else {
                File(DesktopPaths.dataDir, "updates")
            }
    }
}

/** The app's one instance. Inert until [UpdateController.start]. */
object DesktopUpdates {
    val controller: UpdateController by lazy {
        val channel = UpdateChannel.fromEnv()
        UpdateController(
            current = UpdateController.runningVersion(),
            isMac = DesktopPaths.isMac,
            channel = channel,
            prefs = UpdatePreferences(DesktopPaths.file(UpdatePreferences.FILE_NAME)),
            updatesDir = UpdateController.defaultUpdatesDir().absoluteFile,
            check = { ch, cur -> UpdateCheck.check(UpdateCheck.httpClient(), ch, cur) },
            privacyPaused = {
                runCatching { (DesktopOhttp.isEnabled() && DesktopOhttp.isAvailable()) || DesktopCover.isEnabled() }.getOrDefault(true)
            },
        )
    }

    /** Smoke runs only: a shorter first delay. It cannot change where updates come from. */
    fun firstDelayMs(): Long =
        System.getenv("OSHI_UPDATE_FIRST_CHECK_SECONDS")?.toLongOrNull()?.takeIf { it in 1..3600 }?.times(1000)
            ?: UpdateController.FIRST_CHECK_DELAY_MS
}
