package com.oshi.desktop.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __ONE_AVATAR_NOT_THREE_2026_11_07__
 *
 * The desktop used to derive an avatar hue from `h * 31 + c.code` at constant
 * saturation and value. Two consequences, both fixed by porting the phones'
 * palette, both pinned here:
 *
 *  * a different hash meant one person was a different colour on an iPhone and
 *    on this client. An avatar colour exists to be RECOGNISED; three platforms
 *    disagreeing is the same failure as everyone being blue, one step subtler;
 *  * constant S/V put the yellow-green-teal band between 2.85:1 and 3.77:1
 *    against a white monogram — under WCAG AA on a third of the palette.
 *
 * These vectors are the SAME ones `AvatarIdentityTest` uses on Android, which
 * in turn were taken from the Swift implementation. If any platform's hash
 * drifts, exactly one of the three test suites goes red and names the key.
 */
class AvatarParityTest {

    @Test
    fun `the slot matches iOS and Android for known keys`() {
        val vectors = mapOf(
            "anatole" to 7,
            "hugo" to 0,
            "" to 9,
            "lorachan!manif" to 13,
            "04a1b2c3d4e5f60718293a4b5c6d7e8f90" to 7,
        )
        for ((key, expected) in vectors) {
            assertEquals("FNV-1a slot for '$key' diverged from the phones", expected, OshiTheme.avatarSlot(key, 14))
        }
    }

    @Test
    fun `there are fourteen gradients, as on the phones`() {
        assertEquals(14, OshiTheme.avatarGradients.size)
    }

    /**
     * The gradients are copied values, so the only thing stopping them drifting
     * is reading the Swift source back. Compared numerically with a tolerance:
     * Compose `Color` stores each channel lossily, so comparing the formatted
     * text of the literals fails on a correct palette.
     */
    @Test
    fun `the gradients match the swift source`() {
        val swift = listOf(File("../OSHI/AvatarIdentity.swift"), File("../../OSHI/AvatarIdentity.swift"))
            .firstOrNull { it.exists() }
        // The public desktop repo has no iOS tree: skip there (same rule as MeshWireFormatTest).
        org.junit.Assume.assumeTrue("iOS tree not present on this machine", swift != null)
        val text = swift!!.readText()
        val open = text.indexOf("static let gradients")
        assertTrue("gradients missing from the Swift source", open >= 0)
        val block = text.substring(open, text.indexOf("\n    ]", open))
        val nums = Regex("""red: ([\d.]+), green: ([\d.]+), blue: ([\d.]+)""")
            .findAll(block)
            .map { Triple(it.groupValues[1].toFloat(), it.groupValues[2].toFloat(), it.groupValues[3].toFloat()) }
            .toList()
        assertEquals("expected 14 gradients of 2 colours in the Swift source", 28, nums.size)
        OshiTheme.avatarGradients.forEachIndexed { i, (start, end) ->
            for ((offset, c) in listOf(0 to start, 1 to end)) {
                val (r, g, b) = nums[i * 2 + offset]
                val drift = maxOf(
                    kotlin.math.abs(c.red - r),
                    kotlin.math.abs(c.green - g),
                    kotlin.math.abs(c.blue - b),
                )
                assertTrue("gradient $i colour $offset drifted from Swift by $drift", drift <= 0.01f)
            }
        }
    }

    /** White monograms must clear WCAG AA on every gradient — the reason for the port. */
    @Test
    fun `a white monogram is legible on every gradient`() {
        fun lum(r: Float, g: Float, b: Float): Double {
            fun ch(v: Float): Double {
                val d = v.toDouble()
                return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
        }
        OshiTheme.avatarGradients.forEachIndexed { i, (s, e) ->
            val mr = (s.red + e.red) / 2f; val mg = (s.green + e.green) / 2f; val mb = (s.blue + e.blue) / 2f
            val ratio = (1.0 + 0.05) / (lum(mr, mg, mb) + 0.05)
            assertTrue("gradient $i gives a white monogram only ${"%.2f".format(ratio)}:1", ratio >= 4.5)
        }
    }
}
