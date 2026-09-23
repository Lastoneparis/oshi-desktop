package com.oshi.desktop.msg

/**
 * THE list of emoji-sentinel control prefixes — desktop port of `OSHI/OSHIControlPayload.swift`
 * and `OSHI-Android/.../data/model/OSHIControlPayload.kt`.
 *
 * ============================================================ WHY THE WHOLE CATALOG
 *
 * PARITY.md row 0.18 needs eight of these prefixes, not twenty-four. The other sixteen are
 * here anyway, and that is the entire point of the file existing.
 *
 * OSHI rides its control messages on the SAME `content` string as user prose, distinguished
 * by an emoji prefix followed by a JSON or `key|value` body. Every surface that displays a
 * message therefore has to know the list — and both shipped clients arrived at a single
 * catalog only after paying for partial ones. iOS had SIX copies of the prefix array
 * (9, 9, 13, 15, 3 and 22 entries; `OSHIControlPayload.swift:19-25`), Android had FOUR
 * (12, 6, ~18, ~22; `OSHIControlPayload.kt:16-21`). Six lists of six different lengths
 * means "add the missing prefix" fixes exactly one surface and the raw JSON keeps appearing
 * in the other five. The 2026-08-10 iOS fix landed in the SMART-REPLY prompt filter, which
 * feeds an LLM and renders nothing — which is why the user still saw braces afterwards.
 *
 * Writing only the eight this row needs would make this desktop client the seventh partial
 * copy on the first day. So: the catalog is complete, and only row 0.18's payloads have
 * codecs. [Kind] is carried for all of them because it is the answer to "must this ever
 * reach a human", which is a question a CLI or a future UI asks about payloads this row
 * does not own.
 *
 * ============================================================ THE U+FE0F PROBLEM
 *
 * Four prefixes contain U+FE0F (VARIATION SELECTOR-16) as they are written here: `⌨️`,
 * `✍️`, `🛡️` and `➡️`. Older iOS builds and some Android builds emit the BARE codepoint.
 * Swift's `String.hasPrefix` compares grapheme clusters under canonical equivalence, and
 * `"🛡"` is NOT canonically equivalent to `"🛡️"` — so a single spelling silently fails to
 * match half the traffic. Kotlin's `String.startsWith` is a plain UTF-16 comparison and
 * fails the same way, for a simpler reason.
 *
 * Both shipped clients solve it by DERIVING the variants rather than listing them twice
 * (`OSHIControlPayload.swift:167-181`, `.kt` likewise): each catalog prefix contributes
 * itself plus its U+FE0F-stripped twin, both resolving back to the same canonical entry.
 * Duplicating the table instead would be the exact mistake the file exists to stop. This
 * port derives them the same way.
 *
 * **Longest variant first.** `📱CALL_SUMMARY📱` and `📱MISSED_CALL📱` share a leading
 * emoji, and `🛡️CHECK_IN🛡️` / `🛡️CHECKIN🛡️` differ by one character — a shorter entry
 * must never win over a longer one, and neither must a stripped variant over a full one.
 *
 * ============================================================ DUPLICATE SPELLINGS
 *
 * `CHECK_IN`/`CHECKIN` and the two reaction markers and the two typing markers are all
 * duplicated on purpose. iOS and Android disagree about them and **both forms are live on
 * the wire today**; dropping either one un-fixes a shipped device. iOS's own catalog says
 * this in as many words. See [ReactionPayload] and [TypingPayload] for which spelling this
 * client EMITS in each case — accepting both and emitting one is the rule everywhere.
 *
 * ============================================================ ANCHORING, NOT ROUTING
 *
 * [match] is strictly anchored at the start. A message that merely CONTAINS a sentinel
 * ("we should add a 📍LOCATION📍 marker") is ordinary prose and must be left alone. And
 * per PLAN.md §4.5 the prefix decides which PARSER to run, never what the payload means:
 * the body is parsed and then branched on, never branched on by prefix alone.
 */
object ControlPrefix {

    /** How a payload presents itself — `OSHIControlPayload.Kind` (`Swift:57-66`). */
    enum class Kind {
        /** Pure protocol. Not a message. Never render it, never count it. */
        SILENT,

