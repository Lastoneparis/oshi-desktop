package com.oshi.desktop.msg

import org.json.JSONObject

/**
 * __SHARED_NICKNAME_2026_09_22__ The nickname a peer SHARES about themselves, carried as the
 * optional `displayName` key of the `📸PROFILE_UPDATE📸` JSON — the same key on iOS
 * (`ProfileUpdatePayload.displayName`) and Android (`sendProfileUpdate`).
 *
 * Two names, never merged:
 *  - the LOCAL alias ([com.oshi.desktop.store.ContactStore.Contact.displayName]) — what the
 *    user of this machine called the contact. Never leaves the machine, always wins;
 *  - the SHARED nickname ([com.oshi.desktop.store.ContactStore.Contact.sharedNickname]) —
 *    what the peer calls themselves. Shown only when there is no alias.
 *
 * The shared one is written by the PEER, so it is hostile input: [sanitize] strips control
 * and bidi-override characters (a U+202E in a name reverses whatever follows it in the
 * conversation list) and caps it at [MAX_CODE_POINTS] code points, the same 48 the phones
 * enforce on their own setting.
 *
 * Wire semantics, identical on the three platforms:
 *  - key ABSENT (every build before this one) ⇒ [Read.Absent] — leave what we hold alone;
 *  - key present, sanitizes to text ⇒ [Read.Set];
 *  - key present but null/empty after sanitizing ⇒ [Read.Clear] — the peer removed it.
 */
object PeerNickname {

    const val WIRE_KEY = "displayName"
    const val MAX_CODE_POINTS = 48

    sealed class Read {
        data object Absent : Read()
        data object Clear : Read()
        data class Set(val name: String) : Read()
    }

    private fun isStripped(cp: Int): Boolean =
        Character.isISOControl(cp) ||             // C0/C1 incl. \n \r \t
            cp == 0x2028 || cp == 0x2029 ||       // line / paragraph separators
            cp in 0x202A..0x202E ||               // LRE RLE PDF LRO RLO
            cp in 0x2066..0x2069 ||               // LRI RLI FSI PDI
            cp == 0x200E || cp == 0x200F          // LRM RLM

    /** Trimmed, cleaned, capped at [MAX_CODE_POINTS] code points; null when nothing is left. */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val sb = StringBuilder(raw.length)
        raw.codePoints().forEach { cp -> if (!isStripped(cp)) sb.appendCodePoint(cp) }
        val trimmed = sb.toString().trim()
        if (trimmed.isEmpty()) return null
        // Cap on CODE POINTS, never UTF-16 units: `take(48)` could cut an emoji in half and
        // leave a lone surrogate that renders as a replacement box.
        val capped = if (trimmed.codePointCount(0, trimmed.length) > MAX_CODE_POINTS) {
            trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_CODE_POINTS))
        } else trimmed
        return capped.trim().ifEmpty { null }
    }

    /** Tri-state read of a decoded profile-update object. */
    fun read(json: JSONObject): Read {
        if (!json.has(WIRE_KEY)) return Read.Absent
        val raw = if (json.isNull(WIRE_KEY)) null else json.opt(WIRE_KEY) as? String
        return sanitize(raw)?.let { Read.Set(it) } ?: Read.Clear
    }

    /**
     * Tri-state read of a whole `📸PROFILE_UPDATE📸{…}` plaintext. Anything that is not a
     * profile update, or whose body is not a JSON object, is [Read.Absent]: a payload we
     * cannot read must never erase a nickname we already hold.
     */
    fun readProfileUpdate(text: String): Read {
        if (ControlPrefix.match(text) != ControlPrefix.PROFILE_UPDATE) return Read.Absent
        val json = runCatching { JSONObject(ControlPrefix.strip(text)) }.getOrNull() ?: return Read.Absent
        return read(json)
    }

    /** THE display rule: local alias, else shared nickname, else [fallback]. */
    fun resolve(alias: String?, sharedNickname: String?, fallback: String): String =
        alias?.takeIf { it.isNotBlank() }
            ?: sharedNickname?.takeIf { it.isNotBlank() }
            ?: fallback
}

/**
 * __SHARED_NICKNAME_2026_09_22__ The OUTBOUND half: this client's own `📸PROFILE_UPDATE📸`.
 *
 * Built to be decoded by every shipped reader, not just the new ones:
 *  - iOS decodes with a synthesized `Codable` (`ProfileUpdatePayload`,
 *    `OSHI/ContactPresenceManager.swift`) in which `type`, `lastSeen` and `version` are
 *    NON-optional — a payload missing one of them throws and the whole update is dropped.
 *    All three are always written. Everything else it knows is optional and is omitted.
 *  - Android reads with `JSONObject.opt*` and needs nothing in particular.
 *
 * Deliberately NOT sent, and why:
 *  - `profileImageData` — this client has no avatar. Absent and `null` decode the same.
 *  - `lastSeen` carries `0` ("not shared"): there is no presence setting here to honour,
 *    Android treats `<= 0` as absent, and iOS overwrites it with its own receive time.
 *  - `groupEnvelopeV3` / `ratchetV3` / `pqPublicKey` / `appVersion` — capability claims this
 *    client cannot back. Absent is read as "cannot", which is the truth.
 */
object ProfileUpdateWire {
    const val TYPE = "profile_update"
    const val VERSION = 1

    /**
     * @param nickname our own nickname (sanitized again here). Null ⇒ key omitted, which
     *        every receiver reads as "no opinion" — unless [clear] is set, in which case an
     *        explicit empty string tells receivers the nickname was REMOVED.
     */
    fun encode(nickname: String?, clear: Boolean = false): String {
        val o = JSONObject()
            .put("type", TYPE)
            .put("lastSeen", 0)
            .put("version", VERSION)
        val clean = PeerNickname.sanitize(nickname)
        when {
            clean != null -> o.put(PeerNickname.WIRE_KEY, clean)
            clear -> o.put(PeerNickname.WIRE_KEY, "")
        }
        return ControlPrefix.PROFILE_UPDATE + o.toString()
    }

    /**
     * The legacy `/api/sync/profile` blob the phones share between devices of ONE account:
     * `{userName, profileImageData, profileLinks}` (iOS `MultiDeviceSyncManager
     * .uploadProfileForSync`, Android `uploadProfileForSync`). A whole-blob overwrite, so
     * writing our name must carry every other key through untouched — dropping them would
     * erase the phone's avatar and links from the archive.
     */
    fun mergeLegacyProfileBlob(existing: ByteArray?, nickname: String?): ByteArray {
        val o = existing?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JSONObject()
        val clean = PeerNickname.sanitize(nickname)
        if (clean != null) o.put("userName", clean) else o.remove("userName")
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    /** `userName` out of that blob, sanitized; null when absent, empty or unreadable. */
    fun readLegacyProfileBlob(blob: ByteArray?): String? {
        if (blob == null) return null
        val o = runCatching { JSONObject(String(blob, Charsets.UTF_8)) }.getOrNull() ?: return null
        return PeerNickname.sanitize(o.opt("userName") as? String)
    }
}
