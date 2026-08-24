package com.oshi.desktop

import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.OSHIRatchetV2
import com.oshi.messenger.network.v2.V2Session
import com.oshi.messenger.network.v2.FetchedBundle
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * OSHI Desktop protocol skeleton.
 *
 * This is not an app. It is a demonstration that the hard part of a third OSHI client
 * — X3DH, the Double Ratchet, and the exact v2 wire format — already runs on a plain
 * desktop JVM, using the SAME Kotlin source files Android compiles, with no port.
 *
 * Run:  ./gradlew run
 *       ./gradlew run --args="--probe"     (adds one read-only GET to the live server)
 *       ./gradlew run --args="--mesh"      (a live mesh node: mDNS discovery + TCP)
 */
fun main(args: Array<String>) {
    // The mesh node is a long-running program, not a demo that prints and exits, so it
    // takes over main() entirely rather than being step 6 of the walkthrough.
    if (args.contains("--mesh")) {
        com.oshi.desktop.mesh.runMeshCli(args)
        return
    }

    val probe = args.contains("--probe")

    hr("OSHI Desktop — protocol skeleton")
    println("JVM            : ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
    println("OS             : ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    println("Crypto core    : compiled from OSHI-Android/app/src/main/java/com/oshi/messenger/network/v2")
    println("                 (shared source, zero copies — see build.gradle.kts)")

    step1KnownAnswerVectors()
    step2LiveSession()
    step3WireFormat()
    step4RequestSigning()
    if (probe) step5ServerProbe() else {
        hr("5. Live server probe")
        println("   skipped (pass --probe to run one read-only GET /v2/config)")
    }

    hr("Result")
    println("""
        The desktop JVM reproduced iOS's published crypto vectors byte for byte, ran a
        full X3DH + Double Ratchet conversation including an out-of-order message and a
        DH ratchet step, and emitted a v=4 relay envelope. Nothing in the crypto path
        was reimplemented for this platform.

        What is NOT proven here, and must not be assumed: key storage, the UI, media
        blobs, groups, calls. See PLAN.md.

        Mesh is a separate, working thing: `--mesh` runs a real node that discovers
        phones over mDNS and talks to them over TCP. It moves OPAQUE payloads — the
        ratchet above is not wired into it yet. See PLAN_MESH.md.
    """.trimIndent())
}

/**
 * The load-bearing proof. These are iOS's own known-answer vectors, taken verbatim from
 * OSHI-Android/app/src/test/java/com/oshi/messenger/V2CryptoVectorTest.kt, which in turn
 * inlines them from iOS OSHICryptoV2Tests.swift / test_vectors.json.
 *
 * If a desktop client can reproduce these, it is byte-compatible with BOTH shipped
 * platforms at the crypto layer. If it cannot, nothing else it does matters.
 */
private fun step1KnownAnswerVectors() {
    hr("1. iOS known-answer vectors, reproduced on the desktop JVM")

    val aIk = OSHICryptoV2.X25519Pair(
        hex("9075dca881d3bf1d61bb3d8887500ae4ab7e07533c7f2c23e918f87c49dde973"),
        hex("ca548efd90c81b073386d49b0a46fd9fcd9793180fadc0ea3cbadcfd39962403"),
    )
    val aEk = OSHICryptoV2.X25519Pair(
        hex("40fdf08a94a157da384df3c5b985d12089c5195e41deef35aec01c0f64916a74"),
        hex("f88ae69adedcc56713189704c703c7a2253fed76643a2ff3750ac5ffd5014475"),
    )
    val sk = OSHICryptoV2.x3dhInitiator(
        aIk, aEk,
        hex("3854d658e7ee3f7e1e99569048a64ab6a4da2ebe8bed8f71b8190abba1ab5e05"),
        hex("2ff3c27cbe727972e6d594336c8611311fa14740afaffda1e950e034944bc906"),
        hex("417e827f8aa889a74248fa298f0bbed6d06a4616239297ea24266d4456613947"),
    )
    check("X3DH shared secret", hexStr(sk),
        "1e698eedf60da463caacead61efe258cc730a8556050c2841d24ebfc621c9223")

    val drSk = hex("591df6ebb3bbeed6e2d2db39a6e8197fe3b9b6bc1196abcf5e9b174d07a55e88")
    val aRatchetPriv = hex("f0d6f7b0631bbdf92528320af8673fee53bde46120ab3da0b36d6490ef245760")
    val aRatchetPub = hex("5206c6092f1d552164eb33ebe66c7d943737b5de5d9f238073e59cbb97b05e6e")
    val bRatchetPub = hex("cb6a5743bff9c4fb0239d57895c7f5214a40562da2e806728d7f3ff316c41518")

    val (rk, cks) = OSHICryptoV2.kdfRK(drSk, OSHICryptoV2.dh(aRatchetPriv, bRatchetPub))
    check("KDF_RK root key", hexStr(rk),
        "bfc6c4f0408338cf0368387a2e4493a5432b220a1039114ff52783389afaa67f")
    check("KDF_RK chain key", hexStr(cks),
        "bbe73b2fd3d6a904b66539137da48d606da192491802cc6900b9a197f666aa7b")

    val (_, mk) = OSHICryptoV2.kdfCK(cks)
    check("KDF_CK message key", hexStr(mk),
        "3f87018c970f224b743b0c431894ba4774b7b4ec31412b728cd2fdd7a03f3cd1")

    val (key, nonce) = OSHICryptoV2.deriveMsgKey(mk)
    check("MSG key", hexStr(key),
        "88e46d8088fa5014d18ee333b94c8def8840fd98bdf4e9f063751ba2897734f5")
    check("MSG nonce", hexStr(nonce), "8bd0f0abfa88825abc68fc14")

    val header = OSHICryptoV2.Header(dh = aRatchetPub, pn = 0, n = 0)
    val aad = hex("6f7368692d61642d7632") + OSHICryptoV2.encodeHeader(header)
    val ct = OSHICryptoV2.aesGcmSeal(
        key, nonce, hex("68656c6c6f206f73686920646f75626c652072617463686574"), aad)
    check("AES-256-GCM ciphertext‖tag", hexStr(ct),
        "804127cba25e2ec4e966150459b39a0a4f4e81c044c8d8defd83420006e3267838de6f4715d059c1e6")

    val fileNonce = OSHICryptoV2.chunkNonce(hex("9122e59770302611"), 0)
    check("file chunk nonce", hexStr(fileNonce), "9122e5977030261100000000")
    check("file chunk ciphertext", hexStr(OSHICryptoV2.aesGcmSeal(
        hex("4eb8b5d38eb2262b1893c228e3773473b4fa5a7a082e74de32d0fe99e033e1f5"),
        fileNonce,
        hex("4f5348492066696c65206368756e6b207a65726f207061796c6f61642030313233343536373839"),
        ByteArray(0))),
        "60db9693ed70c16f04514ca550ebdbf48f71df2687cec9b89d1682238d42fd7ebe96f1db8f0e23ef26bdaa6b5e0f17b2ae923d7847cf9e")
}

/** A whole conversation: X3DH, in-order, out-of-order, and a DH ratchet on the reply. */
private fun step2LiveSession() {
    hr("2. Full X3DH + Double Ratchet conversation (desktop <-> desktop)")

    val alice = DesktopIdentity.generate()
    val bob = DesktopIdentity.generate()
    val bobSpk = OSHICryptoV2.generateX25519()
    val bobOpk = OSHICryptoV2.generateX25519()

    // Bob's published bundle, signed exactly as V2KeysClient.publish() would sign it.
    val spkSignature = bob.sign(bobSpk.pub)
    val bundle = FetchedBundle(
        identityKey = bob.identity.pub,
        signingKey = bob.signingPub,
        signedPreKeyId = "spk-1",
        signedPreKey = bobSpk.pub,
        oneTimePreKeyId = "opk-7",
        oneTimePreKey = bobOpk.pub,
    )

    // The two checks V2KeysClient runs before it will TRUST a fetched bundle.
    require(DesktopIdentity.verify(bobSpk.pub, spkSignature, bob.signingPub)) { "SPK signature" }
    require(DesktopIdentity.B64.encodeToString(bundle.identityKey) == bob.userKey) { "TOFU" }
    println("   bundle: SPK Ed25519 signature verified, TOFU identity==address verified")

    val init = V2Session.initiator(alice.identity, bundle)

    // RESPONDER-SIDE TOFU. Android shipped without this: the x3dh header's identityKey
    // was never compared to the envelope's `from`, so anyone able to POST a relay
    // envelope could bootstrap a session under a spoofed sender. Byte equality on the
    // exact wire string — no base64 normalisation, no canonicalIdentity folding, both
    // of which differ between iOS and Android and must never touch this check.
    require(DesktopIdentity.B64.encodeToString(init.x3dh.identityKey) == alice.userKey) {
        "x3dh.identityKey != envelope.from — possible sender spoof"
    }
    println("   responder TOFU: x3dh.identityKey == envelope.from verified")

    val bobState = V2Session.responder(bob.identity, bobSpk, bobOpk, init.x3dh)
    println("   X3DH established; Alice is initiator, Bob recomputed the same SK")

    // PRODUCTION associated data is EMPTY. V2MessageRouter passes ByteArray(0), so the
    // effective AAD is exactly the 40 raw header bytes that OSHIRatchetV2 appends.
    // The "oshi-ad-v2" string in step 1 is a TEST-VECTOR value only; using it here would
    // produce ciphertext neither iPhone nor Android could open.
    val ad = ByteArray(0)

    val (h1, c1) = OSHIRatchetV2.encrypt(init.state, "hello from desktop".toByteArray(), ad)
    val (h2, c2) = OSHIRatchetV2.encrypt(init.state, "second message".toByteArray(), ad)
    val (h3, c3) = OSHIRatchetV2.encrypt(init.state, "third message".toByteArray(), ad)

    // Deliver OUT OF ORDER — 3 then 1 then 2 — the way a lossy relay actually behaves.
    println("   delivering #3, #1, #2 out of order:")
    println("     #3 -> \"${String(OSHIRatchetV2.decrypt(bobState, h3, c3, ad))}\"")
    println("     #1 -> \"${String(OSHIRatchetV2.decrypt(bobState, h1, c1, ad))}\"")
    println("     #2 -> \"${String(OSHIRatchetV2.decrypt(bobState, h2, c2, ad))}\"")

    // Bob replies: forces a DH ratchet step on both sides.
    val (h4, c4) = OSHIRatchetV2.encrypt(bobState, "reply, ratchet stepped".toByteArray(), ad)
    println("     #4 <- \"${String(OSHIRatchetV2.decrypt(init.state, h4, c4, ad))}\"  (DH ratchet)")
}

/** The v=4 relay envelope, and the traps baked into it. */
private fun step3WireFormat() {
    hr("3. v=4 relay envelope")

    val alice = DesktopIdentity.generate()
    val bob = DesktopIdentity.generate()
    val bobSpk = OSHICryptoV2.generateX25519()
    val init = V2Session.initiator(alice.identity, FetchedBundle(
        bob.identity.pub, bob.signingPub, "spk-1", bobSpk.pub, null, null))
    val (header, ct) = OSHIRatchetV2.encrypt(
        init.state, "wire format demo".toByteArray(), ByteArray(0))

    val env = DesktopEnvelope(
        msgId = UUID.randomUUID().toString(),
        from = alice.userKey,
        to = bob.userKey,
        x3dh = init.x3dh,                       // rides message #1 ONLY
        header = OSHICryptoV2.encodeHeader(header),
        ciphertext = ct,
        ts = System.currentTimeMillis(),        // epoch MILLIS, not seconds, not 2001
    )
    println("   " + String(env.toWireBytes()))
    println()
    println("   header is 40 bytes raw: dh(32) ‖ pn(u32be) ‖ n(u32be) = ${OSHICryptoV2.encodeHeader(header).size}")
    println("   groupId omitted (null), oneTimePreKeyId omitted (bundle pool drained)")
    println("   -- an explicit JSON null for either is the classic strict-decoder break")

    val round = DesktopEnvelope.fromJson(JSONObject(env.toJson().toString()))
    require(round.ciphertext.contentEquals(env.ciphertext)) { "round-trip" }
    println("   round-trip through JSON: ciphertext identical")
}

/** The canonical request string — where a third platform silently earns a 401. */
private fun step4RequestSigning() {
    hr("4. Request signing")

    val me = DesktopIdentity.generate()
    val signer = DesktopV2Signer(me)
    val path = "/v2/messages/${DesktopV2Signer.encodeIdentity(me.userKey)}"

    println("   address (b64 X25519) : ${me.userKey}")
    println("   encoded into path    : ${path.take(72)}...")
    println("   note + / = became %2B %2F %3D — a normal URL encoder would not do this")
    println()
    val canonical = signer.canonicalString("GET", path, ByteArray(0), "1756060000000")
    println("   canonical string (GET hashes ZERO bytes, query excluded):")
    canonical.split("\n").forEach { println("     | $it") }
    println()
    signer.sign("GET", path, ByteArray(0), withUserHeader = true, timestampMs = 1756060000000L)
        .forEach { (k, v) -> println("   $k: ${v.take(64)}${if (v.length > 64) "..." else ""}") }
}

/** One read-only GET. Changes nothing on the server. */
private fun step5ServerProbe() {
    hr("5. Live server probe — GET /v2/config (read-only, unauthenticated)")
    try {
        val conn = (URL("https://oshi-messenger.com/v2/config").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }
        val body = if (conn.responseCode in 200..299)
            conn.inputStream.bufferedReader().readText()
        else conn.errorStream?.bufferedReader()?.readText().orEmpty()
        println("   HTTP ${conn.responseCode}")
        println("   $body")
        println()
        println("   This is the V2 rollout gate both shipped clients consult. A desktop")
        println("   client MUST honour it too: publishing a prekey bundle is what makes")
        println("   iOS/Android route V2 traffic at this identity. Publishing before the")
        println("   desktop receive path is proven strands messages in a client that")
        println("   cannot open them.")
    } catch (e: Exception) {
        println("   probe failed (offline?): ${e.javaClass.simpleName}: ${e.message}")
    }
}

// ---- helpers ---------------------------------------------------------------

private fun hr(title: String) {
    println()
    println("=".repeat(78))
    println("  $title")
    println("=".repeat(78))
}

private fun check(label: String, actual: String, expected: String) {
    val ok = actual == expected
    println("   ${if (ok) "OK  " else "FAIL"}  ${label.padEnd(28)} ${actual.take(48)}${if (actual.length > 48) "..." else ""}")
    if (!ok) {
        println("         expected ${expected}")
        println("         actual   ${actual}")
        error("VECTOR MISMATCH: $label — this build is NOT interoperable with iOS/Android")
    }
}

private fun hex(s: String): ByteArray =
    ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

private fun hexStr(d: ByteArray): String = d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
