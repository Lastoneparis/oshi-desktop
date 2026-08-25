package com.oshi.desktop.sync

import com.oshi.desktop.store.DesktopPaths
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The pull/apply drain and the push side of the V2 archive — PARITY.md row 0.24, iOS
 * `V2SyncManager.swift`.
 *
 * ============================================================ THE DRAIN
 *
 * `GET /head`; if `maxSeq > lastSeq`, page through everything above `lastSeq`, decrypt each
 * item under the archive key, hand it to an applier, persist the new `lastSeq`. Same shape
 * as `V2SyncManager.pull()` (`swift:200-261`), with the same four properties, each of which
 * is a decision rather than an accident:
 *
 *  1. **`lastSeq` only ever moves FORWARD** ([recordConsumed]). iOS: *"Monotonic: never
 *     move backwards"* (`swift:342-343`). Two devices pulling concurrently, or a page that
 *     arrives out of order after a retry, must not rewind the cursor — a rewind is not a
 *     duplicate delivery (apply is idempotent) but an unbounded replay of the entire
 *     archive on every launch, which for a log that is never trimmed is a client that gets
 *     slower forever.
 *  2. **One undecryptable item costs one item.** `swift:227-233` isolates the failure and
 *     keeps draining. The alternative loses every record above the corrupt one,
 *     permanently, because `lastSeq` never advances past it. [DrainReport.skipped] counts
 *     them so this is visible instead of merely survivable.
 *  3. **`lastSeq` advances past a skipped item too.** That is `swift:222`'s `defer` block,
 *     which runs whether the body `continue`d or not, and it is the thing that makes
 *     property 2 terminate. Written here as an explicit assignment before the `continue`,
 *     because a `defer` that is load-bearing for loop termination is not something to port
 *     as an implicit.
 *  4. **A short page ends the drain** (`swift:249`) — fewer items than the page limit means
 *     the tail has been reached, so there is no extra round-trip to discover it.
 *  5. **A page that did not advance the cursor ends the drain.** Not in the iOS original,
 *     and added because properties 1–4 all assume the server honours `seq > after`. If it
 *     does not, `V2SyncManager.swift:217`'s loop never terminates. See the guard at the
 *     bottom of [drain] for why this is also what makes property 3 testable.
 *
 * ============================================================ CONFLICT RESOLUTION
 *
 * The archive itself only orders; it does not merge. `seq` gives every device the same
 * total order over the log, and apply must be idempotent on `itemId` because a freshly
 * imported device replays the whole non-destructive log
 * (`V2SyncModels.swift:128-132`). Within one `itemId`, the last writer by `ts` wins — and
 * `ts` is the AUTHOR's clock, which is the part iOS states as guidance and does not
 * enforce.
 *
 * This engine enforces it, with a tie-break iOS does not specify: **equal `ts` is broken by
 * `seq`, not by arrival.** Two devices archiving the same logical record in the same
 * millisecond is not hypothetical for machine-generated records (a read-marker sweep
 * stamps a whole conversation at once), and "whichever page I happened to fetch second"
 * is a rule that gives two devices different answers from the same log. `seq` is assigned
 * by the server and is the same everywhere, so it is the only tie-break that converges.
 * See [shouldApply].
 *
 * **Clock skew is not corrected.** A device whose clock is a day fast wins every conflict
 * for a day. That is inherent in last-writer-wins over untrusted client clocks and the
 * shipped clients have it too; the alternative is a vector clock per record, which is a
 * protocol change, not a desktop one. It is recorded here rather than fixed silently —
 * the same posture [com.oshi.desktop.msg.WireClock] takes about never rewriting a
 * timestamp on a heuristic.
 *
 * ============================================================ APPLYING OWN ITEMS
 *
 * Default is to apply them (`V2SyncManager.Config.applyOwnItems = true`, `swift:83-87`),
 * and the reasoning is worth keeping: applying is idempotent, and a REINSTALLED device
 * carries the same `deviceId` from the key store while having none of the history, so
 * skipping "own" items would leave it permanently missing everything it authored before
 * the reinstall. The filter exists for a caller with a genuinely live local copy.
 *
 * ============================================================ WHAT THIS DOES NOT DO
 *
 * It does not run a timer. A desktop client polls (PARITY.md row 2.3) and the poll loop
 * belongs to whoever owns the process lifecycle, not to a store-shaped object; [drain] is
 * a single idempotent pass designed to be called on launch, on foreground, and on a
 * schedule, which is exactly how `V2SyncManager.pull()` is documented to be used
 * (`swift:199`).
 */
