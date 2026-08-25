package com.oshi.desktop.msg

/**
 * Delivery and read receipts — the two halves of PARITY.md row 0.18 that decide whether a
 * tick is grey, doubled, or blue.
 *
 * ============================================================ THE WIRE, EXACTLY
 *
 * **Delivery receipt.** Not JSON. The whole payload is the sentinel followed by the bare
 * message id:
 *
 *     📬DELIVERY_RECEIPT📬1B2C3D4E-5F60-7182-9394-A5B6C7D8E9F0
 *
 * iOS parses it by deleting the prefix and trimming whitespace
 * (`MessageManager.parseDeliveryReceiptMessageId`, `swift:1475-1479`) and uses the
 * remainder AS the id. There is no timestamp, no sender field and no signature — the
 * transport already authenticated the sender, and the receipt means nothing except "this
 * id arrived."
 *
 * Android used to send `{"messageId":"…"}` here, and the bug that produced is worth keeping
 * in view because it is the shape of every parsing failure in this project: iOS did not
 * throw. It stripped the prefix, got the literal string `{"messageId":"…"}` as the id,
 * failed to match any message, and **fell back to marking the OLDEST unacked message
 * delivered** — the wrong bubble, and only visibly wrong when the user sent several
 * messages quickly. `MessageRepository.sendDeliveryReceipt` now sends the bare id and its
 * comment says exactly this (`:5259-5264`). We emit the bare id and ACCEPT both.
 *
 * **Read receipt.** The two platforms disagree, and this is the row's clearest example of
 * "both are live, pick one to emit and accept the other":
 *
 *     iOS      📖READ_RECEIPT📖                                     (bare prefix, no body)
 *     Android  📖READ_RECEIPT📖{"senderPublicKey":"…","timestamp":<apple-epoch>}
 *
 * iOS emits the bare prefix (`MessageManager.swift:7425`, sending literally
 * `"📖READ_RECEIPT📖"`) and its receiver takes only the transport-level sender
 * (`handleIncomingReadReceipt(from:)`) — it never looks at a body. Android emits the JSON
 * (`MessageRepository.kt:5283-5290`) and its receiver, `handleReadReceipt`, also ignores
 * the body: both platforms mark EVERY unread outgoing message to that peer as read. So
 * the body is, today, decorative on both sides.
 *
 * **We emit Android's JSON form.** Working rule 2 says the stricter option wins, and here
 * the stricter option is the one that survives a reader that actually parses: a client that
 * runs `JSONDecoder` over the body chokes on the bare form and cannot choke on the JSON
 * form, while the reverse — a client doing `content == "📖READ_RECEIPT📖"` exact equality —
 * does not exist on either platform (both use `hasPrefix`/`startsWith`, verified at
 * `MessageManager.swift:5905` and `MessageRepository.kt:2956`). It also carries the two
 * facts a desktop client will want the moment read receipts stop being all-or-nothing.
 * Both forms parse on ingest, and an empty body yields a payload with null fields rather
 * than a parse failure.
 *
 * ============================================================ THE EPOCH
 *
 * The read receipt's `timestamp` is **Apple-epoch seconds** — Android writes it as
 * `(System.currentTimeMillis() / 1000.0) - 978307200.0` inline at `MessageRepository.kt:5286`.
 * See [WireClock] for the other three epochs this row lives among.
 *
 * ============================================================ THE OTHER DELIVERY RECEIPT
 *
 * There is a SECOND, unrelated thing also called a delivery receipt in this codebase, and
 * confusing the two would be easy: `OSHI/DeliveryReceiptManager.swift` defines
 * `struct DeliveryReceipt { type, messageId, ipfsHash, recipientKey, senderKey, timestamp,
 * signature }`, AES-GCM-sealed under `SHA256(sharedSecret)`, pinned to IPFS, and announced
 * through `POST /api/queue/<base64url key>/<hash>?nopush=1`. That is the LEGACY IPFS
 * unpinning protocol — its job is to tell the sender it is safe to unpin content from
 * IPFS, not to colour a tick — and it belongs to PARITY.md row 0.23, not this one. It is
 * named here so that a future reader looking for "the delivery receipt struct" finds this
 * paragraph instead of implementing the wrong protocol.
 *
 * Two details of it are worth carrying forward even though the code is not ported:
 *  - its `timestamp` is a Swift `Date` under a bare encoder, so **Apple epoch** again,
 *    while the IPFS envelope it travels in uses **Unix seconds** (`MessageManager.swift:535`)
 *    — two of [WireClock]'s four epochs, one nesting level apart;
 *  - its notify URL is one of the exactly three legitimate uses of base64url in this
 *    project (PLAN.md §4.1 names it: "the legacy delivery-receipt notify path"). base64url
 *    must never appear in an envelope, an identity, a header or a payload body.
 */
