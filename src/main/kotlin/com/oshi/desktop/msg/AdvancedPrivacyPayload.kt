package com.oshi.desktop.msg

import org.json.JSONObject
import kotlin.math.floor

/**
 * __ADV_CHAT_PRIVACY_2026_11_26__ "Advanced chat privacy" (WhatsApp's name for it), per
 * conversation — one contact, or one group. Desktop port of iOS `OSHI/AdvancedChatPrivacy.swift`
 * and Android `data/model/AdvancedChatPrivacy.kt`.
 *
 * When ANY participant turns it on, for everyone in that conversation:
 *
 *  1. other people's media from this chat cannot be saved or opened outside OSHI (the media
 *     viewer's "Save a copy" / "Open in another app" / "Show in folder");
 *  2. the conversation is left out of the encrypted export and cannot be exported as text;
 *  3. on-device AI does not read it (the desktop AI has no conversation context today; the
 *     policy is here so a future one has a gate to call);
 *  4. on Windows the window is excluded from screen capture while the chat is on screen.
 *
 * Forwarding stays allowed, as on WhatsApp and on both phones.
 *
 * ## Wire (shared with iOS and Android — do not change)
 *
 * `🛡ADV_PRIVACY🛡{"v":1,"on":true,"at":<unix seconds>}`
 *
 *  - sent WITHOUT U+FE0F; the `🛡️ADV_PRIVACY🛡️` spelling is accepted on receive ([ControlPrefix]);
 *  - `at` is written as an INTEGER (a Swift `Int` field refuses a fractional number) and read
 *    as any JSON number, floored;
 *  - an unknown `v`, a missing or non-number `at`, or a non-boolean `on` is DROPPED: guessing
 *    either way would silently flip someone else's decision (iOS `decode`, `CFBooleanGetTypeID`);
 *  - the author is the authenticated sender of the envelope, never a JSON field;
 *  - last-writer-wins by `at`, the peer's `at` clamped to our now + 300 s, equal `at` → ON.
 */
object AdvancedPrivacyPayload {

    const val CURRENT_VERSION = 1

    /** How far ahead of our clock a peer's `at` may be (same bound as offline-only). */
    const val MAX_FUTURE_SKEW_SECONDS = 300L

    data class Change(
        val on: Boolean,
        val atUnixSeconds: Long,
        val version: Int = CURRENT_VERSION,
    ) {
        /** Key order `v, on, at`, as iOS writes it. */
        fun encode(): String =
            ControlPrefix.ADV_PRIVACY + "{\"v\":$version,\"on\":${if (on) "true" else "false"},\"at\":$atUnixSeconds}"
    }

    fun create(on: Boolean, atMillis: Long = System.currentTimeMillis()): Change =
        Change(on = on, atUnixSeconds = Math.floorDiv(atMillis, 1000L))

    /** Does [text] start with the sentinel (either spelling)? Anchored: prose that mentions it is prose. */
    fun isPayload(text: String?): Boolean = text != null && ControlPrefix.match(text) == ControlPrefix.ADV_PRIVACY

    fun decode(text: String?): Change? {
        if (!isPayload(text)) return null
        val o = runCatching { JSONObject(ControlPrefix.strip(text!!)) }.getOrNull() ?: return null
        val v = (o.opt("v") as? Number)?.toDouble() ?: return null
        if (v != floor(v) || v < 1 || v > CURRENT_VERSION) return null
        val on = o.opt("on") as? Boolean ?: return null
        val at = (o.opt("at") as? Number)?.toDouble() ?: return null
        if (at.isNaN() || at.isInfinite()) return null
        return Change(on = on, atUnixSeconds = floor(at).toLong(), version = v.toInt())
    }

    /**
     * The peer's `at`, bounded by our clock. Unbounded, a peer could send `on:false, at:<year
     * 3000>` and no later change — anyone's — would ever be newer.
     */
    fun clampedAt(atUnixSeconds: Long, nowUnixSeconds: Long): Long =
        minOf(atUnixSeconds, nowUnixSeconds + MAX_FUTURE_SKEW_SECONDS)

    /**
     * Last-writer-wins with the tie rule of both phones. [existingAt]/[existingOn] null = no
     * state yet. Returns true when [incomingOn]@[incomingAt] (already clamped) replaces it.
     */
    fun wins(existingOn: Boolean?, existingAt: Long?, incomingOn: Boolean, incomingAt: Long): Boolean {
        if (existingOn == null || existingAt == null) return true
        if (incomingAt < existingAt) return false
        if (incomingAt > existingAt) return true
        // Same second (two people toggling at once, or a redelivery): ON wins — of the two
        // outcomes the protecting one cannot surprise anyone. Same decision ⇒ nothing new.
        return existingOn != incomingOn && incomingOn
    }
}

/**
 * Every decision the feature makes, with no UI or I/O dependency, so each one is a plain JVM
 * test. Same names as Android `AdvancedChatPrivacyPolicy`.
 */
object AdvancedPrivacyPolicy {

    /** May this media be saved / opened outside OSHI? Your own media stays yours. */
    fun mediaSaveAllowed(privacyOn: Boolean, isFromMe: Boolean = false): Boolean = !privacyOn || isFromMe

    /** May this conversation go into a message export (encrypted backup or text)? */
    fun exportAllowed(privacyOn: Boolean): Boolean = !privacyOn

    /** May on-device AI read this conversation? */
    fun aiAllowed(privacyOn: Boolean): Boolean = !privacyOn

    /** The messages an AI context builder may see: none when protected. */
    fun <T> aiReadable(privacyOn: Boolean, readable: List<T>): List<T> = if (aiAllowed(privacyOn)) readable else emptyList()

    /** Must the window be excluded from screen capture while this conversation is on screen? */
    fun forcesCaptureProtection(privacyOn: Boolean): Boolean = privacyOn

    /** iOS `RatchetHealSignal.Caps.advancedPrivacy`; Android `GroupLegacyCompat.CAP_ADVANCED_PRIVACY`. */
    const val CAP_ADVANCED_PRIVACY: Int = 1 shl 4

    /** Does a peer read `🛡ADV_PRIVACY🛡`? Its bundle says so, or it sent us one. Unknown caps ⇒ no. */
    fun peerReads(accountCaps: Int?, sentUsOne: Boolean): Boolean =
        sentUsOne || (accountCaps != null && (accountCaps and CAP_ADVANCED_PRIVACY) != 0)

    /** The members a group payload may go to; the rest are "on an older version". */
    fun readers(members: Collection<String>, reads: (String) -> Boolean): List<String> = members.filter(reads)
}
