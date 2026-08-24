package com.oshi.desktop.mesh

/**
 * The constants and the two comparison rules the mesh is built out of.
 *
 * Every value here was read out of the two shipped implementations, not out of the
 * protocol document — where the document and the code disagreed, the code won and the
 * disagreement is noted.
 */
object MeshProtocol {

    /** Bonjour/DNS-SD service type. Trailing dot required; both platforms hardcode it. */
    const val SERVICE_TYPE = "_oshi-mesh._tcp.local."

    /** `OSHI-` + the first 8 characters of the base64 public key. */
    const val SERVICE_NAME_PREFIX = "OSHI-"

    /**
     * The platform string this client puts in its TXT record and in every frame.
     *
     * NOT "ios", and the reason is a hard gate rather than a preference: iOS *skips*
     * every peer whose platform is "ios", on both paths — Bonjour resolve
     * (OSHI/CrossPlatformMesh.swift:1619) and IDENTITY_EXCHANGE
     * (OSHI/CrossPlatformMesh.swift:696) — because iOS↔iOS mesh is MultipeerConnectivity's
     * job. A desktop client claiming "ios" would be invisible to every iPhone.
     *
     * NOT "android" either, which would work today but is a lie that costs the truth on
     * both sides: iOS routes calls by `platform == "android"` (VoiceCallManager.swift
     * :5587 and five other sites) and would offer a desktop peer an Android call path
     * this client does not implement.
     *
     * "desktop" is therefore the honest value, and it is READ BY NOBODY today, which is
     * exactly what we want from the two shipped clients until they ship an update:
     *   - iOS: connects (not "ios"), messaging works, and the Peers screen badges us
     *     "iOS" because PeersView.swift:350 is a two-way ternary. Cosmetic, and worth
     *     fixing on the iOS side before a desktop client is announced.
     *   - Android: connects, messaging works, and `MeshBoosterManager` counts us in
     *     neither `androidPeers` nor `iosPeers`. Cosmetic.
     * Neither platform gates *message delivery* on the platform string. That was checked
     * field by field, not assumed.
     */
    const val PLATFORM = "desktop"

    /** Message types both platforms route through the content pipeline. */
    const val TYPE_IDENTITY_EXCHANGE = "IDENTITY_EXCHANGE"
    const val TYPE_IDENTITY_ANNOUNCE = "IDENTITY_ANNOUNCE"
    const val TYPE_TEXT = "TEXT_MESSAGE"

    /** Relay ceiling. Android and iOS both use 50 for cross-platform messages. */
    const val MAX_HOPS = 50

    /** IDENTITY_ANNOUNCE gossip radius: 2 hops, re-broadcast every 30 s after a 5 s settle. */
    const val ANNOUNCE_TTL = 2
    const val ANNOUNCE_INITIAL_DELAY_MS = 5_000L
    const val ANNOUNCE_PERIOD_MS = 30_000L

    /** Duplicate-suppression cache: 5000 ids, halved when it overflows. Same on both. */
    const val MAX_SEEN_IDS = 5000

    /**
     * Inbound frame ceiling, in bytes of JSON payload (the 4-byte length prefix excludes
     * itself). The two platforms do NOT agree here — Android caps at 16 MiB
     * (CrossPlatformMesh.kt:577), iOS at 100 MiB (CrossPlatformMesh.swift:643) — so a
     * frame between the two is accepted by an iPhone and drops an Android connection.
     * We take the stricter of the two: a desktop client should not be the first to
     * discover what a 90 MiB frame does to a JVM heap, and anything that large is a
     * desynced stream rather than a message (the send path caps media at 10 MB).
     */
    const val MAX_FRAME_BYTES = 16 * 1024 * 1024

    /**
     * Tolerant key normalisation for LOOKUPS ONLY — base64url → standard, padding
     * dropped, whitespace trimmed. Mirrors Android's `CrossPlatformMesh.normalizeKey`.
     *
     * This must never be used for a TOFU or identity check (PLAN.md §4.9): it implements
     * an equivalence relation deliberately looser than equality, which is right for
     * "which socket do I write to" and catastrophic for "is this really who they say".
     */
    fun normalizeKey(key: String): String =
        key.trim().replace('-', '+').replace('_', '/').replace("=", "")

    /**
     * "Is this frame addressed to me?" — replicated EXACTLY from both platforms,
     * including the part that looks wrong.
     *
     * The rule is: empty recipient (broadcast), OR case-insensitive full match, OR a
     * case-insensitive match on the first min(len, 32) characters when that prefix is at
     * least 16 long. Both platforms lowercase before comparing, which is *wrong* for
     * base64 (it maps distinct keys onto each other) and is what ships; and both accept a
     * 16-character prefix match, which for base64 is 96 bits of key — far below a full
     * identity check but not a collision risk in a room full of phones.
     *
     * We copy it rather than improve it because divergence here does not produce an
     * error, it produces a message that one platform delivers and another relays into
     * the void. The safety property that actually matters is elsewhere: this decides
     * *delivery*, never *trust*. Nothing downstream may treat "for us" as authentication —
     * the sender identity on this envelope is unauthenticated plaintext, and the only
     * real proof of authorship is the ratchet under `payload`.
     */
    fun isForUs(recipientPublicKey: String, myPublicKey: String): Boolean {
        if (recipientPublicKey.isEmpty()) return true
        val r = recipientPublicKey.trim().lowercase()
        val m = myPublicKey.trim().lowercase()
        if (r == m) return true
        val prefixLen = minOf(m.length, r.length, 32)
        return prefixLen >= 16 && r.take(prefixLen) == m.take(prefixLen)
    }
}
