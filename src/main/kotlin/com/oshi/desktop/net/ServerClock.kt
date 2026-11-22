package com.oshi.desktop.net

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * __CALL_CLOCK_SKEW_DESKTOP_2026_11_21__ The offset between this computer's wall clock and
 * the OSHI server's, learned from the `Date` header of call / push / v2 responses.
 *
 * Port of Android's `network/auth/ServerClock.kt` (fix/call-reliability-android 10e1ab5f).
 *
 * ## Why (production evidence, 21/11/2026)
 *
 * A device whose clock was more than 30 s off had EVERY signed request refused as
 * "expired" (the server checks `x-oshi-timestamp` within ±30 s, `/relay-token` included),
 * and never rang: the call packet's header instant is compared to "now" and an offer older
 * than 30 s is dropped ([com.oshi.desktop.call.CallPacket.isStale]). On the desktop a PC
 * running fast threw away every incoming offer, and a PC running slow or fast could not sign.
 *
 * The server is the authority on both freshness checks, so this client reads the server's
 * clock: sign with server time, and judge packet age against server time.
 *
 * ## A healthy clock behaves exactly as before
 *
 * The correction is applied only when the measured offset is at least
 * [SIGNING_THRESHOLD_MS]. `Date` has one-second resolution and a sample carries up to
 * RTT/2 of error, so an NTP-synced PC must keep using its own clock untouched.
 *
 * ## Replay protection is unchanged
 *
 * Nothing widens a window: the server still enforces ±30 s, on its own clock; the call
 * packet windows (30 s offer / 60 s terminal) are the same, measured on the server's clock
 * instead of a wrong local one. Only TLS-authenticated (or OHTTP-sealed inner) responses
 * from the OSHI server feed it — never an outer relay response.
 *
 * Pure arithmetic in [sampleOffset] / [effectiveOffset] so every boundary is a JVM test.
 */
object ServerClock {

    /** Below this the correction is not applied. Well inside the server's ±30 s. */
    const val SIGNING_THRESHOLD_MS = 5_000L

    /** A sample from a round trip slower than this is too imprecise to keep. */
    const val MAX_SAMPLE_RTT_MS = 10_000L

    @Volatile private var rawOffsetMs: Long = 0L
    @Volatile private var haveSample: Boolean = false

    /**
     * Offset (server − local) implied by one response. `Date` is truncated to the second,
     * so +500 ms centres it; the local instant is the midpoint of the round trip.
     * Null when the sample is unusable.
     */
    fun sampleOffset(serverDateMs: Long, sentAtMs: Long, receivedAtMs: Long): Long? {
        if (serverDateMs <= 0L || sentAtMs <= 0L || receivedAtMs < sentAtMs) return null
        if (receivedAtMs - sentAtMs > MAX_SAMPLE_RTT_MS) return null
        val localMid = sentAtMs + (receivedAtMs - sentAtMs) / 2
        return serverDateMs + 500L - localMid
    }

    /** The correction applied: none for a sane clock, the full offset otherwise. */
    fun effectiveOffset(rawOffsetMs: Long): Long =
        if (abs(rawOffsetMs) >= SIGNING_THRESHOLD_MS) rawOffsetMs else 0L

    /** RFC 1123 `Date` header → epoch ms, or null. */
    fun parseHttpDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** Record one response's `Date`. Safe from any thread. */
    fun observe(serverDateMs: Long, sentAtMs: Long, receivedAtMs: Long) {
        if (!learning) return
        val s = sampleOffset(serverDateMs, sentAtMs, receivedAtMs) ?: return
        rawOffsetMs = s
        haveSample = true
    }

    /** Record one response's `Date` header value (ignored when absent or unparseable). */
    fun observeHeader(dateHeader: String?, sentAtMs: Long, receivedAtMs: Long) {
        val d = parseHttpDate(dateHeader) ?: return
        observe(d, sentAtMs, receivedAtMs)
    }

    /** Current raw estimate (server − local); 0 before the first sample. */
    fun rawOffsetMs(): Long = if (haveSample) rawOffsetMs else 0L

    /** The correction in force right now (0 for a healthy clock or before any sample). */
    fun effectiveOffsetMs(): Long = effectiveOffset(rawOffsetMs())

    /**
     * "Now" on the server's clock, for `x-oshi-timestamp`, outgoing packet instants and
     * packet freshness. Equals [localNowMs] unless the PC is ≥ 5 s off.
     */
    fun nowMs(localNowMs: Long = System.currentTimeMillis()): Long = localNowMs + effectiveOffsetMs()

    /** Test hook: false pins the offset (a loopback server's Date is this machine's own clock). */
    @Volatile internal var learning: Boolean = true

    /** Test hook. */
    internal fun resetForTest() {
        rawOffsetMs = 0L
        haveSample = false
        learning = true
    }

    /** Test hook: pretend the server is [offsetMs] ahead of this machine. */
    internal fun setOffsetForTest(offsetMs: Long) {
        rawOffsetMs = offsetMs
        haveSample = true
    }
}
