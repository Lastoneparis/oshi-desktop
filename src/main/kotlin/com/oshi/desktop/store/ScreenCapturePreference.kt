package com.oshi.desktop.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File

/**
 * __SCREEN_CAPTURE_PROTECTION_2026_11_20__ "Screenshot protection", as on Android
 * (`ScreenshotProtectionPolicy`, default ON, only an explicit choice turns it off) and iOS
 * (always on). The window side is [com.oshi.desktop.ui.WindowCaptureGuard].
 *
 * Where it holds:
 *  - **Windows 10 2004+**: every OSHI window gets `WDA_EXCLUDEFROMCAPTURE` — screenshots,
 *    Snipping Tool, screen recorders and remote-viewing tools using the Windows capture APIs
 *    see nothing (or black), and Windows Recall does not snapshot it. Taskbar / Alt-Tab live
 *    thumbnails are forced to the icon. Older Windows: `WDA_MONITOR` (black instead of hidden).
 *  - **Linux / X11**: impossible for an app. Any X client can read any window's pixels; there
 *    is no flag to ask for. **Wayland** already refuses capture by other apps unless the person
 *    approves it through the desktop portal — protection there is the compositor's, not ours.
 *  - **macOS**: the shipped Mac app is the native one; this JVM build does not claim it.
 *  - Nowhere: a camera pointed at the screen, or malware with admin rights / a kernel driver.
 */
object ScreenCapturePreference {
    const val FILE_NAME = "screen-capture.json"

    /** True where [com.oshi.desktop.ui.WindowCaptureGuard] can actually do something. */
    val supported: Boolean get() = DesktopPaths.isWindows

    /** Android's rule: never touched = ON; an explicit choice is respected either way. */
    fun resolve(stored: Boolean?): Boolean = stored ?: true

    var enabled: Boolean by mutableStateOf(true)
        private set

    @Volatile private var file: File? = null
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun attach(home: File) {
        val f = File(home, FILE_NAME)
        file = f
        enabled = resolve(read(f))
    }

    fun onChange(l: (Boolean) -> Unit) { listeners.add(l) }

    fun set(on: Boolean) {
        enabled = on
        file?.let { f -> runCatching { AtomicFile.write(f, JSONObject().put("protect", on).toString().toByteArray(Charsets.UTF_8)) } }
        listeners.forEach { runCatching { it(on) } }
    }

    internal fun read(f: File): Boolean? = runCatching {
        if (!f.isFile) null else JSONObject(f.readText(Charsets.UTF_8)).let { if (it.has("protect")) it.getBoolean("protect") else null }
    }.getOrNull()
}
