package com.oshi.desktop.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_LORA_REGION_UI_2026_10_29__ What a person may be OFFERED as a region.
 *
 * The region picker reached the window on 2026-10-29. Until then `loraSetRegion` existed and
 * was reachable only from `/lora region` in the text console, so the pane's own refusal —
 * "The radio has no region set, so it will not transmit. Choose one and try again." — asked
 * for something the window had no control for.
 *
 * The one rule worth pinning is that [LoRaAdmin.Region.UNSET] is never offered. It is the
 * state a radio is rescued FROM: [LoRaAdmin.setConfigFrame] refuses to write it, so a picker
 * that listed it would hand a person a choice that silently does nothing — or, read the other
 * way, a choice that would silence a working node if the refusal were ever relaxed. Both the
 * pane and the console now read `selectable`, so this asserts it once.
 */
class LoRaRegionOfferTest {

    @Test
    fun `UNSET is never offered to a person`() {
        assertFalse(LoRaAdmin.Region.selectable.contains(LoRaAdmin.Region.UNSET))
    }

    @Test
    fun `selectable is every region except UNSET, and loses nothing else`() {
        assertEquals(LoRaAdmin.Region.entries.size - 1, LoRaAdmin.Region.selectable.size)
        assertEquals(
            LoRaAdmin.Region.entries.filter { it != LoRaAdmin.Region.UNSET },
            LoRaAdmin.Region.selectable,
        )
    }

    /**
     * The firmware numbering is the wire contract. A region offered under the wrong number
     * puts the radio on frequencies the owner did not choose, which is a legal problem and
     * not a cosmetic one, so the offer list is checked against the enum it claims to mirror.
     */
    @Test
    fun `every offered region carries a distinct firmware number and a label`() {
        val wires = LoRaAdmin.Region.selectable.map { it.wire }
        assertEquals(wires.size, wires.toSet().size)
        assertFalse(wires.contains(0L))                  // 0 is UNSET
        assertTrue(LoRaAdmin.Region.selectable.all { it.label.isNotBlank() })
    }

    /** Round-trip: anything offered can be read back from the wire number it was offered as. */
    @Test
    fun `an offered region survives the wire round trip`() {
        for (r in LoRaAdmin.Region.selectable) {
            assertEquals(r, LoRaAdmin.Region.fromWire(r.wire))
        }
    }

    /**
     * The picker's contract with the writer: everything it offers is writable, and the one
     * thing it withholds is the one thing the writer refuses. If these two ever disagreed,
     * a person would pick a region and get a success message for a frame that was never
     * built.
     */
    @Test
    fun `everything offered is writable and the thing withheld is not`() {
        for (r in LoRaAdmin.Region.selectable) {
            assertNotNull("${r.name} is offered but setConfigFrame refuses it",
                LoRaAdmin.setConfigFrame(r))
        }
        assertNull(LoRaAdmin.setConfigFrame(LoRaAdmin.Region.UNSET))
    }
}
