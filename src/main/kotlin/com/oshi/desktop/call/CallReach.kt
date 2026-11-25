package com.oshi.desktop.call

/**
 * __CALL_WAKE_2026_11_22__ What the CALLER knows about the callee's phone while it rings.
 *
 * Journal 21/11: callers hung up after ~11 s on calls the callee's phone had not even
 * received. The call server now tracks the wake (`GET /call-reach/:callId`,
 * ServerPatches/call_wake_2026_11_22) and a ringing callee reports `callRinging`.
 * Same rules as Android `CallReach` and iOS `CallReach`.
 */
enum class CallReach {
    CALLING, PUSHED, DELIVERED, RINGING, UNREACHABLE;

    enum class Label { RINGING, CALLING, UNREACHABLE }

    companion object {
        const val RINGING_TYPE = "callRinging"
        /** `/signal` requires a non-empty `signal`; the server records and drops it. */
        const val RINGING_PLACEHOLDER = "cmluZw=="

        fun fromServer(state: String?): CallReach? = when (state) {
            "calling" -> CALLING
            "pushed" -> PUSHED
            "delivered" -> DELIVERED
            "ringing" -> RINGING
            "unreachable" -> UNREACHABLE
            else -> null
        }

        private fun rank(r: CallReach) = when (r) {
            CALLING -> 0; UNREACHABLE -> 1; PUSHED -> 2; DELIVERED -> 3; RINGING -> 4
        }

        /** Never step back; UNREACHABLE is overtaken by any sign of the callee. */
        fun merge(old: CallReach?, new: CallReach?): CallReach? = when {
            new == null -> old
            old == null -> new
            rank(new) >= rank(old) -> new
            else -> old
        }

        /** null (server without the route) keeps the old "Ringing…"; unreachable after 6 s only. */
        fun outgoingLabel(reach: CallReach?, ringingForMs: Long): Label = when (reach) {
            null, RINGING, DELIVERED -> Label.RINGING
            UNREACHABLE -> if (ringingForMs >= 6_000L) Label.UNREACHABLE else Label.CALLING
            PUSHED, CALLING -> Label.CALLING
        }
    }
}
