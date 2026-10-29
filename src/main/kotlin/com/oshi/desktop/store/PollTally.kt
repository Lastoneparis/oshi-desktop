package com.oshi.desktop.store

import com.oshi.desktop.lora.LoRaSecureMessage
import com.oshi.desktop.msg.PollPayload
import java.io.File
import org.json.JSONObject

/**
 * __DESKTOP_POLLS_2026_10_29__ Who voted what — the desktop's half of `PollStore.kt`.
 *
 * Android's `service/PollStore.kt` could not be shared (it is `Context`, `android.util.Log`,
 * `SealedStringStore` and coroutines), so this is the same RULES over this client's own
 * storage — the split `RatchetSeams` / `DoubleRatchetAndroidStore` already established, and
 * the same arrangement [PinnedMessageStore] uses.
 *
 * The ballots live HERE and never in the poll message. iOS gives the reason and it is a
 * lesson from a previous bug: the payload is what both devices re-render from, and a bubble
 * that mutates the payload it was parsed from loses the ability to be re-parsed.
 *
 * ## The rules, and the two that are easy to get subtly wrong
 *
 * **1. Strictly later wins; an equal timestamp keeps what we hold.** Android's comment: two
 * ballots at the same instant from the same voter is a duplicate, *"and flapping between
 * them would make the bars jitter"*. The mesh delivers the same vote more than once as a
 * matter of course, so this is the common path, not an edge case.
 *
 * **2. The voter key is normalised to the PADDING-STRIPPED form, which is NOT this client's
 * usual canonical spelling.** `LegacyRatchetEnvelope.canonicalKey` RE-ADDS base64 padding;
 * Android's `CallAdmissionPolicy.normalizeKey` strips it, and `PollStore` uses that one.
 * Ordinarily an internal map key could use either — but [PollPayload.Results] orders voters
 * by `(atUnix, key)` *specifically so that two devices list them in the same order*, and
 * `"AAAA"` and `"AAAA=="` do not sort alike. So the spelling is a cross-device-visible
 * property here, and this uses `LoRaSecureMessage.normalizeKey`, which is Android's
 * transformation character for character. `PollTallyTest` pins that equivalence rather than
 * trusting this paragraph.
 */
class PollTally(private val file: File) {

    private val lock = Any()
    private var loaded = false
    /** pollId -> voter (normalised) -> that voter's current ballot. */
    private val ballots = HashMap<String, HashMap<String, PollPayload.Ballot>>()
    private val lastTouched = HashMap<String, Double>()

    /**
     * Record a ballot.
     *
     * @return true when the stored state actually changed, so a caller can skip a redraw for
     *   the duplicate the mesh just delivered again.
     */
    fun record(pollId: String, voter: String, ballot: PollPayload.Ballot): Boolean {
        if (pollId.isEmpty() || voter.isEmpty()) return false
        val who = normalize(voter)
        if (who.isEmpty()) return false
        synchronized(lock) {
            ensureLoaded()
            val table = ballots.getOrPut(pollId) { HashMap() }
            val existing = table[who]
            // Strictly later wins. See rule 1 in the class note.
            if (existing != null && ballot.atUnix <= existing.atUnix) return false
            table[who] = ballot
            lastTouched[pollId] = System.currentTimeMillis() / 1000.0
            enforceCap()
            persist()
            return true
        }
    }

    /** Every ballot held for one poll, for tests and for [results]. */
    fun ballotsFor(pollId: String): Map<String, PollPayload.Ballot> = synchronized(lock) {
        ensureLoaded()
        HashMap(ballots[pollId] ?: emptyMap())
    }

    fun forget(pollId: String) {
        synchronized(lock) {
            ensureLoaded()
            if (ballots.remove(pollId) != null) { lastTouched.remove(pollId); persist() }
        }
    }

