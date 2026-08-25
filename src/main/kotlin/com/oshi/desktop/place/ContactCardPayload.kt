package com.oshi.desktop.place

import com.oshi.desktop.msg.ControlJson
import com.oshi.desktop.msg.ControlRead
import com.oshi.desktop.msg.WireClock

/**
 * A shared contact card. PARITY.md row 0.19's third payload — and the one that does NOT
 * belong to [com.oshi.desktop.msg.ControlPrefix]'s dispatch at all.
 *
 * ============================================================ WHY IT IS NOT A SENTINEL
 *
 * Location and check-in ride the `content` string behind an emoji prefix, so they route
 * through the existing [com.oshi.desktop.msg.ControlPayloadRouter]. A contact card does
 * not. It is an ordinary MEDIA message whose `mediaType` is `contact` and whose attachment
 * BYTES are the JSON below — iOS builds it in `MediaManager.createContactCard`
 * (`OSHI/MediaManager.swift:867-897`) as a `MediaAttachment(type: .contact, data: …)` and
 * reads it back with `decodeContactCard(from:)` (`:901-908`). There is no `👤CONTACT👤`
 * prefix anywhere in either tree, and `ControlPrefix`'s catalog — which is complete —
 * does not list one.
 *
 * So this file is a pure codec with no router entry, and [PlaceRouter] deliberately does
 * not claim it. Wiring it up belongs to row 0.15 (blobs) and row 0.13 (the store), which
 * is where a `mediaType` is known; this row owns the bytes.
 *
 * ============================================================ THE WIRE, EXACTLY
 *
 *     {"publicKey":"<base64 X25519 identity>",
 *      "alias":"Alice",                       // OMITTED when absent or blank
 *      "timestamp":<apple-epoch seconds>}
 *
 * iOS's struct (`MediaManager.swift:913-917`):
 *
 *     struct ContactCard: Codable {
 *         let publicKey: String
 *         let alias: String?
 *         let timestamp: Date        // NOT optional
 *     }
 *
 * decoded by a **bare `JSONDecoder()`**. Two consequences, both of which Android
 * discovered the hard way and wrote down in `data/model/ContactCardWire.kt:30-44`:
 *
 * 1. **`timestamp` is required.** Android's `ContactPayload` never carried it, so every
 *    Android→iPhone card threw `keyNotFound` inside the `try?` at
 *    `MediaMessageView.swift:65` and the iPhone drew a blank `person.crop.circle`
 *    placeholder. [encode] always emits it and takes it as a required parameter.
 *
 * 2. **A bare `JSONDecoder` is `.deferredToDate`** — that `Date` is a JSON NUMBER of
 *    seconds since 2001-01-01, not an ISO-8601 string. Sending
 *    `"2026-08-24T09:00:00Z"` throws `typeMismatch` and sinks the card the same way the
 *    missing key did. Epoch 1 again; conversions through [WireClock], no local offset.
 *
 * The reverse direction had its own version of the same bug: Android's reader was
 * `Json.Default`, which has `ignoreUnknownKeys = false`, so iOS's *extra* `timestamp` blew
 * up the whole decode and the card fell through to a raw-text bubble. [decode] is lenient
 * in the way that file now is.
 *
 * `avatarUrl` exists in Android's in-memory `ContactPayload` (`Message.kt:106-111`) and is
 * deliberately NOT emitted — iOS has never heard of it, and Android's own encoder skips it
 * for that reason. [decode] reads it when present, because a peer running an older Android
 * build may still send it.
 *
 * ============================================================ THE vCARD SECOND SHAPE
 *
 * `ContactShareManager.exportToVCard` (`kt:34-40`) puts the same identity into a vCard for
 * the system share sheet, and a peer can forward that text straight back into a
 * conversation. [decode] understands it, which is what Android does. The identity lives in
 * the NOTE line and nowhere else:
 *
 *     BEGIN:VCARD / VERSION:3.0 / FN:<display name> / N:<last>;<first>;;;
 *     NOTE:OSHI Public Key: <base64>
 *     END:VCARD
 *
 * ============================================================ WHAT THIS DOES NOT DO
 *
 * It does not TRUST the card. A `publicKey` arriving inside a message is a claim made by
 * the sender about a THIRD party, which is the weakest provenance any key in this project
 * has — weaker than a QR scan (row 0.22, in person) and weaker than a `/v2/keys` fetch
 * (row 0.9, TOFU-pinned). Nothing here writes to
 * [com.oshi.desktop.store.ContactStore] and nothing here bypasses row 0.9's TOFU. Decoding
 * a card yields a candidate identity for a human to accept, and the refusal to make that
 * decision in a codec is the point.
 */
