package com.oshi.desktop.mail

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.KeyVault
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

/**
 * [MailClient] against a REAL HTTP server in this process ([FakeMailRelay]), which checks
 * every signature the way the real relay does — a mock here would only prove the method we
 * wrote was called, not that the bytes on the wire are the ones the server would accept.
 *
 * Also exercises the seal/open round trip through the real transport: envelopes, drafts and
 * drive metadata are sealed by [MailClient] before they leave the process and decrypted here
 * with the SAME key material to prove the wire content is actually readable, not just that
 * the HTTP call returned 200.
 */
class MailClientTest {

    private lateinit var relay: FakeMailRelay
    private lateinit var identity: DesktopIdentity
    private lateinit var vault: KeyVault
    private lateinit var client: MailClient

    @Before
    fun setUp() {
        relay = FakeMailRelay()
        identity = DesktopIdentity.generate()
        val file = Files.createTempFile("oshi-mail-vault", ".json").toFile().also { it.delete() }
        vault = KeyVault.open(file, InMemorySecretStore(), null)
        client = MailClient(identity, vault, relay.baseUrl)
    }

    @After
    fun tearDown() = relay.close()

    // ---- account -------------------------------------------------------------------------

    @Test
    fun `availability is unauthenticated and reflects the relay`() {
        val available = client.isAvailable("brandnew").getOrThrow()
        assertTrue(available)
        // No signature headers on an unauthenticated route.
        assertEquals(null, relay.last().signature)
    }

    @Test
    fun `register publishes only the public half of the mail key`() {
        val account = client.register("alice").getOrThrow()
        assertEquals("alice", account.localpart)
        assertEquals("alice@${MailClient.MAIL_DOMAIN}", account.address)
        assertEquals(client.mailPublicKeyHex(), relay.mailPubKeyHex)
        assertEquals(client.mailPublicKeyHex().take(18).lowercase(), client.keyBasedLocalpart)
        // The signature the relay independently verified against the exact JSON body sent.
        assertTrue(FakeRelayIsSigned(relay))
    }

    @Test
    fun `account reads back what register wrote`() {
        client.register("bob").getOrThrow()
        val account = client.account().getOrThrow()
        assertEquals("bob", account.localpart)
        assertEquals(10, account.aliasLimit)
    }

    @Test
    fun `an identity without a claimed mailbox gets no synthetic account`() {
        assertTrue(client.account().isFailure)
    }

    // ---- inbox / message -------------------------------------------------------------------

    @Test
    fun `inbox lists a message and its envelope decrypts with our own mail key`() {
        client.register("carol").getOrThrow()
        val myPub = client.mailPublicKey()
        val envelopePlain = JSONObject()
            .put("from", "dave@x.com").put("to", "carol@${MailClient.MAIL_DOMAIN}")
            .put("subject", "hello").put("date", "2026-09-13T00:00:00Z")
            .toString().toByteArray(Charsets.UTF_8)
        val sealedEnvelope = MailCrypto.seal(envelopePlain, myPub).b64()
        val rfc822 = "From: dave@x.com\r\nSubject: hello\r\n\r\nbody text".toByteArray(Charsets.UTF_8)
        val sealedBody = MailCrypto.seal(rfc822, myPub)

        relay.addMessage(FakeMailRelay.StoredMessage("m1", "inbox", seen = false, sealedEnvelope = sealedEnvelope, sealedBody = sealedBody))

        val result = client.inbox().getOrThrow()
        assertEquals(1, result.messages.size)
        val m = result.messages[0]
        assertEquals("m1", m.id)
        assertFalse(m.seen)
        assertEquals("inbox", m.folder)
        requireNotNull(m.envelope)
        assertEquals("dave@x.com", m.envelope!!.from)
        assertEquals("hello", m.envelope!!.subject)

        val body = client.message("m1").getOrThrow()
        assertArrayEquals(rfc822, body)
        val mail = MailMime.parse(body)
        assertEquals("hello", mail.subject)
        assertEquals("body text", mail.text)
    }

    @Test
    fun `inbox limit rides the query string and is not part of the signed path`() {
        client.register("erin").getOrThrow()
        client.inbox(limit = 42).getOrThrow()
        val rec = relay.last()
        assertEquals("limit=42", rec.query)
        assertEquals("/mail/v1/inbox", rec.path)
        assertTrue(FakeRelayIsSigned(relay))
    }

    @Test
    fun `markSeen and move are signed JSON posts the relay applies`() {
        client.register("frank").getOrThrow()
        val sealedBody = MailCrypto.seal("x".toByteArray(), client.mailPublicKey())
        relay.addMessage(FakeMailRelay.StoredMessage("m2", "inbox", seen = false, sealedEnvelope = null, sealedBody = sealedBody))

        client.markSeen("m2", true).getOrThrow()
        assertTrue(relay.messages.getValue("m2").seen)

        client.move("m2", "archive").getOrThrow()
        assertEquals("archive", relay.messages.getValue("m2").folder)
    }

    @Test
    fun `delete moves to trash first, then a permanent delete removes it`() {
        client.register("gabe").getOrThrow()
        val sealedBody = MailCrypto.seal("x".toByteArray(), client.mailPublicKey())
        relay.addMessage(FakeMailRelay.StoredMessage("m3", "inbox", seen = false, sealedEnvelope = null, sealedBody = sealedBody))

        client.delete("m3").getOrThrow()
        assertEquals("trash", relay.messages.getValue("m3").folder)

        client.delete("m3", permanent = true).getOrThrow()
        assertTrue(relay.messages["m3"] == null)
        // permanent=1 must be on the query, never in the signed path.
        assertEquals("permanent=1", relay.last().query)
        assertEquals("/mail/v1/message/m3", relay.last().path)
    }

