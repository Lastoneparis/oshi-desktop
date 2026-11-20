package com.oshi.desktop.store

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * __CONVERSATION_PIN_MUTE_2027__ Pinned and muted conversations, for THIS machine only.
 *
 * Both phones keep these locally and never sync them: Android's `ConversationPinOrder`
 * (pinned ids in DataStore) and the per-chat mute of both apps. Same terms here as
 * [ChatListPreferences]: a small JSON file in the profile home, never sent anywhere, and a
 * read that never throws — an unreadable file is "nothing pinned, nothing muted", which is
 * the state a fresh profile starts in, never a crash at launch.
 *
 * Ids are stored exactly as the conversation list names them (a peer address, a group
 * UUID). A group's own mute lives in its definition ([com.oshi.desktop.app.GroupStore]); this
 * file holds the 1:1 mutes, so one switch per conversation, never two that could disagree.
 *
 * Pinned order is the order of pinning (oldest pin first), like Android: the newest pin goes
 * to the bottom of the pinned block, so pinning one more chat never reshuffles the others.
 */
class ConversationPreferences(private val file: File) {

    private val lock = Any()
    private var loaded = false
    private val pinned = LinkedHashSet<String>()
    private val muted = LinkedHashSet<String>()

    fun pinnedIds(): List<String> = synchronized(lock) { ensureLoaded(); pinned.toList() }

    fun isPinned(id: String): Boolean = synchronized(lock) { ensureLoaded(); id in pinned }

    fun isMuted(id: String): Boolean = synchronized(lock) { ensureLoaded(); id in muted }

    fun mutedIds(): Set<String> = synchronized(lock) { ensureLoaded(); LinkedHashSet(muted) }

    /** @return the new state. */
    fun setPinned(id: String, on: Boolean): Boolean = synchronized(lock) {
        ensureLoaded()
        val changed = if (on) pinned.add(id) else pinned.remove(id)
        if (changed) save()
        on
    }

    /** @return the new state. */
    fun setMuted(id: String, on: Boolean): Boolean = synchronized(lock) {
        ensureLoaded()
        val changed = if (on) muted.add(id) else muted.remove(id)
        if (changed) save()
        on
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            if (!file.isFile) return
            val o = JSONObject(file.readText(Charsets.UTF_8))
            o.optJSONArray(KEY_PINNED)?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(pinned::add) }
            o.optJSONArray(KEY_MUTED)?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(muted::add) }
        } catch (_: Exception) {
            pinned.clear(); muted.clear()
        }
    }

    private fun save() {
        val o = JSONObject()
            .put(KEY_PINNED, JSONArray(pinned.toList()))
            .put(KEY_MUTED, JSONArray(muted.toList()))
        AtomicFile.write(file, o.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val FILE_NAME = "conversation-preferences.json"
        const val KEY_PINNED = "pinned"
        const val KEY_MUTED = "muted"

        /**
         * [items] in their existing order with the pinned ones first, in pin order. Stable
         * for everything else: the list arrives newest-first and pinning must not reshuffle
         * what it does not pin (Android `ConversationPinOrder.apply`). A pin naming a
         * conversation that no longer exists is inert.
         */
        fun <T> pinnedFirst(items: List<T>, pinnedIds: List<String>, idOf: (T) -> String): List<T> {
            if (pinnedIds.isEmpty()) return items
            val rank = pinnedIds.withIndex().associate { (i, id) -> id to i }
            val (top, rest) = items.partition { idOf(it) in rank }
            return top.sortedBy { rank.getValue(idOf(it)) } + rest
        }
    }
}
