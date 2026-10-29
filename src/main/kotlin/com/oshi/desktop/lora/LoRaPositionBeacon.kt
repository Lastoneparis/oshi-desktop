package com.oshi.desktop.lora

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * __DESKTOP_LORA_BEACON_2026_10_28__ The third implementation of the position-beacon
 * policy, and the one that has to prove the other two were not a coincidence.
 *
 * Android and iOS got this earlier today (`LoRaPositionBeacon` on each). This client
 * beacons a position its owner stated rather than one a sensor measured — see
 * [LoRaFixedPosition] for why that is Meshtastic's own answer for a node without GPS, and
 * for the reasoning that first said a desktop beacon was impossible.
 *
 * ## The rules are not re-decided here
 *
 * [decide] is the phones' function, branch for branch, and the three are compared by one
 * SHA-256 over a shared table of inputs (`LoRaBeaconParityTest`). That matters more here
 * than it did between the two phones: a third implementation written from a description
 * rather than from the code is exactly where a policy quietly forks, and matching test
 * NAMES would not catch it — this repository has already paid for that lesson.
 *
 * The rules themselves come from Meshtastic's device defaults, because this mesh is shared
 * with its clients: a 15-minute floor between beacons (`position_broadcast_secs`), a 100 m
 * movement gate (smart position), and a 1-hour ceiling so a stationary node still announces
 * itself to a neighbour that joined or restarted since.
 *
 * **That ceiling is what makes this client work at all.** A fixed position never moves, so
 * [MIN_MOVE_M] suppresses every beacon after the first and only [MAX_SILENCE_MS] ever fires
 * again. It was added for the phones to stop a parked handset disappearing from its
 * neighbours' maps; here it is the entire clock. Nobody designed it for this, which is
 * worth saying out loud before someone "optimises" it away as redundant with the interval.
 *
 * ## What is NOT here
 *
 * No timer. The desktop's beacon is driven from the LoRa pane's existing one-second tick
 * (the same one that refreshes the node roster), so this file owns no thread and no clock —
 * `nowMs` is a parameter. A beacon is also never sent while no radio is attached, which on
 * this client is the common case.
 */
object LoRaPositionBeacon {

    /** The floor between two beacons. Meshtastic's `position_broadcast_secs` default. */
    const val INTERVAL_MS = 15 * 60 * 1000L

    /** Below this, a new position is the same place and does not deserve airtime. */
    const val MIN_MOVE_M = 100.0

    /**
     * __DESKTOP_LORA_WRITE_ONCE_2026_10_29__ Effectively never, and that is the fix.
     *
     * This was one hour, inherited from the phones, where it means "beacon even standing
     * still so a new neighbour can see us". On THIS client it meant something else and
     * something harmful, which only became visible once the lane was understood:
     *
     * The desktop does not beacon. It writes `AdminMessage{set_fixed_position}` into the
     * node's CONFIGURATION, and the firmware then announces on its own schedule — at most
     * every six hours for a node that has pinned itself (`sendOurPosition`'s stationary
     * floor). Re-sending the same coordinates on a timer tells the radio nothing it does
     * not already know and writes its FLASH every hour, for as long as the app runs. That
     * is wear on somebody's hardware in exchange for nothing.
     *
     * A fixed position needs writing when it CHANGES, and at no other time. The ceiling is
     * therefore set beyond any plausible session: the interval and the movement gate still
     * suppress a redundant write, and `LoRaFixedPosition.set` already resets the beacon so
     * a NEW position goes out at once.
     */
    const val MAX_SILENCE_MS = 365L * 24 * 60 * 60 * 1000L

