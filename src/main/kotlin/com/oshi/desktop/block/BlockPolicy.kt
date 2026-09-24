package com.oshi.desktop.block

import com.oshi.desktop.store.ContactStore

/**
 * Blocked contacts — PARITY.md row 0.21, the ENFORCEMENT half.
 *
 * `ContactStore` already stores blocking as a flag (row 0.14) and already refuses to make
 * blocking destructive. That is the storage question, and it was answered. This file
 * answers the other one: what a shipped OSHI client actually DOES with that flag, which
 * turns out to be a list of specific, unobvious behaviours, several of which a
 * from-scratch implementation gets wrong in a way nobody notices until a blocked contact
 * is talking to someone again.
 *
 * ============================================================ WHAT BLOCKING DOES BEYOND THE FLAG
 *
 * **1. It is checked at every single receive ingress, not in the UI.** iOS's own comment
 * counts nine (`MessageRepository.kt:5122-5128` enumerating
 * `MessageManager.swift:5759`, `PushNotificationManager.swift:99,180`,
 * `VoiceCallManager.swift:5603,6335`, `VoIPPushManager.swift:1355`,
 * `ScheduledCallManager.swift:208`, plus the conversation builder at `:8352,8405`).
 * Android mirrors it at seven repository ingresses — mesh `:2666`, legacy VPS `:2920`,
 * V2 text `:3163`, LoRa envelope `:3423`, LoRa interop text `:3503`, V2 file `:3773`,
 * cross-platform mesh `:4379` — plus four in `FCMService`. Android's comment records why
 * that matters: blocking used to be an in-memory boolean in a ViewModel that reset on
 * every screen entry, `blockContact()` had ZERO callers, and "a blocked contact still
 * delivered, still rang, still notified."
 *
 * **2. Where the check sits relative to DECRYPTION is not one answer, it is two.**
 *
 *   - On transports where the envelope carries the sender key in the clear — mesh, legacy
 *     VPS, LoRa — the drop happens BEFORE any decryption work
 *     (`MessageRepository.kt:2665`, "🚫 Blocked sender — drop before decryption, storage
 *     or notification"). The sender key there is only a CLAIM, but a claim is enough to
 *     refuse someone.
 *   - On V2 it happens AFTER decryption, in `dispatchV2Inbound`, which is handed an
 *     already-decrypted plaintext and a ratchet-authenticated sender. It could not be
 *     otherwise, and not only because the identity is unknown before the ratchet runs:
 *     **the ratchet must advance for a blocked sender too.** Skipping the decrypt would
 *     leave the receiving chain one step behind the peer's sending chain, so every later
 *     message from them — including every message after an UNBLOCK — would be
 *     undecryptable. Blocking is a reversible policy; a desynchronised ratchet is not.
 *
 * The desktop's relay path is V2 only, so [inbound] is an AFTER-decrypt gate and
 * `OshiClient.receive` calls it exactly where Android calls its own.
 *
 * **3. Blocking must NOT hold the relay acknowledgement.** No shipped client suppresses
 * an ack for a blocked sender, and the desktop must not either. `V2Router.poll` advances
 * the ack cursor from the poll loop, above the delivery callback; a block that refused to
 * ack would pin `lastSeq` behind the blocked envelope forever, so every message from
 * everyone else that arrived later would be re-pulled on every poll and never get past
 * it. The envelope is decrypted, acked, and dropped on the floor — in that order.
 *
 * **4. Notifications are suppressed separately from delivery.** iOS gates the local
 * notification itself (`PushNotificationManager.swift:99`) as well as the group banner,
 * both for a blocked GROUP and for a blocked SENDER inside an unblocked group (`:174`,
 * `:180`); Android does the same in `FCMService` at four sites. The desktop has no push
 * (PARITY.md 2.3) — the equivalent is that [inbound] returns before `OshiClient.onMessage`
 * fires, so nothing downstream of it can surface the message at all.
 *
 * **5. Blocked contacts vanish from the conversation list**, not merely from the contact
 * picker. iOS's `updateConversations` skips them when folding messages into conversations
 * (`MessageManager.swift:8352`) AND skips the pending-conversation merge for a blocked key
 * (`:8405`), so a QR scan of someone you have blocked does not resurrect the thread.
 * Android filters them out of member pickers (`GroupChatViewModel.kt:244`,
 * `ChatViewModel.kt:677`). History is never deleted — the rows stay, they are just not
 * offered. [filterConversations] is that rule.
 *
 * **6. Calls are refused in BOTH directions, and the incoming refusal is silent.**
 * Outgoing: iOS `VoiceCallManager.startCall` throws `CallError.peerBlocked` (`:5603`),
 * Android `CallManager.kt:365` refuses. Incoming: iOS returns without ringing and without
 * telling the caller anything — "Silently decline - don't even notify the caller we
 * received it" (`:6335`) — and Android drops at `CallManager.kt:669`,
 * `IncomingCallService.kt:106`. Scheduled-call events from a blocked peer are dropped too
 * (`ScheduledCallManager.swift:208`, `.kt:194`). The desktop has no calls (PARITY.md 2.1),
 * so [outgoingCall] exists as the decision that row will need and nothing calls it yet;
 * it is here because the answer belongs with the rest of the policy, not scattered later.
 *
 * **7. Outgoing TEXT is NOT suppressed by either phone.** Worth stating because it is the
 * intuitive assumption and it is wrong: there is no block check on any send path in
 * either tree. `OshiClient.send` refuses anyway and returns `SendOutcome.BLOCKED` — a
 * desktop-only strictness, declared here rather than left to be discovered, on the
 * grounds that a client which lets you type into a thread you have blocked is offering a
 * conversation it will then refuse to complete.
 *
 * **8. It syncs — on one platform.** Android puts `isBlocked` in the multi-device contact
 * sync payload and applies it on receipt with a per-contact last-write-wins timestamp
 * (`MultiDeviceSyncManager.kt:563`, `:869`). iOS's `BlockedContactsManager` persists to
 * `Documents/blockedContacts.json` and appears in no sync payload at all, so an iPhone
 * user's blocks do not follow them to a second device. The desktop keeps blocks local
 * because multi-device sync is PARITY.md row 0.24 and has not started; this paragraph is
 * the note that row 0.24 owes the `isBlocked` field when it does.
 *
 * ============================================================ THE COMPARISON IS NORMALIZED
 *
 * This is the part that is easy to get wrong and impossible to notice.
 *
 * Both platforms compare block-list keys through the SAME fold — trim whitespace,
 * `-`→`+`, `_`→`/`, drop `=` padding — and both say why in as many words. iOS:
 * "without this, a contact blocked as `eAbMQUdW…cyc=` would NOT match an incoming call
 * signaling `eAbMQUdW…cyc` (no padding) and the call would ring"
 * (`BlockedContactsManager.swift:80-88`). Android: "so a block cannot be evaded by
 * re-sending under a differently-padded spelling of the same key"
 * (`MessageRepository.kt:5130-5132`).
 *
 * `ContactStore.isBlocked` is an exact map lookup, because a contact store is keyed by
 * the address it was told. That is correct for a store and wrong for a block check, and
 * [isBlocked] here is the difference: exact hit first (the fast path both platforms also
 * take), then a normalized sweep. Every ingress must go through THIS function, never
 * through `ContactStore.isBlocked` directly.
 *
 * ============================================================ WHERE THEY DISAGREE, AND ONE PLACE BOTH ARE WRONG
 *
 * - **Sync (§8 above).** Android syncs the flag, iOS does not. Stricter side is Android's
 *   — a block the user set on one device should not silently not apply on another — but
 *   it cannot be implemented ahead of row 0.24, so it is recorded, not built.
 * - **Group blocking.** iOS has a whole second list, `BlockedGroup` keyed by group UUID,
 *   enforced in the notification path (`PushNotificationManager.swift:174`). Android has
 *   no group blocking whatsoever. Stricter side is iOS's, and it is deliberately NOT
 *   implemented here: groups are PARITY.md row 0.17 and have not started, and enforcement
 *   for a capability that does not exist cannot be exercised by any test — it would be
 *   ceremony that reads as coverage. Row 0.17 owes this.
 * - **Unblocking, where BOTH platforms have the same bug.** `isBlocked` is normalized on
 *   both, but unblock is exact-match on both: iOS `firstIndex(where: { $0.publicKey ==
 *   publicKey })` (`BlockedContactsManager.swift:130`) and Android
 *   `contactDao.setBlocked(publicKey, false)`, which is strict SQL `=`. So a contact
 *   blocked under one spelling and unblocked under another stays blocked — and on Android
 *   it looks unblocked, because the in-memory cache removal DOES normalize
 *   (`MessageRepository.kt:5071`), right up until the next launch reseeds the cache from
 *   the database and the block comes back. [unblockEverySpelling] sweeps instead. This is
 *   the one place this file knowingly diverges from both shipped clients, and it diverges
 *   toward the user being able to undo their own decision.
 *
 * Stale identities (`StaleIdentityManager` / `StaleIdentityStore`) share this exact
 * normalization and sit immediately after every block check in both trees. They are a
 * different question — "this key is no longer that person" rather than "I do not want
 * this person" — and a different row.
 */
