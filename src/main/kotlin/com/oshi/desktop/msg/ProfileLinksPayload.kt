package com.oshi.desktop.msg

import org.json.JSONObject

/**
 * __DESKTOP_PROFILE_LINKS_2026_10_29__ `🔗LINKS_VISIBILITY🔗` — a contact sharing (or
 * withdrawing) the links on their profile.
 *
 * The fourth catalog sentinel this client carried without acting on it. Found by auditing
 * every entry in [ControlPrefix] for a handler outside the catalog file rather than by
 * stumbling on it: five had none, and of those `KEY_ROTATION`, `SCHEDULED_CALL` and
 * `WALLPAPER_UPDATE` are dead on the PHONES too (`KeyRotationManager` has no callers,
 * `ScheduledCallManager` never puts a byte on a wire — its `"SCHEDULED_CALL"` is a local
 * notification category, not this prefix — and the wallpaper payload is documented here as a
 * codec nothing acts on). `MEDIA_MARKER` and `FORWARDED` are handled through other paths.
 *
 * **This one is live on both phones**, which is what makes it a gap rather than a reserved
 * word: iOS builds it in `ProfileLinksManager.createVisibilityMessage` and sends it from a
 * toggle in `ChatView.swift:2614`, and Android applies an inbound one in
 * `ProfileLinksManager.processIncomingVisibility`. On this client it fell to step 7 of
 * `handleV2Inbound` and was dropped, so a contact who switched their links on saw nothing
 * happen here.
 *
 * ## It is `SILENT`, and that is right
 *
 * Both catalogs mark it silent and iOS's `NotificationManager` lists it among the payloads
 * that must never raise an alert. Somebody adding a GitHub URL is not an event; it changes
 * what their profile shows, nothing more. So this never becomes a row and never notifies.
 *
 * ## EVERY FIELD HERE IS A STRING A STRANGER CHOSE
 *
 * That is the whole reason this file is more careful than its three-field payload deserves:
 *
 *  - **Nothing is fetched, resolved or previewed.** A link preview would be this client
 *    making an outbound request to a third party because a message arrived, which is exactly
 *    what PARITY row 1.4 refuses link previews for. The strings are stored and displayed.
 *  - **Nothing is auto-opened**, ever. Opening a URL is a thing the person does deliberately.
 *  - **The length is capped on the way IN** ([MAX_LINK_LENGTH]), because an unbounded string
 *    from a peer decides how much memory this window allocates and how wide a row draws.
 *  - **A blank field is DROPPED rather than stored as an empty string**, which is Android's
 *    `takeIf { it.isNotEmpty() }` on every field. Storing `""` would make `hasAnyLink` true
 *    and draw an empty row.
 *
 * ## What is deliberately NOT read
 *
 * `updatedAt`. iOS declares it as a Swift `Date`, so a default `JSONEncoder` writes it in
 * APPLE-REFERENCE seconds — the 978 307 200 s trap the poll payload carries a comment about.
 * It exists for iOS's own multi-device conflict resolution ("newer wins" between two devices
 * on one identity), which is a problem this client does not have on this path. **Android does
 * not read it either.** Parsing a timestamp in a second epoch, for nothing, is how the next
 * epoch bug gets written, so it is skipped and that is recorded rather than silent.
 */
object ProfileLinksPayload {

    /**
     * A peer's links. All optional, because a build that predates a field must still decode —
     * iOS says so of `waypathID` in as many words.
     */
    data class Links(
        val websiteUrl: String? = null,
        val linkedinUrl: String? = null,
        val twitterUrl: String? = null,
        val instagramUrl: String? = null,
        val githubUrl: String? = null,
        /** A bare account handle, not a URL. iOS's Waypath loop. */
        val waypathId: String? = null,
    ) {
        val hasAny: Boolean
            get() = listOf(websiteUrl, linkedinUrl, twitterUrl, instagramUrl, githubUrl, waypathId)
                .any { !it.isNullOrEmpty() }

        /** Label and value, in iOS's order, for the ones that are filled. */
        val filled: List<Pair<String, String>>
            get() = buildList {
                websiteUrl?.let { add("Website" to it) }
                linkedinUrl?.let { add("LinkedIn" to it) }
                twitterUrl?.let { add("X" to it) }
                instagramUrl?.let { add("Instagram" to it) }
                githubUrl?.let { add("GitHub" to it) }
                waypathId?.let { add("Waypath" to it) }
            }

        fun toJson(): JSONObject = JSONObject().apply {
            websiteUrl?.let { put("websiteURL", it) }
            linkedinUrl?.let { put("linkedinURL", it) }
            twitterUrl?.let { put("twitterURL", it) }
            instagramUrl?.let { put("instagramURL", it) }
            githubUrl?.let { put("githubURL", it) }
            waypathId?.let { put("waypathID", it) }
        }
    }

    /** `visible` plus the links themselves; withdrawing sends `visible:false`. */
    data class Visibility(val visible: Boolean, val links: Links)

    fun isVisibilityMessage(text: String?): Boolean =
        text != null && ControlPrefix.match(text) == ControlPrefix.LINKS_VISIBILITY

    /**
     * Null when this is not the sentinel or the body is not JSON.
     *
     * An ABSENT `links` object is not a failure — it is how "I am hiding my links" arrives,
     * and iOS's own encoder omits them (`links: visible ? myLinks : nil`). It decodes to an
     * empty [Links], which is Android's `else -> ProfileLinks()`.
     */
    fun decode(text: String?): Visibility? {
        if (!isVisibilityMessage(text)) return null
        val o = runCatching { JSONObject(ControlPrefix.strip(text!!)) }.getOrNull() ?: return null
        val l = o.optJSONObject("links")
        return Visibility(
            visible = o.optBoolean("visible", false),
            links = if (l == null) Links() else Links(
                websiteUrl = field(l, "websiteURL"),
                linkedinUrl = field(l, "linkedinURL"),
                twitterUrl = field(l, "twitterURL"),
                instagramUrl = field(l, "instagramURL"),
                githubUrl = field(l, "githubURL"),
                waypathId = field(l, "waypathID"),
            ),
        )
    }

    fun encode(visible: Boolean, links: Links): String = ControlPrefix.LINKS_VISIBILITY + JSONObject().apply {
        put("visible", visible)
        // iOS sends the links only when turning them ON: withdrawing must not hand over the
        // very strings it is withdrawing.
        if (visible) put("links", links.toJson())
    }.toString()

    /**
     * Read one field: trimmed, capped, and null when empty.
     *
     * The cap is applied HERE rather than at the view, so nothing downstream ever holds an
     * unbounded peer-chosen string — including the store, which persists what it is given.
     */
    private fun field(o: JSONObject, key: String): String? =
        o.optString(key, "").trim().takeIf { it.isNotEmpty() }?.take(MAX_LINK_LENGTH)

    /**
     * Generous for a real URL and far below what a hostile sender would try. A browser will
     * not usefully take more, and a row cannot draw it.
     */
    const val MAX_LINK_LENGTH = 512
}
