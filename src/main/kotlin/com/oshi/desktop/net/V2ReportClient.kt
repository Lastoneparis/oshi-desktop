package com.oshi.desktop.net

import com.oshi.desktop.store.SealedJsonFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * __DESKTOP_REPORT_2026_09_23__ "Signaler" — abuse report for a contact or a group, the
 * desktop port of iOS `ReportSubmitter`/`ReportManager` (`ContactActionsView.swift`) and
 * Android `ReportManager` / `ReportWireFormat`. Desktop had the 34-language strings and no
 * code at all: a desktop user could block but never report.
 *
 * PRIVACY CONTRACT (same as the phones, and as `ServerVPS/v2/report_store.js` expects):
 * the payload is metadata (subject id + type), a reason from a fixed list and the text the
 * REPORTER typed. No message plaintext, no ciphertext, no attachment — ever.
 *
 * Wire: `POST /v2/report`, signed like every v2 route (`x-oshi-user` + signature over
 * `METHOD\nPATH\nSHA256(body)\nTS`); the server refuses unsigned reports (401) and binds
 * the signature to `reporterKey`. 2xx = SENT, 4xx = REJECTED (permanent), anything else
 * (5xx, offline) = PENDING, kept sealed on disk and retried by [retryPending].
 */
class V2ReportClient(
    private val http: V2Http,
    private val reporterKey: () -> String,
    private val file: File?,
    private val key: ByteArray?,
    private val clientVersion: String = "desktop-${V2ConfigGate.DESKTOP_BUILD}",
) {
    enum class Subject(val wire: String) { CONTACT("contact"), GROUP("group") }
    enum class Reason(val wire: String) { SPAM("spam"), HARASSMENT("harassment"), INAPPROPRIATE("inappropriate"), OTHER("other") }
    enum class State { PENDING, SENT, REJECTED }

    data class Report(
        val id: String,
        val subject: Subject,
        val subjectId: String,
        val reason: Reason,
        val details: String,
        val createdAtMs: Long,
        val state: State = State.PENDING,
        val lastError: String? = null,
    )

    companion object {
        const val PATH = "/v2/report"
        const val MAX_DETAILS = 2000
        private const val PURPOSE = "reports-v1"

        /** Exactly what leaves the device — the iOS `ReportPayload` / Android `payloadJson` shape. */
        fun payloadJson(r: Report, reporterKey: String, clientVersion: String): String =
            JSONObject()
                .put("reportId", r.id)
                .put("subjectType", r.subject.wire)
                .put("subjectId", r.subjectId)
                .put("reason", r.reason.wire)
                .put("details", r.details)
                .put("reporterKey", reporterKey)
                .put("clientVersion", clientVersion)
                .put("platform", "desktop")
                .put("createdAt", Instant.ofEpochMilli(r.createdAtMs).truncatedTo(ChronoUnit.SECONDS).toString())
                .toString()
    }

    private val reports = ArrayList<Report>()
    private var loaded = false

    @Synchronized
    fun all(): List<Report> { load(); return reports.sortedByDescending { it.createdAtMs } }

    @Synchronized
    fun hasReported(subject: Subject, subjectId: String): Boolean {
        load()
        val want = norm(subjectId)
        return reports.any { it.subject == subject && norm(it.subjectId) == want }
    }

    /** Record first (never lost), then attempt delivery. Returns the stored record. */
    fun file(subject: Subject, subjectId: String, reason: Reason, details: String, nowMs: Long = System.currentTimeMillis()): Report {
        val r = Report(
            id = UUID.randomUUID().toString(),
            subject = subject,
            subjectId = subjectId.trim(),
            reason = reason,
            details = details.trim().take(MAX_DETAILS),
            createdAtMs = nowMs,
        )
        synchronized(this) { load(); reports += r; save() }
        return deliver(r)
    }

    /** Re-attempt every PENDING report; returns how many were accepted. */
    fun retryPending(): Int {
        val pending = synchronized(this) { load(); reports.filter { it.state == State.PENDING } }
        return pending.count { deliver(it).state == State.SENT }
    }

    private fun deliver(r: Report): Report {
        val body = payloadJson(r, reporterKey(), clientVersion).toByteArray(Charsets.UTF_8)
        val resp = runCatching { http.postJson(PATH, body, withUserHeader = true) }.getOrNull()
        val next = when {
            resp == null -> r.copy(state = State.PENDING, lastError = "unreachable")
            resp.isSuccess -> r.copy(state = State.SENT, lastError = null)
            resp.code in 400..499 -> r.copy(state = State.REJECTED, lastError = "http-${resp.code}: ${resp.body.take(120)}")
            else -> r.copy(state = State.PENDING, lastError = "http-${resp.code}")
        }
        synchronized(this) {
            val i = reports.indexOfFirst { it.id == r.id }
            if (i >= 0) reports[i] = next else reports += next
            save()
        }
        return next
    }

    private fun norm(s: String) = s.trim().replace('-', '+').replace('_', '/').replace("=", "").lowercase()

    private fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        val read = runCatching { SealedJsonFile.read(f, key, PURPOSE) }.getOrNull() ?: return
        val arr = runCatching { JSONObject(read.json).getJSONArray("reports") }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching {
                reports += Report(
                    id = o.getString("id"),
                    subject = Subject.valueOf(o.getString("subject")),
                    subjectId = o.getString("subjectId"),
                    reason = Reason.valueOf(o.getString("reason")),
                    details = o.optString("details", ""),
                    createdAtMs = o.getLong("createdAtMs"),
                    state = State.valueOf(o.optString("state", "PENDING")),
                    lastError = o.optString("lastError").takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        for (r in reports) {
            arr.put(JSONObject()
                .put("id", r.id).put("subject", r.subject.name).put("subjectId", r.subjectId)
                .put("reason", r.reason.name).put("details", r.details).put("createdAtMs", r.createdAtMs)
                .put("state", r.state.name).apply { r.lastError?.let { put("lastError", it) } })
        }
        runCatching { SealedJsonFile.write(f, key, PURPOSE, JSONObject().put("reports", arr).toString()) }
    }
}
