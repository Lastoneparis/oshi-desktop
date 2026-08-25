package com.oshi.desktop.store

import java.io.File
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
        val out = r.stdout.trim()
        return if (out.isEmpty()) null else Base64.getDecoder().decode(out)
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

    fun isUsable(): Boolean =
        Proc.which("secret-tool") != null &&
            // A keyring daemon that is not running makes every call hang or fail; a
            // lookup of a key we do not have is the cheapest way to find out, and its
            // "not found" (exit 1, empty stderr) is success for this purpose.
            Proc.run(listOf("secret-tool", "lookup", "service", service, "account", "__probe__"),
                timeoutSeconds = 5).timedOut.not()

    override fun get(account: String): ByteArray? {
        val r = Proc.run(listOf("secret-tool", "lookup", "service", service, "account", account))
        if (r.exitCode != 0) return null
        val out = r.stdout.trim()
        return if (out.isEmpty()) null else Base64.getDecoder().decode(out)
    }

    override fun put(account: String, secret: ByteArray) {
        val b64 = Base64.getEncoder().encodeToString(secret)
        val r = Proc.run(
            listOf("secret-tool", "store", "--label=OSHI master key", "service", service, "account", account),
            stdin = b64,
        )
        if (r.exitCode != 0) throw SecretStoreException("secret-tool store failed: ${r.stderr.trim()}")
    }

    override fun delete(account: String) {
        Proc.run(listOf("secret-tool", "clear", "service", service, "account", account))
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

    fun isUsable(): Boolean = Proc.which("powershell.exe") != null || Proc.which("powershell") != null

    private fun powershell(): String = Proc.which("powershell.exe") ?: Proc.which("powershell") ?: "powershell.exe"

    override fun get(account: String): ByteArray? {
        val f = blob(account)
        if (!f.isFile) return null
        val r = Proc.run(
            listOf(powershell(), "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Security; " +
                    "\$p=[Convert]::FromBase64String((Get-Content -Raw \$env:OSHI_BLOB_PATH).Trim()); " +
                    "\$b=[Security.Cryptography.ProtectedData]::Unprotect(\$p,\$null,'CurrentUser'); " +
                    "[Convert]::ToBase64String(\$b)"),
            env = mapOf("OSHI_BLOB_PATH" to f.absolutePath),
        )
        if (r.exitCode != 0) return null
        val out = r.stdout.trim()
        return if (out.isEmpty()) null else Base64.getDecoder().decode(out)
    }

    override fun put(account: String, secret: ByteArray) {
        val r = Proc.run(
            listOf(powershell(), "-NoProfile", "-NonInteractive", "-Command",
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
 * Write-to-temp-then-rename.
 *
 * A half-written vault is an unrecoverable account: the file exists, so nothing offers
 * to create a new identity, and it does not decrypt, so nothing can read the old one.
 * The rename is the only step that must be atomic, and on every platform it is.
 */
internal object AtomicFile {
    fun write(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: File(".")
        DesktopPaths.ensurePrivateDir(parent)
        val tmp = File.createTempFile(target.name, ".tmp", parent)
        try {
            DesktopPaths.makePrivate(tmp)
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                // Windows will not rename onto an existing file.
                if (!target.delete() && target.exists()) throw SecretStoreException("cannot replace $target")
                if (!tmp.renameTo(target)) throw SecretStoreException("cannot rename $tmp to $target")
            }
            DesktopPaths.makePrivate(target)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
