package com.oshi.desktop.net

import java.net.Socket
import java.nio.ByteBuffer
import java.util.function.BiFunction
import java.net.http.HttpClient
import java.security.KeyStore
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLContextSpi
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/**
 * __TLS_LOOK_2026_11_19__ Make the desktop's TLS ClientHello look like an Android app's.
 *
 * The JDK's TLS stack sends a hello that no home device sends: 31 cipher suites, 13
 * extensions incl. `status_request_v2` and `signature_algorithms_cert`, 10 groups incl.
 * ffdhe — JA4 `t13d3113h2_1d947a95fc68_7e1102d2036b` (JDK 17 and 21, measured). Blocking
 * that string costs a censor nothing at home (Geedge/MESA ship JA3 signatures; Russia
 * blocked Snowflake by its DTLS hello in 03/2026).
 *
 * With Conscrypt (BoringSSL — the TLS stack of every Android app that uses OkHttp) and
 * the same knobs OkHttp sets on Android, the desktop's hello is the Android one:
 *  * cipher suites = OkHttp `MODERN_TLS`, in OkHttp's order (15);
 *  * groups X25519, P-256, P-384 (what Android 15's Conscrypt offered; Conscrypt 2.8's own
 *    default adds X25519MLKEM768, which would make the hello ~1.2 KB and unlike Android's);
 *  * session tickets on (OkHttp turns them on through `Conscrypt.setUseSessionTickets`);
 *  * ALPN `h2,http/1.1`, set by the JDK client itself.
 *
 * Measured (local capture, docs/CENSORSHIP_2026_THREAT_MODEL.md §3): JDK
 * `t13d3113h2_1d947a95fc68_7e1102d2036b` → this `t13d1512h2_8daaf6152771_40271e0a5736`.
 * Android 15 OkHttp: `t13d1513h2_8daaf6152771_eca864cca44a` with SNI — same ciphers (part
 * b), same order, same extension order; the one difference is BoringSSL's `padding`
 * extension, which Conscrypt 2.8's BoringSSL no longer adds and which cannot be set from
 * Java. Without padding (Android's own hello without SNI) the part c hash is identical.
 *
 * Certificates: the JDK's default PKIX trust manager and the JDK's hostname check
 * (`endpointIdentificationAlgorithm=HTTPS`, set by `HttpClient`) still decide — measured:
 * wrong host, expired and self-signed are refused. One adaptation: Conscrypt reports the
 * TLS 1.3 auth type as `GENERIC`, which the JDK trust manager does not know; it is mapped
 * to `UNKNOWN`, the JDK's own TLS 1.3 value.
 *
 * Fallback: no Conscrypt native for this host (Windows on ARM) or it fails to load →
 * [apply] leaves the builder on the JDK stack, i.e. exactly as before. Never a half-curated
 * JDK hello: a unique hybrid would be easier to single out than the stock Java one.
 */
object TlsLook {

    /** OkHttp 4.12 `ConnectionSpec.MODERN_TLS`, OkHttp order — what Android OkHttp offers. */
    val CIPHER_SUITES = arrayOf(
        "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
        "TLS_RSA_WITH_AES_128_GCM_SHA256", "TLS_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA",
    )
    val NAMED_GROUPS = arrayOf("X25519", "P-256", "P-384")

    private class Look(val context: SSLContext, val parameters: SSLParameters)

    /** Why the JDK stack is used instead (null = Conscrypt active). For diagnostics. */
    @Volatile var unavailableReason: String? = null
        private set

    private val look: Look? by lazy {
        if (System.getProperty("oshi.tls.look", "android") == "jdk") { unavailableReason = "disabled"; return@lazy null }
        val built = try { build() } catch (t: Throwable) { unavailableReason = "${t.javaClass.simpleName}: ${t.message}"; null }
        // One line on stderr, so a packaged build can be checked from a terminal: the
        // fallback is otherwise silent and the JDK fingerprint goes out unnoticed.
        System.err.println(
            if (built != null) "[tls-look] conscrypt active (${built.context.provider.name} ${built.context.provider.versionStr})"
            else "[tls-look] JDK stack, conscrypt unavailable: $unavailableReason"
        )
        built
    }

