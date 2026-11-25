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
 *     {"v":1,"protection":"os-store"|"passphrase","keyAccount":"master-key.<id>"?,"kdf":{...}?,"nonce":b64,"ct":b64}
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
    /**
     * __PER_PROFILE_MASTER_KEY_2026_11_19__ The OS key store account holding THIS vault's
     * master key. Null for a passphrase vault (no OS entry at all), [MASTER_ACCOUNT] for a
     * legacy vault whose key could not be copied to its own entry yet.
     */
    private val keyAccount: String?,
) {

    val protectedBy: String get() = protection

    /** The OS key store account this vault's master key lives under, or null (passphrase). */
    val masterKeyAccount: String? get() = keyAccount
    val path: String get() = file.absolutePath

    @Synchronized
    fun get(account: String): ByteArray? = entries[account]?.copyOf()

    @Synchronized
    fun put(account: String, secret: ByteArray) {
        entries[account] = secret.copyOf()
        persist()
    }

    /**
     * Persist a logically-related set of secrets in one encrypted, atomic vault write.
     *
     * Identity keys must never be written one at a time: a power loss between individual
     * writes used to leave a partial account which looked empty to callers.  The map is
     * copied before it is installed, so callers cannot mutate material retained by the
     * vault after this method returns.
     */
    @Synchronized
    fun putAll(values: Map<String, ByteArray>) {
        require(values.isNotEmpty()) { "putAll requires at least one vault entry" }
        val replacement = values.mapValues { (_, value) -> value.copyOf() }
        entries.putAll(replacement)
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

    /** Remove related secrets in one vault generation rather than leaving partial state. */
    @Synchronized
    fun deleteAll(accounts: Collection<String>) {
        if (accounts.any { entries.containsKey(it) }) {
            accounts.forEach(entries::remove)
            persist()
        }
    }

    @Synchronized
    fun accounts(): Set<String> = entries.keys.toSortedSet()

    /**
     * Wipe the vault file AND the master key that is THIS vault's own. Account deletion,
     * and nothing less — and nothing more.
     *
     * __PER_PROFILE_MASTER_KEY_2026_11_19__ Until 1.5.0 every profile on a machine (the
     * default data directory, every `--home`/`OSHI_HOME` profile) shared ONE OS entry,
     * `com.oshi.desktop` / `master-key`, and this method deleted it unconditionally — even
     * when the vault being destroyed was a PASSPHRASE vault that never used it. Destroying
     * a throwaway profile therefore made every other OS-store profile on the machine
     * undecryptable: a new identity on next start, every contact writing into the void.
     *
     * Now: a passphrase vault deletes NO OS entry; an os-store vault deletes only its own
     * per-vault entry ([masterKeyAccount]); and the legacy shared entry is never deleted,
     * because there is no way to know which other profile still reads it.
     */
    @Synchronized
    fun destroy(secretStore: SecretStore?) {
        entries = HashMap()
        file.delete()
        val own = keyAccount
        if (protection == "os-store" && own != null && own != MASTER_ACCOUNT) {
            secretStore?.delete(own)
        }
    }

    @Synchronized
    internal fun persistLocked() = persist()

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
            if (keyAccount != null && keyAccount != MASTER_ACCOUNT) {
                append(",\"keyAccount\":\"").append(jsonEscape(keyAccount)).append('"')
            }
            if (kdfHeader != null) append(",\"kdf\":").append(kdfHeader)
            append(",\"nonce\":\"").append(b64.encodeToString(nonce)).append('"')
            append(",\"ct\":\"").append(b64.encodeToString(ct)).append("\"}")
        }
        AtomicFile.write(file, json.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val VERSION = 1
        /**
         * The LEGACY master key entry, shared by every profile on the machine until 1.5.0.
         * Still READ (an existing install must open after the upgrade, and a downgrade to
         * 1.4.x must still open a migrated vault), never written for a new vault, never deleted.
         */
        const val MASTER_ACCOUNT = "master-key"

        /** Prefix of the per-vault entry: `master-key.<16 hex>`. */
        const val MASTER_ACCOUNT_PREFIX = "master-key."

        private val KEY_ACCOUNT_SHAPE = Regex("^master-key\\.[0-9a-f]{16,64}$")

        internal fun newKeyAccount(): String {
            val id = ByteArray(8).also { SecureRandom().nextBytes(it) }
            return MASTER_ACCOUNT_PREFIX + id.joinToString("") { "%02x".format(it) }
        }
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
                    if (existing == null) {
                        // First run: mint a key under THIS vault's own entry, hand it to the
                        // OS (verified read-back), and write the (empty) vault at once so the
                        // entry is never orphaned by a process that exits before its first put.
                        val account = newKeyAccount()
                        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
                        store.put(account, key)
                        val v = KeyVault(file, key, "os-store", null, HashMap(), account)
                        v.persistLocked()
                        return v
                    }
                    val recorded = existing.keyAccount
                    if (recorded != null) {
                        val key = store.get(recorded) ?: throw missingKey(store, file, recorded)
                        return load(file, key, "os-store", null, existing, recorded)
                    }
                    // A vault written before 1.5.0: its key is the shared legacy entry.
                    val legacy = store.get(MASTER_ACCOUNT) ?: throw missingKey(store, file, MASTER_ACCOUNT)
                    val opened = load(file, legacy, "os-store", null, existing, MASTER_ACCOUNT)
                    // Migrate: copy (never move) the key to an entry of this vault's own, then
                    // record it in the file. The legacy entry stays — another profile, or a
                    // downgrade to 1.4.x, may still read it. Any failure keeps the legacy
                    // binding, which is exactly how 1.4.5 ran.
                    val account = newKeyAccount()
                    return try {
                        store.put(account, legacy)
                        val migrated = KeyVault(file, legacy, "os-store", null, opened.entries, account)
                        migrated.persistLocked()
                        migrated
                    } catch (e: Exception) {
                        runCatching { store.delete(account) }
                        opened
                    }
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

        /** True when [file] is an existing vault whose master key is in the OS store. Never throws. */
        fun isOsStoreVault(file: File): Boolean = runCatching {
            file.isFile && org.json.JSONObject(file.readText(Charsets.UTF_8)).optString("protection", "os-store") == "os-store"
        }.getOrDefault(false)

        private fun missingKey(store: SecretStore, file: File, account: String) = KeyVaultException(
            "the master key is missing from ${store.id}, but the vault exists.\n" +
                "  vault: ${file.absolutePath}\n" +
                "  This is recoverable ONLY by restoring the key store entry " +
                "(service ${SecretStore.SERVICE}, account $account). Deleting the vault would create a NEW identity " +
                "with a NEW address, and every contact would keep writing to the old one."
        )

        private fun load(
            file: File, key: ByteArray, protection: String, kdfHeader: String?, existing: Parsed?,
            keyAccount: String? = null,
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
            return KeyVault(file, key, protection, kdfHeader, entries, keyAccount)
        }

        fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(passphrase, salt, iterations, 256)).encoded

        private class Parsed(
            val protection: String, val nonce: ByteArray, val ct: ByteArray,
            val salt: ByteArray?, val iterations: Int?, val keyAccount: String?,
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
                keyAccount = o.optString("keyAccount", "").takeIf { it.isNotEmpty() }?.also {
                    if (!KEY_ACCOUNT_SHAPE.matches(it)) throw KeyVaultException(
                        "vault names an unexpected key store entry '$it': ${file.absolutePath}"
                    )
                },
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
