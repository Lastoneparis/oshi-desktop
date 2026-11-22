package com.oshi.desktop.msg

import com.oshi.desktop.store.ProfileLinksStore
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_PROFILE_LINKS_2026_10_29__ `🔗LINKS_VISIBILITY🔗`, pinned to a payload written by
 * hand from `OSHI/ProfileLinksManager.swift:13-27,343-350` and
 * `OSHI-Android/.../ProfileLinksManager.kt:71-89`.
 */
class ProfileLinksTest {

    private val dir: File = Files.createTempDirectory("oshi-links").toFile()
    private val store = ProfileLinksStore(File(dir, ProfileLinksStore.FILE_NAME))

    @After fun tearDown() { dir.deleteRecursively() }

    private val peer = "PEER/phone+key/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb="

    private val iosOn =
        "🔗LINKS_VISIBILITY🔗{\"visible\":true,\"links\":{" +
            "\"websiteURL\":\"https://example.org\",\"githubURL\":\"https://github.com/someone\"," +
            "\"waypathID\":\"someone\"}}"

    @Test
    fun `an iPhone's payload decodes field for field`() {
        val v = ProfileLinksPayload.decode(iosOn)!!
        assertTrue(v.visible)
        assertEquals("https://example.org", v.links.websiteUrl)
        assertEquals("https://github.com/someone", v.links.githubUrl)
        assertEquals("someone", v.links.waypathId)
        assertNull("an absent field is null, never an empty string", v.links.twitterUrl)
        assertTrue(v.links.hasAny)
        assertEquals(listOf("Website", "GitHub", "Waypath"), v.links.filled.map { it.first })
    }

    /** A withdrawal carries NO links, which is iOS's `links: visible ? myLinks : nil`. */
    @Test
    fun `a withdrawal decodes with no links rather than failing`() {
        val off = "🔗LINKS_VISIBILITY🔗{\"visible\":false}"
        val v = ProfileLinksPayload.decode(off)!!
        assertFalse(v.visible)
        assertFalse(v.links.hasAny)
    }

    /** A blank field is DROPPED, not stored as "" — Android's takeIf on every field. */
    @Test
    fun `blank fields are dropped so an empty row is never drawn`() {
        val blank = "🔗LINKS_VISIBILITY🔗{\"visible\":true,\"links\":{\"websiteURL\":\"\",\"githubURL\":\"  \"}}"
        val v = ProfileLinksPayload.decode(blank)!!
        assertNull(v.links.websiteUrl)
        assertNull(v.links.githubUrl)
        assertFalse("an empty string would have made this true", v.links.hasAny)
    }

    /** A peer-chosen string may not be unbounded: the cap is applied on the way IN. */
    @Test
    fun `a hostile length is capped at the boundary`() {
        val long = "x".repeat(5000)
        val wire = ControlPrefix.LINKS_VISIBILITY + JSONObject()
            .put("visible", true)
            .put("links", JSONObject().put("websiteURL", long)).toString()
        val v = ProfileLinksPayload.decode(wire)!!
        assertEquals(ProfileLinksPayload.MAX_LINK_LENGTH, v.links.websiteUrl!!.length)
    }

    @Test
    fun `what we emit carries the phones' keys and withholds links when hiding`() {
        val links = ProfileLinksPayload.Links(websiteUrl = "https://a.example", twitterUrl = "https://x.com/a")
        val on = JSONObject(ControlPrefix.strip(ProfileLinksPayload.encode(true, links)))
        assertEquals(true, on.getBoolean("visible"))
        assertEquals(
            setOf("websiteURL", "twitterURL"),
            on.getJSONObject("links").keys().asSequence().toSet(),
        )
        val off = JSONObject(ControlPrefix.strip(ProfileLinksPayload.encode(false, links)))
        assertFalse(
            "a withdrawal must not hand over the strings it is withdrawing",
            off.has("links"),
        )
    }

