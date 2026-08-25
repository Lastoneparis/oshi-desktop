package com.oshi.desktop.sync

import com.oshi.desktop.sync.SyncProtocol.SyncKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * The wire shapes of PARITY.md row 0.24, asserted against the SHIPPED source rather than
 * against this client's own encoder.
 *
 * The path-key vectors are Android's own, lifted from
 * `OSHI-Android/app/src/test/java/com/oshi/messenger/SettingsSyncWireFormatTest.kt` — the
 * same three inputs, the same three outputs, plus its `percent encoding would target a
 * different storage slot` assertion, which is the one that matters: the two path encodings
 * in this protocol are DIFFERENT FUNCTIONS and mixing them produces no error at all, just
 * two devices syncing happily to two slots.
 */
class SyncWireFormatTest {

    /** A real base64 identity shape: `+`, `/` and `=` all present. */
    private val KEY = "kr/6aAArDrl91TOXf/bjv2u1SiRf+H8gIi8BBgPYpi0="

    // ────────────────────────────────────────────── path keys: two different functions

    /** Android `SettingsSyncWireFormatTest.sync route key is base64url with padding stripped`. */
    @Test
    fun `legacy path key is base64url with padding stripped`() {
        assertEquals("ab-cd_ef", SyncProtocol.legacyPathKey("ab+cd/ef"))
        assertEquals("abcd", SyncProtocol.legacyPathKey("abcd=="))
        assertEquals("MC4wLTEyMw", SyncProtocol.legacyPathKey("MC4wLTEyMw=="))
    }

    /** `V2Client+Sync.swift:138-140` — percent-encode everything non-alphanumeric. */
    @Test
    fun `v2 path key percent encodes plus slash and equals`() {
        assertEquals("ab%2Bcd%2Fef", SyncProtocol.v2PathKey("ab+cd/ef"))
        assertEquals("abcd%3D%3D", SyncProtocol.v2PathKey("abcd=="))
        assertFalse("the V2 key must keep its padding", SyncProtocol.v2PathKey(KEY).endsWith("pi0"))
    }

    /**
     * Android's own guard, restated across the two encodings this client has to keep apart
     * (`SettingsSyncWireFormatTest.percent encoding would target a different storage slot`).
     * If these ever agree, one archive is writing into the other's slot.
     */
    @Test
    fun `the two path encodings are not interchangeable`() {
        assertFalse(
            "V2 percent-encoding and legacy base64url must not coincide",
            SyncProtocol.v2PathKey(KEY) == SyncProtocol.legacyPathKey(KEY)
        )
        assertFalse(SyncProtocol.legacyPathKey(KEY) == URLEncoder.encode(KEY, "UTF-8"))
    }

    /**
     * `URLEncoder` is NOT a stand-in for [SyncProtocol.v2PathKey], and the reason is worth a
     * test of its own because the first half of it looks like the opposite.
     *
     * **On the base64 alphabet the two agree.** A standard base64 string is
     * `A-Za-z0-9+/=`, and `URLEncoder` escapes all three of `+`, `/` and `=`, so for the
     * key shape this protocol actually uses they produce the same bytes — asserted here so
     * nobody "discovers" it later and concludes the hand-rolled encoder is redundant.
     *
     * **It is redundant only for input that is genuinely base64.** `URLEncoder` leaves
     * `-`, `_`, `.` and `*` alone and encodes a space as `+`, which
     * [com.oshi.desktop.DesktopV2Signer]'s doc calls "the single most copy-sensitive
     * function in the protocol". A key that arrived in the base64URL spelling — which
     * [com.oshi.desktop.block.BlockPolicy] exists because contacts do — would percent-encode
     * to a DIFFERENT string under the two, and a space in a key would become a `+` that the
     * server decodes back as a space and never as the `+` it was.
     */
    @Test
    fun `URLEncoder agrees on base64 and diverges on everything else`() {
        assertEquals(
            "for a genuinely base64 key the two encoders coincide",
            URLEncoder.encode(KEY, "UTF-8"), SyncProtocol.v2PathKey(KEY)
        )
        // base64URL spelling: '-' and '_' survive URLEncoder untouched.
        val urlSafe = KEY.replace('+', '-').replace('/', '_').trimEnd('=')
        assertFalse(
            "URLEncoder leaves - and _ alone; the shipped encoder escapes them",
            URLEncoder.encode(urlSafe, "UTF-8") == SyncProtocol.v2PathKey(urlSafe)
        )
        assertEquals("%2D", SyncProtocol.v2PathKey("-"))
        assertEquals("%5F", SyncProtocol.v2PathKey("_"))
        // A space must become %20, never '+'.
        assertEquals("%20", SyncProtocol.v2PathKey(" "))
        assertEquals("+", URLEncoder.encode(" ", "UTF-8"))
    }

