package com.oshi.desktop.msg

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.app.MessageBackup
import com.oshi.desktop.i18n.Strings
import com.oshi.desktop.i18n.t
import com.oshi.desktop.net.V2ConfigGate
import com.oshi.desktop.store.AdvancedPrivacyStore
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.MessageStore
import com.oshi.desktop.store.TimestampSource
import com.oshi.desktop.ui.state.ListPreview
import com.oshi.messenger.network.v2.OSHICryptoV2
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ The wire (shared with iOS and Android), last-writer-wins,
 * the tie rule, the future clamp, the capability gate, the catalog's answers and each
 * enforcement decision. Mirrors Android `AdvancedChatPrivacyTest` (and iOS
 * `AdvancedChatPrivacyTests`) case for case where the desktop has the same decision.
 */
class AdvancedChatPrivacyTest {

    private val dir: File = Files.createTempDirectory("oshi-adv-privacy").toFile()
    @After fun tearDown() { dir.deleteRecursively() }

    private val now = 1_764_000_000L
    private val alice = "q83vEjRWeJASNFZ4kBI0VniQEjRWeJASNFZ4kBI0Vng="   // standard base64
    private val aliceUrl = "q83vEjRWeJASNFZ4kBI0VniQEjRWeJASNFZ4kBI0Vng"  // same key, unpadded
    private val group = "6f1c2a9e-3b4d-4e5f-8a7b-9c0d1e2f3a4b"
    private val wire = "🛡ADV_PRIVACY🛡"
    private val wireWithSelector = "🛡️ADV_PRIVACY🛡️"

    private fun store() = AdvancedPrivacyStore(File(dir, AdvancedPrivacyStore.FILE_NAME), ByteArray(32) { 7 })
    private fun change(on: Boolean, at: Long) = AdvancedPrivacyPayload.Change(on = on, atUnixSeconds = at)

    // ------------------------------------------------------------------ wire

    @Test
    fun `the wire spelling is the bare shield agreed with iOS and Android`() {
        val text = change(true, 1_761_220_000L).encode()
        assertEquals("$wire{\"v\":1,\"on\":true,\"at\":1761220000}", text)
        assertFalse("no U+FE0F on the wire", text.contains("️"))
        val json = JSONObject(text.removePrefix(wire))
        assertEquals(setOf("v", "on", "at"), json.keySet())
        assertTrue("an integer, so a Swift Int field decodes it", json.get("at") is Number && !json.get("at").toString().contains("."))
    }

    @Test
    fun `encode decode round trip`() {
        for (on in listOf(true, false)) {
            val sent = AdvancedPrivacyPayload.create(on, atMillis = 1_700_000_123_456L)
            assertEquals(1_700_000_123L, sent.atUnixSeconds)
            assertEquals(sent, AdvancedPrivacyPayload.decode(sent.encode()))
        }
    }

    @Test
    fun `the emoji-picker spelling with U+FE0F and a fractional at are accepted`() {
        val c = AdvancedPrivacyPayload.decode("$wireWithSelector{\"v\":1,\"on\":false,\"at\":1761220000.75}")
        assertNotNull(c)
        assertEquals(false, c!!.on)
        assertEquals("floored", 1_761_220_000L, c.atUnixSeconds)
        assertTrue(AdvancedPrivacyPayload.isPayload("$wireWithSelector{}"))
        assertEquals(ControlPrefix.ADV_PRIVACY, ControlPrefix.match("$wireWithSelector{}"))
    }

    @Test
    fun `unknown versions, non-boolean on, missing at, garbage and prose are dropped`() {
        assertNull(AdvancedPrivacyPayload.decode("$wire{\"v\":2,\"on\":true,\"at\":1}"))
        assertNull(AdvancedPrivacyPayload.decode("$wire{\"v\":0,\"on\":true,\"at\":1}"))
        assertNull(AdvancedPrivacyPayload.decode("$wire{\"on\":true,\"at\":1}"))
        assertNull("a string is not a boolean", AdvancedPrivacyPayload.decode("$wire{\"v\":1,\"on\":\"true\",\"at\":1}"))
        assertNull("a number is not a boolean", AdvancedPrivacyPayload.decode("$wire{\"v\":1,\"on\":1,\"at\":1}"))
        assertNull("absent on is not guessed", AdvancedPrivacyPayload.decode("$wire{\"v\":1,\"at\":5}"))
        assertNull(AdvancedPrivacyPayload.decode("$wire{\"v\":1,\"on\":true}"))
        assertNull(AdvancedPrivacyPayload.decode("$wire{\"v\":1,\"on\":true,\"at\":\"soon\"}"))
        assertNull(AdvancedPrivacyPayload.decode("${wire}not json"))
        assertNull(AdvancedPrivacyPayload.decode("hello $wire{\"v\":1,\"on\":true,\"at\":1}"))
        assertFalse(AdvancedPrivacyPayload.isPayload("hello"))
    }

