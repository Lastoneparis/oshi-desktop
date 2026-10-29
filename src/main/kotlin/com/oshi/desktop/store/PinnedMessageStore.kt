package com.oshi.desktop.store

import java.io.File
import org.json.JSONObject

/**
 * __DESKTOP_DIRECT_PIN_2026_10_29__ The pinned message of a 1:1 conversation — the one
 * piece of `🔧ACTION🔧` this client decoded and then threw away.
 *
 * ## What was actually wrong
 *
 * [com.oshi.desktop.msg.ControlPayloadRouter] has parsed inbound `pinMessage` and
 * `unpinMessage` since it was written, and returned `PIN_NOT_APPLIED` for both — a counted
 * outcome rather than a silent drop, which is the only reason this was findable at all. Its
 * stated reason was *"there is nowhere to put it yet"*, which was true, plus a second clause
 * that was not:
 *
 *     "given that Android does not send 1:1 pins at all today"
 *
 * **Measured 2026-10-29: Android sends them and receives them.**
 * `MessageRepository.togglePinMessage` (`:7399`) writes the local map and then calls
 * `sendActionPayload(..., "pinMessage")`, and `MessageRepository.init` registers
 * `messageActionsManager.addPinCallback` (`:1627`) to apply an inbound one. Both halves were
 * added as RP-3 of the 2026-08-24 react-reply audit, whose own comment records that they had
 * ZERO callers before that — so the desktop's claim was accurate when it was written and
 * went stale without anyone noticing. iOS has had both halves all along
 * (`MessageManager.pinMessageAndSync` / `.pinMessage(in:)`, and `ChatView.swift:5675-5677`
 * calls the SYNCING variants, not the local ones).
 *
 * That is the third time in this repository that a doc line announcing a gap outlived the
 * gap. It is recorded here, in the file that closes it, because the lesson is cheaper than
 * the re-measurement: **a comment about another platform's behaviour is a measurement with a
 * date on it, not a fact.**
 *
 * ## A pin is SYNCED — which is what makes this file different from `WallpaperStore`
 *
 * Its shape is deliberately copied from `WallpaperStore` (a small JSON map beside the
 * message logs, forward-tolerant, never throwing) and its MEANING is the opposite: a
 * wallpaper is local by design and never touches the wire, while every write here either
 * came from a peer's `🔧ACTION🔧` or is about to be sent as one. The file is a cache of a
 * shared fact, not a private preference.
 *
 * The phones' rules, followed rather than re-decided:
 *
 *  - **One pin per conversation.** Both platforms key a flat map by conversation and
 *    overwrite; pinning a second message silently replaces the first. No list, no stack.
 *  - **No ownership check.** `applyAction`'s C-MSG-4 byte-equality rule guards edit and
 *    delete-for-everyone because those REWRITE someone's words. A pin writes nothing: either
 *    party may pin either party's message, exactly as both phones' receivers do (Android's
 *    callback applies the pin with no sender comparison at all). Inheriting the ownership
 *    rule here would have looked careful and silently diverged from both phones.
 *  - **Case-insensitive ids.** An iPhone's `targetMessageId` is rendered from
 *    `UUID.uuidString`, i.e. UPPERCASE, while this client's own ids are not. Android's
 *    `pinnedMessage` flow and `isPinned` both compare `ignoreCase = true` and say why; a
 *    case-sensitive match here would drop every pin that arrived from an iPhone and look
 *    like a transport fault.
 *
 * ## It never throws
 *
 * A missing, unreadable, corrupt or future-written file reads as "nothing is pinned". A lost
 * pin is a banner that does not appear; an exception out of the receive path would cost the
 * message that carried it.
 */
class PinnedMessageStore(private val file: File) {

    private val lock = Any()
    private var loaded = false
    private val map = HashMap<String, String>()

    /** The pinned message id for this conversation, or null. Never normalised on the way out. */
    fun get(conversationId: String): String? = synchronized(lock) {
        ensureLoaded()
        map[conversationId]
    }

    /** True when [messageId] is this conversation's pin. Case-insensitive — see the class note. */
    fun isPinned(conversationId: String, messageId: String): Boolean = synchronized(lock) {
        ensureLoaded()
        map[conversationId]?.equals(messageId, ignoreCase = true) == true
    }

    /**
     * Pin [messageId], or unpin when it is null.
     *
     * @return true when the stored state actually changed, so a caller can tell a real pin
     *   from a repeat and report `NO_CHANGE` instead of claiming a write.
     */
    fun set(conversationId: String, messageId: String?): Boolean {
        synchronized(lock) {
            ensureLoaded()
            val before = map[conversationId]
            if (messageId == null) {
                if (before == null) return false
                map.remove(conversationId)
            } else {
                if (before != null && before.equals(messageId, ignoreCase = true)) return false
                map[conversationId] = messageId
            }
            persist()
            return true
        }
    }

    /** Everything on record. For tests. */
    fun all(): Map<String, String> = synchronized(lock) {
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
            val id = runCatching { obj.getString(key) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            map[key] = id
        }
    }

    /** Temp file and rename, with a direct write as the fallback. `WallpaperStore`'s writer. */
    private fun persist() {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
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
        /** Beside `messages/` and `ui-wallpapers.json` in the client's home. */
        const val FILE_NAME = "pinned-messages.json"
    }
}
