package com.oshi.desktop.ui.state

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * __CALL_QUALITY_TIERS_2026_10_28__ The SAME table of inputs on iOS and here, reduced to
 * ONE digest.
 *
 * Why this and not a pair of tests with matching names: `golden-file-beats-shared-test-names`
 * is a lesson this repository has already paid for — two cores with identically named tests
 * produced "Donateur 310" on Android and "253" on iOS, and nothing was red. A test name is
 * a claim about intent; it says nothing about the value that came out.
 *
 * `CallQualityTest` above already pins each branch against the figures read off
 * `VoiceCallManager.swift:2151-2173`. That is a transcription, and a transcription is
 * exactly the kind of thing that drifts the next time one side is edited. So the table
 * below is the contract: each row is formatted with its inputs AND the tier they produced,
 * the rows are joined, and the SHA-256 of the whole thing is compared to one constant that
 * `OSHITests/CallQualityParityTests.swift` holds too.
 *
 *  - a threshold that moves on one platform only ⇒ the digests part, one test goes red;
 *  - an INPUT edited on one platform only ⇒ the same, because the inputs are hashed too;
 *  - a threshold that moves on purpose ⇒ both go red together and the constant is updated
 *    once, deliberately, in two files in the same commit.
 *
 * `%.3f` under `Locale.ROOT` is not decoration: Kotlin's `String.format` follows the
 * default locale, so on a French machine the loss figures would carry commas and this would
 * disagree with iOS for a reason that has nothing to do with call quality.
 */
class CallQualityParityTest {

    /** Kept identical, row for row, to `CallQualityParityTests.vectors` on iOS. */
    private data class V(val latencyMs: Int, val loss: Double, val jitterMs: Int)

    private fun vectors(): List<V> = listOf(
        // nothing measured — the all-zero case, which the classifier calls EXCELLENT on
        // both platforms and which only the BADGE refuses to show
        V(0, 0.0, 0),
        // the five tiers, comfortably inside each band
        V(40, 0.5, 20),
        V(150, 3.0, 20),
        V(250, 7.0, 20),
        V(350, 15.0, 20),
        V(40, 25.0, 20),
        // every latency bound, from just inside to just outside
        V(99, 0.0, 0), V(100, 0.0, 0),
        V(199, 0.0, 0), V(200, 0.0, 0),
        V(299, 0.0, 0), V(300, 0.0, 0),
        V(399, 0.0, 0), V(400, 0.0, 0), V(401, 0.0, 0), V(5_000, 0.0, 0),
        // every loss bound
        V(10, 1.999, 0), V(10, 2.0, 0),
        V(10, 4.999, 0), V(10, 5.0, 0),
        V(10, 9.999, 0), V(10, 10.0, 0),
        V(10, 19.999, 0), V(10, 20.0, 0), V(10, 100.0, 0),
        // the jitter asymmetry: it may deny EXCELLENT and must never push below GOOD
        V(10, 0.0, 149), V(10, 0.0, 150), V(10, 0.0, 10_000),
        V(10, 25.0, 10_000),   // jitter irrelevant once loss alone decides
        // a negative latency should never be believed as a fast one
        V(-5, 0.0, 0),
    )

    private fun transcript(): String = vectors().joinToString("\n") { v ->
        val q = CallQualityStats(
            latencyMs = v.latencyMs, lossPercent = v.loss, jitterMs = v.jitterMs,
        ).quality
        "%d|%s|%d=>%s".format(
            java.util.Locale.ROOT,
            v.latencyMs, String.format(java.util.Locale.ROOT, "%.3f", v.loss), v.jitterMs, q.name,
        )
    }

    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `the tier digest matches the one iOS computes`() {
        val t = transcript()
        assertEquals("the table must not lose or gain rows silently", 30, t.lines().size)
        assertEquals(
            "the desktop and iOS disagree about call-quality tiers, OR a threshold moved " +
                "on purpose. In the second case update BOTH this constant and the one in " +
                "OSHITests/CallQualityParityTests.swift, in the same commit.\n" + t,
            POLICY_DIGEST, sha256(t),
        )
    }

    companion object {
        /**
         * SHA-256 of the transcript above. The SAME literal appears in
         * `OSHITests/CallQualityParityTests.swift`.
         */
        const val POLICY_DIGEST =
            "85e5110bcbb4431290d93414f0bbb6e7e84bb64a127a3284a3aa9786a133dc21"
    }
}
