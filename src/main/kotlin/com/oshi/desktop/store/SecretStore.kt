package com.oshi.desktop.store

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The one secret a desktop OSHI client asks the operating system to hold: a 32-byte
 * master key. Everything else it owns is encrypted with that key by [KeyVault].
 *
 * WHY ONLY ONE SECRET. iOS has the Keychain and Android has the Keystore; the desktop
 * equivalents are three unrelated things with three different APIs, none reachable from
 * a plain JVM without native code. Rather than talk to three OS stores for every private
 * key, session and prekey the app owns, this talks to them ONCE, for one value. That
 * keeps the platform-specific surface to roughly forty lines per OS — and it means a
 * machine where the OS store is unavailable degrades to a passphrase over the same
 * vault, instead of degrading to plaintext, which is what the Android tree did with its
 * prekey privates for a while (`V2PrekeyStore.kt` still carries the migration).
 *
 * NOT A SECURE ENCLAVE. On all three platforms this protects the key against another
 * *user* of the machine and against a stolen unencrypted disk. It does not protect
 * against malware already running as this user — that has always been true of a desktop
 * messenger and pretending otherwise in the UI would be the lie.
 */
interface SecretStore {

    /** Short name for logs and for telling the user where their key actually lives. */
    val id: String

    fun get(account: String): ByteArray?
    fun put(account: String, secret: ByteArray)
    fun delete(account: String)

    companion object {
        /** The service/app name every backend files its entries under. */
        const val SERVICE = "com.oshi.desktop"

        /**
         * The best store this machine offers, or null if none is reachable.
         *
         * Null is a legitimate answer, not an error: a headless Linux box with no
         * keyring daemon has nowhere to put this. The caller then asks for a passphrase.
         */
        fun detect(service: String = SERVICE): SecretStore? {
            val backend = when {
                DesktopPaths.isMac -> MacKeychainStore(service).takeIf { it.isUsable() }
                DesktopPaths.isWindows -> WindowsDpapiStore(service).takeIf { it.isUsable() }
                else -> LinuxSecretToolStore(service).takeIf { it.isUsable() }
            }
            return backend?.let { VerifiedSecretStore(it) }
        }
    }
}

// ---------------------------------------------------------------------------- macOS

/**
 * The login Keychain, through `/usr/bin/security`.
 *
 * The secret is handed over in the CHILD'S ENVIRONMENT, never on the command line:
 *
 *     sh -c 'security add-generic-password … -w "$OSHI_SECRET_IN"'
 *
 * `security` has no way to read a password from stdin — piping it stores an EMPTY
 * password and still exits 0 (verified: the item is created, `find … -w` prints
 * nothing), which would silently give every user of this machine an empty master key.
 * Passing it as an argument instead puts it in `ps` output for every user on the box.
 * The environment of another user's process is not readable without root on macOS, so
 * the shell-expands-it form is the least bad of the three, and the single-quoting is
 * what keeps the value out of this process's own argv.
 */
internal class MacKeychainStore(private val service: String) : SecretStore {
    override val id = "macOS Keychain"

    fun isUsable(): Boolean = File("/usr/bin/security").canExecute()

    override fun get(account: String): ByteArray? {
        val r = Proc.run(listOf("/usr/bin/security", "find-generic-password", "-a", account, "-s", service, "-w"))
        if (r.exitCode != 0) return null
        return decodeStoredSecret(r.stdout, id)
    }

    override fun put(account: String, secret: ByteArray) {
        val b64 = Base64.getEncoder().encodeToString(secret)
        val r = Proc.run(
            listOf("/bin/sh", "-c",
                "exec /usr/bin/security add-generic-password -a \"\$OSHI_ACCOUNT\" " +
                    "-s \"\$OSHI_SERVICE\" -w \"\$OSHI_SECRET_IN\" -U"),
            env = mapOf("OSHI_ACCOUNT" to account, "OSHI_SERVICE" to service, "OSHI_SECRET_IN" to b64),
        )
        if (r.exitCode != 0) throw SecretStoreException("keychain write failed: ${r.stderr.trim()}")
    }

