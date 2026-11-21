package com.oshi.desktop.ui.state

import com.oshi.desktop.place.LiveState
import com.oshi.desktop.place.LocationPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_1_6_PARITY__ "Ping a place": what the window SENDS must be what both phones
 * decode, and what they send must decode here. The phone payloads below are the shapes their
 * encoders produce (iOS `LocationMessage.toMessageString`, a plain JSONEncoder with
 * Apple-epoch dates; Android `LocationMessage.toMessageString`, Gson with defaults encoded).
 */
class LocationPingTest {

    @Test fun `the ping the window sends is a one-shot location the phones render`() {
        val wire = LocationPayload.encode(48.8566, 2.3522, atUnixMillis = 1_763_480_000_000L, isLive = false, address = "Hôtel de Ville")
        assertTrue(wire.startsWith("📍LOCATION📍{"))
        val o = JSONObject(wire.removePrefix("📍LOCATION📍"))
        assertEquals(48.8566, o.getDouble("latitude"), 1e-9)
        assertEquals(2.3522, o.getDouble("longitude"), 1e-9)
        assertFalse(o.getBoolean("isLive"))
        assertFalse(o.getBoolean("isUpdate"))
        assertFalse(o.getBoolean("isStopped"))
        assertFalse("a ping has no session (iOS LocationPingPickerView.send)", o.has("sessionId"))
        assertFalse("a ping never expires", o.has("expiresAt"))
        // Apple-epoch seconds, as both phones' decoders expect: Unix − 978 307 200.
        assertEquals(1_763_480_000.0 - 978_307_200.0, o.getDouble("timestamp"), 1e-3)
        assertEquals("Hôtel de Ville", o.getString("address"))
    }

    @Test fun `an iOS one-time share decodes`() {
        val ios = "📍LOCATION📍{\"latitude\":46.519653,\"longitude\":6.632273,\"timestamp\":785172800.123,\"isLive\":false,\"accuracy\":12.5,\"isUpdate\":false,\"isStopped\":false}"
        val p = LocationPayload.decode(ios)
        assertNotNull(p)
        assertEquals(LiveState.NOT_LIVE, p!!.liveState(System.currentTimeMillis()))
        assertNull(p.address)
    }

    @Test fun `an Android live start decodes as live until its expiry`() {
        // Gson writes every field, nulls omitted; times in Apple-epoch seconds.
        val startApple = 785_172_800.0
        val android = "📍LOCATION📍{\"latitude\":45.764,\"longitude\":4.8357,\"timestamp\":$startApple,\"isLive\":true," +
            "\"expiresAt\":${startApple + 900},\"address\":\"Lyon\",\"sessionId\":\"9f1c2d3e-aaaa-bbbb-cccc-1234567890ab\",\"isUpdate\":false,\"isStopped\":false}"
        val p = LocationPayload.decode(android)!!
        val startMs = ((startApple + 978_307_200.0) * 1000).toLong()
        assertEquals(LiveState.LIVE, p.liveState(startMs + 60_000))
        assertEquals(LiveState.EXPIRED, p.liveState(startMs + 901_000))
        assertFalse("a start is a row", p.isSilentForPush)
    }

    @Test fun `a live position ping is silent, so it is not a new row`() {
        val ping = "📍LOCATION📍{\"latitude\":45.7641,\"longitude\":4.8358,\"timestamp\":785172810,\"isLive\":true,\"expiresAt\":785173700,\"sessionId\":\"s\",\"isUpdate\":true,\"isStopped\":false}"
        assertTrue(LocationPayload.decode(ping)!!.isSilentForPush)
    }

    @Test fun `coordinates are read from what people paste`() {
        assertEquals(48.8566 to 2.3522, Coordinates.parse("48.8566, 2.3522"))
        assertEquals(48.8566 to 2.3522, Coordinates.parse("48.8566 2.3522"))
        assertEquals(-33.8688 to 151.2093, Coordinates.parse("(-33.8688; 151.2093)"))
        assertEquals(48.8566 to 2.3522, Coordinates.parse("geo:48.8566,2.3522?z=16"))
        assertEquals(46.5197 to 6.6323, Coordinates.parse("https://www.openstreetmap.org/?mlat=46.5197&mlon=6.6323#map=16/46.5197/6.6323"))
        assertEquals(46.5197 to 6.6323, Coordinates.parse("https://www.openstreetmap.org/#map=16/46.5197/6.6323"))
        assertEquals(40.6892 to -74.0445, Coordinates.parse("https://www.google.com/maps/place/Statue/@40.6892,-74.0445,17z"))
        assertEquals(37.3349 to -122.009, Coordinates.parse("https://maps.apple.com/?ll=37.3349,-122.009&q=Apple"))
    }

    @Test fun `what is not a point on Earth is refused, and nothing is looked up`() {
        assertNull(Coordinates.parse(""))
        assertNull(Coordinates.parse("Rue de Rivoli, Paris"))
        assertNull(Coordinates.parse("95.0, 10.0"))
        assertNull(Coordinates.parse("10.0, 190.0"))
    }

    @Test fun `the map link opens OpenStreetMap at the point`() {
        assertEquals("https://www.openstreetmap.org/?mlat=48.856600&mlon=2.352200#map=16/48.856600/2.352200", Coordinates.osmUrl(48.8566, 2.3522))
        assertEquals("48.85660, 2.35220", Coordinates.format(48.8566, 2.3522))
    }
}
