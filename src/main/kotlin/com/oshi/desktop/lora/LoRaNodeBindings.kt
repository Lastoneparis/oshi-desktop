package com.oshi.desktop.lora

import java.util.Locale

/**
 * Which OSHI identity a Meshtastic node number speaks for — and the rule that a node number
 * on its own **proves nothing**.
 *
 * Port of `OSHI/LoRaNodeIdentityMap.swift` / `service/lora/LoRaNodeIdentityMap.kt`, minus
 * the persistence (the desktop's store belongs to whoever wires this up, and a class that
 * reached into a preferences file could not be unit-tested).
 *
 * ============================================================ WHAT A NODE NUMBER IS
 *
 * A Meshtastic `uint32`, assigned by the RADIO FIRMWARE. **It is not derived from the OSHI
 * public key in any way** — there is no hash, no truncation, no announcement carrying a
 * signature. It arrives as `MeshPacket.from`, an unauthenticated integer field on a channel
 * whose encryption key is the public default LongFast key
 * (`MeshtasticManager.swift:1102-1104`, `LoRaPacketParser.kt:221`).
 *
 * So the mapping is **LEARNED**, and this file is the whole of the learning rule.
 *
 * ============================================================ THE PROOF RULE
 *
 * [bind] may be called from exactly one place: **after the Double Ratchet itself has
 * produced the plaintext.**
 *
 * Android enforces this with a counter it snapshots around the decrypt and compares
 * afterwards (`MessageRepository.kt:3610,3635-3645`, bumped at the single line `:4324`
 * immediately after `doubleRatchet.decrypt(...)`); iOS with
 * `lastRatchetVerifiedMessageId == receivedMessage.id` (`MessageManager.swift:5990-5994`).
 * Android's own comment on the bridge says it plainly: *"a claimed `senderPublicKey` proves
 * nothing, and anyone with a radio can put a contact's (openly shared) public key into an
 * envelope"* (`LoRaIdentityBridge.kt:20-24`).
 *
 * The radio layer therefore does NOT call this — it cannot decrypt, so it has no proof.
 * That separation is why this class takes no radio types and lives in no callback.
 *
 * ============================================================ THE TWO CONFLICT RULES
 *
 * Both directions, and the second one was an iOS fix dated 2026-08-10 for a hole in the
 * first (`LoRaNodeIdentityMap.swift:239-251`):
 *
 *  1. **One node claiming two identities** → the binding is marked `ambiguous`
 *     ([LearnResult.CONFLICT]) and stops routing. The obvious case.
 *  2. **Two nodes claiming ONE identity** → **both** are marked ambiguous. This is the
 *     direction an attacker uses: bind your own node to a contact's already-bound identity
 *     and every raw text you transmit is filed in that contact's conversation. Which of the
 *     two is the impostor is not knowable here, and picking one would be a coin flip on
 *     whose messages get attributed to a real contact — so neither routes, both fall back
 *     to their own `lora!` threads, and [forget] is the way out.
 *
 * Once ambiguous, a node stays ambiguous ([LearnResult.IGNORED_AMBIGUOUS]) until [forget].
 * That is the honest response to a user saying "that isn't them".
 *
 * ============================================================ AND THE PART THAT IS STILL
 * NOT SAFE
 *
 * [adoptRawTextIntoContactThread] is **false by default on both shipped platforms**, and
 * this client keeps it false, because the thing it enables cannot be made sound at this
 * layer. Android's comment is quoted rather than paraphrased because it is the clearest
 * statement of the risk in either tree (`LoRaNodeIdentityMap.kt:318-341`):
 *
 * > *"a raw Meshtastic text packet is UNAUTHENTICATED. Its `from` field is an unsigned
 * > integer on a channel whose key is public, so anyone in radio range can transmit a
 * > packet claiming any node number, including one this map has legitimately bound. Filing
 * > that into a contact's end-to-end-encrypted conversation renders unverified plaintext
 * > identically to a message the ratchet authenticated. […] Turn it on once interop bubbles
 * > carry a visible 'unverified · plaintext over LoRa' marker."*
 *
 * With the flag off, a stock-Meshtastic node cannot impersonate an OSHI contact: every raw
 * text lands in its own `lora!<nodehex>` thread ([fallbackConversationKey]). With it on, it
 * can, and there is no cryptographic difference the receiver could detect. **This client
 * exposes the flag and defaults it off; it does not offer a UI for turning it on, because
 * the marker that would make it honest does not exist yet.**
 *
 * Note this affects INTEROP only. OSHI-to-OSHI over LoRa rides portnum 256 with a real
 * envelope and routes by `recipientPublicKey` ([LoRaSecureMessage.isAddressedToMe]) without
 * consulting this map at all.
 *
 * ============================================================ NOT THREAD-SAFE
 *
 * Deliberately, like [LoRaReassembler]: the shipped clients confine this to one queue, and
 * a lock here would hide from the caller that the radio callback and the message pipeline
 * must not both touch it.
 */