    override fun delete(account: String) {
        Proc.run(listOf("/usr/bin/security", "delete-generic-password", "-a", account, "-s", service))
    }
}

// ---------------------------------------------------------------------------- Linux

/**
 * libsecret via `secret-tool` — the same daemon GNOME Keyring and KWallet expose.
 *
 * `secret-tool` reads the secret from STDIN, so there is no argv or environment
 * exposure here at all. It is absent on a headless box and on minimal installs; that is
 * what [isUsable] is for, and the answer there is a passphrase, not plaintext.
 *
 * UNVERIFIED ON A REAL LINUX MACHINE — see PARITY.md. The shape is straight from
 * secret-tool(1); what has not been watched is a live keyring accepting it.
 */
internal class LinuxSecretToolStore(private val service: String) : SecretStore {
    override val id = "libsecret (secret-tool)"

    private fun secretTool(): String? = resolveSecretTool(Proc::which)

    fun isUsable(): Boolean = keyringAcceptsAWrite

    /**
     * __SECRET_TOOL_PROBE_2026_11_02__ Does this keyring accept a WRITE, not merely
     * answer a question.
     *
     * The old probe was a `lookup` of a key we do not have, and it treated anything
     * other than a timeout as success. A **locked** collection answers a lookup
     * perfectly well — "not found", exit 1, instantly — so the probe passed, `detect()`
     * chose this store, and the FIRST `put` of the master key threw:
     *
     *     Cannot open this account:
     *     secret-tool store failed: secret-tool: Cannot create an item in a locked collection
     *
     * On a GUI launch that text goes to stderr, where nobody sees it, and the launcher
     * returns. The app simply does not open. Measured on Ubuntu 24.04 by extracting the
     * published .deb and running it under Xvfb: the published 1.4.1 cannot create an
     * account on a machine whose keyring is locked or unwritable — a headless box, a
     * minimal install, an auto-login desktop whose login keyring was never unlocked.
     *
     * This is the SAME defect as `__DPAPI_PROBE_2026_10_30__` on Windows, and the note
     * there says "`LinuxSecretToolStore` has always probed the live keyring rather than
     * the binary". It did — and asking the keyring whether it is alive is not asking
     * whether it will take a secret.
     *
     * So: store three constant bytes, read them back, clear them. Never a real secret,
     * and `lazy` because `detect()` runs at startup and this spawns processes.
     *
     * A keyring that is merely LOCKED may prompt here, or time out and send us to the
     * passphrase path. That is the right trade: `KeyVault.open` records the protection
     * scheme in the vault file, so the choice is made once on a fresh profile and never
     * flips underneath an existing one — and a passphrase a user can type beats an app
     * that will not start.
     */
    private val keyringAcceptsAWrite: Boolean by lazy {
        val tool = secretTool() ?: return@lazy false
        val probe = "__probe__"
        val value = "AAEC"   // three constant bytes, base64
        val put = Proc.run(
            listOf(tool, "store", "--label=OSHI key store probe", "service", service, "account", probe),
            stdin = value, timeoutSeconds = 8,
        )
        if (!writeProbePassed(put, null, value)) return@lazy false
        val got = Proc.run(
            listOf(tool, "lookup", "service", service, "account", probe), timeoutSeconds = 8,
        )
        Proc.run(listOf(tool, "clear", "service", service, "account", probe), timeoutSeconds = 8)
        writeProbePassed(put, got, value)
    }

    override fun get(account: String): ByteArray? {
        val tool = secretTool() ?: return null
        val r = Proc.run(listOf(tool, "lookup", "service", service, "account", account))
        if (r.exitCode != 0) return null
        return decodeStoredSecret(r.stdout, id)
    }

