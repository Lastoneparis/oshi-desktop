package com.oshi.desktop.call.transport

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The hole-punch ping, and the call id that makes it belong to THIS call.
 *
 * ```
 * [0x31 or 0x32][call_id u64 BE][nonce 12][ts_ms u64 BE]      exactly 29 bytes
 * ```
 *
 * `OSHI/P2PTransport.swift:1591-1598` builds it, `:1600-1604` turns a ping into a pong,
 * `:1615-1622` reads the call id back out;
 * `OSHI-Android/.../service/p2p/P2PTransport.kt:1051-1066` builds the identical bytes.
 * The type bytes are named on both sides — `PACKET_TYPE_HOLEPUNCH_PING: Byte = 0x31`,
 * `..._PONG: Byte = 0x32` (`kt:67-68`).
 *
 * ============================================================ THIS IS NOT A STUN CHECK
 *
 * RFC 5245 does connectivity checks with STUN Binding Requests carrying USERNAME,
 * MESSAGE-INTEGRITY and PRIORITY. OSHI does not. The check is these 29 bytes and
 * nothing else, it carries no integrity tag, and the reply is the request with one byte
 * changed. Anyone who can put a datagram on the media port can elicit a pong — which is
 * why the call id exists, and why it is the only thing standing between two concurrent
 * calls that happen to share a socket.
 *
 * ============================================================ THE CALL ID, AND cid_mismatch
 *
 * `call_id` is **SHA-256 of the callId string, first 8 bytes, big-endian**
 * (`VoiceCallManager.swift:13384-13398`). PARITY.md row 2.1 already names the failure
 * this creates, and both trees carry a comment about it:
 *
 *   > `changing its SHA-256; the derived P2P cid then mismatched and every ...`
 *   > — `EnhancedCallManager.kt:2568`
 *   > `... → every ICE ping dropped as cid_mismatch → the call connected with NO SOUND`
 *   > `("rings back, no audio")` — `VoiceCallManager.swift:6784-6790`
 *
 * The callId is peeled out of the offer body with no length prefix
 * ([com.oshi.desktop.call.CallOffer]'s THE PEEL), so **one byte too few or too many
 * changes the string, changes the hash, changes the cid, and every hole-punch packet
 * from the peer is dropped**. The signalling still completes. The state machine still
 * reaches CONNECTED. The call is silent, in both directions, with no error anywhere
 * except one log line. That is the single most expensive bug this file can contain and
 * it is why [deriveP2PCallId] canonicalises the string the same way iOS does before
 * hashing it.
 *
 * ============================================================ ZERO IS A WILDCARD
 *
 * `call_id == 0` on an INBOUND ping is a MATCH, not a mismatch. iOS says why
 * (`P2PTransport.swift:1381-1387`):
 *
 *   > `The call_id check is a WILDCARD: bytes [1..9] = 0 means "sender does not carry a`
 *   > `call id" (the Android P2PTransport currently does this) and is treated as a`
 *   > `match. Any non-zero mismatching call_id is still rejected.`
 *
 * That is accurate: Android's `callId` is `@Volatile private var callId: Long = 0L`
 * (`kt:221`) and `setCallId` is documented as optional (`kt:235-245`), so an Android
 * peer whose call manager never calls it punches with all-zero. Refusing the wildcard
 * would make this client unable to punch to those builds at all — every ping dropped,
 * relay-or-silence — so the wildcard is honoured on RECEIVE. It is never used on SEND:
 * this client always stamps the derived cid, which is what earns `cidMatch=exact` in
 * iOS's `DIAG_ICE_PING_RX` and what gives cross-call isolation any teeth.
 *
 * ============================================================ PONGS IGNORE THE CALL ID
 *
 * Deliberately, and copied. `P2PTransport.swift:1411-1415`:
 *
 *   > `Pongs: accept regardless of call_id — the 12-byte nonce (correlated via`
 *   > `nonceSendTimes) is authoritative for linking a pong back to a ping we sent.`
 *
 * A pong echoes our own ping, so its cid is whatever WE put there; re-checking it tests
 * nothing. The nonce is the real correlator and it is 96 bits of [SecureRandom], which
 * is also the only reason a forged pong cannot promote an attacker's address: it would
 * have to guess a nonce we have outstanding.
 */
object HolePunch {

    /** `0x31`. `P2PTransport.kt:67`, `swift:1593`. */
    const val TYPE_PING: Byte = 0x31

    /** `0x32`. `P2PTransport.kt:68`, `swift:1602`. */
    const val TYPE_PONG: Byte = 0x32

    /** 12 random bytes. `swift:807 Self.randomBytes(12)`, `kt:1052 ByteArray(12)`. */
    const val NONCE_SIZE = 12

    /** 1 + 8 + 12 + 8. Both clients hard-code 29 (`swift:1601`, `kt:1055`). */
    const val PACKET_SIZE = 29

    /** Byte offset of the nonce. `swift:1487-1488`, `kt:1060`. */
    const val NONCE_OFFSET = 9

    /** Byte offset of the timestamp. `swift:1495-1496`, `kt:1061`. */
    const val TIMESTAMP_OFFSET = 21

    private val random = SecureRandom()

    /**
     * SHA-256 of the callId, first 8 bytes big-endian.
     *
     * The string is canonicalised first — leading and trailing control characters and
     * whitespace are TRIMMED, which is what iOS does before hashing
     * (`VoiceCallManager.swift:13387-13389`,
     * `trimmingCharacters(in: .controlCharacters.union(.whitespacesAndNewlines))`).
     * Not filtered from the middle: a callId with an interior space is a different
     * string on both platforms and must derive a different cid, or two genuinely
     * different calls would collide. (Interior control characters never reach here
     * anyway — `CallOffer.decode` strips them at parse time, matching iOS's
     * `sanitizeCallId`.)
     *
     * Returns [Long] and not a hypothetical unsigned type: the top bit is set for
     * roughly half of all UUIDs, so this value is frequently NEGATIVE, and every byte
     * of it still has to be written big-endian unchanged. Android holds it the same way
     * (`kt:221 var callId: Long`) and iOS holds it as `UInt64`; the eight bytes on the
     * wire are identical either way, and the only thing sign changes is how a log line
     * prints. Compare with `==`, never with `<`.
     */
    fun deriveP2PCallId(callId: String): Long {
        val canonical = callId.trim { it.isWhitespace() || it.isISOControl() }
        val h = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        var out = 0L
        for (i in 0 until 8) out = (out shl 8) or (h[i].toLong() and 0xFF)
        return out
    }

    /** 12 fresh random bytes for one probe. */
    fun newNonce(): ByteArray = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }

    /**
     * Build a ping. [nonce] and [tsMs] are parameters so the 29 bytes are a pure
     * function of the inputs and can be pinned to a fixture.
     */
    fun buildPing(callId: Long, nonce: ByteArray, tsMs: Long): ByteArray =
        build(TYPE_PING, callId, nonce, tsMs)

    /** Build a pong directly. Normally you want [pongFor]. */
    fun buildPong(callId: Long, nonce: ByteArray, tsMs: Long): ByteArray =
        build(TYPE_PONG, callId, nonce, tsMs)

    private fun build(type: Byte, callId: Long, nonce: ByteArray, tsMs: Long): ByteArray {
        require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" }
        val out = ByteArray(PACKET_SIZE)
        out[0] = type
        for (i in 0 until 8) out[1 + i] = ((callId ushr (56 - i * 8)) and 0xFF).toByte()
        nonce.copyInto(out, NONCE_OFFSET)
        for (i in 0 until 8) out[TIMESTAMP_OFFSET + i] = ((tsMs ushr (56 - i * 8)) and 0xFF).toByte()
        return out
    }

    /**
     * The pong for a ping: the same 29 bytes with byte 0 flipped to `0x32`.
     *
     * Null when the input is not a ping. `P2PTransport.swift:1600-1604` — and note that
     * it echoes the SENDER's call id and the SENDER's timestamp back unchanged, which is
     * what lets the sender fall back to the embedded `ts_ms` for RTT when it has already
     * expired its own nonce record (`swift:1493-1502`).
     */
    fun pongFor(ping: ByteArray): ByteArray? {
        if (ping.size < PACKET_SIZE) return null
        if (ping[0] != TYPE_PING) return null
        // __RELAY_FIRST_UPGRADE_2026_09_23__ a pong is a byte copy of the WHOLE ping, like
        // iOS/Android: phones verify a direct pair with a media-sized (1200/2000 B) padded
        // ping and only count a pong of that size. Truncating to 29 B made every desktop pair
        // look fragment-dropping, so a phone never used direct with a desktop.
        val out = ping.copyOf()
        out[0] = TYPE_PONG
        return out
    }

    /**
     * Bytes 1..9 as a big-endian call id, or null when the packet is too short.
     *
     * A returned `0L` is the WILDCARD, not an error — see ZERO IS A WILDCARD.
     * `P2PTransport.swift:1612-1622`.
     */
    fun readCallId(data: ByteArray): Long? {
        if (data.size < 9) return null
        var out = 0L
        for (i in 1 until 9) out = (out shl 8) or (data[i].toLong() and 0xFF)
        return out
    }

    /** Bytes 9..21. Null when the packet is short. */
    fun readNonce(data: ByteArray): ByteArray? {
        if (data.size < NONCE_OFFSET + NONCE_SIZE) return null
        return data.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + NONCE_SIZE)
    }

    /** Bytes 21..29 as a big-endian millisecond timestamp. Null when short. */
    fun readTimestamp(data: ByteArray): Long? {
        if (data.size < PACKET_SIZE) return null
        var out = 0L
        for (i in 0 until 8) out = (out shl 8) or (data[TIMESTAMP_OFFSET + i].toLong() and 0xFF)
        return out
    }

    /** Lowercase hex, the key both clients use for the nonce map (`swift:810`, `kt:1064`). */
    fun nonceHex(nonce: ByteArray): String =
        nonce.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Does an inbound ping's call id entitle it to a pong?
     *
     * `incoming == 0` (wildcard) or `incoming == local`. Anything else is the
     * `cid_mismatch` drop (`P2PTransport.swift:1388-1394`).
     */
    fun callIdAcceptable(incoming: Long, local: Long): Boolean = incoming == 0L || incoming == local
}

