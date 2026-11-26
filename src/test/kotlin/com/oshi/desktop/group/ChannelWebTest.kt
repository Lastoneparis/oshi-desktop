package com.oshi.desktop.group

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.ReplyEnvelope
import com.oshi.desktop.pairing.ExternalLinkGate
import com.oshi.desktop.store.MediaType
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.TimestampSource
import com.oshi.messenger.network.v2.OSHICryptoV2
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * __CHANNEL_WEB_2026_11_26__ PUBLIC_CHANNEL_WEB_SPEC (desktop client): the §10 signing vectors
 * through the PRODUCTION signer, the app → web markup, what a message publishes, links, the embed
 * snippet, deep-link parsing, the settings keys, the outbox (coalescing, backoff, give-up rules,
 * unknown-media re-upload, persistence of n), the reader's feed match and the image step.
 */
class ChannelWebTest {

    // ------------------------------------------------------------------ §10 vectors

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val vectorSigner: DesktopV2Signer by lazy {
        val seed = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val pub = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        val user = OSHICryptoV2.X25519Pair(ByteArray(32), ByteArray(32) { 7 })
        DesktopV2Signer(DesktopIdentity(user, seed, pub))
    }

    private val publishBody =
        "{\"text\":\"**Weekly position** — long BTC, see https://example.org/x\",\"media\":[\"3f2a9c0d4e5b6a7182930a1b2c3d4e5f\"],\"createdAt\":1795708790000}"

    @Test fun `spec 10 the signing key and userKey of the vector`() {
        val h = vectorSigner.sign("GET", "/x", ByteArray(0), withUserHeader = true, timestampMs = 1L)
        assertEquals("A6EHv/POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg=", h["x-oshi-signing-pubkey"])
        assertEquals("BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=", h["x-oshi-user"])
    }

    @Test fun `spec 10 publish vector - our body bytes, hash, canonical string and signature`() {
        val body = ChannelWebContent.postBody(
            "**Weekly position** — long BTC, see https://example.org/x", listOf("3f2a9c0d4e5b6a7182930a1b2c3d4e5f"), 1795708790000L,
        )
        assertArrayEquals(publishBody.toByteArray(Charsets.UTF_8), body)
        assertEquals("f19a8868d211b8769176a033d27ae6656c0f0a0fca56380915b70393560169e4", DesktopV2Signer.sha256Hex(body))
        val path = ChannelWeb.pathPost("swiss_quant", ChannelWeb.postId("7f3c2a10-1b2c-4d5e-8f90-a1b2c3d4e5f6"))
        assertEquals("/v2/c/swiss_quant/posts/7F3C2A10-1B2C-4D5E-8F90-A1B2C3D4E5F6", path)
        assertEquals(
            "PUT\n/v2/c/swiss_quant/posts/7F3C2A10-1B2C-4D5E-8F90-A1B2C3D4E5F6\n" +
                "f19a8868d211b8769176a033d27ae6656c0f0a0fca56380915b70393560169e4\n1795708800001",
            vectorSigner.canonicalString("PUT", path, body, "1795708800001"),
        )
        val h = vectorSigner.sign("PUT", path, body, withUserHeader = true, timestampMs = 1795708800001L)
        assertEquals("HQW0P7TPX+Z81sUL1kU69gJ/ihOu6z1gx4OLyQZKTEJkZfaGdDXEFkB8LGNHC23nfepHUk7T+iBLY31Oa0RVBA==", h["x-oshi-signature"])
        assertEquals("1795708800001", h["x-oshi-timestamp"])
    }

    @Test fun `spec 10 enable, delete and admin vectors`() {
        val enable = "{\"kind\":\"channel\",\"title\":\"Swiss Quant Notes\",\"searchIndexing\":false}".toByteArray()
        assertEquals("29e23ba1db701ad6da1c70432e0f7907d32d66d8bf80c8f6dadcc3185472757a", DesktopV2Signer.sha256Hex(enable))
        assertEquals("TwhiDsrdmc5I/PCFEiZDQ4eIt4GtJFRx4Kh3PDJobEYRyId1asVN+4cXiTov6AlyJ7ORtDJIpmh/DmqPlhC8CA==",
            vectorSigner.sign("PUT", ChannelWeb.pathChannel("swiss_quant"), enable, true, 1795708800000L)["x-oshi-signature"])
        assertEquals("SiCYwd57OUWnVFabd8ZEGJzIcN/J+sm57joNAg2H9Y2zZJ/DXSXvCHgiLDeW2y7NpQGNeK9BRiQ4E8pWkyr1Bw==",
            vectorSigner.sign("DELETE", ChannelWeb.pathPost("swiss_quant", "7F3C2A10-1B2C-4D5E-8F90-A1B2C3D4E5F6"), ByteArray(0), true, 1795708800002L)["x-oshi-signature"])
        assertEquals("FksWcYVBXrETLgzFK9KGtdZZye/VE2JT+nPFZE3ZFrkbZ9OlNcBGIsXbpa/0lyQAgARiwpDS2zZzV1R2+0TwAw==",
            vectorSigner.sign("GET", ChannelWeb.pathAdmin("swiss_quant"), ByteArray(0), true, 1795708800003L)["x-oshi-signature"])
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", DesktopV2Signer.sha256Hex(ByteArray(0)))
    }

