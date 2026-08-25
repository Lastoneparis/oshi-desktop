package com.oshi.desktop.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.imageio.ImageIO

/**
 * [QrMatrix] against ISO/IEC 18004, not against itself.
 *
 * The shipped trees give nothing to diff here — iOS uses CoreImage and Android uses
 * ZXing, so there is no OSHI byte shape to extract. What there IS, and what every
 * assertion below uses, are the standard's own published tables and defining properties:
 * the format-information and version-information bit strings, the capacity table, the
 * alignment-pattern coordinates, and the fact that a Reed–Solomon codeword is DEFINED as
 * a polynomial with α¹…α^n among its roots. All of those are answers from outside this
 * project. None of them is produced by the code under test.
 */
class QrMatrixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A real 32-byte identity key: iOS's own preview literal, `QRCodeDisplayView.swift:334`. */
    private val IOS_PREVIEW_KEY = "kr/6aAArDrl91TOXf/bjv2u1SiRf+H8gIi8BBgPYpi0="

    // ────────────────────────────────────────────────────────── published tables

    /**
     * ISO/IEC 18004 Annex C, format information for error-correction level M.
     *
     * Not computed here — copied from the table, MSB first. `formatBits` returns the same
     * 15 bits with bit 0 as the LSB, so the string is read right-to-left against it.
     */
    private val FORMAT_BITS_M = arrayOf(
        "101010000010010",  // mask 0
        "101000100100101",  // mask 1
        "101111001111100",  // mask 2
        "101101101001011",  // mask 3
        "100010111111001",  // mask 4
        "100000011001110",  // mask 5
        "100111110010111",  // mask 6
        "100101010100000",  // mask 7
    )

    @Test
    fun `format information matches the published table for every mask`() {
        for (mask in 0..7) {
            val expected = FORMAT_BITS_M[mask]
            val actual = QrMatrix.formatBits(mask).toString(2).padStart(15, '0')
            assertEquals("level M, mask $mask", expected, actual)
        }
    }

    /** ISO/IEC 18004 Annex D, version information for the versions that carry it. */
    private val VERSION_BITS = mapOf(
        7 to "000111110010010100",
        8 to "001000010110111100",
        9 to "001001101010011001",
        10 to "001010010011010011",
    )

    @Test
    fun `version information matches the published table`() {
        for ((version, expected) in VERSION_BITS) {
            val actual = QrMatrix.versionBits(version).toString(2).padStart(18, '0')
            assertEquals("version $version", expected, actual)
        }
    }

    /** ISO/IEC 18004 Table 1 / Table 9: total codewords, and data codewords at level M. */
    @Test
    fun `capacity table matches the standard`() {
        val totalCodewords = intArrayOf(26, 44, 70, 100, 134, 172, 196, 242, 292, 346)
        val dataCodewordsM = intArrayOf(16, 28, 44, 64, 86, 108, 124, 154, 182, 216)
        for (v in 1..10) {
            assertEquals("total codewords, version $v", totalCodewords[v - 1], QrMatrix.rawCodewords(v))
            assertEquals("data codewords at M, version $v", dataCodewordsM[v - 1], QrMatrix.dataCodewords(v))
        }
    }

    /** ISO/IEC 18004 Annex E: alignment-pattern centre coordinates. */
    @Test
    fun `alignment pattern coordinates match the standard`() {
        val expected = mapOf(
            1 to intArrayOf(),
            2 to intArrayOf(6, 18),
            3 to intArrayOf(6, 22),
            4 to intArrayOf(6, 26),
            5 to intArrayOf(6, 30),
            6 to intArrayOf(6, 34),
            7 to intArrayOf(6, 22, 38),
            8 to intArrayOf(6, 24, 42),
            9 to intArrayOf(6, 26, 46),
            10 to intArrayOf(6, 28, 50),
        )
        for ((v, coords) in expected) {
            assertArrayEquals("version $v", coords, QrMatrix.alignmentPositions(v))
        }
    }

    // ────────────────────────────────────────────────────────── Reed–Solomon

    /**
     * The worked example in ISO/IEC 18004 Annex I: version 1, level M, the numeric string
     * "01234567". Its sixteen data codewords and the ten error-correction codewords they
     * produce are printed in the standard.
     *
     * This is a KNOWN ANSWER in the strict sense — the inputs and the outputs both come
     * from the document, and the only thing supplied by this project is the arithmetic in
     * between.
     */
    @Test
    fun `reed solomon reproduces the annex I worked example`() {
        val data = intArrayOf(
            0x10, 0x20, 0x0C, 0x56, 0x61, 0x80, 0xEC, 0x11,
            0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11,
        ).map { it.toByte() }.toByteArray()
        val expected = intArrayOf(0xA5, 0x24, 0xD4, 0xC1, 0xED, 0x36, 0xC7, 0x87, 0x2C, 0x55)
            .map { it.toByte() }.toByteArray()

        val ecc = QrMatrix.rsRemainder(data, QrMatrix.rsDivisor(10))
        assertArrayEquals(expected, ecc)
    }

    /**
     * The DEFINING property, checked on data this test made up rather than on a table.
     *
     * A Reed–Solomon codeword is the polynomial `data‖ecc` and it is a codeword precisely
     * because it is divisible by the generator — equivalently, because α¹ … α^n are all
     * roots of it. Evaluating it at those points must give zero. This catches a class of
     * error the Annex I vector cannot: a generator polynomial that happens to be right at
     * degree 10 and wrong at degree 18 or 26, which are the degrees an identity key
     * actually uses.
     */
    @Test
    fun `every codeword polynomial vanishes at the generator roots`() {
        for (eccLen in intArrayOf(10, 16, 18, 22, 24, 26)) {
            val data = ByteArray(40) { ((it * 37 + 11) and 0xFF).toByte() }
            val codeword = data + QrMatrix.rsRemainder(data, QrMatrix.rsDivisor(eccLen))

            // The generator's roots are α⁰ … α^(n-1): ISO/IEC 18004 Annex A builds it as
            // ∏(x − 2ⁱ) starting from i = 0, not from i = 1. Evaluating one power too far
            // is a real failure — this test caught it first time round, reporting a
            // non-zero at α¹⁰ while α⁰…α⁹ were clean, which is what a correct codeword
            // and a wrong root set look like together.
            var root = 1                       // α⁰
            for (i in 0 until eccLen) {
                // Horner over GF(256): addition is XOR.
                var acc = 0
                for (b in codeword) acc = QrMatrix.gfMul(acc, root) xor (b.toInt() and 0xFF)
                assertEquals("ecc=$eccLen: codeword must vanish at alpha^$i", 0, acc)
                root = QrMatrix.gfMul(root, 2)
            }
        }
    }

    /** GF(2⁸) with the primitive polynomial 0x11D, checked against the field's own axioms. */
    @Test
    fun `galois field multiply obeys the field axioms`() {
        assertEquals(0, QrMatrix.gfMul(0, 0xAB))
        assertEquals(0, QrMatrix.gfMul(0xAB, 0))
        assertEquals(0xAB, QrMatrix.gfMul(1, 0xAB))

        // α⁸ reduces to the primitive polynomial's low byte: 0x11D & 0xFF = 0x1D.
        var alpha = 1
        repeat(8) { alpha = QrMatrix.gfMul(alpha, 2) }
        assertEquals(0x1D, alpha)

        // α²⁵⁵ = 1: the multiplicative group has order 255.
        var a = 1
        repeat(255) { a = QrMatrix.gfMul(a, 2) }
        assertEquals(1, a)

        for (x in 1..255) {
            for (y in 1..255 step 17) {
                assertEquals("commutative", QrMatrix.gfMul(x, y), QrMatrix.gfMul(y, x))
            }
        }
        // Every non-zero element has an inverse — no product of non-zeroes is ever zero.
        for (x in 1..255) for (y in 1..255 step 13) {
            assertTrue("no zero divisors", QrMatrix.gfMul(x, y) != 0)
        }
    }

    // ────────────────────────────────────────────────────────── structure

    /**
     * A 44-character identity key lands on version 4, never version 3.
     *
     * Version 3 at level M holds 44 data codewords = 352 bits, and the payload needs
     * 4 (mode) + 8 (count) + 352 = 364. Off-by-one here would produce an encoder that
     * silently truncates the last byte of every key.
     */
    @Test
    fun `an identity key needs version four`() {
        assertEquals(44, IOS_PREVIEW_KEY.length)
        assertEquals(4, QrMatrix.chooseVersion(IOS_PREVIEW_KEY.length))
        assertEquals("42 bytes is the largest that still fits version 3", 3, QrMatrix.chooseVersion(42))
        assertEquals("43 bytes already needs version 4", 4, QrMatrix.chooseVersion(43))
        assertEquals(1, QrMatrix.chooseVersion(1))
    }

    @Test
    fun `finder patterns timing and the dark module are where the standard puts them`() {
        val qr = QrMatrix.encode(IOS_PREVIEW_KEY)
        assertEquals(4, qr.version)
        assertEquals(4 * 4 + 17, qr.size)

        // Three finder patterns, each a 7×7 ring: dark border, light ring, 3×3 dark core.
        for ((r0, c0) in listOf(0 to 0, 0 to qr.size - 7, qr.size - 7 to 0)) {
            for (dr in 0..6) for (dc in 0..6) {
                val ring = maxOf(
                    kotlin.math.abs(dr - 3),
                    kotlin.math.abs(dc - 3),
                )
                assertEquals(
                    "finder at ($r0,$c0) module ($dr,$dc)",
                    ring != 2,
                    qr.dark(r0 + dr, c0 + dc),
                )
            }
        }

        // Timing patterns: row 6 and column 6 alternate, dark at even coordinates.
        for (i in 8 until qr.size - 8) {
            assertEquals("timing row at $i", i % 2 == 0, qr.dark(6, i))
            assertEquals("timing column at $i", i % 2 == 0, qr.dark(i, 6))
        }

        // The always-dark module, at (4·version + 9, 8).
        assertTrue(qr.dark(4 * qr.version + 9, 8))

        // EXACTLY three corners carry a finder. The missing fourth is what tells a
        // decoder which way up the code is; a fourth one would make it ambiguous.
        fun isFinder(r0: Int, c0: Int): Boolean {
            for (dr in 0..6) for (dc in 0..6) {
                val ring = maxOf(kotlin.math.abs(dr - 3), kotlin.math.abs(dc - 3))
                if (qr.dark(r0 + dr, c0 + dc) != (ring != 2)) return false
            }
            return true
        }
        val corners = listOf(
            0 to 0, 0 to qr.size - 7, qr.size - 7 to 0, qr.size - 7 to qr.size - 7,
        )
        assertEquals(3, corners.count { isFinder(it.first, it.second) })
        assertFalse("bottom-right must not be a finder", isFinder(qr.size - 7, qr.size - 7))
    }

    /**
     * The interleave must emit every codeword exactly once, including across blocks of
     * two different lengths.
     *
     * Version 8 at level M is the smallest version in range with MIXED block sizes — two
     * blocks of 38 data codewords and two of 39 — so it is the one where the padding hole
     * in the short blocks either works or silently duplicates a byte.
     */
    @Test
    fun `interleave emits every codeword once across uneven blocks`() {
        val version = 8
        val data = ByteArray(QrMatrix.dataCodewords(version)) { (it and 0xFF).toByte() }
        val out = QrMatrix.addEccAndInterleave(data, version)

        assertEquals(QrMatrix.rawCodewords(version), out.size)

        // The block structure is ISO/IEC 18004 Table 9, not something this code decided:
        // version 8 level M is (2 × 38) + (2 × 39) data codewords with 22 ECC each.
        val dataLens = intArrayOf(38, 38, 39, 39)
        val starts = intArrayOf(0, 38, 76, 115)
        assertEquals(data.size, dataLens.sum())

        // The data region is emitted column by column across the blocks; a block that has
        // run out of data codewords is simply absent from the last column. Built here
        // from the table above, so it is an expectation about the STANDARD rather than a
        // restatement of the implementation.
        val expectedDataRegion = ArrayList<Byte>(data.size)
        for (i in 0 until dataLens.max()) {
            for (j in dataLens.indices) {
                if (i < dataLens[j]) expectedDataRegion.add(data[starts[j] + i])
            }
        }
        assertEquals(data.size, expectedDataRegion.size)
        assertArrayEquals(
            "data region of the interleave",
            expectedDataRegion.toByteArray(),
            out.copyOfRange(0, data.size),
        )

        // …and what follows is the ECC region: 4 blocks × 22 codewords, nothing else.
        assertEquals(4 * 22, out.size - data.size)
    }

    @Test
    fun `text render carries a quiet zone on all four sides`() {
        val qr = QrMatrix.encode(IOS_PREVIEW_KEY)
        val lines = qr.toText(dark = "#", light = ".", quietZone = 4).trimEnd('\n').split('\n')
        assertEquals(qr.size + 8, lines.size)
        for (i in 0 until 4) {
            assertTrue("top quiet row $i", lines[i].all { it == '.' })
            assertTrue("bottom quiet row $i", lines[lines.size - 1 - i].all { it == '.' })
        }
        for (line in lines) {
            assertEquals(qr.size + 8, line.length)
            assertTrue("left quiet zone", line.take(4).all { it == '.' })
            assertTrue("right quiet zone", line.takeLast(4).all { it == '.' })
        }
        // A code with no dark modules is not a code.
        assertTrue(lines.any { it.contains('#') })
    }

    @Test
    fun `png pixels agree with the matrix`() {
        val qr = QrMatrix.encode(IOS_PREVIEW_KEY)
        val file = qr.toPng(tmp.newFile("key.png"), scale = 3, quietZone = 4)
        val img = ImageIO.read(file)
        assertEquals((qr.size + 8) * 3, img.width)
        assertEquals((qr.size + 8) * 3, img.height)

        // Corner of the quiet zone is white; the top-left finder's corner is black.
        assertEquals(0xFFFFFF, img.getRGB(1, 1) and 0xFFFFFF)
        assertEquals(0x000000, img.getRGB(4 * 3 + 1, 4 * 3 + 1) and 0xFFFFFF)

        for (r in 0 until qr.size) for (c in 0 until qr.size) {
            val px = img.getRGB((c + 4) * 3 + 1, (r + 4) * 3 + 1) and 0xFFFFFF
            assertEquals("module ($r,$c)", if (qr.dark(r, c)) 0x000000 else 0xFFFFFF, px)
        }
    }

    @Test
    fun `every payload length in range encodes and reports a legal version`() {
        for (len in 1..200) {
            val qr = QrMatrix.encode("A".repeat(len))
            assertTrue("version ${qr.version} out of range", qr.version in 1..10)
            assertEquals(4 * qr.version + 17, qr.size)
            assertTrue("mask ${qr.mask}", qr.mask in 0..7)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a payload past version ten is refused rather than truncated`() {
        QrMatrix.encode("A".repeat(300))
    }
}