class SyncEngine(
    private val client: V2SyncClient,
    private val archiveKey: ByteArray,
    private val cursor: SyncCursor,
    private val deviceId: String,
    private val pageLimit: Int = DEFAULT_PAGE_LIMIT,
    private val applyOwnItems: Boolean = true,
) {

    /** Receives each decrypted record. MUST be idempotent on `itemId` — see the class doc. */
    fun interface Applier {
        fun apply(record: SyncProtocol.InboundRecord)
    }

    data class DrainReport(
        val applied: Int,
        val skippedUndecryptable: Int,
        val skippedOwn: Int,
        val skippedStale: Int,
        val lastSeq: Int,
        val headMaxSeq: Int,
    )

    /**
     * Push one record. [logicalId] is namespaced by kind ([SyncProtocol.namespacedItemId]);
     * use a STABLE one for an immutable record so a retry dedupes server-side, and include a
     * version component for mutable state.
     */
    fun archive(
        kind: SyncProtocol.SyncKind,
        logicalId: String,
        payload: ByteArray,
        tsUnixMillis: Long = System.currentTimeMillis(),
    ): Result<SyncProtocol.PushResult> = archiveBatch(listOf(Triple(kind, logicalId, payload)), tsUnixMillis)

    /** Several records, one round-trip, one nonce each (`V2SyncManager.swift:187-191`). */
    fun archiveBatch(
        records: List<Triple<SyncProtocol.SyncKind, String, ByteArray>>,
        tsUnixMillis: Long = System.currentTimeMillis(),
    ): Result<SyncProtocol.PushResult> {
        if (records.isEmpty()) {
            return Result.failure(IllegalArgumentException("no records to archive (V2SyncManager.swift:188)"))
        }
        val items = records.map { (kind, id, payload) ->
            SyncProtocol.SyncItem(
                itemId = SyncProtocol.namespacedItemId(kind, id),
                kind = SyncProtocol.encodeKind(kind),
                ciphertext = SyncCrypto.seal(archiveKey, payload),
                deviceId = deviceId,
                tsUnixMillis = tsUnixMillis,
            )
        }
        return client.push(items)
    }

    /** Cheap "am I behind?" — a head call, no log transfer (`swift:264-269`). */
    fun isBehind(): Result<Boolean> =
        client.head().map { it.maxSeq > cursor.lastSeq() }

    /**
     * One full drain pass. Idempotent: calling it twice with nothing new is two head calls
     * and no applies.
     */
    fun drain(apply: Applier): Result<DrainReport> {
        val head = client.head().getOrElse { return Result.failure(it) }
        var last = cursor.lastSeq()
        if (head.maxSeq <= last) {
            return Result.success(DrainReport(0, 0, 0, 0, last, head.maxSeq))
        }

        var applied = 0
        var undecryptable = 0
        var own = 0
        var stale = 0

        while (last < head.maxSeq) {
            val beforeThisPage = last
            val page = client.pull(afterSeq = last, limit = pageLimit).getOrElse {
                // A transport failure mid-drain is not an empty tail. Persist what has
                // been durably consumed and report the failure — the next pass resumes
                // from the cursor rather than replaying the page.
                cursor.recordConsumed(last)
                return Result.failure(it)
            }
            if (page.items.isEmpty()) break

            for (it in page.items) {
                // Property 3: the cursor advances past this item whatever happens below.
                val seqOfThisItem = it.seq
                try {
                    if (!applyOwnItems && it.deviceId == deviceId) { own++; continue }
                    if (!shouldApply(it)) { stale++; continue }

                    val plain = try {
                        SyncCrypto.open(archiveKey, it.ciphertext)
                    } catch (e: SyncCryptoException) {
                        // Property 2.
                        undecryptable++
                        continue
                    }
                    apply.apply(
                        SyncProtocol.InboundRecord(
                            kind = SyncProtocol.SyncKind.fromWire(it.kind),
                            rawKind = it.kind,
                            itemId = it.itemId,
                            seq = it.seq,
                            deviceId = it.deviceId,
                            tsUnixMillis = it.tsUnixMillis,
                            payload = plain,
                        )
                    )
                    remember(it)
                    applied++
                } finally {
                    if (seqOfThisItem > last) last = seqOfThisItem
                }
            }

            cursor.recordConsumed(last)

            // Property 5: a page that did not advance the cursor ENDS the drain.
            //
            // Everything above assumes the server honours `seq > after`, so that `last`
            // strictly increases every page and the loop terminates. Nothing in the
            // protocol makes a client safe if it does not: a server that echoes the same
            // page — misbehaving, rolled back, or hostile — spins this loop forever at
            // full CPU with no log line, which is the shape
            // `pgrep-c-prints-zero-and-exits-nonzero` records (an unreachable loop
            // condition burning cores). iOS has the same hole: `V2SyncManager.swift:217`
            // loops `while last < head.maxSeq` and its only exits are an empty page and a
            // short page, both of which a repeating full page satisfies neither of.
            //
            // This is also what makes the property-3 guard above TESTABLE rather than
            // merely true: without it, removing the cursor advance produces a hang, and a
            // hang is not a test result.
            if (last <= beforeThisPage) break
            if (page.items.size < pageLimit) break   // property 4
        }

        cursor.recordConsumed(last)
        return Result.success(DrainReport(applied, undecryptable, own, stale, cursor.lastSeq(), head.maxSeq))
    }

    /**
     * Last-writer-wins within one `itemId`, tie-broken by `seq`. See CONFLICT RESOLUTION.
     *
     * The in-pass memory is deliberately per-drain and not persisted: across passes,
     * idempotence is the applier's job (`V2SyncModels.swift:129-131` puts it there), and a
     * persisted per-itemId table here would be a second source of truth about what has been
     * applied, disagreeing with the applier's own the first time one of them is restored
     * from a backup without the other.
     */
    private fun shouldApply(item: SyncProtocol.PulledItem): Boolean {
        val seen = seenInThisPass[item.itemId] ?: return true
        if (item.tsUnixMillis != seen.first) return item.tsUnixMillis > seen.first
        return item.seq > seen.second
    }

    private fun remember(item: SyncProtocol.PulledItem) {
        seenInThisPass[item.itemId] = item.tsUnixMillis to item.seq
    }

    private val seenInThisPass = HashMap<String, Pair<Long, Int>>()

    companion object {
        /** `V2SyncManager.Config.pullPageLimit` default (`swift:91`). */
        const val DEFAULT_PAGE_LIMIT = 500
    }
}