    override fun put(account: String, secret: ByteArray) {
        val tool = secretTool() ?: throw SecretStoreException("secret-tool is unavailable")
        val b64 = Base64.getEncoder().encodeToString(secret)
        val r = Proc.run(
            listOf(tool, "store", "--label=OSHI master key", "service", service, "account", account),
            stdin = b64,
        )
        if (r.exitCode != 0) throw SecretStoreException("secret-tool store failed: ${r.stderr.trim()}")
    }

    override fun delete(account: String) {
        secretTool()?.let { Proc.run(listOf(it, "clear", "service", service, "account", account)) }
    }

    internal companion object {
        /**
         * The probe's verdict, separated from the processes that produce it so the
         * cases that matter can be asserted without a keyring.
         *
         * [lookup] is null on the first call, when only the WRITE has happened: a
         * locked collection fails there, and there is no point reading back.
         */
        internal fun writeProbePassed(put: Proc.Result, lookup: Proc.Result?, expected: String): Boolean {
            if (put.timedOut || put.exitCode != 0) return false
            if (lookup == null) return true
            return !lookup.timedOut && lookup.exitCode == 0 && lookup.stdout.trim() == expected
        }

        private const val SYSTEM_SECRET_TOOL = "/usr/bin/secret-tool"

        /** Prefer the system binary so a writable PATH entry cannot receive the vault key. */
        internal fun resolveSecretTool(
            pathLookup: (String) -> String?,
            isExecutable: (String) -> Boolean = { File(it).isFile && File(it).canExecute() },
        ): String? = SYSTEM_SECRET_TOOL.takeIf(isExecutable) ?: pathLookup("secret-tool")
    }
}

// -------------------------------------------------------------------------- Windows

/**
 * DPAPI (`CryptProtectData`) through PowerShell, with the ciphertext in a file under
 * `%APPDATA%\OSHI`.
 *
 * Windows has no credential store a plain JVM can reach — the Credential Manager needs
 * P/Invoke — but DPAPI does exactly what is needed: it encrypts to the CURRENT USER's
 * profile, so the blob is useless on another account or another machine, and Windows
 * manages the key material. The value crosses to PowerShell through the child's
 * environment and comes back on stdout; it never appears in a command line.
 *
 * UNVERIFIED ON A REAL WINDOWS MACHINE — see PARITY.md.
 */
internal class WindowsDpapiStore(private val service: String) : SecretStore {
    override val id = "Windows DPAPI"

    private val dir get() = File(DesktopPaths.dataDir, "dpapi").also { DesktopPaths.ensurePrivateDir(it) }
    private fun blob(account: String) = File(dir, "${service}.${account.replace(Regex("[^A-Za-z0-9._-]"), "_")}.dpapi")

    /**
     * __DPAPI_PROBE_2026_10_30__ Does DPAPI actually work here, not merely "is PowerShell
     * installed".
     *
     * This asked `powershell() != null`, which answers a different question. On a Windows
     * box where `ProtectedData.Protect` fails — a service account, no loaded user profile,
     * which is the normal shape of a CI runner — PowerShell is present, so `detect()` chose
     * this store and the FIRST `put` of the master key threw. The app should have degraded
     * to a passphrase, which is exactly what this class's own doc says happens "on a machine
     * where the OS store is unavailable".
     *
     * Caught on `windows-latest`, where `SecretStoreTest` says in its own comment that a
     * skip here would be "how an unrun backend gets reported as working" — and the other
     * half of that is a backend reported as working because its probe was too weak.
     * `LinuxSecretToolStore` probed the live keyring rather than the binary — which, as
     * __SECRET_TOOL_PROBE_2026_11_02__ found, still answered "alive" for a keyring that
     * would refuse the write. Both probes are now round trips.
     *
     * A full Protect→Unprotect round trip of three constant bytes, never a secret. `lazy`
     * because `detect()` runs at startup and this spawns a process: at most one, once.
     */
    private val dpapiRoundTrips: Boolean by lazy {
        val shell = powershell() ?: return@lazy false
        val r = Proc.run(
            listOf(shell, "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Security; " +
                    "\$b=[Convert]::FromBase64String('AAEC'); " +
                    "\$p=[Security.Cryptography.ProtectedData]::Protect(\$b,\$null,'CurrentUser'); " +
                    "[Convert]::ToBase64String(" +
                    "[Security.Cryptography.ProtectedData]::Unprotect(\$p,\$null,'CurrentUser'))"),
            timeoutSeconds = 15,
        )
        r.exitCode == 0 && r.stdout.trim() == "AAEC"
    }

