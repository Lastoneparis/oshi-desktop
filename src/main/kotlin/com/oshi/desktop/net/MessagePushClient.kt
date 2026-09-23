package com.oshi.desktop.net

import com.oshi.desktop.DesktopV2Signer
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * __DESKTOP_MESSAGE_PUSH_2026_09_23__ The wake push iOS and Android fire after every v2 send
 * (iOS `MessageManager+V2.swift` `requestPushNotification`, Android
 * `MessageRepository.pushAfterV2Send` / `GroupManager.wakeMembers`). The v2 relay does NOT push:
 * without this, a Desktop message to a phone whose app is suspended surfaced only at its next
 * poll (1-2 min on Android) and never as a banner.
 *
 * `POST /push/send-push`, signed per `docs/CALL_PUSH_AUTH_CONTRACT.md` §1/§3 row 4 with the
 * account's bound Ed25519 key ([DesktopV2Signer]); the claimed identity is
 * `data.senderPublicKey`. Content is GENERIC: fixed English title/body + loc-keys, and only
 * opaque ids in `data` (sender key, message id, group id) so the receiver resolves names
 * locally. The server's allow-list (`push_service.js` PUSH_DATA_ALLOWED_KEYS) drops anything else.
 *
 * Payload keys and `type` values are the Android ones, which both phones route:
 * 1:1 `type:"message"`, group `type:"group_message"` (Android FCMService handles only that
 * spelling for groups; iOS accepts `group` and `group_message`).
 *
 * Fire-and-forget on one background thread: a send never waits on the push service. Failures
 * are logged, not retried (the message is on the relay; the receiver's poll still finds it).
 */
class MessagePushClient(
    baseUrl: String,
    private val signer: DesktopV2Signer,
    private val senderAddress: String,
    private val log: (String) -> Unit = {},
    private val userAgent: String = defaultUserAgent(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    /** Tests run synchronously; production hands the POST to [executor]. */
    private val synchronous: Boolean = false,
) : AutoCloseable {

    val sendPushUrl: String = baseUrl.trimEnd('/') + PATH

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "oshi-message-push").apply { isDaemon = true }
    }

    /** Wake [recipient] for a 1:1 message. */
    fun wakeDirect(recipient: String, messageId: String) =
        submit(recipient, directBody(recipient, messageId))

    /** Wake each reached member for a group message. */
    fun wakeGroup(members: Collection<String>, groupId: String, messageId: String) {
        for (m in members.distinct()) {
            if (m == senderAddress) continue
            submit(m, groupBody(m, groupId, messageId))
        }
    }

    /** The exact bytes POSTed for a 1:1 wake. Exposed for the tests. */
    fun directBody(recipient: String, messageId: String): ByteArray = JSONObject()
        .put("recipientPublicKey", recipient)
        .put("type", "message")
        .put("title", "New Message")
        .put("body", "You have a new encrypted message")
        .put(
            "data", JSONObject()
                .put("type", "message")
                .put("senderPublicKey", senderAddress)
                .put("senderKey", senderAddress)
                .put("messageId", messageId)
                .put("v2", "1")
                .put("bodyLocKey", "notification.push.body.encrypted")
                .put("titleLocKey", "notification.push.title.message")
        )
        .toString().toByteArray(Charsets.UTF_8)

    /** The exact bytes POSTed for a group wake (generic title — never the group name). */
    fun groupBody(recipient: String, groupId: String, messageId: String): ByteArray = JSONObject()
        .put("recipientPublicKey", recipient)
        .put("type", "group_message")
        .put("title", "OSHI")
        .put("body", "You have a new group message")
        .put(
            "data", JSONObject()
                .put("type", "group_message")
                .put("groupId", groupId)
                .put("senderPublicKey", senderAddress)
                .put("senderKey", senderAddress)
                .put("messageId", messageId)
                .put("v2", "1")
                .put("bodyLocKey", "notification.push.body.encrypted")
                .put("titleLocKey", "notification.push.title.message")
        )
        .toString().toByteArray(Charsets.UTF_8)

    /** Signed headers for [body] (contract §1): path as sent, exact body bytes, ms timestamp. */
    fun signedHeaders(body: ByteArray): Map<String, String> =
        signer.sign("POST", URI.create(sendPushUrl).rawPath, body, timestampMs = clockMs())

    /** POST one wake now; returns the HTTP status, -1 when unreachable. */
    fun post(body: ByteArray): Int {
        val b = HttpRequest.newBuilder(URI.create(sendPushUrl))
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
        signedHeaders(body).forEach { (k, v) -> b.header(k, v) }
        b.POST(HttpRequest.BodyPublishers.ofByteArray(body))
        return try {
            http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
        } catch (e: Exception) {
            -1
        }
    }

    private fun submit(recipient: String, body: ByteArray) {
        val task = Runnable {
            val code = post(body)
            if (code !in 200..299) log("push: wake to ${recipient.take(8)}… → HTTP $code")
        }
        if (synchronous) task.run() else runCatching { executor.execute(task) }
    }

    override fun close() {
        executor.shutdown()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    companion object {
        /** nginx `location /push/` → push_service `/send-push`. */
        const val PATH = "/push/send-push"

        fun defaultUserAgent(): String =
            "OSHI-Desktop/" + (MessagePushClient::class.java.`package`?.implementationVersion ?: "dev")
    }
}