    // ---- sending / drafts / aliases ---------------------------------------------------------

    @Test
    fun `send posts plaintext JSON with base64 attachments`() {
        client.register("hana").getOrThrow()
        val id = client.send(
            to = listOf("x@y.com"),
            subject = "subj",
            text = "hi",
            from = "hana.alt@${MailClient.MAIL_DOMAIN}",
            attachments = listOf(MailClient.OutgoingAttachment("a.txt", "text/plain", "hello".toByteArray())),
        ).getOrThrow()
        assertTrue(id.startsWith("sent-"))
        val sent = JSONObject(relay.last().bodyText)
        assertEquals("subj", sent.getString("subject"))
        assertEquals("hana.alt@${MailClient.MAIL_DOMAIN}", sent.getString("from"))
        val att = sent.getJSONArray("attachments").getJSONObject(0)
        assertEquals("a.txt", att.getString("filename"))
        assertArrayEquals("hello".toByteArray(), Base64.getDecoder().decode(att.getString("dataBase64")))
    }

    @Test
    fun `send refuses attachments over the total byte cap before any network call`() {
        client.register("ian").getOrThrow()
        val huge = ByteArray(MailClient.MAX_ATTACHMENT_TOTAL_BYTES + 1)
        val result = client.send(to = listOf("x@y.com"), subject = "s", text = "t",
            attachments = listOf(MailClient.OutgoingAttachment("big.bin", "application/octet-stream", huge)))
        assertTrue(result.isFailure)
    }

    @Test
    fun `saveDraft seals envelope and body to our own mail key before sending them`() {
        client.register("jill").getOrThrow()
        val envelope = "{\"to\":[\"x@y.com\"],\"subject\":\"draft\"}".toByteArray(Charsets.UTF_8)
        val bodyBytes = "draft body".toByteArray(Charsets.UTF_8)
        val id = client.saveDraft(null, envelope, bodyBytes).getOrThrow()
        assertTrue(id.isNotEmpty())

        val sent = JSONObject(relay.last().bodyText)
        val sealedEnvelope = Base64.getDecoder().decode(sent.getString("sealedEnvelope"))
        val sealedBody = Base64.getDecoder().decode(sent.getString("sealedBody"))
        assertArrayEquals(envelope, client.open(sealedEnvelope))
        assertArrayEquals(bodyBytes, client.open(sealedBody))
    }

    @Test
    fun `aliases add and remove round trip`() {
        client.register("kate").getOrThrow()
        val afterAdd = client.addAlias("kate.alt").getOrThrow()
        assertTrue(afterAdd.contains("kate.alt"))
        val afterRemove = client.removeAlias("kate.alt@${MailClient.MAIL_DOMAIN}").getOrThrow()
        assertFalse(afterRemove.contains("kate.alt"))
    }

    // ---- drive --------------------------------------------------------------------------

    @Test
    fun `drive upload spans multiple chunks and round-trips through download`() {
        client.register("leo").getOrThrow()
        val plainFile = ByteArray(64 * 1024 + 777) { (it % 251).toByte() }
        val sealedFile = MailCrypto.seal(plainFile, client.mailPublicKey())

        var lastProgress = 0f
        val id = client.uploadToDrive(sealedFile, "report.pdf") { lastProgress = it }.getOrThrow()
        assertEquals(1f, lastProgress, 0.0001f)
        assertTrue(relay.requests().count { it.path.startsWith("/mail/v1/drive/upload/") && it.method == "PUT" } >= 2)

        val listed = client.driveFiles().getOrThrow()
        assertEquals(1, listed.size)
        assertEquals("report.pdf", listed[0].name)
        assertEquals(id, listed[0].id)

        val downloaded = client.downloadFromDrive(id).getOrThrow()
        assertArrayEquals(plainFile, downloaded)

        client.renameDriveFile(id, "renamed.pdf").getOrThrow()
        assertEquals("renamed.pdf", client.driveFiles().getOrThrow()[0].name)

        client.deleteFromDrive(id).getOrThrow()
        assertTrue(client.driveFiles().getOrThrow().isEmpty())
    }

    // ---- browser pairing -------------------------------------------------------------------

    @Test
    fun `pairing hands the private mail key sealed to the browser, never in the clear`() {
        client.register("mia").getOrThrow()
        val (browserPriv, browserPub) = MailCrypto.generateMailKeyPair()
        relay.pairingCode = "ABCD1234"
        relay.pairingBrowserPubHex = with(MailCrypto) { browserPub.toHex() }

        val pairing = client.inspectPairing(" abcd1234 ").getOrThrow()
        assertArrayEquals(browserPub, pairing.browserPublicKey)

        val address = client.approvePairing(pairing, "My Browser").getOrThrow()
        assertEquals("mia@${MailClient.MAIL_DOMAIN}", address)

        val session = client.webSessions().getOrThrow().single()
        assertEquals("My Browser", session.label)
        assertEquals(1, client.revokeWebSession(session.id).getOrThrow())
        assertTrue(client.webSessions().getOrThrow().isEmpty())

        val sealedKey = Base64.getDecoder().decode(relay.claimedSealedMailKey)
        val openedPriv = MailCrypto.open(sealedKey, browserPriv)
        assertArrayEquals(client.mailPublicKey(), MailCrypto.publicKeyFor(openedPriv))
    }

    // ---- helper ---------------------------------------------------------------------------

    /** True when the last recorded request the relay accepted carried a real signature header. */
    private fun FakeRelayIsSigned(relay: FakeMailRelay): Boolean =
        relay.last().signature != null && relay.last().signingPubKey != null
}