    fun isUsable(): Boolean = dpapiRoundTrips

    private fun powershell(): String? = resolvePowerShell(
        System.getenv("SystemRoot") ?: System.getenv("WINDIR"),
        Proc::which,
    )

    override fun get(account: String): ByteArray? {
        val f = blob(account)
        if (!f.isFile) return null
        val shell = powershell() ?: return null
        val r = Proc.run(
            listOf(shell, "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Security; " +
                    "\$p=[Convert]::FromBase64String((Get-Content -Raw \$env:OSHI_BLOB_PATH).Trim()); " +
                    "\$b=[Security.Cryptography.ProtectedData]::Unprotect(\$p,\$null,'CurrentUser'); " +
                    "[Convert]::ToBase64String(\$b)"),
            env = mapOf("OSHI_BLOB_PATH" to f.absolutePath),
        )
        if (r.exitCode != 0) return null
        return decodeStoredSecret(r.stdout, id)
    }

    override fun put(account: String, secret: ByteArray) {
        val shell = powershell() ?: throw SecretStoreException("PowerShell is unavailable")
        val r = Proc.run(
            listOf(shell, "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Security; " +
                    "\$b=[Convert]::FromBase64String(\$env:OSHI_SECRET_IN); " +
                    "\$p=[Security.Cryptography.ProtectedData]::Protect(\$b,\$null,'CurrentUser'); " +
                    "[Convert]::ToBase64String(\$p)"),
            env = mapOf("OSHI_SECRET_IN" to Base64.getEncoder().encodeToString(secret)),
        )
        if (r.exitCode != 0) throw SecretStoreException("DPAPI protect failed: ${r.stderr.trim()}")
        val f = blob(account)
        AtomicFile.write(f, r.stdout.trim().toByteArray(Charsets.US_ASCII))
    }

    override fun delete(account: String) {
        blob(account).delete()
    }

    internal companion object {
        /**
         * A user-writable directory can precede System32 on PATH. Prefer the PowerShell
         * bundled with Windows, then retain PATH only for stripped-down Windows images.
         */
        internal fun resolvePowerShell(
            systemRoot: String?,
            pathLookup: (String) -> String?,
            isExecutable: (String) -> Boolean = { File(it).isFile && File(it).canExecute() },
        ): String? {
            val systemPowerShell = systemRoot
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it, "System32/WindowsPowerShell/v1.0/powershell.exe").path }
                ?.takeIf(isExecutable)
            return systemPowerShell ?: pathLookup("powershell.exe") ?: pathLookup("powershell")
        }
    }
}

/**
 * Reads every write back, and throws if what comes out is not what went in.
 *
 * This is not belt-and-braces; it is the only check that catches the failure this layer
 * actually has. `security add-generic-password` accepts a password on stdin, **exits 0,
 * and stores an EMPTY value** — verified on macOS 15 while writing this file. An
 * exit code is therefore not evidence that a master key is retrievable, and a master key
 * that is not retrievable is an account that cannot be opened again: the vault exists,
 * so nothing offers to create a new identity, and it does not decrypt, so nothing can
 * read the old one.
 *
 * It lives in a decorator rather than inside each backend so that it can be tested
 * against a store that lies — see `SecretStoreTest`. The same rule for all three
 * platforms also means the two that cannot be run here (DPAPI, libsecret) get the check
 * that was proven on the one that can.
 */
internal class VerifiedSecretStore(private val delegate: SecretStore) : SecretStore {
    override val id: String get() = delegate.id

