package com.oshi.desktop.sync

import com.oshi.desktop.net.V2Http
import com.oshi.desktop.sync.SyncProtocol.Archive

/**
 * The four `/v2/sync` routes — PARITY.md row 0.24, iOS `V2Client+Sync.swift`.
 *
 * Desktop port of `V2SyncAPI`. There is no Android counterpart to port from: `/v2/sync`
 * does not appear anywhere in the Android tree, so this is the one place in Tier 0 where
 * the iOS file is the sole reference and there is no second implementation to check it
 * against — which is precisely why every shape it asserts is quoted with a line number.
 *
 * ============================================================ SIGNING
 *
 * Identical to every other V2 route, so it goes through [V2Http] rather than reimplementing
 * anything. `V2Client+Sync.swift:9-14` says so explicitly ("mirrors `V2Client` EXACTLY"),
 * and the two details that trip a fresh implementation are already [V2Http]'s rules 1 and
 * 2:
 *
 *  - **the query is not signed.** `?after=&limit=` is on the URL and absent from the
 *    canonical string; iOS builds `path` with the query appended and then re-extracts
 *    `percentEncodedPath` for signing (`swift:94-100,166`), which is the same thing said
 *    awkwardly. [V2Http.get] takes path and query as separate parameters so the two cannot
 *    drift.
 *  - **a GET signs over ZERO bytes**, and the iOS comment says why in server terms:
 *    *"Server sets rawBody = empty for GET before verifying -> sign over empty"*
 *    (`swift:99,120`). Not the two-byte `""` the blob and account routes use.
 *
 * `x-oshi-user` rides on these calls (`swift:155`), which is what [V2Http]'s own doc means
 * by "the blob, account and sync routes".
 *
 * ============================================================ OWNER-ONLY
 *
 * All four routes are same-key self-sync: the server requires the verified signing pubkey
 * to equal the `userKey` in the path (`V2Client+Sync.swift:23-26` → `sync_store.js`'s
 * `requireOwner`). iOS enforces this only for push, where it ignores the caller's argument
 * and uses `crypto.userKey` (`swift:81`); `syncPull`, `syncCheckpoint` and `syncHead` all
 * take a `userKey` parameter and will happily build a request for somebody else's archive
 * that the server then 401s.
 *
 * This client keeps iOS's parameter — so the API shapes still line up — and closes the
 * hole behind it: every route runs its `userKey` through [assertOwner] before a request is
 * built, and the parameter defaults to this client's own identity so the ordinary call has
 * nothing to get wrong.
 *
 * The refusal is not defence against the server — the server is already right — it is
 * defence against the request ever being SENT. A signed GET for another identity's archive
 * is a signed statement, to a relay operator, of whose data this account was trying to
 * read. The 401 comes back after that has been said.
 *
 * ============================================================ RETRIES
 *
 * There are none here, deliberately. iOS wraps each route in `withRetry` with 2s/4s/8s
 * backoff (`swift:212-227`); this client's retry policy lives one layer up in
 * [SyncEngine], where the pull loop already knows what it has durably consumed and can
 * resume from `lastSeq` instead of replaying a page. A retry underneath a paging loop
 * retries the page; a retry above it retries the drain, which is the unit that is actually
 * idempotent.
 */
class V2SyncClient(private val http: V2Http, private val ownerKey: String) {

    /** `POST /v2/sync/:userKey` — append items. Idempotent on `itemId`. */
    fun push(items: List<SyncProtocol.SyncItem>, userKey: String = ownerKey): Result<SyncProtocol.PushResult> {
        assertOwner(userKey)
        if (items.isEmpty()) {
            return Result.failure(IllegalArgumentException("no items to push (V2Client+Sync.swift:80)"))
        }
        val body = SyncProtocol.encodePushBody(items)
        val resp = http.postJson(path(userKey), body, withUserHeader = true)
        if (!resp.isSuccess) return Result.failure(httpError("syncPush", resp))
        val parsed = SyncProtocol.parsePushResult(resp.body)
            ?: return Result.failure(RuntimeException("syncPush: unreadable response body"))
        return Result.success(parsed)
    }

