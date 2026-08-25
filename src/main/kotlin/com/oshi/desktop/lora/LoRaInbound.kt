package com.oshi.desktop.lora

/**
 * What to do with a packet off the radio — the routing switch, the recipient filter, and
 * the interop decision, in one place.
 *
 * Port of the receive half of `MeshtasticManager` on both platforms
 * (`OSHI/MeshtasticManager.swift:1013-1105`, `service/lora/MeshtasticManager.kt:1039-1120`).
 * Pure: it takes bytes and a clock and returns a decision. No radio, no callbacks, no
 * threads — which is what lets the whole receive path be driven from a test.
 *
 * ============================================================ ROUTING IS BY PORTNUM, AND
 * BY NOTHING ELSE
 *
 * ```
 * portnum 256 (PRIVATE_APP) → an OSHI "OM" chunk  → reassemble → parse → filter → deliver
 * portnum 1   (TEXT_MESSAGE_APP) → raw Meshtastic text from a non-OSHI device
 * portnum 5   (ROUTING_APP)      → the fate of a want_ack packet
 * anything else                  → ignored
 * ```
 *
 * There is **no magic-byte sniffing on portnum 1**: the whole payload is taken as UTF-8
 * text (`MeshtasticManager.kt:1081-1082`). So the two lanes are separated by a field the
 * sender chooses — which is fine for the OSHI lane, where the ciphertext is the
 * authenticator, and is the entire problem on the interop lane. See below.
 *
 * ============================================================ THE RECIPIENT FILTER IS THE
 * ADDRESSING
 *
 * OSHI **broadcasts** its ciphertext (`MeshtasticManager.swift:497-500`): every OSHI radio
 * in range receives every envelope, and each one decides whether it is the addressee by
 * comparing `recipientPublicKey` to its own, folding base64url spellings first
 * ([LoRaSecureMessage.isAddressedToMe]).
 *
 * That is a filter, not routing, and two consequences follow that a caller must not
 * misread:
 *
 *  - **Everyone nearby holds your ciphertext.** Confidentiality rests entirely on the
 *    ratchet; the Meshtastic channel underneath is the default LongFast one, whose key is
 *    public and is documented as public in the shipped source
 *    (`MeshtasticManager.swift:486-491`).
 *  - **A client with no identity loaded must filter NOTHING through.** Both phones guard
 *    `!myKey.isNullOrEmpty()` before comparing (`MeshtasticManager.kt:1115-1118`); without
 *    it, an empty key would match nothing and — depending on how the comparison is
 *    written — could match everything. [route] therefore returns [Decision.Ignored] for an
 *    empty identity rather than treating the ambiguity as a caller problem.
 *
 * ============================================================ THE INTEROP LANE IS
 * UNAUTHENTICATED, AND STAYS QUARANTINED
 *
 * A portnum-1 packet is plain text from a stock Meshtastic device. It carries no message
 * id, no timestamp, no key and no signature — only `MeshPacket.from`, an unsigned integer
 * the radio firmware writes and anyone in range can claim.
 *
 * So [Decision.InteropText] carries the `lora!<nodehex>` fallback conversation key by
 * default, never a contact's identity, and [LoRaNodeBindings.adoptRawTextIntoContactThread]
 * — off on all three clients — is the only thing that would change that. The shipped
 * comment explaining why is quoted in [LoRaNodeBindings]; the short version is that filing
 * an unauthenticated plaintext packet into an end-to-end-encrypted conversation renders it
 * **identically to a message the ratchet authenticated**.
 *
 * [Decision.InteropText.unverified] is on every interop decision, always, so a UI cannot
 * render one without having been handed the fact that it is unverified.
 *
 * ============================================================ ONE DIAGNOSTIC WORTH KEEPING
 *
 * iOS notes at `MeshtasticManager.swift:1013-1020` that *"two OSHI phones should NEVER
 * reach here"* — arriving in the interop lane for a peer you know is an OSHI peer means the
 * envelope lane failed. [Decision.InteropText.boundKey] is exposed for exactly that: it is
 * non-null when this node HAS proved an identity, which makes "an OSHI peer just sent me
 * raw text" a detectable condition rather than a silent downgrade.
 */
