package com.oshi.desktop.net

import com.oshi.messenger.network.ohttp.OhttpConfig
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import javax.net.ssl.SSLHandshakeException

/**
 * __RELAY_FALLBACK_2026_11_19__ Blocked-host fallback through OSHI's own OHTTP relay
 * (docs/CENSORSHIP_2026_THREAT_MODEL.md §9). Desktop twin of Android's `RelayFallback`
 * (85eea147): same relay, same thresholds, same rules.
 *
 * When oshi-messenger.com itself is blocked (SNI / IP / DNS block), every /v2 call fails at
 * the transport level and nothing is delivered. [RELAY_URL] lives on another IP
 * (213.199.60.210) under an unrelated hostname; it forwards HPKE-sealed requests to the
 * gateway, which replays them to /v2 unchanged (the signature still verifies). So once the
 * direct path has shown the block, eligible /v2 calls go through the relay for [WINDOW_MS];
 * then the next call tries direct again.
 *
 * Activation: a connect-phase failure (DNS, refused, no route, connect timeout, TLS
 * handshake) at once; any other transport failure (reset, EOF, read timeout) when it is the
 * 2nd in a row within [STREAK_MS]. HTTP statuses never count.
 *
 * Privacy: no worse than direct — the relay sees the client IP and ciphertext, the gateway
 * sees content but only the relay's address. OSHI runs both: circumvention, not OHTTP's
 * operator separation. The user's own "Privacy relay" setting, when ON, takes precedence
 * ([V2Http]) and keeps its no-direct-fallback rule. Same hosting provider (Contabo) as the
 * main server: it does not help against an AS-wide block or a whitelist.
 */
object RelayFallback {
    /** Same constant as Android's `OhttpConfig.FALLBACK_RELAY_URL` (§9 of the threat model). */
    const val RELAY_URL = "https://foodies.agency/api/v1/batch"
    const val WINDOW_MS: Long = 15 * 60_000L
    const val STREAK_MS: Long = 3 * 60_000L

    @Volatile private var activeUntil = 0L
    @Volatile private var lastFailureAt = 0L
    @Volatile private var streak = 0
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    @Volatile private var override: DesktopOhttpTransport? = null
    @Volatile private var cached: DesktopOhttpTransport? = null
    /** Off in a process that sets `-Doshi.relay.fallback=off` (or the harness/tests' choice). */
    @Volatile private var disabled = System.getProperty("oshi.relay.fallback") == "off"

    fun transport(): DesktopOhttpTransport? {
        override?.let { return it }
        if (disabled) return null
        cached?.let { return it }
        return DesktopOhttp.build(RELAY_URL, OhttpConfig.PINNED_KEYS_HEX)?.also { cached = it }
    }

    /** Tests / harness: a local relay (null + [setDisabled] true = feature off). */
    fun overrideForTests(t: DesktopOhttpTransport?) { override = t; reset() }
    fun setDisabled(off: Boolean) { disabled = off; reset() }

    fun active(): Boolean = clock() < activeUntil

    fun onDirectSuccess() { streak = 0 }

    /** Returns true when this failure (just) put the fallback in force. */
    fun onDirectFailure(e: Throwable): Boolean {
        if (!DesktopCover.ShortConnections.isTransportFailure(e)) return false
        val now = clock()
        streak = if (now - lastFailureAt <= STREAK_MS) streak + 1 else 1
        lastFailureAt = now
        if (isConnectPhase(e) || streak >= 2) {
            activeUntil = now + WINDOW_MS
            return true
        }
        return false
    }

    /** The relay itself failed to connect: let the next call probe direct again. */
    fun onRelayFailure(e: Throwable) {
        if (e is IOException && DesktopCover.ShortConnections.isTransportFailure(e) && isConnectPhase(e)) activeUntil = 0L
    }

    internal fun reset() { activeUntil = 0L; lastFailureAt = 0L; streak = 0 }

    internal fun isConnectPhase(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) {
            if (c is UnknownHostException || c is ConnectException || c is NoRouteToHostException ||
                c is SSLHandshakeException || c is HttpConnectTimeoutException) return true
            if (c is SocketTimeoutException && c.message.orEmpty().contains("connect", ignoreCase = true)) return true
            c = c.cause
        }
        return false
    }

    /** For the harness: a transport that treats [origin] (e.g. http://127.0.0.1:18094) as OSHI's origin. */
    fun localTransport(relayUrl: String, keysHex: String, origin: String): DesktopOhttpTransport? =
        DesktopOhttp.build(relayUrl, keysHex)?.copy(directOrigin = URI.create(origin))
}
