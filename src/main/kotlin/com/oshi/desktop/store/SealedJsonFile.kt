package com.oshi.desktop.store

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * __LOCAL_DATA_AT_REST_2026_09_22__ One small JSON document, AES-256-GCM sealed at rest.
 *
 * For the stores that are rewritten wholesale (contacts, groups, scheduled messages). On disk:
 *
 *     {"v":1,"format":"oshi-sealed-json","alg":"AES-256-GCM","nonce":b64(12),"ct":b64(ct‖tag)}
 *
 * The AAD is `oshi-sealed-json-v1:<purpose>`, so an envelope written for one store does not
 * open as another even under the same key. A fresh random nonce per write.
 *
 * READ RULES — the same refusals [MessageStore] and [KeyVault] make:
 *  - absent file → `null` ("nothing stored yet"), the only case that may read as empty;
 *  - a legacy plaintext document (anything that is not this envelope) → returned with
 *    `sealed = false`, so the caller can migrate it;
 *  - an envelope with NO key, or one that fails authentication → [SealedStoreException].
 *    Never "empty": an empty contact list or schedule written back over a file this process
 *    merely could not open would destroy it.
 *
 * WRITE RULES — every write, not only the migration, is WRITE NEW, VERIFY, THEN REPLACE:
 * temp file beside the target, `fsync`, read the temp back and decrypt it, compare with the
 * intended plaintext (plus the caller's own [write] `verify`), and only then an atomic move.
 * Any failure deletes the temp and leaves the previous file — plaintext or sealed — untouched.
 */
internal object SealedJsonFile {
    const val FORMAT = "oshi-sealed-json"
    private const val VERSION = 1
    private const val NONCE_BYTES = 12

    class Read(val json: String, val sealed: Boolean)

    fun read(file: File, key: ByteArray?, purpose: String): Read? {
        if (!file.isFile) return null
        val text = file.readText(Charsets.UTF_8)
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw SealedStoreException("not readable JSON: ${file.absolutePath}", e)
        }
        if (!isEnvelope(o)) return Read(text, sealed = false)
        val k = key ?: throw SealedStoreException(
            "${file.absolutePath} is encrypted and no key was supplied; refusing to read it as empty"
        )
        return Read(open(k, purpose, o, file), sealed = true)
    }

    fun isEnvelope(o: JSONObject): Boolean = o.optString("format") == FORMAT && o.has("ct")

    /**
     * Seal (or, with no key, write plaintext — tests and pre-vault callers only) and replace.
     * @param verify an extra check on the plaintext that came back out of the temp file.
     */
    fun write(file: File, key: ByteArray?, purpose: String, json: String, verify: (String) -> Boolean = { true }) {
        val bytes = if (key == null) json.toByteArray(Charsets.UTF_8) else seal(key, purpose, json).toByteArray(Charsets.UTF_8)
        val parent = file.parentFile ?: File(".")
        DesktopPaths.ensurePrivateDir(parent)
        val tmp = File.createTempFile(file.name, ".sealing", parent)
        try {
            DesktopPaths.makePrivate(tmp)
            FileOutputStream(tmp).use { fos -> fos.write(bytes); fos.fd.sync() }
            val back = tmp.readText(Charsets.UTF_8)
            val plain = if (key == null) back else open(key, purpose, JSONObject(back), tmp)
            if (plain != json || !verify(plain)) {
                throw SealedStoreException("read-back of ${file.name} did not reproduce what was written; left untouched")
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            DesktopPaths.makePrivate(file)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun seal(key: ByteArray, purpose: String, json: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(purpose))
        val ct = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return JSONObject()
            .put("v", VERSION)
            .put("format", FORMAT)
            .put("alg", "AES-256-GCM")
            .put("nonce", b64.encodeToString(nonce))
            .put("ct", b64.encodeToString(ct))
            .toString()
    }

    private fun open(key: ByteArray, purpose: String, o: JSONObject, file: File): String {
        try {
            val v = o.optInt("v", -1)
            if (v != VERSION) throw SealedStoreException("unsupported sealed-store version $v in ${file.absolutePath}")
            val nonce = Base64.getDecoder().decode(o.getString("nonce"))
            require(nonce.size == NONCE_BYTES) { "bad nonce length" }
            val ct = Base64.getDecoder().decode(o.getString("ct"))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(purpose))
            return String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: SealedStoreException) {
            throw e
        } catch (e: Exception) {
            throw SealedStoreException(
                "${file.absolutePath} did not decrypt (${e.javaClass.simpleName}): wrong key or modified file. " +
                    "It is left exactly as it is.", e,
            )
        }
    }

    private fun aad(purpose: String): ByteArray = "oshi-sealed-json-v1:$purpose".toByteArray(Charsets.UTF_8)
}

class SealedStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
