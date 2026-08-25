package com.oshi.desktop.pairing

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * A QR code, as a matrix of modules — PARITY.md row 0.22, display half.
 *
 * ============================================================ WHY THIS IS HAND-WRITTEN
 *
 * PARITY.md working rule 4: no new dependency without a reason that survives being
 * written down. The obvious move is ZXing, which is what the Android app uses
 * (`QRCodeScreen.kt:28-29`). The reason not to:
 *
 *  - The desktop client's dependency list is `java.net` + the JDK + the two libraries the
 *    Android app pins, and that list is itself a parity guarantee — BouncyCastle is
 *    version-matched because "a different BouncyCastle is a parity risk"
 *    (`build.gradle.kts`). Adding a library for one screen dilutes the argument that the
 *    list is short on purpose.
 *  - What ZXing would buy is not shared behaviour. iOS does not use ZXing; it uses
 *    CoreImage's `CIQRCodeGenerator`. So there is no "same encoder as the phone" to be
 *    had — ISO/IEC 18004 is the only thing all three agree on, and that is a
 *    specification, not a library. Every conformant encoder produces a code every
 *    conformant decoder reads; that is the entire point of the standard.
 *  - The scope actually needed is small and closed: **byte mode, error-correction level
 *    M, versions 1–10**. That covers a 44-character identity key (which lands on version
 *    4 — see the arithmetic in [chooseVersion]) with a wide margin, and nothing in this
 *    row ever needs kanji mode, structured append, or version 40.
 *  - It is verifiable without the library too. The format-information and
 *    version-information bit strings are published tables in the standard, and a
 *    Reed–Solomon codeword is DEFINED by having α¹…α^n as roots — both are known answers
 *    that come from outside this file, which is what `QrMatrixTest` asserts against.
 *
 * The cost is about 300 lines of well-specified arithmetic. The structure follows the
 * conventional public-domain reference implementation of ISO/IEC 18004 closely enough
 * that anyone who knows that code can review this one.
 *
 * **Level M, not L.** iOS asks for `"M"` (`QRCodeDisplayView.swift:271`); Android takes
 * ZXing's default, which is L (`QRCodeScreen.kt:294`). Stricter side wins per PARITY.md
 * working rule 2: M restores ~15% of a damaged code against L's ~7%, which is the
 * difference between a phone reading a screen at an angle, behind a reflection, or with a
 * cursor sitting on top of it, and a phone that just sits there failing.
 *
 * ============================================================ COORDINATES
 *
 * [dark] is indexed `[row][col]`, origin top-left, exactly as it is drawn. [size] is
 * `4 × version + 17`. The QUIET ZONE — 4 modules of light on every side — is NOT part of
 * this matrix; it is added by the renderers, because it is a property of the presentation
 * and forgetting it is the single most common reason a technically-correct code will not
 * scan.
 */
