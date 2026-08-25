package com.oshi.desktop.group

import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * Key and group-id canonicalisation for PARITY.md row 0.17 — and a deliberate refusal to
 * reuse the one this client already has.
 *
 * ============================================================ WHY NOT `MeshProtocol.normalizeKey`
 *
 * [com.oshi.desktop.mesh.MeshProtocol.normalizeKey] already folds base64url/padding skew,
 * and it is the WRONG function here. It is a port of Android's
 * `CrossPlatformMesh.normalizeKey` / `CallAdmissionPolicy.normalizeKey`
 * (`OSHI-Android/.../service/CallAdmissionPolicy.kt:73-74`):
 *
 *     key.trim().replace('-','+').replace('_','/').replace("=","")
 *
 * — three substitutions and a padding strip, applied unconditionally. It is used for
 * ROUTING: "which of my open connections belongs to this peer". A wrong answer there costs
 * one undelivered frame.
 *
 * Row 0.17 uses the same comparison to decide **who is an admin**. A wrong answer there is
 * a privilege escalation, so this file follows iOS's `MessageManager.canonicalIdentity`
 * (`OSHI/MessageManager.swift:6246-6261`) instead, which does three things Android's does
 * not:
 *
 *  1. **It re-pads.** iOS ends with `while s.count % 4 != 0 { s += "=" }`, so its canonical
 *     form is PADDED standard base64. Android's ends with the padding stripped. Both are
 *     internally consistent — each platform compares canonical to canonical — so this is
 *     not a live interop bug, but a desktop client has to pick one spelling and stay on it,
 *     and mixing the two inside one process would make `isAdmin` depend on which helper the
 *     call site reached for.
 *  2. **It validates.** `guard Data(base64Encoded: s) != nil else { return key }` — a string
 *     that is not base64 after folding is returned UNCHANGED rather than mangled. That is
 *     the strict half: two junk strings that Android's version would fold onto each other
 *     stay distinct here, so a malformed key can never be canonicalised into an admin's.
 *  3. **It has an allocation-free fast path** for a key that is already canonical
 *     (`swift:6251`). Kept because it is also a behaviour: a key whose length is already a
 *     multiple of 4 and contains no `-`/`_` is returned verbatim, base64-valid or not.
 *
 * Per the ledger's working rule 2, the disagreement is recorded and the stricter side wins:
 * **iOS**.
 *
 * ============================================================ GROUP IDS ARE NOT KEYS
 *
 * [canonicalGroupId] is the other half, and it has the opposite shape. iOS `MessageGroup.id`
 * is a `UUID`, which Swift re-encodes as an UPPERCASE `uuidString`, so an Android-created
 * group (lowercase) and the same group as an iPhone spells it are the same group under
 * different bytes. Android normalises with `UUID.fromString(...).uppercase()` and — this is
 * the part that matters — only when the string really parses as a UUID
 * (`GroupManager.kt:130-132`), because a public-group invite `gid` is not a UUID and
 * uppercasing it corrupts it. Same rule here, same reason.
 */
object GroupIdentity {

    /**
     * iOS `MessageManager.canonicalIdentity` (`OSHI/MessageManager.swift:6246-6261`),
     * transliterated including its fast path and its failure mode.
     *
     * Returns [key] unchanged when the folded form is not decodable base64 — never a
     * mangled string. See the class doc for why that is the point rather than an accident.
     */
    fun canonicalIdentity(key: String): String {
        // swift:6251 — the allocation-free fast path, and a behaviour: already canonical
        // in shape means "leave it alone", without a base64 validity check.
        if (key.length % 4 == 0 && !key.contains('-') && !key.contains('_')) return key
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return key
        var s = trimmed.replace('-', '+').replace('_', '/')
        s = s.trimEnd('=')
        while (s.length % 4 != 0) s += "="
        // swift:6259 — decodable, or give the caller back what they handed us.
        val decodable = runCatching { Base64.getDecoder().decode(s) }.isSuccess
        return if (decodable) s else key
    }

    /** True when [a] and [b] name the same identity under [canonicalIdentity]. */
    fun sameIdentity(a: String, b: String): Boolean =
        canonicalIdentity(a) == canonicalIdentity(b)

    /**
     * iOS spells a group id as an UPPERCASE `UUID.uuidString`; a non-UUID invite `gid`
     * must survive verbatim. Android `GroupManager.canonicalGroupId`
     * (`GroupManager.kt:130-132`), asserted at `GroupWireFormatTest.kt:92,98`.
     */
    fun canonicalGroupId(groupId: String): String =
        runCatching { UUID.fromString(groupId).toString().uppercase(Locale.US) }
            .getOrDefault(groupId)
}