class LoRaNodeBindings {

    /** What [bind] did. Every outcome is distinct because they need different UI. */
    enum class LearnResult {
        /** New, unambiguous, and now routing. */
        BOUND,

        /** Already bound to this same identity. Nothing changed. */
        UNCHANGED,

        /** A conflict in either direction; the affected bindings are now ambiguous. */
        CONFLICT,

        /** This node is already ambiguous and will not be re-bound until [forget]. */
        IGNORED_AMBIGUOUS,

        /** [publicKey] did not pass [looksLikeIdentityKey]. */
        REJECTED,
    }

    data class Binding(val publicKey: String, val learnedAtMillis: Long, val ambiguous: Boolean)

    private val bindings = LinkedHashMap<String, Binding>()

    /** A snapshot, keyed by the 8-hex node key. For persistence and for tests. */
    fun snapshot(): Map<String, Binding> = LinkedHashMap(bindings)

    /** Restore a persisted snapshot. Replaces everything. */
    fun restore(saved: Map<String, Binding>) {
        bindings.clear()
        bindings.putAll(saved)
    }

    /**
     * Record that [node] speaks for [publicKey].
     *
     * **Call this ONLY after the ratchet produced the plaintext.** See the class doc: a
     * claimed sender field is not proof, and this class cannot tell the difference — the
     * caller must.
     *
     * @param nowMillis the learn time, passed in rather than read, so a test can drive it.
     */
    fun bind(node: UInt, publicKey: String, nowMillis: Long): LearnResult {
        val trimmed = publicKey.trim()
        if (!looksLikeIdentityKey(trimmed)) return LearnResult.REJECTED

        val key = nodeKey(node)
        val existing = bindings[key]
        if (existing != null) {
            return when {
                existing.ambiguous -> LearnResult.IGNORED_AMBIGUOUS
                normalizeKey(existing.publicKey) == normalizeKey(trimmed) -> LearnResult.UNCHANGED
                else -> {
                    // Rule 1: one node, two identities.
                    bindings[key] = existing.copy(ambiguous = true)
                    LearnResult.CONFLICT
                }
            }
        }

        // Rule 2 (the 2026-08-10 iOS fix): two nodes, one identity. Mark BOTH.
        val other = bindings.entries.firstOrNull {
            it.key != key && !it.value.ambiguous &&
                normalizeKey(it.value.publicKey) == normalizeKey(trimmed)
        }
        return if (other != null) {
            bindings[other.key] = other.value.copy(ambiguous = true)
            bindings[key] = Binding(trimmed, nowMillis, ambiguous = true)
            LearnResult.CONFLICT
        } else {
            bindings[key] = Binding(trimmed, nowMillis, ambiguous = false)
            LearnResult.BOUND
        }
    }

