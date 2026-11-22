package com.oshi.desktop.lora

/**
 * __LORA_AIRTIME_HINT_2026_11_18__ Desktop copy of OSHI-Android's `LoRaAirtimeWait` (59c5ff2b),
 * identical apart from the package.
 *
 * The OSHI Mesh firmware enforces the regional duty cycle (EU_868: an hourly airtime budget).
 * Once a few long messages have spent it, the radio answers STATUS QUEUED and holds the rest.
 * A message QUEUED for over a minute gets the bubble hint "Waiting for radio airtime (legal
 * duty-cycle limit)" instead of looking stuck for no reason. Not thread-confined: every call
 * takes the lock.
 */
class LoRaAirtimeWait(
    private val thresholdMs: Long = DEFAULT_THRESHOLD_MS,
    private val forgetAfterMs: Long = FORGET_AFTER_MS,
) {
    private val lock = Any()
    private val queuedAt = HashMap<String, Long>()

    /** The radio said QUEUED for [messageId]. The first QUEUED counts; repeats do not reset the clock. */
    fun onQueued(messageId: String, nowMs: Long) = synchronized(lock) {
        queuedAt.putIfAbsent(messageId, nowMs)
        Unit
    }

    /** Any later state (SENT, DELIVERED, IN_CUSTODY, FAILED, REJECTED, UPLINKED): not waiting any more. */
    fun onProgress(messageId: String) = synchronized(lock) {
        queuedAt.remove(messageId)
        Unit
    }

    fun hasPending(): Boolean = synchronized(lock) { queuedAt.isNotEmpty() }

    /** Messages QUEUED for at least the threshold; entries older than [forgetAfterMs] are dropped. */
    fun waiting(nowMs: Long): Set<String> = synchronized(lock) {
        queuedAt.entries.removeAll { nowMs - it.value > forgetAfterMs }
        queuedAt.filterValues { nowMs - it >= thresholdMs }.keys.toSet()
    }

    companion object {
        const val DEFAULT_THRESHOLD_MS = 60_000L
        const val FORGET_AFTER_MS = 2 * 60 * 60_000L
    }
}

/**
 * __LORA_LONG_TEXT_OMP_2026_11_18__ The text budget over a radio, by firmware (Android
 * `MessageSendPolicy.LORA_OMP_UTF8_BYTES`, iOS the same): an OSHI Mesh radio carries one OMP
 * message of 64 × 182 B, which fits 4,000 UTF-8 bytes of text after the envelope (~1,350 +
 * 2.38 × bytes, measured on Android); a stock radio keeps the 12-frame chunking.
 */
object LoRaTextBudget {
    const val OMP_UTF8_BYTES = 4_000

    /** True when [body] (an [OshiMeshProtocol.encodeBody] result) fits the OMP path. */
    fun fitsOmp(body: ByteArray): Boolean =
        (body.size + OshiMeshProtocol.MAX_FRAG_DATA - 1) / OshiMeshProtocol.MAX_FRAG_DATA <= OshiMeshProtocol.MAX_FRAGS
}
