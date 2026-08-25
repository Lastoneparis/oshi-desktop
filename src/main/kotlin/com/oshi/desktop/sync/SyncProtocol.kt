package com.oshi.desktop.sync

import com.oshi.desktop.DesktopV2Signer
import org.json.JSONObject

/**
 * Multi-device sync — PARITY.md row 0.24. The wire shapes, and which of them is real.
 *
 * ============================================================ THERE ARE TWO ARCHIVES
 *
 * The ledger row names `V2SyncManager.swift` + `V2Client+Sync.swift` on the iOS side and
 * `MultiDeviceSyncManager.kt` on the Android side, as if those were the two halves of one
 * protocol. **They are not the same protocol.** Reading both trees turns up two entirely
 * separate archives with different routes, different key derivations, different item
 * shapes and different authentication — and the one the row names on iOS is the one that
 * is not wired up:
 *
 * | | **V2 archive** | **legacy archive** |
 * |---|---|---|
 * | route | `/v2/sync/:userKey` (+`/head`, `/checkpoint`) | `/api/sync/{kind}/{key}` |
 * | path key | percent-encoded base64 (`V2Client+Sync.swift:138-140`) | **base64url, padding stripped** (`MultiDeviceSyncManager.swift:449-454`) |
 * | auth | Ed25519 request signature, owner-only | **none at all** |
 * | shape | append-only log of `{seq,itemId,kind,ciphertext,deviceId,ts}` | one whole-blob-per-kind overwrite |
 * | archive key | `HKDF-SHA256(ed25519priv, 0^32, "oshi-mds-v1", 32)` | `SHA-256(x25519priv ‖ "oshi-sync-key")` |
 * | iOS | `V2SyncManager.swift` — **written, never called** | `MultiDeviceSyncManager.swift` — live |
 * | Android | **does not exist** (no `/v2/sync` anywhere in the tree) | `MultiDeviceSyncManager.kt:1163-1226` — live |
 *
 * "Written, never called" is not an inference from a failed grep. `OSHI/AUDIT_V2_2026-07.md:37`
 * states it in the shipped tree's own words: *"WIRING — send not hooked
 * (`MessageManager+V2.swift:125`), no receive/ack loop, **no `V2SyncManager.pull()` caller***".
 * A grep of `OSHI/` for `V2SyncManager` / `V2SyncAPI` / `v2/sync` outside the three files
 * that define them returns that audit line and two comments in `ContactActionsView.swift`
 * that merely cite the signing style. Nothing constructs it. Nothing pulls it.
 *
 * So: **on a real pair of shipped phones today, multi-device sync is the legacy
 * `/api/sync` archive and nothing else.** This package implements both, because the V2
 * archive is the better-designed one and the one a desktop client should push toward, but
 * it does not pretend they are interchangeable — see [Archive].
 *
 * ============================================================ WHAT IS AND IS NOT SYNCED
 *
 * The legacy archive has exactly FIVE live routes. Android verified them against the
 * production server read-only with `OPTIONS` and wrote the result down
 * (`MultiDeviceSyncManager.kt:93-113`): `/api/sync/{messages,groups,contacts,groupMessages,profile}/:key`
 * answer 200; the seven device-registry routes iOS also calls — `register`, `unregister`,
 * `message`, `read`, `pull`, `devices`, `remove-device`
 * (`MultiDeviceSyncManager.swift:220,261,296,319,349,402,427`) — **404 on the live server
 * for both GET and OPTIONS**. Those seven are dead code on iOS too: the call site exists,
 * the route does not. Android deliberately declines to implement them. This client does
 * the same, and [LegacyKind] is the enumeration of what is actually reachable.
 *
 * NOT synced by either archive, at all:
 *   - **scheduled messages** (PARITY.md row 0.25 — not a kind here, not in `/v2/sync`'s
 *     `SyncKind`, and neither `ScheduledMessageManager` ever calls a sync API),
 *   - **ratchet / session state** — deliberately: the archive is same-key-availability,
 *     the live path keeps its forward secrecy (`V2SyncModels.swift:14-20`),
 *   - **media bytes** — only a `{blobId,fileKey,…}` pointer, `kind: "file"`
 *     (`V2SyncModels.swift:159-189`); on the legacy side iOS caps a synced message body at
 *     ~4 MB with 1 MB of inline media (`MultiDeviceSyncManager.swift:718-719`),
 *   - **blocking**, on either platform — the finding this row exists to fix. See
 *     [ContactSyncRecord].
 *
 * And the legacy contacts route syncs **only aliases**: iOS uploads
 * `JSONEncoder().encode([String: String])` read straight out of the
 * `@AppStorage("contactAliases")` blob (`MultiDeviceSyncManager.swift:1007-1017`), and
 * Android mirrors that map exactly (`MultiDeviceSyncManager.kt:1227-1235`). No block
 * state, no verification state, no timestamps. A `publicKey → alias` dictionary is the
 * whole of it.
 *
 * ============================================================ PAIRING AND AUTHENTICATION
 *
 * Three different answers exist in the shipped trees, and two of them are "there isn't
 * any".
 *
 *  1. **V2 archive — Ed25519, owner-only.** Every route verifies that the signing pubkey
 *     equals the `userKey` in the path (`V2Client+Sync.swift:23-26`, server
 *     `sync_store.js → requireOwner → oshi_auth.verifyHttp`). There is no pairing step at
 *     all and none is needed: holding the identity private key IS being a device of that
 *     identity. [V2SyncClient] refuses to build a request for a foreign `userKey` for the
 *     same reason — see that class.
 *
 *  2. **Legacy archive — nothing.** `uploadBlob`/`pullBlob` are a bare OkHttp POST/GET
 *     with no signature and no `x-oshi-*` header of any kind
 *     (`MultiDeviceSyncManager.kt:1169-1226`); iOS's are a bare `URLSession.dataTask`
 *     (`MultiDeviceSyncManager.swift:1035-1041`). The path segment is the identity's own
 *     PUBLIC key. So anyone who knows a user's public key — which is exactly the thing
 *     handed out in a QR code, PARITY.md row 0.22 — can GET that user's archive blob and
 *     can POST over it. The blob is AES-256-GCM under a key derived from the private key,
 *     so its *confidentiality* survives; its *integrity and availability* do not. A
 *     stranger cannot read your contacts, but can replace them with garbage that every
 *     one of your devices then fails to decrypt. Android's own comment calls the pairing
 *     model out loud — *"Server-mediated sync needs no pairing step: sharing the recovery
 *     key IS the pairing"* (`MultiDeviceSyncManager.kt:302-304`).
 *
 *  3. **Android's peer-to-peer device link — a stub.** `🔗LINK🔗` + a 6-digit code with a
 *     5-minute expiry (`MultiDeviceSyncManager.kt:316-330`), exchanged over the ordinary
 *     message relay with `suppressPush = true`. Except `linkDevice()` builds the request
 *     JSON, logs it, and **never sends it** — `// Simulate success for now`, then
 *     `callback(true, …)` unconditionally (`:336-360`). So `_linkedDevices` is only ever
 *     populated by *receiving* a `link_request` that nothing emits, `sendSyncToLinkedDevices`
 *     fans out to an empty list, and `handleSyncData` drops every inbound sync as coming
 *     from an unlinked device (`:488-492`). The whole peer-to-peer half is inert in the
 *     shipped app. That matters for the block finding below: the defect is real in the
 *     source and unreachable in the binary, which is a stay of execution, not a fix.
 *
 * This client implements (1) and (2). It does not implement (3): a desktop cannot be
 * linked by a handshake no shipped client transmits, and building the UI for it would be
 * the same theatre Android declined to build for the 404 device registry.
 *
 * ============================================================ CONFLICT RESOLUTION
 *
 * Four different rules across the two archives, and they are not consistent:
 *
 *  - **V2 archive: append-only, apply-idempotent-on-itemId, ordered by `seq`.** The server
 *    assigns a monotonic `seq`; a re-push of an existing `itemId` is a no-op that echoes
 *    the existing seq (`V2SyncModels.swift:37-42`). A late-joining device replays the whole
 *    non-destructive log. For *mutable* kinds the guidance is last-writer-wins by `ts`
 *    (`V2SyncModels.swift:128-132`) — guidance only; nothing in `V2SyncManager` enforces it,
 *    because it hands the record to an apply-closure and the closure decides.
 *  - **Legacy contacts: gap-fill only, never overwrite.** Both platforms insert a pulled
 *    alias only where none exists locally (`MultiDeviceSyncManager.swift:634-640`,
 *    `.kt:1237-1254`). A rename therefore never propagates in either direction. That is
 *    not a bug so much as a refusal to have a conflict rule.
 *  - **Peer-to-peer contacts: per-contact last-writer-wins on `syncTimestamp`**
 *    (`MultiDeviceSyncManager.kt:858-905`) — the only place a real LWW rule exists. Its
 *    default is the flaw: an unstamped contact is sent with `syncTimestamp = now`
 *    (`:556,559`), so a contact nobody has ever edited always beats the peer's stamp.
 *  - **Profile links: last-writer-wins on `updatedAt`** (`:933-990`).
 *
 * [SyncEngine] implements the V2 rule, which is the only one of the four that is
 * well-defined for a device joining late, and states the tie-break it applies on top.
 *
 * ============================================================ EPOCHS
 *
 * Two of the project's four (PARITY.md row 0.18, [com.oshi.desktop.msg.WireClock]) appear
 * in this row, one per archive, and they are not the same one:
 *
 *   - **V2 archive `SyncItem.ts` is Unix MILLIS** — `Int64(Date().timeIntervalSince1970 * 1000)`,
 *     stated as "client ms-since-epoch (opaque to server)" (`V2SyncModels.swift:48,54`).
 *   - **Legacy archive record bodies are Apple-epoch SECONDS**, because iOS serialises
 *     `SecureMessage`'s `Date` fields with a bare `JSONEncoder` (`.deferredToDate`), which
 *     Android had to reproduce by hand and documented at length
 *     (`MultiDeviceSyncManager.kt:117-131`: `APPLE_EPOCH_OFFSET_SECONDS = 978_307_200`,
 *     *"Encoding epoch millis here instead would land every timestamp in the archive
 *     somewhere in the year 32000 on iOS"*).
 *
 * Every conversion in this package goes through [com.oshi.desktop.msg.WireClock] and there
 * is no second converter here — that constant is restated in at least five places across
 * the shipped trees and this client keeps it in exactly one.
 */
