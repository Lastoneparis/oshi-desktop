package com.oshi.desktop.scheduled

import com.oshi.desktop.store.AtomicFile
import com.oshi.desktop.store.DesktopPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where a desktop schedule lives — PARITY.md row 0.25.
 *
 * `<dataDir>/scheduled-messages.json` = `{"v":1,"messages":[…]}`, rewritten wholesale
 * through [AtomicFile], sorted by `scheduledAtMs` then `id`. Same structural choice as
 * [com.oshi.desktop.store.ContactStore] and for the same reason: a schedule is bounded by
 * what a person has deliberately queued, not by how long they have used the app, so
 * rewrite-everything stays cheap forever. It is emphatically not the append-only shape
 * [com.oshi.desktop.store.MessageStore] uses, because every row here MUTATES — pending
 * becomes sent, sent stays for the UI, cancelled stays so it does not resurrect.
 *
 * The file name is `scheduled-messages.json`, hyphenated, where both phones use
 * `scheduled_messages.json`. Deliberate, and it is the only cosmetic divergence in this
 * package: the two shipped files share a name and are mutually unreadable (see
 * [ScheduledMessage]'s table), so a third file with that same name would invite exactly
 * the wrong assumption from anyone who copies one onto a desktop. Reading a phone's file
 * is [ScheduledMessage.fromIosJson] / [ScheduledMessage.fromAndroidJson]'s job, explicitly.
 *
 * ============================================================ WHAT A DAMAGED FILE COSTS
 *
 * It raises, like [com.oshi.desktop.store.ContactStore] and unlike
 * [com.oshi.desktop.sync.SyncCursor] — which reads a damaged file as absent, because a lost
 * sync cursor costs one replay of a non-destructive log and loses nothing.
 * A schedule is a set of deliberate user decisions with future consequences; reading a
 * corrupt file as "no scheduled messages" would silently cancel every one of them, and the
 * user finds out by the message never arriving. There is no recovery path that reads as
 * success here.
 *
 * ============================================================ PLAINTEXT
 *
 * Same posture as [com.oshi.desktop.store.MessageStore], and worth restating because the
 * contents are worse than a contact list: this file holds message BODIES, unsent, for as
 * long as the user schedules them ahead. It is protected by [DesktopPaths]'s owner-only
 * directory and by nothing else. Both phones do the same — iOS writes it into `Documents/`
 * with no `Data Protection` class named (`swift:268-273`), Android into `filesDir` (`.kt:317`).
 * Stated, not defended: it is the same trade the message history makes, and if that one is
 * revisited this file moves with it.
 */
class ScheduledMessageStore(
    private val file: File = DesktopPaths.file("scheduled-messages.json"),
) {

    @Volatile
    private var cache: MutableMap<String, ScheduledMessage>? = null

    /** Everything, any status. What a "Scheduled" settings screen reads. */
    @Synchronized
    fun all(): List<ScheduledMessage> = load().values.sortedWith(ORDER)

    @Synchronized
    fun get(id: String): ScheduledMessage? = load()[id]

    @Synchronized
    fun pending(): List<ScheduledMessage> = all().filter { it.status == ScheduledMessage.Status.PENDING }

    /** Pending messages for one conversation — iOS `pendingMessages(for:)` (`swift:302-304`). */
    @Synchronized
    fun pendingFor(recipient: String): List<ScheduledMessage> =
        pending().filter { it.recipient == recipient }

    /**
     * Everything due at [nowMs] — PENDING and `scheduledAtMs <= nowMs`, oldest first.
     *
     * Oldest first is not incidental. A desktop that was closed over a weekend comes back
     * with a backlog, and sending it in schedule order is the only order that reads as a
     * conversation on the recipient's side. Neither phone specifies an order: iOS iterates
     * `scheduledMessages.indices` (`swift:172`) and Android filters a `StateFlow` list
     * (`.kt:306-308`), both of which are insertion order — i.e. the order the user happened
     * to create them, which for a backlog is arbitrary.
     */
    @Synchronized
    fun due(nowMs: Long): List<ScheduledMessage> = all().filter { it.isDue(nowMs) }

    @Synchronized
    fun put(m: ScheduledMessage): ScheduledMessage {
        val map = load()
        map[m.id] = m
        persist(map)
        return m
    }

    /**
     * Cancel. A flag, never a delete — the same rule row 0.21 applies to blocking, for the
     * same reason: a cancelled row that vanishes is a row the next reader has no evidence
     * about, and "no evidence" is how a re-import resurrects it.
     *
     * Returns false when the message does not exist or is no longer PENDING. A SENT message
     * cannot be un-sent and saying otherwise in a return value would be a lie the UI repeats.
     */
    @Synchronized
    fun cancel(id: String): Boolean {
        val map = load()
        val m = map[id] ?: return false
        if (m.status != ScheduledMessage.Status.PENDING) return false
        map[id] = m.copy(status = ScheduledMessage.Status.CANCELLED)
        persist(map)
        return true
    }

    /**
     * Edit the body of a PENDING message — iOS's `userEditedContent` override
     * (`swift:19,57-59`), Android's `editContent` which also guards on PENDING
     * (`.kt:214-223`).
     */
    @Synchronized
    fun editContent(id: String, content: String): Boolean {
        val map = load()
        val m = map[id] ?: return false
        if (m.status != ScheduledMessage.Status.PENDING) return false
        map[id] = m.copy(content = content)
        persist(map)
        return true
    }

    /**
     * Move a message to a terminal state, or bump its attempt count.
     *
     * **A status only ever moves out of PENDING.** [ScheduledMessageRunner] is the only
     * caller and it is the guard's other half: nothing may resurrect a CANCELLED row into
     * PENDING, because the WorkManager equivalent on Android has precisely that hole — its
     * worker reads `getContentToSend(messageId)` (`.kt:243-245`), which returns the body
     * regardless of status, so a cancel that loses the race against
     * `WorkManager.cancelUniqueWork` (`.kt:173`) still sends the message the user cancelled.
     */
    @Synchronized
    fun settle(id: String, status: ScheduledMessage.Status, attempts: Int? = null): Boolean {
        require(status != ScheduledMessage.Status.PENDING) {
            "settle() moves a message OUT of PENDING; use retryLater() to leave it pending"
        }
        val map = load()
        val m = map[id] ?: return false
        if (m.status != ScheduledMessage.Status.PENDING) return false
        map[id] = m.copy(status = status, attempts = attempts ?: m.attempts)
        persist(map)
        return true
    }

    /** Leave the message PENDING and record that an attempt was made and refused. */
    @Synchronized
    fun retryLater(id: String): Boolean {
        val map = load()
        val m = map[id] ?: return false
        if (m.status != ScheduledMessage.Status.PENDING) return false
        map[id] = m.copy(attempts = m.attempts + 1)
        persist(map)
        return true
    }

    /** True deletion. Distinct from [cancel] and never a side effect of it. */
    @Synchronized
    fun delete(id: String): Boolean {
        val map = load()
        if (map.remove(id) == null) return false
        persist(map)
        return true
    }

    // ---------------------------------------------------------------------------- plumbing

    private fun load(): MutableMap<String, ScheduledMessage> {
        cache?.let { return it }
        val map = LinkedHashMap<String, ScheduledMessage>()
        if (file.isFile) {
            val o = try {
                JSONObject(file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                throw ScheduledStoreException(
                    "scheduled-messages file is not readable JSON: ${file.absolutePath}", e
                )
            }
            val arr = o.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = parse(arr.getJSONObject(i)) ?: continue
                map[m.id] = m
            }
        }
        cache = map
        return map
    }

    private fun persist(map: Map<String, ScheduledMessage>) {
        val arr = JSONArray()
        for (m in map.values.sortedWith(ORDER)) arr.put(toJson(m))
        val json = JSONObject().put("v", 1).put("messages", arr).toString()
        AtomicFile.write(file, json.toByteArray(Charsets.UTF_8))
        cache = LinkedHashMap(map)
    }

    /**
     * The DESKTOP shape: Unix millis, iOS's lower-case status spelling (arbitrary, but it
     * had to be one of the two and the lower-case one round-trips through
     * [ScheduledMessage.Status.fromWire] on both).
     */
    private fun toJson(m: ScheduledMessage): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("recipient", m.recipient)
        put("content", m.content)
        put("scheduledAtMs", m.scheduledAtMs)
        put("createdAtMs", m.createdAtMs)
        put("status", m.status.iosWire)
        put("tone", m.tone.iosWire)
        if (m.isGroup) put("isGroup", true)
        if (m.aiGenerated) put("aiGenerated", true)
        if (m.attempts > 0) put("attempts", m.attempts)
    }

    private fun parse(o: JSONObject): ScheduledMessage? {
        val id = o.optString("id", "").ifEmpty { return null }
        val recipient = o.optString("recipient", "").ifEmpty { return null }
        if (!o.has("scheduledAtMs")) return null
        return ScheduledMessage(
            id = id,
            recipient = recipient,
            content = o.optString("content", ""),
            scheduledAtMs = o.getLong("scheduledAtMs"),
            status = ScheduledMessage.Status.fromWire(o.optString("status", ""))
                ?: ScheduledMessage.Status.PENDING,
            createdAtMs = o.optLong("createdAtMs", o.getLong("scheduledAtMs")),
            isGroup = o.optBoolean("isGroup", false),
            tone = ScheduledMessage.Tone.fromWire(o.optString("tone", "")),
            aiGenerated = o.optBoolean("aiGenerated", false),
            attempts = o.optInt("attempts", 0),
        )
    }

    private companion object {
        val ORDER: Comparator<ScheduledMessage> =
            compareBy<ScheduledMessage> { it.scheduledAtMs }.thenBy { it.id }
    }
}

class ScheduledStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
