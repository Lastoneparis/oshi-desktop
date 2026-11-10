package com.oshi.desktop.lora

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_NODE_REACH_2026_11_07__
 *
 * Third copy of the reach estimate, same vectors as `LoRaCoverageTest` on
 * Android and `LoRaCoverageTests.swift`, so all three say the same thing about
 * the same radio.
 *
 * The desktop has no map renderer, so there is no ring to draw here — the
 * estimate is stated in the node list instead. The numbers must still agree:
 * two people side by side, one on a phone and one on a laptop, must not read
 * different reaches for the same node.
 */
class LoRaCoverageTest {

    /** THE test. A relayed node's RSSI belongs to the relay, so nothing is claimed. */
    @Test
    fun `a relayed node gets no estimate`() {
        assertNull(LoRaCoverage.radiusMeters(-70, 1))
        assertNull(LoRaCoverage.radiusMeters(-70, 3))
        assertNull("and no words either", LoRaCoverage.reachLabel(-70, 1))
    }

    @Test
    fun `a directly heard node gets one`() {
        assertNotNull(LoRaCoverage.radiusMeters(-70, 0))
        assertNotNull(LoRaCoverage.radiusMeters(-70, null))
    }

    @Test
    fun `a node never heard gets none`() {
        assertNull(LoRaCoverage.radiusMeters(null, 0))
    }

    @Test
    fun `a signal below the usable floor gets none`() {
        assertNull(LoRaCoverage.radiusMeters(-140, 0))
    }

    /** A positive "RSSI" is not an RSSI. */
    @Test
    fun `a nonsense reading gets none`() {
        assertNull(LoRaCoverage.radiusMeters(10, 0))
    }

    /** Stronger signal, tighter reach — the inversion is the whole model. */
    @Test
    fun `a stronger signal means a smaller radius`() {
        val strong = LoRaCoverage.radiusMeters(-40, 0)!!
        val weak = LoRaCoverage.radiusMeters(-115, 0)!!
        assertTrue("strong $strong should be tighter than weak $weak", strong < weak)
        assertTrue(strong >= LoRaCoverage.MIN_RADIUS_M)
        assertTrue(weak <= LoRaCoverage.MAX_RADIUS_M)
    }

    /** The wording hedges, because the estimate does. */
    @Test
    fun `the label never states a boundary`() {
        val label = LoRaCoverage.reachLabel(-90, 0)!!
        assertTrue("got: $label", label.startsWith("reaches about "))
    }

    /**
     * The constants are copied values; the only thing stopping them drifting is
     * reading the other platform back.
     */
    @Test
    fun `the constants match the android source`() {
        val f = listOf(
            File("../OSHI-Android/app/src/main/java/com/oshi/messenger/service/lora/LoRaCoverage.kt"),
            File("../../OSHI-Android/app/src/main/java/com/oshi/messenger/service/lora/LoRaCoverage.kt"),
        ).firstOrNull { it.exists() }
        assertTrue("Android LoRaCoverage.kt not found from ${File(".").absolutePath}", f != null)
        val text = f!!.readText()
        for ((name, value) in listOf(
            "MIN_USABLE_RSSI" to LoRaCoverage.MIN_USABLE_RSSI.toString(),
            "MAX_RSSI" to LoRaCoverage.MAX_RSSI.toString(),
        )) {
            assertTrue("$name drifted from Android", text.contains("$name = $value"))
        }
        assertTrue("MIN_RADIUS_M drifted", text.contains("MIN_RADIUS_M = 150.0"))
        assertTrue("MAX_RADIUS_M drifted", text.contains("MAX_RADIUS_M = 5_000.0"))
        assertEquals(150.0, LoRaCoverage.MIN_RADIUS_M, 0.0)
        assertEquals(5_000.0, LoRaCoverage.MAX_RADIUS_M, 0.0)
    }
}
