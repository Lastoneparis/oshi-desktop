package com.oshi.desktop.place

import com.oshi.desktop.msg.ControlEvent
import com.oshi.desktop.msg.ControlPayloadRouter
import com.oshi.desktop.msg.ControlPrefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That row 0.19 rides the SAME dispatch as row 0.18, and that it does not claim what is not
 * its own.
 *
 * The first test is the load-bearing one: it asserts that the shared classifier already
 * recognises both of this row's sentinels and hands them over as
 * [ControlEvent.Foreign] — which is what makes [PlaceRouter] a consumer of the existing
 * router rather than a second one.
 */
class PlaceRouterTest {

    private val t0 = 1_754_307_200_000L
    private val nowApple = 738_889_200.0

    private fun locationWire(
        isLive: Boolean = false,
        expiresApple: Double? = null,
        sessionId: String? = null,
        isStopped: Boolean = false,
    ) = ControlPrefix.LOCATION + buildString {
        append("""{"latitude":48.85661,"longitude":2.35222,"timestamp":776000000.0,"isLive":$isLive""")
        if (expiresApple != null) append(""","expiresAt":$expiresApple""")
        if (sessionId != null) append(""","sessionId":"$sessionId"""")
        append(""","isUpdate":false,"isStopped":$isStopped}""")
    }

    // ------------------------------------------- one classifier, not two

    @Test
    fun `the shared classifier already hands this row its two sentinels`() {
        for (wire in listOf(locationWire(), ControlPrefix.CHECK_IN + "{}")) {
            val e = ControlPayloadRouter.classify(wire)
            assertTrue("$wire must classify as Foreign, not Prose", e is ControlEvent.Foreign)
            assertEquals(ControlPrefix.Kind.RENDERED, (e as ControlEvent.Foreign).kind)
        }
        assertEquals(ControlPrefix.LOCATION, (ControlPayloadRouter.classify(locationWire()) as ControlEvent.Foreign).prefix)
    }

    @Test
    fun `the pure classifier and the store-bound one give the same answer`() {
        // The row-0.19 extension hoisted `classify` to a companion. If the instance method
        // ever stopped delegating, this row would silently be reading a different catalog.
        val wire = locationWire()
        val instance = ControlPayloadRouter(com.oshi.desktop.store.MessageStore(
            java.nio.file.Files.createTempDirectory("place-router-test").toFile(),
        ))
        assertEquals(ControlPayloadRouter.classify(wire), instance.classify(wire))
    }

    // ------------------------------------------- routing

    @Test
    fun `routes a one-time pin`() {
        val e = PlaceRouter().route(locationWire(), t0)
        assertTrue(e is PlaceEvent.Location)
        assertEquals(LiveState.NOT_LIVE, (e as PlaceEvent.Location).state)
        assertEquals(48.85661, e.payload.latitude, 0.0)
    }

    @Test
    fun `routes a check-in`() {
        val e = PlaceRouter().route(
            ControlPrefix.CHECK_IN + """{"type":"arrived","sessionId":"s1","timestamp":$nowApple}""",
            t0,
        )
        assertTrue(e is PlaceEvent.CheckIn)
        assertEquals(CheckInType.ARRIVED, (e as PlaceEvent.CheckIn).payload.type)
    }

    @Test
    fun `a sentinel of ours with an unusable body is Unparseable, not NotMine`() {
        // The distinction is the whole reason the case exists: a check-in that fails to
        // parse is an emergency alert that never arms, and an uncounted failure is one
        // nobody counts.
        assertEquals(
            PlaceEvent.Unparseable(ControlPrefix.CHECK_IN),
            PlaceRouter().route(ControlPrefix.CHECK_IN + "not json", t0),
        )
        assertEquals(
            PlaceEvent.Unparseable(ControlPrefix.LOCATION),
            PlaceRouter().route(ControlPrefix.LOCATION + """{"latitude":1.0}""", t0),
        )
    }

    @Test
    fun `another row's sentinel and plain prose are both NotMine`() {
        val r = PlaceRouter()
        assertEquals(PlaceEvent.NotMine, r.route("Meet me at the station", t0))
        assertEquals(PlaceEvent.NotMine, r.route("📬DELIVERY_RECEIPT📬abc", t0))
        assertEquals(PlaceEvent.NotMine, r.route("🔥REACTION🔥{}", t0))
        assertEquals(PlaceEvent.NotMine, r.route("we should add a 📍LOCATION📍 marker", t0))
    }

    @Test
    fun `a contact card is not routed here — it has no sentinel to route on`() {
        // It is media attachment bytes (MediaManager.swift:867-897), not a control string.
        val card = """{"publicKey":"abc","timestamp":$nowApple}"""
        assertEquals(PlaceEvent.NotMine, PlaceRouter().route(card, t0))
        assertEquals("abc", ContactCardPayload.decode(card)!!.publicKey)
    }

    // ------------------------------------------- the tracker is wired in

    @Test
    fun `routing folds a live share into the tracker and answers against the pinned expiry`() {
        val r = PlaceRouter()
        val fifteenMinApple = 776_000_900.0

        val first = r.route(locationWire(isLive = true, expiresApple = fifteenMinApple, sessionId = "s1"), t0)
        assertEquals(LiveState.LIVE, (first as PlaceEvent.Location).state)

        // A ratcheting Android ping claiming a later end must not extend it.
        val later = r.route(locationWire(isLive = true, expiresApple = 776_005_000.0, sessionId = "s1"), t0 + 600_000L)
        assertEquals(LiveState.LIVE, (later as PlaceEvent.Location).state)
        assertEquals(t0 + 900_000L, r.tracker().session("s1")!!.pinnedExpiryMs)

        val past = r.route(locationWire(isLive = true, expiresApple = 776_005_000.0, sessionId = "s1"), t0 + 900_000L)
        assertEquals(LiveState.EXPIRED, (past as PlaceEvent.Location).state)
    }

    // ------------------------------------------- rendering

    @Test
    fun `an expired or stopped share never renders as live`() {
        val r = PlaceRouter()
        val live = r.renderToText(
            locationWire(isLive = true, expiresApple = 776_000_900.0, sessionId = "a"), t0,
        )!!
        assertTrue(live.contains("Live location, 15m left"))

        val stopped = PlaceRouter().renderToText(
            locationWire(isLive = true, expiresApple = 776_000_900.0, sessionId = "b", isStopped = true), t0,
        )!!
        assertTrue(stopped.contains("ended"))

        val expired = PlaceRouter().renderToText(
            locationWire(isLive = true, expiresApple = 776_000_900.0, sessionId = "c"), t0 + 900_000L,
        )!!
        assertTrue(expired.contains("expired"))
    }

    @Test
    fun `nothing this row owns renders to text`() {
        val r = PlaceRouter()
        assertNull(r.renderToText("Meet me at the station", t0))
        assertNull(r.renderToText(ControlPrefix.CHECK_IN + "not json", t0))
    }
}
