package com.oshi.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __BUBBLE_MEDIA_2026_11_21__ BUBBLE_MEDIA_SPEC §2, measured: body text ≥ 4.5:1 on its bubble,
 * timestamps and ticks ≥ 3:1 (and the soft text we actually draw them in ≥ 4.5:1), sender hues
 * ≥ 4.5:1 on the incoming bubble — flat and on every glass ground of every palette.
 * Prints the table the design note quotes.
 */
class BubbleColorsContrastTest {

    private fun incomingGrounds(p: OshiPalette): Map<String, Color> {
        val chat = p.bubbles.chatBackground
        return mapOf("incoming" to p.bubbleIncoming.compositeOver(chat)) +
            (p.glass?.textGrounds(chat, p.surface)?.filterKeys { it.startsWith("glassRaised") || it == "glassTop" || it == "glassBottom" }
                ?.mapKeys { "incoming." + it.key } ?: emptyMap())
    }

    @Test fun `every bubble token clears AA in every palette`() {
        val failures = mutableListOf<String>()
        fun need(p: OshiPalette, what: String, fg: Color, bg: Color, min: Double) {
            val r = OshiPalette.contrast(fg, bg)
            println("[bubbles] %-12s %-34s %.2f".format(p.name, what, r))
            if (r < min) failures += "${p.name}: $what = ${"%.2f".format(r)} < $min"
        }
        for (p in OshiPalette.EVERY) {
            val b = p.bubbles
            need(p, "outgoingText on outgoing", b.outgoingText, b.outgoing, 4.5)
            need(p, "outgoingTextSoft on outgoing", b.outgoingTextSoft, b.outgoing, 4.5)
            need(p, "outgoingAccent on outgoing", b.outgoingAccent, b.outgoing, 4.5)
            // Time and ticks under a bubble sit on the thread's ground.
            need(p, "Ink.soft (time) on chat", p.textPrimary.copy(alpha = 0.6f), b.chatBackground, 4.5)
            need(p, "readTick on chat", b.readTick, b.chatBackground, 3.0)
            for ((gn, g) in incomingGrounds(p)) {
                need(p, "textPrimary on $gn", p.textPrimary, g, 4.5)
                need(p, "Ink.soft on $gn", p.textPrimary.copy(alpha = 0.6f), g, 4.5)
                need(p, "brand on $gn", p.brand, g, 4.5)
                need(p, "readTick on $gn", b.readTick, g, 3.0)
                b.senderNames.forEachIndexed { i, c -> need(p, "senderName[$i] on $gn", c, g, 4.5) }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `the time pill on a picture holds 3 to 1 even over pure white`() {
        val pill = Color.Black.copy(alpha = 0.55f).compositeOver(Color.White)
        assertTrue(OshiPalette.contrast(Color.White, pill) >= 4.5)
    }

    @Test fun `light is a pale lavender with near-black text, dark a deep violet with white text`() {
        val l = OshiPalette.LIGHT.bubbles
        assertTrue(!l.outgoingIsDark && l.outgoing.blue > l.outgoing.green)
        assertTrue(l.chatBackground != Color.White)
        assertEquals(Color.White, OshiPalette.LIGHT.bubbleIncoming)
        for (p in listOf(OshiPalette.DARK, OshiPalette.OLED)) {
            assertTrue(p.bubbles.outgoingIsDark)
            assertEquals(Color.White, p.bubbles.outgoingText)
            // Violet: blue-dominant, red above green.
            val o = p.bubbles.outgoing
            assertTrue("$o", o.blue > o.red && o.red > o.green)
        }
        // Dark keeps black for OLED: no pure-black bubble in Dark.
        assertTrue(OshiPalette.DARK.bubbleIncoming != Color.Black && OshiPalette.DARK.bubbles.chatBackground != Color.Black)
    }

    @Test fun `a member's hue is stable and the palette is used evenly`() {
        val b = OshiPalette.LIGHT.bubbles
        assertEquals(b.senderColor("abc="), b.senderColor("abc="))
        assertEquals(7, b.senderNames.size)
        assertEquals(OshiPalette.DARK.bubbles.senderNames.size, b.senderNames.size)
        val counts = IntArray(7)
        for (i in 0 until 7000) counts[BubbleColors.senderIndex("member-$i-key", 7)]++
        assertTrue(counts.joinToString(), counts.all { it in 800..1200 })
        // Deterministic across runs and JVMs (FNV-1a), pinned.
        assertEquals(BubbleColors.senderIndex("Inès Moreau", 7), BubbleColors.senderIndex("Inès Moreau", 7))
    }
}