    // ────────────────────────────────────────────── the V2 push body

    /** Key order is `V2.SyncItem`'s property order (`V2SyncModels.swift:43-49`). */
    @Test
    fun `push body key order matches the swift struct`() {
        val body = SyncProtocol.encodePushBody(
            listOf(SyncProtocol.SyncItem("contact:abc", "contact", "Y2lwaGVy", "dev-1", 1_700_000_000_123L))
        ).toString(Charsets.UTF_8)
        assertEquals(
            """{"items":[{"itemId":"contact:abc","kind":"contact","ciphertext":"Y2lwaGVy","deviceId":"dev-1","ts":1700000000123}]}""",
            body
        )
    }

    /**
     * `ts` is Unix MILLIS, not Apple-epoch seconds — `V2SyncModels.swift:48,54`
     * (`Int64(Date().timeIntervalSince1970 * 1000)`). This is the epoch that is one nesting
     * level apart from the Apple-epoch seconds inside a legacy record body; see
     * [SyncProtocol]'s EPOCHS section.
     */
    @Test
    fun `push body ts is unix millis and is emitted as an integer`() {
        val body = SyncProtocol.encodePushBody(
            listOf(SyncProtocol.SyncItem("msg:1", "msg", "eA", "d", 1_786_961_472_345L))
        ).toString(Charsets.UTF_8)
        assertTrue("no exponent, no fractional part: $body", body.contains("\"ts\":1786961472345}"))
        // The Apple-epoch value for the same instant is three orders of magnitude smaller.
        assertFalse(body.contains("808654272"))
    }

    @Test
    fun `push body refuses an empty item list`() {
        val e = runCatching { SyncProtocol.encodePushBody(emptyList()) }.exceptionOrNull()
        assertTrue("V2Client+Sync.swift:80 refuses this too", e is IllegalArgumentException)
    }

    // ────────────────────────────────────────────── itemId namespacing

    /** `V2SyncManager.swift:311` — `"\(kind.rawValue):\(itemId)"`. */
    @Test
    fun `item id is namespaced by kind`() {
        assertEquals("contact:abc", SyncProtocol.namespacedItemId(SyncKind.CONTACT, "abc"))
        assertEquals("read:abc", SyncProtocol.namespacedItemId(SyncKind.READ_STATE, "abc"))
        // Two kinds, one logical id: distinct on the wire, so the server's global itemId
        // space cannot silently swallow the second push as a duplicate.
        assertFalse(
            SyncProtocol.namespacedItemId(SyncKind.CONTACT, "abc") ==
                SyncProtocol.namespacedItemId(SyncKind.MESSAGE, "abc")
        )
    }

    /**
     * GUARD — `SyncProtocol.encodeKind` refuses [SyncKind.UNKNOWN].
     *
     * `V2SyncModels.swift:123` documents the sentinel as "never pushed" and nothing on iOS
     * enforces it. Here it can arrive from [SyncKind.fromWire] on the receive path, so an
     * applier that echoes what it read would launder a peer's unrecognised record into one
     * this client claims to have authored, under a kind spelled `unknown`.
     */
    @Test
    fun `the unknown kind sentinel is refused on the emit path`() {
        val direct = runCatching { SyncProtocol.encodeKind(SyncKind.UNKNOWN) }.exceptionOrNull()
        assertTrue("encodeKind must refuse UNKNOWN, got $direct", direct is IllegalArgumentException)
        val viaItemId = runCatching { SyncProtocol.namespacedItemId(SyncKind.UNKNOWN, "x") }.exceptionOrNull()
        assertTrue("the refusal must also cover the itemId path", viaItemId is IllegalArgumentException)
        // And the sentinel is still produced on the DECODE side — that half must not change.
        assertEquals(SyncKind.UNKNOWN, SyncKind.fromWire("some-future-kind"))
        assertEquals("some-future-kind", SyncProtocol.parsePullResult(
            """{"items":[{"seq":1,"itemId":"x","kind":"some-future-kind","ciphertext":"YQ","deviceId":"d","ts":1}],"maxSeq":1}"""
        )!!.items[0].kind)
    }