    /**
     * Tally one poll — Android's `results()`, branch for branch.
     *
     * Four behaviours here are decisions and not arithmetic:
     *  - the order is `(atUnix, voterKey)`, so two devices list the same voters identically;
     *  - a ballot's picks are filtered to VALID indices, de-duplicated and sorted, so a
     *    malformed or hostile ballot cannot count a voter twice or index past the options;
     *  - in a single-answer poll a ballot carrying several picks keeps only the FIRST,
     *    rather than counting that voter once per pick;
     *  - an empty effective pick list is a RETRACTION: it is skipped entirely, so it does
     *    not raise `voterCount`.
     */
    fun results(poll: PollPayload.Poll, myPublicKey: String): PollPayload.Results {
        val counts = IntArray(poll.options.size)
        val voters = List(poll.options.size) { mutableListOf<String>() }
        var voterCount = 0
        var mine: Set<Int> = emptySet()
        val me = normalize(myPublicKey)

        val ordered = ballotsFor(poll.id).entries
            .sortedWith(compareBy({ it.value.atUnix }, { it.key }))

        for ((voter, ballot) in ordered) {
            val unique = ballot.picks.filter { poll.isValidOptionIndex(it) }.distinct().sorted()
            val effective = if (poll.allowsMultipleAnswers) unique else unique.take(1)
            if (effective.isEmpty()) continue
            voterCount++
            for (i in effective) {
                counts[i]++
                // Never collected for an anonymous poll — see Results.votersByOption.
                if (!poll.isAnonymous) voters[i].add(voter)
            }
            if (voter == me && me.isNotEmpty()) mine = effective.toSet()
        }

        return PollPayload.Results(
            counts = counts.toList(),
            voterCount = voterCount,
            // __ONE_GUARD_NOT_TWO_2026_10_29__ Android writes the anonymity rule TWICE — once
            // where the key would be appended and again here, emptying the lists on the way
            // out. Both were ported, and a mutation run caught them covering for each other:
            // removing either one alone left all 15 tests green, and only removing BOTH
            // failed. That is the same finding as the two teardown lines row 2.1 records
            // deleting, and the same remedy — a property that no single mutation can break is
            // a property that is not actually tested.
            //
            // The guard at the append site is the one kept, because it is the one that makes
            // the claim in [PollPayload.Results.votersByOption] literally true: with only this
            // one, a key never enters the list at all, whereas the outgoing filter would have
            // let every voter's key accumulate in memory and then dropped it. The OUTPUT is
            // identical either way — for an anonymous poll these lists are empty because
            // nothing was ever put in them.
            votersByOption = voters.map { it.toList() },
            myPicks = mine,
        )
    }

    // ------------------------------------------------------------------ disk

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull()
            ?: return
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        for (pollId in root.keys()) {
            val per = runCatching { root.getJSONObject(pollId) }.getOrNull() ?: continue
            val table = HashMap<String, PollPayload.Ballot>()
            for (voter in per.keys()) {
                val b = runCatching { per.getJSONObject(voter) }.getOrNull() ?: continue
                val arr = b.optJSONArray("picks")
                val picks = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optInt(it) }
                table[voter] = PollPayload.Ballot(picks, b.optDouble("at", 0.0))
            }
            if (table.isNotEmpty()) {
                ballots[pollId] = table
                lastTouched[pollId] = table.values.maxOf { it.atUnix }
            }
        }
    }

    /** Android's `MAX_POLLS_KEPT`, evicting by least-recently-touched. */
    private fun enforceCap() {
        if (ballots.size <= MAX_POLLS_KEPT) return
        val victims = lastTouched.entries.sortedBy { it.value }
            .take(ballots.size - MAX_POLLS_KEPT).map { it.key }
        for (id in victims) { ballots.remove(id); lastTouched.remove(id) }
    }

    private fun persist() {
        val root = JSONObject()
        ballots.forEach { (pollId, table) ->
            val per = JSONObject()
            table.forEach { (voter, b) ->
                per.put(voter, JSONObject()
                    .put("picks", org.json.JSONArray().also { a -> b.picks.forEach { a.put(it) } })
                    .put("at", b.atUnix))
            }
            root.put(pollId, per)
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
        const val MAX_POLLS_KEPT = 1000
        const val FILE_NAME = "poll-ballots.json"

        /**
         * Android's `CallAdmissionPolicy.normalizeKey`, reached through the desktop function
         * that already performs the identical transformation. See rule 2 in the class note
         * for why the PADDED spelling would be wrong here.
         */
        fun normalize(key: String): String = LoRaSecureMessage.normalizeKey(key)
    }
}
