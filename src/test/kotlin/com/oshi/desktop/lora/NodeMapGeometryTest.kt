package com.oshi.desktop.lora

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_NODE_PERIMETER_2026_11_07__
 *
 * The range diagram's maths. Drawing is not tested here; placement is, because
 * a diagram that puts a node in the wrong direction is worse than no diagram —
 * someone walks the wrong way holding it.
 */
class NodeMapGeometryTest {

    private fun node(
        num: UInt = 1u,
        name: String = "n",
        lat: Double? = null,
        lon: Double? = null,
        rssi: Int? = null,
        hops: Int? = null,
    ) = LoRaNodeRoster.Node(
        num = num, name = name, lastHeardMs = 1L,
        latitude = lat, longitude = lon, distanceKm = null,
        rssi = rssi, hopsAway = hops,
    )

    // ---------------- placement

    /** Due north must be north: positive northing, no easting. */
    @Test
    fun `a node due north is placed north`() {
        val (e, n) = NodeMapGeometry.offsetMeters(48.8566, 2.3522, 48.8666, 2.3522)
        assertTrue("easting should be ~0, was $e", abs(e) < 1.0)
        assertTrue("northing should be ~1113 m, was $n", n in 1_000.0..1_200.0)
    }

    /**
     * THE test for the projection.
     *
     * Longitude degrees shrink with latitude. Forget the cos(lat) factor and a
     * node due east of Oslo is drawn far too far out — at 60° by a factor of
     * two. Paris and Oslo are compared at the same longitude delta: Oslo's
     * easting must be visibly SMALLER.
     */
    @Test
    fun `longitude is scaled by latitude`() {
        val (eParis, _) = NodeMapGeometry.offsetMeters(48.8566, 2.3522, 48.8566, 2.3622)
        val (eOslo, _) = NodeMapGeometry.offsetMeters(59.9139, 10.7522, 59.9139, 10.7622)
        assertTrue("Paris easting $eParis should exceed Oslo's $eOslo", eParis > eOslo)
        // cos(59.91)/cos(48.86) ~= 0.502/0.659 ~= 0.76
        assertEquals(0.76, eOslo / eParis, 0.03)
    }

    /** A node east of us is east, not west: the sign must not flip. */
    @Test
    fun `a node due east is placed east`() {
        val (e, n) = NodeMapGeometry.offsetMeters(48.8566, 2.3522, 48.8566, 2.3622)
        assertTrue("easting should be positive, was $e", e > 0)
        assertTrue("northing should be ~0, was $n", abs(n) < 1.0)
    }

    // ---------------- the span

    /**
     * THE test for the span: a circle whose edge falls outside the view is a
     * circle that lies about where coverage stops.
     */
    @Test
    fun `the span covers a node's whole reach, not just its centre`() {
        // -40 dBm heard directly: a tight circle. Node 2 km away.
        val n = node(lat = 48.8746, lon = 2.3522, rssi = -40, hops = 0)
        val plan = NodeMapGeometry.plan(48.8566, 2.3522, listOf(n))!!
        val p = plan.placed.single()
        val reach = requireNonNull(p.radiusM)
        val outerEdge = hypot(p.eastM, p.northM) + reach
        assertTrue(
            "span ${plan.spanM} must cover the circle's far edge $outerEdge",
            plan.spanM >= outerEdge,
        )
    }

    private fun requireNonNull(d: Double?): Double {
        assertNotNull("this node should have a reach estimate", d)
        return d!!
    }

    /** A relayed node is still PLACED — we just claim no perimeter for it. */
    @Test
    fun `a relayed node is placed but gets no circle`() {
        val n = node(lat = 48.8666, lon = 2.3522, rssi = -70, hops = 2)
        val p = NodeMapGeometry.plan(48.8566, 2.3522, listOf(n))!!.placed.single()
        assertTrue("it should still be on the diagram", abs(p.northM) > 100)
        assertNull("its RSSI belongs to the relay, so no circle", p.radiusM)
    }

    // ---------------- refusals

    /** Without our own position there is no frame; a guessed centre is a lie. */
    @Test
    fun `no origin means no diagram`() {
        val n = node(lat = 48.86, lon = 2.35, rssi = -70, hops = 0)
        assertNull(NodeMapGeometry.plan(null, 2.35, listOf(n)))
        assertNull(NodeMapGeometry.plan(48.86, null, listOf(n)))
    }

    /** Null Island is not a position — same rule the map centre uses. */
    @Test
    fun `null island is refused at both ends`() {
        assertNull(
            "our own 0,0 is a radio with no fix",
            NodeMapGeometry.plan(0.0, 0.0, listOf(node(lat = 48.86, lon = 2.35))),
        )
        assertNull(
            "a peer's 0,0 is the same",
            NodeMapGeometry.plan(48.8566, 2.3522, listOf(node(lat = 0.0, lon = 0.0))),
        )
    }

    @Test
    fun `a node with no position is left off, not placed at the origin`() {
        assertNull(NodeMapGeometry.plan(48.8566, 2.3522, listOf(node())))
    }

    @Test
    fun `out of range coordinates are refused`() {
        assertNull(NodeMapGeometry.plan(48.8566, 2.3522, listOf(node(lat = 91.0, lon = 2.0))))
    }

    /** A node on top of us must not make the scale divide by zero. */
    @Test
    fun `a node at our own position still yields a usable span`() {
        val plan = NodeMapGeometry.plan(48.8566, 2.3522, listOf(node(lat = 48.8566, lon = 2.3522)))!!
        assertTrue("span ${plan.spanM} must be positive", plan.spanM >= NodeMapGeometry.MIN_SPAN_M)
    }

    // ---------------- the scale ring

    @Test
    fun `the scale step is a round number below half the span`() {
        for (span in listOf(300.0, 900.0, 4_000.0, 12_000.0, 60_000.0)) {
            val step = NodeMapGeometry.scaleStepM(span)
            assertTrue("step $step must be <= half of $span", step <= span / 2.0 || step == 100.0)
            assertTrue("step $step must be positive", step > 0)
        }
    }

    @Test
    fun `distances read in metres then kilometres`() {
        assertEquals("500 m", NodeMapGeometry.formatDistance(500.0))
        assertEquals("1.2 km", NodeMapGeometry.formatDistance(1_200.0))
    }
}

/**
 * __MESSAGE_THE_NODE_FROM_THE_MAP_2026_11_07__
 *
 * "start a conversation direct with the node or with hop if possible" — the
 * hop half. A unicast is addressed to the node number and the mesh routes it,
 * so a relayed node is reachable exactly like a direct one. Gating the action
 * on `hopsAway == 0` would remove a working capability on a false premise, and
 * it is the kind of gate someone adds later while "tidying up".
 */
class NodeMessagingReachTest {

    @org.junit.Test
    fun `messaging a node is not gated on hop count`() {
        val pane = java.io.File("src/main/kotlin/com/oshi/desktop/ui/components/LoRaPane.kt").readText()
        val i = pane.indexOf("TextAction(\"Message\")")
        org.junit.Assert.assertTrue("the Message action is gone", i > 0)
        // The 400 characters before it must not introduce a hop condition.
        val before = pane.substring(maxOf(0, i - 400), i)
        val code = before.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        org.junit.Assert.assertFalse(
            "a hop gate appeared before the Message action: a relayed node is reachable",
            Regex("""if\s*\([^)]*hops""", RegexOption.IGNORE_CASE).containsMatchIn(code),
        )
    }
}
