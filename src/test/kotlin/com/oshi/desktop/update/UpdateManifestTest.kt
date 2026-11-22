package com.oshi.desktop.update

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/** __DESKTOP_AUTO_UPDATE_2026_11_21__ manifest parsing, versions, and the signature chain. */
class UpdateManifestTest {

    companion object {
        /** The test key `release/update_manifest.py keygen` made for the checked-in fixture. NOT the release key. */
        const val FIXTURE_PUBKEY = "/HmfjSMmAVT4Y1ufGFB2FRX494xps2fUhgdsxU0qX9A="

        fun resource(name: String): ByteArray =
            UpdateManifestTest::class.java.getResourceAsStream("/update/$name")!!.readBytes()

        fun manifestJson(version: String = "1.6.1", host: String = "oshi-messenger.com"): JSONObject = JSONObject()
            .put("schema", 1).put("product", "oshi-desktop").put("version", version)
            .put("files", JSONObject().apply {
                for (k in listOf("exe", "msi", "deb", "rpm")) put(k, JSONObject()
                    .put("url", "https://$host/downloads/OSHI-$version.$k")
                    .put("sha256", "a".repeat(64)).put("size", 1234))
            })

        class Keys {
            val priv = Ed25519PrivateKeyParameters(SecureRandom())
            val pub: ByteArray = priv.generatePublicKey().encoded
            fun sign(msg: ByteArray): ByteArray = Ed25519Signer().run { init(true, priv); update(msg, 0, msg.size); generateSignature() }
            fun sigFile(msg: ByteArray): ByteArray = (Base64.getEncoder().encodeToString(sign(msg)) + "\n").toByteArray()
            fun channel(host: String? = "oshi-messenger.com") = UpdateChannel("https://x/desktop-latest.json", pub, host, isTest = host == null)
        }
    }

    // ------------------------------------------------------------------ versions

    @Test fun `versions compare numerically, not as text`() {
        assertTrue(Version.parse("1.10.0")!! > Version.parse("1.9.9")!!)
        assertTrue(Version.parse("2.0.0")!! > Version.parse("1.99.99")!!)
        assertTrue(Version.parse("1.6.1")!! > Version.parse("1.6.0")!!)
        assertEquals(0, Version.parse("1.6")!!.compareTo(Version.parse("1.6.0")!!))
        assertEquals("1.6.0", Version.parse(" 1.6.0 ").toString())
    }

    @Test fun `anything that is not MAJOR_MINOR_PATCH is not a version`() {
        for (bad in listOf(null, "", "dev", "1.6.0-beta", "v1.6.0", "1..2", "1.2.3.4", "-1.0.0", "1.2.x", "999999.0.0")) {
            assertNull(bad, Version.parse(bad))
        }
    }

    // ------------------------------------------------------------------ parsing

    @Test fun `the fixture the release tool wrote parses`() {
        val m = UpdateManifest.parse(resource("desktop-latest.json"), "oshi-messenger.com")
        assertEquals(Version(9, 8, 7), m.version)
        assertEquals(4, m.files.size)
        assertEquals(10L, m.files[InstallerKind.MSI]!!.size)
        assertEquals("https://oshi-messenger.com/downloads/oshi-desktop_9.8.7-1_amd64.deb", m.files[InstallerKind.DEB]!!.url)
        assertEquals("Démarrage plus rapide.", m.notesFor("fr-CA"))
        assertEquals("Faster startup.", m.notesFor("ja"))
        assertEquals("https://oshi-messenger.com/desktop", m.page)
    }

    @Test fun `notes may be a single string`() {
        val m = UpdateManifest.parse(manifestJson().put("notes", "Hello").toString().toByteArray(), "oshi-messenger.com")
        assertEquals("Hello", m.notesFor("de"))
        assertNull(UpdateManifest.parse(manifestJson().toString().toByteArray(), "oshi-messenger.com").notesFor("en"))
    }

    private fun refuses(j: JSONObject, host: String? = "oshi-messenger.com") {
        try {
            UpdateManifest.parse(j.toString().toByteArray(), host)
            fail("parsed: $j")
        } catch (_: UpdateManifest.ParseException) {}
    }