/**
 * ICE2 pair bookkeeping: which remote address is worth sending audio to right now.
 *
 * The scoring and the constants come from the two shipped transports, whose header
 * comments describe the same algorithm in the same words
 * (`P2PTransport.swift:13-30`, `P2PTransport.kt:31-52`):
 *
 *  - probe EVERY remote candidate on every tick, 2 Hz, not just the active one;
 *  - EWMA RTT with α = 0.125 and a success rate over the last 10 probes;
 *  - `score = (1 / (RTT_ms + 1)) × successRate`, with a **1.5× bias for direct pairs**
 *    so a TURN relay never wins a tie;
 *  - switch only when a challenger beats the incumbent by 1.2× (hysteresis).
 *
 * ============================================================ WHAT THIS DOES NOT DO
 *
 * The shipped clients also demote an active pair on three consecutive misses, on a
 * sustained sub-0.5 success rate for 2 s, or on EWMA RTT over 300 ms, and they open a
 * 200 ms dual-send window after a switch so the peer's jitter buffer sees an overlap
 * instead of a gap. **None of that is here.** [best] is a pure selection over current
 * statistics with the same bias and the same hysteresis; the failure-detection ladder
 * and the dual-send window are transport POLICY that belongs with the integration, and
 * claiming them without the timers that drive them would be claiming a feature that
 * cannot fire. The constants are recorded below so that work has somewhere to start.
 */