    private const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * Haversine metres — the same formula and the same radius as
     * `CheckInEtaEstimator.greatCircleMetres` on Android and `greatCircleMetres` on iOS.
     * A fourth spelling of this would be a fourth chance to disagree, and the shared digest
     * would catch it only because the distances feed [decide].
     */
    fun greatCircleMetres(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val dLat = Math.toRadians(toLat - fromLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * Why a tick did or did not put a frame on the air.
     *
     * The names are the phones' names, because the parity digest compares them as strings.
     * `NO_PERMISSION` has no analogue on a desktop — there is no location permission to
     * grant — and is kept rather than dropped so [decide] stays the same function on all
     * three platforms; this client passes `hasPermission = true` once a position is stored,
     * which is the honest reading: the person typing their coordinates IS the consent.
     */
    enum class Decision {
        SEND, DISABLED, NO_RADIO, NO_PERMISSION, NO_FIX, TOO_SOON, STILL_HERE
    }

    /**
     * The phones' policy, branch for branch. Do not re-decide a threshold here: change it
     * in all three and update the shared digest in one commit, deliberately.
     */
    fun decide(
        enabled: Boolean,
        radioReady: Boolean,
        hasPermission: Boolean,
        lat: Double?,
        lon: Double?,
        nowMs: Long,
        lastSentAtMs: Long,
        lastLat: Double,
        lastLon: Double,
        intervalMs: Long = INTERVAL_MS,
        minMoveM: Double = MIN_MOVE_M,
        maxSilenceMs: Long = MAX_SILENCE_MS,
    ): Decision {
        // The switch first, so turning it off stops every later question too.
        if (!enabled) return Decision.DISABLED
        if (!radioReady) return Decision.NO_RADIO
        if (!hasPermission) return Decision.NO_PERMISSION
        if (lat == null || lon == null) return Decision.NO_FIX
        // 0/0 is what a receiver with no lock reports and what a blank form produces.
        if (lat == 0.0 && lon == 0.0) return Decision.NO_FIX
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return Decision.NO_FIX

        if (lastSentAtMs <= 0L) return Decision.SEND
        val since = nowMs - lastSentAtMs
        // A clock that went backwards must not be able to mute the beacon until it catches up.
        if (since < 0L) return Decision.SEND
        if (since >= maxSilenceMs) return Decision.SEND
        if (since < intervalMs) return Decision.TOO_SOON
        val moved = greatCircleMetres(lastLat, lastLon, lat, lon)
        return if (moved < minMoveM) Decision.STILL_HERE else Decision.SEND
    }

    // ------------------------------------------------------------------ the driver

    @Volatile private var lastSentAtMs = 0L
    @Volatile private var lastLat = 0.0
    @Volatile private var lastLon = 0.0

    /** The last answer, for the pane to explain itself with. */
    @Volatile
    var lastDecision: Decision = Decision.DISABLED
        private set

    /**
     * One pass, driven by the LoRa pane's existing tick.
     *
     * @param send puts a POSITION_APP broadcast on the air and reports whether the radio
     *   took it. Injected rather than reached for, so this is testable without a serial
     *   port — and so the one place that knows how to build the frame stays
     *   [LoRaProto.positionPayload].
     * @return the decision, so a caller can show it.
     */
    fun tick(
        radioReady: Boolean,
        nowMs: Long,
        send: (latitude: Double, longitude: Double) -> Boolean,
    ): Decision {
        val pos = LoRaFixedPosition.position
        val d = decide(
            enabled = LoRaFixedPosition.enabled,
            radioReady = radioReady,
            // A stated position IS the consent on this platform; see [Decision].
            hasPermission = pos != null,
            lat = pos?.first, lon = pos?.second,
            nowMs = nowMs,
            lastSentAtMs = lastSentAtMs, lastLat = lastLat, lastLon = lastLon,
        )
        lastDecision = d
        if (d != Decision.SEND || pos == null) return d
        if (send(pos.first, pos.second)) {
            lastSentAtMs = nowMs
            lastLat = pos.first; lastLon = pos.second
        }
        return d
    }

    /** Forget what was last sent — on attach, on a position change, and for tests. */
    fun reset() {
        lastSentAtMs = 0L; lastLat = 0.0; lastLon = 0.0; lastDecision = Decision.DISABLED
    }
}
