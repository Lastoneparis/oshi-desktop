package com.oshi.desktop.net

import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/**
 * __DESKTOP_CERT_PINNING_2026_11_20__ SPKI pinning for `oshi-messenger.com`, as both phones do.
 *
 * Before: the desktop trusted any certificate the JDK's cacerts accepted, while Android pins
 * (`network_security_config.xml`, expiration 2027-06-01) and iOS pins (`V2Client.apiSPKIPins`).
 * A censor or an employer able to get a certificate from ANY public CA — or to install a root
 * on a managed machine — could read and rewrite every relay request from the desktop only.
 * Content stays end-to-end encrypted, but who talks to whom, when, and the prekey bundles
 * (TOFU) do not.
 *
 * The set is the UNION of the two phones' pins, so the desktop accepts exactly what one of
 * them would: the old and current leaf, Let's Encrypt YE1 and YE2, ISRG Root YE. Measured
 * 2026-11-20: the server presents leaf `tcs1…` ← YE1 `brzv…` ← Root YE `sCkq…`.
 * Like Android, enforcement ends at [EXPIRES] (an app that is never updated must not lock
 * itself out when the server rotates past every pin).
 *
 * A pin is accepted only on the ISSUER CHAIN actually linking the leaf (each certificate
 * verified by the next one's key), never merely because a pinned certificate was appended to
 * what the server sent. Normal PKIX validation runs first, unchanged.
 *
 * Installed as the process's default [SSLContext] ([installDefault]), so every client — the
 * V2 relay, calls, devsync, mail, tiles, `HttpsURLConnection` — is covered, plus the
 * Conscrypt context in [TlsLook]. Hosts other than oshi-messenger.com are not pinned.
 */
object CertificatePins {
    const val HOST = "oshi-messenger.com"
    val EXPIRES: LocalDate = LocalDate.of(2027, 6, 1)

    val PINS: Set<String> = setOf(
        "0nAG9HjNRcCEVoDETqe5BKVXl97VZqmyVK+VikDj1r4=", // previous leaf (Android)
        "tcs1FWdgXPmNWGfDyxW1PXxrJ6C5uP1xyppLl1E/Wdg=", // current leaf (iOS)
        "brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=", // Let's Encrypt YE1 (iOS)
        "s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=", // Let's Encrypt YE2 (both)
        "sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=", // ISRG Root YE (Android)
    )

    fun isPinnedHost(host: String?): Boolean {
        val h = host?.trimEnd('.')?.lowercase() ?: return false
        return h == HOST || h.endsWith(".$HOST")
    }

    fun spki(cert: X509Certificate): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

    /** The certificates that really sign one another, from the leaf up. */
    fun linkedChain(sent: Array<out X509Certificate>): List<X509Certificate> {
        if (sent.isEmpty()) return emptyList()
        val out = arrayListOf(sent[0])
        var cur = sent[0]
        while (out.size < 10) {
            if (cur.subjectX500Principal == cur.issuerX500Principal) break
            val next = sent.firstOrNull { cand ->
                cand !== cur && cand.subjectX500Principal == cur.issuerX500Principal &&
                    runCatching { cur.verify(cand.publicKey); true }.getOrDefault(false)
            } ?: break
            out += next
            cur = next
        }
        return out
    }

    fun check(host: String?, sent: Array<out X509Certificate>?, today: LocalDate = LocalDate.now(ZoneOffset.UTC)) {
        if (!isPinnedHost(host) || !today.isBefore(EXPIRES)) return
        val chain = linkedChain(sent ?: emptyArray())
        if (chain.none { spki(it) in PINS }) {
            throw CertificateException("certificate pin mismatch for $host: none of ${chain.map(::spki)} is an OSHI pin")
        }
    }

    /** Wrap [d] so that a pinned host must also match a pin. */
    class PinningTrust(private val d: X509ExtendedTrustManager) : X509ExtendedTrustManager() {
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, s: Socket?) {
            d.checkServerTrusted(c, a, s)
            check((s as? javax.net.ssl.SSLSocket)?.handshakeSession?.peerHost ?: s?.inetAddress?.hostName, c)
        }
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) {
            d.checkServerTrusted(c, a, e)
            check(e?.peerHost, c)
        }
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) = d.checkServerTrusted(c, a)
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, s: Socket?) = d.checkClientTrusted(c, a, s)
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) = d.checkClientTrusted(c, a, e)
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = d.checkClientTrusted(c, a)
        override fun getAcceptedIssuers(): Array<X509Certificate> = d.acceptedIssuers
    }

    fun jdkTrust(): X509ExtendedTrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
    }

    @Volatile private var installed = false

    /** Make the pinning context the JVM default. Idempotent; never throws. */
    fun installDefault() {
        if (installed) return
        runCatching {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(PinningTrust(jdkTrust())), SecureRandom())
            SSLContext.setDefault(ctx)
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
            installed = true
        }
    }
}