    @Test fun `profile body - kind first, title one line, null clears, avatar three states`() {
        assertEquals("{\"kind\":\"channel\",\"title\":\"Swiss Quant Notes\",\"description\":null,\"searchIndexing\":false}",
            String(ChannelWebContent.profileBody("Swiss Quant\nNotes", null, false)))
        assertEquals("{\"kind\":\"channel\",\"title\":null,\"description\":\"**Weekly** notes\",\"avatarMediaId\":\"ab12\",\"searchIndexing\":true}",
            String(ChannelWebContent.profileBody("  ", " *Weekly* notes ", true, "ab12")))
        assertTrue(String(ChannelWebContent.profileBody("T", null, false, ChannelWebContent.REMOVE_AVATAR)).contains("\"avatarMediaId\":null"))
        val long = "x".repeat(300)
        val d = JSONObject(String(ChannelWebContent.profileBody("y".repeat(80), long, false)))
        assertEquals(64, d.getString("title").codePointCount(0, d.getString("title").length))
        assertEquals(255, d.getString("description").length)
        assertTrue(d.getString("description").endsWith("…"))
    }

    @Test fun `json strings escape quotes, backslashes and controls, and keep slashes and non-ASCII`() {
        assertEquals("\"a\\\"b\\\\c\\nd/é—\\u0001\"", ChannelWebContent.jsonString("a\"b\\c\nd/é—\u0001"))
        assertEquals("x\"y\nz", JSONObject("{\"t\":${ChannelWebContent.jsonString("x\"y\nz")}}").getString("t"))
    }

    // ------------------------------------------------------------------ markup

    @Test fun `app markup becomes the web's - bold doubled, italic kept, the rest loses its markers`() {
        val w = ChannelWebText::webMarkup
        assertEquals("**bold** and _italic_", w("*bold* and _italic_"))
        assertEquals("**bold**", w("**bold**"))                       // iOS double bold
        assertEquals("under", w("__under__"))                        // iOS underline: no web form
        assertEquals("gone and code", w("~gone~ and `code`"))
        assertEquals("**_both_**", w("*_both_*"))                     // the §10 vector's shape
        assertEquals("snake_case_name stays, 2*3*4 stays", w("snake_case_name stays, 2*3*4 stays"))
        assertEquals("2 * 3 * 4 = 24", w("2 * 3 * 4 = 24"))
        assertEquals("see https://x.org/a_b_c.", w("see https://x.org/a_b_c."))
        assertEquals("*not\nclosed*", w("*not\nclosed*"))             // never across a line
        assertEquals("`*literal*`".trim('`'), w("`*literal*`"))     // code content is literal
        assertEquals("line1\n**two** words", w("line1\n*two* words"))
        assertEquals("(**x**)", w("(*x*)"))
    }

    @Test fun `publishable text unwraps reply and forward envelopes and skips control payloads`() {
        val reply = ReplyEnvelope.wrap("*hello* world", ReplyEnvelope.Quote("ID", "KEY", "orig", 1L))
        assertEquals("**hello** world", ChannelWebText.publishableText(reply))
        val fwd = ReplyEnvelope.forward("forwarded _text_", "Bob")
        assertEquals("forwarded _text_", ChannelWebText.publishableText(fwd))
        assertNull(ChannelWebText.publishableText(ControlPrefix.POLL + "{\"q\":1}"))
        assertNull(ChannelWebText.publishableText(ControlPrefix.LOCATION + "46.2,6.1"))
        assertNull(ChannelWebText.publishableText(ControlPrefix.REACTION + "{}"))
        assertNull(ChannelWebText.publishableText("   "))
        assertNull(ChannelWebText.publishableText("{\"type\":\"media\",\"content\":\"AAAA\"}"))
        val long = "a".repeat(5000)
        val cut = ChannelWebText.publishableText(long)!!
        assertEquals(4096, cut.codePointCount(0, cut.length))
        assertTrue(cut.endsWith("…"))
        // Code points, not UTF-16 units: an emoji counts once and is never split.
        val emoji = "😀".repeat(5000)
        val e = ChannelWebText.truncate(emoji, 4096)
        assertEquals(4096, e.codePointCount(0, e.length))
    }

    private fun msg(
        content: String?, mediaType: MediaType? = null, mediaRef: String? = null, fromMe: Boolean = true,
        deleted: Boolean = false, commentOn: String? = null, edited: String? = null,
    ) = Message(
        id = "7F3C2A10-1B2C-4D5E-8F90-A1B2C3D4E5F6", conversationId = "G", senderAddress = "me", recipientAddress = "G",
        fromMe = fromMe, content = content, mediaType = mediaType, mediaRef = mediaRef, sentAtMs = 1_795_708_790_000L,
        sentAtSource = TimestampSource.LOCAL_CLOCK, isDeletedForEveryone = deleted, commentOn = commentOn, editedContent = edited,
    )

    @Test fun `what a channel message publishes`() {
        assertEquals(ChannelWebPostSource("**hi**", false, 1_795_708_790_000L), ChannelWebContent.source(msg("*hi*")))
        assertEquals("edited", ChannelWebContent.source(msg("old", edited = "edited"))!!.text)
        // Photo: image + caption; no caption = the stored file name, which is NOT published.
        assertEquals(ChannelWebPostSource("caption", true, 1_795_708_790_000L), ChannelWebContent.source(msg("caption", MediaType.IMAGE, "/m/photo.jpg")))
        assertEquals(ChannelWebPostSource("", true, 1_795_708_790_000L), ChannelWebContent.source(msg("photo.jpg", MediaType.IMAGE, "/m/photo.jpg")))
        // Video / voice / file: the caption only, nothing without one.
        assertEquals(ChannelWebPostSource("watch", false, 1_795_708_790_000L), ChannelWebContent.source(msg("watch", MediaType.VIDEO, "/m/v.mp4")))
        assertNull(ChannelWebContent.source(msg("v.mp4", MediaType.VIDEO, "/m/v.mp4")))
        assertNull(ChannelWebContent.source(msg("a.m4a", MediaType.AUDIO, "/m/a.m4a")))
        // Stickers (mascot `oshi-*.gif`, anything named sticker) are skipped; a GIF is an image.
        assertNull(ChannelWebContent.source(msg("oshi-wave.gif", MediaType.IMAGE, "/m/oshi-wave.gif")))
        assertNull(ChannelWebContent.source(msg("my_sticker.webp", MediaType.IMAGE, "/m/my_sticker.webp")))
        assertTrue(ChannelWebContent.source(msg("giphy-1.gif", MediaType.IMAGE, "/m/giphy-1.gif"))!!.hasImage)
        // Locations, contacts, polls, deleted rows and comments are not posts.
        assertNull(ChannelWebContent.source(msg("x", MediaType.LOCATION, "/m/x")))
        assertNull(ChannelWebContent.source(msg(ControlPrefix.POLL + "{}")))
        assertNull(ChannelWebContent.source(msg(null, deleted = true)))
        assertNull(ChannelWebContent.source(msg("a comment", commentOn = "POST")))
    }

    // ------------------------------------------------------------------ links

    @Test fun `links and the embed snippet byte for byte`() {
        assertEquals("https://oshi-messenger.com/c/swiss_quant", ChannelWeb.channelUrl("swiss_quant"))
        assertEquals("https://oshi-messenger.com/c/swiss_quant/7", ChannelWeb.postUrl("swiss_quant", 7))
        assertEquals("oshi://channel?h=swiss_quant&n=7", ChannelWeb.appLink("swiss_quant", 7))
        assertEquals(
            "<script async src=\"https://oshi-messenger.com/js/oshi-widget.js?1\" data-oshi-post=\"swiss_quant/7\" data-width=\"100%\"></script>",
            ChannelWeb.embedSnippet("swiss_quant", 7),
        )
        assertEquals(
            "<script async src=\"https://oshi-messenger.com/js/oshi-widget.js?1\" data-oshi-post=\"swiss_quant/7\" data-width=\"100%\" data-dark=\"1\"></script>",
            ChannelWeb.embedSnippet("swiss_quant", 7, dark = true),
        )
        assertTrue(ChannelWeb.embedSnippet("h_ndle", 1, dark = false).endsWith(" data-dark=\"0\"></script>"))
        assertTrue(ChannelWeb.isValidPostId("7F3C2A10-1B2C-4D5E-8F90-A1B2C3D4E5F6"))
        assertFalse(ChannelWeb.isValidPostId("a/b"))
        assertFalse(ChannelWeb.isValidPostId(""))
        assertFalse(ChannelWeb.isValidPostId("x".repeat(129)))
    }

    @Test fun `deep links - oshi scheme and https, n optional and lenient, others refused`() {
        assertEquals(ChannelWeb.Link("swiss_quant", 7), ChannelWeb.parseLink("oshi://channel?h=swiss_quant&n=7"))
        assertEquals(ChannelWeb.Link("swiss_quant", null), ChannelWeb.parseLink("oshi://channel?h=Swiss_Quant"))
        assertEquals(ChannelWeb.Link("swiss_quant", null), ChannelWeb.parseLink("oshi://channel?h=swiss_quant&n=abc"))
        assertEquals(ChannelWeb.Link("swiss_quant", null), ChannelWeb.parseLink("oshi://channel?n=0&h=swiss_quant"))
        assertEquals(ChannelWeb.Link("swiss_quant", 25), ChannelWeb.parseLink("https://oshi-messenger.com/c/swiss_quant/25"))
        assertEquals(ChannelWeb.Link("swiss_quant", null), ChannelWeb.parseLink("https://www.oshi-messenger.org/c/swiss_quant"))
        assertEquals(ChannelWeb.Link("swiss_quant", 3), ChannelWeb.parseLink(" https://oshi-messenger.com/c/swiss_quant/3?embed=1 "))
        assertNull(ChannelWeb.parseLink("https://oshi-messenger.com/c/swiss_quant/media/abc"))
        assertNull(ChannelWeb.parseLink("http://oshi-messenger.com/c/swiss_quant/3"))
        assertNull(ChannelWeb.parseLink("https://evil.example/c/swiss_quant/3"))
        assertNull(ChannelWeb.parseLink("https://oshi-messenger.com/g/swiss_quant"))
        assertNull(ChannelWeb.parseLink("oshi://channel?h=ab"))           // invalid handle
        assertNull(ChannelWeb.parseLink("oshi://g/swiss_quant"))
        assertNull(ChannelWeb.parseLink("oshi://group/join?h=swiss_quant"))
        // The /g/ parser does not claim channel links, and the outside-link gate opens them without a prompt.
        assertNull(GroupPublicLink.parse("https://oshi-messenger.com/c/swiss_quant/7"))
        assertTrue(ExternalLinkGate.classify("oshi://channel?h=swiss_quant&n=7") is ExternalLinkGate.Pending.Channel)
        assertTrue(ExternalLinkGate.classify("https://oshi-messenger.com/c/swiss_quant") is ExternalLinkGate.Pending.Channel)
    }

    // ------------------------------------------------------------------ settings keys (GROUP_INFO_SETTINGS_SPEC §2.13)

    @Test fun `webPreview and webIndexing round-trip, default false, and are always emitted`() {
        val s = GroupSettings(visibility = GroupSettings.Visibility.PUBLIC, handle = "swiss_quant",
            postPolicy = GroupSettings.PostPolicy.ADMINS, webPreview = true, webIndexing = true)
        val wire = JSONObject(GroupSettings.encode(s))
        assertEquals(true, wire.get("webPreview"))
        assertEquals(true, wire.get("webIndexing"))
        assertEquals(s, GroupSettings.parse(wire))
        val off = JSONObject(GroupSettings.encode(GroupSettings()))
        assertEquals(false, off.get("webPreview")); assertEquals(false, off.get("webIndexing"))
        assertFalse(GroupSettings.parse(JSONObject("""{"v":1}"""))!!.webPreview)
        assertFalse(GroupSettings.parse(JSONObject("""{"v":1,"webPreview":"yes","webIndexing":1}"""))!!.webPreview)
        // Active ⇔ on AND channel mode AND public with a handle.
        assertTrue(s.webPreviewActive)
        assertFalse(s.copy(postPolicy = GroupSettings.PostPolicy.EVERYONE).webPreviewActive)
        assertFalse(s.copy(visibility = GroupSettings.Visibility.PRIVATE).webPreviewActive)
        assertFalse(s.copy(webPreview = false).webPreviewActive)
    }

    @Test fun `an admin's save on this build keeps the web preview on receivers`() {
        val admin = "A".repeat(43) + "="
        val g = GroupDefinition(
            groupId = "4C8579F8-9CD5-47E8-AFDD-77300D45171D", name = "Swiss Quant", type = GroupType.ADMIN_ONLY, adminPublicKey = admin,
            members = listOf(GroupMember(admin, joinedAtUnixMillis = 1L, isAdmin = true)), createdAtUnixMillis = 1L, lastActivityUnixMillis = 1L, stateVersion = 3,
            settings = GroupSettings(visibility = GroupSettings.Visibility.PUBLIC, handle = "swiss_quant", postPolicy = GroupSettings.PostPolicy.ADMINS,
                webPreview = true, webIndexing = true),
        )
        val edited = g.copy(settings = g.settings!!.copy(welcome = "hi"), stateVersion = 4)
        val decoded = (GroupUpdateWire.decodeDefinition(GroupUpdateWire.encodeDefinition(edited)) as GroupUpdateWire.GroupUpdateDecode.Ok).definition
        val atReceiver = GroupUpdateAuthorizer.apply(g, decoded, GroupUpdateAuthorizer.authorize(g, admin, decoded))
        assertTrue(atReceiver.settings!!.webPreview)
        assertTrue(atReceiver.settings!!.webIndexing)
        val ev = GroupEvents.diff(g, g.copy(settings = g.settings!!.copy(webPreview = false)), admin, 5L)
        assertEquals(listOf(GroupEvents.Kind.WEB_PREVIEW_CHANGED), ev.map { it.kind })
        assertEquals("web_preview_changed", ev.single().kind.wire)
    }

    // ------------------------------------------------------------------ outcome table, backoff, coalescing

    @Test fun `classify follows the spec 6 table`() {
        val k = ChannelWebState.Kind.PUBLISH
        assertEquals(ChannelWebOutcome.DONE, ChannelWebOutcome.classify(200, null, k))
        assertEquals(ChannelWebOutcome.RETRY, ChannelWebOutcome.classify(-1, null, k))
        assertEquals(ChannelWebOutcome.RETRY, ChannelWebOutcome.classify(401, "unauthorized", k))
        assertEquals(ChannelWebOutcome.RETRY, ChannelWebOutcome.classify(429, "rate-limited", k))
        assertEquals(ChannelWebOutcome.RETRY, ChannelWebOutcome.classify(507, "storage-full", k))
        assertEquals(ChannelWebOutcome.RETRY, ChannelWebOutcome.classify(502, null, k))
        assertEquals(ChannelWebOutcome.REUPLOAD_MEDIA, ChannelWebOutcome.classify(400, "unknown-media", k))
        assertEquals(ChannelWebOutcome.DROP, ChannelWebOutcome.classify(400, "invalid-text", k))
        assertEquals(ChannelWebOutcome.DROP, ChannelWebOutcome.classify(403, "signer-not-a-controller", k))
        assertEquals(ChannelWebOutcome.DROP, ChannelWebOutcome.classify(409, "web-preview-off", k))
        assertEquals(ChannelWebOutcome.DROP, ChannelWebOutcome.classify(404, "unknown-handle", k))
        assertEquals(ChannelWebOutcome.DONE, ChannelWebOutcome.classify(404, "unknown-post", ChannelWebState.Kind.DELETE))
        assertEquals(ChannelWebOutcome.DONE, ChannelWebOutcome.classify(404, "unknown-handle", ChannelWebState.Kind.DISABLE))
        assertEquals("unknown-media", ChannelWebOutcome.errorCode("""{"error":"unknown-media","mediaId":"x"}"""))
        assertNull(ChannelWebOutcome.errorCode("<html>"))
    }

    @Test fun `backoff is 5 s doubling to a one-hour cap with plus-minus 20 percent`() {
        assertEquals(5_000L, ChannelWebOutcome.backoffMs(1, 0.5))
        assertEquals(10_000L, ChannelWebOutcome.backoffMs(2, 0.5))
        assertEquals(40_000L, ChannelWebOutcome.backoffMs(4, 0.5))
        assertEquals(3_600_000L, ChannelWebOutcome.backoffMs(12, 0.5))
        assertEquals(3_600_000L, ChannelWebOutcome.backoffMs(25, 0.5))
        assertEquals(4_000L, ChannelWebOutcome.backoffMs(1, 0.0))
        assertEquals(6_000L, ChannelWebOutcome.backoffMs(1, 1.0))
    }

    @Test fun `queue coalescing - one publish per post, delete cancels it, disable cancels everything for the handle`() {
        val s = ChannelWebState()
        fun j(kind: ChannelWebState.Kind, pid: String? = null, h: String = "swiss_quant") = ChannelWebState.Job(kind = kind, groupId = "G", handle = h, postId = pid)
        s.enqueue(j(ChannelWebState.Kind.PUBLISH, "P1"))
        s.enqueue(j(ChannelWebState.Kind.PUBLISH, "P1"))
        assertEquals(1, s.jobs.size)
        s.enqueue(j(ChannelWebState.Kind.DELETE, "P1"))
        assertEquals(listOf(ChannelWebState.Kind.DELETE), s.jobs.map { it.kind })
        s.enqueue(j(ChannelWebState.Kind.PUBLISH, "P2"))
        s.enqueue(j(ChannelWebState.Kind.PROFILE)); s.enqueue(j(ChannelWebState.Kind.PROFILE))
        s.enqueue(j(ChannelWebState.Kind.SYNC)); s.enqueue(j(ChannelWebState.Kind.SYNC))
        s.enqueue(j(ChannelWebState.Kind.PUBLISH, "P9", h = "other_chan"))
        assertEquals(5, s.jobs.size)
        s.enqueue(j(ChannelWebState.Kind.DISABLE))
        assertEquals(listOf(ChannelWebState.Kind.PUBLISH to "other_chan", ChannelWebState.Kind.DISABLE to "swiss_quant"), s.jobs.map { it.kind to it.handle })
        // A profile after a disable does not cancel the purge.
        s.enqueue(j(ChannelWebState.Kind.PROFILE))
        assertTrue(s.jobs.any { it.kind == ChannelWebState.Kind.DISABLE })
    }

    @Test fun `state round-trips through its JSON - n, media ids, jobs, channels`() {
        val s = ChannelWebState()
        s.posts["P1"] = ChannelWebState.PostRecord("swiss_quant", "G", 7, listOf("m1"), mine = true)
        s.posts["P2"] = ChannelWebState.PostRecord("swiss_quant", "G", null)
        s.channels["swiss_quant"] = ChannelWebState.ChannelRecord("G", "sig", "ak", 123L)
        s.enqueue(ChannelWebState.Job(kind = ChannelWebState.Kind.DELETE, groupId = "G", handle = "swiss_quant", postId = "P1", attempts = 30, notBeforeMs = 99L, lastError = "-1"))
        val back = ChannelWebState.fromJson(s.toJson())
        assertEquals(s.posts, back.posts)
        assertEquals(s.channels, back.channels)
        assertEquals(s.jobs, back.jobs)
        assertEquals(7, back.n("P1", "swiss_quant"))
        assertNull(back.n("P1", "other"))
        assertEquals("P1", back.postId("swiss_quant", 7))
        // Garbage never throws, and is read as an empty state.
        assertTrue(ChannelWebState.fromJson("not json").jobs.isEmpty())
        assertTrue(ChannelWebState.fromJson(null).posts.isEmpty())
    }

    @Test fun `reader feed match - send time within 1 ms, else same text within 2 s`() {
        val posts = listOf(
            JSONObject("""{"n":9,"createdAt":1795708795000,"text":"later"}"""),
            JSONObject("""{"n":8,"createdAt":1795708790001,"text":"exact"}"""),
            JSONObject("""{"n":7,"createdAt":1795708788500,"text":"**same**"}"""),
        )
        assertEquals(8, ChannelWebOutcome.matchFeed(posts, 1795708790000L, null))
        assertEquals(7, ChannelWebOutcome.matchFeed(posts, 1795708790400L, "**same**"))
        assertNull(ChannelWebOutcome.matchFeed(posts, 1795708790400L, "different"))
        assertNull(ChannelWebOutcome.matchFeed(posts, 1795708700000L, "later"))
    }

    // ------------------------------------------------------------------ the publisher against a fake server

    private class FakeServer {
        val calls = ArrayList<Triple<String, String, ByteArray?>>()
        val script = ArrayDeque<Pair<Int, String>>()
        var nextN = 1
        val nOf = HashMap<String, Int>()
        fun send(method: String, path: String, query: String?, body: ByteArray?): Pair<Int, String> {
            calls.add(Triple(method, path + (query?.let { "?$it" } ?: ""), body))
            script.removeFirstOrNull()?.let { return it }
            return when {
                method == "PUT" && path.contains("/posts/") -> {
                    val pid = path.substringAfterLast('/')
                    val n = nOf.getOrPut(pid) { nextN++ }
                    200 to """{"ok":true,"n":$n,"url":"https://oshi-messenger.com/c/swiss_quant/$n"}"""
                }
                method == "POST" && path.endsWith("/media") -> 200 to """{"ok":true,"mediaId":"m${calls.size}"}"""
                else -> 200 to """{"ok":true}"""
            }
        }
    }

    private class FakeSource(var post: ChannelWebPostSource? = ChannelWebPostSource("hello", false, 1_795_708_790_000L)) : ChannelWebPublisher.Source {
        var image: ByteArray? = byteArrayOf(1, 2, 3)
        var profile = ChannelWebProfile("Swiss Quant", null, false, null)
        var avatar: ByteArray? = null
        override fun post(groupId: String, postId: String) = post
        override fun imageBytes(groupId: String, postId: String) = image
        override fun profile(groupId: String) = profile
        override fun avatarBytes(groupId: String) = avatar
        override fun channels() = listOf(ChannelWebPublisher.Channel("G", "swiss_quant", webActive = true, iAmAdmin = true))
        override fun sentTimes(groupId: String) = listOf("7F3C2A10-1B2C-4D5E-8F90-A1B2C3D4E5F6" to 1_795_708_790_000L)
        override fun reRegisterHandle(groupId: String) = false
    }

    private val pid = "7F3C2A10-1B2C-4D5E-8F90-A1B2C3D4E5F6"

    private fun publisher(server: FakeServer, source: FakeSource, persistence: ChannelWebPublisher.Persistence = ChannelWebPublisher.Persistence.memory(), now: () -> Long = { 1_000_000L }) =
        ChannelWebPublisher({ m, p, q, b, _, _ -> server.send(m, p, q, b) }, source, persistence, clock = now, jitter = { 0.5 })

    @Test fun `publish stores n, an edit keeps it, a delete removes the record`() {
        val server = FakeServer(); val src = FakeSource()
        val pub = publisher(server, src)
        pub.didSend("G", pid.lowercase(), "swiss_quant")
        pub.drain()
        assertEquals(1, pub.n(pid, "swiss_quant"))
        val put = server.calls.single()
        assertEquals("PUT" to "/v2/c/swiss_quant/posts/$pid", put.first to put.second)
        assertEquals("""{"text":"hello","media":[],"createdAt":1795708790000}""", String(put.third!!))
        // Edit: same postId, same n.
        src.post = ChannelWebPostSource("hello (edited)", false, 1_795_708_790_000L)
        pub.didEdit("G", pid, "swiss_quant")
        pub.drain()
        assertEquals(1, pub.n(pid, "swiss_quant"))
        assertTrue(String(server.calls.last().third!!).contains("hello (edited)"))
        // An edit while the preview is off (or on another handle) does nothing.
        pub.didEdit("G", pid, null); pub.didEdit("G", pid, "other_chan")
        assertTrue(pub.jobs().isEmpty())
        pub.didDelete("G", pid)
        pub.drain()
        assertEquals("DELETE" to "/v2/c/swiss_quant/posts/$pid", server.calls.last().first to server.calls.last().second)
        assertNull(server.calls.last().third)
        assertNull(pub.n(pid, "swiss_quant"))
        assertFalse(pub.isPublished(pid))
    }

    @Test fun `a network failure retries with backoff - every attempt is a NEW request`() {
        val server = FakeServer(); var now = 1_000_000L
        val pub = publisher(server, FakeSource(), now = { now })
        server.script.add(-1 to "IOException"); server.script.add(503 to """{"error":"x"}""")
        pub.didSend("G", pid, "swiss_quant")
        pub.drain()
        assertEquals(1, server.calls.size)
        assertEquals(1, pub.jobs().single().attempts)
        assertEquals(now + 5_000L, pub.jobs().single().notBeforeMs)
        pub.drain()                       // not due yet
        assertEquals(1, server.calls.size)
        now += 5_000L; pub.drain()
        assertEquals(2, server.calls.size)
        assertEquals(now + 10_000L, pub.jobs().single().notBeforeMs)
        now += 10_000L; pub.drain()
        assertEquals(3, server.calls.size)
        assertTrue(pub.jobs().isEmpty())
        assertEquals(1, pub.n(pid, "swiss_quant"))
    }

    @Test fun `publishes give up after 25 tries, deletes and disables never do`() {
        val server = FakeServer(); var now = 0L
        val pub = publisher(server, FakeSource(), now = { now })
        repeat(200) { server.script.add(-1 to "down") }
        pub.didSend("G", pid, "swiss_quant")
        pub.disable("G", "other_chan")
        pub.testEnqueue(ChannelWebState.Job(kind = ChannelWebState.Kind.DELETE, groupId = "G", handle = "third_chan", postId = "P9"), record = true)
        repeat(40) { now += 3_600_000L * 2; pub.drain() }
        val left = pub.jobs().map { it.kind }.toSet()
        assertEquals(setOf(ChannelWebState.Kind.DISABLE, ChannelWebState.Kind.DELETE), left)
        assertTrue(pub.jobs().all { it.attempts >= 40 })
    }

    @Test fun `unknown-media forgets the media ids and uploads again`() {
        val server = FakeServer(); val src = FakeSource(ChannelWebPostSource("pic", true, 1_795_708_790_000L))
        val pub = publisher(server, src)
        pub.didSend("G", pid, "swiss_quant")
        server.script.add(200 to """{"ok":true,"mediaId":"aaa"}""")
        server.script.add(400 to """{"error":"unknown-media","mediaId":"aaa"}""")
        server.script.add(200 to """{"ok":true,"mediaId":"bbb"}""")
        pub.drain()
        assertEquals(listOf("POST", "PUT", "POST", "PUT"), server.calls.map { it.first })
        assertEquals("""{"text":"pic","media":["bbb"],"createdAt":1795708790000}""", String(server.calls.last().third!!))
        assertEquals(listOf("bbb"), pub.post(pid)!!.mediaIds)
        // An edit reuses the attached image (no new upload).
        pub.didEdit("G", pid, "swiss_quant"); pub.drain()
        assertEquals("PUT", server.calls.last().first)
        assertEquals(5, server.calls.size)
    }

    @Test fun `an image the server refuses is dropped and the text still goes out`() {
        val server = FakeServer(); val pub = publisher(server, FakeSource(ChannelWebPostSource("caption", true, 1_795_708_790_000L)))
        server.script.add(415 to """{"error":"unsupported-type"}""")
        pub.didSend("G", pid, "swiss_quant"); pub.drain()
        assertEquals("""{"text":"caption","media":[],"createdAt":1795708790000}""", String(server.calls.last().third!!))
        assertEquals(1, pub.n(pid, "swiss_quant"))
    }

    @Test fun `the outbox and n survive a restart (persistence)`() {
        val store = ChannelWebPublisher.Persistence.memory()
        val server = FakeServer()
        val a = publisher(server, FakeSource(), store)
        server.script.add(-1 to "down")
        a.didSend("G", pid, "swiss_quant"); a.drain()
        assertEquals(1, a.jobs().size)
        val b = publisher(server, FakeSource(), store, now = { 10_000_000L })
        assertEquals(1, b.jobs().size)
        b.drain()
        assertEquals(1, b.n(pid, "swiss_quant"))
        val c = publisher(server, FakeSource(), store)
        assertEquals(1, c.n(pid, "swiss_quant"))
        assertTrue(c.jobs().isEmpty())
    }

    @Test fun `disable purges, and enable first sends a purge that was still queued`() {
        val server = FakeServer(); val pub = publisher(server, FakeSource())
        pub.didSend("G", pid, "swiss_quant"); pub.drain()
        server.script.add(-1 to "down")
        pub.disable("G", "swiss_quant"); pub.drain()
        assertEquals(ChannelWebState.Kind.DISABLE, pub.jobs().single().kind)
        assertEquals(ChannelWebPublisher.EnableResult.OK, pub.enable("G", "swiss_quant", searchIndexing = true))
        val tail = server.calls.takeLast(2).map { it.first to it.second }
        assertEquals(listOf("DELETE" to "/v2/c/swiss_quant", "PUT" to "/v2/c/swiss_quant"), tail)
        assertTrue(String(server.calls.last().third!!).contains("\"searchIndexing\":true"))
        assertNull(pub.n(pid, "swiss_quant"))   // the purge forgot the old numbers
        assertEquals(setOf(ChannelWebState.Kind.PROFILE, ChannelWebState.Kind.SYNC), pub.jobs().map { it.kind }.toSet())
    }

    @Test fun `enable reports the server's refusals`() {
        val server = FakeServer(); val pub = publisher(server, FakeSource())
        server.script.add(404 to """{"error":"unknown-handle"}""")
        assertEquals(ChannelWebPublisher.EnableResult.NOT_REGISTERED, pub.enable("G", "swiss_quant", false))
        server.script.add(403 to """{"error":"signer-not-a-controller"}""")
        assertEquals(ChannelWebPublisher.EnableResult.NOT_CONTROLLER, pub.enable("G", "swiss_quant", false))
        server.script.add(-1 to "down")
        assertEquals(ChannelWebPublisher.EnableResult.OFFLINE, pub.enable("G", "swiss_quant", false))
        assertTrue(pub.jobs().isEmpty())
    }

    @Test fun `profile with a new picture - PUT, upload, PUT avatarMediaId, removing it sends null`() {
        val server = FakeServer(); val src = FakeSource()
        src.profile = ChannelWebProfile("Swiss Quant", "notes", false, "key1"); src.avatar = byteArrayOf(9)
        val pub = publisher(server, src)
        pub.reconcile(null); pub.drain()
        val writes = server.calls.filter { it.first != "GET" }
        assertEquals(listOf("PUT", "POST", "PUT"), writes.map { it.first })
        assertTrue(String(writes.last().third!!).contains("\"avatarMediaId\":\"m"))
        assertEquals("/v2/c/swiss_quant/admin", server.calls.single { it.first == "GET" }.second)
        assertEquals(src.profile.signature, pub.channel("swiss_quant")!!.profileSignature)
        // Nothing changed → nothing re-sent.
        val before = server.calls.size
        pub.reconcile(null); pub.drain()
        assertEquals(before, server.calls.size)
        // Picture removed → one PUT with avatarMediaId null.
        src.profile = src.profile.copy(avatarKey = null)
        pub.reconcile(null); pub.drain()
        assertTrue(String(server.calls.last().third!!).contains("\"avatarMediaId\":null"))
        assertNull(pub.channel("swiss_quant")!!.avatarKey)
    }

    @Test fun `admin map is adopted and reader lookup goes to the public feed unsigned`() {
        val server = FakeServer(); val pub = publisher(server, FakeSource())
        pub.mergeAdmin("""{"ok":true,"posts":[{"postId":"$pid","n":42,"createdAt":1}]}""", "swiss_quant", "G")
        assertEquals(42, pub.n(pid, "swiss_quant"))
        // Reader: unknown locally → GET /v2/c/<h>?limit=50 (no signature), matched by time.
        var signedCalls = 0
        val reader = ChannelWebPublisher({ m, p, q, b, _, signed -> if (signed) signedCalls++; server.send(m, p, q, b) }, FakeSource(),
            ChannelWebPublisher.Persistence.memory())
        server.script.add(200 to """{"posts":[{"n":12,"createdAt":1795708790001,"text":"x"}],"nextBefore":12}""")
        assertEquals(12, reader.resolveN("G", "AAAA", "swiss_quant", isAdmin = false, createdAtMs = 1795708790000L, text = null))
        assertEquals("GET" to "/v2/c/swiss_quant?limit=50", server.calls.last().first to server.calls.last().second)
        assertEquals(0, signedCalls)
        assertEquals(12, reader.n("AAAA", "swiss_quant"))   // remembered, no second lookup
        // Not on the web (older than everything in the feed) → null.
        server.script.add(200 to """{"posts":[{"n":1,"createdAt":1795708999999,"text":"x"}],"nextBefore":null}""")
        assertNull(reader.resolveN("G", "BBBB", "swiss_quant", false, 1795708000000L, null))
    }

    @Test fun `deep link n is matched to our message by the public post's send time`() {
        val server = FakeServer(); val pub = publisher(server, FakeSource())
        server.script.add(200 to """{"channel":{},"post":{"n":5,"createdAt":1795708790001}}""")
        assertEquals(pid, pub.localMessageId("swiss_quant", 5, "G"))
        assertEquals("GET" to "/v2/c/swiss_quant/5", server.calls.last().first to server.calls.last().second)
        server.script.add(404 to """{"error":"not found"}""")
        assertNull(pub.localMessageId("swiss_quant", 6, "G"))
    }

    @Test fun `reconcile drops queued writes of a channel whose preview went off`() {
        val server = FakeServer()
        val src = object : ChannelWebPublisher.Source by FakeSource() {
            override fun channels() = listOf(ChannelWebPublisher.Channel("G", "swiss_quant", webActive = false, iAmAdmin = true))
        }
        val pub = ChannelWebPublisher({ m, p, q, b, _, _ -> server.send(m, p, q, b) }, src, ChannelWebPublisher.Persistence.memory())
        pub.didSend("G", pid, "swiss_quant")
        pub.disable("G", "other_chan")
        pub.reconcile(null)
        assertEquals(listOf(ChannelWebState.Kind.DISABLE), pub.jobs().map { it.kind })
    }

    // ------------------------------------------------------------------ image step

    private fun jpeg(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = java.awt.Color.RED; g.fillRect(0, 0, w / 2, h); g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
    }

    /** An APP1 Exif segment (big-endian TIFF) holding Orientation and a fake GPS-ish make string. */
    private fun withExif(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = ByteArrayOutputStream()
        fun u16(v: Int) { tiff.write(v shr 8 and 0xFF); tiff.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        tiff.write('M'.code); tiff.write('M'.code); u16(42); u32(8)
        u16(2)
        u16(0x010F); u16(2); u32(4); tiff.write("GPS\u0000".toByteArray())        // Make = "GPS" (inline)
        u16(0x0112); u16(3); u32(1); u16(orientation); u16(0)
        u32(0)
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.toByteArray()
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), ((payload.size + 2) and 0xFF).toByte()) + payload
        return jpeg.copyOfRange(0, 2) + seg + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun hasApp1(b: ByteArray): Boolean {
        var p = 2
        while (p + 4 <= b.size && (b[p].toInt() and 0xFF) == 0xFF) {
            val m = b[p + 1].toInt() and 0xFF
            if (m == 0xE1) return true
            if (m == 0xDA) return false
            p += 2 + (((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF))
        }
        return false
    }

    @Test fun `photos are re-encoded upright, at most 2560 px, without EXIF`() {
        val src = withExif(jpeg(40, 20), 6)
        assertEquals(6, ChannelWebImage.exifOrientation(src))
        assertTrue(hasApp1(src))
        val out = ChannelWebImage.prepare(src)!!
        assertFalse("no EXIF / GPS in what is uploaded", hasApp1(out))
        assertFalse(String(out, Charsets.ISO_8859_1).contains("GPS"))
        val img = ImageIO.read(ByteArrayInputStream(out))
        assertEquals(20, img.width); assertEquals(40, img.height)   // orientation 6 applied
        // Rotated 90° clockwise: the red LEFT half is now the TOP half.
        assertTrue(java.awt.Color(img.getRGB(10, 5)).red > 200)
        assertTrue(java.awt.Color(img.getRGB(10, 35)).red < 80)
        val big = ImageIO.read(ByteArrayInputStream(ChannelWebImage.prepare(jpeg(3000, 1000))!!))
        assertEquals(2560, big.width); assertEquals(853, big.height)
        for (o in 1..8) {
            val r = ImageIO.read(ByteArrayInputStream(ChannelWebImage.prepare(withExif(jpeg(40, 20), o))!!))
            assertEquals("orientation $o", if (o >= 5) 20 to 40 else 40 to 20, r.width to r.height)
        }
    }

    @Test fun `GIFs go up as they are, and what is not an image is refused`() {
        val gif = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "gif", it) }.toByteArray()
        assertArrayEquals(gif, ChannelWebImage.prepare(gif))
        assertNull(ChannelWebImage.prepare("<svg/>".toByteArray()))
        assertNull(ChannelWebImage.prepare(ByteArray(0)))
        assertEquals(1, ChannelWebImage.exifOrientation(jpeg(4, 4)))
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(5, 3, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()
        val fromPng = ChannelWebImage.prepare(png)!!
        assertEquals(0xFF, fromPng[0].toInt() and 0xFF); assertEquals(0xD8, fromPng[1].toInt() and 0xFF)   // JPEG out
        assertNotNull(Base64.getEncoder().encodeToString(fromPng))
        assertTrue(File("/").exists())
    }
}
