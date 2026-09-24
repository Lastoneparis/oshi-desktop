package com.oshi.desktop.net

import com.oshi.desktop.DesktopEnvelope
import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.group.GroupFanout
import com.oshi.desktop.store.PrekeyStore
import com.oshi.desktop.store.SessionStore
import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.OSHIRatchetV2
import com.oshi.messenger.network.v2.V2RetryBudget
import com.oshi.messenger.network.v2.V2Session
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One decrypted inbound message. */
data class V2Inbound(
    val from: String,
    val msgId: String,
    val ts: Long,
    val groupId: String?,
    val text: String,
    /** __PER_DEVICE_MAILBOX_2026_09_23__ The sender's device, on a per-device copy; null otherwise. */
    val fromDevice: String? = null,
)

/**
 * Raised when an X3DH first message names prekeys we cannot use.
 *
 * TRANSIENT by construction, and that is the whole point of it being an exception: the
 * peer may republish, our signed prekey may be the one the NEXT envelope names, and a
 * one-time prekey may be findable after a store reload. Returning null instead would
 * tell [V2Router.poll] the envelope was undecryptable FOREVER — which acks it, and the
 * relay then drops it. The first message from a new contact is exactly the message this
 * loses, with no error and no way back.
 */
class V2X3DHException(message: String) : Exception(message)

/**
 * The V2 transport, orchestrated — PARITY.md row 0.12, desktop port of Android
 * `V2MessageRouter` / iOS `V2MessageRouter` + `MessageManager+V2`.
 *
 * This is where the pieces below it become a messenger: sessions are established,
 * ratchets advance, the relay is polled and acked. It is also where every rule that
 * cannot be enforced anywhere else lives, and each of them is a bug this project has
 * already paid for:
 *
 *  - **Persist the advanced ratchet BEFORE the network send.** Saving on success instead
 *    rewinds the chain on failure, and the next send re-derives the same (key, nonce) for
 *    a different plaintext.
 *  - **The X3DH header rides message #1 only.** Re-attaching it names an ephemeral and a
 *    one-time prekey the responder already burned; the shared secret then differs and the
 *    conversation cannot recover.
 *  - **Never ack an envelope you could not decrypt** — but bound the holding, or one
 *    permanently-undecryptable envelope pins the cursor forever and every message behind
 *    it is re-pulled and never delivered. Eight attempts, then let it pass.
 *  - **Responder-side TOFU.** The initiator's X3DH identity key must equal the envelope's
 *    `from`, because in OSHI the address IS the X25519 identity. Android shipped without
 *    this check, and anyone able to POST a relay envelope could bootstrap a session under
 *    a spoofed sender.
 *  - **Burn a one-time prekey only after the message AEAD-verifies.** Peek, verify, then
 *    consume — burning first destroys the key a legitimate retry needs.
 *  - **One bundle fetch per send.** `GET /v2/keys/:peer` pops a one-time prekey
 *    server-side, so probing capability and then sending would burn two of the peer's
 *    pool per conversation.
 *
 * Threading: the compound load→ratchet→save is serialized by [ratchetLock] across the
 * send path AND inbound handling, because a concurrent send and poll on the same peer
 * would otherwise interleave their read-modify-write. Network I/O is never done under
 * that lock. Delivery is a synchronous callback invoked BEFORE the ack, so a message is
 * durably handled before the relay is told it may drop it.
 */
