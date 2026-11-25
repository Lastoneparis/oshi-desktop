package com.oshi.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import com.oshi.desktop.store.AppLock
import com.oshi.desktop.store.StrictMode
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.WindowEvent

/**
 * __STRICT_MODE_2026_11_22__ The desktop guards of strict mode that live in the UI layer
 * (STRICT_MODE_SPEC §1–§4). The pure rules are in [StrictMode]; this file applies them.
 *
 *  - [install]: lock the window the moment focus leaves OSHI (when a passcode exists), empty the
 *    clipboard of what OSHI put there on that lock and when the mode is turned on.
 *  - [ComposerClipboardScope] + [strictClipboardKeys]: in the composer, copy / cut OUT is
 *    dropped, and paste IN is dropped when "Block paste too" is on — through the Compose
 *    clipboard the text field and its right-click menu both use, and through the keys.
 *  - [strictText]: message text withheld from the accessibility tree unless "Keep screen-reader
 *    support" is on. Compose Desktop exposes semantics through the Java Accessibility API, which
 *    reaches Windows UI Automation / MSAA via the Java Access Bridge and Linux AT-SPI via the ATK
 *    wrapper: an automation or AI agent reading the window through those sees only the label.
 *    It does NOT stop an agent reading PIXELS (OCR of a screenshot) — on Windows the capture
 *    exclusion covers that; on Linux nothing can.
 */
object StrictModeGuards {

    @Volatile private var installed = false

    /** Is focus going to a window of ANOTHER application (null opposite = not ours)? */
    fun leavesApp(e: WindowEvent): Boolean = e.id == WindowEvent.WINDOW_DEACTIVATED && e.oppositeWindow == null

    fun install() {
        if (installed) return
        installed = true
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
                if (e is WindowEvent && leavesApp(e)) onLeftApp()
            }, AWTEvent.WINDOW_EVENT_MASK)
        }
        StrictMode.addChangeListener { old, new ->
            if (new.enabled && !old.enabled) SensitiveClipboard.clearNow()
        }
        AppLock.onLocked = { if (StrictMode.enabled) SensitiveClipboard.clearNow() }
    }

    internal fun onLeftApp() {
        if (fileDialogOpen()) return
        if (StrictMode.lockOnLeave(AppLock.config.hasPasscode)) AppLock.lockNow()
    }

    /**
     * A native file chooser (every Open/Save in this app is an AWT FileDialog) can take focus
     * away from OSHI's windows with no Java opposite window, like another app would: leaving for
     * it must not lock the window under the person's hands.
     */
    private fun fileDialogOpen(): Boolean =
        runCatching { java.awt.Window.getWindows().any { it is java.awt.FileDialog && it.isVisible } }.getOrDefault(false)

    /** Copy / cut shortcuts (Ctrl/Cmd+C, Ctrl/Cmd+X, Ctrl+Insert, Shift+Delete). */
    fun isCopyOrCut(e: KeyEvent): Boolean {
        val mod = e.isCtrlPressed || e.isMetaPressed
        return (mod && (e.key == Key.C || e.key == Key.X)) ||
            (e.isCtrlPressed && e.key == Key.Insert) || (e.isShiftPressed && e.key == Key.Delete)
    }

    /** Paste shortcuts (Ctrl/Cmd+V, Shift+Insert). */
    fun isPaste(e: KeyEvent): Boolean =
        ((e.isCtrlPressed || e.isMetaPressed) && e.key == Key.V) || (e.isShiftPressed && e.key == Key.Insert)

    /** Pure decision for a key in the composer: true = swallow it. */
    fun swallow(isCopyOrCut: Boolean, isPaste: Boolean, v: StrictMode.Values = StrictMode.values): Boolean =
        (isCopyOrCut && !StrictMode.copyAllowed(v)) || (isPaste && !StrictMode.pasteAllowed(v))
}

/** Composer keys: swallow copy/cut while strict, paste while strict + "block paste". */
fun Modifier.strictClipboardKeys(): Modifier = onPreviewKeyEvent { e ->
    e.type == KeyEventType.KeyDown && StrictModeGuards.swallow(StrictModeGuards.isCopyOrCut(e), StrictModeGuards.isPaste(e))
}

/**
 * Message text in the accessibility tree: the text itself normally; only [label] (or nothing)
 * in strict mode without "Keep screen-reader support".
 */
fun Modifier.strictText(label: String? = null): Modifier =
    if (StrictMode.exposeMessageText()) this
    else clearAndSetSemantics { if (label != null) contentDescription = label }

/**
 * The composer's clipboard. Outside strict mode it IS the platform clipboard. In strict mode
 * nothing goes OUT (copy / cut / the right-click menu's Copy do nothing), and nothing comes IN
 * when "Block paste too" is on.
 */
@Composable
fun ComposerClipboardScope(content: @Composable () -> Unit) {
    val v = StrictMode.values
    if (!v.enabled) { content(); return }
    val clip = LocalClipboard.current
    val manager = LocalClipboardManager.current
    val guardedClip = GuardedClipboard(clip, allowPaste = StrictMode.pasteAllowed(v))
    val guardedManager = GuardedClipboardManager(manager, allowPaste = StrictMode.pasteAllowed(v))
    CompositionLocalProvider(LocalClipboard provides guardedClip, LocalClipboardManager provides guardedManager) { content() }
}

internal class GuardedClipboard(private val delegate: Clipboard, private val allowPaste: Boolean) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = if (allowPaste) delegate.getClipEntry() else null
    override suspend fun setClipEntry(clipEntry: ClipEntry?) { /* strict: nothing leaves through the composer */ }
    override val nativeClipboard get() = delegate.nativeClipboard
}

internal class GuardedClipboardManager(private val delegate: ClipboardManager, private val allowPaste: Boolean) : ClipboardManager {
    override fun setText(annotatedString: AnnotatedString) { /* strict: nothing leaves */ }
    override fun getText(): AnnotatedString? = if (allowPaste) delegate.getText() else null
    override fun hasText(): Boolean = allowPaste && delegate.hasText()
    override fun getClip(): ClipEntry? = if (allowPaste) delegate.getClip() else null
    override fun setClip(clipEntry: ClipEntry?) { /* strict: nothing leaves */ }
}