    // ------------------------------------------------------------------ last writer wins

    @Test
    fun `newer wins, older and equal are ignored`() {
        val s = store()
        assertTrue(s.applyIncoming(change(true, now - 100), alice, now))
        assertTrue(s.isOn(alice))
        assertFalse("a late, older off does not undo it", s.applyIncoming(change(false, now - 200), alice, now))
        assertTrue(s.isOn(alice))
        assertFalse("the same change twice is a no-op", s.applyIncoming(change(true, now - 100), alice, now))
        assertTrue(s.applyIncoming(change(false, now - 50), alice, now))
        assertFalse(s.isOn(alice))
    }

    @Test
    fun `our own switch wins and an older peer change cannot override it`() {
        val s = store()
        s.setLocal(change(true, now), alice)
        assertFalse(s.applyIncoming(change(false, now - 1), alice, now))
        assertTrue(s.isOn(alice))
    }

    @Test
    fun `an equal timestamp resolves to ON`() {
        val s = store()
        s.applyIncoming(change(false, now), alice, now)
        assertTrue("off then on in the same second → ON", s.applyIncoming(change(true, now), alice, now))
        assertTrue(s.isOn(alice))
        s.applyIncoming(change(true, now + 10), group, now + 10)
        assertFalse("on then off in the same second → stays ON", s.applyIncoming(change(false, now + 10), group, now + 10))
        assertTrue(s.isOn(group))
        assertTrue(AdvancedPrivacyPayload.wins(false, now, true, now))
        assertFalse(AdvancedPrivacyPayload.wins(true, now, false, now))
    }

    @Test
    fun `key spellings and group id case fold to one conversation`() {
        val s = store()
        s.applyIncoming(change(true, now), aliceUrl, now)
        assertTrue(s.isOn(alice))
        s.applyIncoming(change(true, now), group.uppercase(), now)
        assertTrue(s.isOn(group.lowercase()))
        assertFalse(s.isOn("someone-else"))
        assertFalse(s.isOn(""))
    }

    @Test
    fun `a future timestamp is clamped to now plus five minutes`() {
        assertEquals(now + 300, AdvancedPrivacyPayload.clampedAt(now + 1_000_000_000L, now))
        assertEquals(now - 5, AdvancedPrivacyPayload.clampedAt(now - 5, now))
        val s = store()
        // A peer claiming the year 3000 for "off" …
        s.applyIncoming(change(false, 32_503_680_000L), alice, now)
        assertEquals(now + 300, s.state(alice)!!.changedAtUnixSeconds)
        // … cannot lock the conversation: a change six minutes later still wins.
        assertTrue(s.applyIncoming(change(true, now + 360), alice, now + 360))
        assertTrue(s.isOn(alice))
    }

    @Test
    fun `the state, the readers and the caps survive a restart, sealed`() {
        val a = store()
        a.setLocal(change(true, now), group)
        a.noteReader(aliceUrl)
        a.noteCaps("bob", 30)
        val raw = File(dir, AdvancedPrivacyStore.FILE_NAME).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("sealed at rest: no conversation id in the clear", raw.contains(group.uppercase()) || raw.contains("readers"))
        val b = store()
        assertTrue(b.isOn(group))
        assertTrue(b.sentUsOne(alice))
        assertEquals(30, b.capsOf("bob"))
    }

    // ------------------------------------------------------------------ who may receive it

    @Test
    fun `this build advertises bit 16 and it is the same bit as iOS and Android`() {
        assertEquals(16, AdvancedPrivacyPolicy.CAP_ADVANCED_PRIVACY)
        assertEquals(16, V2ConfigGate.CAP_ADVANCED_PRIVACY)
        assertTrue((V2ConfigGate.MY_CAPS and 16) != 0)
        assertEquals("MY_CAPS 14 → 30: the three older bits untouched", 30, V2ConfigGate.MY_CAPS)
        assertEquals(14, V2ConfigGate.MY_CAPS and 15)
    }

