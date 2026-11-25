package com.oshi.desktop.security

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.StrictMode
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * __STRICT_MODE_2026_11_22__ Strict mode's "someone else may be watching or driving this
 * computer" check (STRICT_MODE_SPEC §3, desktop): which remote-control, screen-sharing /
 * recording, automation and AI computer-use programs are RUNNING right now.
 *
 * It WARNS, it does not block: an app cannot stop another program the person (or an
 * administrator) started, and killing processes is not ours to do. The banner names them so the
 * person can decide. Matching is by process image / command name, so:
 *  - a renamed binary, a tool we do not list, or one running as another user whose process names
 *    we cannot read (Linux `hidepid`, Windows protected processes) is NOT seen;
 *  - a listed program that is merely idle (TeamViewer's service waiting for a call) IS seen —
 *    the banner says "running", not "connected".
 *
 * The list ([CATALOG]) is the maintained part. Adding a program = one [Known] line + a test row
 * in `RemoteControlDetectorTest`.
 */
object RemoteControlDetector {

    enum class Category { REMOTE_CONTROL, SCREEN_CAPTURE, AUTOMATION, AI_AGENT }

    /**
     * One program. [names] are lower-case image / command names without `.exe`; [argsAny], when
     * non-empty, additionally requires one of these words in the command line (ffmpeg is only a
     * screen recorder when it is grabbing the screen).
     */
    data class Known(val id: String, val label: String, val category: Category, val names: Set<String>, val argsAny: Set<String> = emptySet())

    /** A running process as the scanners see it. [comm] = image/command name; [argv] may be empty. */
    data class Proc(val comm: String, val argv: List<String> = emptyList())

    data class Hit(val id: String, val label: String, val category: Category)

    private fun k(id: String, label: String, c: Category, vararg names: String, args: Set<String> = emptySet()) =
        Known(id, label, c, names.map { it.lowercase(Locale.ROOT) }.toSet(), args)

    val CATALOG: List<Known> = listOf(
        // --- remote control ---------------------------------------------------------------
        k("teamviewer", "TeamViewer", Category.REMOTE_CONTROL, "teamviewer", "teamviewer_service", "teamviewerd", "tv_w32", "tv_x64"),
        k("anydesk", "AnyDesk", Category.REMOTE_CONTROL, "anydesk"),
        k("rustdesk", "RustDesk", Category.REMOTE_CONTROL, "rustdesk"),
        k("hoptodesk", "HopToDesk", Category.REMOTE_CONTROL, "hoptodesk"),
        k("chrome-remote-desktop", "Chrome Remote Desktop", Category.REMOTE_CONTROL,
            "remoting_host", "remoting_desktop", "remoting_me2me_host", "chrome-remote-desktop", "chrome-remote-desktop-host"),
        k("vnc", "VNC server", Category.REMOTE_CONTROL,
            "x11vnc", "xvnc", "vncserver", "vncserver-x11", "vncserver-x11-core", "vncserverui", "vncagent",
            "x0vncserver", "x0tigervncserver", "xtigervnc", "tigervncserver", "xtightvnc", "tightvncserver", "tvnserver",
            "winvnc", "uvnc_service", "wayvnc", "krfb", "vino-server"),
        k("rdp", "Windows Remote Desktop session", Category.REMOTE_CONTROL, "rdpclip"),
        k("xrdp", "xrdp", Category.REMOTE_CONTROL, "xrdp", "xrdp-sesman"),
        k("gnome-remote-desktop", "GNOME Remote Desktop", Category.REMOTE_CONTROL, "gnome-remote-desktop-daemon"),
        k("quickassist", "Quick Assist", Category.REMOTE_CONTROL, "quickassist"),
        k("parsec", "Parsec", Category.REMOTE_CONTROL, "parsecd", "pservice"),
        k("splashtop", "Splashtop", Category.REMOTE_CONTROL, "srserver", "srservice", "srmanager", "srstreamer"),
        k("screenconnect", "ScreenConnect", Category.REMOTE_CONTROL,
            "screenconnect.clientservice", "screenconnect.windowsclient", "connectwisecontrol.client"),
        k("logmein", "LogMeIn / GoTo", Category.REMOTE_CONTROL, "logmein", "lmiguardiansvc", "gotoassist", "g2comm"),
        k("nomachine", "NoMachine", Category.REMOTE_CONTROL, "nxserver", "nxnode", "nxservice", "nxservice64"),
        k("ultraviewer", "UltraViewer", Category.REMOTE_CONTROL, "ultraviewer_service", "ultraviewer_desktop"),
        k("supremo", "Supremo", Category.REMOTE_CONTROL, "supremo", "supremoservice"),
        k("remotepc", "RemotePC", Category.REMOTE_CONTROL, "remotepcservice", "remotepcdesktop"),
        k("remote-utilities", "Remote Utilities", Category.REMOTE_CONTROL, "rutserv", "rfusclient"),
        k("dwservice", "DWService", Category.REMOTE_CONTROL, "dwagent", "dwagsvc"),
        k("ammyy", "Ammyy Admin", Category.REMOTE_CONTROL, "aa_v3", "ammyy_admin"),
        k("meshcentral", "MeshCentral agent", Category.REMOTE_CONTROL, "meshagent"),
        // --- screen sharing / recording ---------------------------------------------------
        k("zoom-share", "Zoom screen sharing", Category.SCREEN_CAPTURE, "cpthost"),
        k("obs", "OBS Studio", Category.SCREEN_CAPTURE, "obs64", "obs32", "obs"),
        k("ffmpeg-grab", "ffmpeg (screen capture)", Category.SCREEN_CAPTURE, "ffmpeg",
            args = setOf("x11grab", "gdigrab", "ddagrab", "kmsgrab", "pipewiregrab")),
        k("linux-recorders", "Screen recorder", Category.SCREEN_CAPTURE,
            "simplescreenrecorder", "kazam", "peek", "vokoscreen", "vokoscreenng", "recordmydesktop",
            "wf-recorder", "gpu-screen-recorder", "kooha", "kazam"),
        // --- automation that can drive other apps ------------------------------------------
        k("autohotkey", "AutoHotkey", Category.AUTOMATION, "autohotkey", "autohotkey64", "autohotkeyu64", "autohotkeyu32", "autohotkeyux"),
        k("autoit", "AutoIt", Category.AUTOMATION, "autoit3", "autoit3_x64"),
        k("power-automate", "Power Automate Desktop", Category.AUTOMATION, "pad.robot", "pad.console.host", "pad.desktop"),
        k("ydotool", "ydotool (input injection)", Category.AUTOMATION, "ydotoold"),
        // --- AI agents that can see and/or drive the screen ----------------------------------
        k("claude", "Claude (desktop app / Claude Code)", Category.AI_AGENT, "claude"),
        k("chatgpt", "ChatGPT / Codex", Category.AI_AGENT, "chatgpt", "codex"),
        k("copilot", "Microsoft Copilot", Category.AI_AGENT, "copilot", "microsoft.copilot", "m365copilot"),
        k("recall", "Windows Recall", Category.AI_AGENT, "aixhost"),
        k("open-interpreter", "Open Interpreter", Category.AI_AGENT, "interpreter", "open-interpreter"),
        k("self-operating-computer", "Self-Operating Computer", Category.AI_AGENT, "operate", "self-operating-computer"),
        k("ui-tars", "UI-TARS", Category.AI_AGENT, "ui-tars", "ui tars", "ui-tars-desktop"),
        k("agent-s", "Agent S", Category.AI_AGENT, "agent_s", "agent-s"),
        k("openadapt", "OpenAdapt", Category.AI_AGENT, "openadapt"),
        k("goose", "Goose", Category.AI_AGENT, "goose"),
        k("cua", "Cua computer-use agent", Category.AI_AGENT, "cua-agent", "cua-computer-server", "computer-server"),
    )

    /** Interpreters whose SCRIPT name is what identifies the program (`python -m interpreter`, `node …/goose`). */
    private val INTERPRETERS = setOf("python", "python3", "pythonw", "py", "node", "bun", "deno", "java", "javaw", "sh", "bash", "uv", "uvx", "pipx")

    /** Linux truncates `/proc/<pid>/comm` to 15 characters. */
    private const val COMM_MAX = 15

    fun normalize(name: String): String {
        var n = name.trim().trim('"').replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
        if (n.endsWith(".exe")) n = n.removeSuffix(".exe")
        if (n.endsWith(".app")) n = n.removeSuffix(".app")
        return n
    }

    /** The names a process can be recognised by: its command, argv[0], and a script after an interpreter / `-m`. */
    internal fun namesOf(p: Proc): Set<String> {
        val out = linkedSetOf<String>()
        normalize(p.comm).takeIf { it.isNotEmpty() }?.let(out::add)
        val argv = p.argv.filter { it.isNotBlank() }
        argv.firstOrNull()?.let { normalize(it).takeIf(String::isNotEmpty)?.let(out::add) }
        val first = argv.firstOrNull()?.let(::normalize)?.replace(Regex("[0-9.]+$"), "")
        if (first != null && first in INTERPRETERS) {
            val rest = argv.drop(1)
            val m = rest.indexOf("-m")
            val script = if (m >= 0) rest.getOrNull(m + 1) else rest.firstOrNull { !it.startsWith("-") }
            script?.let { normalize(it).removeSuffix(".py").removeSuffix(".js").takeIf(String::isNotEmpty)?.let(out::add) }
        }
        return out
    }

    /** Pure: the listed programs among [procs], each once, in catalog order. */
    fun match(procs: List<Proc>, catalog: List<Known> = CATALOG): List<Hit> {
        val hits = linkedSetOf<String>()
        for (p in procs) {
            val names = namesOf(p)
            val argsLower by lazy { p.argv.joinToString(" ").lowercase(Locale.ROOT) }
            for (kn in catalog) {
                if (kn.id in hits) continue
                val named = names.any { n ->
                    n in kn.names || (n.length == COMM_MAX && kn.names.any { it.length > COMM_MAX && it.startsWith(n) })
                }
                if (!named) continue
                if (kn.argsAny.isNotEmpty() && kn.argsAny.none { argsLower.contains(it) }) continue
                hits += kn.id
            }
        }
        return catalog.filter { it.id in hits }.map { Hit(it.id, it.label, it.category) }
    }

    /** Pure: `tasklist /fo csv /nh` → image names. */
    fun parseTasklistCsv(text: String): List<Proc> = text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("\"") }
        .mapNotNull { line -> line.substring(1).substringBefore("\"").takeIf { it.isNotBlank() } }
        .map { Proc(it) }
        .toList()

    /** Linux: every readable `/proc/<pid>/comm` (+ `cmdline`, NUL-separated). [root] is a seam for tests. */
    fun scanProc(root: File = File("/proc")): List<Proc> =
        (root.listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) } ?: emptyArray()).mapNotNull { dir ->
            val comm = runCatching { File(dir, "comm").readText().trim() }.getOrNull() ?: return@mapNotNull null
            val argv = runCatching {
                File(dir, "cmdline").readBytes().toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
            }.getOrDefault(emptyList())
            Proc(comm, argv)
        }

    /** The JVM's own view (all OSes; on Windows it often lacks other users' processes, hence tasklist too). */
    private fun scanProcessHandles(): List<Proc> = runCatching {
        ProcessHandle.allProcesses().map { h ->
            val info = h.info()
            val cmd = info.command().orElse("")
            val args = info.arguments().map { it.toList() }.orElse(emptyList())
            Proc(cmd, if (cmd.isNotEmpty()) listOf(cmd) + args else args)
        }.toList()
    }.getOrDefault(emptyList())

    private fun scanTasklist(): List<Proc> = runCatching {
        val p = ProcessBuilder("tasklist", "/fo", "csv", "/nh").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        parseTasklistCsv(out)
    }.getOrDefault(emptyList())

    /** Windows: this very session is a Remote Desktop one (`SESSIONNAME=RDP-Tcp#3`). */
    fun isRdpSession(env: Map<String, String> = System.getenv()): Boolean =
        env["SESSIONNAME"]?.uppercase(Locale.ROOT)?.startsWith("RDP-") == true

    /** One full scan for this OS. Blocking (tasklist); call off the UI thread. */
    fun scan(): List<Hit> {
        val procs = when {
            DesktopPaths.isWindows -> scanTasklist() + scanProcessHandles()
            DesktopPaths.isLinux -> scanProc()
            else -> scanProcessHandles()
        }
        val hits = match(procs).toMutableList()
        if (DesktopPaths.isWindows && isRdpSession() && hits.none { it.id == "rdp" }) {
            CATALOG.first { it.id == "rdp" }.let { hits.add(0, Hit(it.id, it.label, it.category)) }
        }
        return hits
    }
}