    /**
     * The OSHI public key this node has PROVED, or null.
     *
     * Null for an ambiguous binding as well as for an absent one — that is the point of the
     * ambiguous flag, and collapsing the two would route a contested node.
     */
    fun boundKey(node: UInt): String? =
        bindings[nodeKey(node)]?.takeIf { !it.ambiguous }?.publicKey

    /** True when this node is bound but contested. Distinct from "unknown", for the UI. */
    fun isAmbiguous(node: UInt): Boolean = bindings[nodeKey(node)]?.ambiguous == true

    /**
     * Drop what is known about a node — **the only way out of `ambiguous`**, and the honest
     * response to a user saying "that isn't them" (`LoRaNodeIdentityMap.swift:270-276`).
     */
    fun forget(node: UInt) {
        bindings.remove(nodeKey(node))
    }

    /**
     * The conversation a RAW Meshtastic text from this node belongs in.
     *
     * Returns the bound identity only when [adoptRawTextIntoContactThread] is on — which it
     * is not, by default, on any of the three clients. Otherwise, and always for an unbound
     * or ambiguous node, the `lora!<nodehex>` fallback.
     *
     * The flag is read HERE, at the one place the decision is made, rather than at each call
     * site: a routing switch that has to be remembered at three call sites is a switch that
     * will be forgotten at one.
     */
    fun conversationKey(node: UInt): String {
        if (adoptRawTextIntoContactThread) {
            boundKey(node)?.let { return it }
        }
        return fallbackConversationKey(node)
    }

    companion object {
        /**
         * **Default OFF, and left off.** See the class doc — with this on, an unauthenticated
         * plaintext packet is rendered identically to a ratchet-authenticated message.
         * `LoRaNodeIdentityMap.kt:318-341`, `LoRaNodeIdentityMap.swift:157-176`.
         */
        @Volatile
        @JvmStatic
        var adoptRawTextIntoContactThread: Boolean = false

        /** `lora!` — the prefix of every synthetic conversation key this file produces. */
        const val FALLBACK_PREFIX = "lora!"

        /**
         * A node number's persisted key: fixed 8-digit lowercase hex
         * (`LoRaNodeIdentityMap.swift:111-113`). Masked to 32 bits so a sign-extended value
         * can never widen it.
         */
        fun nodeKey(node: UInt): String =
            String.format(Locale.US, "%08x", node.toLong() and 0xFFFFFFFFL)

        /**
         * `"lora!<nodehex>"` (`swift:118-120`). **Never changed**, which is precisely why
         * existing `lora!…` conversations keep working across upgrades.
         */
        fun fallbackConversationKey(node: UInt): String = FALLBACK_PREFIX + nodeKey(node)

        /** True for the synthetic node-keyed ids this file produces (`swift:123-125`). */
        fun isFallbackConversationKey(key: String): Boolean = key.startsWith(FALLBACK_PREFIX)

        /**
         * A cheap sanity filter so a synthetic key, an empty string or a truncated fragment
         * can never become a binding (`swift:130-136`).
         *
         * Three checks: at least 16 characters, not a `lora!` key, and containing no `!` at
         * all. **16 is a floor, not a format check** — a real OSHI identity key is 44
         * characters of base64 Curve25519, and this deliberately does not verify that,
         * because a stricter format test here would silently stop binding the day a key
         * encoding changes. The `!` rule is the load-bearing one: it is what stops a
         * fallback key being fed back in and becoming a binding to itself.
         */
        fun looksLikeIdentityKey(key: String): Boolean {
            val t = key.trim()
            if (t.length < 16) return false
            if (isFallbackConversationKey(t)) return false
            if (t.contains("!")) return false
            return true
        }

        /**
         * Fold base64url and padding variants before comparing two keys — delegated to
         * [LoRaSecureMessage.normalizeKey] rather than restated, because the recipient
         * filter and the conflict rule must agree about when two spellings are one key. Two
         * copies of that function is how a node gets locked out of routing by a "conflict"
         * with itself.
         */
        fun normalizeKey(k: String): String = LoRaSecureMessage.normalizeKey(k)
    }
}