class QrMatrix internal constructor(
    val version: Int,
    val mask: Int,
    private val modules: Array<BooleanArray>,
) {

    val size: Int get() = modules.size

    /** True when the module at [row], [col] is dark. Out-of-range reads as light. */
    fun dark(row: Int, col: Int): Boolean =
        if (row in modules.indices && col in modules.indices) modules[row][col] else false

    /**
     * Render as text, one module per two characters.
     *
     * Two characters wide because terminal cells are roughly twice as tall as they are
     * wide; one character per module produces a matrix squashed to half its width, which
     * a decoder will not accept. [quietZone] modules of light border are included.
     */
    fun toText(dark: String = "██", light: String = "  ", quietZone: Int = 4): String {
        val sb = StringBuilder()
        val span = size + 2 * quietZone
        repeat(quietZone) { sb.append(light.repeat(span)).append('\n') }
        for (r in 0 until size) {
            sb.append(light.repeat(quietZone))
            for (c in 0 until size) sb.append(if (dark(r, c)) dark else light)
            sb.append(light.repeat(quietZone)).append('\n')
        }
        repeat(quietZone) { sb.append(light.repeat(span)).append('\n') }
        return sb.toString()
    }

    /**
     * Render as a black-on-white PNG.
     *
     * `javax.imageio` is JDK — no dependency. [scale] is modules-to-pixels with no
     * interpolation, which is why iOS also disables smoothing on its own QR image
     * (`QRCodeDisplayView.swift:88`, `.interpolation(.none)`): a resampled QR is a blurred
     * QR, and a blurred QR is an unreadable one.
     */
    fun toPng(target: File, scale: Int = 8, quietZone: Int = 4): File {
        require(scale >= 1) { "scale must be >= 1" }
        val span = (size + 2 * quietZone) * scale
        val img = BufferedImage(span, span, BufferedImage.TYPE_INT_RGB)
        val white = 0xFFFFFF
        val black = 0x000000
        for (y in 0 until span) {
            for (x in 0 until span) {
                val r = y / scale - quietZone
                val c = x / scale - quietZone
                img.setRGB(x, y, if (dark(r, c)) black else white)
            }
        }
        target.parentFile?.mkdirs()
        ImageIO.write(img, "png", target)
        return target
    }

    companion object {

        /** Data codewords available at level M, indexed by version (index 0 unused). */
        private val ECC_PER_BLOCK_M = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS_M = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

        const val MIN_VERSION = 1
        const val MAX_VERSION = 10

        /** ISO/IEC 18004 level indicator bits. L=1, M=0, Q=3, H=2 — M is what we emit. */
        private const val ECC_BITS_M = 0

        private const val PAD_A = 0xEC
        private const val PAD_B = 0x11

        /**
         * Encode [text] as ISO 8859-1 / UTF-8 bytes in byte mode at level M.
         *
         * @throws IllegalArgumentException when the payload does not fit version
         *   [MAX_VERSION]. That is a deliberate ceiling, not an oversight: everything this
         *   row encodes is a 44-character identity key, and a caller that has silently
         *   grown to 200 characters has changed the contract and should find out.
         */
        fun encode(text: String): QrMatrix {
            val data = text.toByteArray(Charsets.UTF_8)
            val version = chooseVersion(data.size)
            val codewords = buildCodewords(data, version)
            val interleaved = addEccAndInterleave(codewords, version)

            val size = version * 4 + 17
            val modules = Array(size) { BooleanArray(size) }
            val isFunction = Array(size) { BooleanArray(size) }
            drawFunctionPatterns(version, modules, isFunction)
            drawCodewords(interleaved, modules, isFunction)

            // Every mask is drawn and scored; the lowest penalty wins. Choosing a fixed
            // mask "because it usually looks fine" is how a code acquires a run of eleven
            // identical modules that a decoder reads as a finder pattern.
            var bestMask = 0
            var bestPenalty = Int.MAX_VALUE
            for (m in 0..7) {
                applyMask(m, modules, isFunction)
                drawFormatBits(m, modules, isFunction)
                val p = penalty(modules)
                if (p < bestPenalty) {
                    bestPenalty = p
                    bestMask = m
                }
                applyMask(m, modules, isFunction)   // XOR is its own inverse — undo
            }
            applyMask(bestMask, modules, isFunction)
            drawFormatBits(bestMask, modules, isFunction)
            return QrMatrix(version, bestMask, modules)
        }

        /**
         * Smallest version whose level-M data capacity holds [byteCount] bytes.
         *
         * The header is 4 bits of mode indicator plus the character-count indicator,
         * which is 8 bits for versions 1–9 and 16 bits for version 10 and up — a detail
         * worth stating because it is what pushes a 44-byte key past version 3. Version
         * 3 at level M holds 44 data codewords = 352 bits; 4 + 8 + 44×8 = 364 bits does
         * not fit, so an identity key lands on version 4 (512 bits), never version 3.
         */
        fun chooseVersion(byteCount: Int): Int {
            for (v in MIN_VERSION..MAX_VERSION) {
                val headerBits = 4 + if (v <= 9) 8 else 16
                if (dataCodewords(v) * 8 >= headerBits + byteCount * 8) return v
            }
            throw IllegalArgumentException(
                "$byteCount bytes does not fit a version-$MAX_VERSION level-M QR code " +
                    "(max ${(dataCodewords(MAX_VERSION) * 8 - 20) / 8} bytes)",
            )
        }

        /** Total codewords the symbol carries, from its module count. */
        internal fun rawCodewords(version: Int): Int {
            var bits = (16 * version + 128) * version + 64
            if (version >= 2) {
                val numAlign = version / 7 + 2
                bits -= (25 * numAlign - 10) * numAlign - 55
                if (version >= 7) bits -= 36
            }
            return bits / 8
        }

        internal fun dataCodewords(version: Int): Int =
            rawCodewords(version) - ECC_PER_BLOCK_M[version] * BLOCKS_M[version]

        // ------------------------------------------------------------------ bitstream

        private fun buildCodewords(data: ByteArray, version: Int): ByteArray {
            val capacityBits = dataCodewords(version) * 8
            val bits = BitBuffer()
            bits.append(0b0100, 4)                                   // byte mode
            bits.append(data.size, if (version <= 9) 8 else 16)      // character count
            for (b in data) bits.append(b.toInt() and 0xFF, 8)

            // Terminator: up to four zero bits, then zero-fill to a byte boundary.
            bits.append(0, minOf(4, capacityBits - bits.length))
            bits.append(0, (8 - bits.length % 8) % 8)

            // Alternating 11101100 / 00010001 pad codewords. Not arbitrary: the spec
            // names these two bytes, and a decoder that trims them relies on them.
            var pad = PAD_A
            while (bits.length < capacityBits) {
                bits.append(pad, 8)
                pad = if (pad == PAD_A) PAD_B else PAD_A
            }
            return bits.toBytes()
        }

        // ------------------------------------------------------------------ error correction

        /**
         * Split into blocks, append Reed–Solomon parity to each, and interleave.
         *
         * Interleaving is what makes the error correction useful: a thumb over one corner
         * of the code damages a contiguous run of MODULES, which after de-interleaving is
         * a few symbols in each of several blocks rather than one block destroyed.
         */
        internal fun addEccAndInterleave(data: ByteArray, version: Int): ByteArray {
            val numBlocks = BLOCKS_M[version]
            val eccLen = ECC_PER_BLOCK_M[version]
            val raw = rawCodewords(version)
            require(data.size == dataCodewords(version)) { "wrong data length for version $version" }

            val numShort = numBlocks - raw % numBlocks
            val shortLen = raw / numBlocks
            val divisor = rsDivisor(eccLen)

            // Every block is materialised at the LONG length, with the short blocks
            // carrying a one-byte hole where their missing data codeword would be and
            // their ECC pushed to the end. The hole is what makes the interleave a
            // straight column walk: without it, "column i" would mean a data byte in one
            // block and an ECC byte in another, and the two would be emitted in the wrong
            // order. The hole itself is skipped on the way out, never emitted.
            val blockLen = shortLen + 1
            val holeIndex = shortLen - eccLen
            val blocks = ArrayList<ByteArray>(numBlocks)
            var k = 0
            for (i in 0 until numBlocks) {
                val dataLen = holeIndex + (if (i < numShort) 0 else 1)
                val dat = data.copyOfRange(k, k + dataLen)
                k += dataLen
                val block = ByteArray(blockLen)
                dat.copyInto(block)
                rsRemainder(dat, divisor).copyInto(block, blockLen - eccLen)
                blocks.add(block)
            }

            val out = ByteArray(raw)
            var o = 0
            for (i in 0 until blockLen) {
                for (j in blocks.indices) {
                    if (i != holeIndex || j >= numShort) out[o++] = blocks[j][i]
                }
            }
            check(o == raw) { "interleave produced $o codewords, expected $raw" }
            return out
        }

        /** Generator polynomial of degree [degree] over GF(256), coefficients descending. */
        internal fun rsDivisor(degree: Int): ByteArray {
            require(degree in 1..255)
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in result.indices) {
                    result[j] = gfMul(result[j].toInt() and 0xFF, root).toByte()
                    if (j + 1 < result.size) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
                root = gfMul(root, 2)
            }
            return result
        }

        internal fun rsRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) {
                    result[i] = (result[i].toInt() xor gfMul(divisor[i].toInt() and 0xFF, factor)).toByte()
                }
            }
            return result
        }

        /**
         * GF(2⁸) multiply, primitive polynomial x⁸+x⁴+x³+x²+1 = 0x11D.
         *
         * Russian-peasant rather than log/antilog tables: no table to get wrong, no
         * special case for a zero operand, and this runs once per codeword on a code the
         * size of a postage stamp.
         */
        internal fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z and 0xFF
        }

        // ------------------------------------------------------------------ drawing

        private fun drawFunctionPatterns(
            version: Int,
            m: Array<BooleanArray>,
            f: Array<BooleanArray>,
        ) {
            val size = m.size
            // Timing patterns: alternating dark/light along row 6 and column 6.
            for (i in 0 until size) {
                setFn(m, f, 6, i, i % 2 == 0)
                setFn(m, f, i, 6, i % 2 == 0)
            }
            // Finder patterns + their separators, at three corners. Three, not four:
            // the missing fourth is how a decoder recovers the code's rotation.
            drawFinder(m, f, 3, 3)
            drawFinder(m, f, 3, size - 4)
            drawFinder(m, f, size - 4, 3)

            val align = alignmentPositions(version)
            for (i in align.indices) {
                for (j in align.indices) {
                    val skipCorner = (i == 0 && j == 0) ||
                        (i == 0 && j == align.size - 1) ||
                        (i == align.size - 1 && j == 0)
                    if (!skipCorner) drawAlignment(m, f, align[i], align[j])
                }
            }

            // Format-info cells are reserved now and written later, once the mask is
            // chosen — they must not receive data codewords in between.
            drawFormatBits(0, m, f, reserveOnly = true)
            if (version >= 7) drawVersionBits(version, m, f)
        }

        private fun drawFinder(m: Array<BooleanArray>, f: Array<BooleanArray>, row: Int, col: Int) {
            for (dr in -4..4) for (dc in -4..4) {
                val dist = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
                val r = row + dr
                val c = col + dc
                if (r in m.indices && c in m.indices) setFn(m, f, r, c, dist != 2 && dist != 4)
            }
        }

        private fun drawAlignment(m: Array<BooleanArray>, f: Array<BooleanArray>, row: Int, col: Int) {
            for (dr in -2..2) for (dc in -2..2) {
                setFn(m, f, row + dr, col + dc, maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc)) != 1)
            }
        }

        /** Alignment-pattern centre coordinates for [version]; empty for version 1. */
        internal fun alignmentPositions(version: Int): IntArray {
            if (version == 1) return IntArray(0)
            val numAlign = version / 7 + 2
            val size = version * 4 + 17
            val step = (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var pos = size - 7
            for (i in numAlign - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        /**
         * The 15-bit format information, twice, plus the always-dark module.
         *
         * BCH(15,5) over the generator 0x537, then XOR 0x5412 so an all-zero format
         * (level M, mask 0) is not an all-light region. `QrMatrixTest` checks the result
         * against the published table in ISO/IEC 18004 Annex C — an answer from outside
         * this file, which is the only kind worth asserting.
         */
        internal fun drawFormatBits(
            mask: Int,
            m: Array<BooleanArray>,
            f: Array<BooleanArray>,
            reserveOnly: Boolean = false,
        ) {
            val bits = if (reserveOnly) 0 else formatBits(mask)
            val size = m.size
            fun put(row: Int, col: Int, i: Int) {
                if (reserveOnly) {
                    f[row][col] = true
                } else {
                    m[row][col] = ((bits ushr i) and 1) != 0
                }
            }
            for (i in 0..5) put(i, 8, i)
            put(7, 8, 6)
            put(8, 8, 7)
            put(8, 7, 8)
            for (i in 9..14) put(8, 14 - i, i)

            for (i in 0..7) put(8, size - 1 - i, i)
            for (i in 8..14) put(size - 15 + i, 8, i)

            // The dark module, always set, at (4·version + 9, 8).
            if (reserveOnly) f[size - 8][8] = true else m[size - 8][8] = true
        }

        /** The 15 format bits for level M and [mask], masked with 0x5412. */
        internal fun formatBits(mask: Int): Int {
            val data = (ECC_BITS_M shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            return ((data shl 10) or rem) xor 0x5412
        }

        /** The 18 version bits for [version] (versions 7 and up only). */
        internal fun versionBits(version: Int): Int {
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            return (version shl 12) or rem
        }

        private fun drawVersionBits(version: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            val bits = versionBits(version)
            val size = m.size
            for (i in 0 until 18) {
                val bit = ((bits ushr i) and 1) != 0
                val a = size - 11 + i % 3
                val b = i / 3
                setFn(m, f, a, b, bit)
                setFn(m, f, b, a, bit)
            }
        }

        private fun setFn(m: Array<BooleanArray>, f: Array<BooleanArray>, row: Int, col: Int, v: Boolean) {
            if (row !in m.indices || col !in m.indices) return
            m[row][col] = v
            f[row][col] = true
        }

        /**
         * Zig-zag the codewords into the non-function modules: two columns at a time,
         * right to left, alternating upward and downward, skipping the vertical timing
         * column.
         */
        private fun drawCodewords(data: ByteArray, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            val size = m.size
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5      // column 6 is the timing pattern
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val col = right - j
                        val upward = ((right + 1) and 2) == 0
                        val row = if (upward) size - 1 - vert else vert
                        if (!f[row][col] && i < data.size * 8) {
                            m[row][col] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                            i++
                        }
                        // Remaining modules stay light — those are the remainder bits,
                        // which the spec leaves as zero.
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            val size = m.size
            for (row in 0 until size) for (col in 0 until size) {
                if (f[row][col]) continue
                val invert = when (mask) {
                    0 -> (row + col) % 2 == 0
                    1 -> row % 2 == 0
                    2 -> col % 3 == 0
                    3 -> (row + col) % 3 == 0
                    4 -> (col / 3 + row / 2) % 2 == 0
                    5 -> col * row % 2 + col * row % 3 == 0
                    6 -> (col * row % 2 + col * row % 3) % 2 == 0
                    7 -> ((col + row) % 2 + col * row % 3) % 2 == 0
                    else -> throw IllegalArgumentException("mask $mask")
                }
                if (invert) m[row][col] = !m[row][col]
            }
        }

        /**
         * The four penalty rules of ISO/IEC 18004 §8.8.2. Lower is better.
         *
         * Worth being clear about what this does and does not affect: the penalty score
         * only decides WHICH mask is used. Every one of the eight masks produces a code
         * that decodes, so an imperfect score costs legibility at the margins, never
         * correctness. N3 is implemented as a literal search for the two eleven-module
         * sequences the standard names, rather than as a run-length ratio test — same
         * rule, fewer ways to get it subtly wrong.
         */
        internal fun penalty(m: Array<BooleanArray>): Int {
            val size = m.size
            var score = 0

            // N3's two patterns: the finder's 1:1:3:1:1 signature with four light modules
            // on one side. A decoder hunting for finder patterns must not find one here.
            val n3a = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
            val n3b = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)

            for (fixed in 0 until size) {
                for (horizontal in booleanArrayOf(true, false)) {
                    fun at(i: Int): Boolean = if (horizontal) m[fixed][i] else m[i][fixed]

                    // N1: any run of five or more identical modules.
                    var runLen = 1
                    for (i in 1 until size) {
                        if (at(i) == at(i - 1)) {
                            runLen++
                        } else {
                            if (runLen >= 5) score += 3 + (runLen - 5)
                            runLen = 1
                        }
                    }
                    if (runLen >= 5) score += 3 + (runLen - 5)

                    // N3.
                    for (i in 0..size - 11) {
                        var matchA = true
                        var matchB = true
                        for (j in 0 until 11) {
                            val cell = at(i + j)
                            if (cell != n3a[j]) matchA = false
                            if (cell != n3b[j]) matchB = false
                            if (!matchA && !matchB) break
                        }
                        if (matchA) score += 40
                        if (matchB) score += 40
                    }
                }
            }

            // N2: 2×2 blocks of one colour.
            for (r in 0 until size - 1) for (c in 0 until size - 1) {
                val v = m[r][c]
                if (v == m[r][c + 1] && v == m[r + 1][c] && v == m[r + 1][c + 1]) score += 3
            }

            // N4: the smallest k >= 0 such that the dark ratio is within (50 ± 5(k+1))%.
            var darkCount = 0
            for (row in m) for (cell in row) if (cell) darkCount++
            val total = size * size
            val k = ((kotlin.math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1).coerceAtLeast(0)
            score += k * 10
            return score
        }
    }

    /** Big-endian bit accumulator. */
    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val length: Int get() = bits.size

        fun append(value: Int, count: Int) {
            require(count in 0..31)
            for (i in count - 1 downTo 0) bits.add(((value ushr i) and 1) != 0)
        }

        fun toBytes(): ByteArray {
            require(bits.size % 8 == 0) { "bit buffer is not byte-aligned" }
            val out = ByteArray(bits.size / 8)
            for (i in bits.indices) if (bits[i]) {
                out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
            return out
        }
    }
}
