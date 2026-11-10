package com.oshi.desktop.msg

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __OSHI_SMILE_REACTION_2026_11_09__ */
class OshiReactionTest {

    @Test
    fun `wire value is U+2B55 U+1F60A`() {
        val expected = byteArrayOf(
            0xe2.toByte(), 0xad.toByte(), 0x95.toByte(),
            0xf0.toByte(), 0x9f.toByte(), 0x98.toByte(), 0x8a.toByte(),
        )
        assertArrayEquals(expected, OshiReaction.OSHI_SMILE.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `only the exact value is the OSHI mark`() {
        for (v in listOf("⭕", "😊", "♥", "👍", "😂", "⭕ 😊", "😊⭕", "⭕️😊", "", null)) {
            assertFalse("'$v' drawn as the OSHI O", OshiReaction.isOshiSmile(v))
        }
        assertTrue(OshiReaction.isOshiSmile("⭕😊"))
    }

    /**
     * Why the bubble draws per KEY: two DIFFERENT reactions, "⭕" and "😊", join to
     * exactly the OSHI bytes in the old concatenated string.
     */
    @Test
    fun `two separate reactions joined look like the OSHI one - so keys are kept apart`() {
        val keys = listOf("😊", "⭕").sorted()
        assertEquals(OshiReaction.OSHI_SMILE, keys.joinToString(""))
        assertTrue(keys.none(OshiReaction::isOshiSmile))
        val model = File("src/main/kotlin/com/oshi/desktop/ui/state/ChatShellModel.kt").readText()
        assertTrue(model.contains("reactionKeys = m.reactions.keys.sorted()"))
    }
}