    @Test
    fun `a peer reads the payload only by cap or by proof`() {
        assertTrue(AdvancedPrivacyPolicy.peerReads(accountCaps = 16, sentUsOne = false))
        assertTrue(AdvancedPrivacyPolicy.peerReads(accountCaps = 31, sentUsOne = false))
        assertTrue(AdvancedPrivacyPolicy.peerReads(accountCaps = null, sentUsOne = true))
        // Android 1.7.3 / old desktop (15 / 14), iOS 1.0.43 (1) and an unknown bundle do not.
        assertFalse(AdvancedPrivacyPolicy.peerReads(accountCaps = 15, sentUsOne = false))
        assertFalse(AdvancedPrivacyPolicy.peerReads(accountCaps = 14, sentUsOne = false))
        assertFalse(AdvancedPrivacyPolicy.peerReads(accountCaps = 1, sentUsOne = false))
        assertFalse(AdvancedPrivacyPolicy.peerReads(accountCaps = null, sentUsOne = false))
    }

    @Test
    fun `having sent us one is remembered under either key spelling, and caps can withdraw`() {
        val s = store()
        assertFalse(s.peerReads(alice))
        s.noteReader(aliceUrl)
        assertTrue(s.peerReads(alice))
        s.noteCaps("bob", 30)
        assertTrue(s.peerReads("bob"))
        s.noteCaps("bob", 14)
        assertFalse("a newer bundle without the bit is believed", s.peerReads("bob"))
    }

    @Test
    fun `the readers record is bounded`() {
        val s = store()
        repeat(AdvancedPrivacyStore.MAX_PEERS + 5) { s.noteReader("peer-$it") }
        assertFalse("the oldest is evicted", s.sentUsOne("peer-0"))
        assertTrue(s.sentUsOne("peer-${AdvancedPrivacyStore.MAX_PEERS + 4}"))
    }

    @Test
    fun `a group fan-out keeps only the readers`() {
        val members = listOf("a", "b", "c", "d")
        val reads = setOf("b", "d")
        assertEquals(listOf("b", "d"), AdvancedPrivacyPolicy.readers(members) { it in reads })
        assertEquals(emptyList<String>(), AdvancedPrivacyPolicy.readers(members) { false })
    }

    // ------------------------------------------------------------------ catalog

    @Test
    fun `the catalog renders it, never raw, never unread, never a push`() {
        val on = change(true, now).encode()
        val off = change(false, now).encode()
        for (raw in listOf(on, off, on.replace(wire, wireWithSelector))) {
            assertEquals(ControlPrefix.ADV_PRIVACY, ControlPrefix.match(raw))
            assertEquals(ControlPrefix.Kind.RENDERED, ControlPrefix.kindOf(raw))
            assertTrue("isControl ⇒ never prose, never AI context", ControlPrefix.isControl(raw))
            assertFalse("rendered, not silent", ControlPrefix.isSilent(raw))
            assertTrue("no push to the peer", ControlPrefix.suppressesPush(raw))
            assertTrue("no unread, no notification", ControlPrefix.isQuietNotice(raw))
            assertEquals("{", ControlPrefix.strip(raw).take(1))
        }
        assertFalse("prose that mentions it is prose", ControlPrefix.isQuietNotice("about $wire later"))
        assertFalse(ControlPrefix.isQuietNotice("🛡️CHECK_IN🛡️{}"))
        // The catalog entry is the bare spelling, after OFFLINE_ONLY and before MEDIA, as iOS lists it.
        val order = ControlPrefix.catalog.map { it.prefix }
        assertEquals(order.indexOf(ControlPrefix.OFFLINE_ONLY) + 1, order.indexOf(wire))
        assertEquals(order.indexOf(wire) + 1, order.indexOf(ControlPrefix.MEDIA_MARKER))
    }

    @Test
    fun `the list preview says it in words, never the payload`() {
        fun row(content: String) = Message(
            id = "x", conversationId = alice, senderAddress = alice, recipientAddress = "me", fromMe = false,
            content = content, sentAtMs = 1_764_000_000_000L, sentAtSource = TimestampSource.LOCAL_CLOCK,
        )
        val on = ListPreview.of(row(change(true, now).encode())) { "RAW" }
        val off = ListPreview.of(row(change(false, now).encode())) { "RAW" }
        assertEquals("🛡 " + t("desktop.adv_privacy.summary.on"), on.text)
        assertEquals("🛡 " + t("desktop.adv_privacy.summary.off"), off.text)
        assertFalse(on.text.contains("{"))
        assertEquals("Advanced chat privacy turned on", Strings.get("desktop.adv_privacy.summary.on", "en"))
        assertEquals("Advanced chat privacy turned off", Strings.get("desktop.adv_privacy.summary.off", "en"))
    }

