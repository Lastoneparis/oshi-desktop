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
}
