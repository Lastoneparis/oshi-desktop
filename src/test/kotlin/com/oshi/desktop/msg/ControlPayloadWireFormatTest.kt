package com.oshi.desktop.msg

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * PARITY.md row 0.18 — the exact bytes.
 *
 * ============================================================ HOW THESE TESTS ARE ANCHORED
 *
 * Every expected string below was written by reading the SHIPPED emitters, not by running
 * this package's encoder and pasting what came out. A test that generates its expectation
 * with the code under test proves only that the code is self-consistent — it is vacuous, and
 * this project has already been burned by exactly that shape (a seed-recovery suite that
 * "found 9/9 families" while modelling the wrong Python version).
 *
 * So the golden strings are hand-derived from:
 *
 *   iOS      `OSHI/TypingIndicatorManager.swift`  (`TypingStatus`, bare JSONEncoder)
 *            `OSHI/MessageReactionManager.swift`  (`ReactionEmoji`, `ReactionPayload`)
 *            `OSHI/MessageManager.swift:8593`     (`MessageActionPayload`)
 *            `OSHI/MessageManager.swift:1475,7425`(receipt emit + parse)
 *   Android  `MessageRepository.buildActionPayload` / `sendReadReceipt` / `sendDeliveryReceipt`
 *            `TypingIndicatorManager.buildTypingPayload`
 *            `MessageReactionManager.sendReactionNotification`
 *            `MessageActionWireFormatTest` (Android's own assertions on the same payload)
 *
 * A SECOND layer of tests re-reads those shipped files at run time and fails if the shape
 * they describe has drifted from the constants in this package. That is the part a golden
 * string cannot do: a frozen expectation stays green forever while the thing it was copied
 * from changes underneath it.
 */
class ControlPayloadWireFormatTest {

    private val iosRoot = File(
        System.getProperty("oshi.ios.root") ?: "/Users/HUGOMORICEAU/Documents/Genesis/OSHI"
    )
    private val androidRoot = File(
        System.getProperty("oshi.android.root") ?: "/Users/HUGOMORICEAU/Documents/Genesis/OSHI-Android"
    )
    private fun ios(name: String) = File(iosRoot, name)
    private fun android(rel: String) = File(androidRoot, "app/src/main/java/com/oshi/messenger/$rel")

    /** Android's own wire test uses this base; reusing it keeps the two comparable. */
    private val baseMs = 1_770_000_000_000L

    /** 1_770_000_000_000 / 1000 − 978_307_200, by hand. */
    private val appleLiteral = "791692800"

    private val key = "MCowBQYDK2VuAyEA/EXAMPLE/desktop+key/aaaaaaaaaaaaaaaa="

    // ============================================================== delivery receipt

    /**
     * NOT JSON. iOS strips the prefix and uses the remainder AS the id
     * (`MessageManager.parseDeliveryReceiptMessageId`). Android's emitter comment spells out
     * what a JSON body cost: iOS got `{...}` as the id, matched nothing, and fell back to
     * marking the OLDEST unacked message delivered — the wrong bubble.
     */
    @Test
    fun `delivery receipt is the sentinel plus a bare message id`() {
        assertEquals(
            "📬DELIVERY_RECEIPT📬1B2C3D4E-5F60-7182-9394-A5B6C7D8E9F0",
            DeliveryReceipt.encode("1B2C3D4E-5F60-7182-9394-A5B6C7D8E9F0"),
        )
        assertFalse("a JSON body is the shipped bug, not the format", DeliveryReceipt.encode("m-1").contains("{"))
    }

    @Test
    fun `delivery receipt decodes the bare form, the legacy JSON form, and iOS's trailing whitespace`() {
        assertEquals("m-1", DeliveryReceipt.decode("📬DELIVERY_RECEIPT📬m-1"))
        assertEquals("m-1", DeliveryReceipt.decode("📬DELIVERY_RECEIPT📬{\"messageId\":\"m-1\"}"))
        assertEquals("m-1", DeliveryReceipt.decode("📬DELIVERY_RECEIPT📬 m-1 \n"))
    }

    /** iOS calls this "a legacy bare-prefix receipt" and yields "" — we yield null. */
    @Test
    fun `a bare-prefix delivery receipt carries no id`() {
        assertNull(DeliveryReceipt.decode("📬DELIVERY_RECEIPT📬"))
    }

    // ================================================================== read receipt

    /**
     * iOS emits the bare prefix, Android emits JSON, neither reads the body. We emit
     * Android's form — see [DeliveryReceipt]'s doc comment for why that is the stricter
     * choice — and accept both.
     */
    @Test
    fun `read receipt emits Android's JSON body with an apple-epoch timestamp`() {
        assertEquals(
            "📖READ_RECEIPT📖{\"senderPublicKey\":\"$key\",\"timestamp\":$appleLiteral}",
            ReadReceipt.encode(key, baseMs),
        )
    }

    @Test
    fun `iOS's bare read receipt still decodes, with null fields`() {
        val r = ReadReceipt.decode("📖READ_RECEIPT📖")
        assertNotNull("a bare prefix is iOS's shipped form — losing it loses the blue ticks", r)
        assertNull(r!!.senderPublicKey)
        assertNull(r.timestampMs)
    }

    // ======================================================================= typing

    /**
     * Key order is Swift's property order; `groupId` and `senderName` are OMITTED for a
     * 1-on-1 ping, matching `var groupId: String? = nil` and `encodeIfPresent`.
     */
    @Test
    fun `a one-to-one typing ping matches iOS's TypingStatus shape`() {
        assertEquals(
            "⌨️TYPING⌨️{\"senderPublicKey\":\"$key\",\"isTyping\":true,\"timestamp\":$appleLiteral}",
            TypingPayload.encode(key, isTyping = true, atUnixMillis = baseMs),
        )
    }

    @Test
    fun `a group typing ping appends groupId then senderName, in that order`() {
        assertEquals(
            "⌨️TYPING⌨️{\"senderPublicKey\":\"$key\",\"isTyping\":false,\"timestamp\":$appleLiteral," +
                "\"groupId\":\"3F2504E0-4F89-11D3-9A0C-0305E82C3301\",\"senderName\":\"Alice\"}",
            TypingPayload.encode(
                key, isTyping = false, atUnixMillis = baseMs,
                groupId = "3F2504E0-4F89-11D3-9A0C-0305E82C3301", senderName = "Alice",
            ),
        )
    }

    /** PLAN.md §4.4: absent optionals are OMITTED keys, never JSON null. */
    @Test
    fun `an absent groupId is an omitted key, not a null`() {
        val body = JSONObject(ControlPrefix.strip(TypingPayload.encode(key, true, baseMs)))
        assertFalse("\"groupId\":null is the shape §4.4 forbids", body.has("groupId"))
        assertFalse(body.has("senderName"))
    }

    /**
     * iOS decodes all three of these with `try c.decode(...)` — non-optional. Any one missing
     * throws, `fromMessageString` returns nil, and because `⌨️TYPING⌨️` is SILENT the ping
     * vanishes with no error anyone sees. Android shipped `{"isTyping":true}` for a whole
     * release exactly this way. We must not be more lenient than the peer.
     */
    @Test
    fun `a typing ping missing any of iOS's three required fields is refused`() {
        assertNull(TypingPayload.decode("⌨️TYPING⌨️{\"isTyping\":true}"))
        assertNull(TypingPayload.decode("⌨️TYPING⌨️{\"senderPublicKey\":\"k\",\"timestamp\":$appleLiteral}"))
        assertNull(TypingPayload.decode("⌨️TYPING⌨️{\"senderPublicKey\":\"k\",\"isTyping\":true}"))
        assertNotNull(
            TypingPayload.decode("⌨️TYPING⌨️{\"senderPublicKey\":\"k\",\"isTyping\":true,\"timestamp\":$appleLiteral}")
        )
    }

    /** Both spellings are live on the wire; only one is emitted. */
    @Test
    fun `the legacy Android typing prefix is accepted but never emitted`() {
        val legacy = "✍️TYPING✍️{\"senderPublicKey\":\"k\",\"isTyping\":true,\"timestamp\":$appleLiteral}"
        assertNotNull(TypingPayload.decode(legacy))
        assertTrue(TypingPayload.encode(key, true, baseMs).startsWith(ControlPrefix.TYPING_IOS))
    }

    // ===================================================================== reactions

    @Test
    fun `a reaction matches iOS's ReactionPayload shape and key order`() {
        assertEquals(
            "🔥REACTION🔥{\"messageId\":\"m-1\",\"emoji\":\"👍\",\"senderPublicKey\":\"$key\"," +
                "\"senderName\":\"\",\"timestamp\":$appleLiteral,\"action\":\"add\"}",
            ReactionPayload.encode("m-1", "👍", key, isAdding = true, atUnixMillis = baseMs),
        )
    }

    /** `senderName` is non-Optional on iOS, so the key is written even when empty. */
    @Test
    fun `senderName is always present, even empty`() {
        val body = JSONObject(ControlPrefix.strip(ReactionPayload.encode("m", "🔥", key, true, baseMs)))
        assertTrue("iOS's ReactionPayload.senderName is non-Optional", body.has("senderName"))
        assertEquals("", body.getString("senderName"))
    }

    @Test
    fun `a removal emits action remove, never isAdding`() {
        val body = JSONObject(ControlPrefix.strip(ReactionPayload.encode("m", "✅", key, false, baseMs)))
        assertEquals("remove", body.getString("action"))
        assertFalse("isAdding is legacy Android; emitting both is what makes the two decoders disagree",
            body.has("isAdding"))
    }

    /**
     * THE guard from disagreement 4. iOS's `ReactionEmoji` is a closed 20-value enum and its
     * LENIENT decoder substitutes 👍 for anything else (`swift:165`) — so an un-whitelisted
     * emoji does not fail loudly on an iPhone, it renders a thumbs-up nobody chose.
     */
    @Test
    fun `emitting an emoji outside iOS's twenty is refused`() {
        val e = runCatching { ReactionPayload.encode("m", "😀", key, true, baseMs) }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $e", e is IllegalArgumentException)
        assertTrue(
            "the message must name the substitution, or the next reader will 'fix' the whitelist away",
            e!!.message!!.contains("ReactionPayload.IOS_EMOJI"),
        )
    }

    /** Ingest keeps what the peer sent — coercing here would invent a divergence. */
    @Test
    fun `an unknown emoji still decodes, flagged as not iOS-renderable`() {
        val p = ReactionPayload.decode(
            "🔥REACTION🔥{\"messageId\":\"m\",\"emoji\":\"😀\",\"action\":\"add\"}"
        )
        assertNotNull(p)
        assertEquals("😀", p!!.emoji)
        assertFalse(p.isIosRenderable)
    }

    /** Four shipped decoders, two precedences. We emit `action` and prefer it on ingest. */
    @Test
    fun `when both action and isAdding are present, action wins`() {
        val p = ReactionPayload.decode(
            "🔥REACTION🔥{\"messageId\":\"m\",\"emoji\":\"👍\",\"isAdding\":true,\"action\":\"remove\"}"
        )
        assertNotNull(p)
        assertFalse(
            "iOS's strict Codable tier reads `action` and ignores the extra key, and " +
                "GroupManager.parseGroupReaction does too — so `action` is what the peer acts on",
            p!!.isAdding,
        )
    }

    @Test
    fun `the legacy isAdding-only form still decodes`() {
        val p = ReactionPayload.decode("👍 REACTION👍{\"messageId\":\"m\",\"emoji\":\"👍\",\"isAdding\":false}")
        assertNotNull(p)
        assertFalse(p!!.isAdding)
    }

    /**
     * The object form is legacy-Android-only. Both Android decoders tolerate it; iOS's
     * lenient tier is the ONE decoder that cannot — `json["emoji"] as? String` fails and the
     * whole payload returns nil. So: tolerate on ingest, never emit.
     */
    @Test
    fun `the object emoji form is tolerated on ingest and never emitted`() {
        val p = ReactionPayload.decode(
            "🔥REACTION🔥{\"messageId\":\"m\",\"emoji\":{\"rawValue\":\"🔥\"},\"action\":\"add\"}"
        )
        assertNotNull(p)
        assertEquals("🔥", p!!.emoji)
        assertTrue(ReactionPayload.encode("m", "🔥", key, true, baseMs).contains("\"emoji\":\"🔥\""))
    }

    // ==================================================================== 🔧 actions

    /** Android's own test asserts exactly these three facts about the same payload. */
    @Test
    fun `deleteForEveryone matches the iOS shape and omits newContent`() {
        val payload = MessageActionPayload.encode(
            MessageActionPayload.ActionType.DELETE_FOR_EVERYONE,
            "1B2C3D4E-5F60-7182-9394-A5B6C7D8E9F0", baseMs,
        )
        assertEquals(
            "🔧ACTION🔧{\"actionType\":\"deleteForEveryone\"," +
                "\"targetMessageId\":\"1B2C3D4E-5F60-7182-9394-A5B6C7D8E9F0\",\"timestamp\":$appleLiteral}",
            payload,
        )
        assertFalse(
            "Swift encodes Optionals with encodeIfPresent — a nil newContent is ABSENT, " +
                "not the JSONObject.NULL Android's deleted emitter wrote",
            JSONObject(ControlPrefix.strip(payload)).has("newContent"),
        )
    }

    @Test
    fun `editMessage carries newContent in Swift's declaration position`() {
        assertEquals(
            "🔧ACTION🔧{\"actionType\":\"editMessage\",\"targetMessageId\":\"m-1\"," +
                "\"newContent\":\"corrected text\",\"timestamp\":$appleLiteral}",
            MessageActionPayload.encode(
                MessageActionPayload.ActionType.EDIT_MESSAGE, "m-1", baseMs, "corrected text",
            ),
        )
    }

    /** Android's `content with quotes and newlines survives the JSON encoding`, same input. */
    @Test
    fun `content with quotes, newlines and backslashes survives the encoding`() {
        val nasty = "he said \"hi\"\nthen left\\"
        val payload = MessageActionPayload.encode(
            MessageActionPayload.ActionType.EDIT_MESSAGE, "m", baseMs, nasty,
        )
        assertEquals(nasty, JSONObject(ControlPrefix.strip(payload)).getString("newContent"))
    }

    /** The emoji precedes the `{`, so a brace-scanner misses this payload entirely. */
    @Test
    fun `the action sentinel precedes the opening brace`() {
        val p = MessageActionPayload.encode(MessageActionPayload.ActionType.PIN_MESSAGE, "m", baseMs)
        assertTrue(p.startsWith("🔧ACTION🔧"))
        assertTrue(p.indexOf('{') > 0)
    }

    /**
     * Android's named test, restated from the other side: the timestamp must be Apple-epoch
     * seconds. Asserted on the EMITTED BYTES rather than on a data class, because PLAN.md §3
     * records a real defect in this project's own suite where a `ts` guard asserted on
     * `toJson()` and stayed green while the emitter divided by 1000.
     */
    @Test
    fun `the action timestamp is apple-epoch seconds, not unix millis`() {
        val body = JSONObject(
            ControlPrefix.strip(
                MessageActionPayload.encode(MessageActionPayload.ActionType.EDIT_MESSAGE, "m", baseMs, "x")
            )
        )
        val ts = body.getDouble("timestamp")
        assertEquals((baseMs / 1000.0) - 978_307_200.0, ts, 1e-6)
        assertTrue("looks like Unix millis", ts < 4_000_000_000.0)
        assertTrue(ts > 700_000_000.0)
    }

    @Test
    fun `all four action types round trip`() {
        for (t in MessageActionPayload.ActionType.entries) {
            val content = if (t == MessageActionPayload.ActionType.EDIT_MESSAGE) "new" else null
            val decoded = MessageActionPayload.decode(MessageActionPayload.encode(t, "m-1", baseMs, content))
            assertEquals(t, decoded!!.actionType)
            assertEquals("m-1", decoded.targetMessageId)
            assertEquals(baseMs, decoded.timestampMs)
            assertEquals(content, decoded.newContent)
        }
    }

    /**
     * iOS's `ActionType` is a plain String-raw-value Codable enum with no tolerant decoder,
     * so an unknown value sinks the WHOLE payload on an iPhone. Being more permissive here
     * would mean acting on an action the peer ignores.
     */
    @Test
    fun `an unknown actionType is refused, matching iOS's strict enum`() {
        assertNull(MessageActionPayload.decode("🔧ACTION🔧{\"actionType\":\"burnItAll\",\"targetMessageId\":\"m\"}"))
    }

    @Test
    fun `Android's legacy edit and delete prefixes decode with their messageId key`() {
        val edit = MessageActionPayload.decode(
            MessageActionPayload.LEGACY_EDIT_PREFIX + "{\"messageId\":\"m-9\",\"newContent\":\"fixed\"}"
        )
        assertEquals(MessageActionPayload.ActionType.EDIT_MESSAGE, edit!!.actionType)
        assertEquals("m-9", edit.targetMessageId)
        assertEquals("fixed", edit.newContent)

        val del = MessageActionPayload.decode(
            MessageActionPayload.LEGACY_DELETE_PREFIX + "{\"messageId\":\"m-9\"}"
        )
        assertEquals(MessageActionPayload.ActionType.DELETE_FOR_EVERYONE, del!!.actionType)
    }

    /** An edit whose newContent is empty blanks the peer's bubble — iOS assigns it directly. */
    @Test(expected = IllegalArgumentException::class)
    fun `an edit with no newContent is refused at the emitter`() {
        MessageActionPayload.encode(MessageActionPayload.ActionType.EDIT_MESSAGE, "m", baseMs, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a delete carrying newContent is refused at the emitter`() {
        MessageActionPayload.encode(MessageActionPayload.ActionType.DELETE_FOR_EVERYONE, "m", baseMs, "oops")
    }

    // ======================================================= U+FE0F variant matching

    /**
     * Older iOS builds emit the BARE codepoint. Kotlin's `startsWith` is a UTF-16 comparison,
     * so one spelling silently fails to match half the traffic — and a SILENT payload that
     * fails to match produces no error at all.
     */
    @Test
    fun `a typing prefix without its variation selector still matches, canonically`() {
        val bare = "⌨TYPING⌨{\"senderPublicKey\":\"k\",\"isTyping\":true,\"timestamp\":$appleLiteral}"
        assertEquals(ControlPrefix.TYPING_IOS, ControlPrefix.match(bare))
        assertNotNull("a bare-codepoint ping from an older iOS build must still parse", TypingPayload.decode(bare))
        assertTrue(
            "strip() must remove the spelling ACTUALLY present, or a stray U+FE0F is glued to the JSON",
            ControlPrefix.strip(bare).startsWith("{"),
        )
    }

    @Test
    fun `a longer prefix is never shadowed by a shorter one that shares its lead`() {
        assertEquals(ControlPrefix.CALL_SUMMARY, ControlPrefix.match(ControlPrefix.CALL_SUMMARY + "x"))
        assertEquals(ControlPrefix.MISSED_CALL, ControlPrefix.match(ControlPrefix.MISSED_CALL + "x"))
        assertEquals(ControlPrefix.CHECK_IN, ControlPrefix.match(ControlPrefix.CHECK_IN + "{}"))
        assertEquals(ControlPrefix.CHECKIN, ControlPrefix.match(ControlPrefix.CHECKIN + "{}"))
    }

    @Test
    fun `a sentinel in the middle of prose is prose`() {
        assertNull(ControlPrefix.match("we should add a 📍LOCATION📍 marker"))
        assertNull(TypingPayload.decode("I pressed ⌨️TYPING⌨️ by accident"))
    }

    /** `📞CALL_SIGNAL📞` is SILENT but must still be able to ring — it is not a no-push. */
    @Test
    fun `the no-push list is not derived from the silent kind`() {
        assertTrue(ControlPrefix.isSilent(ControlPrefix.CALL_SIGNAL + "{}"))
        assertFalse(
            "deriving no-push from Kind.SILENT would silence incoming calls",
            ControlPrefix.suppressesPush(ControlPrefix.CALL_SIGNAL + "{}"),
        )
        assertTrue(ControlPrefix.suppressesPush(ControlPrefix.DELIVERY_RECEIPT + "m"))
        assertTrue(ControlPrefix.suppressesPush(ControlPrefix.TYPING_IOS + "{}"))
    }

    // ============================================== drift tripwires against the shipped trees

    /**
     * The 20 `ReactionEmoji` raw values, re-extracted from the shipped Swift at run time.
     *
     * This is the test that makes [ReactionPayload.IOS_EMOJI] worth having: a whitelist
     * copied by hand is wrong the moment iOS adds a 21st reaction, and the failure mode is
     * that the desktop refuses to send an emoji the iPhone renders perfectly. Five of the
     * twenty carry an invisible trailing U+FE0F, so the comparison is on exact code units.
     */
    @Test
    fun `IOS_EMOJI still equals the shipped ReactionEmoji raw values, in declaration order`() {
        val f = ios("MessageReactionManager.swift")
        assumeTrue("iOS tree not present on this machine", f.isFile)
        val text = f.readText()
        val block = text.substring(text.indexOf("enum ReactionEmoji"), text.indexOf("var displayEmoji"))
        val shipped = Regex("""case\s+\w+\s*=\s*"([^"]+)"""").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(
            "iOS's ReactionEmoji has changed. A whitelist that disagrees with it either " +
                "refuses an emoji iPhones render, or emits one they silently rewrite to 👍.",
            shipped, ReactionPayload.IOS_EMOJI,
        )
    }

    /**
     * Swift's synthesized `Codable` emits properties in DECLARATION order, and Android's
     * builders reproduce that order key for key. If a Swift struct is reordered, the golden
     * strings above stop describing what an iPhone sends — and a frozen expectation would
     * never notice.
     */
    @Test
    fun `iOS payload structs still declare their fields in the order this package emits`() {
        data class Case(val file: String, val struct: String, val end: String, val expected: List<String>)
        val cases = listOf(
            Case("TypingIndicatorManager.swift", "struct TypingStatus", "enum CodingKeys",
                listOf("senderPublicKey", "isTyping", "timestamp", "groupId", "senderName")),
            Case("MessageReactionManager.swift", "struct ReactionPayload", "enum ReactionAction",
                listOf("messageId", "emoji", "senderPublicKey", "senderName", "timestamp", "action")),
            Case("MessageManager.swift", "struct MessageActionPayload", "static func isActionMessage",
                listOf("actionType", "targetMessageId", "newContent", "timestamp")),
        )
        for (c in cases) {
            val f = ios(c.file)
            assumeTrue("iOS tree not present on this machine", f.isFile)
            val text = f.readText()
            val from = text.indexOf(c.struct)
            assertTrue("${c.struct} is gone from ${c.file}", from >= 0)
            val to = text.indexOf(c.end, from)
            val block = text.substring(from, if (to > from) to else minOf(text.length, from + 2000))
            val declared = Regex("""^\s*(?:let|var)\s+(\w+)\s*:""", RegexOption.MULTILINE)
                .findAll(block).map { it.groupValues[1] }.toList()
            assertEquals("${c.struct} field order drifted", c.expected, declared)
        }
    }

    /**
     * Android's live emitters, re-read. These are the second independent statement of the
     * same wire shape, and the reason a desktop can be confident without a phone in hand.
     */
    @Test
    fun `Android's live emitters still describe the shapes this package emits`() {
        val repo = android("data/repository/MessageRepository.kt")
        val typing = android("service/TypingIndicatorManager.kt")
        val reaction = android("service/MessageReactionManager.kt")
        assumeTrue("Android tree not present on this machine", repo.isFile && typing.isFile && reaction.isFile)

        val repoText = repo.readText()
        // Delivery receipt: bare id, no JSON.
        assertTrue(repoText.contains("\"\$DELIVERY_RECEIPT_PREFIX\$messageId\""))
        // Read receipt: senderPublicKey + apple-epoch timestamp.
        assertTrue(repoText.contains("(System.currentTimeMillis() / 1000.0) - 978307200.0"))
        // Action: newContent omitted when null.
        assertTrue(repoText.contains("if (newContent != null) put(\"newContent\", newContent)"))

        // Typing: iOS's prefix on the wire, all three required fields.
        val typingText = typing.readText()
        assertTrue(typingText.contains("\"\$TYPING_PREFIX_IOS\${json}\""))
        for (k in listOf("senderPublicKey", "isTyping", "timestamp")) {
            assertTrue("Android stopped emitting typing.$k", typingText.contains("put(\"$k\""))
        }

        // Reaction: iOS's prefix and iOS's `action` spelling, not `isAdding`.
        val reactionText = reaction.readText()
        assertTrue(reactionText.contains("\"\$REACTION_PREFIX_IOS\${json}\""))
        assertTrue(reactionText.contains("put(\"action\", if (isAdding) \"add\" else \"remove\")"))
    }

    /**
     * The catalog is meant to be complete, not "the eight this row uses" — that partial-copy
     * habit is what OSHIControlPayload exists to stop, on both platforms. This checks the
     * desktop's copy against iOS's, entry for entry.
     */
    @Test
    fun `the desktop catalog still matches iOS's, entry for entry`() {
        val f = ios("OSHIControlPayload.swift")
        assumeTrue("iOS tree not present on this machine", f.isFile)
        val text = f.readText()
        val block = text.substring(text.indexOf("static let catalog"), text.indexOf("Named accessors"))
        val shipped = Regex("""Entry\("([^"]+)",\s*\.(\w+)\)""").findAll(block)
            .map { it.groupValues[1] to it.groupValues[2].uppercase() }.toList()
        val mine = ControlPrefix.catalog.map { it.prefix to it.kind.name }
        assertEquals(
            "The desktop control-payload catalog has drifted from iOS's. A partial catalog is " +
                "how a control payload starts rendering as raw JSON on exactly one surface.",
            shipped, mine,
        )
    }
}