class IcePairTable(private val successWindow: Int = SUCCESS_WINDOW) {

    /** Per-pair statistics. Mutable, and only ever touched under [IcePairTable]'s lock. */
    class PairState(val candidate: IceCandidate) {
        var ewmaRttMs: Double = -1.0
            private set
        var lastPongAtMs: Long = 0
            private set
        var inflight: Int = 0
        private val outcomes = ArrayDeque<Boolean>()

        fun recordPong(rttMs: Long, nowMs: Long, window: Int) {
            ewmaRttMs = if (ewmaRttMs < 0) rttMs.toDouble()
            else EWMA_ALPHA * rttMs + (1 - EWMA_ALPHA) * ewmaRttMs
            lastPongAtMs = nowMs
            push(true, window)
            if (inflight > 0) inflight--
        }

        fun recordMiss(window: Int) {
            push(false, window)
            if (inflight > 0) inflight--
        }

        private fun push(ok: Boolean, window: Int) {
            outcomes.addLast(ok)
            while (outcomes.size > window) outcomes.removeFirst()
        }

        /** 0.0 until a pair has been probed at all — an unprobed pair is not live. */
        fun successRate(): Double =
            if (outcomes.isEmpty()) 0.0 else outcomes.count { it }.toDouble() / outcomes.size

