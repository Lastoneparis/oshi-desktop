package com.oshi.desktop.store

import java.io.File
import org.json.JSONObject

/**
 * __MERGED_CHATS_2026_11_18__ "Combine messages and groups", for THIS machine only — same
 * terms as [AppearancePreferences]: a small JSON file in the profile home, never synced,
 * never throws on read.
 *
 * [load] returns null when nothing was ever decided; the caller then applies
 * `ChatListMerge.defaultFor` ONCE and saves it, so the default never flips later.
 */
class ChatListPreferences(private val file: File) {

    @Synchronized fun load(): Boolean? = try {
        if (!file.isFile) null
        else JSONObject(file.readText(Charsets.UTF_8)).let { if (it.has(KEY_MERGED)) it.optBoolean(KEY_MERGED, false) else null }
    } catch (_: Exception) {
        null
    }

    @Synchronized fun save(merged: Boolean) {
        AtomicFile.write(file, JSONObject().put(KEY_MERGED, merged).toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val FILE_NAME = "chat-list-preferences.json"
        const val KEY_MERGED = "mergedChats"
    }
}
