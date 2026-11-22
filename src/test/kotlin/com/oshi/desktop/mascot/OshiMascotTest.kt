package com.oshi.desktop.mascot

import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.OshiReaction
import com.oshi.desktop.msg.ReactionPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** __O_BUBBLE_MASCOT_2026_11_16__ */
class OshiMascotTest {

    private val names = listOf(
        "love", "laugh", "thumbs", "cry", "wow", "angry", "pray", "fire", "party", "hearteyes",
        "kiss", "clap", "thinking", "cool", "sleep", "ok", "hundred", "wave", "peek", "tearsmile",
    )

    @Test
    fun `the bundled manifest has the 20 mascots, every file present`() {
        assertEquals(names, OshiMascot.all.map { it.name })
        for (m in OshiMascot.all) {
            assertNotNull("gif ${m.name}", OshiMascot.classpathBytes(m.gifPath))
            assertNotNull("still 64 ${m.name}", OshiMascot.classpathBytes(m.staticPath(64)))
            assertNotNull("still 128 ${m.name}", OshiMascot.classpathBytes(m.staticPath(128)))
            assertEquals(20, m.fps)
            assertEquals(m.frames * 1000 / m.fps, m.durationMs)
        }
        for (i in 0 until 40) assertNotNull(OshiMascot.classpathBytes("mascot/launch/wave_512/%02d.png".format(i)))
        assertNotNull(OshiMascot.classpathBytes("mascot/launch/logo_1024.png"))
        assertNotNull(OshiMascot.classpathBytes("mascot/launch/neutral_1024.png"))
    }

    @Test
    fun `each canonical emoji maps to its mascot`() {
        val expected = mapOf(
            "❤️" to "love", "😂" to "laugh", "😭" to "cry", "🫣" to "peek", "🥲" to "tearsmile", "👍" to "thumbs",
            "🙏" to "pray", "🔥" to "fire", "😮" to "wow", "😡" to "angry", "🎉" to "party", "👋" to "wave",
            "😍" to "hearteyes", "🤔" to "thinking", "👏" to "clap", "😘" to "kiss", "😴" to "sleep",
            "😎" to "cool", "✅" to "ok", "💯" to "hundred",
        )
        assertEquals(20, expected.size)
        for ((emoji, name) in expected) assertEquals(emoji, name, OshiMascot.forEmoji(emoji)?.name)
    }

    @Test
    fun `aliases, bare variation selectors and skin tones draw the same mascot`() {
        val expected = mapOf(
            "❤" to "love", "♥" to "love", "♥️" to "love", "💜" to "love", "🩷" to "love", "🖤" to "love",
            "🤣" to "laugh", "😢" to "cry", "😱" to "wow", "🤬" to "angry", "🥳" to "party", "🥰" to "hearteyes",
            "😚" to "kiss", "🙌" to "clap", "🧐" to "thinking", "💤" to "sleep", "✔️" to "ok", "✔" to "ok",
            "👌" to "ok", "🙋" to "wave", "🙈" to "peek",
            "👍🏽" to "thumbs", "👍🏿" to "thumbs", "🙏🏻" to "pray", "👋🏾" to "wave", "👏🏼" to "clap",
        )
        for ((emoji, name) in expected) assertEquals(emoji, name, OshiMascot.forEmoji(emoji)?.name)
    }

    @Test
    fun `emoji without a mascot, and the OSHI smile, keep their old rendering`() {
        for (e in listOf("😀", "👎", "❌", "👀", "✝️", "🖕", "", "a", OshiReaction.OSHI_SMILE, "⭕")) {
            assertNull("'$e'", OshiMascot.forEmoji(e))
        }
        assertNull(OshiMascot.forEmoji(null))
    }

    @Test
    fun `picker has all 20, the quick six first`() {
        assertEquals(20, OshiMascot.pickerOrder.size)
        assertEquals(20, OshiMascot.pickerOrder.map { it.name }.toSet().size)
        assertEquals(OshiMascot.reactionBar, OshiMascot.pickerOrder.take(6))
    }

