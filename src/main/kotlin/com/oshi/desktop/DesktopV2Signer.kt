package com.oshi.desktop

import java.security.MessageDigest

/**
 * Request signer for the OSHI V2 transport — desktop copy of the Android `V2Signer`,
 * which is itself a port of iOS `V2Client.makeRequest`/`signRequest`.
 *
 * Reimplemented rather than shared because the Android original imports
 * android.util.Base64 and Hilt. The RULES are transcribed exactly:
 *
 *   canonical = "METHOD\nPATH\nBODYHASH\nTIMESTAMP"   (three literal newlines, UTF-8)
 *
 *   METHOD     upper-case verb
 *   PATH       percent-encoded path, QUERY EXCLUDED
 *   BODYHASH   LOWERCASE-hex SHA-256 of the exact body bytes
 *   TIMESTAMP  epoch MILLISECONDS, decimal string (server enforces a 30 s window)
 *
 *   signature  = Ed25519 over utf8(canonical), STANDARD PADDED base64 (not base64url)
 *
 * Four traps live in here, all of which have bitten this project before:
 *
 *  1. BODYHASH is over the EXACT bytes sent. Serialize once, hash that array, POST that
 *     same array. Re-serializing between hashing and sending can reorder keys and the
 *     server rejects with a 401 that looks like a clock problem.
 *  2. The body-to-hash is not always the body. GET and commit routes hash ZERO bytes;
 *     the verify-first blob/account routes hash the TWO-BYTE string `""`. See
 *     [EMPTY_JSON_STRING_BODY].
 *  3. The query string is excluded from PATH but present on the URL. `/v2/messages/{id}`
 *     is signed; `?after=123` is not.
 *  4. [encodeIdentity] percent-encodes EVERYTHING that is not ASCII alphanumeric. A
 *     base64 identity contains `+`, `/` and `=`, and all three must become %2B, %2F,
 *     %3D. Using a normal URL encoder — which leaves `=` or turns space into `+` —
 *     breaks every identity-in-path route. This is the single most copy-sensitive
 *     function in the protocol.
 */
class DesktopV2Signer(private val identity: DesktopIdentity) {

    /** The account X25519 userKey this signer puts in `x-oshi-user` (the devsync upgrade binds it into B). */
    val userKey: String get() = identity.userKey

    fun sign(
        method: String,
        path: String,
        bodyToHash: ByteArray,
        withUserHeader: Boolean = false,
        timestampMs: Long = System.currentTimeMillis(),
    ): Map<String, String> {
        val timestamp = timestampMs.toString()
        val canonical = canonicalString(method, path, bodyToHash, timestamp)
        val headers = linkedMapOf(
            "x-oshi-signature" to DesktopIdentity.B64.encodeToString(
                identity.sign(canonical.toByteArray(Charsets.UTF_8))
            ),
            "x-oshi-timestamp" to timestamp,
            "x-oshi-signing-pubkey" to DesktopIdentity.B64.encodeToString(identity.signingPub),
        )
        if (withUserHeader) headers["x-oshi-user"] = identity.userKey
        return headers
    }

    /** Exposed so a test can assert the string itself, not just that signing succeeded. */
    fun canonicalString(method: String, path: String, bodyToHash: ByteArray, timestamp: String): String =
        "$method\n$path\n${sha256Hex(bodyToHash)}\n$timestamp"

    companion object {
        /** The 2-byte body-to-hash used by the verify-first blob/account routes. */
        val EMPTY_JSON_STRING_BODY: ByteArray = "\"\"".toByteArray(Charsets.UTF_8)

        /**
         * Only ASCII alphanumerics pass through; every other byte becomes upper-case %XX.
         * Byte-for-byte port of Android `V2Signer.encodeIdentity` / iOS `encodeIdentity`.
         */
        fun encodeIdentity(identity: String): String {
            val sb = StringBuilder(identity.length * 3)
            for (b in identity.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                if (c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%').append("%02X".format(c))
                }
            }
            return sb.toString()
        }

        /** Lowercase-hex SHA-256. Upper-case hex here is a silent 401. */
        fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
