package com.oshi.desktop.msg

import org.json.JSONArray
import org.json.JSONObject

/**
 * __DESKTOP_POLLS_2026_10_29__ `📊POLL📊` and `📊POLL_VOTE📊` — a poll in a conversation,
 * which this client had the SENTINELS for and no way to read.
 *
 * ## What was wrong
 *
 * [ControlPrefix] gained `POLL` and `POLL_VOTE` on 2026-10-27 (`__DESKTOP_CATALOG_DRIFT…`)
 * and nothing was ever built to decode them, so the catalog entry changed the symptom and
 * not the outcome. **Traced on the live inbound path before writing any of this, because the
 * two symptoms call for different fixes:**
 *
 *  - BEFORE the catalog knew the sentinel, a poll was `Kind.PROSE`, was stored, and rendered
 *    in the bubble as the literal text `📊POLL📊{"v":1,"id":"…","q":"Pizza or sushi?",…}`.
 *    That is the failure `ControlPayloadWireFormatTest`'s message names.
 *  - AFTER it, a poll is `ControlEvent.Foreign`, falls to step 7 of `handleV2Inbound`
 *    (*"a sentinel belonging to a row this client has not built … NOT rendered"*), and is
 *    **logged and dropped**. So what ships today is worse in the way that matters: an
 *    iPhone's poll does not arrive mangled, it does not arrive at all, and the only trace is
 *    a line in a log nobody is reading.
 *
 * The same shape as the 1:1 pin fixed earlier today: the sentinel is known, nothing acts on
 * it. A catalog entry is not a feature — it is a promise that something downstream exists.
 *
 * ## This is a THIRD implementation, and that is said out loud
 *
 * `PLAN.md` §1 argues against those, and the repository's answer is usually to COMPILE the
 * phones' file — `DoubleRatchet.kt`, `V2FileKeyMessage.kt` and a dozen others are on this
 * build's `kotlin.include` list for exactly that reason. **That was tried first here and it
 * does not work**: Android's `data/model/PollMessage.kt` is otherwise perfectly shareable
 * (only `org.json` and `java.util.UUID`), but every one of its wire entry points calls
 * `OSHIControlPayload`, and that file imports `android.content.Context` and
 * `com.oshi.messenger.R` across 954 lines. Sharing the model would drag the catalog in.
 *
 * The phones' own fix for this shape is the `RatchetSeams` split — pull the Android-coupled
 * seam out of the pure file and share the rest. That is the right long-term answer and it
 * means editing a shipping app's source, which is not this change. So: a third
 * implementation, mitigated the way this repository has learned to mitigate one —
 * **golden payload bytes, not round trips.** `PollWireFormatTest` pins literal JSON built
 * from the two shipped sources; a round-trip test would pass just as happily with every key
 * renamed, and a shared TEST NAME proves even less (see `golden-file-beats-shared-test-names`).
 *
 * ## The rules, copied rather than re-decided
 *
 * Keys are the phones' `CodingKeys` (`PollMessage.swift:61-71`) and Android's `JSONObject`
 * puts (`PollMessage.kt:61-75`), which already agree: `v`, `id`, `q`, `opts`, `multi`,
 * `anon`, `at`, `closes`, `by`; a vote is `v`, `poll`, `picks`, `at`.
 *
 * **Timestamps are UNIX SECONDS as a Double, not Apple-reference seconds.** iOS says so in
 * a comment with the number in it (`__UNIX_SECONDS_NOT_DATE_2026_10_23__`, the 978 307 200 s
 * gap), because a `Date` through a default `JSONEncoder` would have been the other epoch.
 * This is the one field where being wrong is invisible until a poll closes in 2001.
 *
 * Limits are both platforms': question 300, option 100, 2..12 options. iOS states the reason
 * and it is not cosmetic — *"a poll that one client accepts and the other truncates is a
 * poll that shows two different results on two phones"*.
 *
 * A VOTER IS NEVER ON THE WIRE. [Vote] carries no voter field, by design on both phones: the
 * voter is the authenticated sender of the decrypted envelope, and a name inside the JSON
 * would let anyone vote as anyone. The caller passes the sender's key to [PollTally.record].
 */
object PollPayload {

    /** Both platforms' `CURRENT_VERSION`. */
    const val VERSION = 1

    const val MAX_QUESTION_LENGTH = 300
    const val MAX_OPTION_LENGTH = 100
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 12

    /**
     * A poll definition, exactly as it travelled. **Never mutated by a vote.**
     *
     * iOS records why, and it is a lesson from a different bubble: *"the message payload is
     * the thing both devices signed and re-render from, and the CheckIn mini-map already
     * taught us what happens when a bubble mutates the payload it was parsed from."* Votes
     * live in [PollTally], keyed by poll id.
     */
    data class Poll(
        val id: String,
        val question: String,
        val options: List<String>,
        val allowsMultipleAnswers: Boolean,
        val isAnonymous: Boolean,
        val createdAtUnix: Double,
        val closesAtUnix: Double?,
        val creatorPublicKey: String,
        val version: Int = VERSION,
    ) {
        fun isValidOptionIndex(i: Int): Boolean = i in options.indices

        /** A closed poll still SHOWS its results; it only stops taking ballots. */
        fun isClosed(nowUnix: Double): Boolean = closesAtUnix?.let { it <= nowUnix } == true

        fun encode(): String = ControlPrefix.POLL + JSONObject().apply {
            put("v", version)
            put("id", id)
            put("q", question)
            put("opts", JSONArray().also { a -> options.forEach { a.put(it) } })
            put("multi", allowsMultipleAnswers)
            put("anon", isAnonymous)
            put("at", createdAtUnix)
            // Swift encodes an Optional with encodeIfPresent, so an open-ended poll has NO
            // `closes` key rather than a null one — the same rule the action payload's
            // `newContent` follows, and for the same reason.
            closesAtUnix?.let { put("closes", it) }
            put("by", creatorPublicKey)
        }.toString()
    }