        /** A pair is live while a pong arrived inside [LIVENESS_MAX_AGE_MS]. */
        fun isLive(nowMs: Long): Boolean =
            lastPongAtMs > 0 && (nowMs - lastPongAtMs) <= LIVENESS_MAX_AGE_MS

        /** `(1 / (RTT + 1)) × successRate`, biased 1.5× for a direct pair. */
        fun effectiveScore(): Double {
            if (ewmaRttMs < 0) return 0.0
            val base = (1.0 / (ewmaRttMs + 1.0)) * successRate()
            return if (candidate.isDirect) base * DIRECT_BIAS else base
        }
    }

    private val pairs = LinkedHashMap<String, PairState>()

    /** nonceHex → (sentAtMs, pairKey). The authoritative pong correlator. */
    private val inflightNonces = HashMap<String, Pair<Long, String>>()

    private val remote = LinkedHashMap<String, IceCandidate>()

    @Synchronized
    fun remoteCandidates(): List<IceCandidate> = remote.values.toList()

    @Synchronized
    fun pair(key: String): PairState? = pairs[key]

    /** Adds a signalled remote candidate. Idempotent on `"ip:port"`. */
    @Synchronized
    fun addRemote(candidate: IceCandidate): Boolean {
        if (remote.containsKey(candidate.key)) return false
        remote[candidate.key] = candidate
        pairs.getOrPut(candidate.key) { PairState(candidate) }
        return true
    }

    /**
     * Learn a peer-reflexive candidate from an unsignalled ping source (RFC 5245 §7.1.3).
     *
     * A symmetric or port-randomising mobile NAT gives the peer a DIFFERENT egress port
     * per destination, so the srflx it signalled is not the address its pings actually
     * arrive from — iOS describes exactly this in the iPhone⇄Android logs
     * (`P2PTransport.swift:1400-1408`). Without this the send path "keeps blasting the
     * stale signaled SRFLX port forever".
     *
     * Capped at [MAX_PEER_REFLEXIVE], which is iOS's cap and its reason
     * (`swift:1434-1443`): each unsignalled source can mint a candidate, so an
     * unauthenticated flood would otherwise grow this map without limit and turn the
     * client into a packet reflector.
     */
    @Synchronized
    fun learnPeerReflexive(ip: String, port: Int): IceCandidate? {
        val key = "$ip:$port"
        if (remote.containsKey(key)) return null
        val existing = remote.values.count { it.type == IceCandidateType.SRFLX }
        if (existing >= MAX_PEER_REFLEXIVE) return null
        val family = if (ip.contains(':')) 6 else 4
        val cand = IceCandidate(
            IceCandidateType.SRFLX, ip, port,
            IcePriority.ios(IceCandidateType.SRFLX, family),
        )
        remote[key] = cand
        pairs[key] = PairState(cand)
        return cand
    }

    /** Record that a probe with [nonceHex] just went out to [key]. */
    @Synchronized
    fun noteProbeSent(nonceHex: String, key: String, nowMs: Long) {
        inflightNonces[nonceHex] = nowMs to key
        pairs[key]?.let { it.inflight++ }
    }

