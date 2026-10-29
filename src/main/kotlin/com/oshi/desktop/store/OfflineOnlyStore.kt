package com.oshi.desktop.store

import com.oshi.desktop.msg.OfflineOnlyPayload
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * __DESKTOP_OFFLINE_ONLY_2026_10_29__ Which conversations are restricted to the mesh and the
 * radio, and who has confirmed — iOS's `OfflineOnlyStore`.
 *
 * ## Four rules, each of which is a decision
 *
 * **1. [set] CLEARS the confirmations.** A new request has to be answered again; carrying the
 * old set forward would show a conversation as agreed when nobody has seen the latest change.
 * This is the rule that makes the count trustworthy, so it is the one worth breaking a test
 * over.
 *
 * **2. [noteConfirmation] counts only while the conversation is actually restricted.** A
 * stale confirmation arriving after someone lifted the restriction must not resurrect it, and
 * must not inflate the count of a later one.
 *
 * **3. [answersCurrentRequest] matches on the STATE AND the TIMESTAMP.** Matching on `on`
 * alone would count an ack for a previous, opposite state after a quick off-then-on. The
 * tolerance exists because the value makes a round trip through JSON and back on another
 * platform, where it may be re-encoded from a float.
 *
 * **4. [applyIncoming] ignores a request OLDER than what we hold.** Mesh delivery is not
 * ordered, so an out-of-order request is ordinary rather than an error — and applying it
 * would flip a conversation back to a state its owner already changed.
 *
 * ## What is NOT here
 *
 * No sealing. iOS seals this file *"because the list of people you have chosen to speak to
 * only off-grid is user content"*, and it is right — but on this client the comparable list
 * already sits in plain `MessageStore` file names and `contacts.json`, so sealing only this
 * one would be a promise the surrounding directory does not keep. Recorded rather than
 * quietly skipped: the honest statement is that this client's home directory is as sensitive
 * as its most sensitive file, and that is a bigger change than one store.
 */
class OfflineOnlyStore(private val file: File) {

    data class State(
        val on: Boolean,
        val changedAtUnix: Double,
        /** OTHER participants who echoed the request back. We are never in it. */
        val confirmedBy: Set<String>,
    )

    private val lock = Any()
    private var loaded = false
    private val states = HashMap<String, State>()

    fun isOfflineOnly(conversationId: String): Boolean = synchronized(lock) {
        ensureLoaded()
        states[canonical(conversationId)]?.on == true
    }

    fun state(conversationId: String): State? = synchronized(lock) {
        ensureLoaded()
        states[canonical(conversationId)]
    }

    fun confirmedCount(conversationId: String): Int = synchronized(lock) {
        ensureLoaded()
        states[canonical(conversationId)]?.confirmedBy?.size ?: 0
    }

    fun all(): Map<String, State> = synchronized(lock) {
        ensureLoaded()
        HashMap(states)
    }

    /** WE turned it on or off. Rule 1: the confirmations are cleared. */
    fun set(on: Boolean, conversationId: String, atUnix: Double): State {
        synchronized(lock) {
            ensureLoaded()
            val s = State(on = on, changedAtUnix = atUnix, confirmedBy = emptySet())
            states[canonical(conversationId)] = s
            persist()
            return s
        }
    }

    /** Rule 3. */
    fun answersCurrentRequest(req: OfflineOnlyPayload.Request, conversationId: String): Boolean =
        synchronized(lock) {
            ensureLoaded()
            val s = states[canonical(conversationId)] ?: return false
            if (!s.on || req.on != s.on) return false
            return Math.abs(req.atUnix - s.changedAtUnix) < ACK_TOLERANCE_SECONDS
        }