/**
 * The banner's state: scans every [PERIOD_MS] while strict mode is on, nothing while it is off.
 * A dismissal holds until the SET of programs changes (a new one appears → the banner returns).
 */
object RemoteControlWatch {
    const val PERIOD_MS = 20_000L

    var hits: List<RemoteControlDetector.Hit> by mutableStateOf(emptyList())
        private set
    var dismissed: Set<String> by mutableStateOf(emptySet())
        private set

    /** What the banner shows: hits, unless exactly this set was dismissed. */
    val visible: List<RemoteControlDetector.Hit>
        get() = if (StrictMode.enabled && hits.isNotEmpty() && hits.map { it.id }.toSet() != dismissed) hits else emptyList()

    fun dismiss() { dismissed = hits.map { it.id }.toSet() }

    /** Renderer / test seam. */
    fun preview(h: List<RemoteControlDetector.Hit>) { hits = h; dismissed = emptySet() }

    @Volatile private var started = false
    @Volatile private var kick = true

    /** Scan at the next second rather than at the end of the period (the mode was just turned on). */
    fun rescanSoon() { kick = true }

    fun start(scan: () -> List<RemoteControlDetector.Hit> = RemoteControlDetector::scan) {
        if (started) return
        started = true
        StrictMode.addChangeListener { old, new -> if (new.enabled && !old.enabled) rescanSoon() }
        Thread({
            var waited = 0L
            while (true) {
                if (StrictMode.enabled) {
                    if (kick || waited >= PERIOD_MS) {
                        kick = false
                        waited = 0
                        hits = runCatching(scan).getOrDefault(emptyList())
                    }
                } else if (hits.isNotEmpty()) {
                    hits = emptyList()
                }
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { return@Thread }
                waited += 1_000
            }
        }, "oshi-strict-remote-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}
