package com.oshi.desktop.report

import com.oshi.desktop.app.WiringFixture
import com.oshi.desktop.net.V2ReportClient
import com.oshi.desktop.net.V2ReportClient.Reason
import com.oshi.desktop.net.V2ReportClient.State
import com.oshi.desktop.net.V2ReportClient.Subject
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_REPORT_2026_09_23__ "Signaler" on desktop: the wire shape the server
 * (`ServerVPS/v2/report_store.js`) validates, the privacy contract (no conversation
 * content), delivery states, "also block", and retry of a report filed offline.
 */
class ReportClientTest {

    private val fx = WiringFixture()

    @After
    fun tearDown() = fx.close()

    @Test
    fun `the payload has exactly the phones' fields and nothing from the conversation`() {
        val r = V2ReportClient.Report("id-1", Subject.CONTACT, "PEERKEY", Reason.HARASSMENT, "typed by me", 1_700_000_000_000L)
        val o = JSONObject(V2ReportClient.payloadJson(r, "MYKEY", "desktop-1"))
        assertEquals(
            setOf("reportId", "subjectType", "subjectId", "reason", "details", "reporterKey", "clientVersion", "platform", "createdAt"),
            o.keySet(),
        )
        assertEquals("contact", o.getString("subjectType"))
        assertEquals("harassment", o.getString("reason"))
        assertEquals("2023-11-14T22:13:20Z", o.getString("createdAt"))
    }

    @Test
    fun `a contact report reaches the server, carries no message text, and can also block`() {
        val a = fx.client("a")
        val b = fx.client("b")
        b.send(a.address, "the offending message")
        a.router.poll()

        val filed = a.report(Subject.CONTACT, b.address, Reason.SPAM, "  keeps spamming  ", alsoBlock = true)

        assertEquals(State.SENT, filed.state)
        val sent = fx.relay.reports.single()
        assertEquals(b.address, sent.getString("subjectId"))
        assertEquals(a.address, sent.getString("reporterKey"))
        assertEquals("keeps spamming", sent.getString("details"))
        assertEquals("desktop", sent.getString("platform"))
        assertFalse("conversation content left the device", sent.toString().contains("offending"))
        assertTrue("'also block' did not block", a.contacts.all().any { it.address == b.address && it.blocked })
        assertTrue(a.reports.hasReported(Subject.CONTACT, b.address))
    }

    @Test
    fun `a group report sends the group id and a failed delivery is kept and retried`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val g = a.createGroup("noise", listOf(b.address))
        fx.relay.reportStatus = 503

        val first = a.report(Subject.GROUP, g.groupId, Reason.INAPPROPRIATE, "", alsoBlock = false)
        assertEquals("a 5xx must stay PENDING, not be reported as sent", State.PENDING, first.state)
        assertFalse(a.isGroupBlocked(g.groupId))

        fx.relay.reportStatus = 201
        assertEquals(1, a.reports.retryPending())
        assertEquals(g.groupId, fx.relay.reports.single().getString("subjectId"))
        assertEquals("group", fx.relay.reports.single().getString("subjectType"))
        assertEquals(State.SENT, a.reports.all().single().state)
    }

    @Test
    fun `a 4xx is permanent`() {
        val a = fx.client("a")
        val b = fx.client("b")
        fx.relay.reportStatus = 400
        val r = a.report(Subject.CONTACT, b.address, Reason.OTHER, "", alsoBlock = false)
        assertEquals(State.REJECTED, r.state)
        assertEquals(0, a.reports.retryPending())
    }
}