    @Test fun `what the app acts on is validated strictly`() {
        refuses(manifestJson().put("schema", 2))
        refuses(manifestJson().put("product", "oshi-android"))
        refuses(manifestJson("1.6.1-rc1"))
        refuses(manifestJson().put("files", JSONObject()))
        refuses(manifestJson().also { it.getJSONObject("files").getJSONObject("msi").put("sha256", "abc") })
        refuses(manifestJson().also { it.getJSONObject("files").getJSONObject("msi").put("size", 0) })
        refuses(manifestJson().also { it.getJSONObject("files").getJSONObject("msi").put("size", 5L shl 30) })
        try { UpdateManifest.parse("not json".toByteArray(), null); fail() } catch (_: UpdateManifest.ParseException) {}
        try { UpdateManifest.parse(ByteArray(UpdateManifest.MAX_BYTES + 1) { ' '.code.toByte() }, null); fail() } catch (_: UpdateManifest.ParseException) {}
    }

    @Test fun `the production channel only downloads from https oshi-messenger_com`() {
        refuses(manifestJson(host = "evil.example"))
        refuses(manifestJson().also { it.getJSONObject("files").getJSONObject("deb").put("url", "http://oshi-messenger.com/downloads/x.deb") })
        refuses(manifestJson().also { it.getJSONObject("files").getJSONObject("deb").put("url", "https://oshi-messenger.com.evil.example/x.deb") })
        refuses(manifestJson().also { it.getJSONObject("files").getJSONObject("deb").put("url", "https://user@oshi-messenger.com/x.deb") })
        refuses(manifestJson().also { it.getJSONObject("files").getJSONObject("deb").put("url", "file:///etc/passwd") }, host = null)
        // The test channel (no host pin) takes a loopback http server.
        UpdateManifest.parse(manifestJson(host = "127.0.0.1:8080").toString().replace("https://", "http://").toByteArray(), null)
    }

    // ------------------------------------------------------------------ signature

    @Test fun `the Python release tool's signature verifies here (cross-implementation)`() {
        val ch = UpdateChannel("https://x/m.json", Base64.getDecoder().decode(FIXTURE_PUBKEY), "oshi-messenger.com", isTest = false)
        val r = UpdateCheck.evaluate(resource("desktop-latest.json"), resource("desktop-latest.json.sig"), ch, Version(1, 6, 0))
        assertTrue(r.toString(), r is CheckResult.Available)
        assertEquals(Version(9, 8, 7), (r as CheckResult.Available).manifest.version)
    }

    @Test fun `good signature, newer version - available, same or older - up to date`() {
        val k = Keys()
        val body = manifestJson("1.6.1").toString().toByteArray()
        assertTrue(UpdateCheck.evaluate(body, k.sigFile(body), k.channel(), Version(1, 6, 0)) is CheckResult.Available)
        assertEquals(CheckResult.UpToDate(Version(1, 6, 1)), UpdateCheck.evaluate(body, k.sigFile(body), k.channel(), Version(1, 6, 1)))
        assertEquals(CheckResult.UpToDate(Version(1, 6, 1)), UpdateCheck.evaluate(body, k.sigFile(body), k.channel(), Version(1, 7, 0)))
    }

    @Test fun `missing, malformed or wrong signature is a failed check, never parsed`() {
        val k = Keys()
        val body = manifestJson("1.6.1").toString().toByteArray()
        assertTrue(UpdateCheck.evaluate(body, null, k.channel(), Version(1, 6, 0)) is CheckResult.Failed)
        assertTrue(UpdateCheck.evaluate(body, ByteArray(0), k.channel(), Version(1, 6, 0)) is CheckResult.Failed)
        assertTrue(UpdateCheck.evaluate(body, "not base64!!".toByteArray(), k.channel(), Version(1, 6, 0)) is CheckResult.Failed)
        assertTrue(UpdateCheck.evaluate(body, Base64.getEncoder().encode(ByteArray(64)), k.channel(), Version(1, 6, 0)) is CheckResult.Failed)
        // Signed by a different key.
        assertTrue(UpdateCheck.evaluate(body, Keys().sigFile(body), k.channel(), Version(1, 6, 0)) is CheckResult.Failed)
        assertTrue(UpdateCheck.evaluate(null, k.sigFile(body), k.channel(), Version(1, 6, 0)) is CheckResult.Failed)
    }

