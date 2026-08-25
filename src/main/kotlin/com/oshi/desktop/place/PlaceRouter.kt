package com.oshi.desktop.place

import com.oshi.desktop.msg.ControlEvent
import com.oshi.desktop.msg.ControlPayloadRouter
import com.oshi.desktop.msg.ControlPrefix

/**
 * What a `📍LOCATION📍` or `🛡️CHECK_IN🛡️` turned out to be. PARITY.md row 0.19.
 *
 * The shape mirrors [ControlEvent] on purpose — [Unparseable] is separate from [NotMine]
 * for the reason stated there, which lands harder in this row: a check-in that fails to
 * parse is an emergency alert that never arms, and a failure that is not countable is a
 * failure nobody counts.
 */
sealed class PlaceEvent {

    /**
     * A location pin or live share. [state] is what it is doing NOW, taken against the
     * caller's clock and — when the payload names a session — against
     * [LiveShareTracker]'s pinned expiry rather than the payload's own.
     */
    data class Location(val payload: LocationPayload, val state: LiveState) : PlaceEvent()

    /** A check-in of any of the seven types. */
    data class CheckIn(val payload: CheckInPayload) : PlaceEvent()

    /** One of this row's two sentinels with an unusable body. */
    data class Unparseable(val prefix: String) : PlaceEvent()

    /** Prose, or a sentinel belonging to some other row. Never an error. */
    object NotMine : PlaceEvent()
}

/**
 * Row 0.19's half of the ONE control-payload dispatch — not a second router.
 *
 * ============================================================ WHY THIS IS NOT A SECOND ROUTER
 *
 * `📍LOCATION📍` and `🛡️CHECK_IN🛡️` are ordinary emoji sentinels on the `content` string,
 * exactly like the receipts and reactions row 0.18 already handles, so building a parallel
 * catalog-and-prefix-matcher for them would make this client the thing
 * [ControlPrefix]'s doc comment exists to prevent — the seventh partial copy of a list that
 * has already been six.
 *
 * So there is exactly one classifier in this project and this class calls it:
 * [ControlPayloadRouter.classify]. That function already recognises both of this row's
 * prefixes (they are in the shared catalog) and already returns them as
 * [ControlEvent.Foreign] — a case whose own documentation names row 0.19 as its owner.
 * This class is the code that case was written for. It never inspects a prefix that
 * [ControlPrefix] has not already resolved to its canonical spelling, which is how a
 * payload arriving with the bare `🛡` instead of `🛡️` is handled here for free.
 *
 * The extension needed on row 0.18's side was one line of visibility — see
 * [ControlPayloadRouter.Companion].
 *
 * ============================================================ WHY IT TAKES A CLOCK
 *
 * [route] takes `nowMs` rather than reading `System.currentTimeMillis()`. Expiry is the
 * security property of this row and a property evaluated against a hidden global clock
 * cannot be tested at its boundary — which is precisely where the two shipped clients'
 * expiry bugs live. Every "is this still live" decision in this package is a pure function
 * of the bytes and an explicit instant.
 *
 * ============================================================ WHAT IT DOES NOT ROUTE
 *
 * Contact cards. They are media attachment bytes with no sentinel at all — see
 * [ContactCardPayload]'s WHY IT IS NOT A SENTINEL. There is nothing for a prefix router to
 * dispatch on, and inventing a prefix so this class could own all three of row 0.19's
 * payloads would put bytes on the wire that no shipped client emits or reads.
 *
 * It also does not SEND, does not decide whether to raise a banner (that is
 * [LocationPayload.isSilentForPush]'s answer and the notification layer's decision), and
 * does not write to any store — row 0.19's payloads are `Kind.RENDERED`, so their
 * destination is a bubble.
 */
class PlaceRouter(private val tracker: LiveShareTracker = LiveShareTracker()) {

    /** The tracker this router folds live shares into, for a CLI or a test to inspect. */
    fun tracker(): LiveShareTracker = tracker

    /**
     * Classify [plaintext] through the shared classifier and decode it if it is ours.
     *
     * A live share with a `sessionId` is folded into [tracker], so a second call with a
     * later ping of the same session is answered against the pinned expiry. That fold is a
     * side effect and it is the only one in this class; it is here rather than in the codec
     * because a codec that remembered things could not be tested against a byte shape.
     */
    fun route(plaintext: String, nowMs: Long): PlaceEvent {
        val event = ControlPayloadRouter.classify(plaintext)
        if (event !is ControlEvent.Foreign) return PlaceEvent.NotMine

        return when (event.prefix) {
            ControlPrefix.LOCATION -> {
                val p = LocationPayload.decode(plaintext)
                    ?: return PlaceEvent.Unparseable(ControlPrefix.LOCATION)
                PlaceEvent.Location(p, tracker.observe(p, nowMs))
            }

            ControlPrefix.CHECK_IN, ControlPrefix.CHECKIN -> {
                val p = CheckInPayload.decode(plaintext)
                    ?: return PlaceEvent.Unparseable(event.prefix)
                PlaceEvent.CheckIn(p)
            }

            // Some other row's sentinel. `Foreign` means row 0.18 knew what it was and
            // knew it was not row 0.18's; this branch says the same thing about row 0.19.
            else -> PlaceEvent.NotMine
        }
    }

    /** One line of text for [plaintext], or null when it is not this row's. */
    fun renderToText(plaintext: String, nowMs: Long): String? =
        when (val e = route(plaintext, nowMs)) {
            is PlaceEvent.Location -> e.payload.copy().let { p ->
                // Render against the TRACKED state, not the payload's own reading of
                // itself — that is the whole point of the tracker existing.
                when (e.state) {
                    LiveState.LIVE -> p.renderToText(nowMs)
                    LiveState.STOPPED -> p.copy(isStopped = true).renderToText(nowMs)
                    LiveState.EXPIRED -> "📍 Live location expired: " +
                        (p.address ?: "${p.latitude}, ${p.longitude}")
                    else -> p.renderToText(nowMs)
                }
            }
            is PlaceEvent.CheckIn -> e.payload.renderToText(nowMs)
            is PlaceEvent.Unparseable -> null
            PlaceEvent.NotMine -> null
        }
}
