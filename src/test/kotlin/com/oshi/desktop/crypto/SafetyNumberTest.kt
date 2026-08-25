package com.oshi.desktop.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-for-byte parity of the desktop safety number against `OSHI/SafetyNumber.swift`
 * (see [SafetyNumber]'s file header for why iOS, and for the two Android generators
 * that once disagreed with it).
 *
 * KNOWN-ANSWER VECTORS. The three vectors below (`aliceKey`/`bobKey`, the all-`/` pair,
 * and `"zzz"`/`"aaa"`) are copied VERBATIM — same keys, same expected digit strings —
 * from `OSHI-Android/app/src/test/java/com/oshi/messenger/SafetyNumberVectorTest.kt`,
 * which is Android's own test of `SafetyNumberCodec`, the object Android converged on
 * (commit `8d5f7b2`, "Android 1.6.3: iOS parity sweep") after it stopped disagreeing
 * with iOS. That file's header states those expected values were derived "from the
 * SWIFT source ... computed outside this codebase — not read back out of the Kotlin
 * under test" — i.e. an independent derivation from `OSHI/SafetyNumber.swift:21-39`,
 * not from any Kotlin implementation. Reusing them here means THIS file's assertions
 * were not authored by looking at [SafetyNumber]'s own output either: three independent
 * things (Swift source, an Android test author, and now this file) have to agree.
 * (Independently re-derived with a throwaway `hashlib.sha256` Python script during this
 * change, purely as a sanity check before trusting the reuse — not the source of these
 * numbers.)
 */
class SafetyNumberTest {

    // Sorted order: '/' (0x2F) < 'A' (0x41) < 'a' (0x61) in ASCII, so Kotlin's String
    // ordering and Swift's Comparable agree on every vector below.
    private val aliceKey = "dGVzdC1hbGljZS1rZXktMzJieXRlcy1iYXNlNjQ="
    private val bobKey = "dGVzdC1ib2ItcHVibGljLWtleS0zMmJ5dGVzLTE="

    @Test
    fun `known-answer vector matches Android's independently-derived Swift vector`() {
        assertEquals(
            "490187061099861224338996915126128920988382993901176521575591",
            SafetyNumber.fingerprint(aliceKey, bobKey),
        )
        assertEquals(
            "49018 70610 99861 22433 89969 15126 12892 09883 82993 90117 65215 75591",
            SafetyNumber.compute(aliceKey, bobKey),
        )
        assertEquals(
            "SAFETY:v1:${aliceKey}${bobKey}",
            SafetyNumber.qrPayload(aliceKey, bobKey),
        )
    }

    /**
     * The all-zero / all-`/` pair is the vector that catches a SIGNED-byte modulo: those
     * bytes are 0xFF-heavy, and `byte % 100` on a signed Kotlin `Byte` would emit a
     * negative remainder and desynchronise the whole 60-digit string. Same keys and
     * expected fingerprint as Android's "high bytes stay unsigned" test.
     */
    @Test
    fun `high bytes stay unsigned`() {
        val k1 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val k2 = "/////////////////////////////////////w="
        val fp = SafetyNumber.fingerprint(k1, k2)
        assertEquals("663299970154834468818702686968934008296244198766744727225413", fp)
        assertEquals(60, fp.length)
        assertTrue("every character must be a digit", fp.all { it.isDigit() })
    }

    /** Same keys and expected fingerprint as Android's sort-order test. */
    @Test
    fun `sort order puts the lexicographically smaller key first`() {
        assertEquals("aaazzz", SafetyNumber.combined("zzz", "aaa"))
        assertEquals(
            "438761365740092220640165584271500724656400668299550810631432",
            SafetyNumber.fingerprint("zzz", "aaa"),
        )
    }

    @Test
    fun `argument order does not change the result`() {
        // The property that makes a safety number usable at all: the initiator and the
        // responder must compute the SAME string without agreeing in advance on who
        // calls compute(a, b) versus compute(b, a). If this ever fails, every
        // conversation shows a false MITM warning to one side of it.
        assertEquals(
            SafetyNumber.fingerprint(aliceKey, bobKey),
            SafetyNumber.fingerprint(bobKey, aliceKey),
        )
        assertEquals(
            SafetyNumber.qrPayload(aliceKey, bobKey),
            SafetyNumber.qrPayload(bobKey, aliceKey),
        )
    }

    @Test
    fun `a one-bit change in either identity key changes the number`() {
        // Flip the low bit of the last UTF-8 byte of bobKey ('=' 0x3D -> 0x3C, '<').
        val bobKeyFlipped = bobKey.dropLast(1) + '<'
        assertNotEquals(
            SafetyNumber.fingerprint(aliceKey, bobKey),
            SafetyNumber.fingerprint(aliceKey, bobKeyFlipped),
        )

        // Same check on the other side of the pair, so a bug that only re-hashes one
        // argument (e.g. treating the "first" sorted string as fixed) can't hide.
        val aliceKeyFlipped = aliceKey.dropLast(1) + '<'
        assertNotEquals(
            SafetyNumber.fingerprint(aliceKey, bobKey),
            SafetyNumber.fingerprint(aliceKeyFlipped, bobKey),
        )
    }

    @Test
    fun `output is exactly 60 digits in 12 groups of 5, space-separated`() {
        val number = SafetyNumber.compute(aliceKey, bobKey)
        val groups = number.split(" ")

        assertEquals(12, groups.size)
        assertTrue("every group must be exactly 5 characters: $groups", groups.all { it.length == 5 })
        assertTrue(
            "every character must be a space or an ASCII digit: $number",
            number.all { it == ' ' || it.isDigit() },
        )
        assertEquals(SafetyNumber.DIGIT_COUNT, number.count { it.isDigit() })
        assertEquals(60, SafetyNumber.DIGIT_COUNT)
    }

    @Test
    fun `verify is byte equality of the scanned payload, order-independent`() {
        val payload = SafetyNumber.qrPayload(aliceKey, bobKey)
        assertTrue(SafetyNumber.verify(payload, aliceKey, bobKey))
        assertTrue("scanning is order-independent", SafetyNumber.verify(payload, bobKey, aliceKey))
        assertFalse(SafetyNumber.verify(payload, aliceKey, "someone-else"))
        // A payload carrying the fingerprint instead of the keys — the OLD Android
        // shape — must NOT verify: verify never re-derives, it only compares bytes.
        assertFalse(SafetyNumber.verify("$aliceKey:$bobKey:deadbeef", aliceKey, bobKey))
    }

    @Test
    fun `identical keys still produce a well-formed number`() {
        // Not a realistic case (nobody verifies a safety number with themselves), but
        // the sort-and-concatenate step must not throw or degenerate when keyA == keyB.
        val number = SafetyNumber.compute(aliceKey, aliceKey)
        assertEquals(12, number.split(" ").size)
    }
}