    override fun get(account: String): ByteArray? = delegate.get(account)

    override fun put(account: String, secret: ByteArray) {
        delegate.put(account, secret)
        val back = delegate.get(account)
        if (back == null) {
            throw SecretStoreException(
                "${delegate.id} reported a successful write for '$account' but the value cannot be read back " +
                    "(it stored nothing). Refusing to continue: a master key that cannot be read is an " +
                    "account that cannot be opened."
            )
        }
        if (!back.contentEquals(secret)) {
            throw SecretStoreException(
                "${delegate.id} stored a DIFFERENT value than the one written for '$account' " +
                    "(${back.size} bytes back, ${secret.size} written)."
            )
        }
    }

    override fun delete(account: String) = delegate.delete(account)
}

class SecretStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A helper printing non-base64 must not leak an implementation exception into account open. */
internal fun decodeStoredSecret(stdout: String, backend: String): ByteArray? {
    val encoded = stdout.trim()
    if (encoded.isEmpty()) return null
    return try {
        Base64.getDecoder().decode(encoded)
    } catch (e: IllegalArgumentException) {
        throw SecretStoreException("$backend returned malformed base64 for a stored secret", e)
    }
}

/** An in-memory store. Tests use it; nothing else should. */
class InMemorySecretStore(override val id: String = "in-memory (NOT PERSISTENT)") : SecretStore {
    private val map = HashMap<String, ByteArray>()
    override fun get(account: String): ByteArray? = map[account]?.copyOf()
    override fun put(account: String, secret: ByteArray) { map[account] = secret.copyOf() }
    override fun delete(account: String) { map.remove(account) }
}

// ------------------------------------------------------------------------ processes

/** Minimal, timeout-bounded process runner. Secrets go through env or stdin, never argv. */
internal object Proc {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    fun run(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        stdin: String? = null,
        timeoutSeconds: Long = 20,
    ): Result = try {
        val pb = ProcessBuilder(command)
        pb.environment().putAll(env)
        val p = pb.start()
        if (stdin != null) {
            p.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
        } else {
            p.outputStream.close()
        }
        // Drain both pipes on their own threads: a child that fills the stderr buffer
        // while we block reading stdout deadlocks, and `security` is chatty on stderr.
        val out = StringBuilder(); val err = StringBuilder()
        val tOut = Thread { p.inputStream.reader().use { out.append(it.readText()) } }.apply { isDaemon = true; start() }
        val tErr = Thread { p.errorStream.reader().use { err.append(it.readText()) } }.apply { isDaemon = true; start() }
        val finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            Result(-1, out.toString(), err.toString(), timedOut = true)
        } else {
            tOut.join(2000); tErr.join(2000)
            Result(p.exitValue(), out.toString(), err.toString(), timedOut = false)
        }
    } catch (e: Exception) {
        Result(-1, "", "${e.javaClass.simpleName}: ${e.message}", timedOut = false)
    }

    /** `which`, without a shell. */
    fun which(binary: String): String? {
        val path = System.getenv("PATH") ?: return null
        val sep = if (DesktopPaths.isWindows) ";" else ":"
        for (dir in path.split(sep)) {
            val f = File(dir, binary)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        return null
    }
}

/**
 * Write-to-temp-then-replace.
 *
 * A half-written vault is an unrecoverable account: the file exists, so nothing offers
 * to create a new identity, and it does not decrypt, so nothing can read the old one.
 * Prefer an atomic replacement where the filesystem supports it. The fallback never
 * deletes the old file first, avoiding a Windows delete-then-rename loss window.
 */
internal object AtomicFile {
    fun write(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: File(".")
        DesktopPaths.ensurePrivateDir(parent)
        val tmp = File.createTempFile(target.name, ".tmp", parent)
        try {
            DesktopPaths.makePrivate(tmp)
            tmp.writeBytes(bytes)
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            DesktopPaths.makePrivate(target)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
