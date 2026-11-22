package com.oshi.desktop.lora

import com.oshi.desktop.store.MediaType
import org.json.JSONObject

/**
 * Media inside a ratchet payload — the shape both phones put on the mesh and the radio.
 *
 * __DESKTOP_LORA_MEDIA_2026_10_28__ The last LoRa gap. A media message's bytes do NOT ride
 * the envelope's own fields: `meshWireSafe()` strips `mediaAttachment` as a size and
 * privacy measure, and what survives is `encryptedContent`, which for a media message is
 * — in iOS's own words at `MessageManager.swift:9566` — *"the base64 of the FULL encrypted
 * media"*. So the media is inside the CIPHERTEXT, as a JSON wrapper that the ratchet
 * encrypts exactly like any text.
 *
 * ============================================================ TWO CONVENTIONS, BOTH READ
 *
 * ```
 *   iOS      {"type":"media", "mediaType":"image", "content":"<base64>"}
 *   Android  {"type":"IMAGE",                      "data":"<base64>"}
 * ```
 *
 * iOS's `parseAndStoreMedia` accepts both (`MessageManager.swift:8690-8700`) and so does
 * [decode]: accepting what a peer might send costs nothing, and refusing one of two shipped
 * spellings turns a photo into an empty bubble. This client WRITES the iOS wrapper, because
 * that is the one both phones read.
 *
 * ============================================================ WHY THIS IS SO SMALL
 *
 * The radio budget is 12 frames × 180 bytes = 2 160 bytes of ENVELOPE, and the media sits
 * under THREE base64 layers on the way there — the wrapper's, the ratchet message's, and
 * the envelope ciphertext's — which compound to about 2.37x before a byte of overhead.
 * [maxMediaBytes] works that back, and the answer is **a couple of hundred bytes**.
 *
 * That is not a photograph and not a thumbnail; it is an icon, a short voice blip, a vCard.
 * The same arithmetic applies to iOS, whose own comment calls this lane *"a true last
 * resort (opt-in, minutes on the air)"* and refuses anything larger as `.tooLarge`. Saying
 * so plainly here matters more than the feature does: a toggle that silently refuses almost
 * everything a person tries is worse than one that explains the ceiling up front.
 *
 * That is why the opt-in exists on both platforms and defaults to OFF. Anything over the
 * ceiling is REFUSED with the number, never truncated: a truncated base64 string decodes to
 * nothing on the far side, and the recipient would see an empty bubble with no error
 * anywhere — the exact failure iOS's LAN mesh carries a named fix for.
 */
object LoRaMediaWire {

    /** `{"type":"media", ...}` — the marker iOS writes and both phones recognise. */
    const val TYPE_MEDIA = "media"

    data class Media(val type: MediaType, val bytes: ByteArray, val fileName: String?) {
        override fun equals(other: Any?): Boolean =
            other is Media && type == other.type && bytes.contentEquals(other.bytes) && fileName == other.fileName

        override fun hashCode(): Int = 31 * (31 * type.hashCode() + bytes.contentHashCode()) + (fileName?.hashCode() ?: 0)
    }

    /** The iOS wrapper. Written, never the Android one — both phones read this. */
    fun encode(media: Media): String {
        val o = JSONObject()
            .put("type", TYPE_MEDIA)
            .put("mediaType", media.type.wire)
            .put("content", java.util.Base64.getEncoder().encodeToString(media.bytes))
        media.fileName?.takeIf { it.isNotBlank() }?.let { o.put("fileName", it) }
        return o.toString()
    }

    /**
     * Read either convention, or null when this plaintext is not media at all.
     *
     * Null is the ordinary case — most payloads are text — so this must be cheap and must
     * never throw: a malformed wrapper is text that happens to start with `{`, not an error.
     */
    fun decode(plaintext: String): Media? {
        val trimmed = plaintext.trim()
        if (!trimmed.startsWith("{")) return null
        val o = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null

        val base64 = o.optString("content", "").ifEmpty { o.optString("data", "") }
        if (base64.isEmpty()) return null

        // iOS puts the kind in `mediaType` and the marker in `type`; Android puts the kind
        // in `type` as an UPPERCASE enum name. Reading `mediaType` first means an iOS
        // wrapper is never mistaken for an Android one whose type is literally "media".
        val declared = o.optString("mediaType", "").ifEmpty { o.optString("type", "") }
        if (declared.isEmpty() || declared.equals(TYPE_MEDIA, ignoreCase = true)) return null

        val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(base64) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null

        val type = MediaType.entries.firstOrNull { it.wire.equals(declared, ignoreCase = true) }
            ?: if (declared.equals("photo", ignoreCase = true)) MediaType.IMAGE else MediaType.UNKNOWN
        val name = o.optString("fileName", "").ifEmpty { null }
        return Media(type, bytes, name)
    }

    /**
     * The largest media this radio can carry, worked back from the frame budget.
     *
     * Deliberately derived rather than a constant: the budget, the base64 inflation and the
     * AEAD overhead are all stated elsewhere, and a hand-tuned number here would drift from
     * them silently and start refusing — or worse, accepting — the wrong size.
     */
    fun maxMediaBytes(): Int {
        // Worked BACKWARDS through the exact path a send takes, because the first version
        // of this counted two base64 layers and there are THREE. A test caught it by
        // building a payload at the advertised ceiling and finding it did not fit — which
        // is the only way an error like this ever shows, since nothing throws.
        //
        //   media M
        //     → wrapper    base64(M) + JSON keys
        //     → ratchet    AEAD(wrapper), base64'd into the ratchet message, + its header
        //     → envelope   base64(ratchet JSON), AEAD'd, base64'd again into `ciphertext`
        //     → + the envelope's own JSON
        //
        // Three (4/3) inflations compound to ~2.37x before any overhead, which is why the
        // answer is as small as it is.
        fun unBase64(n: Int) = n * 3 / 4
        val envelopeOverhead = 700     // ids, keys, timestamps, the JSON around them
        val aead = 28                  // 12-byte nonce + 16-byte tag
        val ratchetHeader = 300        // publicKey, sessionId, counters, timestamp
        val wrapperOverhead = 120      // the wrapper's own JSON keys

        val ciphertextBudget = LoRaFrame.MAX_SEND_BYTES - envelopeOverhead
        val ratchetBase64 = unBase64(ciphertextBudget) - aead
        val ratchetJson = unBase64(ratchetBase64)
        val wrapper = unBase64(ratchetJson - ratchetHeader) - aead
        return unBase64(wrapper - wrapperOverhead).coerceAtLeast(0)
    }
}
