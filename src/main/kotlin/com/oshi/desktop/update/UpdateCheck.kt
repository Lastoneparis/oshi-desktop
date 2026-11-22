package com.oshi.desktop.update

import com.oshi.desktop.net.NetClients
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** __DESKTOP_AUTO_UPDATE_2026_11_21__ What one check concluded. */
sealed class CheckResult {
    data class UpToDate(val latest: Version) : CheckResult()
    data class Available(val manifest: UpdateManifest) : CheckResult()
    /** Nothing usable came back. [why] is for the log only; the user is never shown a reason. */
    data class Failed(val why: String) : CheckResult()
}

object UpdateCheck {

    /**
     * The whole trust decision, pure: signature first, over the bytes as received, and only
     * then parse. A manifest whose signature is missing, malformed or wrong is [CheckResult.Failed]
     * — exactly as if the server were down — and is never parsed, so not one of its fields
     * (version, URL, hash) can influence anything.
     */
    fun evaluate(manifest: ByteArray?, signatureFile: ByteArray?, channel: UpdateChannel, current: Version): CheckResult {
        if (manifest == null) return CheckResult.Failed("no manifest")
        if (manifest.size > UpdateManifest.MAX_BYTES) return CheckResult.Failed("manifest too large")
        val sig = signatureFile?.let(UpdateSignature::decodeSigFile) ?: return CheckResult.Failed("no or malformed signature")
        if (!UpdateSignature.verify(channel.publicKey, manifest, sig)) return CheckResult.Failed("signature does not verify")
        val m = try {
            UpdateManifest.parse(manifest, channel.requireHost)
        } catch (e: UpdateManifest.ParseException) {
            return CheckResult.Failed("signed manifest unusable: ${e.message}")
        }
        return if (m.version > current) CheckResult.Available(m) else CheckResult.UpToDate(m.version)
    }

    /** One plain GET per file, bounded in size and time; 200 only. */
    fun fetch(http: HttpClient, url: String, limit: Int): ByteArray {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Cache-Control", "no-cache")
            .GET()
            .build()
        val resp = NetClients.sendBounded(http, req, limit, Duration.ofSeconds(45), "update file larger than $limit bytes")
        if (resp.statusCode() != 200) throw IOException("HTTP ${resp.statusCode()} for $url")
        return resp.body()
    }

    /** Fetch manifest + signature over [http] and [evaluate] them. Never throws. */
    fun check(http: HttpClient, channel: UpdateChannel, current: Version): CheckResult = try {
        val manifest = fetch(http, channel.manifestUrl, UpdateManifest.MAX_BYTES)
        val sig = fetch(http, channel.signatureUrl, 1024)
        evaluate(manifest, sig, channel, current)
    } catch (e: Exception) {
        CheckResult.Failed("${e.javaClass.simpleName}: ${e.message}")
    }

    /**
     * The update client. The same censorship hardening as every relay client ([NetClients]:
     * DoH fallback route, the system proxy, the app's TLS look and the oshi-messenger.com pins
     * installed process-wide), no cookies, no identifying header, no query string.
     */
    fun httpClient(): HttpClient = NetClients.builder()
        .connectTimeout(Duration.ofSeconds(15))
        // https → https only (the JDK's NORMAL never follows https → http). Integrity rests on
        // the signed hash, not on where the bytes came from.
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
}

/**
 * Streams an installer to disk while hashing it, and keeps it ONLY if the size and SHA-256
 * match the signed manifest. Anything else — short, long, different bytes, cancelled, stalled —
 * deletes the partial file; [download] returns normally only for a verified file.
 */
object UpdateDownloader {

    class VerificationFailed(msg: String) : IOException(msg)
    class Cancelled : IOException("cancelled")

    /** No byte for this long = the connection is dead (a frozen TSPU-style flow never RSTs). */
    var stallMs: Long = 60_000

