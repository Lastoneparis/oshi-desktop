package com.oshi.desktop.msg

import com.oshi.desktop.call.CallEndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The strings below are the phones' own, copied from the Swift sources named in [CallSummary]. */
class CallSummaryTest {

    @Test
    fun answeredCallsCarryDirectionAndDuration() {
        assertEquals("📱CALL_SUMMARY📱📞↗️call.outgoing|2:10", CallSummary.forEndedCall(true, true, CallEndReason.HUNG_UP, 130))
        assertEquals("📱CALL_SUMMARY📱📞↙️call.incoming|1:02:03", CallSummary.forEndedCall(false, true, CallEndReason.HUNG_UP, 3723))
    }

    @Test
    fun unansweredCallsFollowHandleMissedCall() {
        assertEquals("📱CALL_SUMMARY📱📞↗️call.no.answer", CallSummary.forEndedCall(true, false, CallEndReason.NO_ANSWER, 0))
        assertEquals("📱CALL_SUMMARY📱📞↗️call.declined", CallSummary.forEndedCall(true, false, CallEndReason.DECLINED, 0))
        assertEquals("📱CALL_SUMMARY📱📞↙️call.declined", CallSummary.forEndedCall(false, false, CallEndReason.DECLINED, 0))
        assertEquals("📱MISSED_CALL📱❌call.missed", CallSummary.forEndedCall(false, false, CallEndReason.NO_ANSWER, 0))
        // They hung up before I answered: iOS files that as MISSED too.
        assertEquals("📱MISSED_CALL📱❌call.missed", CallSummary.forEndedCall(false, false, CallEndReason.HUNG_UP, 0))
    }

    @Test
    fun renderLocalizesTheKeyLikeTheListDoes() {
        val keys = mapOf("call.incoming" to "Appel entrant", "call.missed" to "Appel manqué")
        val l = { k: String -> keys[k] ?: k }
        assertEquals("📞↙️ Appel entrant • 2:10", CallSummary.render("📱CALL_SUMMARY📱📞↙️call.incoming|2:10", l))
        assertEquals("❌ Appel manqué", CallSummary.render("📱MISSED_CALL📱❌call.missed", l))
        assertTrue(CallSummary.isMissed("📱MISSED_CALL📱❌call.missed"))
        assertFalse(CallSummary.isCallSummary("hello"))
    }

    @Test
    fun durationNeverGoesNegative() {
        assertEquals("0:00", CallSummary.duration(-5))
        assertEquals("0:59", CallSummary.duration(59))
    }
}
