package com.oshi.desktop.place

import com.oshi.desktop.msg.ControlPrefix
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `📍LOCATION📍` against the SHIPPED bytes.
 *
 * Every literal below was lifted out of a shipped tree, not out of this client's encoder:
 * the ten-key set and the three hand-written wire strings come from Android's own
 * `app/src/test/java/com/oshi/messenger/LocationWireFormatTest.kt` (:32-35, :152, :170-171,
 * :181), the field optionality from iOS's custom `init(from:)`
 * (`OSHI/LocationSharingManager.swift:1008-1023`), and the epoch from its bare
 * `JSONEncoder` (`:871`). A test that round-trips this client's own encoder through its own
 * decoder proves the two agree with each other and nothing else — the round-trip case here
 * is the LAST one for that reason, not the first.
 */
class LocationWireFormatTest {

    /** Apple-epoch 776000000.0 = 2025-08-04T13:33:20Z. Android's own fixture value. */
    private val appleTs = 776_000_000.0
    private val unixMsForAppleTs = 1_754_307_200_000L

    private fun body(wire: String): JSONObject = JSONObject(ControlPrefix.strip(wire))

    // ---------------------------------------------------------------- prefix

    @Test
    fun `the prefix is the bare pushpin with no variation selector`() {
        assertEquals("📍LOCATION📍", ControlPrefix.LOCATION)
        assertFalse(
            "U+FE0F in the wire prefix would break every iOS decoder — Android asserts " +
                "the same absence at LocationWireFormatTest.kt:56-65",
            ControlPrefix.LOCATION.contains(ControlPrefix.VS16),
        )
    }

    @Test
    fun `a sentinel in the middle of prose is prose`() {
        assertNull(LocationPayload.decode("we should add a 📍LOCATION📍 marker"))
        assertNull(LocationPayload.decode("""{"latitude":1.0}"""))
        assertNull(LocationPayload.decode("hello"))
    }

    // ------------------------------------------------------------ encode side

    @Test
    fun `a one-time share emits exactly the ten iOS fields in Swift declaration order`() {
        // The set is Android's `iosFields` (LocationWireFormatTest.kt:32-35); the ORDER is
        // Swift's `CodingKeys` (LocationSharingManager.swift:1006).
        val wire = LocationPayload.encode(
            latitude = 48.85661,
            longitude = 2.35222,
            atUnixMillis = unixMsForAppleTs,
            isLive = true,
            expiresAtUnixMillis = unixMsForAppleTs + 900_000L,
            accuracy = 12.5,
            address = "Rue de Rivoli, Paris, France",
            sessionId = "8f3c1d2e-0000-4444-8888-abcdefabcdef",
        )
        assertEquals(
            ControlPrefix.LOCATION +
                """{"latitude":48.85661,"longitude":2.35222,"timestamp":776000000,""" +
                """"isLive":true,"expiresAt":776000900,"accuracy":12.5,""" +
                """"address":"Rue de Rivoli, Paris, France",""" +
                """"sessionId":"8f3c1d2e-0000-4444-8888-abcdefabcdef",""" +
                """"isUpdate":false,"isStopped":false}""",
            wire,
        )
    }

    @Test
    fun `absent optionals are omitted, never written as null`() {
        // iOS's synthesized encoder uses encodeIfPresent, so it EXPECTS an absent key.
        // Android's kotlinx writes explicit nulls and iOS tolerates them; we follow iOS.
        val json = body(
            LocationPayload.encode(
                latitude = 48.85661, longitude = 2.35222,
                atUnixMillis = unixMsForAppleTs, isLive = false,
            ),
        )
        assertEquals(
            setOf("latitude", "longitude", "timestamp", "isLive", "isUpdate", "isStopped"),
            json.keys().asSequence().toSet(),
        )
    }

    @Test
    fun `the update and stop flags are always emitted, never omitted as defaults`() {
        val json = body(
            LocationPayload.encode(
                latitude = 1.0, longitude = 2.0,
                atUnixMillis = unixMsForAppleTs, isLive = false,
            ),
        )
        assertTrue(json.has("isUpdate"))
        assertTrue(json.has("isStopped"))
    }

