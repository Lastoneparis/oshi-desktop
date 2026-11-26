package com.oshi.desktop.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The split [FrameSampler] rests on: a long delta is the Compose clock RESTING, not a frame
 * that took that long. Mixing the two would let a still window report a terrible p95, or let
 * real jank hide inside an average dragged down by idleness.
 */
class FrameSamplerTest {

    private fun ms(vararg v: Double) = v.map { (it * 1e6).toLong() }

    @Test
    fun `idle gaps are counted apart from drawing frames`() {
        // Twenty 8 ms frames, then the window sits still for two seconds, then twenty more.
        val deltas = ms(*DoubleArray(20) { 8.3 }.toTypedArray().toDoubleArray()) +
            ms(2_000.0) +
            ms(*DoubleArray(20) { 8.3 }.toTypedArray().toDoubleArray())
        val s = FrameSampler.summarise(deltas)
        assertTrue("drawing frames counted, not the gap: $s", s.contains("n=40"))
        assertTrue("the gap is reported as idle, not as a frame: $s", s.contains("idleGaps=1"))
        assertTrue("a 2 s rest must not reach the percentiles: $s", s.contains("p95=8.30 ms"))
    }

    @Test
    fun `slow frames are reported as a share of the drawing ones`() {
        val deltas = ms(*(DoubleArray(96) { 8.0 }.toList() + List(4) { 20.0 }).toDoubleArray())
        val s = FrameSampler.summarise(deltas)
        assertTrue("four of a hundred over 16.7 ms: $s", s.contains("over16.7ms=4 (4.0%)"))
    }

    @Test
    fun `a window with nothing but rest says so rather than dividing by zero`() {
        val s = FrameSampler.summarise(ms(900.0, 1_500.0))
        assertTrue(s, s.contains("no drawing frames"))
        assertTrue(s, s.contains("2 idle gaps"))
    }
}
