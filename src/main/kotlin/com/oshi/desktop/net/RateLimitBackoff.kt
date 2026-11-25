package com.oshi.desktop.net

/**
 * __GROUP_FANOUT_2026_11_24__ How a relay send waits out a `429`. Same rule as Android
 * `network/v2/RateLimitBackoff.kt` and iOS `V2RateLimitBackoff`.
 *
 * The relay charges one token per member envelope to a per-sender bucket (60 tokens, 10/s on the
 * relay of 2026-11-24). The desktop group fan-out sends one request per member and did NOT retry a
 * 429: reproduced on a local copy of the production relay, a 200-member group at 20 ms RTT reached
 * 111 members and silently skipped 89 (169/31 at 50 ms), while the message showed "Sent". Now a 429
 * waits what the relay says (`Retry-After`, seconds; ServerPatches/group_fanout_2026_11_24) or 1, 2,
 * 4… s without it, each wait capped at [MAX_SINGLE_WAIT_MS], all of them at [MAX_RATE_LIMIT_WAIT_MS].
 * Any other status is returned at once (unchanged behaviour).
 */
object RateLimitBackoff {
    const val MAX_SINGLE_WAIT_MS = 30_000L
    const val MAX_RATE_LIMIT_WAIT_MS = 120_000L

    fun parseRetryAfterMs(header: String?): Long? =
        header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000L }

    /** Delay before the next attempt after a 429, or null to stop. */
    fun nextDelayMs(retryAfter: String?, rateLimitedSoFar: Int, waitedMs: Long): Long? {
        val backoff = 1000L shl rateLimitedSoFar.coerceAtMost(5)
        val d = (parseRetryAfterMs(retryAfter) ?: backoff).coerceIn(1000L, MAX_SINGLE_WAIT_MS)
        return if (waitedMs + d > MAX_RATE_LIMIT_WAIT_MS) null else d
    }

    fun <A> retrying(call: () -> A, status: (A) -> Int, retryAfter: (A) -> String?, sleep: (Long) -> Unit = { Thread.sleep(it) }): A {
        var limited = 0
        var waited = 0L
        while (true) {
            val a = call()
            if (status(a) != 429) return a
            val d = nextDelayMs(retryAfter(a), limited, waited) ?: return a
            limited++
            waited += d
            sleep(d)
        }
    }
}
