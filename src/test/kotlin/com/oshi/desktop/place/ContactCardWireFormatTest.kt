package com.oshi.desktop.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contact card, against the SHIPPED bytes.
 *
 * iOS's `ContactCard` struct and its bare `JSONDecoder`
 * (`OSHI/MediaManager.swift:867-928`); Android's `ContactCardWire`
 * (`data/model/ContactCardWire.kt`), whose doc comment records both directions of the same
 * bug — a missing `timestamp` that drew a blank placeholder on every iPhone, and a strict
 * reader that let iOS's extra key sink the card back the other way.
 */
class ContactCardWireFormatTest {

    private val nowMs = 1_717_196_400_000L
    private val nowApple = 738_889_200.0
    private val key = "mF6l0GJ8yqQ2vN1xW7bTz3Kd9hRpAoLcE5sUiY4nXgM="

    // ------------------------------------------------------------ encode side

    @Test
    fun `emits exactly the three keys iOS declares, in Swift declaration order`() {
        assertEquals(
            """{"publicKey":"$key","alias":"Alice","timestamp":738889200}""",
            ContactCardPayload.encode(key, "Alice", nowMs),
        )
    }

    @Test
    fun `timestamp is always emitted — the key whose absence blanked every iPhone`() {
        // Android's ContactPayload never carried it, so an Android→iPhone card threw
        // keyNotFound inside the try? at MediaMessageView.swift:65 (ContactCardWire.kt:30-35).
        val json = ContactCardPayload.encode(key, null, nowMs)
        assertTrue(json.contains(""""timestamp":"""))
        assertEquals("""{"publicKey":"$key","timestamp":738889200}""", json)
    }

    @Test
    fun `timestamp is a NUMBER of Apple-epoch seconds, never an ISO-8601 string`() {
        // A bare JSONDecoder is .deferredToDate: handing it "2026-08-24T09:00:00Z" throws
        // typeMismatch and sinks the card exactly as the missing key did.
        val json = ContactCardPayload.encode(key, null, nowMs)
        assertTrue(json.contains(""""timestamp":738889200"""))
        assertTrue(!json.contains("T09:") && !json.contains("Z\""))
    }

    @Test
    fun `a blank alias is omitted rather than sent empty`() {
        assertEquals(
            """{"publicKey":"$key","timestamp":738889200}""",
            ContactCardPayload.encode(key, "   ", nowMs),
        )
    }

    @Test
    fun `avatarUrl is never emitted — iOS has never heard of it`() {
        val decoded = ContactCardPayload.decode(
            """{"publicKey":"$key","avatarUrl":"https://x/y.png","timestamp":$nowApple}""",
        )!!
        assertEquals("https://x/y.png", decoded.avatarUrl)
        assertTrue(!ContactCardPayload.encode(key, null, nowMs).contains("avatarUrl"))
    }

    // ------------------------------------------------------- the publicKey guard

    @Test
    fun `a card with no publicKey is refused`() {
        // GUARD: ContactCardPayload.CARD_REQUIRES_PUBLIC_KEY. One of the rare places all
        // three clients already agree — iOS decodes publicKey non-optionally, Android
        // returns null on a blank one (ContactCardWire.kt:118-119).
        val e = assertThrows(IllegalArgumentException::class.java) {
            ContactCardPayload.encode("   ", "Alice", nowMs)
        }
        assertTrue(e.message!!.contains(ContactCardPayload.CARD_REQUIRES_PUBLIC_KEY))
        assertNull(ContactCardPayload.decode("""{"alias":"Alice","timestamp":$nowApple}"""))
        assertNull(ContactCardPayload.decode("""{"publicKey":"","timestamp":$nowApple}"""))
    }

    // ------------------------------------------------------------ decode side

    @Test
    fun `decodes an iOS card, converting its Apple-epoch timestamp`() {
        val p = ContactCardPayload.decode("""{"publicKey":"$key","alias":"Alice","timestamp":$nowApple}""")
        assertNotNull(p)
        assertEquals(key, p!!.publicKey)
        assertEquals("Alice", p.alias)
        assertEquals(nowMs, p.timestampMs)
        assertEquals(nowApple, p.appleTimestamp!!, 0.0)
    }

    @Test
    fun `an unknown key from a newer peer degrades a field, never the card`() {
        // The strict-reader half of the bug: Json.Default has ignoreUnknownKeys = false,
        // so iOS's extra timestamp used to blow up the whole decode and the card fell
        // through to a raw-text bubble (ContactCardWire.kt:45-49).
        val p = ContactCardPayload.decode(
            """{"publicKey":"$key","alias":"Alice","timestamp":$nowApple,"nickname":"Al"}""",
        )
        assertNotNull(p)
        assertEquals("Alice", p!!.alias)
    }

    @Test
    fun `a card with no timestamp still decodes — we are lenient where iOS is not`() {
        // The asymmetry is deliberate: refusing to READ what a shipped Android build sends
        // would lose the card, whereas refusing to WRITE it is what keeps iPhones working.
        val p = ContactCardPayload.decode("""{"publicKey":"$key"}""")
        assertNotNull(p)
        assertNull(p!!.timestampMs)
    }

    @Test
    fun `a legacy card carrying Unix millis in timestamp is read as millis`() {
        val p = ContactCardPayload.decode("""{"publicKey":"$key","timestamp":$nowMs}""")!!
        assertEquals(nowMs, p.timestampMs)
    }

    // ---------------------------------------------------------------- vCard

    @Test
    fun `decodes the vCard the share sheet produces`() {
        // ContactShareManager.exportToVCard (kt:34-40). The identity lives in NOTE and
        // nowhere else.
        val vcard = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Alice Example
            N:Example;Alice;;;
            NOTE:OSHI Public Key: $key
            END:VCARD
        """.trimIndent()
        val p = ContactCardPayload.decode(vcard)
        assertNotNull(p)
        assertEquals(key, p!!.publicKey)
        assertEquals("Alice Example", p.alias)
        assertNull(p.timestampMs)
    }

    @Test
    fun `a vCard with no OSHI key note is not a contact card`() {
        val vcard = "BEGIN:VCARD\nVERSION:3.0\nFN:Alice\nEND:VCARD"
        assertNull(ContactCardPayload.decode(vcard))
    }

    @Test
    fun `neither JSON nor vCard yields null so a caller can fall back to text`() {
        assertNull(ContactCardPayload.decode("Meet me at the station"))
        assertNull(ContactCardPayload.decode(""))
        assertNull(ContactCardPayload.decode("{not json"))
    }

    // ---------------------------------------------------------------- display

    @Test
    fun `displayName is the alias, else iOS's six-dot-dot-four elision`() {
        // MediaManager.swift:918-928.
        assertEquals("Alice", ContactCardPayload(publicKey = key, alias = "Alice").displayName)
        assertEquals("mF6l0G...XgM=", ContactCardPayload(publicKey = key).displayName)
        assertEquals("short", ContactCardPayload(publicKey = "short").displayName)
    }
}
