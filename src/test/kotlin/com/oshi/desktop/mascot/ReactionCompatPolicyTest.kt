package com.oshi.desktop.mascot

import com.oshi.desktop.msg.ReactionPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __REACTION_COMPAT_2026_11_16__ The "safe mapping" policy decided with the user on
 * 2026-11-16 for iOS 1.0.43 (on sale), which shows 👍 for any emoji outside its
 * `ReactionEmoji` set:
 *
 *  * cry SENDS 😢 (known to iOS) and is still drawn as the cry mascot, as is a received 😭;
 *  * the quick row is ❤️ 😂 😢 👍 😮 🔥 — nothing in it can become 👍 on an iPhone;
 *  * the other eight unknown-to-iOS mascots stay in the full picker, under the "new" hint.
 *
 * Reverting is one table (`ReactionPayload.MASCOT_WIRE_OVERRIDES`) and one list
 * (`OshiMascot.REACTION_BAR_NAMES`); these tests are the ones to update when that happens.
 */
class ReactionCompatPolicyTest {

    @Test
    fun `cry sends the sad emoji iOS knows, and both spellings draw the cry mascot`() {
        val cry = OshiMascot.named("cry")!!
        assertEquals("😭", cry.emoji)                    // canonical drawing value unchanged
        assertEquals("😢", OshiMascot.wireEmoji(cry))      // U+1F622, iOS `sad`
        assertEquals("😢", OshiMascot.wireEmoji(cry))
        assertTrue(OshiMascot.wireEmoji(cry) in ReactionPayload.IOS_EMOJI)
        assertEquals("cry", OshiMascot.forEmoji("😢")?.name)
        assertEquals("cry", OshiMascot.forEmoji("😭")?.name)
    }

    @Test
    fun `the override table is exactly the one cry entry`() {
        assertEquals(mapOf("😭" to "😢"), ReactionPayload.MASCOT_WIRE_OVERRIDES)
    }

    @Test
    fun `wire values of all 20 mascots, in manifest order`() {
        assertEquals(
            listOf(
                "❤️", "😂", "👍", "😢", "😮", "😡", "🙏", "🔥", "🎉", "😍",
                "😘", "👏", "🤔", "😎", "😴", "✅", "💯", "👋", "🫣", "🥲",
            ),
            OshiMascot.all.map { OshiMascot.wireEmoji(it) },
        )
        assertEquals(OshiMascot.all.map { OshiMascot.wireEmoji(it) }, ReactionPayload.MASCOT_WIRE_EMOJI)
    }

    @Test
    fun `quick row is love laugh cry thumbs wow fire and every one is known to iOS`() {
        assertEquals(listOf("love", "laugh", "cry", "thumbs", "wow", "fire"), OshiMascot.reactionBar.map { it.name })
        val wire = OshiMascot.reactionBar.map { OshiMascot.wireEmoji(it) }
        assertEquals(listOf("❤️", "😂", "😢", "👍", "😮", "🔥"), wire)
        for (e in wire) assertTrue("'$e' would be 👍 on iOS 1.0.43", e in ReactionPayload.IOS_EMOJI)
        assertTrue(OshiMascot.reactionBar.none { OshiMascot.isNewForOlderIphones(it) })
    }

    @Test
    fun `the full picker puts the eight iPhone-unknown mascots in the new section`() {
        assertEquals(
            listOf("angry", "pray", "hearteyes", "cool", "sleep", "wave", "peek", "tearsmile"),
            OshiMascot.pickerNew.map { it.name },
        )
        assertTrue(OshiMascot.pickerNew.all { OshiMascot.isNewForOlderIphones(it) })
        assertEquals(12, OshiMascot.pickerKnown.size)
        assertTrue(OshiMascot.pickerKnown.none { OshiMascot.isNewForOlderIphones(it) })
        assertEquals(OshiMascot.pickerKnown + OshiMascot.pickerNew, OshiMascot.pickerOrder)
        assertEquals(OshiMascot.all.toSet(), OshiMascot.pickerOrder.toSet())
    }

    @Test
    fun `SENDABLE_EMOJI is iOS's set plus exactly the eight new wire values and the OSHI O`() {
        val extra = ReactionPayload.SENDABLE_EMOJI - ReactionPayload.IOS_EMOJI.toSet()
        assertEquals(setOf("😡", "🙏", "😍", "😎", "😴", "👋", "🫣", "🥲", "\u2B55"), extra)
        // The remapped canonical value is no longer something this client sends.
        assertFalse("😭" in ReactionPayload.SENDABLE_EMOJI)
        val err = runCatching { ReactionPayload.encode("m", "😭", "KEY=", true, 1L) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        // Every mascot's wire value encodes.
        for (m in OshiMascot.all) assertTrue(OshiMascot.wireEmoji(m) in ReactionPayload.SENDABLE_EMOJI)
    }

    @Test
    fun `a received 😭 still decodes, and is drawn as cry`() {
        // Another client (or an older desktop) may still send 😭: decoding stays lenient.
        val body = com.oshi.desktop.msg.ControlPrefix.REACTION + """{"messageId":"m","emoji":"😭","senderPublicKey":"K","senderName":"","isAdding":true,"timestamp":1}"""
        val p = ReactionPayload.decode(body)!!
        assertEquals("😭", p.emoji)
        assertFalse(p.isIosRenderable)
        assertEquals("cry", OshiMascot.forEmoji(p.emoji)?.name)
    }

    @Test
    fun `the OSHI O is U+2B55 alone, sendable, first in the quick row and never marked new`() {
        assertEquals("e2ad95", com.oshi.desktop.msg.OshiReaction.OSHI_O.toByteArray().joinToString("") { "%02x".format(it) })
        assertTrue(com.oshi.desktop.msg.OshiReaction.isOshiO("\u2B55"))
        assertFalse(com.oshi.desktop.msg.OshiReaction.isOshiO("\u2B55\uFE0F"))
        assertFalse(com.oshi.desktop.msg.OshiReaction.isOshiO(com.oshi.desktop.msg.OshiReaction.OSHI_SMILE))
        ReactionPayload.encode("m", com.oshi.desktop.msg.OshiReaction.OSHI_O, "KEY=", true, 1_763_000_000_000L)
        assertFalse(ReactionPayload.isNewForOlderIphones(com.oshi.desktop.msg.OshiReaction.OSHI_O))
        assertEquals(
            listOf("\u2B55", "\u2764\uFE0F", "😂", "😢", "👍", "😮", "🔥"),
            OshiMascot.quickRowWire,
        )
    }
}