    /** True when relay-facing clients use the Android-like hello. */
    val active: Boolean get() = look != null

    /** Give [b] the Android-like TLS stack when available; otherwise leave it on the JDK stack. */
    fun apply(b: HttpClient.Builder): HttpClient.Builder {
        val l = look ?: return b
        // A fresh copy: HttpClient keeps and mutates (ALPN) its own parameters.
        val p = SSLParameters(l.parameters.cipherSuites, l.parameters.protocols)
        return b.sslContext(l.context).sslParameters(p)
    }

    private fun build(): Look {
        val provider = Class.forName("org.conscrypt.OpenSSLProvider").getDeclaredConstructor().newInstance() as Provider
        val conscrypt = Class.forName("org.conscrypt.Conscrypt")
        val tickets = conscrypt.getMethod("setUseSessionTickets", SSLEngine::class.java, Boolean::class.javaPrimitiveType)
        val groups = conscrypt.getMethod("setNamedGroups", SSLSocketFactory::class.java, Array<String>::class.java)

        val real = SSLContext.getInstance("TLS", provider)
        real.init(null, arrayOf<TrustManager>(jdkTrust()), SecureRandom())
        // The socket factory shares the context's parameter object, so this sets the groups
        // every engine of [real] starts from (there is no per-engine setter in Conscrypt 2.8).
        groups.invoke(null, real.socketFactory, NAMED_GROUPS)

        val spi = object : SSLContextSpi() {
            override fun engineInit(km: Array<out KeyManager>?, tm: Array<out TrustManager>?, sr: SecureRandom?) {}
            override fun engineGetSocketFactory(): SSLSocketFactory = real.socketFactory
            override fun engineGetServerSocketFactory(): SSLServerSocketFactory = real.serverSocketFactory
            override fun engineCreateSSLEngine(): SSLEngine = tune(real.createSSLEngine())
            override fun engineCreateSSLEngine(host: String?, port: Int): SSLEngine = tune(real.createSSLEngine(host, port))
            override fun engineGetServerSessionContext(): SSLSessionContext = real.serverSessionContext
            override fun engineGetClientSessionContext(): SSLSessionContext = real.clientSessionContext
            override fun engineGetDefaultSSLParameters(): SSLParameters = withCiphers(real.defaultSSLParameters)
            override fun engineGetSupportedSSLParameters(): SSLParameters = real.supportedSSLParameters
            private fun tune(e: SSLEngine): SSLEngine { tickets.invoke(null, e, true); return GroupKeepingEngine(e) }
        }
        val ctx = object : SSLContext(spi, provider, "TLS") {}
        val params = withCiphers(real.defaultSSLParameters)
        // Fail here, not on the first message, if this build cannot speak to anything.
        require(params.cipherSuites.size == CIPHER_SUITES.size) { "cipher suites missing: ${params.cipherSuites.toList()}" }
        return Look(ctx, params)
    }

    private fun withCiphers(p: SSLParameters): SSLParameters {
        p.cipherSuites = CIPHER_SUITES
        p.protocols = arrayOf("TLSv1.3", "TLSv1.2")
        return p
    }

