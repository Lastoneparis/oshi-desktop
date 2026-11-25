package com.oshi.desktop.update

import com.oshi.desktop.store.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * __DESKTOP_AUTO_UPDATE_2026_11_21__ "Later": that version is not offered again for
 * [SNOOZE_MS]. A NEWER version than the snoozed one is offered at once.
 *
 * A small plaintext file in this profile's data directory, beside `receipt-preferences.json`:
 * machine state, not account data — it is not in the vault, not exported, not synced to linked
 * devices. A corrupt or missing file reads as "nothing snoozed".
 */
class UpdatePreferences(private val file: File) {

    data class Snooze(val version: String, val untilMs: Long)

    @Synchronized fun snooze(): Snooze? = try {
        if (!file.isFile) null
        else JSONObject(file.readText(Charsets.UTF_8)).let { j ->
            val v = j.optString(KEY_VERSION)
            val until = j.optLong(KEY_UNTIL, 0)
            if (v.isBlank() || until <= 0) null else Snooze(v, until)
        }
    } catch (_: Exception) {
        null
    }

    @Synchronized fun snooze(version: Version, nowMs: Long) {
        val j = JSONObject().put(KEY_VERSION, version.toString()).put(KEY_UNTIL, nowMs + SNOOZE_MS)
        runCatching { AtomicFile.write(file, j.toString().toByteArray(Charsets.UTF_8)) }
    }

    @Synchronized fun clear() {
        runCatching { file.delete() }
    }

    /** True while [offered] is the snoozed version and its 3 days have not run out. */
    fun isSnoozed(offered: Version, nowMs: Long): Boolean {
        val s = snooze() ?: return false
        return s.version == offered.toString() && nowMs < s.untilMs
    }

    companion object {
        const val FILE_NAME = "update-preferences.json"
        const val SNOOZE_MS = 3L * 24 * 60 * 60 * 1000
        private const val KEY_VERSION = "snoozed_version"
        private const val KEY_UNTIL = "snoozed_until_ms"
    }
}