object DeliveryReceipt {

    /**
     * `📬DELIVERY_RECEIPT📬<messageId>` — the exact bytes both shipped clients emit today.
     *
     * [messageId] is written verbatim, not JSON-escaped, because this payload is not JSON.
     * A message id in this system is a UUID string or a relay `msgId`; a blank one is
     * rejected here rather than producing a receipt that acks nothing and, on iOS, silently
     * falls back to acking the oldest unacked message instead.
     */
    fun encode(messageId: String): String {
        require(messageId.isNotBlank()) { "delivery receipt with a blank messageId acks the wrong message on iOS" }
        return ControlPrefix.DELIVERY_RECEIPT + messageId
    }

    /**
     * The message id a delivery receipt acks, or null when [text] is not one.
     *
     * Accepts the bare-id form (iOS, current Android) and the legacy `{"messageId":"…"}`
     * form (older Android) — `handleDeliveryReceipt` at `MessageRepository.kt:2687` accepts
     * both for exactly the same reason: those builds are on devices today.
     *
     * Returns null for an EMPTY remainder rather than "". Very old peers send the bare
     * prefix with no id at all; iOS's own comment calls that "a legacy bare-prefix receipt"
     * and returns `""`. Null is the same statement in a form a Kotlin caller cannot
     * accidentally use as an id — and the caller must then fall back to its own policy
     * (this client's is: do nothing, see [ControlPayloadRouter]).
     */
    fun decode(text: String): String? {
        if (ControlPrefix.match(text) != ControlPrefix.DELIVERY_RECEIPT) return null
        val body = ControlPrefix.strip(text).trim()
        if (body.isEmpty()) return null
        if (body.startsWith("{")) {
            val id = ControlRead.obj(body)?.let { ControlRead.str(it, "messageId") }
            return id?.takeIf { it.isNotBlank() }
        }
        return body
    }
}

/**
 * A read receipt's body. Both fields are nullable because iOS sends neither.
 *
 * See [DeliveryReceipt]'s doc comment for the full wire description and for why this client
 * emits the JSON form.
 */
data class ReadReceipt(
    /**
     * The peer whose messages have been read, as the SENDER of the receipt spells it.
     *
     * Android puts the RECIPIENT's key here — `sendReadReceipt(senderPublicKey)` puts its
     * argument, which is the peer being told, not the reader (`MessageRepository.kt:5284`).
     * That reads like a bug and behaves like a no-op, because neither platform consumes the
     * field. It is preserved rather than "corrected" because correcting it would be a
     * unilateral wire change no shipped client asked for, and the honest place for the
     * observation is this comment.
     */
    val senderPublicKey: String?,
    /** Apple-epoch seconds as they appeared on the wire, unconverted. */
    val appleTimestamp: Double?,
    /** [appleTimestamp] as Unix millis, or null when absent or failing [WireClock]'s guard. */
    val timestampMs: Long?,
) {
    companion object {

        /**
         * `📖READ_RECEIPT📖{"senderPublicKey":"…","timestamp":<apple-epoch>}` — Android's
         * key order (`MessageRepository.kt:5284-5287`), which is also the only order that
         * exists, since iOS has no struct for this payload.
         */
        fun encode(senderPublicKey: String, atUnixMillis: Long): String =
            ControlPrefix.READ_RECEIPT + ControlJson()
                .str("senderPublicKey", senderPublicKey)
                .num("timestamp", WireClock.toAppleSeconds(atUnixMillis))
                .build()

        /**
         * Decode either form, or null when [text] is not a read receipt at all.
         *
         * A bare prefix (iOS) and an unparseable body both yield a [ReadReceipt] with null
         * fields, NOT null: the receipt itself is the signal, and losing it because the
         * decoration was missing is precisely the failure mode this row exists to avoid.
         */
        fun decode(text: String): ReadReceipt? {
            if (ControlPrefix.match(text) != ControlPrefix.READ_RECEIPT) return null
            val body = ControlPrefix.strip(text).trim()
            val o = if (body.isEmpty()) null else ControlRead.obj(body)
            val ts = o?.let { ControlRead.num(it, "timestamp") }
            return ReadReceipt(
                senderPublicKey = o?.let { ControlRead.str(it, "senderPublicKey") },
                appleTimestamp = ts,
                timestampMs = ts?.let { WireClock.toUnixMillis(it) },
            )
        }
    }
}