    /** `updatedAt` is iOS's Apple-epoch Date and is deliberately not read. */
    @Test
    fun `an updatedAt field is ignored rather than parsed in a second epoch`() {
        val withDate = ControlPrefix.LINKS_VISIBILITY + JSONObject()
            .put("visible", true)
            .put("links", JSONObject().put("websiteURL", "https://a.example").put("updatedAt", 780000000.0))
            .toString()
        val v = ProfileLinksPayload.decode(withDate)!!
        assertEquals("https://a.example", v.links.websiteUrl)
        // Nothing to assert about the stamp, which is the point: it is not in [Links] at all,
        // so no code here can read a 2001-based number as a 1970-based one.
        assertEquals(1, v.links.filled.size)
    }

    @Test
    fun `the refusals`() {
        assertNull(ProfileLinksPayload.decode("hello"))
        assertNull(ProfileLinksPayload.decode(ControlPrefix.LINKS_VISIBILITY + "{nope"))
        assertFalse(ProfileLinksPayload.isVisibilityMessage(null))
    }

    // ------------------------------------------------------------------ the store

    /**
     * THE TWO MAPS. A withdrawal must stop the links being SHOWN without forgetting them, or
     * a contact who toggles off and on again shows nothing until their next payload lands.
     */
    @Test
    fun `withdrawing hides the links without losing them`() {
        assertTrue(store.apply(peer, ProfileLinksPayload.decode(iosOn)!!))
        assertTrue(store.isVisible(peer))
        assertEquals("https://example.org", store.shownLinks(peer)!!.websiteUrl)

        assertTrue(store.apply(peer, ProfileLinksPayload.Visibility(false, ProfileLinksPayload.Links())))
        assertFalse(store.isVisible(peer))
        assertNull("nothing may be shown while hidden", store.shownLinks(peer))
        assertEquals(
            "the links must be remembered so a second 'on' has something to draw",
            "https://example.org", store.linksOf(peer)!!.websiteUrl,
        )

        assertTrue(store.apply(peer, ProfileLinksPayload.Visibility(true, ProfileLinksPayload.Links())))
        assertEquals("https://example.org", store.shownLinks(peer)!!.websiteUrl)
    }

    /** A redelivery changes nothing, so a caller can skip the repaint. */
    @Test
    fun `an identical payload reports no change`() {
        val v = ProfileLinksPayload.decode(iosOn)!!
        assertTrue(store.apply(peer, v))
        assertFalse("the mesh redelivers routinely", store.apply(peer, v))
    }

    @Test
    fun `links survive a restart and keep their cap`() {
        store.apply(peer, ProfileLinksPayload.decode(iosOn)!!)
        val fresh = ProfileLinksStore(File(dir, ProfileLinksStore.FILE_NAME))
        assertEquals("https://example.org", fresh.shownLinks(peer)!!.websiteUrl)
        assertTrue(fresh.isVisible(peer))
    }

    @Test
    fun `a corrupt file reads as nothing shared`() {
        File(dir, ProfileLinksStore.FILE_NAME).writeText("{ not json", Charsets.UTF_8)
        val fresh = ProfileLinksStore(File(dir, ProfileLinksStore.FILE_NAME))
        assertNull(fresh.shownLinks(peer))
        assertTrue(fresh.all().isEmpty())
    }

    /**
     * Both base64 spellings of one key are one contact — tested with a REAL key.
     *
     * This test first used the readable `PEER/phone+key/…` fixture above and failed, and the
     * function was right: `GroupIdentity.canonicalIdentity` is iOS's
     * `MessageManager.canonicalIdentity` transliterated including its failure mode, and it
     * returns the key UNCHANGED when the folded form is not decodable base64. That fixture is
     * 45 characters, pads to three `=`, and is not valid base64, so neither spelling folds —
     * correctly, because mangling a string it cannot parse would be worse.
     *
     * A production key is a real 32-byte public key, so the fold is tested with one. Recorded
     * rather than quietly swapped: the limitation is real, and a reader who assumes the fold
     * is unconditional will be wrong about exactly the keys that are not base64.
     */
    @Test
    fun `the contact key is folded for a real base64 key`() {
        val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        store.apply(key, ProfileLinksPayload.decode(iosOn)!!)
        val urlSafe = key.replace('+', '-').replace('/', '_').trimEnd('=')
        assertEquals("https://example.org", store.shownLinks(urlSafe)!!.websiteUrl)
        assertTrue("and the padded spelling too", store.isVisible(key))
        assertEquals("one contact, not three", 1, store.all().size)
    }
}
