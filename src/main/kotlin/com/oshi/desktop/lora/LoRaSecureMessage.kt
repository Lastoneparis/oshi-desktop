package com.oshi.desktop.lora

import com.oshi.desktop.msg.WireClock
import java.util.Base64
import org.json.JSONObject

/**
 * The JSON inside the `"OM"` frames — iOS's `SecureMessage`, as Swift's default
 * `JSONEncoder` writes it.
 *
 * `OSHI/MessageManager.swift:8487-8531` (the struct and its explicit `CodingKeys`),
 * `OSHI/MeshtasticManager.swift:509` (put on the LoRa wire), Android's port at
 * `service/lora/LoRaWireMessage.kt`.
 *
 * ============================================================ TWO THINGS THAT SILENTLY
 * BREAK INTEROP
 *
 * **1. THE PLAINTEXT STRIP.** `SecureMessage` has no custom `encode(to:)` and
 * `plaintextContent` IS in its `CodingKeys`, so encoding the message whole puts the user's
 * text **on the air in the clear, beside the ciphertext** — on the default Meshtastic
 * LongFast channel, whose key is public and documented. iOS says so at
 * `MeshtasticManager.swift:486-491`. `meshWireSafe()` is the strip
 * (`MeshNetworkManager.swift:1997-2019`) and the LoRa path was shipped without it once.
 *
 * [toWireJson] applies [wireSafe] **itself**, so the rule cannot be forgotten at a call
 * site. That is the single most important line in this file, and it is why the strip is
 * not left to the caller.
 *
 * `deliveryMethod` is stripped too, for a different reason: it is the only field carrying
 * an enum RAW VALUE across the wire, and a case an older peer has never heard of makes its
 * `Decodable` **throw**, which drops the whole message rather than the field. Each side
 * stamps the transport it actually observed.
 *
 * **2. THE EPOCH.** Every date here is Apple-reference seconds — a `Double` of seconds
 * since 2001-01-01, which is what a bare `JSONEncoder` writes for a `Date`. Unix millis in
 * one of these fields decodes on an iPhone as roughly the year 55 000. The conversion goes
 * through [com.oshi.desktop.msg.WireClock] and nowhere else, per the project rule; this
 * file defines no offset constant of its own, unlike Android's port which restates
 * `978_307_200.0` locally (`LoRaWireMessage.kt:130`).
 *
 * **This client is STRICTER than both phones on both directions of that conversion.**
 * `WireClock.toAppleSeconds` refuses to emit an instant outside [2000, 2100] — a
 * `require`, because we control our own clock. `WireClock.toUnixMillis` returns null for a
 * value that failed the magnitude guard, and [unixMillis] is therefore **nullable**: an
 * implausible date does not cost the message, matching row 0.18's rule, but it is reported
 * as unknown rather than rendered as the year 55 000. Both phones accept any `Double` here.
 *
 * ============================================================ WHICH KEYS ARE REQUIRED
 *
 * iOS's decoder uses plain `decode` (not `decodeIfPresent`) for exactly seven keys
 * (`MessageManager.swift:8521-8528`): `id`, `senderAddress`, `recipientAddress`,
 * `encryptedContent`, `timestamp`, `isRead`, `deliveryStatus`. A frame missing any one of
 * them is **dropped whole** by an iPhone, silently. So they are always emitted, and
 * [fromWireJson] requires the same seven — being more tolerant than the peer means
 * accepting frames that peer would never send and can never read back.
 *
 * A Swift `nil` encodes as an **ABSENT KEY, never JSON `null`**. An explicit null would
 * still decode (`decodeIfPresent` tolerates it) but would spend wire bytes out of a
 * 2 160-byte budget for nothing (see [LoRaFrame]).
 *
 * ============================================================ KEY ORDER: THE TWO PHONES
 * DISAGREE
 *
 * Not a correctness issue — both parse by key — but it destroys a byte diff, which is the
 * cheap way to check parity, so it is worth pinning:
 *
 *  - **iOS** emits in `CodingKeys` declaration order (`MessageManager.swift:8513-8519`):
 *    `id, senderAddress, recipientAddress, encryptedContent, timestamp, isRead,
 *    deliveryStatus, senderPublicKey, recipientPublicKey, plaintextContent,
 *    mediaAttachment, deliveryMethod, mediaType, originalMediaData, mediaFileName,
 *    isViewOnce, hasBeenViewed, editedAt, editedContent, isDeletedForEveryone, replyToId`.
 *  - **Android** groups every always-present key first, then the optionals
 *    (`LoRaWireMessage.kt:93-117`), so `isViewOnce`, `hasBeenViewed` and
 *    `isDeletedForEveryone` move forward past the optional block.
 *
 * **iOS's order is taken**, because iOS is the reference implementation Android's own port
 * and its own tests cite line by line, and because after the strip the three booleans are
 * adjacent anyway in most real frames. [toWireJson] builds the object by hand rather than
 * through `JSONObject`, which on the Maven `org.json` artifact is backed by a `HashMap` and
 * would emit a different order on every JVM (PLAN.md §3).
 */