/**
 * Where `lastSeq` lives, per identity.
 *
 * iOS keeps it in `UserDefaults` under `"com.oshi.v2.sync.lastSeq.<userKey>"`
 * (`V2SyncManager.swift:333`) and the device id in the Keychain
 * (`swift:351-359`). This client has no `UserDefaults`; both go in one small JSON file
 * beside the other stores.
 *
 * The device id is a stable per-install opaque tag, NOT the identity: it attributes an
 * archive item to the device that authored it and appears in cleartext on the server, so
 * it is a random UUID and never anything derived from a key. It survives across runs
 * because [SyncEngine]'s own-item filter and any future per-device diagnostics are
 * meaningless if it rotates.
 *
 * **This file is not secret and does not go in the [com.oshi.desktop.store.KeyVault].** It
 * holds an integer and a UUID; encrypting it would imply the vault is where non-secret
 * state belongs and would make a corrupt vault cost the sync cursor as well as the keys.
 * A lost cursor costs one full replay, which is exactly what the archive is designed to
 * survive.
 */
class SyncCursor(private val file: File = DesktopPaths.file("sync-cursor.json")) {

    private var cache: JSONObject? = null

    @Synchronized
    fun lastSeq(): Int = load().optInt(KEY_LAST_SEQ, 0)

    /**
     * Advance the cursor. **Monotonic** — a lower value is ignored, not written. See
     * [SyncEngine]'s property 1; `V2SyncManager.swift:342-343` is the same guard.
     */
    @Synchronized
    fun recordConsumed(seq: Int) {
        val current = lastSeq()
        if (seq <= current) return
        val o = load()
        o.put(KEY_LAST_SEQ, seq)
        persist(o)
    }

    /** Stable per-install device id, minted on first use. */
    @Synchronized
    fun deviceId(): String {
        val o = load()
        val existing = o.optString(KEY_DEVICE_ID, "")
        if (existing.isNotEmpty()) return existing
        val id = UUID.randomUUID().toString()
        o.put(KEY_DEVICE_ID, id)
        persist(o)
        return id
    }

    /**
     * Forget everything. What an identity switch calls — the cursor is per-identity and a
     * cursor carried across identities would suppress the whole first drain of the new one.
     */
    @Synchronized
    fun reset() {
        cache = JSONObject()
        persist(cache!!)
    }

    private fun load(): JSONObject {
        cache?.let { return it }
        val o = if (file.isFile) {
            // Unlike the contacts file, a damaged cursor is genuinely recoverable by
            // starting from zero: the archive is append-only and non-destructive, so a
            // reset cursor costs one replay and loses nothing. Reading it as absent is
            // the right call HERE and would not be in ContactStore.
            runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrDefault(JSONObject())
        } else JSONObject()
        cache = o
        return o
    }

    private fun persist(o: JSONObject) {
        com.oshi.desktop.store.AtomicFile.write(file, o.toString().toByteArray(Charsets.UTF_8))
        cache = o
    }

    private companion object {
        const val KEY_LAST_SEQ = "lastSeq"
        const val KEY_DEVICE_ID = "deviceId"
    }
}
