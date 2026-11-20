package com.oshi.desktop.ui

import androidx.compose.ui.graphics.Color
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __AVATAR_PALETTE_V2_2026_11_17__ (history: `__ONE_AVATAR_NOT_THREE_2026_11_07__`)
 *
 * The desktop once derived an avatar hue from its own `h * 31 + c.code` hash, so a
 * person was one colour on their iPhone and another here. The palette is now ONE spec
 * shared by all three platforms, `docs/avatar-palette.json`, and this suite reads it
 * back — the same file `AvatarIdentityTests.swift` (iOS) and `AvatarIdentityTest.kt`
 * (Android) read. If any platform's literals or slot rule drift, exactly that
 * platform's suite goes red and names the entry or identifier.
 */
class AvatarParityTest {

    private val spec: JSONObject by lazy {
        val file = listOf(File("../docs/avatar-palette.json"), File("../../docs/avatar-palette.json"))
            .firstOrNull { it.exists() }
        assertTrue("docs/avatar-palette.json not found from ${File(".").absolutePath}", file != null)
        JSONObject(file!!.readText())
    }

    private fun color(s: String): Color = Color(0xFF000000L or s.removePrefix("#").toLong(16))

    @Test
    fun `the slot matches the shared vectors`() {
        val v = spec.getJSONArray("vectors")
        assertTrue(v.length() >= 10)
        for (i in 0 until v.length()) {
            val o = v.getJSONObject(i)
            assertEquals("slot for '${o.getString("id")}' diverged from the phones",
                o.getInt("slot"), OshiTheme.avatarSlot(o.getString("id")))
        }
    }

    @Test
    fun `a group id is one slot in either case and a key is never case folded`() {
        val upper = java.util.UUID.randomUUID().toString().uppercase()
        assertEquals(OshiTheme.avatarSlot(upper), OshiTheme.avatarSlot(upper.lowercase()))
        val key = "BHq2Y7qkzL0m1yQe0kq9XW3sV1d6c7m3pPq9yZ4b8tE="
        assertEquals(key, OshiTheme.avatarKey(key))
    }

    @Test
    fun `the palette literals match the shared spec`() {
        val p = spec.getJSONArray("palette")
        assertEquals(16, OshiTheme.avatarEntries.size)
        assertEquals(p.length(), OshiTheme.avatarEntries.size)
        for (i in 0 until p.length()) {
            val e = p.getJSONObject(i)
            val mine = OshiTheme.avatarEntries[e.getInt("index")]
            assertEquals("entry $i start", color(e.getString("start")), mine.start)
            assertEquals("entry $i end", color(e.getString("end")), mine.end)
            assertEquals("entry $i ink", color(e.getString("ink")), mine.ink)
        }
        val h = spec.getJSONObject("highlight")
        assertEquals(h.getDouble("centerX"), OshiTheme.AVATAR_HIGHLIGHT_X.toDouble(), 1e-6)
        assertEquals(h.getDouble("centerY"), OshiTheme.AVATAR_HIGHLIGHT_Y.toDouble(), 1e-6)
        assertEquals(h.getDouble("radius"), OshiTheme.AVATAR_HIGHLIGHT_RADIUS.toDouble(), 1e-6)
        assertEquals(h.getDouble("alphaAtCenter"), OshiTheme.AVATAR_HIGHLIGHT_ALPHA.toDouble(), 1e-6)
    }

    /** Every monogram ink clears 3:1 (large text) on start, end and highlighted centre. */
    @Test
    fun `the monogram is legible on every gradient`() {
        fun ch(v: Double) = if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        fun rgb(s: String): DoubleArray {
            val h = s.removePrefix("#").toLong(16)
            return doubleArrayOf(((h shr 16) and 0xFF) / 255.0, ((h shr 8) and 0xFF) / 255.0, (h and 0xFF) / 255.0)
        }
        fun lum(c: DoubleArray) = 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])
        fun cr(a: DoubleArray, b: DoubleArray): Double {
            val la = lum(a); val lb = lum(b); return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }
        fun mix(a: DoubleArray, b: DoubleArray, t: Double) = DoubleArray(3) { a[it] + (b[it] - a[it]) * t }
        val h = spec.getJSONObject("highlight")
        val alpha = h.getDouble("alphaAtCenter") *
            maxOf(0.0, 1 - Math.hypot(0.5 - h.getDouble("centerX"), 0.5 - h.getDouble("centerY")) / h.getDouble("radius"))
        val min = spec.getJSONObject("contrast").getDouble("minimum")
        val p = spec.getJSONArray("palette")
        for (i in 0 until p.length()) {
            val e = p.getJSONObject(i)
            val s = rgb(e.getString("start")); val t = rgb(e.getString("end")); val ink = rgb(e.getString("ink"))
            val centre = mix(mix(s, t, 0.5), doubleArrayOf(1.0, 1.0, 1.0), alpha)
            for ((label, bg) in listOf("start" to s, "end" to t, "centre" to centre)) {
                val r = cr(ink, bg)
                assertTrue("entry $i ink is ${"%.2f".format(r)}:1 on its $label", r >= min)
            }
        }
    }
}