object SyncProtocol {

    /**
     * Which archive a call targets. Not a config flag with a sensible default — a caller
     * has to say, because the two disagree about the route, the key, the auth and the
     * shape, and a wrong guess is not an error, it is a silent write to a storage slot no
     * other device of the same identity will ever read.
     */
    enum class Archive {
        /** `/v2/sync/:userKey` — signed, owner-only, append-only log. iOS-only, unwired. */
        V2,

        /** `/api/sync/{kind}/{key}` — unauthenticated whole-blob overwrite. What ships. */
        LEGACY,
    }

    /**
     * The five reachable legacy routes. `profile` and `groupMessages` are listed because
     * they answer 200, not because this client uses them yet.
     *
     * The seven device-registry routes are deliberately absent — see the class doc.
     */
    enum class LegacyKind(val wire: String) {
        MESSAGES("messages"),
        GROUPS("groups"),
        CONTACTS("contacts"),
        GROUP_MESSAGES("groupMessages"),
        PROFILE("profile"),
    }

    /**
     * The `kind` tag on a V2 archive item. Wire strings from `V2SyncModels.swift:117-124`.
     *
     * [UNKNOWN] is a decode sentinel and is **never pushed** — a newer client's kind must
     * not crash an older one, so an unrecognised tag decodes to [UNKNOWN] with the raw
     * string preserved on the record and the applier skips it. [encodeKind] enforces the
     * "never pushed" half; a mutation of it is one of this row's guard tests.
     */
    enum class SyncKind(val wire: String) {
        MESSAGE("msg"),
        READ_STATE("read"),
        CONTACT("contact"),
        GROUP("group"),
        FILE("file"),
        UNKNOWN("unknown");

