package com.oshi.desktop.mail

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.ui.state.MailModel
import com.oshi.desktop.ui.state.MailArrivalTracker
import com.oshi.desktop.pairing.QrMatrix
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

class MailModelTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun exercise(test: (MailModel, FakeMailRelay) -> Unit) {
        FakeMailRelay().use { relay ->
            val vault = KeyVault.open(temporary.root.resolve("vault"), InMemorySecretStore(), null)
            val client = MailClient(DesktopIdentity.generate(), vault, relay.baseUrl)
            val model = MailModel(client, Executor { it.run() })
            model.claim("alice") { assertNull(it) }
            test(model, relay)
        }
    }

    @Test fun `sealed draft reopens with Cc and reply metadata and updates the same record`() = exercise { model, relay ->
        model.editDraft(MailModel.DraftContent(null, "bob@example.com", "carol@example.com", "Subject", "Body", "<original@example.com>"))
        var id: String? = null
        model.autosaveDraft(null, "bob@example.com", "carol@example.com", "Subject", "Body") { id = it }
        assertNotNull(id)
        assertEquals(id, model.state.editingDraft!!.id)
        model.clearEditingDraft()
        model.openDraftForEdit(id!!)
        assertEquals("carol@example.com", model.state.editingDraft!!.cc)
        assertEquals("<original@example.com>", model.state.editingDraft!!.inReplyTo)
        assertEquals("Body", model.state.editingDraft!!.body)
        model.autosaveDraft(id, "bob@example.com", "", "Updated", "Changed") { assertEquals(id, it) }
        assertEquals(1, relay.messages.size)
        model.openDraftForEdit(id!!)
        assertEquals("Changed", model.state.editingDraft!!.body)
    }

    @Test fun `empty composer creates no server draft and new composer preserves work`() = exercise { model, relay ->
        model.autosaveDraft(null, "", "", "", "") { assertNull(it) }
        assertTrue(relay.messages.isEmpty())
        model.editDraft(MailModel.DraftContent(null, "bob@example.com", "", "", "Unsaved"))
        model.newDraft()
        assertEquals("Unsaved", model.state.editingDraft!!.body)
    }

    @Test fun `a delayed autosave cannot assign its id to a replacement composer`() {
        FakeMailRelay().use { relay ->
            val vault = KeyVault.open(temporary.root.resolve("autosave-vault"), InMemorySecretStore(), null)
            val client = MailClient(DesktopIdentity.generate(), vault, relay.baseUrl)
            client.register("alice").getOrThrow()
            val pending = java.util.ArrayDeque<Runnable>()
            val model = MailModel(client, Executor { pending += it })

            model.newDraft()
            model.autosaveDraft(null, "bob@example.com", "", "Old", "draft") { }
            model.clearEditingDraft()
            model.newDraft()
            pending.removeFirst().run()

            assertNull(model.state.editingDraft!!.id)
            assertEquals(1, relay.messages.size)
        }
    }

    @Test fun `failed refresh keeps existing mailbox and failed send keeps composer`() = exercise { model, relay ->
        model.newDraft()
        model.editDraft(model.state.editingDraft!!.copy(body = "Keep me"))
        relay.close()
        model.refresh()
        assertTrue(model.state.hasMailbox)
        assertNotNull(model.state.error)
        model.send(listOf("bob@example.com"), emptyList(), "", "Keep me", null, emptyList(), null, null) { assertNotNull(it) }
        assertEquals("Keep me", model.state.editingDraft!!.body)
        assertNull(model.state.busy)
    }

    @Test fun `failed draft save does not report previous id as success`() = exercise { model, relay ->
        relay.close()
        model.autosaveDraft("existing", "bob@example.com", "", "", "Keep me") { assertNull(it) }
        assertNotNull(model.state.error)
    }

    @Test fun `failed availability leaves mailbox setup unknown`() = exercise { model, relay ->
        relay.close()
        var available: Boolean? = true
        model.isAvailable("alice") { available = it }
        assertNull(available)
    }

    @Test fun `search and badges are folder scoped and body search is opt in`() {
        val inbox = MailClient.MailSummary("1", "now", 1, false, "inbox", "", MailClient.Envelope("Alice", "Bob", "Hello", ""))
        val sent = inbox.copy(id = "2", folder = "sent")
        val state = MailModel.MailUiState(messages = listOf(inbox, sent), bodyCache = mapOf("1" to "needle"))
        assertEquals(1, state.badgeCount("inbox"))
        assertEquals(1, state.badgeCount("sent"))
        assertTrue(state.copy(searchQuery = "needle").visibleMessages.isEmpty())
        assertEquals(listOf(inbox), state.copy(searchQuery = "needle", deepSearch = true).visibleMessages)
        assertEquals(listOf(sent), state.copy(folder = "sent", searchQuery = "alice").visibleMessages)
    }

    @Test fun `recipient suggestions replace only the active token and do not repeat an earlier recipient`() {
        val state = MailModel.MailUiState(contacts = listOf(
            "Alice" to "alice@example.com", "Bob" to "bob@example.com", "Alison" to "alison@example.com",
        ))
        assertEquals(listOf("Alison" to "alison@example.com"), state.recipientSuggestions("alice@example.com, ali"))
        assertEquals("alice@example.com, alison@example.com", state.applyRecipientSuggestion("alice@example.com, ali", "alison@example.com"))
        assertTrue(state.recipientSuggestions("   ").isEmpty())
    }

    @Test fun `mail pane and app shell can independently observe the same mailbox`() = exercise { model, _ ->
        val shell = mutableListOf<MailModel.MailUiState>()
        val pane = mutableListOf<MailModel.MailUiState>()
        val stopShell = model.observe { shell += it }
        val stopPane = model.observe { pane += it }

        model.newDraft()
        assertEquals(2, shell.size)
        assertEquals(2, pane.size)
        assertNotNull(shell.last().editingDraft)
        assertNotNull(pane.last().editingDraft)

        stopShell()
        model.clearEditingDraft()
        assertEquals("unsubscribed shell must not receive later mail state", 2, shell.size)
        assertEquals(3, pane.size)
        assertNull(pane.last().editingDraft)
        stopPane()
    }

    @Test fun `first mailbox snapshot stays loading until its inbox has been fetched`() {
        FakeMailRelay().use { relay ->
            val vault = KeyVault.open(temporary.root.resolve("loading-vault"), InMemorySecretStore(), null)
            val model = MailModel(MailClient(DesktopIdentity.generate(), vault, relay.baseUrl), Executor { it.run() })
            val snapshots = mutableListOf<MailModel.MailUiState>()
            val stop = model.observe { snapshots += it }

            model.claim("alice") { assertNull(it) }

            assertTrue(snapshots.any { it.hasMailbox && it.loading })
            assertFalse(model.state.loading)
            assertTrue(model.state.hasMailbox)
            assertTrue(model.state.messages.isEmpty())
            stop()
        }
    }

    @Test fun `mail notification tracker ignores the initial inbox and local unread changes`() {
        val tracker = MailArrivalTracker()
        val old = MailClient.MailSummary("old", "now", 1, false, "inbox", "", null)
        val first = MailModel.MailUiState(loading = false, hasMailbox = true, messages = listOf(old))
        assertFalse(tracker.onSnapshot(MailModel.MailUiState()))
        assertFalse(tracker.onSnapshot(first))
        assertFalse(tracker.onSnapshot(first.copy(messages = listOf(old.copy(seen = true)))))
        assertFalse(tracker.onSnapshot(first))

        val new = old.copy(id = "new")
        assertTrue(tracker.onSnapshot(first.copy(messages = listOf(old, new))))
        assertFalse(tracker.onSnapshot(first.copy(messages = listOf(old, new))))
        assertFalse(tracker.onSnapshot(first.copy(messages = listOf(old, new.copy(folder = "archive")))))
    }

    @Test fun `a decrypted Drive file attaches to the active mail composer`() = exercise { model, _ ->
        val source = temporary.newFile("from-drive.txt").apply { writeText("sealed drive bytes") }
        model.uploadFile(source) { assertNull(it) }
        model.loadDrive()
        val driveFile = model.state.drive.single()
        assertNotNull(driveFile.name)

        model.newDraft()
        var outcome: String? = "not finished"
        model.attachDriveFile(driveFile) { outcome = it }

        assertNull(outcome)
        val attachment = model.state.editingDraft!!.attachments.single()
        assertEquals("from-drive.txt", attachment.filename)
        assertArrayEquals("sealed drive bytes".toByteArray(), attachment.bytes)
    }

    @Test fun `selected alias is forwarded as the mail sender`() = exercise { model, relay ->
        var result: String? = "unfinished"
        model.send(
            to = listOf("bob@example.com"), cc = emptyList(), subject = "Hello", text = "Body",
            inReplyTo = null, attachments = emptyList(), from = "alice.alt@${MailClient.MAIL_DOMAIN}",
            draftIdToRemove = null,
        ) { result = it }
        assertNull(result)
        val send = relay.requests().last { it.path == "/mail/v1/send" }
        assertEquals("alice.alt@${MailClient.MAIL_DOMAIN}", org.json.JSONObject(send.bodyText).getString("from"))
    }

    @Test fun `an added alias remains available after the account refresh`() = exercise { model, _ ->
        model.addAlias("alice.alt") { assertNull(it) }
        assertTrue(model.state.account!!.aliases.contains("alice.alt"))
    }

    @Test fun `browser pairing requires inspection before it shares the sealed mail key and can be revoked`() = exercise { model, relay ->
        val (_, browserPublic) = MailCrypto.generateMailKeyPair()
        relay.pairingCode = "ABCD1234"
        relay.pairingBrowserPubHex = with(MailCrypto) { browserPublic.toHex() }

        var found: MailClient.PairingRequest? = null
        model.inspectBrowserPairing("abcd1234") { pairing, error -> assertNull(error); found = pairing }
        assertNotNull(found)
        var address: String? = null
        model.approveBrowserPairing(found!!, "Approved from OSHI Desktop") { approved, error -> assertNull(error); address = approved }
        assertEquals("alice@${MailClient.MAIL_DOMAIN}", address)
        assertEquals("Approved from OSHI Desktop", model.state.webSessions.single().label)

        model.revokeBrowserSession(model.state.webSessions.single().id) { assertNull(it) }
        assertTrue(model.state.webSessions.isEmpty())
    }

    @Test fun `mail pairing accepts only a mail-prefixed QR image`() = exercise { model, relay ->
        val (_, browserPublic) = MailCrypto.generateMailKeyPair()
        relay.pairingCode = "ABCD1234"
        relay.pairingBrowserPubHex = with(MailCrypto) { browserPublic.toHex() }
        val codeImage = temporary.newFile("mail-pair.png")
        QrMatrix.encode("OSHIMAIL:PAIR:ABCD1234").toPng(codeImage)

        var pairing: MailClient.PairingRequest? = null
        model.inspectBrowserPairingQr(codeImage) { found, error -> assertNull(error); pairing = found }
        assertEquals("ABCD1234", pairing?.code)

        val contactImage = temporary.newFile("contact-pair.png")
        QrMatrix.encode("oshi://add?key=not-a-mail-request").toPng(contactImage)
        model.inspectBrowserPairingQr(contactImage) { found, error -> assertNull(found); assertNotNull(error) }
    }
}
