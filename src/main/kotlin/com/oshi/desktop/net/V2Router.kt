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
) {

    /** Invoked for every decrypted message, BEFORE the relay is acked. Must be durable. */
    var onMessage: (V2Inbound) -> Unit = {}

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
            if (!prekeys.hasPublished()) return keys.publish(OPK_TARGET) != null
            when (val remaining = keys.remainingCount()) {
                // Count unavailable → do NOT mint. Guessing is what grew the pool; the
                // bundle already on the server is still valid.
                null -> true
                else -> if (remaining >= OPK_LOW_WATER) true else keys.publish(OPK_TARGET - remaining) != null
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
    ): Boolean {
        if (!config.isEnabledCached()) return false
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
        val pull = messages.pull(myUserKey, state.lastSeq()) ?: return 0
        var delivered = 0

        // Hold the ack BEFORE the earliest failed seq so the relay keeps anything we could
        // not decrypt — but only while the retry budget lasts.
        var lowestFailedSeq = Long.MAX_VALUE
        fun hold(seq: Long) { if (seq in 0 until lowestFailedSeq) lowestFailedSeq = seq }

        for (env in pull.messages.sortedBy { it.seq }) {
            if (!state.markSeen(env.msgId)) continue

            val plaintext: ByteArray? = try {
                ratchetLock.withLock { decryptInbound(env) }
            } catch (e: Exception) {
                // The ratchet was NOT advanced. Hold the seq so the relay keeps the
                // envelope — bounded, so one poisoned envelope cannot wedge the mailbox.
                state.unmarkSeen(env.msgId)
                if (retryBudget.shouldRetry(env.msgId)) {
                    hold(env.seq)
                    log("inbound ${env.msgId.take(8)} from ${env.from.take(8)}… held: ${e.message}")
                } else {
                    log("giving up on ${env.msgId.take(8)} after ${V2RetryBudget.MAX_RETRIES} attempts: ${e.message}")
                }
                continue
            }
            if (plaintext == null) continue      // undecryptable-forever → let it be acked
            retryBudget.clear(env.msgId)

            try {
                onMessage(V2Inbound(env.from, env.msgId, env.ts, env.groupId, String(plaintext, Charsets.UTF_8)))
                delivered++
            } catch (e: Exception) {
                // The ratchet is ALREADY spent — this envelope can never be decrypted
                // again, so holding the seq would poison the queue for nothing.
                log("deliver failed after decrypt; ${env.msgId.take(8)} dropped locally: ${e.message}")
            }
        }

        val ackSeq = if (lowestFailedSeq == Long.MAX_VALUE) pull.maxSeq else lowestFailedSeq - 1
        if (ackSeq > state.lastSeq() && messages.ack(myUserKey, ackSeq)) state.setLastSeq(ackSeq)
        state.flush()
        delivered
    }

    /**
     * Decrypt one envelope, advancing and persisting the ratchet.
     *
     * @return the plaintext, or null when the message is undecryptable FOREVER — which is
     *         only "first contact with no X3DH header", a no-op the caller may ack.
     * @throws Exception on a genuine failure, so the caller holds the seq.
     */
    private fun decryptInbound(env: DesktopEnvelope): ByteArray? {
        val header = OSHICryptoV2.decodeHeader(env.header)
        val existing = sessions.load(env.from)

        if (existing == null) {
            val x = env.x3dh ?: return null       // nothing to establish from
            val (fresh, opkId) = establishResponder(env.from, x)
            val pt = OSHIRatchetV2.decrypt(fresh, header, env.ciphertext, EMPTY)   // throws if forged
            opkId?.let { prekeys.consumeOneTimePreKeyPriv(it) }                    // burn AFTER verify
            sessions.save(responderRecord(env.from, fresh, x.identityKey))
            return pt
        }

        return try {
            val pt = OSHIRatchetV2.decrypt(existing.ratchet, header, env.ciphertext, EMPTY)
            existing.pendingInitiatorHeader = null      // the peer answered → stop attaching x3dh
            sessions.save(existing)
            pt
        } catch (e: Exception) {
            // Recovery: a message carrying an X3DH header that the current session cannot
            // open → re-establish as responder and retry, exactly as both platforms do.
            val x = env.x3dh ?: throw e
            val (fresh, opkId) = establishResponder(env.from, x)
            val pt = OSHIRatchetV2.decrypt(fresh, header, env.ciphertext, EMPTY)
            opkId?.let { prekeys.consumeOneTimePreKeyPriv(it) }
            sessions.save(responderRecord(env.from, fresh, x.identityKey))
            pt
        }
    }

    /** @return the responder state and the one-time prekey id to burn once it verifies. */
    private fun establishResponder(
        sender: String,
        x: V2Session.X3DHHeader,
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

    // ------------------------------------------------------------------ bundles

    private fun fetchBundleCached(peer: String) =
        bundleCache[peer] ?: keys.fetchBundle(peer)?.also { bundleCache[peer] = it }

    /** Consume the cached bundle, or fetch a fresh one. A bundle is never reused: its OPK is spent. */
    private fun takeBundle(peer: String) = bundleCache.remove(peer) ?: keys.fetchBundle(peer)

    companion object {
        private val EMPTY = ByteArray(0)
        const val OPK_TARGET = 20
        const val OPK_LOW_WATER = 5
    }
}