    @Test fun `a tampered sha256 or URL breaks the signature`() {
        val k = Keys()
        val body = manifestJson("1.6.1").toString().toByteArray()
        val sig = k.sigFile(body)
        val tamperedSha = String(body).replace("a".repeat(64), "b".repeat(64)).toByteArray()
        val tamperedUrl = String(body).replace("OSHI-1.6.1.msi", "evil.msi").toByteArray()
        val trailingSpace = body + ' '.code.toByte()
        for (t in listOf(tamperedSha, tamperedUrl, trailingSpace)) {
            val r = UpdateCheck.evaluate(t, sig, k.channel(), Version(1, 6, 0))
            assertTrue(r.toString(), r is CheckResult.Failed)
        }
        // The fixture, one hash digit changed.
        val fx = String(resource("desktop-latest.json")).replace("15459d13", "15459d14").toByteArray()
        val ch = UpdateChannel("https://x/m.json", Base64.getDecoder().decode(FIXTURE_PUBKEY), "oshi-messenger.com", isTest = false)
        assertTrue(UpdateCheck.evaluate(fx, resource("desktop-latest.json.sig"), ch, Version(1, 6, 0)) is CheckResult.Failed)
    }

    @Test fun `a validly signed manifest pointing off-site is still refused on the production channel`() {
        val k = Keys()
        val body = manifestJson("1.6.1", host = "evil.example").toString().toByteArray()
        assertTrue(UpdateCheck.evaluate(body, k.sigFile(body), k.channel(), Version(1, 6, 0)) is CheckResult.Failed)
    }

    @Test fun `verify never throws on garbage`() {
        assertFalse(UpdateSignature.verify(ByteArray(3), ByteArray(0), ByteArray(64)))
        assertFalse(UpdateSignature.verify(ByteArray(32), ByteArray(0), ByteArray(10)))
        assertFalse(UpdateSignature.verify(ByteArray(32) { 0xff.toByte() }, ByteArray(5), ByteArray(64) { 0xff.toByte() }))
        assertNull(UpdateSignature.decodeSigFile(ByteArray(2000)))
    }

    // ------------------------------------------------------------------ channel

    @Test fun `the production key is the one the release tool prints`() {
        assertEquals(32, UpdateChannel.PRODUCTION.publicKey.size)
        assertEquals("3ca413091fbd83cbea3e186c7cf3300a846c6c6d889f90985c82788c7f4b0857", UpdateSignature.fingerprint(UpdateChannel.PRODUCTION.publicKey))
        assertEquals("https://oshi-messenger.com/downloads/desktop-latest.json.sig", UpdateChannel.PRODUCTION.signatureUrl)
        assertFalse(UpdateChannel.PRODUCTION.isTest)
    }

    @Test fun `the real 1_6_0 manifest, signed with the release key, verifies on the production channel`() {
        // release/sign_update_manifest.sh v1.6.0 (dry run) — proves the offline key and the
        // compiled-in key are one pair.
        val body = resource("production-1.6.0.json")
        val sig = resource("production-1.6.0.json.sig")
        assertEquals(CheckResult.UpToDate(Version(1, 6, 0)), UpdateCheck.evaluate(body, sig, UpdateChannel.PRODUCTION, Version(1, 6, 0)))
        val r = UpdateCheck.evaluate(body, sig, UpdateChannel.PRODUCTION, Version(1, 5, 1))
        assertTrue(r.toString(), r is CheckResult.Available)
        assertEquals(266425547L, (r as CheckResult.Available).manifest.files[InstallerKind.MSI]!!.size)
    }

    @Test fun `a URL override alone is ignored - it needs a test key with it`() {
        val env = mapOf("OSHI_UPDATE_MANIFEST_URL" to "https://evil.example/m.json")
        assertEquals(UpdateChannel.PRODUCTION, UpdateChannel.fromEnv { env[it] })
        val bad = env + ("OSHI_UPDATE_PUBKEY" to "c2hvcnQ=")
        assertEquals(UpdateChannel.PRODUCTION, UpdateChannel.fromEnv { bad[it] })
        val keyOnly = mapOf("OSHI_UPDATE_PUBKEY" to FIXTURE_PUBKEY)
        assertEquals(UpdateChannel.PRODUCTION, UpdateChannel.fromEnv { keyOnly[it] })
        val both = env + ("OSHI_UPDATE_PUBKEY" to FIXTURE_PUBKEY)
        val ch = UpdateChannel.fromEnv { both[it] }
        assertTrue(ch.isTest)
        assertNull(ch.requireHost)
        assertFalse(ch.publicKey.contentEquals(UpdateChannel.PRODUCTION.publicKey))
        val file = both + ("OSHI_UPDATE_MANIFEST_URL" to "file:///tmp/m.json")
        assertEquals(UpdateChannel.PRODUCTION, UpdateChannel.fromEnv { file[it] })
    }
}
