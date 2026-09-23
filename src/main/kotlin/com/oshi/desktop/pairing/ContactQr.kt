package com.oshi.desktop.pairing

import java.util.Base64

/**
 * QR pairing / identity exchange — PARITY.md row 0.22.
 *
 * This file owns ONE question: what exact bytes does a shipped OSHI phone put inside a
 * contact QR code, and what does the scanning side do with them. Everything below was
 * read out of the two shipped trees, not inferred; the line references are load-bearing
 * and should be re-checked if either app changes.
 *
 * ============================================================ THE PAYLOAD
 *
 * **The QR payload is the identity public key as a bare string. Nothing else.** No JSON,
 * no version byte, no URL, no scheme, no prefix, no signature, no display name.
 *
 *   - iOS `QRCodeDisplayView.swift:272`: `filter.message = Data(self.publicKey.utf8)`
 *     with `filter.correctionLevel = "M"` and a 10× scale transform.
 *   - Android `QRCodeScreen.kt:63-70`: `generateQRCode(publicKey)` →
 *     `QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)`.
 *
 * That string is the account address, and in OSHI the address IS the X25519 identity:
 * 32 RAW public-key bytes, encoded with **STANDARD base64** — RFC 4648 §4, the `+` / `/`
 * alphabet, `=`-padded, no line wrapping. Android produces it at
 * `CryptoManager.kt:194` (`Base64.encodeToString(it, Base64.NO_WRAP)`); the literal in
 * iOS's own preview (`QRCodeDisplayView.swift:334`) is
 *
 *     kr/6aAArDrl91TOXf/bjv2u1SiRf+H8gIi8BBgPYpi0=
 *
 * — 44 characters, containing a `/`, a `+` and a trailing `=`. Those three characters are
 * the whole point: they are exactly the characters base64url does not have.
 *
 * ============================================================ base64url vs base64
 *
 * The ledger note for this row says "base64url lives here legitimately", and it does —
 * but only on ONE side of the exchange, and getting that backwards produces a code no
 * phone can use. The rule:
 *
 *   **EMIT standard base64. ACCEPT either alphabet.**
 *
 * - **Emission is standard base64, always.** No shipped path encodes a contact QR, a
 *   contact deep link or a relay `from`/`to` field in base64url. Every base64url emission
 *   in either tree is on an unrelated path where the key is a URL PATH SEGMENT and `/`
 *   would split it: `VPSClient.publicKeyToBase64url` for `/signals/<key>`
 *   (`VPSClient.kt:481-494`), `UDPRelayClient.base64urlEncode` (`:273-277`),
 *   `MultiDeviceSyncManager` for `/api/sync/<kind>/<key>` (`:135-142`), and Android's
 *   navigation-route helper `encodeKeyForNav` (`MainActivity.kt:101-106`) — which is not
 *   even the key's alphabet, it is base64url OF THE UTF-8 OF the standard-base64 string.
 *   None of those is a QR code.
 * - **Acceptance folds base64url back to standard**, because a key can reach a client
 *   through one of those paths and be pasted into the scan field. Android's
 *   `CryptoManager.importPublicKey` (`:200-212`) folds `-`→`+`, `_`→`/` and re-pads
 *   before decoding, and both platforms' `canonicalIdentity` does the same
 *   (`MessageManager.swift:6246-6261`, `MessageRepository.kt:255-285`).
 *
 * Why emitting base64url would be a silent, expensive failure rather than a cosmetic one:
 * the V2 X3DH responder check compares the initiator's identity key to the envelope's
 * `from` as an **exact wire string, with no normalisation** — see the TOFU block in
 * `V2Router.establishResponder`, and the identical rule stated in
 * `MessageRepository.kt:252-254` ("Deliberately NOT applied to the V2 X3DH identity
 * binding … those compare the exact wire string on both platforms and must stay strict").
 * A peer added under a base64url spelling therefore fetches a bundle fine, sends fine,
 * and then fails TOFU forever on the first inbound message, with an "impersonation"
 * error naming a contact who did nothing wrong. [canonicalAddress] is applied at PARSE
 * time precisely so that spelling never reaches the session layer.
 *
 * ============================================================ THE OTHER TWO FORMS
 *
 * A scanner also has to survive two strings that are NOT the QR payload but routinely end
 * up in front of it, because both apps put them on the clipboard and in share sheets:
 *
 * 1. **The add-contact deep link.** `https://oshi-messenger.org/add?key=<key>`, plus the
 *    custom-scheme `oshi://add?key=<key>`. Accepted hosts are `oshi-messenger.com`,
 *    `oshi-messenger.org` and their `www.` forms (`OSHI.entitlements:13-17`,
 *    `SharedContentHandler.swift:64-65`), `https` only. The parameter is `key`, with `k`
 *    accepted as a short alias.
 * 2. **The legacy Android contact QR**, `oshi:<key>` — no `//`. Android emitted this
 *    until the fix recorded at `QRCodeScreen.kt:63-70`; iOS's scanner never stripped it,
 *    so every iPhone that scanned an Android code got `oshi:<base64>` as the peer address
 *    and could never message that person. Android still accepts it
 *    (`NewMessageScreen.kt:89`) so printed and screenshotted codes keep working.
 *
 * ============================================================ WHERE THEY DISAGREE
 *
 * Recorded rather than smoothed over, per PLAN/PARITY working rule 2. In each case the
 * desktop takes the STRICTER side, named here.
 *
 * | # | iOS | Android | desktop |
 * |---|-----|---------|---------|
 * | 1 | error correction **M** (`QRCodeDisplayView.swift:271`) | ZXing default, **L** (`QRCodeScreen.kt:294`) | **M** — more redundancy, and a code a phone can still read when it is half-covered by a thumb |
 * | 2 | deep link percent-encodes with `.urlQueryAllowed` (`QRCodeDisplayView.swift:41`), which does **not** escape `+`, `/` or `=` — the key goes on the wire raw | `URLEncoder.encode(key,"UTF-8")` (`QRCodeScreen.kt:186`) escapes them to `%2B` / `%2F` / `%3D` | **percent-encoded** — a raw `+` in a query is ambiguous with a space under form-encoding, and Android's own `DeepLinkParserTest` has a test for each form because both are in the wild |
 * | 3 | scan path does **no host check at all** (`MessagesListView.swift:472-480` parses any URL with a `key=`) | `DeepLinkParser` rejects foreign hosts, including `oshi-messenger.com.evil.com` and `oshi-messenger.org@evil.com` | **host-checked** — a QR is an untrusted string from a stranger's screen; iOS's omission means a printed `https://evil.com/add?key=<attacker key>` is accepted |
 * | 4 | validation is `count >= 20 && != myPublicKey` (`NewMessageView.swift:124`, `MessagesListView.swift:218`) | identical: `length >= 20 && != myPublicKey` (`NewMessageScreen.kt:153`) | **stricter than both**: must decode to exactly 32 bytes. The phones agree with each other and are both too weak — 20 characters of anything passes, and the failure then surfaces much later as `require(keyBytes.size == 32)` inside `importPublicKey`, or as a session that can never be established |
 * | 5 | `normalizeScannedContactPayload` is Android-only; it runs `URLDecoder.decode` over the whole query value (`NewMessageScreen.kt:83-86`), which turns a `+` into a SPACE — so it corrupts exactly the link iOS emits (see row 2) | Android's other parser, `DeepLinkParser`, decodes `%XX` only and keeps `+` (`DeepLinkParserTest`: "literal plus stays a plus") | **`%XX` only** — the two Android parsers contradict each other; the one with the test is right |
 *
 * ============================================================ VALIDATION: WHAT IS AND IS NOT CHECKED
 *
 * There is **no signature on a contact QR, and no safety-number confirmation at scan
 * time**, on either platform. The QR carries a bare public key; nothing signs it and
 * nothing proves the person holding the phone owns the private half. Scanning is a
 * trust-on-first-use gesture whose security comes from the physical channel — you are
 * looking at their screen. The cryptographic binding happens later and elsewhere:
 *
 *   - the signed-prekey signature check in `V2KeysClient` when the bundle is fetched;
 *   - the responder-side TOFU check in `V2Router.establishResponder`, which is what
 *     actually enforces "the address IS the identity";
 *   - safety numbers (PARITY.md row 0.20), which are how a user CONFIRMS the binding
 *     after the fact, and which no scan path consults.
 *
 * Saying that plainly matters more than implementing something extra here: a reader who
 * assumes the QR is authenticated will put weight on it that it cannot carry.
 *
 * ============================================================ DESKTOP SCOPE — NO CAMERA
 *
 * **This client has no live camera.** [QrImageDecoder] reads a user-selected PNG/JPEG
 * screenshot or photo through ZXing, with an explicit pixel bound; the New conversation
 * screen exposes that path. There is still no `AVCaptureMetadataOutput` equivalent or live
 * webcam integration over V4L2 / Media Foundation / AVFoundation, which would require a
 * separate native capability on each desktop OS.
 *
 * The desktop-side contract is therefore two halves of the exchange, not three:
 *
 *   - **DISPLAY**: [payloadFor] gives the exact string to encode, and [QrMatrix] turns it
 *     into modules a phone camera can read (terminal text or PNG). This is the direction
 *     that matters, because it is the one where a wrong byte is invisible until someone
 *     points a phone at the screen.
 *   - **PARSE**: [parse] takes an already-decoded string — pasted from a phone's "Copy key"
 *     button, typed in, dropped in from a share sheet, or returned by [QrImageDecoder] — and
 *     returns either a canonical address or a named reason it was refused. Both phones offer
 *     this manual-entry path next to their scanner (`QRScannerView.swift:76-99`,
 *     `NewMessageScreen.kt` "Paste Key").
 */