object BlockPolicy {

    /**
     * The fold both platforms compare block-list keys through.
     *
     * Byte-for-byte `BlockedContactsManager.normalizeKey` (`swift:85-90`) and
     * `MessageRepository.normalizeBase64Key` (`kt:214-219`).
     *
     * The result is deliberately NOT valid base64 — the padding is gone. It is a
     * comparison scratch value and must never be stored or put on a wire; that job
     * belongs to `ContactQr.canonicalAddress`, which re-pads and verifies instead.
     */
    fun normalizeKey(key: String): String =
        key.trim().replace('-', '+').replace('_', '/').replace("=", "")

    /**
     * Is this peer blocked? The ONLY block question any ingress may ask.
     *
     * Exact hit first — that is the common case and it is what both platforms check
     * before paying for the sweep — then a normalized comparison against every contact.
     * An empty address is not blocked (matching `isBlockedSender`'s first line); it is
     * also not deliverable, but that is a different refusal and not this function's to
     * make.
     */
    fun isBlocked(contacts: ContactStore, address: String): Boolean {
        if (address.isBlank()) return false
        if (contacts.isBlocked(address)) return true
        val wanted = normalizeKey(address)
        if (wanted.isEmpty()) return false
        return contacts.all().any { it.blocked && normalizeKey(it.address) == wanted }
    }