    @Test
    fun `every locale serves the strings from its own catalog`() {
        val keys = listOf(
            "desktop.adv_privacy.title", "desktop.adv_privacy.menu.subtitle", "desktop.adv_privacy.notice.on.other",
            "desktop.adv_privacy.sheet.media", "desktop.adv_privacy.sheet.not_applied",
        )
        for (tag in Strings.availableTags) for (k in keys) {
            assertNotNull("$tag lacks $k", Strings.catalog(tag)?.get(k))
        }
        assertEquals(34, Strings.availableTags.size)
        assertTrue(Strings.catalog("fr")!!["desktop.adv_privacy.notice.on.other"]!!.contains("%@"))
    }

    // ------------------------------------------------------------------ enforcement decisions

    @Test
    fun `AI context is empty for a protected conversation`() {
        val readable = listOf("hi", "hello", "how are you")
        val s = store()
        s.setLocal(change(true, now), alice)
        assertEquals(emptyList<String>(), AdvancedPrivacyPolicy.aiReadable(s.isOn(alice), readable))
        assertFalse(AdvancedPrivacyPolicy.aiAllowed(s.isOn(alice)))
        assertEquals(readable, AdvancedPrivacyPolicy.aiReadable(s.isOn("bob"), readable))
        assertTrue(AdvancedPrivacyPolicy.aiAllowed(s.isOn("bob")))
    }

    @Test
    fun `export is refused for a protected conversation only, and back when it is lifted`() {
        val s = store()
        s.applyIncoming(change(true, now), group, now)
        assertFalse(AdvancedPrivacyPolicy.exportAllowed(s.isOn(group)))
        assertTrue(AdvancedPrivacyPolicy.exportAllowed(s.isOn(alice)))
        s.applyIncoming(change(false, now + 1), group, now + 1)
        assertTrue(AdvancedPrivacyPolicy.exportAllowed(s.isOn(group)))
    }

    @Test
    fun `the encrypted export leaves a protected conversation out entirely`() {
        val ed = Ed25519PrivateKeyParameters(java.security.SecureRandom())
        val account = DesktopIdentity(OSHICryptoV2.generateX25519(), ed.encoded, ed.generatePublicKey().encoded)
        fun m(id: String, conv: String) = Message(
            id = id, conversationId = conv, senderAddress = conv, recipientAddress = "me", fromMe = false,
            content = "line $id", sentAtMs = 1_770_000_000_000L, sentAtSource = TimestampSource.RELAY_ENVELOPE_MS,
            deliveryStatus = DeliveryStatus.READ,
        )
        val source = MessageStore(File(dir, "src"), ByteArray(32) { 1 })
        source.append(m("m1", "peer-open"))
        source.append(m("m2", "peer-protected"))
        source.append(m("m3", "peer-protected"))
        val result = MessageBackup(account, source, isProtected = { it == "peer-protected" }).export(File(dir, "out"))
        assertEquals(1, result.messages)
        val target = MessageStore(File(dir, "dst"), ByteArray(32) { 1 })
        MessageBackup(account, target).import(result.file)
        assertEquals(1, target.messages("peer-open").size)
        assertTrue(target.messages("peer-protected").isEmpty())
    }

    @Test
    fun `saving others' media is blocked but not our own`() {
        assertFalse(AdvancedPrivacyPolicy.mediaSaveAllowed(privacyOn = true, isFromMe = false))
        assertTrue(AdvancedPrivacyPolicy.mediaSaveAllowed(privacyOn = true, isFromMe = true))
        assertTrue(AdvancedPrivacyPolicy.mediaSaveAllowed(privacyOn = false, isFromMe = false))
    }

    @Test
    fun `a protected chat on screen asks for capture protection, and releases it`() {
        val s = store()
        val seen = ArrayList<Boolean>()
        s.onCaptureProtectionChange { seen += it }
        assertFalse(s.captureProtectionRequested)
        s.setOnScreen(aliceUrl, on = true)
        assertTrue(s.captureProtectionRequested)
        s.setOnScreen(aliceUrl, on = true)
        s.setOnScreen("bob", on = false)
        assertFalse(s.captureProtectionRequested)
        s.setOnScreen(null, on = false)
        assertEquals("one event per change, none for a repeat", listOf(true, false), seen)
        assertTrue(AdvancedPrivacyPolicy.forcesCaptureProtection(true))
    }

    @Test
    fun `the window guard protects while a protected chat is on screen without touching the setting`() {
        val pref = com.oshi.desktop.store.ScreenCapturePreference
        val before = pref.advancedPrivacyOnScreen
        try {
            pref.setAdvancedPrivacyOnScreen(true)
            assertTrue(pref.windowProtected)
            pref.setAdvancedPrivacyOnScreen(false)
            assertEquals(pref.effective, pref.windowProtected)
        } finally {
            pref.setAdvancedPrivacyOnScreen(before)
        }
    }
}