    /**
     * One ballot. An EMPTY [picks] is a RETRACTION and is a real ballot, not a missing one —
     * so it still has to beat the earlier one on timestamp. Both phones say this in the same
     * words.
     */
    data class Ballot(val picks: List<Int>, val atUnix: Double)

    /** The `📊POLL_VOTE📊` payload. Silent on both phones: a ballot is not an event. */
    data class Vote(
        val pollId: String,
        val picks: List<Int>,
        val atUnix: Double,
        val version: Int = VERSION,
    ) {
        val ballot: Ballot get() = Ballot(picks, atUnix)

        fun encode(): String = ControlPrefix.POLL_VOTE + JSONObject().apply {
            put("v", version)
            put("poll", pollId)
            put("picks", JSONArray().also { a -> picks.forEach { a.put(it) } })
            put("at", atUnix)
        }.toString()
    }

    /**
     * What the bubble draws.
     *
     * [voterCount] is the number of DISTINCT people with a non-empty ballot, so in a
     * multiple-answer poll it is less than the sum of [counts] — which is why a footer says
     * "N people" and the bars are drawn against this number and not against the total.
     */
    data class Results(
        val counts: List<Int>,
        val voterCount: Int,
        /**
         * Voter keys per option, in ballot order. **Empty for an anonymous poll**, and the
         * distinction matters: they are not withheld at draw time, they are NEVER COLLECTED,
         * so a screenshot of this window cannot leak what was never gathered.
         */
        val votersByOption: List<List<String>>,
        val myPicks: Set<Int>,
    ) {
        fun share(index: Int): Float =
            if (voterCount > 0 && index in counts.indices) counts[index].toFloat() / voterCount else 0f

        /**
         * Rounded, and never 0 % for an option that really has a voter — both phones have
         * this clamp and iOS gives the reason: *"a bar with a body and 0 % written on it
         * reads as a bug."*
         */
        fun percent(index: Int): Int {
            val raw = share(index) * 100f
            if (raw > 0f && raw < 1f) return 1
            return Math.round(raw)
        }
    }

    // ------------------------------------------------------------------ decode

    fun isPoll(text: String?): Boolean = text != null && ControlPrefix.match(text) == ControlPrefix.POLL

    fun isVote(text: String?): Boolean = text != null && ControlPrefix.match(text) == ControlPrefix.POLL_VOTE

    /**
     * Null when the text is not a poll, the body is not JSON, there is no `id`, or fewer
     * than [MIN_OPTIONS] options survive — the same four refusals both phones make.
     *
     * An option that is blank after trimming is DROPPED rather than kept, which is Android's
     * `filter { it.isNotEmpty() }` on decode; dropping it here and not there would renumber
     * the indices a vote refers to.
     */
    fun decodePoll(text: String?): Poll? {
        if (!isPoll(text)) return null
        val body = ControlPrefix.strip(text!!)
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val arr = o.optJSONArray("opts") ?: return null
        val options = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        if (options.size < MIN_OPTIONS) return null
        return Poll(
            id = id,
            question = o.optString("q"),
            options = options,
            allowsMultipleAnswers = o.optBoolean("multi", false),
            isAnonymous = o.optBoolean("anon", false),
            createdAtUnix = o.optDouble("at", 0.0),
            // `has` before `optDouble`: an ABSENT `closes` is an open-ended poll, while
            // optDouble's own default would make it a poll that closed at the epoch.
            closesAtUnix = if (o.has("closes") && !o.isNull("closes")) o.optDouble("closes") else null,
            creatorPublicKey = o.optString("by"),
            version = o.optInt("v", 1),
        )
    }

    /** Null when the text is not a vote, the body is not JSON, or `poll` is missing. */
    fun decodeVote(text: String?): Vote? {
        if (!isVote(text)) return null
        val body = ControlPrefix.strip(text!!)
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val id = o.optString("poll").takeIf { it.isNotEmpty() } ?: return null
        val arr = o.optJSONArray("picks") ?: JSONArray()
        return Vote(
            pollId = id,
            picks = (0 until arr.length()).map { arr.optInt(it) },
            // A vote with no timestamp cannot be ordered, so it loses to anything stamped.
            atUnix = o.optDouble("at", 0.0),
            version = o.optInt("v", 1),
        )
    }

    /**
     * The one definition of "this poll is sendable", so a Send button and the wire can never
     * disagree — iOS's reason for having it in one place.
     *
     * Returns null when the question is blank or the option count is out of range.
     */
    fun sanitize(question: String, options: List<String>): Pair<String, List<String>>? {
        val q = question.trim()
        if (q.isEmpty()) return null
        val opts = options.map { it.trim() }.filter { it.isNotEmpty() }.map { it.take(MAX_OPTION_LENGTH) }
        if (opts.size < MIN_OPTIONS || opts.size > MAX_OPTIONS) return null
        return q.take(MAX_QUESTION_LENGTH) to opts
    }
}