    /**
     * Clear the block on every stored spelling of [address].
     *
     * @return how many contact rows were unblocked. Zero means nothing matched, which is
     *   a legitimate outcome (already unblocked, or never blocked) and not an error.
     */
    fun unblockEverySpelling(contacts: ContactStore, address: String, atMs: Long = System.currentTimeMillis()): Int {
        val wanted = normalizeKey(address)
        if (wanted.isEmpty()) return 0
        var n = 0
        for (c in contacts.all()) {
            if (c.blocked && normalizeKey(c.address) == wanted) {
                contacts.unblock(c.address, atMs)
                n++
            }
        }
        return n
    }

    // ------------------------------------------------------------------ the decisions

    /** What to do with a decrypted inbound message. */
    enum class Inbound {
        /** Store it, surface it, count it. */
        DELIVER,

        /**
         * Drop it: nothing stored, nothing surfaced, no contact `lastSeen` bump.
         *
         * The envelope has ALREADY been decrypted and the ratchet has ALREADY advanced,
         * and the relay ack is not this decision's business — see §2 and §3 of the class
         * note. "Drop" means drop locally, not "refuse to acknowledge".
         */
        DROP_BLOCKED,
    }

    /**
     * The receive gate. Called after decryption, before storage — the position
     * `dispatchV2Inbound` uses (`MessageRepository.kt:3163`).
     */
    fun inbound(contacts: ContactStore, from: String): Inbound =
        if (isBlocked(contacts, from)) Inbound.DROP_BLOCKED else Inbound.DELIVER

    /** What to do with an outbound attempt. */
    enum class Outbound { ALLOW, REFUSE_BLOCKED }

    /**
     * The send gate for TEXT.
     *
     * Neither phone has this check (§7). The desktop does, and this function is where
     * that divergence is visible instead of buried in a caller.
     */
    fun outgoingText(contacts: ContactStore, peer: String): Outbound =
        if (isBlocked(contacts, peer)) Outbound.REFUSE_BLOCKED else Outbound.ALLOW

    /**
     * The gate BOTH phones do have: an outgoing call to a blocked peer is refused
     * (`VoiceCallManager.swift:5603` throws `CallError.peerBlocked`, `CallManager.kt:365`).
     *
     * PARITY.md row 2.1 is now its caller — see
     * [com.oshi.desktop.call.CallStateMachine.startCall].
     */
    fun outgoingCall(contacts: ContactStore, peer: String): Outbound =
        if (isBlocked(contacts, peer)) Outbound.REFUSE_BLOCKED else Outbound.ALLOW

