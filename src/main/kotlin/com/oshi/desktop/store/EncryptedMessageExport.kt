package com.oshi.desktop.store

import com.oshi.desktop.i18n.dt
import com.oshi.messenger.network.v2.OSHICryptoV2
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ The `.oshiexport` container — byte-compatible with
 * iOS `EncryptedMessageExport` (`OSHI/FileStorage.swift`, "Encrypted message export") and
 * `IdentityManager.deriveMessageExportKey` / `messageExportAccountTag`.
 *
 *     "OSHIEXP1" (8) | version 0x01 (1) | salt (32) | accountTag (8) | nonce (12) | ct | GCM tag (16)
 *
 *  - AAD       = the 49-byte header (magic ‖ version ‖ salt ‖ accountTag). Changing the
 *                account tag or the version breaks authentication.
 *  - key       = HKDF-SHA256(IKM = the account's RAW 32-byte X25519 *encryption* private key,
 *                salt = the 32-byte salt, info = "oshi-message-export-v1", L = 32).
 *  - accountTag= SHA-256(raw 32-byte X25519 public key)[0..8) — lets an import say "this belongs
 *                to another account" instead of failing as a silent decryption error. Reveals
 *                nothing beyond the public key, which is already public.
 *  - plaintext = UTF-8 JSON. Written as the platform-neutral VERSION 2 payload
 *                ([ExportV2], `docs/OSHI_EXPORT_FORMAT.md`) that iOS and Android also read;
 *                the legacy version 1 `{"version":1,"platform":"desktop","messages":[…]}` of
 *                [Message.toJson] objects is still read back ([readAny]).
 *
 * WHY THE ACCOUNT KEY AND NOT THE AT-REST HISTORY KEY. The history key in [KeyVault] is bound
 * to THIS machine's OS key store and must never leave it. The account key is the one thing the
 * 64-byte recovery key restores on any device ([IdentityStore.parseRecoveryKey]: bytes 0..32 are
 * exactly this X25519 private key, as on iOS/Android), so an export is readable only by an OSHI
 * session holding the same account, wherever it runs.
 *
 * The container is shared across platforms; the MESSAGE OBJECTS are not yet (iOS writes
 * `SecureMessage`, the desktop writes [Message.toJson]). `platform` names the schema, and an
 * import refuses a schema it cannot read rather than guessing.
 *
 * NO PLAINTEXT ON DISK. [write] serialises and encrypts in memory and only ever writes the
 * sealed bytes; [read] decrypts in memory. The plaintext byte arrays are zeroed after use
 * (best effort: the JVM may still hold `String` copies until they are collected).
 */
object EncryptedMessageExport {

    const val FILE_EXTENSION = "oshiexport"
    const val PLATFORM = "desktop"
    const val PAYLOAD_VERSION = 1

    private val MAGIC = "OSHIEXP1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 0x01
    const val SALT_BYTES = 32
    const val ACCOUNT_TAG_BYTES = 8
    const val NONCE_BYTES = 12
    const val GCM_TAG_BYTES = 16
    const val HEADER_BYTES = 8 + 1 + SALT_BYTES + ACCOUNT_TAG_BYTES // 49
    private val HKDF_INFO = "oshi-message-export-v1".toByteArray(Charsets.UTF_8)

    /** A history export is text; refuse anything absurd before reading it all into memory. */
    const val MAX_FILE_BYTES: Long = 512L * 1024 * 1024

    // ------------------------------------------------------------------ primitives

    /** First 8 bytes of SHA-256 over the raw 32-byte X25519 public key. */
    fun accountTag(x25519Public: ByteArray): ByteArray {
        require(x25519Public.size == 32) { "X25519 public key must be 32 bytes" }
        return MessageDigest.getInstance("SHA-256").digest(x25519Public).copyOf(ACCOUNT_TAG_BYTES)
    }

