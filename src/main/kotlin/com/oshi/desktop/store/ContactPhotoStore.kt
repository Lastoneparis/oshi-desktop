package com.oshi.desktop.store

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * __PROFILE_PHOTOS_DESKTOP_2027__ Profile pictures — the contacts' (received in their
 * `📸PROFILE_UPDATE📸`, `profileImageData`) and this account's own — one sealed file each.
 *
 * NOT a field of [ContactStore], on purpose: `contacts.json` is rewritten on every
 * `seen()` — i.e. on every inbound message — and a JPEG per contact inside it would turn
 * each of those rewrites into a multi-megabyte write. iOS hit exactly this
 * (`ContactPresenceManager`'s LAUNCH-FREEZE note: `contact_presence.json` "embeds a full
 * JPEG per contact"). Here a picture is written only when it changes.
 *
 * Sealed at rest under its own HKDF subkey ([LocalDataKeys.CONTACT_PHOTOS]) like every other
 * local store: a face is personal data. The file name is a hash of the CANONICAL identity, so
 * the same key spelled base64url or unpadded by a peer lands on the same file.
 *
 * Values are base64 JPEG/PNG strings exactly as they travel (the wire format), so nothing is
 * re-encoded between receiving a picture and drawing it.
 */
class ContactPhotoStore(
    private val dir: File,
    private val atRestKey: ByteArray?,
) {
    private val cache = ConcurrentHashMap<String, Entry>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private data class Entry(val photo: String?)

    fun addChangeListener(l: () -> Unit) { listeners.add(l) }

    /** The stored picture for [address] (base64), or null. Cached after the first read. */
    fun get(address: String): String? {
        val k = keyOf(address)
        cache[k]?.let { return it.photo }
        val read = runCatching { SealedJsonFile.read(fileFor(k), atRestKey, LocalDataKeys.CONTACT_PHOTOS) }.getOrNull()
        val photo = read?.let { runCatching { JSONObject(it.json).optString("photo", "") }.getOrNull() }?.ifBlank { null }
        cache[k] = Entry(photo)
        return photo
    }

    /**
     * Store, replace or (with null) remove a picture. Returns true when something changed,
     * so a peer re-sending the same profile on every first contact costs no write.
     */
    @Synchronized
    fun set(address: String, photoBase64: String?): Boolean {
        val k = keyOf(address)
        val clean = photoBase64?.takeIf { it.isNotBlank() }
        if (get(address) == clean) return false
        val f = fileFor(k)
        if (clean == null) {
            f.delete()
        } else {
            dir.mkdirs()
            SealedJsonFile.write(f, atRestKey, LocalDataKeys.CONTACT_PHOTOS, JSONObject().put("photo", clean).toString())
        }
        cache[k] = Entry(clean)
        listeners.forEach { runCatching { it() } }
        return true
    }

    /** Wipe everything (account deletion). */
    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        cache.clear()
    }

    private fun fileFor(k: String) = File(dir, "$k.json")

    private fun keyOf(address: String): String {
        val canonical = if (address == SELF) SELF else runCatching {
            com.oshi.desktop.group.GroupIdentity.canonicalIdentity(address)
        }.getOrDefault(address)
        val d = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(40)
    }

    companion object {
        /** The slot holding THIS account's own picture. Not a valid identity key, by design. */
        const val SELF = "#self"
    }
}