    fun download(
        http: HttpClient,
        file: UpdateFile,
        dest: File,
        progress: (Long, Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): File {
        dest.parentFile?.let { it.mkdirs() }
        dest.delete()
        val part = File(dest.parentFile, dest.name + ".part")
        part.delete()
        try {
            val req = HttpRequest.newBuilder(URI.create(file.url)).timeout(Duration.ofSeconds(30)).GET().build()
            val sub = FileSink(part, file.size, progress)
            val future = http.sendAsync(req) { info ->
                if (info.statusCode() != 200) {
                    HttpResponse.BodySubscribers.replacing<FileSink.Done?>(null).also { sub.badStatus = info.statusCode() }
                } else sub
            }
            while (true) {
                if (cancelled()) { future.cancel(true); sub.cancel(); throw Cancelled() }
                if (System.currentTimeMillis() - sub.lastProgressAt > stallMs) {
                    future.cancel(true); sub.cancel(); throw IOException("download stalled")
                }
                try {
                    future.get(500, TimeUnit.MILLISECONDS)
                    break
                } catch (_: TimeoutException) {
                    continue
                } catch (e: java.util.concurrent.ExecutionException) {
                    val c = e.cause
                    throw if (c is IOException) c else IOException(c?.message ?: "download failed", c)
                }
            }
            sub.badStatus?.let { throw IOException("HTTP $it for ${file.url}") }
            val done = sub.result.getNow(null) ?: throw IOException("download incomplete")
            verify(file, done.size, done.sha256)
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true); part.delete()
            }
            // Re-hash what is now on disk: the file that will be run is the file that was checked.
            verify(file, dest.length(), sha256(dest))
            return dest
        } catch (e: Exception) {
            part.delete(); dest.delete()
            throw e
        }
    }

    fun verify(file: UpdateFile, size: Long, sha256: String) {
        if (size != file.size) throw VerificationFailed("size $size, the signed manifest says ${file.size}")
        if (!sha256.equals(file.sha256, ignoreCase = true)) throw VerificationFailed("sha256 $sha256, the signed manifest says ${file.sha256}")
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Writes to [file], hashing as it goes; refuses to grow past [expected] bytes. */
    private class FileSink(
        private val file: File,
        private val expected: Long,
        private val progress: (Long, Long) -> Unit,
    ) : HttpResponse.BodySubscriber<FileSink.Done?> {
        class Done(val size: Long, val sha256: String)

        val result = CompletableFuture<Done?>()
        @Volatile var lastProgressAt = System.currentTimeMillis()
        @Volatile var badStatus: Int? = null
        @Volatile private var subscription: Flow.Subscription? = null
        private val md = MessageDigest.getInstance("SHA-256")
        private var written = 0L
        private var channel: FileChannel? = null

        override fun getBody(): CompletionStage<Done?> = result

        override fun onSubscribe(s: Flow.Subscription) {
            subscription = s
            try {
                channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            } catch (e: Exception) {
                s.cancel(); result.completeExceptionally(e); return
            }
            s.request(1)
        }

        override fun onNext(items: List<ByteBuffer>) {
            if (result.isDone) return
            try {
                for (b in items) {
                    written += b.remaining()
                    if (written > expected) throw VerificationFailed("server sent more than the signed size ($expected bytes)")
                    md.update(b.duplicate())
                    while (b.hasRemaining()) channel!!.write(b)
                }
                lastProgressAt = System.currentTimeMillis()
                progress(written, expected)
                subscription?.request(1)
            } catch (e: Exception) {
                subscription?.cancel(); close(); result.completeExceptionally(e)
            }
        }

        override fun onError(t: Throwable) { close(); result.completeExceptionally(t) }

        override fun onComplete() {
            close()
            result.complete(Done(written, md.digest().joinToString("") { "%02x".format(it) }))
        }

        fun cancel() { subscription?.cancel(); close(); result.completeExceptionally(Cancelled()) }

        private fun close() { runCatching { channel?.close() }; channel = null }
    }
}
