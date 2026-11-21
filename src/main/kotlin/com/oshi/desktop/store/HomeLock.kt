package com.oshi.desktop.store

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * __DESKTOP_HOME_LOCK_2026_10_29__ One process per data directory, enforced by the kernel.
 *
 * ## The corruption this prevents, in iOS's own words
 *
 * `OSHI/CrossProcessLock.swift` exists because two processes could read-modify-write one
 * ratchet session, and it states the consequence without softening it:
 *
 * > *"The Double Ratchet does not tolerate that: every send advances the sending chain and
 * > every receive advances the receiving chain, and the state is a single serialized blob.
 * > Two processes doing read-modify-write on it concurrently means the second write silently
 * > discards the first one's advance — and the peer's next message then fails to
 * > authenticate, permanently, for that session."*
 *
 * **On iOS the second process is a share extension. On a desktop it is a second copy of the
 * app**, which is far easier to produce by accident: double-click the icon while it is
 * already running, launch it from a terminal with the packaged app open, or leave
 * `./gradlew run` going. Nothing in this client prevented it. `SessionStore` is
 * `@Synchronized`, and iOS names that exact trap — a per-process lock is *"invisible to the
 * other side and would give a false sense of safety"*.
 *
 * There is a second reason beyond the ratchet, specific to this client: `MessageStore` is an
 * append-only log per conversation and `RouterState` is a read-modify-write JSON file. Two
 * processes polling the same relay cursor would also double-ack and interleave appends.
 *
 * ## Why this REFUSES rather than locking per peer
 *
 * iOS needs a lock per session because the share extension is a legitimate concurrent writer
 * — it has work to do and must be allowed to do it. A desktop app has no legitimate second
 * process at all, so the correct answer is both simpler and stronger: **do not start.** A
 * per-peer lock would make the second instance mostly work and corrupt things occasionally;
 * refusing makes the failure immediate, total, and explainable in one sentence to the person
 * who double-clicked.
 *
 * ## Why a file lock
 *
 * `FileChannel.tryLock` is an OS-level advisory lock (`flock`/`LockFileEx`), and the property
 * that matters is the one iOS relies on too: **the kernel releases it when the holder dies.**
 * A lock implemented with a pid file or a marker would survive a crash and lock the user out
 * of their own messages — turning a rare corruption into a permanent lockout, which is worse.
 *
 * Two failure modes are deliberately told apart, because they need different words:
 *  - [Outcome.Busy] — another process holds it. This is the case this exists for.
 *  - [Outcome.Unsupported] — the filesystem refused to lock at all (some network mounts do).
 *    **The app must still run.** A client that refused to start on an NFS home directory
 *    would be broken for a real user in exchange for a hypothetical one, so this reports and
 *    proceeds, and the caller says so once.
 */
class HomeLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock?,
) : AutoCloseable {

    sealed interface Outcome {
        /** We hold it. [lock] must be closed when the process ends. */
        data class Held(val lock: HomeLock) : Outcome

        /** Another process holds it: do not touch this directory. */
        data object Busy : Outcome

        /** The filesystem cannot lock. Proceed, unprotected, and SAY so. */
        data class Unsupported(val reason: String) : Outcome
    }

    override fun close() {
        runCatching { lock?.release() }
        runCatching { channel.close() }
        // Drop the retention as well, or `HomeLockTest`'s release-and-reacquire case would
        // keep a dead lock reachable and the next acquire would see a stale one.
        if (retained === this) retained = null
    }

    companion object {
        const val FILE_NAME = ".instance.lock"

        /**
         * THE HELD LOCK, RETAINED FOR THE PROCESS'S LIFETIME.
         *
         * **This field is the fix for a bug the unit tests could not see.** `acquire` returns
         * an [Outcome.Held] carrying the open channel, and both entry points did
         * `when (val lock = acquire(home)) { is Held -> Unit }` — which DISCARDS it. An
         * unreachable `FileChannel` is eligible for finalization, and finalizing it releases
         * the OS lock, so a second process started normally while the first was still running.
         *
         * `HomeLockTest` passed throughout, because a test keeps every lock it takes in a list
         * so it can close them in teardown. The test retained what the application threw away
         * — which is why this was found by running two REAL processes and not by the suite.
         *
         * Holding it here also makes the lifetime honest: it is released when the process
         * ends, by the kernel, and never before.
         */
        @Volatile
        private var retained: HomeLock? = null

        /**
         * Try to become the single writer of [home].
         *
         * The channel is kept open for the process's lifetime on purpose: releasing the
         * FileLock or closing the channel drops the lock, so a `use {}` block here would
         * hold it for microseconds and protect nothing.
         */
        fun acquire(home: File): Outcome {
            val file = File(home, FILE_NAME)
            runCatching { home.mkdirs() }
            val channel = runCatching { RandomAccessFile(file, "rw").channel }.getOrElse {
                return Outcome.Unsupported("could not open ${file.name}: ${it.message}")
            }
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                // Another lock in THIS JVM already covers the region. On a desktop that means
                // the app is starting twice in one process, which is a programming error
                // rather than a second instance — reported as Busy because the effect on the
                // stores is identical.
                runCatching { channel.close() }
                return Outcome.Busy
            } catch (e: Exception) {
                // Locking is unsupported here (some network filesystems). See the class note:
                // refusing to start would be worse than running unprotected.
                runCatching { channel.close() }
                return Outcome.Unsupported(e.message ?: e.javaClass.simpleName)
            }
            if (lock == null) {
                runCatching { channel.close() }
                return Outcome.Busy
            }
            // Retained, or the channel is collectable and the lock evaporates. See [retained].
            return Outcome.Held(HomeLock(channel, lock).also { retained = it })
        }
    }
}
