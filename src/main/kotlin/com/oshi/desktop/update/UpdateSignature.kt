package com.oshi.desktop.update

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.util.Base64

/**
 * __DESKTOP_AUTO_UPDATE_2026_11_21__ Ed25519 over the EXACT manifest bytes.
 *
 * An updater that runs what a web server hands it is a remote-code-execution channel for
 * whoever controls that server, its TLS, or the user's DNS. OSHI is a security product, so the
 * installer is trusted only through this chain:
 *
 *   key compiled into the app  →  signature over desktop-latest.json  →  sha256 + size of the
 *   installer, inside that signed JSON  →  the downloaded bytes.
 *
 * TLS pinning (CertificatePins) is a second layer, not the one this rests on. The private key
 * is offline on the release machine (`~/.oshi-release/desktop-update-ed25519.key`), never on
 * the web server: owning oshi-messenger.com is not enough to push an installer.
 *
 * BouncyCastle rather than the JDK's `Ed25519` provider: the project targets JVM 11 bytecode
 * and BC (already the crypto dependency) gives the same result on every runtime.
 */
object UpdateSignature {

    /** Strict RFC 8032 verification. False for anything malformed — never throws. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != Ed25519PublicKeyParameters.KEY_SIZE || signature.size != 64) return false
        return try {
            val v = Ed25519Signer()
            v.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            v.update(message, 0, message.size)
            v.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    /** The `.sig` file: base64 of the 64 signature bytes, surrounding whitespace ignored. */
    fun decodeSigFile(bytes: ByteArray): ByteArray? {
        if (bytes.size > 1024) return null
        return try {
            Base64.getDecoder().decode(String(bytes, Charsets.US_ASCII).trim()).takeIf { it.size == 64 }
        } catch (_: Exception) {
            null
        }
    }

    /** SHA-256 of the raw public key, hex — the fingerprint `update_manifest.py` prints. */
    fun fingerprint(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey).joinToString("") { "%02x".format(it) }
}

/**
 * Where the manifest comes from and which key must have signed it.
 *
 * There is ONE way to point the app elsewhere, for the PC smoke run and local testing: set
 * BOTH `OSHI_UPDATE_MANIFEST_URL` and `OSHI_UPDATE_PUBKEY`. A URL alone is IGNORED — the
 * production key still applies to the production URL — so an override can never make the app
 * trust the production key for a manifest served from somewhere else, nor accept an unsigned
 * one. Whoever can set both variables in the user's environment can already inject code into
 * the JVM (`JAVA_TOOL_OPTIONS`), so the pair grants nothing new. The test channel is shown as
 * such in Settings.
 */
data class UpdateChannel(
    val manifestUrl: String,
    val publicKey: ByteArray,
    /** Download URLs must be https on this host; null on a test channel. */
    val requireHost: String?,
    val isTest: Boolean,
) {
    val signatureUrl: String get() = "$manifestUrl.sig"

    override fun equals(other: Any?) = other is UpdateChannel && other.manifestUrl == manifestUrl &&
        other.publicKey.contentEquals(publicKey) && other.requireHost == requireHost && other.isTest == isTest
    override fun hashCode() = manifestUrl.hashCode() * 31 + publicKey.contentHashCode()

    companion object {
        const val PRODUCTION_URL = "https://oshi-messenger.com/downloads/desktop-latest.json"
        const val PRODUCTION_HOST = "oshi-messenger.com"

        /**
         * The release signing key (`release/update_manifest.py pubkey`).
         * Fingerprint sha256:3ca413091fbd83cbea3e186c7cf3300a846c6c6d889f90985c82788c7f4b0857.
         * Rotating it means every installed app stops accepting updates until the user
         * reinstalls by hand — ship any new key in a release signed by the OLD one first.
         */
        const val PRODUCTION_PUBLIC_KEY_B64 = "as8MMx9JfcUcaGacsD47GXtHW6gT3k0QLZ7UdXP096Q="

        val PRODUCTION = UpdateChannel(
            PRODUCTION_URL, Base64.getDecoder().decode(PRODUCTION_PUBLIC_KEY_B64), PRODUCTION_HOST, isTest = false,
        )

        fun fromEnv(env: (String) -> String? = System::getenv): UpdateChannel {
            val url = env("OSHI_UPDATE_MANIFEST_URL")?.trim().orEmpty()
            val key = env("OSHI_UPDATE_PUBKEY")?.trim().orEmpty()
            if (url.isEmpty() || key.isEmpty()) return PRODUCTION
            val raw = runCatching { Base64.getDecoder().decode(key) }.getOrNull()
            val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
            if (raw == null || raw.size != 32 || (scheme != "https" && scheme != "http")) return PRODUCTION
            return UpdateChannel(url, raw, requireHost = null, isTest = true)
        }
    }
}
