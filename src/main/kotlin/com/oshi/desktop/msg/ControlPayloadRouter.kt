package com.oshi.desktop.msg

import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.MessageStore

/**
 * What a decrypted plaintext turned out to be — the result of PARSING it, never of looking
 * at its prefix.
 *
 * PLAN.md §4.5's rule ("never route by prefix — parse, then branch") has teeth in this row
 * because `🔧ACTION🔧` carries four different operations behind one sentinel, distinguished
 * only by a field inside the JSON. A router that branched on prefixes alone would apply a
 * delete for every pin.
 *
 * [Prose] and [Foreign] are separate answers on purpose. "Not one of MY payloads" and "not a
 * control payload at all" look the same to a caller that only asks `is ControlEvent.Prose`,
 * and they are not the same thing: a `📍LOCATION📍` is a real event that PARITY.md row 0.19
 * owns and that must NOT be shown to a user as if the sender had typed a wall of JSON. The
 * whole catalog is in [ControlPrefix] precisely so this class can say "I know what that is,
 * it is not mine" instead of "I have never heard of it."
 */
sealed class ControlEvent {

    /** `📬DELIVERY_RECEIPT📬<id>` — the peer's device holds [messageId]. */
    data class Delivered(val messageId: String) : ControlEvent()

    /** `📖READ_RECEIPT📖` — the peer opened the conversation. Not per-message; see [receipt]. */
    data class Read(val receipt: ReadReceipt) : ControlEvent()

    /** `⌨️TYPING⌨️` — ephemeral, never stored. */
    data class Typing(val payload: TypingPayload) : ControlEvent()

    /** `🔥REACTION🔥` — add or remove one emoji on one message. */
    data class Reaction(val payload: ReactionPayload) : ControlEvent()

    /** `🔧ACTION🔧` — edit, delete-for-everyone, pin or unpin. */
    data class Action(val payload: MessageActionPayload) : ControlEvent()

    /**
     * A catalogued sentinel that belongs to a different PARITY.md row (location, call
     * summary, group update, profile, wallpaper, reply/forward envelopes…). Carries the
     * CANONICAL prefix and its [ControlPrefix.Kind] so a caller can decide whether it is
     * safe to display, without owning a second copy of the catalog.
     */
    data class Foreign(val prefix: String, val kind: ControlPrefix.Kind) : ControlEvent()

    /**
     * The sentinel was one of this row's, and the body was unusable.
     *
     * Distinct from [Foreign] and from [Prose] because it is the only one of the three that
     * means something is WRONG. A silent payload that fails to parse produces no error a
     * user ever sees — that is exactly how Android's `{"isTyping":true}` typing ping was
     * broken for a whole release while looking fixed. Naming the case is what lets a caller
     * count it.
     */
    data class Unparseable(val prefix: String) : ControlEvent()

    /** Ordinary user text. Includes a message that merely CONTAINS a sentinel mid-string. */
    object Prose : ControlEvent()
}