object ContactQr {

    /** `QRCodeDisplayView.swift:16`, `QRCodeScreen.kt:188`, `NewMessageScreen.kt:814`. */
    const val DEEP_LINK_BASE: String = "https://oshi-messenger.org/add"

    /** `OSHI.entitlements:13-17` + `SharedContentHandler.swift:64-65`, lowercased. */
    val ACCEPTED_HOSTS: Set<String> = setOf(
        "oshi-messenger.com", "www.oshi-messenger.com",
        "oshi-messenger.org", "www.oshi-messenger.org",
    )

    /** Raw X25519 public key length. `CryptoManager.kt:207` requires exactly this. */
    const val IDENTITY_KEY_BYTES: Int = 32

    /** The brackets iOS wraps a shared key in — `NewMessageView.swift:726`. See [parse]. */
    const val OPEN_BRACKET: Char = '⟦'   // ⟦
    const val CLOSE_BRACKET: Char = '⟧'  // ⟧

    // ------------------------------------------------------------------ display side

    /**
     * The exact string to put inside the QR code for [address].
     *
     * Returns the address verbatim once it has been proved to be a real identity key —
     * no prefix, no URL, no wrapper. Anything else would be a code the iPhone in front of
     * you cannot use, which is the failure this whole file exists to prevent.
     *
     * @throws IllegalArgumentException when [address] is not a 32-byte key. A QR that
     *   encodes a malformed address is worse than no QR: it scans cleanly and then fails
     *   deep inside the other phone's session setup.
     */
    fun payloadFor(address: String): String {
        val canonical = canonicalAddress(address)
        require(canonical != null) {
            "refusing to encode a QR for a string that is not a 32-byte identity key: " +
                "'${address.take(24)}${if (address.length > 24) "…" else ""}'"
        }
        return canonical
    }

