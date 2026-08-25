package com.oshi.desktop.store

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Everything secret this client owns, in one AES-256-GCM file, under one master key the
 * operating system holds.
 *
 * This is PARITY.md row 0.5, and it gates every row below it: an identity that does not
 * survive a restart is not an account, it is a demo. iOS puts this material in the
 * Keychain and Android in the Keystore; there is no single desktop answer, so the shape
 * here is one OS-held master key ([SecretStore]) wrapping one encrypted file.
 *
 * THE FAILURE MODE THIS FILE EXISTS TO PREVENT. A vault that cannot be decrypted must
 * FAIL, loudly, and never look like "no account yet". If a wrong or missing master key
 * read as an empty vault, the app above would cheerfully generate a fresh identity — and
 * a new identity is a new address, so every contact keeps writing to an account nobody
 * reads, delivery receipts stop, and the user's messages are not lost so much as sent
 * into a void that still looks like a working app. Android has shipped this class of bug
 * (`removing-an-enum-case-wipes-the-whole-stored-list`); here it is an exception with a
 * message that names the vault path and the master key's location.
 *
 * FORMAT (JSON, so it can be inspected without this code):
 *
 *     {"v":1,"protection":"os-store"|"passphrase","kdf":{...}?,"nonce":b64,"ct":b64}
 *
 * `ct` is AES-256-GCM over a JSON object of `{account: base64(secret)}`, with the
 * 12-byte nonce and the 128-bit tag GCM's defaults, matching the parameters the shared
 * crypto core uses everywhere else in this project.
 */