    @Test
    fun `kind wire strings match V2SyncModels`() {
        assertEquals("msg", SyncKind.MESSAGE.wire)
        assertEquals("read", SyncKind.READ_STATE.wire)
        assertEquals("contact", SyncKind.CONTACT.wire)
        assertEquals("group", SyncKind.GROUP.wire)
        assertEquals("file", SyncKind.FILE.wire)
    }

    // ────────────────────────────────────────────── the legacy envelope

    /** `MultiDeviceSyncManager.swift:1021-1024` / `.kt:146-151` — `{deviceId, data}`. */
    @Test
    fun `legacy upload envelope has exactly deviceId and data`() {
        val o = JSONObject(
            SyncProtocol.encodeLegacyUploadBody("dev-9", "Y2lwaGVy").toString(Charsets.UTF_8)
        )
        assertEquals(setOf("deviceId", "data"), o.keySet())
        assertEquals("dev-9", o.getString("deviceId"))
        assertEquals("Y2lwaGVy", o.getString("data"))
        // NOT `encryptedData` — that is the peer-to-peer envelope's field name (.kt:1412-1425)
        // and neither reads the other's.
        assertFalse(o.has("encryptedData"))
    }

    /** `{"success":true,"data":null}` is "nothing stored", not an error (`.kt:1215-1218`). */
    @Test
    fun `legacy pull treats a null data field as an empty archive`() {
        assertNull(SyncProtocol.parseLegacyPullData("""{"success":true,"data":null}"""))
        assertNull(SyncProtocol.parseLegacyPullData("""{"success":true,"data":"null"}"""))
        assertNull(SyncProtocol.parseLegacyPullData("""{"success":true}"""))
        assertEquals("Y2lwaGVy", SyncProtocol.parseLegacyPullData("""{"success":true,"data":"Y2lwaGVy"}"""))
    }

    @Test
    fun `only the five reachable legacy kinds exist`() {
        // The seven device-registry routes iOS calls 404 on the live server
        // (MultiDeviceSyncManager.kt:93-113). They are absent here on purpose.
        assertEquals(
            listOf("messages", "groups", "contacts", "groupMessages", "profile"),
            SyncProtocol.LegacyKind.entries.map { it.wire }
        )
    }

    // ────────────────────────────────────────────── response parsing

    /** A failed pull is a failure, never an empty page — PARITY.md row 0.10's rule. */
    @Test
    fun `an unreadable pull body is null and not an empty page`() {
        assertNull(SyncProtocol.parsePullResult("not json"))
        assertNull(SyncProtocol.parsePullResult("""{"maxSeq":7}"""))
        val empty = SyncProtocol.parsePullResult("""{"items":[],"maxSeq":7}""")!!
        assertTrue(empty.items.isEmpty())
        assertEquals(7, empty.maxSeq)
    }

    /** One malformed ITEM costs that item, not the page. */
    @Test
    fun `a malformed item is skipped and the rest of the page survives`() {
        val page = SyncProtocol.parsePullResult(
            """{"items":[
                 {"seq":1,"itemId":"a","kind":"msg","ciphertext":"YQ","deviceId":"d","ts":1},
                 {"itemId":"no-seq","kind":"msg","ciphertext":"YQ"},
                 {"seq":3,"itemId":"c","kind":"msg","ciphertext":"","deviceId":"d","ts":3},
                 {"seq":4,"itemId":"d","kind":"msg","ciphertext":"YQ","deviceId":"d","ts":4}
               ],"maxSeq":4}"""
        )!!
        assertEquals(listOf("a", "d"), page.items.map { it.itemId })
        assertEquals(4, page.maxSeq)
    }
}