    /**
     * The shareable add-contact link. Percent-encoded — see disagreement 2 in the class
     * note; both phones' parsers accept this form, only one of them emits it.
     */
    fun deepLinkFor(address: String): String = "$DEEP_LINK_BASE?key=${percentEncodeKey(payloadFor(address))}"

    /**
     * The share-sheet text, ported from `NewMessageView.swift:720-729`.
     *
     * The brackets are not decoration. Pasted into a chat app, a trailing base64 key gets
     * latched onto by iOS's data detectors and a unit conversion appended — observed in
     * the wild as `…muFE3A=0.0741 acre`, a silently wrong key with no error. Two defences,
     * both from the shipped comment: the key sits between markers so a detector has a
     * boundary, and something follows it so it is never the final token.
     */
    fun shareText(address: String): String {
        val key = payloadFor(address)
        return buildString {
            append("Add me on OSHI!\n\n")
            append(deepLinkFor(address)).append("\n\n")
            append("My public key:\n")
            append(OPEN_BRACKET).append(key).append(CLOSE_BRACKET).append("\n\n")
            append("Copy everything between the brackets.")
        }
    }

    // ------------------------------------------------------------------ parse side

    /** What [parse] concluded about a scanned/pasted string. */
    sealed interface Scan {
        /** A usable peer address, already canonical: standard base64, padded, 32 bytes. */
        data class Contact(val address: String) : Scan

