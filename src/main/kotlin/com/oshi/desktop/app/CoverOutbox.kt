package com.oshi.desktop.app

import com.oshi.desktop.store.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * __PRIVACY_DISGUISE_2026_11_18__ 1:1 messages waiting for the disguised connection.
 *
 * Port of Android's `CoverOutbox` (audit finding 1, fail-closed). With "Disguise OSHI
 * traffic" ON, a 1:1 message whose disguised V2 send fails is NOT reported as failed and is
 * NEVER handed to anything undisguised. It stays on this machine, its bubble says "Waiting
 * for disguised connection", and it is retried over the disguised path only, with Android's
 * backoff. After [GIVE_UP_AFTER_MS] it becomes an ordinary "not delivered" row.
 *
 * The desktop has no legacy relay for 1:1 traffic at all — V2 is its only internet lane —
 * so the fail-closed half is structural here; what this class adds is the honest WAITING
 * state, which is what a user of the phones sees.
 *
 * Driven from the client's poll tick ([due] / [record]) rather than its own thread, so a
 * retry can never race the poll for the same ratchet session. Ids are mirrored to
 * [file] so that a process that dies with a row waiting does not leave that bubble
 * "waiting" forever: [recoverAfterRestart] hands the survivors back to be marked failed,
 * Android's rule (an in-memory retry does not survive the process; pretending it does
 * would be the lie).
 */
