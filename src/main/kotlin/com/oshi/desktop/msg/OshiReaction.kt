package com.oshi.desktop.msg

/**
 * __OSHI_SMILE_REACTION_2026_11_09__ The OSHI reaction, as it travels in the existing
 * `🔥REACTION🔥` payload. Fixed across iOS, Android and desktop: U+2B55 U+1F60A
 * (UTF-8 `e2 ad 95 f0 9f 98 8a`). Mobile clients send it on a double-tap; this client
 * draws exactly that value as the OSHI mark and nothing else as it.
 */
object OshiReaction {
    /** U+2B55 U+1F60A. Do not normalise, add a variation selector, or reorder. */
    const val OSHI_SMILE: String = "⭕😊"

    fun isOshiSmile(code: String?): Boolean = code == OSHI_SMILE

    /**
     * __OSHI_O_REACTION_2026_11_15__ The plain OSHI "O" (the brand mark, no face): the single
     * code point U+2B55, UTF-8 `e2 ad 95`, NO variation selector. Same wire value as Android
     * (`OshiReaction.OSHI_O`) and iOS. It travels through the existing reaction payload, so a
     * client that predates it shows the emoji "⭕" — still an O. Distinct from [OSHI_SMILE],
     * which keeps its own value and drawing.
     */
    const val OSHI_O: String = "\u2B55"

    /** True only for the exact wire value "⭕" — not "⭕️" (FE0F), not "⭕😊". */
    fun isOshiO(code: String?): Boolean = code == OSHI_O

    /** Either OSHI mark: drawn as the mark, never shown as their emoji. */
    fun isOshiMark(code: String?): Boolean = isOshiO(code) || isOshiSmile(code)
}