    @Test
    fun `timestamp is Apple-epoch seconds, not Unix seconds and not millis`() {
        val json = body(
            LocationPayload.encode(
                latitude = 1.0, longitude = 2.0,
                atUnixMillis = 1_717_200_000_000L, isLive = false,
            ),
        )
        // 1717200000 Unix seconds - 978307200 = 738892800 Apple seconds.
        assertEquals(738_892_800.0, json.getDouble("timestamp"), 0.0)
    }

    @Test
    fun `a whole-valued coordinate prints without an exponent`() {
        // Maven's org.json would print 7.731E8; Swift and Android's org.json would not.
        // See WireClock.jsonNumber.
        val wire = LocationPayload.encode(
            latitude = 0.0, longitude = -180.0,
            atUnixMillis = unixMsForAppleTs, isLive = false,
        )
        assertTrue(wire.contains("""{"latitude":0,"longitude":-180,"""))
    }

    // ------------------------------------------------------------ decode side

    @Test
    fun `decodes an iOS payload that omits every optional field`() {
        // Verbatim from Android's LocationWireFormatTest.kt:151-152 — what a Swift encoder
        // produces for a one-time share.
        val wire = ControlPrefix.LOCATION +
            """{"latitude":48.85661,"longitude":2.35222,"timestamp":776000000.5,"isLive":false}"""
        val p = LocationPayload.decode(wire)
        assertNotNull(p)
        assertEquals(48.85661, p!!.latitude, 1e-9)
        assertEquals(2.35222, p.longitude, 1e-9)
        assertEquals(776_000_000.5, p.appleTimestamp, 1e-9)
        assertEquals(1_754_307_200_500L, p.timestampMs)
        assertFalse(p.isLive)
        assertNull(p.appleExpiresAt)
        assertNull(p.accuracy)
        assertNull(p.address)
        assertNull(p.sessionId)
        assertFalse(p.isUpdate)
        assertFalse(p.isStopped)
    }

    @Test
    fun `decodes the explicit nulls Android emits`() {
        // Verbatim from Android's LocationWireFormatTest.kt:169-171.
        val wire = ControlPrefix.LOCATION +
            """{"latitude":1.0,"longitude":2.0,"timestamp":776000000.0,"isLive":true,""" +
            """"expiresAt":null,"accuracy":null,"address":null,"sessionId":null}"""
        val p = LocationPayload.decode(wire)
        assertNotNull(p)
        assertNull(p!!.appleExpiresAt)
        assertNull(p.sessionId)
    }

    @Test
    fun `unknown fields from a newer peer are ignored, not fatal`() {
        // Verbatim from Android's LocationWireFormatTest.kt:180-181.
        val wire = ControlPrefix.LOCATION +
            """{"latitude":1.0,"longitude":2.0,"timestamp":776000000.0,"isLive":false,"altitude":33.0}"""
        assertNotNull(LocationPayload.decode(wire))
    }

    @Test
    fun `any of the four fields iOS decodes non-optionally being absent drops the payload`() {
        // `try container.decode` for latitude/longitude/timestamp/isLive
        // (LocationSharingManager.swift:1013-1016) throws, JSONDecoder returns nil at :884.
        val full = """{"latitude":1.0,"longitude":2.0,"timestamp":776000000.0,"isLive":false}"""
        assertNotNull(LocationPayload.decode(ControlPrefix.LOCATION + full))
        for (missing in listOf("latitude", "longitude", "timestamp", "isLive")) {
            val o = JSONObject(full)
            o.remove(missing)
            assertNull(
                "removing $missing must drop the payload, as it does on iOS",
                LocationPayload.decode(ControlPrefix.LOCATION + o.toString()),
            )
        }
    }

    @Test
    fun `a legacy Android payload carrying Unix millis is still readable`() {
        // Android asserts the same passthrough at LocationWireFormatTest.kt:201-208.
        val wire = ControlPrefix.LOCATION +
            """{"latitude":1.0,"longitude":2.0,"timestamp":1717200000000,"isLive":false}"""
        val p = LocationPayload.decode(wire)
        assertNotNull(p)
        assertEquals(1_717_200_000_000L, p!!.timestampMs)
    }

    // ------------------------------------------------------- the coordinate guard