    /** Rule 2. @return true when the set actually grew, so a caller can skip a redraw. */
    fun noteConfirmation(from: String, conversationId: String): Boolean {
        synchronized(lock) {
            ensureLoaded()
            val key = canonical(conversationId)
            val s = states[key] ?: return false
            if (!s.on) return false
            val who = canonical(from)
            if (who.isEmpty() || who in s.confirmedBy) return false
            states[key] = s.copy(confirmedBy = s.confirmedBy + who)
            persist()
            return true
        }
    }

    /**
     * Rule 4. Someone else asked for this conversation to be restricted; we honour it
     * locally, which is the whole point of the request.
     *
     * @return true when the state changed, so the caller knows to echo a confirmation and
     *   repaint. A request we already hold is answered again anyway by the caller — an ack
     *   that crossed with a redelivery is cheap, and a peer waiting for one is not.
     */
    fun applyIncoming(req: OfflineOnlyPayload.Request, conversationId: String): Boolean {
        synchronized(lock) {
            ensureLoaded()
            val key = canonical(conversationId)
            val existing = states[key]
            if (existing != null && existing.changedAtUnix > req.atUnix) return false
            if (existing != null && existing.on == req.on && existing.changedAtUnix == req.atUnix) return false
            states[key] = State(on = req.on, changedAtUnix = req.atUnix, confirmedBy = emptySet())
            persist()
            return true
        }
    }

    // ------------------------------------------------------------------ disk

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull()
            ?: return
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        for (id in root.keys()) {
            val o = runCatching { root.getJSONObject(id) }.getOrNull() ?: continue
            val arr = o.optJSONArray("by") ?: JSONArray()
            states[id] = State(
                on = o.optBoolean("on", false),
                changedAtUnix = o.optDouble("at", 0.0),
                confirmedBy = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.toSet(),
            )
        }
    }

    private fun persist() {
        val root = JSONObject()
        states.forEach { (id, s) ->
            root.put(id, JSONObject()
                .put("on", s.on)
                .put("at", s.changedAtUnix)
                .put("by", JSONArray().also { a -> s.confirmedBy.sorted().forEach { a.put(it) } }))
        }
        val payload = root.toString()
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            tmp.setReadable(false, false); tmp.setReadable(true, true)
            tmp.setWritable(false, false); tmp.setWritable(true, true)
            if (!tmp.renameTo(file)) { file.writeText(payload, Charsets.UTF_8); tmp.delete() }
        }
    }

    companion object {
        const val FILE_NAME = "offline-only.json"

        /**
         * iOS's `< 1.0`. The stamp makes a round trip through JSON on another platform, so
         * an exact comparison would reject an ack that is answering the right request.
         */
        const val ACK_TOLERANCE_SECONDS = 1.0

        /**
         * Fold the two base64 spellings of a public key so one conversation cannot end up
         * with two entries that disagree. iOS's store calls
         * `MessageManager.canonicalIdentity`, and `GroupIdentity.canonicalIdentity` is that
         * function transliterated line for line — so this is the same fold, not an
         * equivalent one.
         *
         * **Deliberately NOT `canonicalGroupId`**, which was the first thing written here and
         * is wrong for this: it tries a UUID parse and returns anything else UNCHANGED, so the
         * two spellings of a public key would not have folded at all — exactly the bug the
         * fold exists to prevent, and silent, because each spelling would simply get its own
         * entry.
         *
         * One observation recorded because iOS's comment beside this call says the opposite
         * and someone will check: iOS claims *"group ids are uuidStrings and pass through
         * unchanged"*. They do not. A 36-character UUID fails the fast path (it contains
         * `-`), its hyphens become `+`, and the result decodes as base64, so a group id comes
         * out as `3F2A9C10+1B2C+...`. That is harmless HERE — the transformation is stable, so
         * every lookup of one group id yields one key — and it is only harmless because this
         * value is never put on a wire or shown. Worth knowing before anyone reuses it.
         */
        fun canonical(id: String): String =
            if (id.isEmpty()) id else com.oshi.desktop.group.GroupIdentity.canonicalIdentity(id)
    }
}
