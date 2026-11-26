package com.oshi.desktop.net

import com.oshi.desktop.store.LocalDataKeys
import com.oshi.desktop.store.SealedSmallJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * __GROUP_BLOB_2026_11_25__ Media rows whose download did not finish, waiting to RESUME.
 *
 * Before: a received photo whose blob could not be fetched in that one poll pass was stored with
 * no media and never fetched again — the key message was consumed, the bytes stayed on the server
 * until its TTL. Now the key message ([keyJson], the `v2file` JSON: blob id + file key, so the
 * file is sealed under [LocalDataKeys.PENDING_MEDIA_DOWNLOADS]) is kept with the row it belongs
 * to; the client retries on a schedule (1, 5, 30 min, then every 3 h, for 7 days), the chunks
 * already fetched wait in [V2PartialChunks], and a success fills the row in
 * (`MessageStore.replaceMedia`).
 */
class PendingMediaDownloads(private val file: File, private val atRestKey: ByteArray? = null) {
    data class Entry(
        val conversationId: String,
        val rowId: String,
        val keyJson: String,
        val outPath: String,
        val attempts: Int,
        val firstAt: Long,
        val nextAt: Long,
    ) {
        val id: String get() = "$conversationId#$rowId"
    }

    private val lock = Any()
    private var cache: LinkedHashMap<String, Entry>? = null
    private var writable = true

    fun add(conversationId: String, rowId: String, keyJson: String, outPath: String, now: Long = System.currentTimeMillis()) =
        synchronized(lock) {
            val all = load()
            val e = Entry(conversationId, rowId, keyJson, outPath, 0, now, now + delayAfter(1))
            all[e.id] = e
            save(all)
        }

    /** One retry failed: back off. Past 7 days the entry is dropped. */
    fun failed(e: Entry, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val all = load()
        if (now - e.firstAt >= MAX_AGE_MS) all.remove(e.id)
        else all[e.id] = e.copy(attempts = e.attempts + 1, nextAt = now + delayAfter(e.attempts + 2))
        save(all)
    }

    fun done(e: Entry) = synchronized(lock) { val all = load(); if (all.remove(e.id) != null) save(all) }
    fun due(now: Long = System.currentTimeMillis()): List<Entry> = synchronized(lock) { load().values.filter { it.nextAt <= now } }
    fun makeDue(now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val all = load(); for ((k, e) in all.entries.toList()) all[k] = e.copy(nextAt = now); save(all)
    }
    fun size(): Int = synchronized(lock) { load().size }

    private fun load(): LinkedHashMap<String, Entry> {
        cache?.let { return it }
        val out = LinkedHashMap<String, Entry>()
        val disk = SealedSmallJson.load(file, atRestKey, LocalDataKeys.PENDING_MEDIA_DOWNLOADS)
        writable = disk.writable
        disk.json?.let { text ->
            runCatching {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val e = Entry(o.getString("c"), o.getString("r"), o.getString("k"), o.getString("o"),
                        o.optInt("a", 0), o.getLong("f"), o.getLong("n"))
                    out[e.id] = e
                }
            }
        }
        cache = out
        return out
    }

    private fun save(all: LinkedHashMap<String, Entry>) {
        while (all.size > MAX_ENTRIES) all.remove(all.keys.first())
        cache = all
        if (!writable) return
        val arr = JSONArray()
        for (e in all.values) arr.put(JSONObject().put("c", e.conversationId).put("r", e.rowId).put("k", e.keyJson)
            .put("o", e.outPath).put("a", e.attempts).put("f", e.firstAt).put("n", e.nextAt))
        runCatching { SealedSmallJson.save(file, atRestKey, LocalDataKeys.PENDING_MEDIA_DOWNLOADS, arr.toString()) }
    }

    companion object {
        const val FILE_NAME = "pending-media-downloads.json"
        const val MAX_ENTRIES = 300
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MIN = 60_000L
        fun delayAfter(attempt: Int): Long = when {
            attempt <= 1 -> MIN
            attempt == 2 -> 5 * MIN
            attempt == 3 -> 30 * MIN
            else -> 180 * MIN
        }
    }
}