/**
 * Classifies decrypted plaintext and applies row-0.18 payloads to a [MessageStore].
 *
 * ============================================================ WHAT THIS OWNS
 *
 * The four payload files in this package are codecs: bytes in, data out. They deliberately
 * know nothing about stored messages, because a codec that needed a store could not be
 * tested against a byte shape. This class is the other half — the place where a parsed
 * payload meets local state, and therefore the place where the rules that need both live.
 *
 * There are exactly three such rules, and each of them is a shipped security decision
 * rather than a convenience:
 *
 * ============================================================ 1. THE OWNERSHIP RULE (C-MSG-4)
 *
 * **A delete-for-everyone or an edit is honoured only when the sender of the ACTION is the
 * sender of the TARGET message.** iOS states it twice, once per action, with the reason
 * attached: "a sender may only delete-for-everyone a message they themselves sent. Without
 * this ownership check an attacker can remotely delete ANY message (see security review
 * C-MSG-4)" (`OSHI/MessageManager.swift:7574-7578`, `:7592-7596`). Android states it at
 * `MessageRepository.kt:5675`: "without the check, a crafted 🔧ACTION🔧 could rewrite OUR
 * OWN sent messages in our thread."
 *
 * That last clause is the part worth reading twice. Without the check, a peer can edit a
 * message **we** sent, in **our** copy of the thread — not merely retract their own. The
 * transport authenticates who sent the action; nothing else stops it from naming any id.
 *
 * **The comparison is byte equality on the exact stored spelling.** It is NOT run through
 * `canonicalIdentity` / `normalizeKey`, and that is a deliberate reading of PLAN.md §4.9:
 * those helpers implement equivalence relations *looser than equality* (iOS re-pads and
 * validates decodability, Android strips padding and never validates — different relations,
 * one name), and §4.9's rule is that they must not be applied to an identity check.
 * `MeshProtocol.normalizeKey`'s own doc comment says the same thing about itself: right for
 * "which socket do I write to", catastrophic for "is this really who they say."
 *
 * iOS agrees by construction — `handleMessageAction` uses a bare `==` on
 * `senderPublicKey`, while the RECEIPT paths a few hundred lines away deliberately do
 * canonicalise (`__IDENTITY_CANONICAL_2026_08_15__`). The asymmetry is the point: a receipt
 * that fails to match costs a tick, and an ownership check that matches too eagerly costs a
 * message. This client fails closed in the same place iOS does, and [Outcome.REJECTED_NOT_OWNER]
 * makes the refusal countable rather than silent.
 *
 * ============================================================ 2. STATUS ONLY EVER ADVANCES
 *
 * Receipts move a message forward and never back, and never resurrect a `FAILED` one. That
 * rule already lives in [MessageStore.advanceDeliveryStatus] (ported from iOS's
 * `DeliveryStatus.advanced(to:)`), so this class does not restate it — it just routes into
 * it. Worth knowing why it matters HERE: the relay and the mesh race, so a `delivered`
 * receipt can arrive after the `read` receipt for the same message. Applying receipts in
 * arrival order without the monotonic rule un-reads messages the user has already opened.
 *
 * A read receipt is **not per-message**. Neither platform sends an id with it: iOS's handler
 * takes only the sender and marks every outgoing message to that peer read
 * (`MessageManager.swift:5885-5895`), Android's `handleReadReceipt` selects
 * `getUnreadSentMessages(senderPublicKey)` and flips all of them. [apply] does the same —
 * every message WE sent in that conversation advances to [DeliveryStatus.READ].
 *
 * ============================================================ 3. NOTHING SILENT IS STORED
 *
 * A typing ping, a receipt and an action are `Kind.SILENT`: they must never become a row in
 * a conversation. This class never calls [MessageStore.append] with a control payload's
 * text — it only ever calls the store's typed mutators. That is the difference between
 * "the desktop shows JSON in a bubble" and "the desktop shows the edit."
 *
 * ============================================================ WHAT THIS DOES NOT DO
 *
 * It does not SEND. Nothing in this package decides on its own to put a payload on the wire,
 * for two reasons that are both policy: the debounce and auto-clear windows for typing
 * (5 s / 2 s on both platforms) and the two privacy toggles ("Read Receipts",
 * "Typing Indicators", checked at the send site on both platforms) belong to whatever drives
 * this codec. Wiring these into [com.oshi.desktop.net.V2Router] is the next step and is
 * deliberately not taken here — that file is row 0.12's and this row does not modify it.
 *
 * It does not apply PINS. See [Outcome.PIN_NOT_APPLIED].
 */
class ControlPayloadRouter(private val store: MessageStore) {

    /**
     * What [apply] did. An enum rather than a Boolean because "nothing happened" has five
     * meaningfully different causes here, and collapsing them is how a refused delete
     * becomes indistinguishable from a delete for a message we never received.
     */
    enum class Outcome {
        /** The store changed. */
        APPLIED,

        /** Parsed, valid, and the store already held that state. */
        NO_CHANGE,

        /** Not a payload this row owns (prose, or another row's sentinel). */
        NOT_MINE,

        /** One of this row's sentinels with an unusable body. See [ControlEvent.Unparseable]. */
        UNPARSEABLE,

        /** The action or reaction names a message this conversation has no record of. */
        TARGET_UNKNOWN,

        /**
         * An edit or delete-for-everyone whose sender does not own the target message.
         * The security refusal — see THE OWNERSHIP RULE. Countable on purpose.
         */
        REJECTED_NOT_OWNER,

        /** A typing ping: parsed and returned to the caller, deliberately never stored. */
        TYPING_NOT_STORED,