    /**
     * Correlate a pong. Returns the measured RTT in ms, or null when the nonce is not
     * one we have outstanding — a duplicate, a very late reply, or a forgery.
     *
     * The pair credited is the one the PING was sent to, not the source of the pong:
     * the nonce record is what the shipped clients key on (`swift:1487-1492`,
     * `kt:521-560`), and crediting the source instead would let anyone who can see a
     * ping steal the pair's statistics by answering it from a different address.
     */
    @Synchronized
    fun recordPong(nonceHex: String, nowMs: Long): Long? {
        val (sentAt, key) = inflightNonces.remove(nonceHex) ?: return null
        val rtt = if (nowMs >= sentAt) nowMs - sentAt else 0L
        pairs[key]?.recordPong(rtt, nowMs, successWindow)
        return rtt
    }

    /**
     * Age out probes that were never answered and count each as a loss.
     * `swift:791-800` uses the same 2 s cutoff (`kt:78 PROBE_INFLIGHT_TIMEOUT_MS`).
     */
    @Synchronized
    fun expireProbes(nowMs: Long): Int {
        val stale = inflightNonces.filterValues { nowMs - it.first > PROBE_INFLIGHT_TIMEOUT_MS }
        for ((hex, v) in stale) {
            inflightNonces.remove(hex)
            pairs[v.second]?.recordMiss(successWindow)
        }
        return stale.size
    }

    /**
     * The pair that should carry audio, or null when nothing is live.
     *
     * [incumbent] is the currently selected key; a challenger must beat it by
     * [SWITCH_HYSTERESIS] to displace it, which is what stops a call flapping between
     * two pairs whose scores cross on jitter.
     */
    @Synchronized
    fun best(nowMs: Long, incumbent: String? = null): IceCandidate? {
        val live = pairs.values.filter { it.isLive(nowMs) }
        if (live.isEmpty()) return null
        val top = live.maxByOrNull { it.effectiveScore() } ?: return null
        val current = incumbent?.let { pairs[it] }
        if (current == null || !current.isLive(nowMs)) return top.candidate
        if (top.candidate.key == current.candidate.key) return current.candidate
        return if (top.effectiveScore() > current.effectiveScore() * SWITCH_HYSTERESIS) {
            top.candidate
        } else {
            current.candidate
        }
    }

    @Synchronized
    fun clear() {
        pairs.clear()
        inflightNonces.clear()
        remote.clear()
    }

    companion object {
        /** `kt:76 EWMA_ALPHA`, `swift:18`. */
        const val EWMA_ALPHA = 0.125

        /** `kt:75 SUCCESS_WINDOW`. */
        const val SUCCESS_WINDOW = 10

        /** `kt:83 SWITCH_HYSTERESIS`, `swift:24-25`. */
        const val SWITCH_HYSTERESIS = 1.2

        /** `kt:89 DIRECT_BIAS`, `swift:19-21`. TURN never wins a tie. */
        const val DIRECT_BIAS = 1.5

        /** `kt:81 LIVENESS_MAX_AGE_MS`. */
        const val LIVENESS_MAX_AGE_MS = 5_000L

        /** `kt:80 PROBE_INFLIGHT_TIMEOUT_MS`, `swift:788`. */
        const val PROBE_INFLIGHT_TIMEOUT_MS = 2_000L

        /** `kt:73 PROBE_INTERVAL_MS` — 2 Hz. Recorded; the timer lives with the caller. */
        const val PROBE_INTERVAL_MS = 500L

        /** `swift:1438 maxPeerReflexiveCandidates`. */
        const val MAX_PEER_REFLEXIVE = 8

        /**
         * NOT implemented — the failure-detection ladder both clients run on the ACTIVE
         * pair. Recorded so the gap is a number and not a shrug.
         * `kt:77 RTT_FAILOVER_MS`, `kt:78 LOSS_SUSTAINED_MS`, `kt:79 PROBE_FAIL_THRESHOLD`,
         * `kt:103 DUAL_SEND_WINDOW_MS`.
         */
        const val UNIMPLEMENTED_RTT_FAILOVER_MS = 300.0
        const val UNIMPLEMENTED_LOSS_SUSTAINED_MS = 2_000L
        const val UNIMPLEMENTED_PROBE_FAIL_THRESHOLD = 3
        const val UNIMPLEMENTED_DUAL_SEND_WINDOW_MS = 200L
    }
}