data class ContactCardPayload(
    /** The shared party's identity key, base64. Required, and never blank. */
    val publicKey: String,
    val alias: String? = null,
    /** Not on iOS's struct. Read when a legacy Android peer sends it; never emitted. */
    val avatarUrl: String? = null,
    /** Apple-epoch seconds as they arrived, or null for a vCard (which carries no time). */
    val appleTimestamp: Double? = null,
    /** [appleTimestamp] as Unix millis, or null. */
    val timestampMs: Long? = null,
) {
    /** iOS's `displayName` (`MediaManager.swift:918-928`): alias, else a 6…4 elision. */
    val displayName: String
        get() = alias ?: if (publicKey.length > 10) {
            "${publicKey.take(6)}...${publicKey.takeLast(4)}"
        } else {
            publicKey
        }

    companion object {

        /** The NOTE line prefix that carries the identity in a vCard. `kt:38`, `:144`. */
        const val VCARD_KEY_NOTE = "NOTE:OSHI Public Key:"

        /**
         * The exact JSON body an iPhone's `decodeContactCard` accepts.
         *
         * [timestampUnixMillis] is required with no default — see consequence 1 above. A
         * defaulted timestamp is exactly how kotlinx dropped the key on Android
         * (`ContactCardWire.kt:83-96`), so the parameter that must never be omitted is the
         * one with no way to omit it.
         */
        fun encode(publicKey: String, alias: String?, timestampUnixMillis: Long): String {
            require(publicKey.isNotBlank()) { CARD_REQUIRES_PUBLIC_KEY }
            val json = ControlJson().str("publicKey", publicKey)
            json.optional("alias", alias?.takeIf { it.isNotBlank() })
            return json.num("timestamp", WireClock.toAppleSeconds(timestampUnixMillis)).build()
        }

        /**
         * The one thing a card cannot be missing. Named so it can be grepped and so the
         * test that watches it fail has something to name.
         *
         * A card with no key names nobody. Both platforms treat it as fatal — iOS decodes
         * `publicKey` non-optionally, Android's `decode` returns null on a blank one
         * (`ContactCardWire.kt:118-119`) — and this is one of the rare places all three
         * clients already agree.
         */
        const val CARD_REQUIRES_PUBLIC_KEY: String =
            "a contact card with no publicKey identifies nobody"

        /**
         * Read a card from attachment bytes-as-text, whatever platform wrote it.
         *
         * JSON first (what both clients send today), vCard second (what the share sheet
         * produces). Null when it is neither, so a caller can fall back to a text bubble
         * rather than draw an empty card — Android's `ContactCardWire.decode` orders it the
         * same way and for the same reason (`kt:104-112`).
         */
        fun decode(raw: String): ContactCardPayload? {
            val trimmed = raw.trim()
            if (trimmed.startsWith("{")) {
                val o = ControlRead.obj(trimmed)
                if (o != null) {
                    val pk = ControlRead.str(o, "publicKey")?.takeIf { it.isNotBlank() }
                    if (pk != null) {
                        val ts = ControlRead.num(o, "timestamp")
                        return ContactCardPayload(
                            publicKey = pk,
                            alias = ControlRead.str(o, "alias")?.takeIf { it.isNotBlank() },
                            avatarUrl = ControlRead.str(o, "avatarUrl")?.takeIf { it.isNotBlank() },
                            appleTimestamp = ts,
                            timestampMs = ts?.let { LocationPayload.wireToUnixMillis(it) },
                        )
                    }
                }
                // Fall through: a JSON object with no usable key may still be nothing we
                // want, but a vCard never starts with `{`, so returning null here is safe.
                return null
            }
            if (!trimmed.contains("BEGIN:VCARD")) return null

            var pk: String? = null
            var name: String? = null
            for (line in trimmed.lines()) {
                val t = line.trim()
                when {
                    t.startsWith(VCARD_KEY_NOTE) -> pk = t.removePrefix(VCARD_KEY_NOTE).trim()
                    t.startsWith("FN:") -> name = t.removePrefix("FN:").trim()
                }
            }
            val key = pk?.takeIf { it.isNotBlank() } ?: return null
            return ContactCardPayload(publicKey = key, alias = name?.takeIf { it.isNotBlank() })
        }
    }
}
