package com.oshi.desktop.store

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __O_BUBBLE_MASCOT_2026_11_16__ "Animated OSHI reactions" — default on. */
class ReactionPreferencesTest {

    private fun file() = Files.createTempDirectory("oshi-reaction-prefs").toFile()
        .resolve(ReactionPreferences.FILE_NAME)

    @Test
    fun `defaults to animated when nothing was saved`() {
        assertTrue(ReactionPreferences(file()).load().animatedMascots)
    }

    @Test
    fun `off survives a reload, and on again`() {
        val f = file()
        ReactionPreferences(f).save(ReactionPreferences.Values(animatedMascots = false))
        assertFalse(ReactionPreferences(f).load().animatedMascots)
        ReactionPreferences(f).save(ReactionPreferences.Values(animatedMascots = true))
        assertTrue(ReactionPreferences(f).load().animatedMascots)
    }

    @Test
    fun `a corrupt or foreign file falls back to the default`() {
        val f = file()
        f.writeText("{not json")
        assertTrue(ReactionPreferences(f).load().animatedMascots)
        f.writeText("""{"somethingElse":false}""")
        assertTrue(ReactionPreferences(f).load().animatedMascots)
    }
}