    /** HKDF-SHA256(ikm = raw X25519 private key, salt, info = "oshi-message-export-v1", 32). */
    fun deriveKey(x25519Private: ByteArray, salt: ByteArray): ByteArray {
        require(x25519Private.size == 32) { "X25519 private key must be 32 bytes" }
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(x25519Private, salt, HKDF_INFO))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, it.size) }
    }

    /**
     * Seal [plaintext] for the account whose X25519 private key is [x25519Private].
     * The public key (and so the account tag) is DERIVED from the private key, never taken
     * from a caller, so the tag cannot disagree with the key that encrypted the file.
     * [salt] and [nonce] are parameters only so the known-answer test can pin them.
     */
    fun seal(
        x25519Private: ByteArray,
        plaintext: ByteArray,
        salt: ByteArray = randomBytes(SALT_BYTES),
        nonce: ByteArray = randomBytes(NONCE_BYTES),
    ): ByteArray {
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        val pub = OSHICryptoV2.x25519PubFromPriv(x25519Private)
        val header = MAGIC + byteArrayOf(VERSION) + salt + accountTag(pub)
        check(header.size == HEADER_BYTES)
        val key = deriveKey(x25519Private, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BYTES * 8, nonce))
            cipher.updateAAD(header)
            val ctAndTag = cipher.doFinal(plaintext) // JCE appends the 16-byte tag, as CryptoKit's layout does
            return header + nonce + ctAndTag
        } finally {
            key.fill(0)
        }
    }

    /**
     * Authenticate and decrypt a container. Throws [MessageExportException] naming exactly
     * what is wrong; never returns a partial result.
     */
    fun open(x25519Private: ByteArray, file: ByteArray): ByteArray {
        if (file.size < HEADER_BYTES + NONCE_BYTES + GCM_TAG_BYTES ||
            !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        ) throw MessageExportException(MessageExportException.Kind.NOT_AN_EXPORT)
        if (file[MAGIC.size] != VERSION) throw MessageExportException(MessageExportException.Kind.UNSUPPORTED_VERSION)

        val header = file.copyOfRange(0, HEADER_BYTES)
        val saltStart = MAGIC.size + 1
        val salt = file.copyOfRange(saltStart, saltStart + SALT_BYTES)
        val tag = file.copyOfRange(saltStart + SALT_BYTES, HEADER_BYTES)
        val pub = OSHICryptoV2.x25519PubFromPriv(x25519Private)
        if (!MessageDigest.isEqual(tag, accountTag(pub))) {
            throw MessageExportException(MessageExportException.Kind.OTHER_ACCOUNT)
        }
        val nonce = file.copyOfRange(HEADER_BYTES, HEADER_BYTES + NONCE_BYTES)
        val key = deriveKey(x25519Private, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BYTES * 8, nonce))
            cipher.updateAAD(header)
            return cipher.doFinal(file, HEADER_BYTES + NONCE_BYTES, file.size - HEADER_BYTES - NONCE_BYTES)
        } catch (e: AEADBadTagException) {
            throw MessageExportException(MessageExportException.Kind.CORRUPTED, cause = e)
        } catch (e: java.security.GeneralSecurityException) {
            throw MessageExportException(MessageExportException.Kind.CORRUPTED, cause = e)
        } finally {
            key.fill(0)
        }
    }

    // ------------------------------------------------------------------ payload

    fun buildPayload(messages: List<Message>, exportedAt: Instant): ByteArray {
        val array = JSONArray()
        for (m in messages) array.put(m.toJson())
        return JSONObject()
            .put("version", PAYLOAD_VERSION)
            .put("platform", PLATFORM)
            .put("exportedAt", exportedAt.truncatedTo(ChronoUnit.SECONDS).toString())
            .put("messages", array)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    data class Parsed(val messages: List<Message>, val invalid: Int, val exportedAt: String?)

    /**
     * Parse an AUTHENTICATED payload. A payload from another platform is refused by name —
     * iOS `SecureMessage` objects are not desktop [Message]s, and guessing a mapping would
     * import something that looks right and is not.
     */
    fun parsePayload(plaintext: ByteArray): Parsed {
        val root = try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            throw MessageExportException(MessageExportException.Kind.CORRUPTED, cause = e)
        }
        val platform = root.optString("platform", "")
        if (platform != PLATFORM) {
            throw MessageExportException(
                MessageExportException.Kind.OTHER_PLATFORM,
                platform = platform.ifBlank { "an unknown platform" },
            )
        }
        if (root.optInt("version", -1) != PAYLOAD_VERSION) {
            throw MessageExportException(MessageExportException.Kind.UNSUPPORTED_VERSION)
        }
        val array = root.optJSONArray("messages")
            ?: throw MessageExportException(MessageExportException.Kind.CORRUPTED)
        val out = ArrayList<Message>(array.length())
        var invalid = 0
        for (i in 0 until array.length()) {
            val m = runCatching { Message.fromJson(array.getJSONObject(i)) }.getOrNull()
            if (m == null) invalid++ else out += m
        }
        return Parsed(out, invalid, root.optString("exportedAt", "").ifBlank { null })
    }

    // ------------------------------------------------------------------ files

    /** Encrypt [messages] and write ONLY the sealed bytes to [target] (atomic replace). */
    fun write(x25519Private: ByteArray, messages: List<Message>, target: File, now: Instant = Instant.now()) {
        val plaintext = buildPayload(messages, now)
        try {
            AtomicFile.write(target, seal(x25519Private, plaintext))
        } finally {
            plaintext.fill(0)
        }
    }

    fun read(x25519Private: ByteArray, source: File): Parsed {
        if (!source.isFile) throw MessageExportException(MessageExportException.Kind.NOT_AN_EXPORT)
        if (source.length() > MAX_FILE_BYTES) throw MessageExportException(MessageExportException.Kind.NOT_AN_EXPORT)
        val plaintext = open(x25519Private, source.readBytes())
        try {
            return parsePayload(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    // ------------------------------------------------------------------ version 2 (__EXPORT_V2_INTEROP_2026_09_22__)

    /** What an export decrypted to: a legacy desktop payload, or the neutral v2 one. */
    sealed class Opened {
        data class V1(val parsed: Parsed) : Opened()
        data class V2(val parsed: ExportV2.Parsed) : Opened()
    }

    /** Seal an already-encoded payload (v2 writer) and write only the sealed bytes. */
    fun writeSealed(x25519Private: ByteArray, plaintext: ByteArray, target: File) {
        try {
            AtomicFile.write(target, seal(x25519Private, plaintext))
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Open [source] and dispatch on the payload version: 2 from ANY platform, 1 from a desktop
     * only (a v1 payload from a phone holds that phone's native objects). Anything newer than 2
     * is refused as [MessageExportException.Kind.UNSUPPORTED_VERSION].
     */
    fun readAny(x25519Private: ByteArray, source: File): Opened {
        if (!source.isFile) throw MessageExportException(MessageExportException.Kind.NOT_AN_EXPORT)
        if (source.length() > MAX_FILE_BYTES) throw MessageExportException(MessageExportException.Kind.NOT_AN_EXPORT)
        val plaintext = open(x25519Private, source.readBytes())
        try {
            return parseAny(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    fun parseAny(plaintext: ByteArray): Opened {
        val root = try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            throw MessageExportException(MessageExportException.Kind.CORRUPTED, cause = e)
        }
        val version = root.optInt("version", -1)
        return when {
            version == ExportV2.VERSION -> try {
                Opened.V2(ExportV2.parse(root))
            } catch (e: Exception) {
                throw MessageExportException(MessageExportException.Kind.CORRUPTED, cause = e)
            }
            version > ExportV2.VERSION -> throw MessageExportException(MessageExportException.Kind.UNSUPPORTED_VERSION)
            else -> Opened.V1(parsePayload(plaintext))
        }
    }

    /** `name` with the `.oshiexport` extension, added if missing. */
    fun withExtension(file: File): File =
        if (file.name.endsWith(".$FILE_EXTENSION", ignoreCase = true)) file
        else File(file.parentFile, file.name + ".$FILE_EXTENSION")

    /** The iOS default name: `OSHI_Messages_<yyyy-MM-dd>.oshiexport`. */
    fun defaultFileName(now: Instant = Instant.now()): String =
        "OSHI_Messages_${now.toString().take(10)}.$FILE_EXTENSION"

    private fun randomBytes(n: Int) = ByteArray(n).also(SecureRandom()::nextBytes)
}

/** Why an import (or export) failed, with the sentence iOS shows for the same case. */
class MessageExportException(
    val kind: Kind,
    val platform: String? = null,
    cause: Throwable? = null,
) : RuntimeException(kind.name + (platform?.let { " ($it)" } ?: ""), cause) {

    enum class Kind { NOT_AN_EXPORT, UNSUPPORTED_VERSION, OTHER_ACCOUNT, CORRUPTED, OTHER_PLATFORM }

    /** Localised, user-facing sentence. */
    fun userMessage(): String = when (kind) {
        Kind.NOT_AN_EXPORT -> dt("desktop.export.error.notExport")
        Kind.UNSUPPORTED_VERSION -> dt("desktop.export.error.version")
        Kind.OTHER_ACCOUNT -> dt("desktop.export.error.otherAccount")
        Kind.CORRUPTED -> dt("desktop.export.error.corrupted")
        Kind.OTHER_PLATFORM -> dt("desktop.export.error.otherPlatform", platform ?: "?")
    }
}
