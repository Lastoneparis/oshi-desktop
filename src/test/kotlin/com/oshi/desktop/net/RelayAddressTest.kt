package com.oshi.desktop.net

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/** __OHTTP_RELAY_ADDRESS_2026_11_10__ same rule as Android's RelayAddressTest. */
class RelayAddressTest {
    @Test fun acceptsHttpsRelayOfAnotherOperator() {
        assertEquals("https://relay.example.org/ohttp", DesktopOhttp.normalizeRelayUrl(" https://relay.example.org/ohttp "))
        assertEquals("https://relay.example.org/", DesktopOhttp.normalizeRelayUrl("https://relay.example.org"))
    }
    @Test fun refusesCleartext() { assertNull(DesktopOhttp.normalizeRelayUrl("http://relay.example.org/")) }
    @Test fun refusesOshiItself() {
        assertNull(DesktopOhttp.normalizeRelayUrl("https://oshi-messenger.com/ohttp/gateway"))
        assertNull(DesktopOhttp.normalizeRelayUrl("https://relay.oshi-messenger.com/"))
    }
    @Test fun refusesGarbage() {
        assertNull(DesktopOhttp.normalizeRelayUrl(""))
        assertNull(DesktopOhttp.normalizeRelayUrl("relay"))
        assertNull(DesktopOhttp.normalizeRelayUrl("https://localhost/"))
    }
}
