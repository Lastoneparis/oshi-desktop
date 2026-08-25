package com.oshi.desktop.place

import com.oshi.desktop.msg.ControlPrefix
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `🛡️CHECK_IN🛡️` against the SHIPPED bytes.
 *
 * The two fixture instants and the Apple-epoch expectations are Android's own
 * (`app/src/test/java/com/oshi/messenger/CheckInWireFormatTest.kt:23-26, :46`); the field
 * optionality and key order are iOS's struct (`OSHI/CheckInMessage.swift:212-221`,
 * `EmergencyData` at `:87-95`).
 *
 * This is the row where a wrong epoch does not cost a bubble — it costs the arrival
 * deadline that arms an emergency alert. Android's own comment
 * (`CheckInMessage.kt:338-347`) is quoted in the source file.
 */
class CheckInWireFormatTest {

    /** 2024-06-01T00:00:00Z. Android's `etaUnixMillis`. */
    private val etaMs = 1_717_200_000_000L

    /** One hour earlier. Android's `nowUnixMillis`. */
    private val nowMs = 1_717_196_400_000L

    private val etaApple = 738_892_800.0
    private val nowApple = 738_889_200.0

    private fun body(wire: String): JSONObject = JSONObject(ControlPrefix.strip(wire))

    // ---------------------------------------------------------------- prefix

    @Test
    fun `the prefix carries the variation selector on both platforms`() {
        assertEquals("🛡️CHECK_IN🛡️", ControlPrefix.CHECK_IN)
        assertTrue(ControlPrefix.CHECK_IN.contains(ControlPrefix.VS16))
    }

    @Test
    fun `the bare-shield spelling an older build emits still decodes`() {
        // ControlPrefix derives the U+FE0F-stripped twin; nothing here lists it twice.
        val stripped = ControlPrefix.CHECK_IN.replace(ControlPrefix.VS16, "")
        val wire = stripped + """{"type":"started","sessionId":"s1","timestamp":$nowApple}"""
        val p = CheckInPayload.decode(wire)
        assertNotNull(p)
        assertEquals(CheckInType.STARTED, p!!.type)
    }

    @Test
    fun `the underscore-less CHECKIN alias is accepted on receive and never emitted`() {
        // Both catalogs list 🛡️CHECKIN🛡️ (OSHIControlPayload.swift:99) and iOS's group
        // renderer accepts it (GroupViews.swift:2568), but NO emitter on either platform
        // produces it: iOS's only messagePrefix is the underscored one (CheckInMessage.swift:223).
        val wire = ControlPrefix.CHECKIN + """{"type":"arrived","sessionId":"s1","timestamp":$nowApple}"""
        assertNotNull(CheckInPayload.decode(wire))
        assertTrue(
            CheckInPayload.encode(CheckInType.ARRIVED, "s1", nowMs)
                .startsWith(ControlPrefix.CHECK_IN),
        )
    }

    // ------------------------------------------------------------ encode side

    @Test
    fun `the wire emits Apple-epoch seconds, not Unix millis, in Swift declaration order`() {
        val wire = CheckInPayload.encode(
            type = CheckInType.STARTED,
            sessionId = "s1",
            atUnixMillis = nowMs,
            destinationAddress = "Home",
            transportMode = TransportMode.WALKING,
            estimatedArrivalUnixMillis = etaMs,
        )
        assertEquals(
            ControlPrefix.CHECK_IN +
                """{"type":"started","sessionId":"s1","destinationAddress":"Home",""" +
                """"transportMode":"walking","estimatedArrival":738892800,"timestamp":738889200}""",
            wire,
        )
        val json = body(wire)
        // Android's own bound: Apple seconds today ≈ 7.7e8, Unix millis ≈ 1.7e12.
        assertTrue(json.getDouble("estimatedArrival") < 1e11)
        assertTrue(json.getDouble("timestamp") < 1e11)
        assertEquals(etaApple, json.getDouble("estimatedArrival"), 0.001)
    }

