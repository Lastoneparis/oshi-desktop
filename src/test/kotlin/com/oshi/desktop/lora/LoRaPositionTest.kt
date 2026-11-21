package com.oshi.desktop.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * POSITION_APP — where a node says it is, and the distance that follows from it.
 *
 * The numbers here are the ones observed on this mesh: a capture held 135 position packets
 * around 46.53°N 6.47°E, and NONE of them came from our own radios, which is what "indoors,
 * no fix" looks like on the wire. That is the case the null branches below are about.
 */
class LoRaPositionTest {

    /** `Position.latitude_i` / `longitude_i` are sfixed32 scaled by 1e-7. */
    private fun positionPayload(lat: Double, lon: Double): ByteArray {
        fun sfixed32(field: Int, value: Int): ByteArray {
            val tag = (field shl 3) or 5
            return byteArrayOf(
                tag.toByte(),
                (value and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 24) and 0xFF).toByte(),
            )
        }
        return sfixed32(1, Math.round(lat * 1e7).toInt()) + sfixed32(2, Math.round(lon * 1e7).toInt())
    }

    private fun rosterWith(vararg positions: Pair<UInt, Pair<Double, Double>>): LoRaNodeRoster {
        val r = LoRaNodeRoster()
        positions.forEach { (num, p) -> r.observePosition(num, positionPayload(p.first, p.second)) }
        return r
    }

    @Test
    fun `a position is read as sfixed32 scaled by 1e-7`() {
        val r = LoRaNodeRoster()
        r.observePosition(0x1234u, positionPayload(46.5300000, 6.4700000))
        r.observeFromRadio(byteArrayOf(), 0L)   // no-op; the roster still holds the position
        // Read it back through the public surface by heard-ing the node first.
        val seen = LoRaNodeRoster().also {
            it.observePosition(0x1234u, positionPayload(46.53, 6.47))
        }
        assertEquals(46.53, readLat(seen, 0x1234u)!!, 1e-6)
        assertEquals(6.47, readLon(seen, 0x1234u)!!, 1e-6)
    }

    /**
     * A node with NO FIX reports 0/0. Storing it would move a node that has a fix into the
     * Gulf of Guinea — iOS's words, and iOS's bug before it guarded this.
     */
    @Test
    fun `a zero-zero position is discarded, not stored`() {
        val r = LoRaNodeRoster()
        r.observePosition(0x1234u, positionPayload(46.53, 6.47))
        r.observePosition(0x1234u, positionPayload(0.0, 0.0))
        assertEquals("the real fix must survive a later no-fix report", 46.53, readLat(r, 0x1234u)!!, 1e-6)
    }

    @Test
    fun `a position off the planet is refused`() {
        val r = LoRaNodeRoster()
        r.observePosition(0x1234u, positionPayload(91.0, 6.47))
        assertNull(readLat(r, 0x1234u))
        r.observePosition(0x1235u, positionPayload(46.53, 181.0))
        assertNull(readLat(r, 0x1235u))
    }

    @Test
    fun `a truncated position payload is ignored rather than half-read`() {
        val r = LoRaNodeRoster()
        r.observePosition(0x1234u, byteArrayOf(0x0D, 1, 2))   // a latitude tag and too few bytes
        assertNull(readLat(r, 0x1234u))
    }

    // ---------------------------------------------------------------- distance

    /** Lausanne to Geneva, ~50 km. The figure a map agrees with, not one this code invented. */
    @Test
    fun `the great-circle distance matches a known pair of points`() {
        val km = LoRaNodeRoster.haversineKm(46.5197, 6.6323, 46.2044, 6.1432)
        assertEquals(50.0, km, 2.0)
    }

    @Test
    fun `a point is zero kilometres from itself`() {
        assertEquals(0.0, LoRaNodeRoster.haversineKm(46.53, 6.47, 46.53, 6.47), 1e-9)
    }

    @Test
    fun `distance is symmetric`() {
        assertEquals(
            LoRaNodeRoster.haversineKm(46.5197, 6.6323, 46.2044, 6.1432),
            LoRaNodeRoster.haversineKm(46.2044, 6.1432, 46.5197, 6.6323),
            1e-9,
        )
    }

    private fun readLat(r: LoRaNodeRoster, num: UInt): Double? = probe(r, num)?.first
    private fun readLon(r: LoRaNodeRoster, num: UInt): Double? = probe(r, num)?.second

    /** The roster exposes positions only through [LoRaNodeRoster.onlineNodes], so heard it first. */
    private fun probe(r: LoRaNodeRoster, num: UInt): Pair<Double, Double>? {
        val now = 1_761_600_000_000L
        r.observeFromRadio(packetFrom(num), now)
        val node = r.onlineNodes(now).firstOrNull { it.num == num } ?: return null
        val la = node.latitude ?: return null
        val lo = node.longitude ?: return null
        return la to lo
    }

    /** A bare `FromRadio{packet{from}}`, enough to make the roster count the node as heard. */
    private fun packetFrom(num: UInt): ByteArray {
        val from = byteArrayOf(
            0x0D,
            (num.toInt() and 0xFF).toByte(),
            ((num.toInt() ushr 8) and 0xFF).toByte(),
            ((num.toInt() ushr 16) and 0xFF).toByte(),
            ((num.toInt() ushr 24) and 0xFF).toByte(),
        )
        // FromRadio field 2 (packet), length-delimited.
        return byteArrayOf(0x12, from.size.toByte()) + from
    }
}
