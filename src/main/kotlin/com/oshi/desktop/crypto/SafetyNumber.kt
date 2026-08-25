package com.oshi.desktop.crypto

import java.security.MessageDigest

/**
 * PARITY.md row 0.20. A safety number is the string two people read to each other over a
 * side channel to confirm nobody sits in the middle of their session — it binds the two
 * identity keys the app is actually using, so it only means something if EVERY platform
 * computes it the SAME way.
 *
 * THIS PROJECT HAS SHIPPED THREE MUTUALLY INCOMPATIBLE GENERATORS FOR IT. In order:
 *
 *  1. `OSHI/SafetyNumber.swift:21-39` (iOS `SafetyNumber.generate`, plus `verify` at
 *     `:41-43`) — the reference, and the one implemented below:
 *       - sort the two raw key STRINGS, concatenate with NO separator
 *       - SHA-256 once
 *       - take the first 30 of the 32 digest bytes, map each to TWO digits via
 *         `byte % 100` (zero-padded) -> 60 decimal digits
 *       - chunk into groups of 5, space-joined -> 12 groups
 *       - the QR payload is `"SAFETY:v1:" + combinedKeys` (the KEYS, not the digits),
 *         and `verify` is EXACT BYTE EQUALITY of that payload — never a re-derivation
 *
 *  2. `OSHI-Android/.../service/SafetyNumberManager.kt` originally combined
 *     `"0:key1:key2"`, hashed with SHA-512 iterated 5200 times (re-hashing the DIGEST,
 *     not digest+input), hex-encoded 30 of the resulting 64 bytes, then mapped hex
 *     CHARACTERS to decimal digits by `(char.code - 'a'.code) % 10` — a mapping that
 *     only makes sense for 'a'..'f'. Its own doc comment claimed "Compatible with iOS
 *     SafetyNumber.swift". It was not: different hash, different iteration count,
 *     different pre-image format, different digit derivation.
 *
 *  3. `OSHI-Android/.../network/encryption/CryptoManager.kt`'s `generateSafetyNumber`
 *     sorted-and-concatenated like (1) and hashed with SHA-256 like (1), but then read
 *     only the first 12 of the 32 digest bytes as six big-endian 16-bit words, each
 *     reduced `% 100000` -> SIX groups, 30 digits total — half the length of (1), from
 *     less than half the hash output. Its doc comment also claimed parity with iOS.
 *
 * Android has SINCE unified on (1): `OSHI-Android/.../service/SafetyNumberManager.kt`
 * now defines `SafetyNumberCodec`, whose `fingerprint`/`displayCode`/`qrPayload`/
 * `verify` are byte-for-byte iOS's algorithm (commit `8d5f7b2`, "Android 1.6.3: iOS
 * parity sweep"), with its own independently-derived vectors in
 * `SafetyNumberVectorTest.kt`. Both `SafetyNumberManager.generateSafetyNumber` and
 * `CryptoManager.generateSafetyNumber` now redirect to that one codec. THIS FILE MIRRORS
 * `SafetyNumberCodec`'s SHAPE (fingerprint / combined / displayCode / qrPayload /
 * verify) for exactly that reason: it is the current single source of truth this
 * project has converged on, and it IS iOS's original algorithm.
 *
 * That said: as of this writing neither app actually shows a safety number to a user.
 * `SafetyNumberView` (`OSHI/SafetyNumber.swift:57-252`) has no references anywhere in
 * the iOS tree, and Android's `SafetyNumberManager` is wired through Hilt
 * (`AppModule.kt`) but injected into nothing. There is no live UI to disagree with
 * today — but if a THIRD platform (this one) shipped a fourth algorithm, or if either
 * app's dead code were wired up without first deleting the two Android generators
 * above, the next person to see a mismatch would have no way to tell "the algorithms
 * disagree" from "someone is actively intercepting this conversation": all of the
 * variants above render as confident-looking groups of digits in the same monospace
 * layout, with no version tag or algorithm id anywhere in what's displayed. That is the
 * failure this file exists to not add a fourth instance of.
 */
