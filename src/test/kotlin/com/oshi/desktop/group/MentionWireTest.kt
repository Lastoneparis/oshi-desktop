package com.oshi.desktop.group

import com.oshi.desktop.ui.components.mentionCandidates
import com.oshi.desktop.ui.components.mentionRanges
import com.oshi.desktop.ui.components.insertMention
import com.oshi.desktop.ticker.TickerLinks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * __MENTIONS_2026_09_23__ `@name` person mentions. The golden vector
 * `docs/fixtures/mentions/mentions_v1.json` is shared with OSHI-Android's `MentionWireTest`,
 * so both clients emit and read the same bytes.
 */
class MentionWireTest {

    private val fx = JSONObject(locate().readText())
    private val mentions = (0 until fx.getJSONArray("mentions").length()).map {
        val o = fx.getJSONArray("mentions").getJSONObject(it)
        MentionWire.Mention(o.getString("publicKey"), o.getString("name"))
    }
    private val body = fx.getString("body")
    private val kAlice = mentions[0].publicKey
    private val kJl = mentions[1].publicKey

    @Test fun `render is byte-identical to the shared golden vector`() {
        assertEquals(fx.getString("rendered"), MentionWire.render(mentions))
        assertNull(MentionWire.render(emptyList()))
    }

    @Test fun `parse round-trips the golden value`() {
        assertEquals(mentions, MentionWire.parse(JSONArray(fx.getString("rendered"))))
        assertEquals(mentions, MentionWire.parse(fx.getString("rendered")))
    }

    @Test fun `the desktop encoder emits the golden GroupMessage and decodes it back`() {
        val payload = GroupMessageWire.GroupMessagePayload(
            messageId = fx.getString("messageId"),
            groupId = fx.getString("groupId"),
            senderPublicKey = fx.getString("senderPublicKey"),
            body = body,
            timestampUnixMillis = fx.getLong("unixMillis"),
            mentions = mentions,
        )
        assertEquals(fx.getString("groupMessageJson"), GroupMessageWire.encode(payload))
        val back = GroupMessageWire.decode(fx.getString("groupMessageJson"))!!
        assertEquals(body, back.body)
        assertEquals(mentions, back.mentions)
    }

    @Test fun `no mentions means no key at all`() {
        val json = GroupMessageWire.encode(
            GroupMessageWire.GroupMessagePayload("ID", fx.getString("groupId"), "S", "hi", fx.getLong("unixMillis"))
        )
        assertFalse(json.contains("mentions"))
    }

    /**
     * DEGRADE: what an iPhone on 1.0.43 decodes is the GroupMessage restricted to its
     * `CodingKeys` (JSONDecoder drops every other key). The text it shows is the base64
     * `encryptedContent` — readable `@Alice`, never a key code.
     */
    @Test fun `an iOS 1_0_43 decoder sees readable prose and nothing else`() {
        val full = JSONObject(fx.getString("groupMessageJson"))
        val keys = fx.getJSONArray("iosCodingKeys_1_0_43").let { a -> (0 until a.length()).map(a::getString).toSet() }
        val ios = JSONObject()
        for (k in full.keys()) if (k in keys) ios.put(k, full.get(k))
        assertFalse(ios.has("mentions"))
        val shown = String(Base64.getDecoder().decode(ios.getString("encryptedContent")), Charsets.UTF_8)
        assertEquals(body, shown)
        assertTrue(shown.startsWith("@Alice and @Jean-Luc"))
        assertFalse(shown.contains(kAlice))
    }

    /** DEGRADE: Android 1.6.25 reads `content` with `optString`; an extra key changes nothing. */
    @Test fun `an Android 1_6_25 decoder reads the same content with or without the key`() {
        val with = JSONObject(fx.getString("groupMessageJson"))
        val without = JSONObject(fx.getString("groupMessageJson")).apply { remove("mentions") }
        assertEquals(without.optString("content"), with.optString("content"))
        assertEquals(body, with.optString("content"))
    }