        /**
         * A valid `pinMessage` / `unpinMessage` that this client understood and did not act
         * on, because there is nowhere to put it yet.
         *
         * iOS keeps a `pinnedMessages: [senderKey: messageId]` map persisted beside the
         * thread (`MessageManager.swift:7607-7615`); [MessageStore] has no equivalent field
         * and adding one is a change to row 0.13's file, which this row does not own.
         * Reporting the outcome instead of dropping it silently is the whole difference
         * between "not implemented" and "quietly broken" — and it is the honest status,
         * given that Android does not send 1:1 pins at all today (see
         * [MessageActionPayload]'s PIN section, which shows why its stated reason is wrong).
         */
        PIN_NOT_APPLIED,
    }

    /**
     * Parse [plaintext] — never guess from its prefix alone. See [ControlEvent].
     *
     * Pure and free of any store access, so the classification can be tested against wire
     * bytes with no fixture at all.
     */
    fun classify(plaintext: String): ControlEvent {
        // Order matters only in that each branch checks its own sentinel; the payload
        // decoders re-verify the prefix themselves, so a reordering cannot cross-route.
        DeliveryReceipt.decode(plaintext)?.let { return ControlEvent.Delivered(it) }
        if (ControlPrefix.match(plaintext) == ControlPrefix.DELIVERY_RECEIPT) {
            // Matched the sentinel, carried no usable id: an ancient bare-prefix receipt.
            return ControlEvent.Unparseable(ControlPrefix.DELIVERY_RECEIPT)
        }

        ReadReceipt.decode(plaintext)?.let { return ControlEvent.Read(it) }

        val prefix = ControlPrefix.match(plaintext)
        if (prefix == ControlPrefix.TYPING_IOS || prefix == ControlPrefix.TYPING_ANDROID) {
            return TypingPayload.decode(plaintext)?.let { ControlEvent.Typing(it) }
                ?: ControlEvent.Unparseable(prefix)
        }
        if (prefix == ControlPrefix.REACTION || prefix == ControlPrefix.REACTION_LEGACY) {
            return ReactionPayload.decode(plaintext)?.let { ControlEvent.Reaction(it) }
                ?: ControlEvent.Unparseable(prefix)
        }
        if (prefix == ControlPrefix.ACTION ||
            plaintext.startsWith(MessageActionPayload.LEGACY_EDIT_PREFIX) ||
            plaintext.startsWith(MessageActionPayload.LEGACY_DELETE_PREFIX)
        ) {
            return MessageActionPayload.decode(plaintext)?.let { ControlEvent.Action(it) }
                ?: ControlEvent.Unparseable(prefix ?: ControlPrefix.ACTION)
        }

        val kind = ControlPrefix.kindOf(plaintext)
        return if (prefix != null && kind != null) ControlEvent.Foreign(prefix, kind) else ControlEvent.Prose
    }

    /**
     * Classify [plaintext] and apply it to [conversationId]'s history.
     *
     * [fromSenderKey] is the sender the TRANSPORT authenticated — the envelope's `from`
     * after the AEAD verified, never a field inside the payload. Everything in this row that
     * needs to know who acted uses this and not `senderPublicKey`, because a payload field
     * is attacker-chosen and the envelope's `from` is not. (The reaction's own
     * `senderPublicKey` is used only as an attribution label when present, exactly as
     * `GroupManager.parseGroupReaction`'s `transportSenderKey` fallback does — and it is the
     * transport key that decides WHOSE reaction is replaced.)
     *
     * "Which messages are mine" — needed by the read receipt, which is not per-message —
     * comes from the store's own [com.oshi.desktop.store.Message.fromMe] flag rather than
     * from a self-address parameter, so this class cannot be handed the wrong identity.
     */
    fun apply(
        conversationId: String,
        fromSenderKey: String,
        plaintext: String,
    ): Pair<ControlEvent, Outcome> {
        val event = classify(plaintext)
        val outcome = when (event) {
            is ControlEvent.Prose, is ControlEvent.Foreign -> Outcome.NOT_MINE
            is ControlEvent.Unparseable -> Outcome.UNPARSEABLE
            is ControlEvent.Typing -> Outcome.TYPING_NOT_STORED
            is ControlEvent.Delivered -> applyDelivered(conversationId, event.messageId)
            is ControlEvent.Read -> applyRead(conversationId)
            is ControlEvent.Reaction -> applyReaction(conversationId, fromSenderKey, event.payload)
            is ControlEvent.Action -> applyAction(conversationId, fromSenderKey, event.payload)
        }
        return event to outcome
    }

