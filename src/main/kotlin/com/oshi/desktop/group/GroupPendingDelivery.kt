package com.oshi.desktop.group

import com.oshi.desktop.store.LocalDataKeys
import com.oshi.desktop.store.SealedSmallJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * __GROUP_RESEND_2026_11_25__ Members a group message has NOT reached yet, kept on disk (sealed
 * under [LocalDataKeys.GROUP_PENDING_DELIVERY]) until they are reached, leave the group, or
 * 7 days pass. Same rules as Android `GroupPendingDeliveryStore` and iOS.
 *
 * Before: `groupFanOut` reported a member it could not reach this time (relay 429 past the wait
 * budget, a timeout, a 5xx, the bundle fetch failing) in a log line, stored the message as SENT
 * because somebody else got it, and never tried that member again.
 *
 *  * [begin] before the fan-out (a quit in the middle resumes on the next start);
 *  * [settle] after each attempt — reached members, members the server says have NO v2 bundle
 *    (the "must update" spec) and blocked members leave the list; empty ⇒ removed, the row is a
 *    plain Sent; otherwise the next attempt is scheduled ([GroupResendSchedule]);
 *  * [dropDeparted]: members who left are not owed the message;
 *  * after 7 days it is [Entry.gaveUp]: kept for the status, no automatic retry, "Retry now" works.
 *
 * [Entry.payload] is the per-member ratchet plaintext the first attempt built (the GroupMessage
 * envelope, or the `v2file` key message of a SHARED blob — identical for every member), so a
 * retry re-encrypts and re-signs exactly that through the normal router path. [Entry.mediaPath]
 * is set instead when the blob store did not take the recipient list (an older server): each
 * remaining member then needs a blob of their own, rebuilt from the sealed local file.
 */