object SafetyNumber {

    /** What a scanned/received QR payload is prefixed with. Matches iOS `qrData` (`:36`)
     *  and Android's `SafetyNumberCodec.QR_PREFIX`. */
    const val QR_PREFIX: String = "SAFETY:v1:"

    /** iOS reads only the first 30 of SHA-256's 32 output bytes (`:28`). */
    private const val DIGEST_BYTES_USED = 30

    /** Each byte becomes exactly two decimal digits (`byte % 100`, zero-padded, `:29`). */
    private const val DIGITS_PER_BYTE = 2

    /** Display groups of 5 digits, matching iOS's `stride(from:to:by:5)` (`:32-34`). */
    private const val GROUP_SIZE = 5

    /** Total digits: 30 bytes * 2 digits/byte = 60; 60 / 5 = 12 groups. */
    const val DIGIT_COUNT: Int = DIGEST_BYTES_USED * DIGITS_PER_BYTE

    /**
     * The sorted, separator-less pre-image both sides hash: whichever of [keyA]/[keyB]
     * sorts first (by Kotlin's `String` ordering — UTF-16 code unit order, which agrees
     * with Swift's `Comparable` conformance for the ASCII/base64 key strings this
     * protocol actually produces) comes first, with nothing joining them. Sorting the
     * key STRINGS (not their hashes, not their bytes) is what makes the result agree
     * regardless of which participant — initiator or responder — calls this: without
     * it, "who is first" would depend on who's asking, and the two sides would compute
     * two different numbers for the same pair of keys, a false MITM warning on every
     * single conversation.
     */
    fun combined(keyA: String, keyB: String): String {
        val keys = listOf(keyA, keyB).sorted()
        return keys[0] + keys[1]
    }

    /** The 60-digit numeric fingerprint, unformatted (`OSHI/SafetyNumber.swift:25-30`). */
    fun fingerprint(keyA: String, keyB: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(combined(keyA, keyB).toByteArray(Charsets.UTF_8))

        // `byte.toInt() and 0xFF`: Kotlin's Byte is SIGNED (-128..127). Without the
        // mask, `byte % 100` on any byte >= 0x80 yields a NEGATIVE remainder and
        // "%02d".format(-7) prints "-7" — three characters instead of two — which
        // desynchronises every digit after it in the 60-digit string. Swift's Data
        // element is an unsigned UInt8 and never hits this. (Android hit exactly this
        // bug once; see `SafetyNumberVectorTest.kt`'s "high bytes stay unsigned" case,
        // reproduced as this file's own test of the same name.)
        return digest.take(DIGEST_BYTES_USED).joinToString("") {
            (it.toInt() and 0xFF).rem(100).toString().padStart(DIGITS_PER_BYTE, '0')
        }
    }

    /** [fingerprint] chunked into groups of 5, space-separated — what a user reads aloud. */
    fun displayCode(fingerprint: String): String = fingerprint.chunked(GROUP_SIZE).joinToString(" ")

    /** Convenience: `displayCode(fingerprint(keyA, keyB))` in one call. */
    fun compute(keyA: String, keyB: String): String = displayCode(fingerprint(keyA, keyB))

    /**
     * What a QR code encodes: the COMBINED KEYS, not the fingerprint digits
     * (`OSHI/SafetyNumber.swift:36`). Encoding the keys rather than the derived digits
     * means `verify` below can be plain byte equality instead of a second hash pass.
     */
    fun qrPayload(keyA: String, keyB: String): String = QR_PREFIX + combined(keyA, keyB)

    /**
     * iOS never re-derives on scan — it compares the scanned QR bytes to the locally
     * computed payload (`OSHI/SafetyNumber.swift:41-43`). Order-independent because
     * [qrPayload] already sorts.
     */
    fun verify(scannedPayload: String, keyA: String, keyB: String): Boolean =
        scannedPayload == qrPayload(keyA, keyB)
}