        companion object {
            fun fromWire(raw: String): SyncKind = entries.firstOrNull { it.wire == raw } ?: UNKNOWN
        }
    }

    /**
     * The V2 path encoding: percent-encode everything that is not ASCII alphanumeric, so
     * `+`, `/` and `=` in a base64 identity survive routing. Byte-identical to
     * `V2Client+Sync.swift:138-140` (`addingPercentEncoding(withAllowedCharacters: .alphanumerics)`)
     * and therefore to [DesktopV2Signer.encodeIdentity], which is where it comes from —
     * this is a delegation, not a copy, because a second implementation of the single
     * most copy-sensitive function in the protocol is how the two drift apart.
     */
    fun v2PathKey(userKey: String): String = DesktopV2Signer.encodeIdentity(userKey)

    /**
     * The LEGACY path encoding, which is a completely different function: base64url with
     * padding stripped (`MultiDeviceSyncManager.swift:449-454`, `.kt:141-143`).
     *
     * Android's own test states why this may not be swapped for percent-encoding: *"a
     * percent-encoded key hits a DIFFERENT storage slot than the iPhone's and the two
     * devices silently never see each other's archive"* (`.kt:136-140`), asserted in
     * `SettingsSyncWireFormatTest.percent encoding would target a different storage slot`.
     * There is no error, no 404 and no log line — both devices sync happily to two slots.
     *
     * Note this is NOT a base64url re-encoding of the decoded bytes: it is a character
     * substitution on the base64 TEXT, so a key that was never valid base64 passes through
     * unchanged. That is what the shipped clients do and this reproduces it exactly.
     */
    fun legacyPathKey(publicKey: String): String =
        publicKey.replace("+", "-").replace("/", "_").replace("=", "")