class GroupPendingDeliveryStore(
    private val file: File,
    private val atRestKey: ByteArray? = null,
    private val canon: (String) -> String = { it },
) {
    data class Entry(
        val msgId: String,
        val groupId: String,
        val conversationId: String,
        val total: Int,
        val pending: Set<String>,
        val attempts: Int,
        val firstAt: Long,
        val nextAt: Long,
        val gaveUp: Boolean = false,
        val payload: ByteArray? = null,
        val mediaPath: String? = null,
        val caption: String? = null,
        val noteDurationMs: Long? = null,
        val videoNote: Boolean = false,
        val wake: Boolean = true,
    ) {
        val delivered: Int get() = (total - pending.size).coerceAtLeast(0)
        override fun equals(other: Any?): Boolean = other is Entry && other.msgId == msgId &&
            other.pending == pending && other.total == total && other.nextAt == nextAt && other.gaveUp == gaveUp
        override fun hashCode(): Int = msgId.hashCode()
    }

    private val lock = Any()
    private var cache: LinkedHashMap<String, Entry>? = null
    private var writable = true

    fun begin(entry: Entry): Entry? = synchronized(lock) {
        val list = entry.pending.filter { it.isNotBlank() }.distinctBy(canon).toSet()
        if (list.isEmpty()) return null
        val all = load()
        val e = entry.copy(pending = list, total = list.size, attempts = 0,
            nextAt = entry.firstAt + GroupResendSchedule.delayAfter(0))
        all[e.msgId] = e
        save(all)
        e
    }

    /** One attempt ended. Returns what is still pending, or null when nobody is left (removed). */
    fun settle(
        msgId: String,
        reached: Collection<String>,
        notOwed: Collection<String>,
        now: Long = System.currentTimeMillis(),
        retryAfterMs: Long? = null,
        payload: ByteArray? = null,
        mediaPath: String? = null,
    ): Entry? = synchronized(lock) {
        val all = load()
        val e = all[msgId] ?: return null
        val gone = (reached + notOwed).map(canon).toSet()
        val notOwedHere = notOwed.map(canon).toSet().count { k -> e.pending.any { canon(it) == k } }
        val left = e.pending.filterNot { canon(it) in gone }.toSet()
        if (left.isEmpty()) { all.remove(msgId); save(all); return null }
        val attempts = e.attempts + 1
        val next = e.copy(
            total = (e.total - notOwedHere).coerceAtLeast(left.size),
            pending = left,
            attempts = attempts,
            nextAt = now + maxOf(GroupResendSchedule.delayAfter(attempts), retryAfterMs ?: 0L),
            gaveUp = now - e.firstAt >= GroupResendSchedule.MAX_AGE_MS,
            payload = payload ?: e.payload,
            mediaPath = if (payload != null) null else (mediaPath ?: e.mediaPath),
        )
        all[msgId] = next
        save(all)
        next
    }

    fun dropDeparted(groupId: String, members: Collection<String>): List<String> = synchronized(lock) {
        val keep = members.map(canon).toSet()
        val all = load()
        val done = ArrayList<String>()
        var changed = false
        for ((id, e) in all.entries.toList()) {
            if (!e.groupId.equals(groupId, ignoreCase = true)) continue
            val left = e.pending.filter { canon(it) in keep }.toSet()
            if (left.size == e.pending.size) continue
            changed = true
            if (left.isEmpty()) { all.remove(id); done += id }
            else all[id] = e.copy(pending = left, total = (e.total - (e.pending.size - left.size)).coerceAtLeast(left.size))
        }
        if (changed) save(all)
        done
    }

    fun due(now: Long = System.currentTimeMillis()): List<Entry> = synchronized(lock) {
        load().values.filter { !it.gaveUp && it.nextAt <= now }
    }

    fun makeDue(msgId: String? = null, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val all = load()
        var changed = false
        for ((id, e) in all.entries.toList()) {
            if (msgId != null && id != msgId) continue
            if (e.gaveUp && msgId == null) continue
            all[id] = e.copy(nextAt = now, gaveUp = false, firstAt = if (e.gaveUp) now else e.firstAt)
            changed = true
        }
        if (changed) save(all)
    }

    fun get(msgId: String): Entry? = synchronized(lock) { load()[msgId] }
    fun all(): Map<String, Entry> = synchronized(lock) { LinkedHashMap(load()) }
    fun remove(msgId: String) = synchronized(lock) { val all = load(); if (all.remove(msgId) != null) save(all) }

    // ---- persistence -----------------------------------------------------------------------

    private fun load(): LinkedHashMap<String, Entry> {
        cache?.let { return it }
        val out = LinkedHashMap<String, Entry>()
        val disk = SealedSmallJson.load(file, atRestKey, LocalDataKeys.GROUP_PENDING_DELIVERY)
        writable = disk.writable
        val text = disk.json
        if (text != null) runCatching {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = o.getJSONArray("pending")
                val e = Entry(
                    msgId = o.getString("msgId"),
                    groupId = o.getString("groupId"),
                    conversationId = o.getString("conversationId"),
                    total = o.getInt("total"),
                    pending = (0 until p.length()).map { p.getString(it) }.toSet(),
                    attempts = o.optInt("attempts", 0),
                    firstAt = o.getLong("firstAt"),
                    nextAt = o.getLong("nextAt"),
                    gaveUp = o.optBoolean("gaveUp", false),
                    payload = o.optStringOrNull("payload")?.let { Base64.getDecoder().decode(it) },
                    mediaPath = o.optStringOrNull("mediaPath"),
                    caption = o.optStringOrNull("caption"),
                    noteDurationMs = if (o.has("noteDurationMs")) o.getLong("noteDurationMs") else null,
                    videoNote = o.optBoolean("videoNote", false),
                    wake = o.optBoolean("wake", true),
                )
                out[e.msgId] = e
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
        for (e in all.values) arr.put(JSONObject().apply {
            put("msgId", e.msgId); put("groupId", e.groupId); put("conversationId", e.conversationId)
            put("total", e.total); put("pending", JSONArray(e.pending.toList())); put("attempts", e.attempts)
            put("firstAt", e.firstAt); put("nextAt", e.nextAt)
            if (e.gaveUp) put("gaveUp", true)
            e.payload?.let { put("payload", Base64.getEncoder().encodeToString(it)) }
            e.mediaPath?.let { put("mediaPath", it) }
            e.caption?.let { put("caption", it) }
            e.noteDurationMs?.let { put("noteDurationMs", it) }
            if (e.videoNote) put("videoNote", true)
            if (!e.wake) put("wake", false)
        })
        runCatching { SealedSmallJson.save(file, atRestKey, LocalDataKeys.GROUP_PENDING_DELIVERY, arr.toString()) }
    }

    private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

    companion object {
        const val FILE_NAME = "group-pending-delivery.json"
        const val MAX_ENTRIES = 500
    }
}

/** __GROUP_RESEND_2026_11_25__ When the next automatic attempt runs (Android/iOS parity). */
object GroupResendSchedule {
    private const val MIN = 60_000L
    const val MAX_AGE_MS = 7L * 24 * 60 * MIN

    /** 0 = right after the first send (matters only after a quit mid-send); then 1, 5, 30 min, every 3 h. */
    fun delayAfter(attempts: Int): Long = when {
        attempts <= 1 -> MIN
        attempts == 2 -> 5 * MIN
        attempts == 3 -> 30 * MIN
        else -> 180 * MIN
    }
}