        /** Not usable. [reason] is for code, [detail] for a log line or a toast. */
        data class Rejected(val reason: Reason, val detail: String) : Scan
    }

    enum class Reason {
        /** Nothing, or only whitespace. */
        EMPTY,

        /** A link on a host OSHI does not own — see disagreement 3. */
        FOREIGN_HOST,

        /** Decoded to something other than 32 bytes, or is not base64 at all. */
        NOT_AN_IDENTITY_KEY,

        /**
         * Our own address. Both phones refuse this (`NewMessageView.swift:124`,
         * `NewMessageScreen.kt:153`) and they are right to: a conversation with yourself
         * has no second ratchet end, so it would fail at X3DH with a confusing error.
         */
        OWN_KEY,
    }

    /**
     * Parse an already-decoded QR / pasted string into a peer address.
     *
     * @param myAddress this account's address, so a self-scan is refused. Pass null only
     *   where the identity genuinely is not known yet.
     *
     * The order of the steps below is copied from the shipped parsers and is not
     * arbitrary — the URL branch has to run before the bracket branch (iOS
     * `MessagesListView.swift:474-496`), because a share text contains both and only the
     * bracket form survives the newlines.
     */
    fun parse(raw: String?, myAddress: String? = null): Scan {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return Scan.Rejected(Reason.EMPTY, "empty payload")

        // 1. A URL with a key parameter. Swift's `URL(string:)` rejects a string with
        //    whitespace, so the share text (which has newlines) falls through to step 2
        //    exactly as it does on iOS — the whitespace test is what reproduces that.
        if (trimmed.none { it.isWhitespace() } && trimmed.contains("?")) {
            when (val fromUrl = keyFromUrl(trimmed)) {
                is UrlKey.Found -> return finish(fromUrl.value, myAddress)
                is UrlKey.ForeignHost -> return Scan.Rejected(
                    Reason.FOREIGN_HOST,
                    "link host '${fromUrl.host}' is not an OSHI host",
                )
                UrlKey.NotAUrl -> Unit   // fall through
            }
        }

        // 2. ⟦key⟧ anywhere inside a longer paste — people paste the whole share message.
        val open = trimmed.indexOf(OPEN_BRACKET)
        val close = trimmed.lastIndexOf(CLOSE_BRACKET)
        if (open in 0 until close) {
            val inner = trimmed.substring(open + 1, close).trim()
            if (inner.isNotEmpty()) return finish(inner, myAddress)
        }

        // 3. Legacy Android contact QR: `oshi:<key>`, no slashes. `oshi://…` is a deep
        //    link and was already handled in step 1.
        val unprefixed = if (trimmed.startsWith("oshi:") && !trimmed.startsWith("oshi://")) {
            trimmed.removePrefix("oshi:")
        } else {
            trimmed
        }

        // 4. Trailing junk glued onto an UNWRAPPED paste by a data detector. A key ends
        //    at its last `=`; a tail is only cut when it is clearly not key material,
        //    because a real key never contains a space or a comma
        //    (`MessagesListView.swift:501-508`).
        val detrailed = stripDetectorTail(unprefixed)

        return finish(detrailed, myAddress)
    }

    private fun finish(candidate: String, myAddress: String?): Scan {
        val canonical = canonicalAddress(candidate)
            ?: return Scan.Rejected(
                Reason.NOT_AN_IDENTITY_KEY,
                "'${candidate.take(24)}${if (candidate.length > 24) "…" else ""}' is not a " +
                    "$IDENTITY_KEY_BYTES-byte base64 key",
            )
        if (myAddress != null && canonicalAddress(myAddress) == canonical) {
            return Scan.Rejected(Reason.OWN_KEY, "that is this account's own address")
        }
        return Scan.Contact(canonical)
    }

    // ------------------------------------------------------------------ key shapes