    /**
     * `POST /v2/sync/:userKey` body: `{"items":[…]}`, one object per item.
     *
     * Key order is the declaration order of `V2.SyncItem` (`V2SyncModels.swift:43-49`):
     * `itemId, kind, ciphertext, deviceId, ts` — Swift's synthesised `CodingKeys` emit in
     * property order and `org.json`'s `JSONObject` does not preserve insertion order on
     * this JVM, so the object is built by hand rather than through a map. It is not a
     * correctness requirement (the server parses JSON, not a byte string), but a payload
     * that diffs cleanly against a captured iOS one is worth the six lines; PLAN.md §4.3
     * makes the same argument for the relay envelope.
     */
    fun encodePushBody(items: List<SyncItem>): ByteArray {
        require(items.isNotEmpty()) { "no items to push (V2Client+Sync.swift:80 refuses this too)" }
        val sb = StringBuilder(64 * items.size)
        sb.append("{\"items\":[")
        items.forEachIndexed { i, it ->
            if (i > 0) sb.append(',')
            sb.append("{\"itemId\":").append(JSONObject.quote(it.itemId))
                .append(",\"kind\":").append(JSONObject.quote(it.kind))
                .append(",\"ciphertext\":").append(JSONObject.quote(it.ciphertext))
                .append(",\"deviceId\":").append(JSONObject.quote(it.deviceId))
                .append(",\"ts\":").append(it.tsUnixMillis)
                .append('}')
        }
        sb.append("]}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * The legacy upload envelope: `{"deviceId":…,"data":<base64 AES-GCM>}`
     * (`MultiDeviceSyncManager.swift:1021-1024`, `.kt:146-151`). Two keys, and the
     * ciphertext field is called `data` here while the peer-to-peer envelope calls the
     * same thing `encryptedData` (`.kt:1412-1425`). They are different envelopes on
     * different transports and neither reads the other's field name.
     */
    fun encodeLegacyUploadBody(deviceId: String, ciphertextB64: String): ByteArray =
        JSONObject().put("deviceId", deviceId).put("data", ciphertextB64)
            .toString().toByteArray(Charsets.UTF_8)

    /**
     * The `itemId` namespacing rule: `"<kind>:<logicalId>"` (`V2SyncManager.swift:311`).
     *
     * `itemId` is the server's idempotency key and it is global per identity, not per
     * kind, so two kinds using the same logical id would collide and the second push
     * would be silently discarded as a duplicate — the server answers with the EXISTING
     * seq and looks like a success. The prefix is what stops that.
     */
    fun namespacedItemId(kind: SyncKind, logicalId: String): String {
        require(logicalId.isNotBlank()) { "logical itemId must not be blank" }
        return "${encodeKind(kind)}:$logicalId"
    }

    /**
     * The wire tag for an OUTBOUND item.
     *
     * Refuses [SyncKind.UNKNOWN]. `V2SyncModels.swift:123` documents that sentinel as
     * "forward-compat sentinel (never pushed)" and nothing on iOS enforces it, because on
     * iOS the enum is only ever constructed by the caller. Here the same value can arrive
     * from [SyncKind.fromWire] on the RECEIVE path and be handed straight back to a
     * re-push by an applier that echoes what it read — at which point a peer's
     * unrecognised record has been laundered into a record this client claims to have
     * authored, under a kind literally spelled `unknown`. One `require` is cheaper than
     * the archive entry that would result.
     */
    fun encodeKind(kind: SyncKind): String {
        require(kind != SyncKind.UNKNOWN) {
            "SyncKind.UNKNOWN is a decode sentinel and must never be pushed " +
                "(V2SyncModels.swift:123). An unrecognised inbound kind is skipped, not echoed."
        }
        return kind.wire
    }

    /** One archive item as PUSHED. `ts` is Unix millis — see the class doc's EPOCHS. */
    data class SyncItem(
        val itemId: String,
        val kind: String,
        val ciphertext: String,
        val deviceId: String,
        val tsUnixMillis: Long,
    )

    /** One archive item as PULLED — the server merges its monotonic `seq` in. */
    data class PulledItem(
        val seq: Int,
        val itemId: String,
        val kind: String,
        val ciphertext: String,
        val deviceId: String,
        val tsUnixMillis: Long,
    )

    /** `{accepted:[{itemId,seq}], maxSeq}` — `V2SyncModels.swift:79-88`. */
    data class PushResult(val accepted: Map<String, Int>, val maxSeq: Int)

    /** `{items:[…], maxSeq}` — `V2SyncModels.swift:91-94`. */
    data class PullResult(val items: List<PulledItem>, val maxSeq: Int)

    /** `{maxSeq, count}` — `V2SyncModels.swift:96-101`. */
    data class Head(val maxSeq: Int, val count: Int)

    /** `{trimmed, remaining}` — `V2SyncModels.swift:103-107`. */
    data class CheckpointResult(val trimmed: Int, val remaining: Int)

    /**
     * A decrypted inbound record handed to an applier. `payload` is bytes, not a String,
     * for the reason `V2Http.getBytes` gives: a record body may be any encoding, and
     * round-tripping unknown bytes through UTF-8 is lossy.
     */
    data class InboundRecord(
        val kind: SyncKind,
        val rawKind: String,
        val itemId: String,
        val seq: Int,
        val deviceId: String,
        val tsUnixMillis: Long,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is InboundRecord && itemId == other.itemId && seq == other.seq &&
                rawKind == other.rawKind && deviceId == other.deviceId &&
                tsUnixMillis == other.tsUnixMillis && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * (31 * itemId.hashCode() + seq) + rawKind.hashCode()
    }

    // ------------------------------------------------------------------ response parsing

    /**
     * Parse a `/v2/sync` pull page.
     *
     * A malformed ITEM costs that item, not the page — same rule as row 0.10's envelope
     * pull ("one bad envelope costs one message, not the response"). A malformed PAGE is
     * a null: a failed pull must never be indistinguishable from an empty archive, which
     * is the mistake PARITY.md row 0.10 records for `/v2/messages`.
     */
    fun parsePullResult(body: String): PullResult? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val arr = o.optJSONArray("items") ?: return null
        val out = ArrayList<PulledItem>(arr.length())
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val seq = if (it.has("seq")) it.optInt("seq", -1) else -1
            val itemId = it.optString("itemId", "")
            val ct = it.optString("ciphertext", "")
            if (seq < 0 || itemId.isEmpty() || ct.isEmpty()) continue
            out.add(
                PulledItem(
                    seq = seq,
                    itemId = itemId,
                    kind = it.optString("kind", SyncKind.UNKNOWN.wire),
                    ciphertext = ct,
                    deviceId = it.optString("deviceId", ""),
                    tsUnixMillis = it.optLong("ts", 0L),
                )
            )
        }
        return PullResult(out, o.optInt("maxSeq", out.maxOfOrNull { it.seq } ?: 0))
    }

    fun parseHead(body: String): Head? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!o.has("maxSeq")) return null
        return Head(o.optInt("maxSeq", 0), o.optInt("count", 0))
    }

    fun parsePushResult(body: String): PushResult? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val arr = o.optJSONArray("accepted") ?: return null
        val acc = LinkedHashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val id = a.optString("itemId", "")
            if (id.isNotEmpty()) acc[id] = a.optInt("seq", 0)
        }
        return PushResult(acc, o.optInt("maxSeq", 0))
    }

    fun parseCheckpointResult(body: String): CheckpointResult? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!o.has("trimmed")) return null
        return CheckpointResult(o.optInt("trimmed", 0), o.optInt("remaining", 0))
    }

    /**
     * The legacy pull body: `{"success":true,"data":<base64|null>}`. `"data":null` is not
     * an error — it is the server saying nothing is stored yet, and Android counts it as a
     * reached-the-server (`MultiDeviceSyncManager.kt:1210-1219`). The string `"null"` is
     * checked as well as the JSON null because that is what Android checks, and it checks
     * it because the server has produced both.
     */
    fun parseLegacyPullData(body: String): String? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val d = o.optString("data", "")
        return if (d.isEmpty() || d == "null") null else d
    }
}