data class LoRaSecureMessage(
    val id: String,
    val senderAddress: String,
    val recipientAddress: String,
    val encryptedContent: LoRaEncryptedContent,
    /**
     * Unix millis, or **null** when the wire value failed [WireClock]'s magnitude guard.
     * See the class doc: an implausible date does not cost the message.
     */
    val unixMillis: Long?,
    val isRead: Boolean,
    val deliveryStatus: String,
    val senderPublicKey: String = "",
    val recipientPublicKey: String = "",
    /** LOCAL ONLY — stripped by [wireSafe]. */
    val plaintextContent: String? = null,
    /** LOCAL ONLY — stripped by [wireSafe]. */
    val mediaAttachment: ByteArray? = null,
    /** LOCAL ONLY — stripped by [wireSafe]. See the class doc for why. */
    val deliveryMethod: String? = null,
    val mediaType: String? = null,
    /** LOCAL ONLY — stripped by [wireSafe]. */
    val originalMediaData: ByteArray? = null,
    val mediaFileName: String? = null,
    val isViewOnce: Boolean = false,
    val hasBeenViewed: Boolean = false,
    /** Unix millis, or null. Apple-epoch seconds on the wire. */
    val editedAtUnixMillis: Long? = null,
    /** LOCAL ONLY — stripped by [wireSafe]. */
    val editedContent: String? = null,
    val isDeletedForEveryone: Boolean = false,
    val replyToId: String? = null,
) {

    /**
     * True when this message carries media, **which LoRa refuses outright**.
     *
     * The refusal lives before any framing or size check
     * (`MeshtasticManager.swift:449-455`), and iOS's comment explains why it lives there:
     * so every LoRa refusal is recorded in ONE place, because *"my photo won't send
     * off-grid"* is exactly the case where a person needs to be told that LoRa carries text
     * and nothing else.
     */
    val carriesMedia: Boolean get() = mediaAttachment != null || originalMediaData != null

    /**
     * A copy safe to transmit — the five local-only fields cleared
     * (`MeshNetworkManager.swift:2005-2019`).
     *
     * Public so a test can assert the strip on the OBJECT as well as on the bytes, and so a
     * caller that wants to log what it is about to send logs the stripped form.
     */
    fun wireSafe(): LoRaSecureMessage = copy(
        plaintextContent = null,
        mediaAttachment = null,
        originalMediaData = null,
        editedContent = null,
        deliveryMethod = null,
    )

    /**
     * The bytes that go inside the `"OM"` frames. Applies [wireSafe] itself — see the class
     * doc.
     *
     * @throws IllegalArgumentException when [unixMillis] is null or outside
     *   [2000, 2100]. Strict on emit, because the seven-key rule makes `timestamp`
     *   mandatory: there is no "unknown time" to put on the wire, and an iPhone drops a
     *   frame that omits it. A caller with no plausible timestamp has a bug to fix, not a
     *   frame to send.
     */
    fun toWireJson(): ByteArray = wireSafe().toJsonString().toByteArray(Charsets.UTF_8)

    /** [toWireJson] as a String, for diffing and for tests. Also applies [wireSafe]. */
    fun toJsonString(): String {
        val m = wireSafe()
        val ts = m.unixMillis
            ?: throw IllegalArgumentException(
                "cannot emit a LoRa frame with an unknown timestamp — iOS's decoder requires " +
                    "the key (MessageManager.swift:8525) and drops the whole message without it"
            )
        val sb = StringBuilder(512)
        sb.append('{')
        str(sb, "id", m.id, first = true)
        str(sb, "senderAddress", m.senderAddress)
        str(sb, "recipientAddress", m.recipientAddress)
        sb.append(",\"encryptedContent\":").append(m.encryptedContent.toJsonString())
        sb.append(",\"timestamp\":").append(WireClock.jsonNumber(WireClock.toAppleSeconds(ts)))
        sb.append(",\"isRead\":").append(m.isRead)
        str(sb, "deliveryStatus", m.deliveryStatus)
        str(sb, "senderPublicKey", m.senderPublicKey)
        str(sb, "recipientPublicKey", m.recipientPublicKey)
        // plaintextContent / mediaAttachment / deliveryMethod are always absent here:
        // wireSafe() cleared them, and this emitter has no branch that could write them —
        // two independent reasons, which is deliberate. Their SLOTS are kept in this order
        // so the emitted key sequence still matches iOS's CodingKeys for the fields that
        // do survive.
        m.mediaType?.let { str(sb, "mediaType", it) }
        m.mediaFileName?.let { str(sb, "mediaFileName", it) }
        sb.append(",\"isViewOnce\":").append(m.isViewOnce)
        sb.append(",\"hasBeenViewed\":").append(m.hasBeenViewed)
        m.editedAtUnixMillis?.let {
            sb.append(",\"editedAt\":").append(WireClock.jsonNumber(WireClock.toAppleSeconds(it)))
        }
        sb.append(",\"isDeletedForEveryone\":").append(m.isDeletedForEveryone)
        m.replyToId?.let { str(sb, "replyToId", it) }
        sb.append('}')
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean = other is LoRaSecureMessage && id == other.id

    override fun hashCode(): Int = id.hashCode()

    companion object {
        // DeliveryStatus raw values (`MessageManager.swift:8122-8134`).
        const val STATUS_PENDING = "pending"
        const val STATUS_SENT = "sent"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_READ = "read"
        const val STATUS_FAILED = "failed"

        // DeliveryMethod raw values (`MessageManager.swift:7966-7981`). LOCAL ONLY — never
        // on the wire, see the class doc. Here because the RECEIVER stamps one.
        const val METHOD_MESH = "Direct"
        const val METHOD_CLOUD = "Cloud"
        const val METHOD_LORA = "LoRa"
        const val METHOD_PENDING = "Sending..."

        /** The seven keys iOS decodes with plain `decode` (`MessageManager.swift:8521-8528`). */
        val REQUIRED_KEYS = listOf(
            "id", "senderAddress", "recipientAddress", "encryptedContent",
            "timestamp", "isRead", "deliveryStatus",
        )

        /**
         * Decode a reassembled payload, or **null** when it is not a message.
         *
         * Null rather than an exception: a frame that does not parse is a frame, not a
         * crash, and on a radio link a corrupt payload is an ordinary event. iOS logs and
         * drops (`MeshtasticManager.swift:1080-1083`).
         */
        fun fromWireJson(bytes: ByteArray): LoRaSecureMessage? = try {
            fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
        } catch (_: Exception) {
            null
        }

        fun fromJson(o: JSONObject): LoRaSecureMessage? {
            // The seven required keys are required. A message missing one is not a message
            // — and more importantly is not a message an iPhone would have accepted either,
            // so accepting it here would make the desktop uniquely tolerant.
            if (REQUIRED_KEYS.any { !o.has(it) }) return null
            val enc = LoRaEncryptedContent.fromJson(o.optJSONObject("encryptedContent") ?: return null)
                ?: return null
            val ts = o.optDouble("timestamp", Double.NaN)
            return LoRaSecureMessage(
                id = o.optString("id", "").ifEmpty { return null },
                senderAddress = o.optString("senderAddress", ""),
                recipientAddress = o.optString("recipientAddress", ""),
                encryptedContent = enc,
                unixMillis = WireClock.toUnixMillis(ts),
                isRead = o.optBoolean("isRead", false),
                deliveryStatus = o.optString("deliveryStatus", ""),
                senderPublicKey = o.optString("senderPublicKey", ""),
                recipientPublicKey = o.optString("recipientPublicKey", ""),
                plaintextContent = str(o, "plaintextContent"),
                mediaAttachment = b64(o, "mediaAttachment"),
                // An unrecognised transport label is worth nothing and the message is worth
                // everything, so it is carried through as a raw String. A Kotlin enum here
                // would reintroduce exactly the throw the sender's strip exists to avoid
                // (`MessageManager.swift:8036-8046`).
                deliveryMethod = str(o, "deliveryMethod"),
                mediaType = str(o, "mediaType"),
                originalMediaData = b64(o, "originalMediaData"),
                mediaFileName = str(o, "mediaFileName"),
                isViewOnce = o.optBoolean("isViewOnce", false),
                hasBeenViewed = o.optBoolean("hasBeenViewed", false),
                editedAtUnixMillis = if (o.has("editedAt") && !o.isNull("editedAt")) {
                    WireClock.toUnixMillis(o.optDouble("editedAt", Double.NaN))
                } else null,
                editedContent = str(o, "editedContent"),
                isDeletedForEveryone = o.optBoolean("isDeletedForEveryone", false),
                replyToId = str(o, "replyToId"),
            )
        }

        /**
         * `normalizeKey` — fold base64url and padding variants before comparing two public
         * keys (`MeshtasticManager.swift:469-474`, `MeshtasticManager.kt:151-152`).
         *
         * This is the recipient filter that makes LoRa work at all: OSHI **broadcasts** its
         * ciphertext (`MeshtasticManager.swift:497-500`), so every OSHI radio in range sees
         * every envelope and each one decides whether it is the addressee by comparing
         * `recipientPublicKey` to its own. Comparing the two spellings literally would drop
         * a legitimate message whenever one side happened to hold the base64url form — with
         * no error anywhere, which is the same class of silent miss PARITY.md row 0.24
         * records for the sync path key.
         *
         * Note this is NOT a decoding: it is a character substitution on the base64 TEXT,
         * exactly as both phones do it, so a string that was never valid base64 passes
         * through unchanged.
         */
        fun normalizeKey(k: String): String =
            k.replace("-", "+").replace("_", "/").replace("=", "").trim()

        /**
         * Is this envelope addressed to [myPublicKey]?
         *
         * A dedicated function rather than an inline comparison because it is the ONLY
         * addressing this transport has, and because an empty `myPublicKey` must answer
         * **false**: both phones guard `!myKey.isNullOrEmpty()` before comparing
         * (`MeshtasticManager.kt:1115-1118`), and a client with no identity loaded that
         * treated every broadcast as its own would surface every nearby conversation's
         * ciphertext to its own pipeline.
         */
        fun isAddressedToMe(message: LoRaSecureMessage, myPublicKey: String): Boolean =
            myPublicKey.isNotEmpty() &&
                normalizeKey(message.recipientPublicKey) == normalizeKey(myPublicKey)

        private fun str(o: JSONObject, key: String): String? =
            if (o.has(key) && !o.isNull(key)) o.optString(key, "").ifEmpty { null } else null

        private fun b64(o: JSONObject, key: String): ByteArray? {
            val s = str(o, key) ?: return null
            return try {
                Base64.getDecoder().decode(s)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun str(sb: StringBuilder, key: String, value: String, first: Boolean = false) {
            if (!first) sb.append(',')
            sb.append('"').append(key).append("\":\"")
                .append(com.oshi.messenger.network.v2.OSHICryptoV2.jsonEscape(value)).append('"')
        }
    }
}

/**
 * `EncryptedMessage` — the nested object that actually carries the ciphertext
 * (`OSHI/IdentityManager.swift:787-792`).
 *
 * **This is the LEGACY Double Ratchet, not V2.** `ciphertext` is
 * `base64( AES-GCM_{HKDF(X25519 shared)}( base64( JSON( DoubleRatchetMessage ) ) ) )`
 * (`MessageRepository.kt:4368-4409`), and the ratchet header inside it is JSON — a base64
 * DH public key of 44 characters plus six more fields — which is why an EMPTY stripped
 * envelope is already ~1 114 bytes and why the 12-frame budget in [LoRaFrame] exists at all.
 * PARITY.md row 0.16 records that this legacy stack is Android-coupled and has no published
 * vectors; this codec therefore stops at [ciphertext] and treats it as an opaque String,
 * exactly as the radio layers on both phones do (`MeshtasticManager.kt:63-65`: the radio
 * neither encrypts nor decrypts).
 *
 * `signature` is emitted as the **empty string** by Android (`MessageRepository.kt:4401`)
 * and iOS does not verify it. It is carried, not checked, and it is not an authenticator:
 * the authentication on this path is the ratchet succeeding.
 */
data class LoRaEncryptedContent(
    val ciphertext: String,
    val signature: String,
    val senderPublicKey: String,
    /** Unix millis, or null — Apple-epoch seconds on the wire, same rule as the outer one. */
    val unixMillis: Long?,
) {
    fun toJsonString(): String {
        val ts = unixMillis
            ?: throw IllegalArgumentException("encryptedContent.timestamp is required on the wire")
        val esc = com.oshi.messenger.network.v2.OSHICryptoV2::jsonEscape
        return "{\"ciphertext\":\"${esc(ciphertext)}\"," +
            "\"signature\":\"${esc(signature)}\"," +
            "\"senderPublicKey\":\"${esc(senderPublicKey)}\"," +
            "\"timestamp\":${WireClock.jsonNumber(WireClock.toAppleSeconds(ts))}}"
    }

    companion object {
        fun fromJson(o: JSONObject): LoRaEncryptedContent? {
            if (!o.has("ciphertext")) return null
            return LoRaEncryptedContent(
                ciphertext = o.optString("ciphertext", "").ifEmpty { return null },
                signature = o.optString("signature", ""),
                senderPublicKey = o.optString("senderPublicKey", ""),
                unixMillis = WireClock.toUnixMillis(o.optDouble("timestamp", Double.NaN)),
            )
        }
    }
}