    @Test
    fun `absent optionals are omitted, never written as null`() {
        val json = body(CheckInPayload.encode(CheckInType.CANCELLED, "s1", nowMs))
        assertEquals(setOf("type", "sessionId", "timestamp"), json.keys().asSequence().toSet())
    }

    @Test
    fun `nested emergency timestamps cross the wire in Apple seconds too`() {
        val wire = CheckInPayload.encode(
            type = CheckInType.EMERGENCY,
            sessionId = "e1",
            atUnixMillis = nowMs,
            emergencyData = EmergencyData(
                lastKnownLatitude = 46.2,
                lastKnownLongitude = 6.1,
                lastLocationTimeMs = nowMs,
                batteryLevel = 0.42,
                isCharging = false,
                networkType = "Offline",
                deviceName = "Pixel",
                lastMovementTimeMs = nowMs,
            ),
        )
        // Asserted on the RAW BYTES, not through JSONObject. Maven's `org.json` backs
        // JSONObject with a HashMap, so reading the keys back scrambles them — this
        // assertion failed on its first run for exactly that reason, which is a live
        // demonstration of why ControlJson writes its own ordered output instead of
        // calling JSONObject.toString() (see that file's WHY NOT JSONObject.toString()).
        assertTrue(
            wire,
            wire.contains(
                """"emergencyData":{"lastKnownLatitude":46.2,"lastKnownLongitude":6.1,""" +
                    """"lastLocationTime":738889200,"batteryLevel":0.42,"isCharging":false,""" +
                    """"networkType":"Offline","deviceName":"Pixel","lastMovementTime":738889200}""",
            ),
        )
        val emo = body(wire).getJSONObject("emergencyData")
        assertEquals(
            setOf(
                "lastKnownLatitude", "lastKnownLongitude", "lastLocationTime", "batteryLevel",
                "isCharging", "networkType", "deviceName", "lastMovementTime",
            ),
            emo.keys().asSequence().toSet(),
        )
        assertTrue("nested lastLocationTime must be Apple seconds", emo.getDouble("lastLocationTime") < 1e11)
        assertEquals(nowApple, emo.getDouble("lastMovementTime"), 0.001)
        // A FRACTION, not a percentage — swift:101-103, kt:117-118.
        assertEquals(0.42, emo.getDouble("batteryLevel"), 0.0)
    }

    // ------------------------------------------------------------ decode side

    @Test
    fun `decodes an iOS-style Apple-epoch payload into Unix millis`() {
        // Android builds the same fixture at CheckInWireFormatTest.kt:66-74.
        val wire = ControlPrefix.CHECK_IN +
            """{"type":"started","sessionId":"ios1","estimatedArrival":$etaApple,"timestamp":$nowApple}"""
        val p = CheckInPayload.decode(wire)
        assertNotNull(p)
        assertEquals(etaMs, p!!.estimatedArrivalMs)
        assertEquals(nowMs, p.timestampMs)
        assertEquals(CheckInType.STARTED, p.type)
    }

    @Test
    fun `tolerates a legacy Android payload that still carries Unix millis`() {
        // Android asserts the same at CheckInWireFormatTest.kt:81-95.
        val wire = ControlPrefix.CHECK_IN +
            """{"type":"started","sessionId":"legacy1","estimatedArrival":$etaMs,"timestamp":$nowMs}"""
        val p = CheckInPayload.decode(wire)
        assertNotNull(p)
        assertEquals(etaMs, p!!.estimatedArrivalMs)
    }

    @Test
    fun `all seven types round-trip through their exact wire spellings`() {
        // iOS `CheckInMessageType` (swift:75-83) and Android (kt:59-70) agree case for case.
        val spellings = listOf(
            "started", "update", "arrived", "extended", "cancelled", "warning", "emergency",
        )
        assertEquals(spellings, CheckInType.entries.map { it.wire })
        for (s in spellings) {
            val wire = ControlPrefix.CHECK_IN + """{"type":"$s","sessionId":"s","timestamp":$nowApple}"""
            assertEquals(s, CheckInPayload.decode(wire)!!.type.wire)
        }
        assertEquals(listOf("walking", "driving", "transit"), TransportMode.entries.map { it.wire })
    }

