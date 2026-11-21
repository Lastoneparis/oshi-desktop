package com.oshi.desktop.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __BUBBLE_MEDIA_2026_11_21__ BUBBLE_MEDIA_SPEC §1 sizing, held headlessly. */
class BubbleMediaLayoutTest {
    private val eps = 0.01f

    @Test fun `a large landscape fills the bubble and keeps its aspect`() {
        val s = BubbleMediaLayout.mediaSize(1600, 1000, 420f)
        assertEquals(420f, s.width, eps)
        assertEquals(262.5f, s.height, eps)
    }

    @Test fun `a small image is scaled UP to the minimum width, never leaving a gap`() {
        val s = BubbleMediaLayout.mediaSize(160, 120, 420f)
        assertEquals(BubbleMediaLayout.MIN_WIDTH, s.width, eps)
        assertEquals(150f, s.height, eps)
    }

    @Test fun `a medium image keeps its natural width`() {
        val s = BubbleMediaLayout.mediaSize(300, 300, 420f)
        assertEquals(300f, s.width, eps)
        assertEquals(300f, s.height, eps)
    }

    @Test fun `a tall portrait is clamped to 1_3 x width and centre-cropped`() {
        val s = BubbleMediaLayout.mediaSize(1000, 1600, 420f)
        assertEquals(420f, s.width, eps)
        assertEquals(420f * 1.3f, s.height, eps)
        assertTrue(BubbleMediaLayout.isCropped(1000, 1600))
        assertFalse(BubbleMediaLayout.isCropped(1000, 1200))
    }

    @Test fun `a panorama is clamped to 0_5 x width`() {
        val s = BubbleMediaLayout.mediaSize(2400, 600, 420f)
        assertEquals(210f, s.height, eps)
        assertTrue(BubbleMediaLayout.isCropped(2400, 600))
    }

    @Test fun `an unknown size reserves the full width at 4 to 3 so the row does not jump much`() {
        for ((w, h) in listOf(null to null, 0 to 100, 100 to -1)) {
            val s = BubbleMediaLayout.mediaSize(w, h, 400f)
            assertEquals(400f, s.width, eps)
            assertEquals(300f, s.height, eps)
        }
    }

    @Test fun `the minimum never exceeds a narrower bubble`() {
        val s = BubbleMediaLayout.mediaSize(50, 50, 150f)
        assertEquals(150f, s.width, eps)
    }

    @Test fun `bubble caps - 75 percent up to 420 in chats, 90 percent up to 560 in channels`() {
        assertEquals(420f, BubbleMediaLayout.maxBubbleWidth(1200f, channel = false), eps)
        assertEquals(300f, BubbleMediaLayout.maxBubbleWidth(400f, channel = false), eps)
        assertEquals(560f, BubbleMediaLayout.maxBubbleWidth(1200f, channel = true), eps)
        assertEquals(360f, BubbleMediaLayout.maxBubbleWidth(400f, channel = true), eps)
        // Narrow: at least the media minimum, never wider than the thread.
        assertEquals(200f, BubbleMediaLayout.maxBubbleWidth(240f, channel = false), eps)
        assertEquals(120f, BubbleMediaLayout.maxBubbleWidth(120f, channel = false), eps)
    }

    @Test fun `a channel picture fills the card width whatever its natural size`() {
        val small = BubbleMediaLayout.cardMediaSize(160, 120, 560f)
        assertEquals(560f, small.width, eps)
        assertEquals(420f, small.height, eps)
        val tall = BubbleMediaLayout.cardMediaSize(1000, 1600, 560f)
        assertEquals(560f * 1.3f, tall.height, eps)
    }

    @Test fun `the media width IS the bubble width - nothing narrower than the cap is left beside it`() {
        // The defect: a 250 dp picture in a bubble a caption had widened to 420 dp.
        for ((w, h) in listOf(1600 to 1000, 1000 to 1600, 160 to 120, 2400 to 600, 1200 to 900)) {
            val s = BubbleMediaLayout.mediaSize(w, h, 420f)
            assertTrue("$w x $h → ${s.width}", s.width >= BubbleMediaLayout.MIN_WIDTH && s.width <= 420f)
            if (w >= 420) assertEquals(420f, s.width, eps)
        }
    }
}