    /**
     * The INCOMING call gate, added for PARITY.md row 2.1. §6 of the class note is its
     * specification; this is the function that row calls.
     *
     * ============================================================ THE REFUSAL IS SILENT
     *
     * It returns [Inbound.DROP_BLOCKED] and the caller must send NOTHING back — not a
     * decline, not a busy, not an error. That is the shipped behaviour on both platforms
     * and both wrote down why. iOS, `VoiceCallManager.swift:6373-6377`:
     *
     * ```swift
     * if BlockedContactsManager.shared.isBlocked(peerPublicKey) {
     *     // Silently decline - don't even notify the caller we received it
     *     return
     * }
     * ```
     *
     * Android drops the same way at `EnhancedCallManager.kt:2486-2489` and again in
     * `IncomingCallService.kt:100-127`. The reason is the one §1 of this note already
     * paid for on the message side: **a refusal that is observable tells the blocked
     * person they are blocked.** A decline is indistinguishable from "the user pressed
     * Decline"; silence is indistinguishable from "the phone is off". Only the second is
     * a block.
     *
     * This is also why row 0.21's defect 1 — iOS answering a blocked sender's message
     * with a delivery receipt — is a real bug and not a nitpick: the call path gets this
     * right on both platforms and the message path does not.
     *
     * ============================================================ WHERE IT SITS: AFTER DECRYPT
     *
     * Same answer as [inbound], for a DIFFERENT reason, and the difference is worth
     * stating because it changes what "before decryption" could even mean here.
     *
     * On V2 the ratchet must advance for a blocked sender or the chain desynchronises
     * (§2). A call signal is sealed with a STATIC-static ECDH
     * ([com.oshi.desktop.call.CallSignalCrypto]) — there is no chain and nothing to
     * desynchronise, so that argument does not apply. What applies instead is that the
     * TYPE of a call packet is byte 0 of the *plaintext*: until the seal is opened, a
     * ring and a hang-up are the same opaque blob. Android names this exactly —
     * `handleCallSignal` calls `resolveSignalType`, which decrypts at
     * `EnhancedCallManager.kt:2334`, purely to read the type byte, and only then reaches
     * the block check at `:2486`.
     *
     * So the block is evaluated after the AEAD and before ANY ring, state change or
     * notification. The envelope's `sender` field is available earlier and is
     * deliberately not used for this: it is an unauthenticated claim on a server with no
     * authentication (`call_server.js:65`), so gating on it would let anyone suppress a
     * call by spoofing a blocked sender's name.
     *
     * ============================================================ AND A GAP WE INHERIT
     *
     * On iOS the FIRST gate is in the VoIP push handler, before any decrypt
     * (`VoIPPushManager.swift:1355-1364`), because a push carries the caller key in
     * cleartext. The desktop has no push at all (PARITY.md row 2.3), so that gate has no
     * desktop equivalent and needs none — there is no path here that learns a caller
     * before the packet arrives.
     */
    fun incomingCall(contacts: ContactStore, from: String): Inbound =
        if (isBlocked(contacts, from)) Inbound.DROP_BLOCKED else Inbound.DELIVER

    /**
     * Hide blocked peers from a conversation list.
     *
     * iOS `MessageManager.swift:8352` (fold) and `:8405` (the pending-conversation merge,
     * which is what stops a QR scan from resurrecting a blocked thread). Nothing is
     * deleted — [ContactStore] rule 1 and `MessageStore` both keep their rows; this only
     * decides what is OFFERED.
     */
    fun <T> filterConversations(items: List<T>, contacts: ContactStore, keyOf: (T) -> String): List<T> {
        if (items.isEmpty()) return items
        // One pass over the contact list instead of one per item: a conversation list is
        // walked on every refresh, and `contacts.all()` reads (and may parse) the store.
        val blocked = contacts.all().asSequence()
            .filter { it.blocked }
            .map { normalizeKey(it.address) }
            .filter { it.isNotEmpty() }
            .toHashSet()
        if (blocked.isEmpty()) return items
        return items.filterNot { normalizeKey(keyOf(it)) in blocked }
    }
}
