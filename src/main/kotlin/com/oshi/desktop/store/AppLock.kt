package com.oshi.desktop.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * __DESKTOP_APP_LOCK_2027__ A passcode in front of the window: asked at launch and after a
 * chosen time without keyboard or mouse input, as the phones lock behind Face ID / the device
 * passcode on launch and when the app is left (iOS `autoLockEnabled`, Android
 * `auto_lock_timeout_seconds`).
 *
 * ## What is stored
 * Never the passcode. `app-lock.json` holds a random 16-byte salt and PBKDF2-HMAC-SHA256
 * ([ITERATIONS] rounds, 32 bytes) of the passcode; the comparison is constant-time. Also the
 * failure count and the end of the current wait, so quitting does not reset them.
 *
 * ## Nobody is locked out — the phones' recovery
 * On a phone the way back from a forgotten app lock is the DEVICE credential (iOS falls back
 * to `.deviceOwnerAuthentication`, Android to `DEVICE_CREDENTIAL`). This app's equivalent
 * credential is the account itself: the **recovery key** — the same key that restores this
 * identity on a phone — unlocks the window and removes the passcode ([unlockWithRecoveryKey]).
 * It is accepted at any time, including during a wait.
 *  * Wrong passcodes never erase anything. After [FREE_ATTEMPTS] misses each further miss
 *    starts a wait: 30 s, doubling, at most [MAX_WAIT_MS].
 *
 * ## What it is not
 * A screen lock, not encryption: the messages on disk are protected by the key vault
 * whether or not a passcode is set, exactly as the phones' app lock protects the screen and
 * not the database.
 *
 * ## Bound to the vault (__APP_LOCK_FILE_DELETE_BYPASS_2026_11_20__)
 * Until 1.5.1 the passcode lived ONLY in `app-lock.json`, plaintext: deleting that file (or
 * editing `failures`/`waitUntilMs`) removed the lock, and the PBKDF2 hash of a 4-digit code
 * could be brute-forced offline from any copy of the profile. Once [bindToVault] runs, the
 * configuration is a [KeyVault] entry ([VAULT_ACCOUNT]) under the OS-held master key and the
 * file keeps only a non-secret stub. Deleting or editing the file no longer unlocks anything.
 */
object AppLock {

    const val FILE_NAME = "app-lock.json"
    const val VAULT_ACCOUNT = "app-lock-v1"
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 64
    const val FREE_ATTEMPTS = 5
    const val MAX_WAIT_MS = 15 * 60_000L
    private const val FIRST_WAIT_MS = 30_000L
    internal var ITERATIONS = 210_000

    /** Minutes without input before the window locks; 0 = only at launch. */
    val AUTO_LOCK_CHOICES = listOf(1, 5, 15, 60, 0)

    data class Config(
        val saltB64: String? = null,
        val hashB64: String? = null,
        val iterations: Int = ITERATIONS,
        val autoLockMinutes: Int = 5,
        val failures: Int = 0,
        val waitUntilMs: Long = 0,
    ) {
        val hasPasscode: Boolean get() = saltB64 != null && hashB64 != null
    }

    sealed interface Attempt {
        data object Unlocked : Attempt
        /** Wrong. [remaining] free tries left before waits begin (0 = the next miss waits). */
        data class Wrong(val remaining: Int, val waitUntilMs: Long) : Attempt
        /** Not even checked: a wait is running. */
        data class Waiting(val untilMs: Long) : Attempt
    }

    var config: Config by mutableStateOf(Config())
        private set

    /** True while the window must show the lock screen. */
    var locked: Boolean by mutableStateOf(false)
        private set

    @Volatile private var file: File? = null
    @Volatile private var vault: KeyVault? = null
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()

    fun attach(home: File) {
        val f = File(home, FILE_NAME)
        file = f
        vault = null
        config = read(f)
        // Locked at launch whenever a passcode is set.
        locked = config.hasPasscode
        lastActivityMs = System.currentTimeMillis()
    }

    /**
     * Make the vault the source of truth. Called once the account's vault is open and BEFORE
     * the main window: a passcode found only in the legacy file is moved into the vault; a
     * passcode in the vault locks even if the file was deleted or rewritten.
     */
    fun bindToVault(v: KeyVault) {
        val stored = runCatching { v.get(VAULT_ACCOUNT)?.let { parse(String(it, Charsets.UTF_8)) } }.getOrNull()
        vault = v
        if (stored != null) {
            config = stored
        } else if (config.hasPasscode) {
            runCatching { v.put(VAULT_ACCOUNT, encode(config, includeSecret = true).toByteArray(Charsets.UTF_8)) }
        }
        if (config.hasPasscode) locked = true
        writeFile(config)
    }

    /** Renderer / test seam. */
    fun preview(c: Config, isLocked: Boolean) { config = c; locked = isLocked }

    fun isValidNew(code: String): Boolean = code.length in MIN_LENGTH..MAX_LENGTH && code.isNotBlank()

    /** Set or replace the passcode. Changing one needs the current one ([current]). */
    fun setPasscode(newCode: String, current: String? = null): Boolean {
        if (!isValidNew(newCode)) return false
        if (config.hasPasscode && (current == null || !matches(config, current))) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(newCode, salt, ITERATIONS)
        save(config.copy(
            saltB64 = Base64.getEncoder().encodeToString(salt),
            hashB64 = Base64.getEncoder().encodeToString(hash),
            iterations = ITERATIONS, failures = 0, waitUntilMs = 0,
        ))
        return true
    }