        /** A real user-facing event that happens to travel as a sentinel payload. */
        RENDERED,

        /** A user message wrapped in an envelope (reply / forward). Unwrap, never summarise. */
        ENVELOPE,
    }

    // ---------------------------------------------------------------- row 0.18's eight

    /** `📬DELIVERY_RECEIPT📬` — body is the BARE message id. See [DeliveryReceipt]. */
    const val DELIVERY_RECEIPT = "📬DELIVERY_RECEIPT📬"

    /** `📖READ_RECEIPT📖` — body is empty on iOS, JSON on Android. See [ReadReceipt]. */
    const val READ_RECEIPT = "📖READ_RECEIPT📖"

    /** `⌨️TYPING⌨️` — the ONLY typing spelling iOS parses. See [TypingPayload]. */
    const val TYPING_IOS = "⌨️TYPING⌨️"

    /** `✍️TYPING✍️` — legacy Android-only; an iPhone silently drops it. */
    const val TYPING_ANDROID = "✍️TYPING✍️"

    /** `🔥REACTION🔥` — what BOTH platforms emit today. See [ReactionPayload]. */
    const val REACTION = "🔥REACTION🔥"

    /**
     * `👍 REACTION👍` — legacy Android. Note the SPACE after the first emoji and the
     * absence of one before the second: that asymmetry is in both shipped catalogs
     * (`OSHIControlPayload.swift:101`, `.kt:80`) and is not a typo to be tidied.
     */
    const val REACTION_LEGACY = "👍 REACTION👍"

    /** `🔧ACTION🔧` — the unified edit / delete / pin payload. See [MessageActionPayload]. */
    const val ACTION = "🔧ACTION🔧"

    // ------------------------------------------------------- the rest of the catalog

    const val CALL_SIGNAL = "📞CALL_SIGNAL📞"
    const val LINKS_VISIBILITY = "🔗LINKS_VISIBILITY🔗"
    const val PROFILE_REQUEST = "📸PROFILE_REQUEST📸"
    const val GROUP_UPDATE = "📢GROUP_UPDATE📢"
    const val SESSION_RESET = "__SESSION_RESET__"

    const val CALL_SUMMARY = "📱CALL_SUMMARY📱"
    const val MISSED_CALL = "📱MISSED_CALL📱"
    const val LOCATION = "📍LOCATION📍"
    const val SCHEDULED_CALL = "📅SCHEDULED_CALL📅"
    const val CHECK_IN = "🛡️CHECK_IN🛡️"
    const val CHECKIN = "🛡️CHECKIN🛡️"
    const val PROFILE_UPDATE = "📸PROFILE_UPDATE📸"
    const val WALLPAPER_UPDATE = "🎨WALLPAPER_UPDATE🎨"
    const val KEY_ROTATION = "🔑KEY_ROTATION🔑"
    /** A rate-limited delivery refusal, rendered in the receiving device's language. */
    const val BLOCKED_NOTICE = "🚫BLOCKED🚫"
    const val MEDIA_MARKER = "MEDIA|||"

    const val REPLY = "💬REPLY💬"
    const val FORWARDED = "➡️FORWARDED➡️"

    data class Entry(val prefix: String, val kind: Kind)

    /**
     * Every sentinel on the wire, in the order both shipped catalogs list them. A new
     * control message is declared HERE and nowhere else.
     */
    val catalog: List<Entry> = listOf(
        Entry(CALL_SIGNAL, Kind.SILENT),
        Entry(DELIVERY_RECEIPT, Kind.SILENT),
        Entry(READ_RECEIPT, Kind.SILENT),
        Entry(TYPING_IOS, Kind.SILENT),
        Entry(TYPING_ANDROID, Kind.SILENT),
        Entry(LINKS_VISIBILITY, Kind.SILENT),
        Entry(PROFILE_REQUEST, Kind.SILENT),
        Entry(ACTION, Kind.SILENT),
        Entry(GROUP_UPDATE, Kind.SILENT),
        Entry(SESSION_RESET, Kind.SILENT),

        Entry(CALL_SUMMARY, Kind.RENDERED),
        Entry(MISSED_CALL, Kind.RENDERED),
        Entry(LOCATION, Kind.RENDERED),
        Entry(SCHEDULED_CALL, Kind.RENDERED),
        Entry(CHECK_IN, Kind.RENDERED),
        Entry(CHECKIN, Kind.RENDERED),
        Entry(PROFILE_UPDATE, Kind.RENDERED),
        Entry(WALLPAPER_UPDATE, Kind.RENDERED),
        Entry(REACTION, Kind.RENDERED),
        Entry(REACTION_LEGACY, Kind.RENDERED),
        Entry(KEY_ROTATION, Kind.RENDERED),
        Entry(BLOCKED_NOTICE, Kind.RENDERED),
        Entry(MEDIA_MARKER, Kind.RENDERED),

        Entry(REPLY, Kind.ENVELOPE),
        Entry(FORWARDED, Kind.ENVELOPE),
    )