    /** The JDK's default PKIX trust manager (cacerts), with Conscrypt's TLS 1.3 auth type mapped. */
    private fun jdkTrust(): X509ExtendedTrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val jdk = tmf.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
        return MappedTrust(jdk)
    }

    // __TLS_LOOK_GROUPS_JDK20_2026_11_19__ On JDK 20+ `SSLParameters` has `namedGroups`, and
    // Conscrypt copies it over the engine's own in `setSSLParameters` (Java9PlatformUtil). The
    // JDK HttpClient hands every engine a copy of its parameters made by
    // `Utils.copySSLParameters`, which does NOT copy `namedGroups` (JDK 21.0.12, checked by
    // javap) ⇒ null ⇒ Conscrypt's default groups, X25519MLKEM768 first. Measured on JDK 21:
    // the hello offered 4588,29,23,24 instead of 29,23,24 — a ~1.2 KB hello unlike Android's,
    // and the packaged app bundles JDK 21. Setting the groups on the builder's parameters
    // cannot help (the copy drops them), so the engine puts them back on whatever it is given.
    // JDK < 20 has no `setNamedGroups`: Conscrypt then keeps the groups it was created with.
    private val setNamedGroups = runCatching {
        SSLParameters::class.java.getMethod("setNamedGroups", Array<String>::class.java)
    }.getOrNull()

    internal class GroupKeepingEngine(private val d: SSLEngine) : SSLEngine(d.peerHost, d.peerPort) {
        override fun setSSLParameters(p: SSLParameters) {
            setNamedGroups?.invoke(p, NAMED_GROUPS.clone() as Any)
            d.sslParameters = p
        }
        override fun getSSLParameters(): SSLParameters = d.sslParameters
        override fun wrap(srcs: Array<out ByteBuffer>, off: Int, len: Int, dst: ByteBuffer): SSLEngineResult = d.wrap(srcs, off, len, dst)
        override fun unwrap(src: ByteBuffer, dsts: Array<out ByteBuffer>, off: Int, len: Int): SSLEngineResult = d.unwrap(src, dsts, off, len)
        override fun getDelegatedTask(): Runnable? = d.delegatedTask
        override fun closeInbound() = d.closeInbound()
        override fun isInboundDone(): Boolean = d.isInboundDone
        override fun closeOutbound() = d.closeOutbound()
        override fun isOutboundDone(): Boolean = d.isOutboundDone
        override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
        override fun getEnabledCipherSuites(): Array<String> = d.enabledCipherSuites
        override fun setEnabledCipherSuites(s: Array<out String>) { d.enabledCipherSuites = s }
        override fun getSupportedProtocols(): Array<String> = d.supportedProtocols
        override fun getEnabledProtocols(): Array<String> = d.enabledProtocols
        override fun setEnabledProtocols(s: Array<out String>) { d.enabledProtocols = s }
        override fun getSession(): SSLSession = d.session
        override fun getHandshakeSession(): SSLSession? = d.handshakeSession
        override fun beginHandshake() = d.beginHandshake()
        override fun getHandshakeStatus(): SSLEngineResult.HandshakeStatus = d.handshakeStatus
        override fun setUseClientMode(m: Boolean) { d.useClientMode = m }
        override fun getUseClientMode(): Boolean = d.useClientMode
        override fun setNeedClientAuth(v: Boolean) { d.needClientAuth = v }
        override fun getNeedClientAuth(): Boolean = d.needClientAuth
        override fun setWantClientAuth(v: Boolean) { d.wantClientAuth = v }
        override fun getWantClientAuth(): Boolean = d.wantClientAuth
        override fun setEnableSessionCreation(v: Boolean) { d.enableSessionCreation = v }
        override fun getEnableSessionCreation(): Boolean = d.enableSessionCreation
        override fun getApplicationProtocol(): String? = d.applicationProtocol
        override fun getHandshakeApplicationProtocol(): String? = d.handshakeApplicationProtocol
        override fun setHandshakeApplicationProtocolSelector(s: BiFunction<SSLEngine, MutableList<String>, String>?) {
            d.handshakeApplicationProtocolSelector = s
        }
        override fun getHandshakeApplicationProtocolSelector(): BiFunction<SSLEngine, MutableList<String>, String>? =
            d.handshakeApplicationProtocolSelector
    }

    internal class MappedTrust(private val d: X509ExtendedTrustManager) : X509ExtendedTrustManager() {
        private fun m(a: String?) = if (a == null || a == "GENERIC") "UNKNOWN" else a
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, s: Socket?) = d.checkServerTrusted(c, m(a), s)
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) = d.checkServerTrusted(c, m(a), e)
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) = d.checkServerTrusted(c, m(a))
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, s: Socket?) = d.checkClientTrusted(c, a, s)
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) = d.checkClientTrusted(c, a, e)
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = d.checkClientTrusted(c, a)
        override fun getAcceptedIssuers(): Array<X509Certificate> = d.acceptedIssuers
    }
}