    /** Remove the passcode; needs the current one. */
    fun removePasscode(current: String): Boolean {
        if (!config.hasPasscode || !matches(config, current)) return false
        save(Config(autoLockMinutes = config.autoLockMinutes))
        locked = false
        return true
    }

    fun setAutoLockMinutes(minutes: Int) {
        if (minutes in AUTO_LOCK_CHOICES) save(config.copy(autoLockMinutes = minutes))
    }

    fun lockNow() { if (config.hasPasscode) locked = true }

    fun noteActivity(nowMs: Long = System.currentTimeMillis()) { lastActivityMs = nowMs }

    /** Called periodically: lock once [Config.autoLockMinutes] pass without input. */
    fun tick(nowMs: Long = System.currentTimeMillis()) {
        if (shouldAutoLock(config, locked, lastActivityMs, nowMs)) locked = true
    }

    fun unlock(code: String, nowMs: Long = System.currentTimeMillis()): Attempt {
        val (outcome, next) = attempt(config, code, nowMs)
        if (next != config) save(next)
        if (outcome == Attempt.Unlocked) { locked = false; lastActivityMs = nowMs }
        return outcome
    }

    /**
     * The phones' way back: [recoveryKeyMatches] says whether [key] is THIS account's recovery
     * key. On a match the window unlocks and the passcode is removed, so the person sets a new
     * one rather than being asked again for the one they forgot.
     */
    fun unlockWithRecoveryKey(key: String, recoveryKeyMatches: (String) -> Boolean): Boolean {
        if (key.isBlank() || !runCatching { recoveryKeyMatches(key.trim()) }.getOrDefault(false)) return false
        save(Config(autoLockMinutes = config.autoLockMinutes))
        locked = false
        lastActivityMs = System.currentTimeMillis()
        return true
    }

    // ------------------------------------------------------------------ pure rules

    fun shouldAutoLock(c: Config, isLocked: Boolean, lastActivity: Long, now: Long): Boolean =
        c.hasPasscode && !isLocked && c.autoLockMinutes > 0 && now - lastActivity >= c.autoLockMinutes * 60_000L

    /** The wait after the [failures]-th consecutive miss; 0 while misses are free. */
    fun waitAfter(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0
        val doublings = (failures - FREE_ATTEMPTS).coerceAtMost(20)
        return (FIRST_WAIT_MS shl doublings).coerceAtMost(MAX_WAIT_MS)
    }

    fun attempt(c: Config, code: String, now: Long): Pair<Attempt, Config> {
        if (!c.hasPasscode) return Attempt.Unlocked to c
        if (now < c.waitUntilMs) return Attempt.Waiting(c.waitUntilMs) to c
        if (matches(c, code)) return Attempt.Unlocked to c.copy(failures = 0, waitUntilMs = 0)
        val failures = c.failures + 1
        val wait = waitAfter(failures)
        val until = if (wait > 0) now + wait else 0
        return Attempt.Wrong((FREE_ATTEMPTS - failures).coerceAtLeast(0), until) to c.copy(failures = failures, waitUntilMs = until)
    }

    fun matches(c: Config, code: String): Boolean {
        val salt = c.saltB64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return false
        val want = c.hashB64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return false
        return MessageDigest.isEqual(derive(code, salt, c.iterations), want)
    }

    fun derive(code: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    // ------------------------------------------------------------------ file

    fun read(f: File): Config = try {
        if (!f.isFile) Config() else parse(f.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        // An unreadable file must not lock anyone out — and must not silently drop a lock
        // either. It reads as "no passcode", which is what deleting it would do anyway; once
        // the vault is bound ([bindToVault]) the vault entry decides instead.
        Config()
    }

    fun parse(text: String): Config = JSONObject(text).let {
            Config(
                saltB64 = it.optString("salt", "").ifEmpty { null },
                hashB64 = it.optString("hash", "").ifEmpty { null },
                iterations = it.optInt("iterations", ITERATIONS).coerceAtLeast(1),
                autoLockMinutes = it.optInt("autoLockMinutes", 5).takeIf { m -> m in AUTO_LOCK_CHOICES } ?: 5,
                failures = it.optInt("failures", 0).coerceAtLeast(0),
                waitUntilMs = it.optLong("waitUntilMs", 0),
            )
        }

    private fun save(c: Config) {
        config = c
        vault?.let { v ->
            runCatching { v.put(VAULT_ACCOUNT, encode(c, includeSecret = true).toByteArray(Charsets.UTF_8)) }
        }
        writeFile(c)
    }

    /** With a vault bound the file carries no salt, hash or counters: nothing to edit or brute-force. */
    private fun writeFile(c: Config) {
        val f = file ?: return
        val secretInFile = vault == null
        runCatching { AtomicFile.write(f, encode(c, includeSecret = secretInFile).toByteArray(Charsets.UTF_8)) }
    }

    internal fun encode(c: Config, includeSecret: Boolean): String = JSONObject().apply {
        put("v", 1)
        if (includeSecret) {
            c.saltB64?.let { put("salt", it) }
            c.hashB64?.let { put("hash", it) }
            put("kdf", "PBKDF2-HMAC-SHA256")
            put("iterations", c.iterations)
            put("failures", c.failures)
            put("waitUntilMs", c.waitUntilMs)
        } else {
            put("inVault", true)
        }
        put("autoLockMinutes", c.autoLockMinutes)
    }.toString()

    /** Test seam: write into [f] without the global file. */
    internal fun attachForTest(f: File?) { file = f; vault = null; config = f?.let(::read) ?: Config(); locked = config.hasPasscode }
}
