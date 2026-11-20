package com.oshi.desktop.net

import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * __CENSOR_2026_11_19__ The one place a relay-facing JDK `HttpClient` gets its censorship
 * hardening, so the signed V2 client, the cover's short-lived clients, the rollout gate and
 * the push wake-up cannot drift apart:
 *
 *  * [DnsFallbackRoute] — DNS-over-HTTPS when the system resolver lies (see [CoverDns]);
 *  * [TlsLook] — an Android-app TLS ClientHello instead of the rare JDK one.
 *
 * Callers still set their own timeouts, redirect policy and HTTP version.
 */
object NetClients {
    fun builder(): HttpClient.Builder =
        TlsLook.apply(HttpClient.newBuilder()).proxy(DnsFallbackRoute.DEFAULT)

    /**
     * __COVER_SHORT_CONNECTIONS_2026_11_19__ `send()` whose WHOLE exchange — headers AND body —
     * must finish within [timeout], body capped at [limit] bytes ([tooLarge] is the message).
     *
     * The JDK request timeout stops at the response headers. A per-connection volume cap
     * (Russia TSPU, netem `tspu16k`/`tspu8p`) freezes a connection mid-body with no RST, so a
     * body read blocked forever: measured, the desktop's poll thread hung after its 1st or 2nd
     * pull and received 1/8 texts. Here the caller is freed at the deadline with an
     * [HttpTimeoutException] (a transport failure: it opens the short-connection window).
     */
    fun sendBounded(client: HttpClient, req: HttpRequest, limit: Int, timeout: Duration, tooLarge: String): HttpResponse<ByteArray> {
        val f = client.sendAsync(req) { BoundedBody(limit, tooLarge) }
        try {
            return f.get(timeout.toMillis() + 1_000, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true)
            throw HttpTimeoutException("response not complete within ${timeout.seconds} s")
        } catch (e: ExecutionException) {
            val c = e.cause
            throw if (c is IOException) c else IOException(c?.message ?: "exchange failed", c)
        } catch (e: InterruptedException) {
            f.cancel(true); Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        }
    }

    private class BoundedBody(private val limit: Int, private val tooLarge: String) : HttpResponse.BodySubscriber<ByteArray> {
        private val out = java.io.ByteArrayOutputStream()
        private val result = CompletableFuture<ByteArray>()
        @Volatile private var sub: Flow.Subscription? = null
        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(s: Flow.Subscription) { sub = s; s.request(Long.MAX_VALUE) }
        override fun onNext(items: List<ByteBuffer>) {
            if (result.isDone) return
            for (b in items) {
                if (out.size().toLong() + b.remaining() > limit) {
                    sub?.cancel(); result.completeExceptionally(IOException(tooLarge)); return
                }
                val a = ByteArray(b.remaining()); b.get(a); out.write(a)
            }
        }
        override fun onError(t: Throwable) { result.completeExceptionally(t) }
        override fun onComplete() { result.complete(out.toByteArray()) }
    }

    /** A builder carrying [like]'s proxy route (and TLS setup), for a short-lived twin of it. */
    fun builderLike(like: HttpClient): HttpClient.Builder {
        val b = HttpClient.newBuilder()
        like.proxy().ifPresent { b.proxy(it) }
        b.sslContext(like.sslContext())
        b.sslParameters(like.sslParameters())
        return b
    }
}