    @Test
    fun `the three fields iOS decodes non-optionally are required here too`() {
        val full = """{"type":"started","sessionId":"s1","timestamp":$nowApple}"""
        assertNotNull(CheckInPayload.decode(ControlPrefix.CHECK_IN + full))
        for (missing in listOf("type", "sessionId", "timestamp")) {
            val o = JSONObject(full)
            o.remove(missing)
            assertNull(
                "removing $missing must drop the check-in, as it does on iOS",
                CheckInPayload.decode(ControlPrefix.CHECK_IN + o.toString()),
            )
        }
    }

    @Test
    fun `an unknown type is dropped rather than guessed`() {
        // Swift decodes the enum non-optionally, so an unrecognised case throws and the
        // whole message is discarded. Guessing a safety message's type is worse.
        val wire = ControlPrefix.CHECK_IN +
            """{"type":"panic","sessionId":"s1","timestamp":$nowApple}"""
        assertNull(CheckInPayload.decode(wire))
    }

    @Test
    fun `a partial emergencyData takes the whole check-in down, as it does on iOS`() {
        // Seven of EmergencyData's eight fields are non-optional on iOS (swift:88-94), so a
        // partial one throws inside the parent decode. Delivering an emergency with an
        // empty body would be worse than delivering none.
        val wire = ControlPrefix.CHECK_IN +
            """{"type":"emergency","sessionId":"e1","emergencyData":""" +
            """{"lastKnownLatitude":46.2,"lastKnownLongitude":6.1},"timestamp":$nowApple}"""
        assertNull(CheckInPayload.decode(wire))
    }

    @Test
    fun `an overdue arrival deadline is reported against an explicit clock`() {
        val p = CheckInPayload.decode(
            ControlPrefix.CHECK_IN +
                """{"type":"started","sessionId":"s1","estimatedArrival":$etaApple,"timestamp":$nowApple}""",
        )!!
        assertEquals(false, p.isOverdue(etaMs - 1))
        assertEquals(true, p.isOverdue(etaMs))
        assertTrue(p.renderToText(etaMs).contains("overdue"))
        // No deadline ⇒ never overdue, rather than "overdue since 1970".
        val noEta = CheckInPayload.decode(
            ControlPrefix.CHECK_IN + """{"type":"update","sessionId":"s1","timestamp":$nowApple}""",
        )!!
        assertEquals(false, noEta.isOverdue(etaMs + 86_400_000L))
    }

    @Test
    fun `round-trips its own wire format losslessly`() {
        val original = EmergencyData(
            lastKnownLatitude = 46.2, lastKnownLongitude = 6.1,
            lastLocationTimeMs = nowMs, batteryLevel = 0.42, isCharging = true,
            networkType = "WiFi", deviceName = "Pixel", lastMovementTimeMs = nowMs,
        )
        val wire = CheckInPayload.encode(
            type = CheckInType.EMERGENCY, sessionId = "e1", atUnixMillis = nowMs,
            destinationAddress = "Home", estimatedArrivalUnixMillis = etaMs,
            currentLatitude = 46.2, currentLongitude = 6.1,
            emergencyData = original, senderName = "Alice",
        )
        val p = CheckInPayload.decode(wire)!!
        assertEquals(CheckInType.EMERGENCY, p.type)
        assertEquals("e1", p.sessionId)
        assertEquals("Home", p.destinationAddress)
        assertEquals(etaMs, p.estimatedArrivalMs)
        assertEquals(nowMs, p.timestampMs)
        assertEquals("Alice", p.senderName)
        assertEquals(original, p.emergencyData)
    }
}