    @Test
    fun `a coordinate that is not on Earth is refused`() {
        // GUARD: LocationPayload.isPlausibleCoordinate. Neither shipped client checks; both
        // would draw a marker at latitude 400.
        for (bad in listOf("""400.0,2.0""", """1.0,200.0""", """-91.0,0.0""", """0.0,-181.0""")) {
            val (lat, lon) = bad.split(",")
            val wire = ControlPrefix.LOCATION +
                """{"latitude":$lat,"longitude":$lon,"timestamp":776000000.0,"isLive":false}"""
            assertNull("$bad must be refused", LocationPayload.decode(wire))
        }
    }

    @Test
    fun `zero zero stays valid because that is what a stop with no fix sends`() {
        // Both `stopped()` factories fall back to 0,0 (swift:993-1002, kt:623-637). A guard
        // that rejected it would drop exactly the message that ends a share.
        val wire = ControlPrefix.LOCATION +
            """{"latitude":0.0,"longitude":0.0,"timestamp":776000000.0,"isLive":true,""" +
            """"expiresAt":776000000.0,"sessionId":"s1","isUpdate":true,"isStopped":true}"""
        val p = LocationPayload.decode(wire)
        assertNotNull(p)
        assertTrue(p!!.isStopped)
        assertEquals(LiveState.STOPPED, p.liveState(unixMsForAppleTs))
    }

    // --------------------------------------------------- the silent-push predicate

    @Test
    fun `a stop signal is NOT silent — this client takes iOS's side`() {
        // iOS: isUpdate && !isStopped (swift:861, tombstone __LOCATION_PING_SPAM_2026_08_24__).
        // Android: isUpdate || isStopped (kt:680-681), i.e. iOS's PRE-fix behaviour, and its
        // own test asserts the old semantics (LocationWireFormatTest.kt:222).
        fun wire(isUpdate: Boolean, isStopped: Boolean) = ControlPrefix.LOCATION +
            """{"latitude":1.0,"longitude":2.0,"timestamp":776000000.0,"isLive":true,""" +
            """"expiresAt":776003600.0,"sessionId":"s1",""" +
            """"isUpdate":$isUpdate,"isStopped":$isStopped}"""

        assertFalse("the initial share must notify once", LocationPayload.isSilentPushContent(wire(false, false)))
        assertTrue("every ~10s ping must not raise a banner", LocationPayload.isSilentPushContent(wire(true, false)))
        assertFalse(
            "the stop signal must banner — iOS 2026-08-24 fix; Android still silences it",
            LocationPayload.isSilentPushContent(wire(true, true)),
        )
    }

    @Test
    fun `the silent predicate cannot be replaced by a prefix match`() {
        // Both the initial share and its pings carry the identical sentinel, which is why
        // 📍LOCATION📍 is absent from ControlPrefix.silentNoPushPrefixes.
        assertFalse(ControlPrefix.silentNoPushPrefixes.contains(ControlPrefix.LOCATION))
        assertFalse(ControlPrefix.suppressesPush(ControlPrefix.LOCATION + "{}"))
    }

    @Test
    fun `non-location content is never treated as a silent location ping`() {
        assertFalse(LocationPayload.isSilentPushContent("Meet me at the station"))
        assertFalse(LocationPayload.isSilentPushContent("📬DELIVERY_RECEIPT📬abc"))
    }

    // ------------------------------------------------------------- round trip, last

    @Test
    fun `round-trips its own wire format losslessly`() {
        val wire = LocationPayload.encode(
            latitude = -33.86785, longitude = 151.20732,
            atUnixMillis = unixMsForAppleTs, isLive = true,
            expiresAtUnixMillis = unixMsForAppleTs + 3_600_000L,
            accuracy = 8.0, address = "Sydney, Australia",
            sessionId = "8f3c1d2e-0000-4444-8888-abcdefabcdef",
            isUpdate = true,
        )
        val p = LocationPayload.decode(wire)!!
        assertEquals(-33.86785, p.latitude, 0.0)
        assertEquals(151.20732, p.longitude, 0.0)
        assertEquals(unixMsForAppleTs, p.timestampMs)
        assertEquals(unixMsForAppleTs + 3_600_000L, p.expiresAtMs)
        assertEquals(8.0, p.accuracy!!, 0.0)
        assertEquals("Sydney, Australia", p.address)
        assertTrue(p.isUpdate)
        assertFalse(p.isStopped)
        assertEquals(appleTs, p.appleTimestamp, 0.0)
    }
}
