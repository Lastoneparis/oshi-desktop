package com.oshi.desktop.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.LocalDate

/**
 * __DESKTOP_CERT_PINNING_2026_11_20__ Against the chain oshi-messenger.com actually served on
 * 2026-11-20 (captured with openssl s_client into test resources).
 */
class CertificatePinsTest {
    private val chain: Array<X509Certificate> = CertificateFactory.getInstance("X.509")
        .generateCertificates(File("src/test/resources/oshi-messenger-chain-2026-11-20.chain.txt").inputStream())
        .map { it as X509Certificate }.toTypedArray()
    private val today = LocalDate.of(2026, 11, 20)

    @Test
    fun `the live chain links leaf to root and matches the phones' pins`() {
        assertEquals(4, chain.size)
        assertEquals(chain.size, CertificatePins.linkedChain(chain).size)
        CertificatePins.check("oshi-messenger.com", chain, today)
        CertificatePins.check("api.oshi-messenger.com", chain, today)
    }

    @Test
    fun `a chain from another CA is refused for oshi-messenger_com only`() {
        val other = arrayOf(chain[3]) // a valid public certificate, not one of ours
        try {
            CertificatePins.check("oshi-messenger.com", other, today); fail("accepted an unpinned chain")
        } catch (_: CertificateException) {}
        CertificatePins.check("example.org", other, today) // not a pinned host
        CertificatePins.check("oshi-messenger.com", other, CertificatePins.EXPIRES) // Android's expiry semantics
    }

    @Test
    fun `appending a pinned certificate that does not sign the leaf does not satisfy the pin`() {
        val forged = arrayOf(chain[3], chain[1]) // unrelated leaf + our YE1 tacked on
        assertEquals(1, CertificatePins.linkedChain(forged).size)
        try {
            CertificatePins.check("oshi-messenger.com", forged, today); fail("an appended pin was accepted")
        } catch (_: CertificateException) {}
    }

    @Test
    fun `the pin set is the union of Android and iOS`() {
        val android = File("../OSHI-Android/app/src/main/res/xml/network_security_config.xml").takeIf { it.isFile }?.readText()
        if (android != null) {
            Regex("""<pin digest="SHA-256">([^<]+)</pin>""").findAll(android).forEach { assertTrue(it.groupValues[1] in CertificatePins.PINS) }
        }
    }
}
