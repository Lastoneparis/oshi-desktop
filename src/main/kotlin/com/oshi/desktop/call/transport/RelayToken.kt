package com.oshi.desktop.call.transport

import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * __CALL_MEDIA_AUTH_DESKTOP_2026_09_23__ `docs/CALL_MEDIA_RELAY_AUTH_CONTRACT.md` (STABLE 1.0) §2.
 *
 * The per-call relay token `POST /api/call/relay-token` hands out: a 16-byte [tokenId] the
 * `:8089` relay looks up, and a 32-byte [macKey] that proves every UDP register (§3/§4).
 *
 * [macKey] is a secret: held in memory for the call only, never logged ([toString]
 * redacts it), never persisted.
 */
class RelayToken(
    val tokenId: ByteArray,
    val macKey: ByteArray,
    /** Unix ms. The token is refreshed [REFRESH_BEFORE_MS] before this. */
    val expiresAtMs: Long,
    val udpPort: Int = 8089,
    /** The server's `CALL_MEDIA_AUTH_MODE` at issue time: `off` · `dual` · `enforce`. */
    val mode: String = "",
) {
    init {
        require(tokenId.size == TOKEN_ID_LEN) { "tokenId must be $TOKEN_ID_LEN bytes" }
        require(macKey.size == MAC_KEY_LEN) { "macKey must be $MAC_KEY_LEN bytes" }
    }

    fun needsRefresh(nowMs: Long): Boolean = nowMs >= expiresAtMs - REFRESH_BEFORE_MS

    /** Never print the key. The token id is a lookup handle, the first 4 bytes are enough to correlate. */
    override fun toString(): String =
        "RelayToken(${tokenId.take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) }}…, expires=$expiresAtMs, mode=$mode)"

    companion object {
        const val TOKEN_ID_LEN = 16
        const val MAC_KEY_LEN = 32

        /** Contract §2 "Refresh": a call outliving `expiresAt - 10 min` switches to a new token. */
        const val REFRESH_BEFORE_MS = 10 * 60_000L

        /** The 200 body of §2, or null when it is not one. Accepts base64url or standard base64. */
        fun parse(body: String): RelayToken? = runCatching {
            val j = JSONObject(body)
            RelayToken(
                decodeB64(j.getString("tokenId")),
                decodeB64(j.getString("macKey")),
                j.getLong("expiresAt"),
                j.optInt("udpPort", 8089),
                j.optString("mode", ""),
            )
        }.getOrNull()

        private fun decodeB64(s: String): ByteArray =
            Base64.getDecoder().decode(
                s.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) },
            )
    }
}

/**
 * The §3/§4 trailer: `"ORA1" | tokenId 16 | counter u64 BE | mac 16`, where
 * `mac = HMAC-SHA256(macKey, "OSHI-UDP-REG-1" ‖ every datagram byte before the mac)[0..16]`.
 */
object UdpRelayAuth {
    val MAGIC: ByteArray = "ORA1".toByteArray(Charsets.US_ASCII)
    val LABEL: ByteArray = "OSHI-UDP-REG-1".toByteArray(Charsets.US_ASCII)
    const val MAC_LEN = 16
    const val TRAILER_LEN = 4 + RelayToken.TOKEN_ID_LEN + 8 + MAC_LEN // 44

    /** §5 nack codes (`04 00 <code>`). */
    const val NACK_UNKNOWN_TOKEN = 0x01
    const val NACK_BAD_MAC = 0x02
    const val NACK_REPLAY = 0x03
    const val NACK_BINDING = 0x04
    const val NACK_KEY_HELD = 0x05
    const val NACK_UNAUTH_REFUSED = 0x06

    /**
     * A complete authenticated register (0x04) or keepalive (0x05) datagram. [recipient] =
     * the peer, [sender] = us, both base64url unpadded — the same header [UdpRelayClient.encode]
     * builds for media.
     */
    fun datagram(
        type: Int,
        recipient: String,
        sender: String,
        callId: String,
        tokenId: ByteArray,
        counter: Long,
        macKey: ByteArray,
    ): ByteArray {
        require(tokenId.size == RelayToken.TOKEN_ID_LEN)
        val prefix = ByteBuffer.allocate(4 + RelayToken.TOKEN_ID_LEN + 8)
            .put(MAGIC).put(tokenId).putLong(counter).array()
        val head = UdpRelayClient.encode(type, recipient, sender, callId, prefix)
        return head + mac(macKey, head)
    }

    fun mac(macKey: ByteArray, datagramBeforeMac: ByteArray): ByteArray {
        val h = Mac.getInstance("HmacSHA256")
        h.init(SecretKeySpec(macKey, "HmacSHA256"))
        h.update(LABEL)
        h.update(datagramBeforeMac)
        return h.doFinal().copyOf(MAC_LEN)
    }
}

/**
 * One call's token, fetched once and re-fetched only when it must be: near expiry (§2
 * Refresh) or when the relay nacked it (§5). Re-fetches are spaced by [minRefetchMs] so a
 * nack storm cannot run the account into the route's 30/min limit.
 *
 * [fetch] is the signed `POST /relay-token` for exactly (me, peer, callId) — see
 * `CallSignalClient.relayToken`. It blocks; call [current]/[refresh] off the UI thread.
 */
class RelayTokenSource(
    private val fetch: () -> RelayToken?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minRefetchMs: Long = 5_000L,
) {
    @Volatile private var token: RelayToken? = null
    private var lastFetchMs = 0L
    private var fetched = false

    /** How many times [fetch] actually ran. */
    @Volatile var fetches = 0; private set

    /** The live token, fetching it the first time and again when it is near expiry. */
    @Synchronized
    fun current(): RelayToken? {
        val t = token
        val now = clock()
        if (t != null && !t.needsRefresh(now)) return t
        if (fetched && now - lastFetchMs < minRefetchMs) return t?.takeIf { now < it.expiresAtMs }
        return doFetch(now) ?: t?.takeIf { now < it.expiresAtMs }
    }

    /** Drop the current token (the relay no longer knows it) and fetch a new one. */
    @Synchronized
    fun refresh(): RelayToken? {
        val now = clock()
        if (fetched && now - lastFetchMs < minRefetchMs) return token
        token = null
        return doFetch(now)
    }

    private fun doFetch(now: Long): RelayToken? {
        fetched = true
        lastFetchMs = now
        fetches++
        val fresh = runCatching { fetch() }.getOrNull() ?: return null
        token = fresh
        return fresh
    }
}
