package com.oshi.desktop.diag

import com.oshi.desktop.call.CallEndReason
import com.oshi.desktop.call.CallState
import com.oshi.desktop.call.CallStateMachine
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.store.LocalDataKeys
import com.oshi.messenger.service.diag.CallFileLogger
import com.oshi.messenger.service.diag.CallLogRecords
import com.oshi.messenger.service.diag.CallLogRedaction
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * __CALL_LOG_AT_REST_2026_09_23__ The desktop's sealed call log: the SHARED logger
 * (`service/diag/CallFileLogger.kt` from the Android tree) keyed from the vault, and the
 * redacted export byte-identical to iOS — against the same fixtures the Android suite reads
 * (`OSHI-Android/app/src/test/resources/call_log/`, on this build's test classpath).
 */
class DesktopCallLogTest {

    private lateinit var home: File
    private val loggers = mutableListOf<CallFileLogger>()

    @Before fun setUp() { home = Files.createTempDirectory("DesktopCallLogTest").toFile() }

    @After fun tearDown() {
        loggers.forEach { runCatching { it.close() } }
        home.deleteRecursively()
    }

    private fun resource(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("call_log/$name")?.use { it.readBytes() }
            ?: error("missing test resource call_log/$name (the Android test resources are not on the classpath)")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** The production key path: vault (passphrase-protected here, no OS store) → local-data root → HKDF. */
    private fun vaultKey(vaultFile: File, pass: String = "test-pass"): ByteArray {
        val vault = KeyVault.open(vaultFile, secretStore = null, passphrase = pass.toCharArray())
        return LocalDataKeys.derive(LocalDataKeys.root(vault), LocalDataKeys.CALL_LOG)
    }

    private fun logger(key: ByteArray): CallFileLogger =
        CallFileLogger(File(home, DesktopCallLog.LOG_DIR), File(home, DesktopCallLog.EXPORT_SCRATCH_DIR), { key })
            .also { loggers += it }

    @Test fun `the shared logger has no android dependency`() {
        val src = File(System.getProperty("oshi.android.root") ?: "../OSHI-Android",
            "app/src/main/java/com/oshi/messenger/service/diag/CallFileLogger.kt")
        assertTrue("shared source missing: $src", src.isFile)
        val text = src.readText()
        for (b in listOf("import android.", "import androidx.", "import dagger.", "BuildConfig")) {
            assertFalse("CallFileLogger.kt gained '$b' — the desktop compiles it", text.contains(b))
        }
    }

    @Test fun `vault-keyed log is sealed on disk and reads back`() {
        val key = vaultKey(File(home, KeyVault.FILE_NAME))
        assertEquals(32, key.size)
        val l = logger(key)
        l.log("DIAG_PATH | txPrimary=UDP_RELAY | desktop-marker")
        l.log("DIAG_MSG_DECRYPT_OK | preview=never-in-clear")
        l.flushAndWait()
        val raw = File(home, "${DesktopCallLog.LOG_DIR}/${CallFileLogger.CURRENT_LOG_NAME}").readBytes()
        assertTrue(CallLogRecords.hasMagic(raw))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("desktop-marker"))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("never-in-clear"))
        val text = String(CallLogRecords.decode(raw, key).plaintext, Charsets.UTF_8)
        assertTrue(text.contains("] DIAG_PATH | txPrimary=UDP_RELAY | desktop-marker\n"))

        // Same vault, next start ⇒ same key ⇒ the previous run is still readable.
        val again = logger(vaultKey(File(home, KeyVault.FILE_NAME)))
        assertTrue(again.decryptedLogs().first { it.first == CallFileLogger.PREVIOUS_LOG_NAME }.second.contains("desktop-marker"))
    }

    @Test fun `a different vault cannot read it and the old run is dropped`() {
        val first = logger(vaultKey(File(home, "a-" + KeyVault.FILE_NAME)))
        first.log("DIAG_PATH | old-vault")
        first.close()
        val second = logger(vaultKey(File(home, "b-" + KeyVault.FILE_NAME)))
        second.log("DIAG_PATH | new-vault")
        val export = second.redactedExportText()!!
        assertFalse(export.contains("old-vault"))
        assertTrue(export.contains("DIAG_PATH | new-vault"))
        assertFalse(File(home, "${DesktopCallLog.LOG_DIR}/${CallFileLogger.PREVIOUS_LOG_NAME}").exists())
    }

    @Test fun `redaction is byte-identical to iOS`() {
        val out = CallLogRedaction.redact(
            listOf(
                CallFileLogger.PREVIOUS_LOG_NAME to String(resource("redaction_prev.log"), Charsets.UTF_8),
                CallFileLogger.CURRENT_LOG_NAME to String(resource("redaction_current.log"), Charsets.UTF_8),
            )
        )
        assertEquals(String(resource("redaction_expected.txt"), Charsets.UTF_8), out)
    }

    @Test fun `OSHILOG1 vectors — Kotlin bytes equal the reference, Swift-sealed file opens`() {
        val v = JSONObject(String(resource("oshilog1_vectors.json"), Charsets.UTF_8))
        val k = hex(v.getString("keyHex"))
        val fixed = v.getJSONObject("fixedNonce")
        val recs = fixed.getJSONArray("records")
        var built = CallLogRecords.MAGIC
        for (i in 0 until recs.length()) {
            val r = recs.getJSONObject(i)
            built += CallLogRecords.sealWithNonce(r.getString("plaintext").toByteArray(), k, hex(r.getString("nonceHex")))
        }
        assertArrayEquals(Base64.getDecoder().decode(fixed.getString("fileBase64")), built)
        val swift = v.getJSONObject("swift")
        val d = CallLogRecords.decode(Base64.getDecoder().decode(swift.getString("fileBase64")), k)
        assertEquals(swift.getString("plaintext"), String(d.plaintext, Charsets.UTF_8))
    }

    @Test fun `export to a chosen file is the redacted text`() {
        val l = logger(vaultKey(File(home, KeyVault.FILE_NAME)))
        l.log("🔄 STATE CHANGE: RINGING → IN_CALL")
        l.log("call: 1234 learned 2 of 2 remote candidates")   // desktop prose: sealed, never exported
        val text = l.redactedExportText()!!
        assertTrue(text.startsWith(CallLogRedaction.HEADER))
        assertTrue(text.endsWith("🔄 STATE CHANGE: RINGING → IN_CALL\n"))
        assertFalse(text.contains("learned 2 of 2"))
    }

    @Test fun `the state machine reports every transition for the STATE CHANGE line`() {
        val seen = mutableListOf<Pair<CallState, CallState>>()
        val m = CallStateMachine("AAAAmyidentitykeybase64aaaaaaaaaaaaaaaaaaaaa=", null)
        m.onStateChange = { a, b -> seen += a to b }
        val t0 = 1_770_000_000_000L
        m.startCall("ZZZZpeeridentitykeybase64zzzzzzzzzzzzzzzzzzz=", t0, newCallId = "1B2C3D4E-5F60-7182-9394-A5B6C7D8E9F0")
        m.hangUp(CallEndReason.HUNG_UP, t0 + 1_000)
        assertEquals(CallState.IDLE, seen.first().first)
        assertEquals(CallState.ENDED, seen.last().second)
        seen.zipWithNext().forEach { (x, y) -> assertEquals("transitions chain", x.second, y.first) }
        assertTrue(seen.none { it.first == it.second })
    }
}
