package com.oshi.desktop.lora

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * __DESKTOP_NODE_PERIMETER_2026_11_07__
 *
 * The maths behind the node perimeter view, with no drawing in it, so it can be
 * tested without a window.
 *
 * I first closed this request as impossible on the desktop because the client
 * has no map: no tiles, no renderer, Places is a text search. That reasoning
 * was circular — there is no map because nobody wrote one, and the thing asked
 * for does not need one. What Meshtastic's circle communicates is HOW FAR A
 * NODE REACHES, drawn around the node. That is geometry: relative positions and
 * a radius in metres. A street basemap underneath is a nicety, not the feature.
 *
 * So this projects nodes into a flat local frame centred on our own radio and
 * scales the result to fit, which is enough to draw the perimeter honestly.
 * What it deliberately does NOT do is pretend to be a map: no tiles, no roads,
 * no absolute coordinates on screen. It is a range diagram, and it is labelled
 * as one.
 */
object NodeMapGeometry {

    /** A node placed in the local frame: metres east and north of us. */
    data class Placed(
        val num: UInt,
        val name: String,
        val eastM: Double,
        val northM: Double,
        /** Reach radius in metres, or null when none can be claimed. */
        val radiusM: Double?,
    )

    /** What to draw, already scaled to the view. */
    data class Plan(
        val placed: List<Placed>,
        /** Metres per unit of view radius — the scale bar's number. */
        val spanM: Double,
    )

    private const val M_PER_DEG_LAT = 111_320.0

    /**
     * Metres east/north of ([originLat], [originLon]).
     *
     * Equirectangular, which is wrong over long distances and exactly right
     * here: LoRa reach is kilometres, and over kilometres the error is
     * centimetres. Longitude is scaled by cos(latitude) or everything stretches
     * sideways as you move away from the equator — the bug that makes a node due
     * east look north-east in Oslo.
     */
    fun offsetMeters(
        originLat: Double,
        originLon: Double,
        lat: Double,
        lon: Double,
    ): Pair<Double, Double> {
        val north = (lat - originLat) * M_PER_DEG_LAT
        val east = (lon - originLon) * M_PER_DEG_LAT * cos(Math.toRadians(originLat))
        return east to north
    }

    /**
     * Places every node that has a position, and works out the span the view
     * must cover.
     *
     * The span includes each node's REACH, not just its centre: a circle whose
     * edge falls outside the view is a circle that lies about where coverage
     * stops. Returns null when we do not know where we are — without an origin
     * there is no frame, and guessing one would draw a confident diagram of
     * nothing.
     */
    fun plan(
        originLat: Double?,
        originLon: Double?,
        nodes: List<LoRaNodeRoster.Node>,
    ): Plan? {
        if (originLat == null || originLon == null) return null
        if (!plausible(originLat, originLon)) return null

        val placed = nodes.mapNotNull { n ->
            val lat = n.latitude ?: return@mapNotNull null
            val lon = n.longitude ?: return@mapNotNull null
            if (!plausible(lat, lon)) return@mapNotNull null
            val (e, nr) = offsetMeters(originLat, originLon, lat, lon)
            Placed(
                num = n.num,
                name = n.name,
                eastM = e,
                northM = nr,
                radiusM = LoRaCoverage.radiusMeters(n.rssi, n.hopsAway),
            )
        }
        if (placed.isEmpty()) return null

        val needed = placed.maxOf { p ->
            val d = kotlin.math.hypot(p.eastM, p.northM)
            d + (p.radiusM ?: 0.0)
        }
        // A floor so a single node sitting on top of us does not divide by zero
        // and so the scale bar reads a round, believable number.
        val span = max(MIN_SPAN_M, needed * 1.15)
        return Plan(placed, span)
    }

    /**
     * Null Island and out-of-range values are not positions. Same rule the map
     * centre uses — a radio with no fix reports 0,0 and it is not in the Gulf
     * of Guinea.
     */
    fun plausible(lat: Double, lon: Double): Boolean =
        abs(lat) <= 90.0 && abs(lon) <= 180.0 && !(abs(lat) < 1e-7 && abs(lon) < 1e-7)

    /** A tidy number of metres for the scale ring, at or below [span]. */
    fun scaleStepM(span: Double): Double {
        val target = span / 2.0
        val steps = doubleArrayOf(
            100.0, 200.0, 250.0, 500.0, 1_000.0, 2_000.0, 2_500.0,
            5_000.0, 10_000.0, 20_000.0, 50_000.0,
        )
        return steps.lastOrNull { it <= target } ?: steps.first()
    }

    /** "500 m" / "2.0 km", for the ring label. */
    fun formatDistance(m: Double): String =
        if (m < 1_000) "%.0f m".format(m) else "%.1f km".format(m / 1_000.0)

    const val MIN_SPAN_M = 300.0
}
