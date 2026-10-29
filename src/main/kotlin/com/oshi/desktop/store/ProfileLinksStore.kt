package com.oshi.desktop.store

import com.oshi.desktop.msg.ProfileLinksPayload
import java.io.File
import org.json.JSONObject

/**
 * __DESKTOP_PROFILE_LINKS_2026_10_29__ Each contact's shared links, and whether they are
 * currently shared — iOS's `contactLinks` / `contactLinksVisible`, Android's two StateFlows.
 *
 * ## Visibility and the links are kept SEPARATELY, as both phones keep them
 *
 * A peer turning their links off sends `visible:false` and no links, so the obvious
 * implementation — one nullable field — would have to choose between forgetting what they
 * had shared and showing it anyway. Both phones keep two maps, and the reason shows up the
 * moment someone toggles twice: the links are remembered and simply not displayed, so the
 * second "on" has something to draw before the next payload arrives.
 *
 * It also makes the honest statement possible: **withdrawal is a request, not a revocation.**
 * Anything a peer sent us is already on this disk, and a client that deleted it on
 * `visible:false` would be claiming to unsend something. The window stops showing the links;
 * it does not pretend they were never received. That is recorded here because the opposite
 * choice looks more privacy-preserving and is less honest.
 */
class ProfileLinksStore(private val file: File) {

    private val lock = Any()
    private var loaded = false
    private val links = HashMap<String, ProfileLinksPayload.Links>()
    private val visible = HashMap<String, Boolean>()

    /** What this contact shared, or null if they never did. */
    fun linksOf(contact: String): ProfileLinksPayload.Links? = synchronized(lock) {
        ensureLoaded()
        links[key(contact)]
    }

    /** True only while the contact is sharing them. See the class note. */
    fun isVisible(contact: String): Boolean = synchronized(lock) {
        ensureLoaded()
        visible[key(contact)] == true
    }

    /** What the window should draw: the links, or null when there is nothing to show. */
    fun shownLinks(contact: String): ProfileLinksPayload.Links? = synchronized(lock) {
        ensureLoaded()
        val k = key(contact)
        if (visible[k] != true) null else links[k]?.takeIf { it.hasAny }
    }

    fun all(): Map<String, ProfileLinksPayload.Links> = synchronized(lock) {
        ensureLoaded()
        HashMap(links)
    }

    /**
     * Apply an inbound payload.
     *
     * @return true when something changed, so a caller can skip a repaint for the redelivery
     *   a mesh produces routinely.
     */
    fun apply(contact: String, v: ProfileLinksPayload.Visibility): Boolean {
        val k = key(contact)
        if (k.isEmpty()) return false
        synchronized(lock) {
            ensureLoaded()
            val beforeVisible = visible[k]
            val beforeLinks = links[k]
            visible[k] = v.visible
            // Only overwrite the links when they were actually sent. A withdrawal carries
            // none, and treating that as "they cleared their links" would lose what we hold
            // and then show nothing on the next "on" until another payload arrived.
            if (v.visible && v.links.hasAny) links[k] = v.links
            val changed = beforeVisible != visible[k] || beforeLinks != links[k]
            if (changed) persist()
            return changed
        }
    }

    fun forget(contact: String) {
        synchronized(lock) {
            ensureLoaded()
            val k = key(contact)
            if (links.remove(k) != null || visible.remove(k) != null) persist()
        }
    }

    // ------------------------------------------------------------------ disk

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull()
            ?: return
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        for (contact in root.keys()) {
            val o = runCatching { root.getJSONObject(contact) }.getOrNull() ?: continue
            visible[contact] = o.optBoolean("visible", false)
            val l = o.optJSONObject("links") ?: continue
            // Re-read through the payload decoder's own field rule so a file written by a
            // future build cannot hand the window a longer string than the wire may.
            ProfileLinksPayload.decode(
                com.oshi.desktop.msg.ControlPrefix.LINKS_VISIBILITY +
                    JSONObject().put("visible", true).put("links", l).toString(),
            )?.links?.takeIf { it.hasAny }?.let { links[contact] = it }
        }
    }

    private fun persist() {
        val root = JSONObject()
        val contacts = links.keys + visible.keys
        contacts.forEach { c ->
            val o = JSONObject().put("visible", visible[c] == true)
            links[c]?.let { o.put("links", it.toJson()) }
            root.put(c, o)
        }
        val payload = root.toString()
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            tmp.setReadable(false, false); tmp.setReadable(true, true)
            tmp.setWritable(false, false); tmp.setWritable(true, true)
            if (!tmp.renameTo(file)) { file.writeText(payload, Charsets.UTF_8); tmp.delete() }
        }
    }

    companion object {
        const val FILE_NAME = "contact-links.json"

        /** One spelling per identity, as every other per-contact store here folds it. */
        fun key(contact: String): String =
            if (contact.isEmpty()) contact else com.oshi.desktop.group.GroupIdentity.canonicalIdentity(contact)
    }
}