    /**
     * `GET /v2/sync/:userKey?after=&limit=` — non-destructive pull of everything with
     * `seq > afterSeq`.
     *
     * iOS omits `after` entirely when it is 0 and `limit` when absent (`swift:96-98`);
     * reproduced, because an omitted parameter and `after=0` are the same request only if
     * the server's default is 0, and this client does not get to assume that.
     */
    fun pull(afterSeq: Int = 0, limit: Int? = null, userKey: String = ownerKey): Result<SyncProtocol.PullResult> {
        assertOwner(userKey)
        val q = buildList {
            if (afterSeq > 0) add("after=$afterSeq")
            if (limit != null && limit > 0) add("limit=$limit")
        }.joinToString("&")
        val resp = http.get(path(userKey), query = q.ifEmpty { null }, withUserHeader = true)
        if (!resp.isSuccess) return Result.failure(httpError("syncPull", resp))
        // A failed pull is a failure, never an empty page — PARITY.md row 0.10's rule,
        // and it matters more here: an empty page ENDS the drain loop in [SyncEngine].
        val parsed = SyncProtocol.parsePullResult(resp.body)
            ?: return Result.failure(RuntimeException("syncPull: unreadable response body"))
        return Result.success(parsed)
    }

    /** `GET /v2/sync/:userKey/head` — `{maxSeq,count}` without transferring the log. */
    fun head(userKey: String = ownerKey): Result<SyncProtocol.Head> {
        assertOwner(userKey)
        val resp = http.get("${path(userKey)}/head", withUserHeader = true)
        if (!resp.isSuccess) return Result.failure(httpError("syncHead", resp))
        val parsed = SyncProtocol.parseHead(resp.body)
            ?: return Result.failure(RuntimeException("syncHead: unreadable response body"))
        return Result.success(parsed)
    }

    /**
     * `POST /v2/sync/:userKey/checkpoint {upToSeq}` — let the server trim at or below
     * `upToSeq`.
     *
     * Trimming is a MULTI-DEVICE decision: it is only safe once every device of the
     * identity has consumed that seq, and no device can know that on its own. The server
     * keeps a safety margin regardless, and iOS's default policy is `.disabled` for exactly
     * this reason (`V2SyncManager.swift:104-114`). [SyncEngine] keeps that default; this
     * method exists so a designated coordinator can call it explicitly, never as a side
     * effect of a pull.
     */
    fun checkpoint(upToSeq: Int, userKey: String = ownerKey): Result<SyncProtocol.CheckpointResult> {
        assertOwner(userKey)
        val body = "{\"upToSeq\":$upToSeq}".toByteArray(Charsets.UTF_8)
        val resp = http.postJson("${path(userKey)}/checkpoint", body, withUserHeader = true)
        if (!resp.isSuccess) return Result.failure(httpError("syncCheckpoint", resp))
        val parsed = SyncProtocol.parseCheckpointResult(resp.body)
            ?: return Result.failure(RuntimeException("syncCheckpoint: unreadable response body"))
        return Result.success(parsed)
    }

    /**
     * Refuse a `userKey` that is not this client's own identity.
     *
     * The guard behind the OWNER-ONLY section. Called by anything that obtained a key from
     * a config file, a CLI flag or a pulled record and is about to address the archive with
     * it. Comparison is exact, not normalised: unlike a contact address (row 0.21, where a
     * padding difference must still match), this string is about to be percent-encoded into
     * a PATH, and two spellings of the same key are two different storage slots on the
     * server — treating them as equal here would let a request through that reads an
     * archive nothing writes to.
     */
    fun assertOwner(userKey: String) {
        require(userKey == ownerKey) {
            "/v2/sync is owner-only same-key self-sync (V2Client+Sync.swift:23-26): refusing to " +
                "address ${userKey.take(12)}… from identity ${ownerKey.take(12)}…"
        }
    }

    /** Which archive this client speaks, so a caller cannot confuse it with the legacy one. */
    val archive: Archive get() = Archive.V2

    private fun path(userKey: String): String = "/v2/sync/${SyncProtocol.v2PathKey(userKey)}"

    private fun httpError(label: String, resp: V2Http.Response): Throwable =
        RuntimeException("$label HTTP ${resp.code}: ${resp.body.take(200)}")
}
