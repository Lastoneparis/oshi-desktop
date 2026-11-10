package com.oshi.desktop.lora

/**
 * LoRaCoverage — how far our link to a radio plausibly reaches.
 *
 * Third copy of `OSHI/LoRaCoverage.swift` and
 * `OSHI-Android/.../service/lora/LoRaCoverage.kt`, numbers and rules identical.
 *
 * WHY IT IS HERE WITHOUT A MAP
 * ----------------------------
 * The phones draw this as a soft ring around the node. This client has no map
 * renderer at all — "Places" is a text search over offline data — so there is
 * no circle to draw, and inventing one would be a worse answer than none.
 *
 * But the circle's VALUE is not the circle: it is the number behind it, "this
 * radio reaches about that far". That fact belongs next to the distance the
 * node list already shows, and a desktop operator planning where to walk needs
 * it as much as a phone user does. So the estimate is shared, the drawing is
 * not.
 *
 * WHY IT IS SOFT, AND WHY THAT IS NOT LAZINESS
 * --------------------------------------------
 * A radius from signal strength is a weak estimate. Coverage is not a circle:
 * it is a shape carved by terrain, buildings, antenna orientation and the other
 * radio's transmit power, none of which a received packet reveals. The same
 * −90 dBm means two kilometres across a lake and eighty metres through a city
 * block. The phones therefore draw a faint fill with a dashed edge; here the
 * wording hedges for the same reason ("about", never a boundary).
 *
 * THE TRAP: A RELAYED NODE'S SIGNAL IS NOT ITS OWN
 * ------------------------------------------------
 * [radiusMeters] returns null for anything heard through a relay. With
 * `hopsAway > 0` the RSSI belongs to the LAST HOP, not to the node that
 * originated the packet. A figure of that size quoted for the distant node
 * would be a confident number built from a measurement of someone else, wrong
 * by however far the relay happens to be. No measurement of OUR link to that
 * node exists, so nothing is claimed.
 */
object LoRaCoverage {

    /** Signal floor below which a link is not usefully alive (LongFast). */
    const val MIN_USABLE_RSSI = -125

    /** Signal at which the other radio is effectively next to you. */
    const val MAX_RSSI = -30

    const val MIN_RADIUS_M = 150.0

    /**
     * The furthest we will assert from a single ground-level RSSI reading. Not
     * the record for LoRa — hilltop links run hundreds of kilometres — but
     * beyond this the estimate is dominated by terrain we cannot see.
     */
    const val MAX_RADIUS_M = 5_000.0

    /** Estimated reach radius in metres, or null when no honest estimate exists. */
    fun radiusMeters(rssi: Int?, hopsAway: Int?): Double? {
        if (hopsAway != null && hopsAway > 0) return null
        if (rssi == null || rssi < MIN_USABLE_RSSI || rssi > 0) return null

        val span = (MAX_RSSI - MIN_USABLE_RSSI).toDouble()
        val quality = ((rssi - MIN_USABLE_RSSI) / span).coerceIn(0.0, 1.0)

        // Inverted and squared: a strong signal means the radio is close, so the
        // reach is tight; a weak one means we are already at the edge of what
        // this link covers. Squared because path loss is logarithmic in distance
        // — a 10 dB drop is roughly a tripling of range, so linear scaling made
        // every mid-strength node look identical.
        val weakness = 1 - quality
        return MIN_RADIUS_M + (MAX_RADIUS_M - MIN_RADIUS_M) * (weakness * weakness)
    }

    /**
     * The line the node list shows, or null when nothing honest can be said.
     *
     * Hedged on purpose. "reaches about 1.2 km" is a different claim from
     * "reaches 1.2 km", and only the first one is true.
     */
    fun reachLabel(rssi: Int?, hopsAway: Int?): String? {
        val m = radiusMeters(rssi, hopsAway) ?: return null
        return if (m < 1000) "reaches about %.0f m".format(m)
        else "reaches about %.1f km".format(m / 1000.0)
    }
}
