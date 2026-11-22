package com.oshi.desktop.lora

import java.util.prefs.Preferences
import kotlin.math.abs

/**
 * __DESKTOP_LORA_BEACON_2026_10_28__ Sharing THIS machine's position over the mesh, the way
 * Meshtastic does it for a node that has no GPS of its own.
 *
 * ## Why this exists at all, after I argued it could not
 *
 * The phones got a position beacon earlier today, and I concluded the desktop could not
 * have one: PARITY row 0.19 says this client has no GPS, `LoRaNodeRoster.ourPosition()`
 * reads `positions[myNodeNum]` — our *radio's* fix, not the computer's — and a radio with
 * GPS already beacons itself from firmware, so re-sending it would be duplication.
 *
 * All of that is still true and the conclusion was still wrong, because it answered a
 * narrower question than the one asked. A Mac has no GPS; it does have a position its owner
 * knows. Meshtastic's own clients cover exactly this case with a **fixed position**
 * (`Config.PositionConfig.fixed_position`): you state where the node is, and it beacons
 * that. A desk does not move, which is precisely why stating it once is enough.
 *
 * The honest shape of the gap was therefore "no position SOURCE", not "no beacon", and a
 * source the person types is a real source. Recorded at length because the reasoning failed
 * in a way worth not repeating: *"the platform cannot do X"* was true of the sensor and
 * false of the feature.
 *
 * ## What it is not
 *
 * It is not a fix and it is not tracking. Nothing here reads WiFi, IP geolocation or any
 * other inference — those would be a position the person did not consent to publishing,
 * for a feature whose entire output is a public broadcast. If this machine moves, the
 * stored position is simply wrong until somebody changes it, and that is the correct
 * failure mode for a value a human asserted.
 *
 * Stored in `java.util.prefs` beside the call-device choices, for the same reason they are:
 * this is a property of THIS machine, not account data, and must never ride device sync or
 * an export. A position that followed a user to another computer would be a lie on that
 * computer's first beacon.
 */
object LoRaFixedPosition {

    private val prefs: Preferences? =
        runCatching { Preferences.userRoot().node("com/oshi/desktop/lora-position") }.getOrNull()

    private const val LAT = "fixedLat"
    private const val LON = "fixedLon"
    private const val ON = "beaconEnabled"

    /**
     * Where this machine is, as its owner stated it, or null when nobody has said.
     *
     * Null is the default and stays the default. A beacon cannot be enabled without one,
     * so there is no path from a fresh install to transmitting a position by accident.
     */
    @Volatile
    var position: Pair<Double, Double>? = load()
        private set

    /**
     * Whether to beacon it. Off by default and persisted off.
     *
     * Separate from [position] on purpose: forgetting your coordinates should not be the
     * only way to stop broadcasting them, and someone who turns the beacon off expects the
     * switch to be what changed, not their settings to be erased.
     */
    @Volatile
    var enabled: Boolean = runCatching { prefs?.getBoolean(ON, false) ?: false }.getOrDefault(false)
        private set

    /**
     * State a position. Returns false — and changes nothing — for coordinates that are not
     * on Earth, or for the 0/0 that a blank form and a GPS with no lock both produce.
     *
     * 0/0 is refused here as well as at the sender, and deliberately twice: the sender's
     * guard stops a bad value going out, this one stops it being *stored*, which is what
     * would otherwise put this node in the Gulf of Guinea on every map that heard it for as
     * long as nobody noticed.
     */
    fun set(latitude: Double, longitude: Double): Boolean {
        if (latitude.isNaN() || longitude.isNaN()) return false
        if (abs(latitude) < 1e-9 && abs(longitude) < 1e-9) return false
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return false
        position = latitude to longitude
        runCatching {
            prefs?.putDouble(LAT, latitude)
            prefs?.putDouble(LON, longitude)
            prefs?.flush()
        }
        return true
    }

    /** Forget the stored position, and stop beaconing — there is nothing left to send. */
    fun clear() {
        position = null
        enabled = false
        runCatching {
            prefs?.remove(LAT); prefs?.remove(LON); prefs?.putBoolean(ON, false); prefs?.flush()
        }
    }

    /**
     * Turn the beacon on or off. Turning it ON without a stored position is refused rather
     * than remembered: a switch that reads "on" while nothing is being sent is the kind of
     * indicator this codebase has been bitten by.
     */
    fun setEnabled(value: Boolean): Boolean {
        if (value && position == null) return false
        enabled = value
        runCatching { prefs?.putBoolean(ON, value); prefs?.flush() }
        return true
    }

    private fun load(): Pair<Double, Double>? = runCatching {
        val la = prefs?.getDouble(LAT, Double.NaN) ?: Double.NaN
        val lo = prefs?.getDouble(LON, Double.NaN) ?: Double.NaN
        if (la.isNaN() || lo.isNaN()) null else la to lo
    }.getOrNull()
}
