package com.oshi.desktop.group

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * __GROUP_PARITY_2026_09_23__ Group invite links, byte-compatible with iOS.
 *
 * EMITTED (iOS `MessageGroup.inviteLink`, `OSHI/GroupMessaging.swift:168-180`, and the QR in
 * `GroupInfoSheet.generateQRCode`, `GroupViews.swift:1421`):
 *     https://oshi-messenger.com/group?gid=<UUID>&name=<urlencoded>&inviter=<pubkey>
 *
 * ACCEPTED (iOS `SharedContentHandler.swift:112-160`, in its order):
 *     oshi://group/join?gid=…&name=…&inviter=…
 *     https://oshi-messenger.com/group?gid=…&name=…&inviter=…
 *     oshi://group/<UUID>                     (legacy, no inviter)
 *     https://oshi-messenger.com/group/<UUID> (legacy, no inviter)
 * The gid must be a UUID, as iOS validates it; an invite without one is refused.
 *
 * ENCODING. iOS builds the query with `URLComponents`, which leaves `+`, `/` and `=` of a
 * base64 key UNescaped. A form decoder would turn that `+` into a space and corrupt the key,
 * so the query is split by hand and only `%XX` is decoded — never `+`. On emit, every
 * character outside RFC 3986 "unreserved" plus `+/=` is percent-encoded, which iOS's
 * `URLComponents.queryItems` decodes back exactly.
 */
object GroupInvite {

    const val HOST = "oshi-messenger.com"

    data class Invite(val groupId: String, val name: String?, val inviter: String?)

    fun link(groupId: String, name: String, inviter: String): String =
        "https://$HOST/group?gid=${GroupIdentity.canonicalGroupId(groupId)}" +
            "&name=${enc(name)}&inviter=${enc(inviter)}"

    /** Null when [raw] is not a group invite (so a contact code can be tried instead). */
    fun parse(raw: String?): Invite? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val token = Regex("""(oshi://group\S*|https?://(www\.)?oshi-messenger\.com/group\S*)""", RegexOption.IGNORE_CASE)
            .find(text)?.value ?: return null
        val uri = runCatching { URI(token) }.getOrNull() ?: return null
        val query = parseQuery(uri.rawQuery)
        var gid = query["gid"].orEmpty()
        if (gid.isEmpty()) {
            val segs = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
            gid = if (uri.scheme.equals("oshi", true)) {
                // oshi://group/<UUID> — host is "group", the UUID is the first path segment.
                segs.firstOrNull()?.takeIf { it != "join" }.orEmpty()
            } else {
                // https://oshi-messenger.com/group/<UUID>
                segs.getOrNull(1)?.takeIf { it != "join" }.orEmpty()
            }
        }
        if (runCatching { UUID.fromString(gid) }.isFailure || gid.length != 36) return null
        return Invite(
            groupId = GroupIdentity.canonicalGroupId(gid),
            name = query["name"]?.takeIf { it.isNotBlank() },
            inviter = query["inviter"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            out.putIfAbsent(pair.substring(0, i), pctDecode(pair.substring(i + 1)))
        }
        return out
    }

    /** `%XX` only — a literal `+` is a base64 character here, never a space. */
    private fun pctDecode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8) }.getOrDefault(s)

    private fun enc(s: String): String =
        URLEncoder.encode(s, StandardCharsets.UTF_8)
            .replace("+", "%20")        // a space
            .replace("%2B", "+")         // base64 stays as iOS writes it
            .replace("%2F", "/")
            .replace("%3D", "=")
            .replace("%7E", "~")
}
