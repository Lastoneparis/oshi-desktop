package com.oshi.desktop.store

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * __SECOND_LAUNCH_DID_NOTHING_2026_11_19__ "I double-click OSHI and nothing happens."
 *
 * On Windows and Linux, closing the window HIDES it to the notification area so calls and
 * messages keep arriving. Windows 11 folds that icon behind the "^" overflow, so the person
 * does the obvious thing — the Start menu or the desktop shortcut — and the second process
 * finds the profile locked ([HomeLock]), writes one line to a stderr that a GUI launch
 * discards, and exits 0. Nothing on screen, every time, until a reboot.
 *
 * The first instance now listens on a loopback port, written with a random token to
 * [FILE_NAME] inside the (private) profile directory. A second launch on the same profile
 * connects, presents the token, and the first instance shows and raises its window. The
 * token means another local account that can reach 127.0.0.1 cannot pop our window; the
 * protocol carries nothing else.
 */
object InstanceActivation {
    const val FILE_NAME = ".instance.port"
    private const val OK = "ok"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var handler: ((String?) -> Unit)? = null
    private val pending = ArrayList<String?>()

    /** __OSHI_LINKS_ON_PC_2026_11_20__ only an `oshi:` link travels; anything else is just "show". */
    fun acceptableLink(raw: String?): String? = raw?.trim()?.takeIf {
        it.length in 6..4096 && it.startsWith("oshi:", ignoreCase = true) && it.none { c -> c == '\n' || c == '\r' || c == '\t' }
    }

    /**
     * Who to call when another launch asks for the window. Requests that arrived before a
     * window existed (the first-run screen, the vault opening) are replayed on registration.
     */
    @Synchronized
    fun onActivate(h: ((String?) -> Unit)?) {
        handler = h
        if (h != null && pending.isNotEmpty()) {
            val queued = pending.toList(); pending.clear()
            queued.forEach { runCatching { h(it) } }
        }
    }

    /**
     * Show the window and, with an `oshi:` [link], open it. Queued until a window registers —
     * which is also how a link that STARTED the app reaches the window once it exists.
     */
    @Synchronized
    fun request(link: String? = null) {
        val l = acceptableLink(link)
        val h = handler
        if (h == null) { if (pending.size < 16) pending += l } else runCatching { h(l) }
    }

    /** Start listening for this profile. Call once, AFTER [HomeLock] is held. Never throws. */
    fun listen(home: File): Boolean = runCatching {
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val ss = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        server = ss
        AtomicFile.write(File(home, FILE_NAME), "${ss.localPort} $token".toByteArray(Charsets.US_ASCII))
        Thread({
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching {
                    s.use { sock ->
                        sock.soTimeout = 2_000
                        val line = sock.getInputStream().bufferedReader(Charsets.UTF_8).readLine()?.trim()
                        val parts = line?.split('\t', limit = 2)
                        if (parts != null && parts[0] == token) {
                            sock.getOutputStream().write("$OK\n".toByteArray(Charsets.US_ASCII))
                            sock.getOutputStream().flush()
                            request(parts.getOrNull(1))
                        }
                    }
                }
            }
        }, "oshi-instance-activation").apply { isDaemon = true; start() }
        true
    }.getOrDefault(false)

    /**
     * Ask the instance already running on [home] to show its window.
     * True only when that instance acknowledged; false means the caller must say something itself.
     */
    fun signal(home: File, timeoutMs: Int = 2_000, link: String? = null): Boolean = runCatching {
        val parts = File(home, FILE_NAME).readText(Charsets.US_ASCII).trim().split(' ')
        if (parts.size != 2) return false
        val port = parts[0].toInt()
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeoutMs)
            s.soTimeout = timeoutMs
            val payload = parts[1] + (acceptableLink(link)?.let { "\t$it" } ?: "")
            s.getOutputStream().write("$payload\n".toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
            s.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()?.trim() == OK
        }
    }.getOrDefault(false)

    /** Tests only. */
    internal fun stopForTest() {
        runCatching { server?.close() }
        server = null; handler = null; synchronized(this) { pending.clear() }
    }
}