    @Test fun `spans match the golden vector and respect word boundaries`() {
        val spans = MentionWire.spans(body, mentions)
        val expected = fx.getJSONArray("spans").let { a ->
            (0 until a.length()).map { i -> a.getJSONArray(i).let { Triple(it.getInt(0), it.getInt(1), it.getString(2)) } }
        }
        assertEquals(expected, spans.map { Triple(it.start, it.endExclusive, it.publicKey) })
        // `bob@Alice.com` and `@Alicette` are not mentions.
        assertEquals(2, spans.size)
    }

    @Test fun `receiver admits only visible tokens of current members, once per key`() {
        val members = setOf(kAlice, kJl)
        val admitted = MentionWire.admit(body, mentions, { it in members })
        assertEquals(mentions, admitted)
        // Not a member → dropped.
        assertEquals(listOf(mentions[1]), MentionWire.admit(body, mentions, { it == kJl }))
        // Hidden ping: the key is named but its token is not in the text → refused.
        val hidden = MentionWire.Mention(kAlice, "Zed")
        assertTrue(MentionWire.admit(body, listOf(hidden), { true }).isEmpty())
        // Duplicate key → once.
        assertEquals(1, MentionWire.admit(body, listOf(mentions[0], mentions[0].copy(name = "Alice")), { true }).size)
        // Malformed names never parse.
        assertTrue(MentionWire.parse("""[{"publicKey":"K","name":"a@b"},{"publicKey":"","name":"x"},{"name":"y"},7]""").isEmpty())
    }

    @Test fun `mentioned me is decided on the admitted list`() {
        val admitted = MentionWire.admit(body, mentions, { true })
        assertTrue(MentionWire.mentions(kAlice, admitted))
        assertFalse(MentionWire.mentions("SomeoneElse", admitted))
    }

    @Test fun `a picked person wins over a ticker, and real tickers still chip`() {
        val max = MentionWire.Mention("KMAX", "MAX")
        val text = "@MAX look at @AAPL"
        val tickers = TickerLinks.matches(text)
        assertEquals(listOf("MAX", "AAPL"), tickers.map { it.symbol })   // the parser alone would chip both
        val exclude = mentionRanges(text, listOf(max))
        val kept = tickers.filter { m -> exclude.none { r -> m.start <= r.last && r.first < m.end } }
        assertEquals(listOf("AAPL"), kept.map { it.symbol })
        // Without a picked mention `@MAX` stays a ticker — the text alone never makes a mention.
        assertTrue(mentionRanges(text, emptyList()).isEmpty())
    }

    @Test fun `composer query, candidates and insertion`() {
        assertEquals(6 to "Al", MentionWire.activeQuery("hello @Al", 9))
        assertEquals(0 to "", MentionWire.activeQuery("@", 1))
        assertNull(MentionWire.activeQuery("mail bob@Al", 11))
        assertNull(MentionWire.activeQuery("no sigil", 8))
        val members = listOf(MentionWire.Mention("K1", "Alice"), MentionWire.Mention("K2", "Malik"), MentionWire.Mention("K3", "Bob"))
        assertEquals(listOf("Alice", "Malik"), mentionCandidates(members, "al").map { it.name })
        assertEquals("hello @Alice ", insertMention("hello @Al", 6, members[0]))
        assertEquals(listOf(members[0]), MentionWire.stillPresent("hello @Alice ", listOf(members[0], members[2])))
    }

    @Test fun `a stored message keeps its mentions across a restart`() {
        val m = com.oshi.desktop.store.Message(
            id = "M1", conversationId = "G", senderAddress = "S", recipientAddress = "G", fromMe = false,
            content = body, sentAtMs = fx.getLong("unixMillis"),
            sentAtSource = com.oshi.desktop.store.TimestampSource.RELAY_ENVELOPE_MS, mentions = mentions,
        )
        assertEquals(mentions, com.oshi.desktop.store.Message.fromJson(m.toJson()).mentions)
        val plain = m.copy(mentions = emptyList())
        assertFalse(plain.toJson().has("mentions"))
    }

    private fun locate(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, "docs/fixtures/mentions/mentions_v1.json")
            if (f.isFile) return f
            d = d.parentFile
        }
        error("docs/fixtures/mentions/mentions_v1.json not found above ${System.getProperty("user.dir")}")
    }
}