    private fun applyDelivered(conversationId: String, messageId: String): Outcome {
        val before = store.message(conversationId, messageId) ?: return Outcome.TARGET_UNKNOWN
        val after = store.advanceDeliveryStatus(conversationId, messageId, DeliveryStatus.DELIVERED)
        return if (after?.deliveryStatus != before.deliveryStatus) Outcome.APPLIED else Outcome.NO_CHANGE
    }

    /**
     * Mark every message WE sent in this conversation read.
     *
     * Not per-message, because no read receipt on either platform carries an id — see rule 2
     * in the class doc. The monotonic rule in [MessageStore.advanceDeliveryStatus] does the
     * rest: a `FAILED` send is not resurrected and an already-`READ` message is untouched,
     * so this is idempotent no matter how many receipts a chatty peer sends.
     */
    private fun applyRead(conversationId: String): Outcome {
        var changed = false
        for (m in store.messages(conversationId)) {
            if (!m.fromMe) continue
            val before = m.deliveryStatus
            val after = store.advanceDeliveryStatus(conversationId, m.id, DeliveryStatus.READ)
            if (after != null && after.deliveryStatus != before) changed = true
        }
        return if (changed) Outcome.APPLIED else Outcome.NO_CHANGE
    }

    /**
     * Apply an add or a remove.
     *
     * The reactor is [fromSenderKey] — the transport's sender — not the payload's
     * `senderPublicKey`. A payload field cannot be allowed to decide whose reaction gets
     * replaced: [MessageStore.setReaction] removes that address's existing reaction first
     * (the shipped "one reaction per person" rule), so honouring an attacker-chosen key
     * would let a peer clear someone else's reaction.
     *
     * There is deliberately NO ownership check here, and the asymmetry with edit/delete is
     * the shipped one: reacting to another person's message is the entire feature, whereas
     * editing another person's message is the C-MSG-4 attack. Neither platform gates a
     * reaction on ownership.
     */
    private fun applyReaction(conversationId: String, fromSenderKey: String, p: ReactionPayload): Outcome {
        val before = store.message(conversationId, p.messageId) ?: return Outcome.TARGET_UNKNOWN
        val after = store.setReaction(
            conversationId, p.messageId, fromSenderKey,
            if (p.isAdding) p.emoji else null,
        )
        return if (after?.reactions != before.reactions) Outcome.APPLIED else Outcome.NO_CHANGE
    }

    /**
     * Apply an edit / delete / pin.
     *
     * The ownership check is the first thing that happens after the target is found, and it
     * is byte equality — see THE OWNERSHIP RULE in the class doc for why it is not
     * normalised. `timestampMs` may be null (a peer that put Unix millis in an Apple-epoch
     * field); the action still applies, and the edit is stamped with the local clock rather
     * than with a date in the year 55 000. That choice is recorded here rather than in
     * [WireClock] because it is a policy about local state, not about the epoch.
     */
    private fun applyAction(conversationId: String, fromSenderKey: String, p: MessageActionPayload): Outcome {
        if (p.actionType == MessageActionPayload.ActionType.PIN_MESSAGE ||
            p.actionType == MessageActionPayload.ActionType.UNPIN_MESSAGE
        ) {
            return Outcome.PIN_NOT_APPLIED
        }

        val target = store.message(conversationId, p.targetMessageId) ?: return Outcome.TARGET_UNKNOWN

        // C-MSG-4. Byte equality, on the exact stored spelling, deliberately un-normalised.
        if (target.senderAddress != fromSenderKey) return Outcome.REJECTED_NOT_OWNER

        val stampMs = p.timestampMs ?: System.currentTimeMillis()
        return when (p.actionType) {
            MessageActionPayload.ActionType.DELETE_FOR_EVERYONE -> {
                if (target.isDeletedForEveryone) return Outcome.NO_CHANGE
                store.deleteForEveryone(conversationId, p.targetMessageId, stampMs)
                Outcome.APPLIED
            }
            MessageActionPayload.ActionType.EDIT_MESSAGE -> {
                val text = p.newContent ?: return Outcome.UNPARSEABLE
                if (target.isDeletedForEveryone) return Outcome.NO_CHANGE
                if (target.content == text) return Outcome.NO_CHANGE
                store.editContent(conversationId, p.targetMessageId, text, stampMs)
                Outcome.APPLIED
            }
            else -> Outcome.PIN_NOT_APPLIED
        }
    }
}