    /**
     * Fold a key into the one spelling everything downstream may store and compare:
     * standard base64, correctly padded, decoding to exactly [IDENTITY_KEY_BYTES].
     *
     * Port of `canonicalIdentity` (`MessageManager.swift:6246-6261`,
     * `MessageRepository.kt:255-285`) with ONE deliberate difference: those two return
     * their input unchanged when it does not decode, because they also carry pseudo-keys
     * like `lora!<nodehex>` through the same funnel. Here a non-key is a REFUSAL, because
     * this function's only callers are deciding whether to trust a stranger's string.
     *
     * @return the canonical address, or null when the input is not an identity key.
     */
    fun canonicalAddress(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        var s = trimmed.replace('-', '+').replace('_', '/').trimEnd('=')
        // Re-pad. A base64 body whose length % 4 == 1 can never be valid; leaving it to
        // the decoder would be fine, but padding it to the next multiple of 4 would
        // manufacture a string that decodes to a WRONG byte count, so it is refused here.
        when (s.length % 4) {
            1 -> return null
            2 -> s += "=="
            3 -> s += "="
        }
        val bytes = try {
            // The strict decoder: it rejects `-`, `_`, whitespace and any character
            // outside the standard alphabet, which is what `Data(base64Encoded:)` does
            // and what makes this a real validity test rather than a reformat.
            Base64.getDecoder().decode(s)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size != IDENTITY_KEY_BYTES) return null
        return s
    }

    /** True when this string is a usable OSHI address. */
    fun isIdentityKey(raw: String?): Boolean = canonicalAddress(raw) != null

    // ------------------------------------------------------------------ internals

    private sealed interface UrlKey {
        data class Found(val value: String) : UrlKey
        data class ForeignHost(val host: String) : UrlKey
        object NotAUrl : UrlKey
    }

    /**
     * Pull `key=` / `k=` out of an OSHI link.
     *
     * Hand-rolled rather than `java.net.URI` for the same reason Android's
     * `DeepLinkParser` avoids `android.net.Uri`: the authority rules that matter here are
     * the ones that defeat `oshi-messenger.org@evil.com` and `oshi-messenger.com.evil.com`,
     * and they are clearer written out than argued about through a parser's edge cases.
     */
    private fun keyFromUrl(raw: String): UrlKey {
        val schemeSep = raw.indexOf("://")
        if (schemeSep <= 0) return UrlKey.NotAUrl
        val scheme = raw.substring(0, schemeSep).lowercase()

        val rest = raw.substring(schemeSep + 3).substringBefore('#')
        val beforeQuery = rest.substringBefore('?')
        if (!rest.contains('?')) return UrlKey.NotAUrl
        val query = rest.substringAfter('?')
        val authority = beforeQuery.substringBefore('/')

        when (scheme) {
            "https" -> {
                // userinfo@host:port — the host is what follows the LAST '@' and precedes
                // the ':'. Taking the first '@' is how `…org@evil.com` gets accepted.
                val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
                if (host !in ACCEPTED_HOSTS) return UrlKey.ForeignHost(host)
            }
            "oshi" -> Unit          // custom scheme: only this app is registered for it
            else -> return UrlKey.NotAUrl   // http, javascript:, file: … not ours
        }

        for (param in query.split('&')) {
            val name = param.substringBefore('=')
            if (name != "key" && name != "k") continue
            if (!param.contains('=')) continue
            val value = param.substringAfter('=')
            if (value.isEmpty()) continue
            return UrlKey.Found(percentDecodeKeepingPlus(value))
        }
        return UrlKey.NotAUrl
    }

    /**
     * Decode `%XX` escapes and NOTHING else.
     *
     * A `+` stays a `+`. That is the entire disagreement 5: `URLDecoder.decode` would
     * turn it into a space and silently corrupt every key iOS shares, because iOS's
     * `.urlQueryAllowed` leaves `+` unescaped. An unparseable escape is left as literal
     * text rather than throwing — the `$IDENTITY_KEY_BYTES`-byte check downstream rejects
     * it, which is the same outcome by a calmer route.
     */
    private fun percentDecodeKeepingPlus(s: String): String {
        if (!s.contains('%')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) {
                    out.append(v.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** iOS's `.urlQueryAllowed` leaves these raw; we do not — see disagreement 2. */
    private fun percentEncodeKey(key: String): String = buildString(key.length + 8) {
        for (c in key) when (c) {
            '+' -> append("%2B")
            '/' -> append("%2F")
            '=' -> append("%3D")
            else -> append(c)
        }
    }

    /** `MessagesListView.swift:501-508`. */
    private fun stripDetectorTail(s: String): String {
        val eq = s.lastIndexOf('=')
        if (eq < 0 || eq == s.length - 1) return s
        val head = s.substring(0, eq + 1)
        val tail = s.substring(eq + 1)
        if (head.length >= 20 && (tail.contains(' ') || tail.contains(','))) return head
        return s
    }
}
