package com.oshi.desktop.store

import com.oshi.desktop.msg.ProfileUpdateWire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivacyPreferencesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `defaults are the phones' defaults, and a corrupt file reads as them`() {
        val d = PrivacyPreferences.Values()
        assertTrue(d.lastSeen); assertTrue(d.profilePhotoVisible)
        assertFalse(d.callsFromContactsOnly); assertFalse(d.blockForwardCopy)
        val f = tmp.newFile("p.json").apply { writeText("{not json") }
        assertEquals(d, PrivacyPreferences.read(f))
        assertEquals(d, PrivacyPreferences.read(tmp.root.resolve("absent.json")))
    }

    @Test
    fun `values round-trip under the iOS key names`() {
        val f = tmp.root.resolve("p.json")
        val v = PrivacyPreferences.Values(lastSeen = false, profilePhotoVisible = false, callsFromContactsOnly = true, blockForwardCopy = true)
        PrivacyPreferences.write(f, v)
        assertEquals(v, PrivacyPreferences.read(f))
        val o = JSONObject(f.readText())
        assertEquals(setOf("lastSeenEnabled", "profilePhotoVisible", "callsFromContactsOnly", "blockForwardAndCopy"), o.keySet())
    }

    @Test
    fun `last seen goes on the wire in seconds when on and as 0 when off`() {
        val on = PrivacyPreferences.Values(lastSeen = true)
        val off = PrivacyPreferences.Values(lastSeen = false)
        assertEquals(1_790_000_000.5, PrivacyPreferences.lastSeenWireValue(1_790_000_000_500L, on), 0.0)
        assertEquals(0.0, PrivacyPreferences.lastSeenWireValue(1_790_000_000_500L, off), 0.0)
        val wireOn = JSONObject(ProfileUpdateWire.encode("Hugo", lastSeen = 1_790_000_000.5).substringAfter('{').let { "{$it" })
        assertEquals(1_790_000_000.5, wireOn.getDouble("lastSeen"), 0.0)
        val wireOff = JSONObject(ProfileUpdateWire.encode("Hugo", lastSeen = 0.0).substringAfter('{').let { "{$it" })
        // The key is always present: iOS decodes it as required.
        assertEquals(0, wireOff.getInt("lastSeen"))
    }

    @Test
    fun `a hidden profile photo is not handed to the sender`() {
        assertNull(PrivacyPreferences.outgoingProfilePhoto("iVBOR", PrivacyPreferences.Values(profilePhotoVisible = false)))
        assertEquals("iVBOR", PrivacyPreferences.outgoingProfilePhoto("iVBOR", PrivacyPreferences.Values()))
    }

    @Test
    fun `contacts-only lets a known caller ring and silences a stranger`() {
        val known = setOf("alice")
        val on = PrivacyPreferences.Values(callsFromContactsOnly = true)
        assertTrue(PrivacyPreferences.mayRing("alice", { it in known }, on))
        assertFalse(PrivacyPreferences.mayRing("mallory", { it in known }, on))
        assertTrue(PrivacyPreferences.mayRing("mallory", { it in known }, PrivacyPreferences.Values()))
    }
}