class CoverOutbox(
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Parked(
        val conversationId: String,
        val messageId: String,
        val text: String,
        val parkedAtMs: Long,
        val attempts: Int,
        val nextAttemptMs: Long,
        /**
         * __RESEND_KEEPS_DATE_2026_11_18__ When the person pressed send. Every retry is dated
         * with THIS, not with the retry's own time, so receivers keep the thread in send order.
         */
        val sentAtMs: Long = parkedAtMs,
        /** Wall-clock time of the last disguised attempt (the park itself counts). */
        val lastAttemptMs: Long = parkedAtMs,
        /**
         * __COVER_MEDIA_RETRY_2026_11_18__ An attachment (photo, GIF, file, voice/video note,
         * contact card) rather than a text: [text] is unused; the retry re-seals and
         * re-uploads the same bytes under the same message id and date.
         */
        val media: Boolean = false,
    )

    enum class Verdict { DELIVERED, STILL_WAITING, GAVE_UP }

    private val parked = LinkedHashMap<String, Parked>()

    @Synchronized fun isWaiting(messageId: String): Boolean = parked.containsKey(messageId)
    @Synchronized fun waiting(): List<Parked> = parked.values.toList()

    /** Park [messageId]; the first disguised retry is [BACKOFF_MS]`[0]` from now. */
    @Synchronized fun park(conversationId: String, messageId: String, text: String, sentAtMs: Long? = null, media: Boolean = false) {
        val now = clock()
        parked[messageId] = Parked(conversationId, messageId, text, now, 0, now + BACKOFF_MS[0], sentAtMs ?: now, now, media)
        persist()
    }

    /**
     * __COVER_RESUME_AFTER_RESTART_2026_11_18__ Re-park a row a previous process left waiting
     * (see [resumeAfterRestart]): due at once, its hour still counted from [sentAtMs].
     */
    @Synchronized fun resume(conversationId: String, messageId: String, text: String, sentAtMs: Long, media: Boolean = false) {
        val now = clock()
        parked[messageId] = Parked(conversationId, messageId, text, sentAtMs, 0, now, sentAtMs, now - KICK_MIN_SPACING_MS, media)
        persist()
    }

    /**
     * __COVER_RETRY_KICK_2026_11_18__ A disguised request just went THROUGH (a pull answered):
     * the path is back, so parked rows need not sleep out their backoff — after a 10-minute
     * block the next retry used to be up to 10 minutes away although the path had been back
     * for most of them (netem "block10"). Never closer than [KICK_MIN_SPACING_MS] to a row's
     * previous attempt, so a kick cannot become a retry storm. Android's `CoverOutbox.kick`.
     */
    @Synchronized fun kick() {
        if (parked.isEmpty()) return
        val now = clock()
        for ((k, p) in parked.entries.toList()) {
            val earliest = p.lastAttemptMs + KICK_MIN_SPACING_MS
            val at = maxOf(now, earliest)
            if (at < p.nextAttemptMs) parked[k] = p.copy(nextAttemptMs = at)
        }
    }

    /** Rows whose next retry is due. The caller attempts each and reports via [record]. */
    @Synchronized fun due(): List<Parked> {
        val now = clock()
        // Oldest first by the time each was SENT, so the receiver gets them in that order too.
        return parked.values.filter { it.nextAttemptMs <= now }.sortedBy { it.sentAtMs }
    }

    /** The outcome of one disguised retry of [p]. */
    @Synchronized fun record(p: Parked, delivered: Boolean): Verdict {
        val current = parked[p.messageId] ?: return Verdict.GAVE_UP
        if (delivered) {
            parked.remove(p.messageId); persist()
            return Verdict.DELIVERED
        }
        val now = clock()
        if (now - current.parkedAtMs >= GIVE_UP_AFTER_MS) {
            parked.remove(p.messageId); persist()
            return Verdict.GAVE_UP
        }
        val i = current.attempts + 1
        parked[p.messageId] = current.copy(attempts = i, nextAttemptMs = now + BACKOFF_MS[minOf(i, BACKOFF_MS.size - 1)], lastAttemptMs = now)
        return Verdict.STILL_WAITING
    }

    /** User deleted the row, or the switch went off: stop retrying it (it is marked by the caller). */
    @Synchronized fun cancel(messageId: String): Parked? = parked.remove(messageId)?.also { persist() }

    /** Everything parked, removed — the caller decides what each row becomes. */
    @Synchronized fun drain(): List<Parked> = parked.values.toList().also { parked.clear(); persist() }

    /**
     * (conversationId, messageId) pairs a previous process left waiting. Read once, then
     * forgotten: the caller marks them failed.
     */
    @Synchronized fun recoverAfterRestart(): List<Pair<String, String>> {
        val f = file ?: return emptyList()
        val out = try {
            if (!f.isFile) emptyList() else {
                val arr = JSONObject(f.readText(Charsets.UTF_8)).optJSONArray("waiting") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val c = o.optString("c"); val m = o.optString("m")
                    if (c.isEmpty() || m.isEmpty() || parked.containsKey(m)) null else c to m
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        persist()
        return out
    }

    private fun persist() {
        val f = file ?: return
        val arr = JSONArray()
        for (p in parked.values) arr.put(JSONObject().put("c", p.conversationId).put("m", p.messageId).put("k", if (p.media) "media" else "text"))
        runCatching { AtomicFile.write(f, JSONObject().put("waiting", arr).toString().toByteArray(Charsets.UTF_8)) }
    }

    companion object {
        /** A kick never brings a retry closer than this to the row's previous attempt. */
        const val KICK_MIN_SPACING_MS = 5_000L

        /**
         * __COVER_RESUME_AFTER_RESTART_2026_11_18__ On a cold start, a row a previous process left
         * waiting is sent again over the disguised path when it is a plain text, still within
         * the hour, and the disguise is still ON; anything else is "not delivered". Android's
         * `CoverOutbox.resumeAfterRestart` — before, every survivor became a failed row.
         */
        fun resumeAfterRestart(coverOn: Boolean, isText: Boolean, ageMs: Long, mediaReadable: Boolean = false): Boolean =
            coverOn && (isText || mediaReadable) && ageMs in 0 until GIVE_UP_AFTER_MS

        /** Android's backoff between disguised retries; the last value repeats. */
        val BACKOFF_MS = longArrayOf(10_000, 30_000, 60_000, 120_000, 300_000, 600_000)
        /** Android's: "Not delivered" after one hour of waiting. */
        const val GIVE_UP_AFTER_MS = 60 * 60_000L
        /** The `transport` a waiting row carries, so the bubble can say why it waits. */
        const val TRANSPORT_WAITING = "cover-waiting"
        const val FILE_NAME = "cover-outbox.json"
    }
}