class KeyVault private constructor(
    private val file: File,
    private val masterKey: ByteArray,
    private val protection: String,
    private val kdfHeader: String?,
    private var entries: MutableMap<String, ByteArray>,
) {

    val protectedBy: String get() = protection
    val path: String get() = file.absolutePath

    @Synchronized
    fun get(account: String): ByteArray? = entries[account]?.copyOf()

    @Synchronized
    fun put(account: String, secret: ByteArray) {
        entries[account] = secret.copyOf()
        persist()
    }

    /** Read-or-create in one step, so two callers cannot each generate a different value. */
    @Synchronized
    fun getOrCreate(account: String, create: () -> ByteArray): ByteArray {
        entries[account]?.let { return it.copyOf() }
        val fresh = create()
        entries[account] = fresh.copyOf()
        persist()
        return fresh
    }

    @Synchronized
    fun delete(account: String) {
        if (entries.remove(account) != null) persist()
    }

    @Synchronized
    fun accounts(): Set<String> = entries.keys.toSortedSet()

    /** Wipe the vault file AND the master key. Account deletion, and nothing less. */
    @Synchronized
    fun destroy(secretStore: SecretStore?, masterAccount: String = MASTER_ACCOUNT) {
        entries = HashMap()
        file.delete()
        secretStore?.delete(masterAccount)
    }

    private fun persist() {
        val plain = StringBuilder("{")
        var first = true
        for ((k, v) in entries.toSortedMap()) {
            if (!first) plain.append(',')
            first = false
            plain.append('"').append(jsonEscape(k)).append("\":\"")
                .append(Base64.getEncoder().encodeToString(v)).append('"')
        }
        plain.append('}')

        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(plain.toString().toByteArray(Charsets.UTF_8))

        val b64 = Base64.getEncoder()
        val json = buildString {
            append("{\"v\":").append(VERSION)
            append(",\"protection\":\"").append(protection).append('"')
            if (kdfHeader != null) append(",\"kdf\":").append(kdfHeader)
            append(",\"nonce\":\"").append(b64.encodeToString(nonce)).append('"')
            append(",\"ct\":\"").append(b64.encodeToString(ct)).append("\"}")
        }
        AtomicFile.write(file, json.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val VERSION = 1
        const val MASTER_ACCOUNT = "master-key"
        const val FILE_NAME = "keyvault.json"

        /** OWASP's floor for PBKDF2-HMAC-SHA256 at the time of writing. Recorded in the file so it can be raised. */
        const val PBKDF2_ITERATIONS = 210_000

        /**
         * Open (or create) the vault.
         *
         * @param secretStore where the master key lives. Pass null to force the
         *        passphrase path — which is also what happens when no OS store is
         *        reachable, and never a silent fall back to plaintext.
         * @param passphrase used only when there is no [secretStore]. Absent both, this
         *        throws rather than write anything readable.
         */
        fun open(
            file: File = DesktopPaths.file(FILE_NAME),
            secretStore: SecretStore? = SecretStore.detect(),
            passphrase: CharArray? = null,
        ): KeyVault {
            val existing = if (file.isFile) parse(file) else null

            // The protection scheme is decided by the FILE when one exists. A vault
            // written under a passphrase must not be silently re-opened with an OS key
            // that would not decrypt it, and vice versa.
            val scheme = existing?.protection ?: if (secretStore != null) "os-store" else "passphrase"

            return when (scheme) {
                "os-store" -> {
                    val store = secretStore
                        ?: throw KeyVaultException(
                            "this vault is protected by the OS key store, and none is reachable on this " +
                                "machine.\n  vault: ${file.absolutePath}\n" +
                                "  Without that key the vault cannot be decrypted; it must not be replaced."
                        )
                    val key = if (existing == null) {
                        // First run: mint a key, hand it to the OS, and only then write a file.
                        store.get(MASTER_ACCOUNT) ?: ByteArray(32).also {
                            SecureRandom().nextBytes(it)
                            store.put(MASTER_ACCOUNT, it)      // verifies its own read-back
                        }
                    } else {
                        store.get(MASTER_ACCOUNT) ?: throw KeyVaultException(
                            "the master key is missing from ${store.id}, but the vault exists.\n" +
                                "  vault: ${file.absolutePath}\n" +
                                "  This is recoverable ONLY by restoring the key store entry " +
                                "($SERVICE_HINT). Deleting the vault would create a NEW identity " +
                                "with a NEW address, and every contact would keep writing to the old one."
                        )
                    }
                    load(file, key, "os-store", null, existing)
                }
                "passphrase" -> {
                    val pass = passphrase ?: throw KeyVaultException(
                        "a passphrase is required: no OS key store is available on this machine" +
                            (if (existing != null) ", and this vault was created with one" else "") +
                            ".\n  vault: ${file.absolutePath}\n" +
                            "  Set OSHI_PASSPHRASE or pass one in. Nothing is written unencrypted."
                    )
                    val salt = existing?.salt ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
                    val iterations = existing?.iterations ?: PBKDF2_ITERATIONS
                    val key = deriveKey(pass, salt, iterations)
                    val header = "{\"alg\":\"PBKDF2WithHmacSHA256\",\"iterations\":$iterations," +
                        "\"salt\":\"${Base64.getEncoder().encodeToString(salt)}\"}"
                    load(file, key, "passphrase", header, existing)
                }
                else -> throw KeyVaultException("unknown vault protection '$scheme' in ${file.absolutePath}")
            }
        }

        private const val SERVICE_HINT = "service ${SecretStore.SERVICE}, account $MASTER_ACCOUNT"

        private fun load(
            file: File, key: ByteArray, protection: String, kdfHeader: String?, existing: Parsed?,
        ): KeyVault {
            val entries: MutableMap<String, ByteArray> = if (existing == null) {
                HashMap()
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, existing.nonce))
                val plain = try {
                    String(cipher.doFinal(existing.ct), Charsets.UTF_8)
                } catch (e: Exception) {
                    // AEADBadTagException — wrong key, or the file was altered. Both are
                    // fatal and neither may degrade to "empty vault": see the class note.
                    throw KeyVaultException(
                        "the vault did not decrypt (${e.javaClass.simpleName}). Either the master key is " +
                            "not the one it was written with, or the file was modified.\n" +
                            "  vault: ${file.absolutePath}\n" +
                            "  Refusing to continue: starting fresh here would silently create a NEW " +
                            "identity and abandon the account this machine already has.", e
                    )
                }
                parseEntries(plain)
            }
            return KeyVault(file, key, protection, kdfHeader, entries)
        }

        fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(passphrase, salt, iterations, 256)).encoded

        private class Parsed(
            val protection: String, val nonce: ByteArray, val ct: ByteArray,
            val salt: ByteArray?, val iterations: Int?,
        )

        private fun parse(file: File): Parsed {
            val o = try {
                org.json.JSONObject(file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                throw KeyVaultException("vault file is not readable JSON: ${file.absolutePath}", e)
            }
            val v = o.optInt("v", 0)
            if (v > VERSION) throw KeyVaultException(
                "vault version $v was written by a newer OSHI desktop build; this one understands $VERSION. " +
                    "Upgrade rather than overwrite: ${file.absolutePath}"
            )
            val kdf = o.optJSONObject("kdf")
            return Parsed(
                protection = o.optString("protection", "os-store"),
                nonce = Base64.getDecoder().decode(o.getString("nonce")),
                ct = Base64.getDecoder().decode(o.getString("ct")),
                salt = kdf?.optString("salt")?.takeIf { it.isNotEmpty() }?.let { Base64.getDecoder().decode(it) },
                iterations = kdf?.optInt("iterations")?.takeIf { it > 0 },
            )
        }

        private fun parseEntries(plain: String): MutableMap<String, ByteArray> {
            val o = org.json.JSONObject(plain)
            val out = HashMap<String, ByteArray>()
            for (k in o.keys()) out[k] = Base64.getDecoder().decode(o.getString(k))
            return out
        }

        /** Local escaper for account NAMES (not secrets, which are base64). */
        private fun jsonEscape(s: String): String =
            com.oshi.messenger.network.v2.OSHICryptoV2.jsonEscape(s)
    }
}

class KeyVaultException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