class V2Router(
    private val identity: DesktopIdentity,
    private val config: V2ConfigGate,
    private val keys: V2KeysClient,
    private val messages: V2MessagesClient,
    private val sessions: SessionStore,
    private val prekeys: PrekeyStore,
    private val state: RouterState,
    private val log: (String) -> Unit = {},
    /**
     * __PER_DEVICE_MAILBOX_2026_09_23__ This install as a device of its account. Null, or not
     * [DeviceMailbox.active] (flag off / not registered / removed), means every path below is
     * exactly the pre-existing one.
     */
    private val devices: DeviceMailbox? = null,
) {

    /** Invoked for every decrypted message, BEFORE the relay is acked. Must be durable. */
    var onMessage: (V2Inbound) -> Unit = {}

    /**
     * __PER_DEVICE_MAILBOX_2026_09_23__ A `sent-copy` another device of this account encrypted
     * for this one (CLIENT_SPEC.md §3.6): to be stored as an OUTGOING row. Called BEFORE the ack,
     * like [onMessage]. Never routed through [onMessage]: it is not something a contact said.
     */
    var onSentCopy: (SentCopy) -> Unit = {}

    /**
     * Account envelopes this device could not open and dropped WITHOUT a retry, a reset or a
     * heal signal because they were not addressed to it (§3.4). The reset-storm check reads it.
     */
    @Volatile var foreignDrops: Int = 0
        private set

    private val ratchetLock = ReentrantLock()
    private val pollLock = ReentrantLock()
    private val bundleCache = ConcurrentHashMap<String, com.oshi.messenger.network.v2.FetchedBundle>()
    private val retryBudget = V2RetryBudget(state.retryStore())

    private val myUserKey: String get() = identity.userKey
    private val myPair: OSHICryptoV2.X25519Pair get() = identity.identity

    // ------------------------------------------------------------------ capability

    /** V2 enabled AND this peer is reachable over it (existing session, or a fetchable bundle). */
    fun ensureCapable(peerUserKey: String): Boolean {
        if (!config.isEnabledCached()) return false
        if (activeDevices()?.peerDevices(peerUserKey)?.isNotEmpty() == true) return true
        if (sessions.has(peerUserKey)) return true
        return fetchBundleCached(peerUserKey) != null
    }

    fun refreshConfig() = config.refresh(myUserKey)

    /**
     * Publish our prekey bundle, or top it up — only when V2 is enabled, and only when
     * the pool needs it.
     *
     * `publish` APPENDS one-time prekeys while REPLACING the long-lived material, and the
     * server pops the OLDEST first. Minting twenty per launch unconditionally — which is
     * what both mobile clients did until they measured it — grows the pool without bound
     * and leaves its head a dead generation. Ask the server for the count, and publish
     * exactly the shortfall.
     */
    fun publishBundleIfNeeded(): Boolean {
        if (!config.isEnabled(myUserKey)) return false
        return try {
            // __PER_DEVICE_MAILBOX_2026_09_23__ The ACCOUNT bundle has ONE owner. A second
            // install of the same identity publishing it resets the owner's pool and steals
            // its new sessions (CLIENT_SPEC.md §1-§3.2). [DeviceMailbox.mayPublishAccountBundle]
            // says whether this install is that owner; "no" is not a failure (return true: the
            // bundle that is live is valid, it is just not ours to replace).
            val policy = devices
            if (!prekeys.hasPublished()) {
                if (policy != null && !policy.mayPublishAccountBundle(null)) return true
                return keys.publish(OPK_TARGET) != null
            }
            when (val remaining = keys.remainingCount()) {
                // Count unavailable → do NOT mint. Guessing is what grew the pool; the
                // bundle already on the server is still valid.
                null -> true
                else -> when {
                    remaining >= OPK_LOW_WATER -> true
                    policy != null && !policy.mayPublishAccountBundle(remaining) -> true
                    else -> keys.publish(OPK_TARGET - remaining) != null
                }
            }
        } catch (e: Exception) {
            log("publishBundle failed: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------ send

    /**
     * Send [plaintext] to [peerUserKey] over V2.
     *
     * @param msgId the APP's stable message id, so one logical message keeps ONE id
     *        across every transport (mesh / relay) and the receiver's cross-transport
     *        dedup works.
     * @param groupId set for ONE LEG of a group fan-out (PARITY.md row 0.17): the
     *        envelope then carries `type:"group"` and the group id, which is the only
     *        thing that routes it — the group plaintext is bare base64 of a JSON
     *        `GroupMessage` with no sentinel prefix, so a dropped `groupId` lands it as
     *        base64 garbage in the 1:1 thread. The two fields are built by
     *        [com.oshi.desktop.group.GroupFanout.envelopeFields], never here, so that
     *        row's guard is ON the send path rather than beside it.
     * @return false means NOT SENT — the caller falls back to another transport.
     */
    fun sendText(
        peerUserKey: String,
        plaintext: ByteArray,
        msgId: String? = null,
        groupId: String? = null,
        /**
         * __PER_DEVICE_MAILBOX_2026_09_23__ Also send a `sent-copy` to this account's other
         * devices, in the same batch (§3.6). For USER messages only — never receipts/typing.
         * Ignored outside device mode. A group send asks via [sendSelfCopy] once instead.
         */
        selfCopy: Boolean = false,
    ): Boolean {
        if (!config.isEnabledCached()) return false
        activeDevices()?.let { dm ->
            return try {
                sendDeviceMode(dm, peerUserKey, plaintext, msgId ?: UUID.randomUUID().toString(), groupId, selfCopy)
            } catch (e: Exception) {
                log("sendText (device mode) failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
        val routing = groupId?.let { GroupFanout.envelopeFields(it) }
        return try {
            // The bundle fetch is network I/O and stays OUTSIDE the ratchet lock, so a
            // slow fetch cannot block an in-flight receive on another peer.
            val bundle = if (sessions.load(peerUserKey) == null) takeBundle(peerUserKey) ?: return false else null

            val envelope = ratchetLock.withLock {
                // Re-read inside the lock: a concurrent inbound may have established or
                // advanced this session while we were fetching.
                val session = sessions.load(peerUserKey) ?: run {
                    val b = bundle ?: return@sendText false
                    // ONE initiator() call: each mints fresh ephemerals, so the state and
                    // the header must come from the same one.
                    val init = V2Session.initiator(myPair, b)
                    SessionStore.Record(
                        peerUserKey = peerUserKey,
                        role = SessionStore.Role.INITIATOR,
                        ratchet = init.state,
                        peerIdentityKey = b.identityKey,
                        peerSigningKey = b.signingKey,
                        pendingInitiatorHeader = init.x3dh,
                    )
                }
                val x3dh = session.pendingInitiatorHeader
                session.pendingInitiatorHeader = null
                val (header, ct) = OSHIRatchetV2.encrypt(session.ratchet, plaintext, EMPTY)
                // BEFORE the send. A failed send costs one skipped sequence number, which
                // the peer ignores; a rewound chain costs a reused (key, nonce).
                sessions.save(session)
                DesktopEnvelope(
                    msgId = msgId ?: UUID.randomUUID().toString(),
                    from = myUserKey,
                    to = peerUserKey,
                    type = routing?.get("type") ?: "1to1",
                    groupId = routing?.get("groupId"),
                    x3dh = x3dh,
                    header = OSHICryptoV2.encodeHeader(header),
                    ciphertext = ct,
                    ts = System.currentTimeMillis(),
                )
            }
            messages.send(envelope).isNotEmpty()
        } catch (e: Exception) {
            log("sendText failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Establish an initiator session without sending; the X3DH header rides whatever goes first. */
    fun ensureSession(peerUserKey: String): Boolean {
        // Device mode: the per-device sessions are created by the fan-out itself.
        if (activeDevices()?.peerDevices(peerUserKey)?.isNotEmpty() == true) return true
        if (sessions.has(peerUserKey)) return true
        val bundle = takeBundle(peerUserKey) ?: return false
        return ratchetLock.withLock {
            if (sessions.has(peerUserKey)) return@withLock true
            val init = V2Session.initiator(myPair, bundle)
            sessions.save(
                SessionStore.Record(
                    peerUserKey, SessionStore.Role.INITIATOR, init.state,
                    bundle.identityKey, bundle.signingKey, init.x3dh,
                )
            )
            true
        }
    }

    // ------------------------------------------------------------------ receive

    /**
     * One poll: pull → decrypt → deliver → ack. Safe to call on a timer.
     *
     * @return how many messages were delivered.
     */
    fun poll(): Int = pollLock.withLock {
        if (!config.isEnabledCached()) return 0
        activeDevices()?.let { return pollDevice(it) }
        val pull = messages.pull(myUserKey, state.lastSeq()) ?: return 0
        val (delivered, ackSeq) = processPull(pull, deviceView = null)
        if (ackSeq > state.lastSeq() && messages.ack(myUserKey, ackSeq)) state.setLastSeq(ackSeq)
        state.flush()
        delivered
    }

    /**
     * Decrypt → deliver every envelope of one pull, in seq order, and say how far it may be
     * acked. Shared by the legacy view ([deviceView] null) and the device view.
     *
     * Hold the ack BEFORE the earliest failed seq so the relay keeps anything we could not
     * decrypt — but only while the retry budget lasts, and only for envelopes ADDRESSED to this
     * device: one that was not (§3.4) is let go on the first failure, with no retry, no reset.
     */
    private fun processPull(pull: V2Pull, deviceView: DeviceMailbox?): Pair<Int, Long> {
        var delivered = 0
        var lowestFailedSeq = Long.MAX_VALUE
        fun hold(seq: Long) { if (seq in 0 until lowestFailedSeq) lowestFailedSeq = seq }

        for (env in pull.messages.sortedBy { it.seq }) {
            val route = routeInbound(deviceView, env)
            if (route == null) { foreignDrops++; continue }
            if (!state.markSeen(env.msgId)) continue

            val plaintext: ByteArray? = try {
                ratchetLock.withLock { decryptInbound(env, route.sessionDevice, route.pool) }
            } catch (e: Exception) {
                // The ratchet was NOT advanced.
                state.unmarkSeen(env.msgId)
                if (!route.addressed) {
                    // THE RULE (§3.4): an envelope that was not addressed to this device never
                    // costs a retry, a session reset, a heal signal or an error row. The device
                    // holding the account bundle has it; devsync brings it here.
                    foreignDrops++
                    log("account envelope ${env.msgId.take(8)} not decryptable on this device — dropped quietly")
                } else if (retryBudget.shouldRetry(env.msgId)) {
                    // Hold the seq so the relay keeps the envelope — bounded, so one poisoned
                    // envelope cannot wedge the mailbox.
                    hold(env.seq)
                    log("inbound ${env.msgId.take(8)} from ${env.from.take(8)}… held: ${e.message}")
                } else {
                    log("giving up on ${env.msgId.take(8)} after ${V2RetryBudget.MAX_RETRIES} attempts: ${e.message}")
                }
                continue
            }
            if (plaintext == null) {                // undecryptable-forever → let it be acked
                if (!route.addressed) foreignDrops++
                continue
            }
            retryBudget.clear(env.msgId)

            try {
                if (env.fromDevice != null && isSelf(env.from)) {
                    // Another device of this account (§3.6). Only a sent-copy becomes anything.
                    SentCopy.parse(plaintext)?.let { onSentCopy(it.copy(fromDevice = env.fromDevice)); delivered++ }
                } else {
                    onMessage(V2Inbound(env.from, env.msgId, env.ts, env.groupId, String(plaintext, Charsets.UTF_8), env.fromDevice))
                    delivered++
                }
            } catch (e: Exception) {
                // The ratchet is ALREADY spent — this envelope can never be decrypted
                // again, so holding the seq would poison the queue for nothing.
                log("deliver failed after decrypt; ${env.msgId.take(8)} dropped locally: ${e.message}")
            }
        }
        val ackSeq = if (lowestFailedSeq == Long.MAX_VALUE) pull.maxSeq else lowestFailedSeq - 1
        return delivered to ackSeq
    }

    /**
     * Decrypt one envelope, advancing and persisting the ratchet.
     *
     * @return the plaintext, or null when the message is undecryptable FOREVER — which is
     *         only "first contact with no X3DH header", a no-op the caller may ack.
     * @throws Exception on a genuine failure, so the caller holds the seq.
     */
    private fun decryptInbound(
        env: DesktopEnvelope,
        // __PER_DEVICE_MAILBOX_2026_09_23__ the session is (from, sessionDevice) and a first
        // message is answered with [pool] — this device's own pre-keys for a per-device copy,
        // the account pre-keys otherwise. Defaults = the legacy path, unchanged.
        sessionDevice: String? = null,
        pool: PrekeyStore = prekeys,
    ): ByteArray? {
        val header = OSHICryptoV2.decodeHeader(env.header)
        val existing = sessions.load(env.from, sessionDevice)

        if (existing == null) {
            val x = env.x3dh ?: return null       // nothing to establish from
            val (fresh, opkId) = establishResponder(env.from, x, pool)
            val pt = OSHIRatchetV2.decrypt(fresh, header, env.ciphertext, EMPTY)   // throws if forged
            opkId?.let { pool.consumeOneTimePreKeyPriv(it) }                       // burn AFTER verify
            sessions.save(responderRecord(env.from, fresh, x.identityKey), sessionDevice)
            return pt
        }

        return try {
            val pt = OSHIRatchetV2.decrypt(existing.ratchet, header, env.ciphertext, EMPTY)
            existing.pendingInitiatorHeader = null      // the peer answered → stop attaching x3dh
            sessions.save(existing, sessionDevice)
            pt
        } catch (e: Exception) {
            // Recovery: a message carrying an X3DH header that the current session cannot
            // open → re-establish as responder and retry, exactly as both platforms do.
            // The replacement is saved only once the message AEAD-verifies, so an envelope
            // that was never meant for this session cannot reset it.
            val x = env.x3dh ?: throw e
            val (fresh, opkId) = establishResponder(env.from, x, pool)
            val pt = OSHIRatchetV2.decrypt(fresh, header, env.ciphertext, EMPTY)
            opkId?.let { pool.consumeOneTimePreKeyPriv(it) }
            sessions.save(responderRecord(env.from, fresh, x.identityKey), sessionDevice)
            pt
        }
    }

    /** @return the responder state and the one-time prekey id to burn once it verifies. */
    private fun establishResponder(
        sender: String,
        x: V2Session.X3DHHeader,
        prekeys: PrekeyStore = this.prekeys,
    ): Pair<OSHIRatchetV2.State, String?> {
        // RESPONDER-SIDE TOFU. The initiator's X3DH identity key MUST equal their routing
        // address — the address IS the X25519 identity. Without this, anyone who can POST
        // a relay envelope bootstraps a session under a SPOOFED `from`: the shared secret
        // derives from THEIR keys, the AEAD verifies because they own them, and the
        // message is stored as coming from the impersonated contact. Byte equality on the
        // exact wire string; no base64 normalisation, which would widen it.
        val claimed = Base64.getEncoder().encodeToString(x.identityKey)
        if (claimed != sender) {
            throw SecurityException("x3dh.identityKey != sender (${sender.take(12)}…) — possible impersonation")
        }
        val spk = prekeys.currentSignedPreKey()
        if (spk.keyId != x.signedPreKeyId) {
            // Our signed prekey rotated: this shared secret cannot be derived with what we
            // hold now. Transient — the peer refetches our bundle and tries again.
            throw V2X3DHException("signed prekey mismatch (they want ${x.signedPreKeyId.take(8)}…, we hold ${spk.keyId.take(8)}…)")
        }
        var opkPair: OSHICryptoV2.X25519Pair? = null
        val opkId = x.oneTimePreKeyId
        if (opkId != null) {
            val priv = prekeys.oneTimePreKeyPrivPeek(opkId)
                ?: throw V2X3DHException("unknown one-time prekey ${opkId.take(8)}…")
            opkPair = OSHICryptoV2.X25519Pair(priv, OSHICryptoV2.x25519PubFromPriv(priv))
        }
        return V2Session.responder(myPair, spk.pair, opkPair, x) to opkId
    }

    private fun responderRecord(peer: String, state: OSHIRatchetV2.State, peerIdentity: ByteArray) =
        SessionStore.Record(peer, SessionStore.Role.RESPONDER, state, peerIdentity, null, null)

    // ------------------------------------------------------------------ device mode
    // __PER_DEVICE_MAILBOX_2026_09_23__ CLIENT_SPEC.md §3.3-§3.6.

    private fun activeDevices(): DeviceMailbox? = devices?.takeIf { it.active }

    private fun isSelf(userKey: String) = DeviceAuth.normalizeIdentity(userKey) == DeviceAuth.normalizeIdentity(myUserKey)

    /** How one inbound envelope is opened on THIS device, or null = not ours: ack past it, silently. */
    private class InboundRoute(val sessionDevice: String?, val pool: PrekeyStore, val addressed: Boolean)

    /**
     * @param dm the device view, or null for the legacy view. In the legacy view an account
     *        envelope is handled EXACTLY as before; a per-device copy (queued while the flag was
     *        on, shown to the legacy view after it turned off) is still opened when it is ours.
     */
    private fun routeInbound(dm: DeviceMailbox?, env: DesktopEnvelope): InboundRoute? {
        val to = env.toDevice
        if (to == null) {
            // An account envelope (old sender). In device view only the account-bundle holder
            // is its real recipient; every other device merely TRIES it (§3.4).
            return InboundRoute(null, prekeys, addressed = dm?.holdsAccountBundle ?: true)
        }
        val mine = dm ?: devices ?: return null
        return when {
            // The server filters these; if one is seen anyway it is someone else's copy.
            to != mine.deviceId -> null
            isSelf(env.from) && env.fromDevice == null -> null
            // A copy encrypted for this device: its own pre-keys, session (from, fromDevice).
            else -> InboundRoute(env.fromDevice, mine.devicePrekeys, addressed = true)
        }
    }

    /** One poll of THIS DEVICE's view: its own cursor, device-signed pull and ack. */
    private fun pollDevice(dm: DeviceMailbox): Int {
        val call = messages.pullDevice(myUserKey, state.deviceLastSeq())
        if (!call.ok) {
            when (call.code) {
                410 -> dm.onDeviceUnknown()                 // removed: alert, back to legacy next tick
                409 -> dm.onMailboxDisabled()               // the flag went off server-side
                else -> log("device poll → HTTP ${call.code} ${call.error ?: ""}")
            }
            return 0
        }
        val pull = call.value!!
        val (delivered, ackSeq) = processPull(pull, deviceView = dm)
        if (ackSeq > state.deviceLastSeq()) {
            val ack = messages.ackDevice(myUserKey, ackSeq)
            when {
                ack.code in 200..299 -> state.setDeviceLastSeq(ackSeq)
                ack.code == 410 -> dm.onDeviceUnknown()
                ack.code == 409 -> dm.onMailboxDisabled()
            }
        }
        state.flush()
        return delivered
    }

    /**
     * One encrypted envelope for (peer, device?) — device null = the legacy/account session.
     * Same rules as the legacy send: bundle fetch outside the ratchet lock, ONE initiator()
     * call, the X3DH header on the first message only, ratchet persisted BEFORE the network.
     * Null = no usable bundle for that target.
     */
    private fun encryptFor(
        peer: String,
        device: String?,
        plaintext: ByteArray,
        msgId: String,
        routing: Map<String, String>?,
        fromDevice: String?,
    ): DesktopEnvelope? {
        val bundle = if (sessions.load(peer, device) == null) {
            (if (device == null) takeBundle(peer) else keys.fetchDeviceBundle(peer, device)) ?: return null
        } else null
        return ratchetLock.withLock {
            val session = sessions.load(peer, device) ?: run {
                val b = bundle ?: return null
                val init = V2Session.initiator(myPair, b)
                SessionStore.Record(peer, SessionStore.Role.INITIATOR, init.state, b.identityKey, b.signingKey, init.x3dh)
            }
            val x3dh = session.pendingInitiatorHeader
            session.pendingInitiatorHeader = null
            val (header, ct) = OSHIRatchetV2.encrypt(session.ratchet, plaintext, EMPTY)
            sessions.save(session, device)
            DesktopEnvelope(
                msgId = msgId, from = myUserKey, to = peer,
                type = routing?.get("type") ?: "1to1", groupId = routing?.get("groupId"),
                x3dh = x3dh, header = OSHICryptoV2.encodeHeader(header), ciphertext = ct,
                ts = System.currentTimeMillis(), toDevice = device, fromDevice = fromDevice,
            )
        }
    }

    /** One recipient identity of a fan-out: its device list, or null = one account envelope. */
    private class Leg(val to: String, val plaintext: ByteArray, val routing: Map<String, String>?, var devices: List<String>?, val isSelfCopy: Boolean)

    private fun sendDeviceMode(dm: DeviceMailbox, peer: String, plaintext: ByteArray, msgId: String, groupId: String?, selfCopy: Boolean): Boolean {
        val routing = groupId?.let { GroupFanout.envelopeFields(it) }
        val toSelf = isSelf(peer)
        // §3.5 step 1-2: the recipient's devices. Could not ask → today's path, unchanged.
        val peerDevices = (if (toSelf) dm.ownOtherDevices() else dm.peerDevices(peer))
            ?: return sendLegacyOnly(peer, plaintext, msgId, routing)
        val legs = ArrayList<Leg>()
        legs += Leg(peer, plaintext, routing, peerDevices.takeIf { it.isNotEmpty() }, isSelfCopy = false)
        if (selfCopy && !toSelf) {
            val mine = dm.ownOtherDevices().orEmpty()
            if (mine.isNotEmpty()) {
                val conv = if (groupId != null) SentCopy.conversationGroup(groupId) else SentCopy.conversation1to1(peer)
                val copy = SentCopy.encode(conv, String(plaintext, Charsets.UTF_8), msgId, System.currentTimeMillis())
                legs += Leg(myUserKey, copy, null, mine, isSelfCopy = true)
            }
        }
        return postFanout(dm, msgId, legs, fallback = { sendLegacyOnly(peer, plaintext, msgId, routing) })
    }

    /**
     * §3.6 for a GROUP send: the member legs go out one by one, the self copies once, here.
     * @param payload the exact plaintext the members received.
     */
    fun sendSelfCopy(conversation: org.json.JSONObject, payload: ByteArray, msgId: String): Boolean {
        val dm = activeDevices() ?: return false
        val mine = dm.ownOtherDevices() ?: return false
        if (mine.isEmpty()) return true
        val copy = SentCopy.encode(conversation, String(payload, Charsets.UTF_8), msgId, System.currentTimeMillis())
        return try {
            postFanout(dm, msgId, listOf(Leg(myUserKey, copy, null, mine, isSelfCopy = true)), fallback = { false })
        } catch (e: Exception) {
            log("sendSelfCopy failed: ${e.javaClass.simpleName}: ${e.message}"); false
        }
    }

    /**
     * Encrypt every leg, POST ONE batch, and answer `409 device-list-mismatch` by adopting the
     * server's list, dropping the sessions of stale devices and rebuilding only what is missing
     * (§3.5 step 5). Copies already encrypted are REUSED across retries — their ratchet step is
     * spent and persisted, re-encrypting would only skip a message number. After
     * [MAX_MISMATCH_RETRIES] the caller's [fallback] sends one account envelope.
     */
    private fun postFanout(
        dm: DeviceMailbox,
        msgId: String,
        legs: List<Leg>,
        fallback: () -> Boolean,
        built: HashMap<String, DesktopEnvelope> = HashMap(),
    ): Boolean {
        for (attempt in 0..MAX_MISMATCH_RETRIES) {
            val batch = ArrayList<DesktopEnvelope>()
            for (leg in legs) {
                val devs = leg.devices
                if (devs == null) {
                    val k = "${leg.to}|"
                    val env = built[k] ?: encryptFor(leg.to, null, leg.plaintext, msgId, leg.routing, null)?.also { built[k] = it }
                    if (env == null) { if (!leg.isSelfCopy) return false else continue }
                    batch += env
                    continue
                }
                val usable = ArrayList<String>()
                for (d in devs) {
                    val k = "${leg.to}|$d"
                    val env = built[k] ?: encryptFor(leg.to, d, leg.plaintext, msgId, leg.routing, dm.deviceId)?.also { built[k] = it }
                    // No bundle for d: leave it out; if the server still counts it, it says so.
                    if (env == null) { dm.invalidatePeer(leg.to); continue }
                    batch += env; usable += d
                }
                leg.devices = usable
            }
            if (batch.isEmpty()) return legs.firstOrNull()?.isSelfCopy == true
            val r = messages.sendBatch(batch)
            if (r.ok && r.value!!.isNotEmpty()) return true
            if (r.code != 409) { log("fan-out ${msgId.take(8)} → HTTP ${r.code} ${r.error ?: ""}"); return false }
            when (r.error) {
                "device-list-mismatch" -> {
                    val m = V2MessagesClient.parseMismatch(r.body) ?: return false
                    val leg = legs.firstOrNull { it.devices != null && DeviceAuth.normalizeIdentity(it.to) == m.to } ?: return fallback()
                    dm.replacePeerDevices(leg.to, m.devices)
                    val next = if (leg.isSelfCopy || isSelf(leg.to)) m.devices.filter { it != dm.deviceId } else m.devices
                    for (stale in m.stale) {
                        built.remove("${leg.to}|$stale")
                        ratchetLock.withLock { sessions.delete(leg.to, stale) }
                    }
                    log("fan-out ${msgId.take(8)}: device list for ${leg.to.take(8)}… was stale (missing ${m.missing.size}, stale ${m.stale.size}) — retrying")
                    if (next.isEmpty() && !leg.isSelfCopy) {
                        // The recipient no longer has any device: one account envelope, as today.
                        leg.devices = null
                    } else leg.devices = next
                }
                "device-mailbox-disabled" -> { dm.onMailboxDisabled(); return fallback() }
                "unknown-fromDevice" -> { dm.onDeviceUnknown(); return fallback() }
                else -> { log("fan-out ${msgId.take(8)} → 409 ${r.error}"); return false }
            }
        }
        // Our OWN list kept moving: the message to the contact matters more than the copy.
        if (legs.any { it.isSelfCopy } && legs.any { !it.isSelfCopy }) {
            log("fan-out ${msgId.take(8)}: self copies dropped after $MAX_MISMATCH_RETRIES retries")
            return postFanout(dm, msgId, legs.filterNot { it.isSelfCopy }, fallback, built)
        }
        log("fan-out ${msgId.take(8)}: device list still mismatched after $MAX_MISMATCH_RETRIES retries — account envelope instead")
        return fallback()
    }

    /** Today's path for one peer: one account envelope, session (peer, nil), no device fields. */
    private fun sendLegacyOnly(peer: String, plaintext: ByteArray, msgId: String, routing: Map<String, String>?): Boolean {
        val env = encryptFor(peer, null, plaintext, msgId, routing, null) ?: return false
        return messages.send(env).isNotEmpty()
    }

    // ------------------------------------------------------------------ bundles

    private fun fetchBundleCached(peer: String) =
        bundleCache[peer] ?: keys.fetchBundle(peer)?.also { bundleCache[peer] = it }

    /** Consume the cached bundle, or fetch a fresh one. A bundle is never reused: its OPK is spent. */
    private fun takeBundle(peer: String) = bundleCache.remove(peer) ?: keys.fetchBundle(peer)

    companion object {
        private val EMPTY = ByteArray(0)
        const val OPK_TARGET = 20
        const val OPK_LOW_WATER = 5
        /** §3.5: at most two rebuilds after a device-list mismatch, then the account envelope. */
        const val MAX_MISMATCH_RETRIES = 2
    }
}