    @Test
    fun `MASCOT_EMOJI is the manifest's canonical emoji, byte for byte`() {
        val manifest = JSONObject(String(OshiMascot.classpathBytes(OshiMascot.MANIFEST)!!, Charsets.UTF_8))
            .getJSONArray("animations")
        val fromManifest = (0 until manifest.length()).map { manifest.getJSONObject(it).getString("emoji") }
        assertEquals(fromManifest, OshiMascot.all.map { it.emoji })
        // ReactionPayload's escaped copy is the same list, in the same order.
        assertEquals(fromManifest, ReactionPayload.MASCOT_EMOJI)
        // What is SENT is the canonical emoji passed through the compat table.
        assertEquals(fromManifest.map { ReactionPayload.mascotWireEmoji(it) }, OshiMascot.all.map { OshiMascot.wireEmoji(it) })
        // ❤️ keeps its U+FE0F: that is iOS's `heart` raw value exactly.
        assertEquals("❤️", OshiMascot.named("love")!!.emoji)
        assertTrue(OshiMascot.named("love")!!.emoji in ReactionPayload.IOS_EMOJI)
    }

    @Test
    fun `every mascot is sendable, and the payload carries exactly its wire emoji`() {
        for (m in OshiMascot.all) {
            val wire = ReactionPayload.encode("m-1", OshiMascot.wireEmoji(m), "KEY=", true, 1_770_000_000_000L)
            assertTrue(wire.startsWith(ControlPrefix.REACTION))
            val decoded = ReactionPayload.decode(wire)!!
            assertEquals(OshiMascot.wireEmoji(m), decoded.emoji)
            // And what comes back is drawn as the same mascot.
            assertEquals(m.name, OshiMascot.forEmoji(decoded.emoji)?.name)
            assertTrue(decoded.isAdding)
        }
    }

    @Test
    fun `iOS's set is untouched and anything outside both lists is still refused`() {
        assertEquals(21, ReactionPayload.IOS_EMOJI.size)
        for (e in listOf("😀", "♥", "❤", "👍🏽", "🤣")) {
            val err = runCatching { ReactionPayload.encode("m", e, "KEY=", true, 1L) }.exceptionOrNull()
            assertTrue("'$e' should be refused, got $err", err is IllegalArgumentException)
        }
        // The nine mascot emoji iOS 1.0.43 does not know — written down, not discovered later.
        val notOnIos = ReactionPayload.MASCOT_EMOJI.filterNot { it in ReactionPayload.IOS_EMOJI }
        assertEquals(listOf("😭", "😡", "🙏", "😍", "😎", "😴", "👋", "🫣", "🥲"), notOnIos)
    }

    @Test
    fun `a malformed manifest entry is skipped, never fatal`() {
        val raw = """{"animations":[{"name":"a","emoji":"😀"},{"emoji":"x"},7,{"name":"b","emoji":"😃","aliases":["😄",""]}]}"""
        val list = OshiMascot.parse(raw.toByteArray())
        assertEquals(listOf("a", "b"), list.map { it.name })
        assertEquals(listOf("😄"), list[1].aliases)
        assertEquals(emptyList<OshiMascot.Mascot>(), OshiMascot.parse("not json".toByteArray()))
        assertEquals(emptyList<OshiMascot.Mascot>(), OshiMascot.parse(null))
    }

    @Test
    fun `an alias never steals a canonical key`() {
        val a = OshiMascot.Mascot("a", "😀", listOf("😃"), "a", 20, 1, 50, 0)
        val b = OshiMascot.Mascot("b", "😃", emptyList(), "b", 20, 1, 50, 0)
        assertEquals("b", OshiMascot.index(listOf(a, b))["😃"]?.name)
    }
}