    /**
     * The prefixes a peer must process but must never be alerted about — iOS's
     * `v2SilentControlPrefixes` (`MessageManager+V2.swift:1047-1050`), ported verbatim by
     * Android as `V2_SILENT_CONTROL_PREFIXES` (`MessageRepository.kt:158-168`).
     *
     * This is NOT the same list as `catalog.filter { it.kind == SILENT }`, and the
     * difference is deliberate on both platforms: `📞CALL_SIGNAL📞` is Kind.SILENT (never
     * rendered) but is absent here, because a call signal may need to RING. Deriving one
     * list from the other would silence incoming calls.
     */
    val silentNoPushPrefixes: List<String> = listOf(
        DELIVERY_RECEIPT, READ_RECEIPT, PROFILE_UPDATE, PROFILE_REQUEST,
        LINKS_VISIBILITY, WALLPAPER_UPDATE, TYPING_IOS, TYPING_ANDROID,
    )

    private data class Variant(val spelling: String, val canonical: String, val kind: Kind)

    /**
     * Derived, never duplicated: each catalog prefix contributes itself plus its
     * U+FE0F-stripped twin. Sorted longest-spelling-first so a shorter entry can never
     * shadow a longer one. See the class doc.
     */
    /**
     * VARIATION SELECTOR-16, spelled as an escape on purpose: written literally it is a
     * zero-width character that no reviewer can see in a diff, and this whole mechanism
     * exists because that character is invisible.
     */
    const val VS16 = "\uFE0F"

    private val variants: List<Variant> = buildList {
        for (e in catalog) {
            add(Variant(e.prefix, e.prefix, e.kind))
            val stripped = e.prefix.replace(VS16, "")
            if (stripped != e.prefix) add(Variant(stripped, e.prefix, e.kind))
        }
    }.sortedByDescending { it.spelling.length }

    /**
     * The catalog prefix [text] starts with, in its CANONICAL spelling, or null.
     *
     * Canonical means: a `⌨TYPING⌨` payload from an old build reports [TYPING_IOS], the
     * spelling with the variation selectors, so callers compare against one constant
     * instead of two. Anchored at the start — a sentinel in the middle of prose is prose.
     */
    fun match(text: String): String? = variants.firstOrNull { text.startsWith(it.spelling) }?.canonical

    /**
     * [text] with its leading sentinel removed, using the spelling ACTUALLY present.
     *
     * This is why it is not `removePrefix(canonical)`: stripping the canonical spelling off
     * a payload that arrived with the bare codepoint leaves a stray U+FE0F glued to the
     * front of the JSON, and `JSONObject("️{...}")` throws. Android hit exactly this
     * and left a comment saying "stripPrefix (not removePrefix)".
     */
    fun strip(text: String): String {
        val v = variants.firstOrNull { text.startsWith(it.spelling) } ?: return text
        return text.substring(v.spelling.length)
    }

    fun kindOf(text: String): Kind? = variants.firstOrNull { text.startsWith(it.spelling) }?.kind

    /** True when [text] is any sentinel payload, including reply/forward envelopes. */
    fun isControl(text: String): Boolean = kindOf(text) != null

    /** True when [text] must never reach the user in any form: drop the message. */
    fun isSilent(text: String): Boolean = kindOf(text) == Kind.SILENT

    /** True when sending [text] must not fire a push at the peer (see [silentNoPushPrefixes]). */
    fun suppressesPush(text: String): Boolean = silentNoPushPrefixes.any { match(text) == it }
}
