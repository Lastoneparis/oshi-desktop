package com.oshi.desktop.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File

/**
 * __STRICT_MODE_2026_11_22__ "Strict security mode" (STRICT_MODE_SPEC.md, shared with Android
 * and iOS): one switch in Settings › Security, OFF by default, local to this computer (the file
 * lives in the profile and no sync path reads it).
 *
 * ## Forcing without overwriting
 * The spec asks that the mode "force the existing settings while active and restore the user's
 * previous values when turned off". Here nothing is overwritten in the first place: every guard
 * reads an EFFECTIVE value — the person's own choice combined with [Values] — at the place it is
 * enforced ([captureProtected], [notificationPreview], [readReceipts], [typing], [privacyRelay],
 * [gifSearch], …). The person's own files (`screen-capture.json`, `notification-preferences.json`,
 * `receipt-preferences.json`, `privacy-relay.json`) are never written by this mode, so turning it
 * off "restores" them by construction, a crash in between cannot leave a half-restored state, and
 * the settings rows can show both "forced by strict mode" and what will come back.
 *
 * ## Turning it off
 * Needs the app-lock passcode when one is set ([disable]); without a passcode there is nothing on
 * this computer to authenticate with, and the explanation page says so.
 */
object StrictMode {
    const val FILE_NAME = "strict-mode.json"

    data class Values(
        val enabled: Boolean = false,
        /** "Block paste into the composer too" — default ON once the mode is on (spec §1). */
        val blockPaste: Boolean = true,
        /** "Turn off read receipts and typing indicators" — optional sub-toggle, default ON. */
        val receiptsOff: Boolean = true,
        /**
         * "Keep screen-reader support": OFF = message text is withheld from the accessibility
         * tree (UI Automation via the Java Access Bridge on Windows, AT-SPI via the ATK wrapper on
         * Linux); ON = it stays readable, for people who need a screen reader.
         */
        val keepScreenReader: Boolean = false,
    )

    var values: Values by mutableStateOf(Values())
        private set

    val enabled: Boolean get() = values.enabled

    @Volatile private var file: File? = null
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Values, Values) -> Unit>()

    fun attach(home: File) {
        val f = File(home, FILE_NAME)
        file = f
        values = read(f)
    }

    /** Renderer / test seam: set without a file and without listeners. */
    fun preview(v: Values) { values = v }

    /** Test seam. */
    internal fun attachForTest(f: File?) { file = f; values = f?.let(::read) ?: Values() }

    fun addChangeListener(l: (old: Values, new: Values) -> Unit) { listeners += l }
    fun removeChangeListener(l: (old: Values, new: Values) -> Unit) { listeners -= l }

    /** Turn the mode on. Never needs authentication: it only ever makes things stricter. */
    fun enable() = update { it.copy(enabled = true) }

    sealed interface DisableResult {
        data object Done : DisableResult
        data object PasscodeRequired : DisableResult
        data object WrongPasscode : DisableResult
    }

    /**
     * Turn the mode off. With an app-lock passcode set, [passcode] must match it
     * ([AppLock.matches]); the attempt is NOT counted as a lock-screen failure (it is not one).
     */
    fun disable(passcode: String?, lock: AppLock.Config = AppLock.config): DisableResult {
        if (!values.enabled) return DisableResult.Done
        if (lock.hasPasscode) {
            if (passcode.isNullOrEmpty()) return DisableResult.PasscodeRequired
            if (!AppLock.matches(lock, passcode)) return DisableResult.WrongPasscode
        }
        update { it.copy(enabled = false) }
        return DisableResult.Done
    }

    /** Sub-toggles. Changing them while ON only ever needs auth when it LOOSENS the mode. */
    fun setBlockPaste(on: Boolean) = update { it.copy(blockPaste = on) }
    fun setReceiptsOff(on: Boolean) = update { it.copy(receiptsOff = on) }
    fun setKeepScreenReader(on: Boolean) = update { it.copy(keepScreenReader = on) }

    private fun update(change: (Values) -> Values) {
        val old = values
        val next = change(old)
        values = next
        file?.let { write(it, next) }
        if (old != next) listeners.forEach { l -> runCatching { l(old, next) } }
    }

    // ------------------------------------------------------------------ effective values (pure)

    /** Copy / Forward / Share of message content. */
    fun copyAllowed(v: Values = values): Boolean = !v.enabled

    /** Paste INTO the composer. */
    fun pasteAllowed(v: Values = values): Boolean = !(v.enabled && v.blockPaste)

    /** Windows capture exclusion: the person's switch, or the mode. */
    fun captureProtected(userChoice: Boolean, v: Values = values): Boolean = userChoice || v.enabled

    /** A notification may name the sender and quote the message. */
    fun notificationPreview(userChoice: Boolean, v: Values = values): Boolean = userChoice && !v.enabled

    fun readReceipts(userChoice: Boolean, v: Values = values): Boolean = userChoice && !(v.enabled && v.receiptsOff)
    fun typing(userChoice: Boolean, v: Values = values): Boolean = userChoice && !(v.enabled && v.receiptsOff)

    /** GIPHY search / trending (a third party sees what is searched and from where). */
    fun gifSearch(v: Values = values): Boolean = !v.enabled

    /** The privacy relay, forced ON while the mode is on — only where a relay is configured. */
    fun privacyRelay(userChoice: Boolean, available: Boolean, v: Values = values): Boolean =
        available && (userChoice || v.enabled)

    /** Lock the window the moment focus leaves OSHI. Needs a passcode to lock with. */
    fun lockOnLeave(hasPasscode: Boolean, v: Values = values): Boolean = v.enabled && hasPasscode

    /** May message text be exposed to the accessibility tree? */
    fun exposeMessageText(v: Values = values): Boolean = !v.enabled || v.keepScreenReader

    /** How long text OSHI put on the clipboard stays there. */
    fun clipboardClearMs(normalMs: Long, v: Values = values): Long = if (v.enabled) STRICT_CLIPBOARD_MS else normalMs

    const val STRICT_CLIPBOARD_MS = 15_000L

    // ------------------------------------------------------------------ file

    fun read(f: File): Values = try {
        if (!f.isFile) Values() else JSONObject(f.readText(Charsets.UTF_8)).let {
            Values(
                enabled = it.optBoolean(KEY_ENABLED, false),
                blockPaste = it.optBoolean(KEY_PASTE, true),
                receiptsOff = it.optBoolean(KEY_RECEIPTS, true),
                keepScreenReader = it.optBoolean(KEY_A11Y, false),
            )
        }
    } catch (_: Exception) {
        // A corrupt file reads as OFF: the mode is opt-in, and an unreadable file must not lock
        // the person out of their own messages' Copy button with no visible switch explaining why.
        Values()
    }

    fun write(f: File, v: Values) {
        runCatching {
            AtomicFile.write(
                f,
                JSONObject()
                    .put(KEY_ENABLED, v.enabled)
                    .put(KEY_PASTE, v.blockPaste)
                    .put(KEY_RECEIPTS, v.receiptsOff)
                    .put(KEY_A11Y, v.keepScreenReader)
                    .toString().toByteArray(Charsets.UTF_8),
            )
        }
    }

    // `strict_` prefix, as the spec asks of every platform's keys. Local only: never synced.
    private const val KEY_ENABLED = "strict_mode_enabled"
    private const val KEY_PASTE = "strict_block_paste"
    private const val KEY_RECEIPTS = "strict_receipts_off"
    private const val KEY_A11Y = "strict_keep_screen_reader"
}