class LoRaInbound(
    private val bindings: LoRaNodeBindings = LoRaNodeBindings(),
    private val reassembler: LoRaReassembler = LoRaReassembler(),
) {

    /** What the caller should do with a packet. */
    sealed class Decision {

        /** Nothing to do: wrong portnum, malformed frame, not addressed to us, or
         *  still waiting for more chunks. Deliberately one case, not five — the caller's
         *  action is identical and splitting it would invite acting on the difference. */
        object Ignored : Decision()

        /**
         * A complete OSHI envelope addressed to this identity. [message] is the parsed
         * `SecureMessage`; its `encryptedContent.ciphertext` is still the legacy
         * Double-Ratchet blob and this class has not opened it.
         *
         * [nodeNum] is carried so the caller can bind it **after** the ratchet succeeds —
         * never before. See [LoRaNodeBindings].
         */
        data class Envelope(val message: LoRaSecureMessage, val nodeNum: UInt) : Decision()

        /**
         * Plain text from a stock Meshtastic node.
         *
         * @property conversationKey where it should be filed — `lora!<nodehex>` unless the
         *   adopt flag is on.
         * @property boundKey the identity this node has PROVED, if any. Non-null here means
         *   an OSHI peer sent raw text, which should not happen (see the class doc).
         * @property unverified always true. Present as a field rather than as a comment so
         *   a renderer has to acknowledge it.
         */
        data class InteropText(
            val text: String,
            val nodeNum: UInt,
            val conversationKey: String,
            val boundKey: String?,
            val unverified: Boolean = true,
        ) : Decision()

        /** A `ROUTING_APP` reply — the fate of a `want_ack` packet. Carried, not parsed. */
        data class Routing(val nodeNum: UInt, val payload: ByteArray) : Decision() {
            override fun equals(other: Any?): Boolean =
                other is Routing && nodeNum == other.nodeNum && payload.contentEquals(other.payload)

            override fun hashCode(): Int = nodeNum.hashCode() * 31 + payload.contentHashCode()
        }
    }

    /**
     * Route one `MeshPacket`.
     *
     * @param meshPacket the raw `MeshPacket` protobuf, as [LoRaProto.parseMeshPacket] takes.
     * @param myPublicKey this client's identity. **Empty means "no identity loaded"** and
     *   every envelope is ignored — see the class doc.
     * @param nowMillis drives the reassembly TTL. A parameter, never a clock read inside,
     *   so the 180-second expiry can be watched happening.
     */
    fun route(meshPacket: ByteArray, myPublicKey: String, nowMillis: Long): Decision {
        val p = LoRaProto.parseMeshPacket(meshPacket) ?: return Decision.Ignored
        return when (p.portnum) {
            LoRaProto.PORT_OSHI -> oshiChunk(p, myPublicKey, nowMillis)
            LoRaProto.PORT_TEXT -> interop(p)
            LoRaProto.PORT_ROUTING -> Decision.Routing(p.from, p.payload)
            else -> Decision.Ignored
        }
    }

    private fun oshiChunk(p: LoRaProto.Incoming, myPublicKey: String, nowMillis: Long): Decision {
        val whole = reassembler.accept(p.payload, nowMillis) ?: return Decision.Ignored
        val message = LoRaSecureMessage.fromWireJson(whole) ?: return Decision.Ignored
        if (!LoRaSecureMessage.isAddressedToMe(message, myPublicKey)) return Decision.Ignored
        return Decision.Envelope(message, p.from)
    }

    private fun interop(p: LoRaProto.Incoming): Decision {
        val text = String(p.payload, Charsets.UTF_8)
        return Decision.InteropText(
            text = text,
            nodeNum = p.from,
            conversationKey = bindings.conversationKey(p.from),
            boundKey = bindings.boundKey(p.from),
        )
    }

    /**
     * Record that a node speaks for an identity — **only after the ratchet produced the
     * plaintext**.
     *
     * Exposed here rather than only on [LoRaNodeBindings] so the whole receive story reads
     * in one file, and so the ordering constraint sits next to the [Decision.Envelope] that
     * must precede it.
     */
    fun bindAfterRatchetProof(nodeNum: UInt, publicKey: String, nowMillis: Long) =
        bindings.bind(nodeNum, publicKey, nowMillis)

    /** Access for a caller that needs to show or forget a binding. */
    fun bindings(): LoRaNodeBindings = bindings

    companion object {
        /**
         * The largest typed text, in UTF-8 bytes, that survives the 12-frame cap
         * (`OSHI/MessageSendPolicy.swift:47`, `MessageSendPolicy.kt:39`).
         *
         * **Measured, not guessed**, and the derivation is worth keeping because it is what
         * makes 500 defensible (`MessageSendPolicy.swift:22-35`): the 2 160-byte budget is
         * spent on the ENVELOPE, not the text — a stripped `SecureMessage` costs ~1 114
         * bytes empty, and every plaintext byte costs ~1.83 wire bytes once it has been
         * through AES-GCM → `DoubleRatchetMessage` JSON → AES-GCM → base64 → JSON. 572
         * UTF-8 bytes is the exact ceiling that still fits; 500 is the shipped value,
         * leaving ~130 bytes of headroom for the fields that vary — the message id, a
         * `senderAddress` that is sometimes a 44-character key and sometimes a short
         * address, and `replyToId`.
         *
         * So the honest statement of the MTU story is not "a LoRa packet is 237 bytes". It
         * is: **a LoRa message is 12 frames of 180, and after the ratchet that buys you
         * about 500 characters.**
         */
        const val LORA_SAFE_UTF8_BYTES = 500
    }
}
