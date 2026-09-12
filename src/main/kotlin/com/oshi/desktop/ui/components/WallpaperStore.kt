package com.oshi.desktop.ui.components

import java.io.File
import org.json.JSONObject

/**
 * The per-conversation chat wallpaper — a LOCAL preference, and only that.
 *
 * ============================================================ READ THIS BEFORE THE CODE
 *
 * `DesktopLimits.MISSING` currently lists "Chat wallpapers, bubble styles, link previews"
 * against PARITY.md row 1.4, and its stated reason is exact and worth keeping in front of
 * you: *"A bubble style is a per-conversation setting the phones store locally and never
 * sync, so one set here would be invisible on the phone and vice versa."*
 *
 * That reason is a description of the SHIPPED behaviour, not an argument that the setting
 * cannot exist. `ChatWallpaperManager.swift` keeps `[conversationId: ChatWallpaper]` in
 * `UserDefaults` under `"chatWallpapers"`; `BubbleStyleManager.swift` keeps its accent and
 * shape under `"oshi.bubble.*"`. Neither ever reaches the wire. This file is the same thing
 * on the same terms — a JSON map beside the message logs, never sent, never received.
 *
 * **The window therefore must not imply cross-device appearance.** The picker says the
 * preference is local to this machine, in those words, on screen. `🎨WALLPAPER_UPDATE🎨`
 * exists in this repository as a CODEC and nothing acts on it; nothing here produces it,
 * consumes it, or hints that a peer will see anything.
 *
 * **And the ledger is now out of step with the window.** `DesktopLimits.MISSING` says
 * wallpapers are not available in this client; after this file they are, locally. That row
 * needs amending by whoever owns it. Recording the contradiction here because a stale ledger
 * that nobody flagged is exactly how "verify ≠ believe" fails.
 *
 * ============================================================ THE PALETTE IS THE THEME'S
 *
 * The phone ships 10 solid presets, 6 gradients, 6 animated styles and 16 pattern sets. This
 * offers five options, every one of them built from a token already in `OshiTheme` — see
 * `WallpaperPainter`. A sixteen-option picker whose colours came from nowhere would have
 * introduced a second palette into a window whose whole point is that it draws with the
 * shipped app's.
 *
 * ============================================================ IT NEVER THROWS
 *
 * A preferences file is not load-bearing. A missing file, an unreadable one, a corrupt one,
 * a value from a future build — every one of them reads as [WallpaperId.NONE] and the window
 * draws its default background. Failing to open a chat because a cosmetic file lost a brace
 * would be a far worse bug than a lost wallpaper.
 */
enum class WallpaperId(val id: String, val label: String) {
    /** No wallpaper: the ordinary window background. */
    NONE("none", "None"),

    /** `OshiTheme.surface` — the phone's "paper" preset, in this theme's token. */
    PAPER("paper", "Paper"),

    /** `OshiTheme.brandGradient`, heavily washed out. The house colour. */
    VIOLET("violet", "Violet wash"),

    /** `OshiTheme.brandSecondary`, washed out. */
    BLUSH("blush", "Blush"),

    /** `OshiTheme.textPrimary` at a low alpha — a neutral warm-free grey. */
    INK("ink", "Ink");

    companion object {
        /**
         * An unknown id is [NONE], never a crash and never a silent nearest-match.
         *
         * This is the forward-compatibility seam: a preferences file written by a build that
         * has more wallpapers than this one must open, not fail. It is also why the enum is
         * keyed by a STRING and not by ordinal — `BubbleStyleManager.swift` persists `Int`
         * raw values and carries a comment warning that new cases must go at the end, which
         * is a constraint this file simply does not have to inherit.
         */
        fun fromId(raw: String?): WallpaperId = entries.firstOrNull { it.id == raw } ?: NONE
    }
}

/**
 * `{"<conversationId>": "<wallpaperId>"}` in one small JSON file beside the message logs.
 *
 * Conversation ids are standard base64 public keys and contain `+` and `/`; they are used as
 * JSON object keys verbatim, which JSON permits, rather than being hashed or escaped — a
 * transformation here would make the file unreadable next to `MessageStore`'s own naming.
 */
class WallpaperStore(private val file: File) {

    private val lock = Any()
    private var loaded = false
    private val map = HashMap<String, WallpaperId>()

    /** The wallpaper chosen for this conversation. [WallpaperId.NONE] if none, ever. */
    fun get(conversationId: String): WallpaperId = synchronized(lock) {
        ensureLoaded()
        map[conversationId] ?: WallpaperId.NONE
    }

    /**
     * Choose a wallpaper. Selecting [WallpaperId.NONE] REMOVES the entry rather than storing
     * it, so the file only ever holds conversations the user actually decorated.
     */
    fun set(conversationId: String, wallpaper: WallpaperId) {
        synchronized(lock) {
            ensureLoaded()
            if (wallpaper == WallpaperId.NONE) map.remove(conversationId) else map[conversationId] = wallpaper
            persist()
        }
    }

    /** Everything on record. Used by tests and by nothing in the window. */
    fun all(): Map<String, WallpaperId> = synchronized(lock) {
        ensureLoaded()
        HashMap(map)
    }

    // ------------------------------------------------------------------ disk

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull()
            ?: return
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        for (key in obj.keys()) {
            val chosen = WallpaperId.fromId(runCatching { obj.getString(key) }.getOrNull())
            if (chosen != WallpaperId.NONE) map[key] = chosen
        }
    }

    /**
     * Write through a sibling temp file and rename.
     *
     * A half-written preferences file is the one way this could break opening a chat — the
     * reader tolerates corruption, but only because the writer tries not to produce any. The
     * rename is best-effort: on a filesystem where it fails the fallback is a direct write,
     * because losing a wallpaper is preferable to throwing out of a click handler.
     */
    private fun persist() {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v.id) }
        val payload = obj.toString()
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            tmp.setReadable(false, false); tmp.setReadable(true, true)
            tmp.setWritable(false, false); tmp.setWritable(true, true)
            if (!tmp.renameTo(file)) {
                file.writeText(payload, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    companion object {
        /** Sits beside `messages/`, `contacts.json` and the rest of the client's home. */
        const val FILE_NAME = "ui-wallpapers.json"
    }
}
