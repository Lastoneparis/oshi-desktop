package com.oshi.desktop.mascot

import com.oshi.desktop.gif.LocalGifLibrary

/**
 * __OSHI_MASCOT_STICKERS_2026_11_21__ The "OSHI" tab of the GIF & sticker picker: the 20
 * O-bubble mascot animations ([OshiMascot.all]) as stickers anyone can send.
 *
 * ============================================================ THE WIRE DOES NOT CHANGE
 *
 * A sticker is the bundled GIF (`mascot/gif/<name>.gif`, 240 px, GIF89a, transparent,
 * infinite loop) handed UNTOUCHED to the picker's `onPick` — the path every pack GIF takes
 * (`GifOutbox.write` → `image/gif`, labelled `gif` by [com.oshi.desktop.gif.GifWire]). No
 * sticker id, no new message type:
 *  - iOS 1.0.43 has no `gif` media type, falls back to the MIME (`image/` → `.image`) and
 *    draws `UIImage(data:)` — the FIRST frame, still. iOS 1.0.46+ animates
 *    (`MediaManager.isGIFData`), Android through Coil's GIF decoder, this client through
 *    `GifBubble`.
 *  - groups: `photo` + a `.gif` name, as for every GIF.
 *
 * Because iOS 1.0.43 shows only frame 0, the shipped GIFs START on the manifest's
 * `still_frame` (✅ on "ok", 💯 on "hundred"…): the same loop, rotated, so frame 0 is also
 * the `static/<name>_128.png` still [com.oshi.desktop.ui.components.MascotImage] shows
 * before it starts playing. `OshiStickerPackTest` pins it.
 */
object OshiStickerPack {

    enum class Tab { OSHI, GIFS, STICKERS }

    /** Tab order of the picker: OSHI first. */
    val TABS: List<Tab> = listOf(Tab.OSHI, Tab.GIFS, Tab.STICKERS)

    /** The tab the picker opens on. */
    val DEFAULT_TAB: Tab = Tab.OSHI

    /** Only the GIFs and Stickers tabs talk to GIPHY (its key is capped per hour, app-wide). */
    fun usesGiphy(tab: Tab): Boolean = tab != Tab.OSHI

    /** The offline pack a tab browses, or null for the OSHI tab. */
    fun packKind(tab: Tab): LocalGifLibrary.Kind? = when (tab) {
        Tab.OSHI -> null
        Tab.GIFS -> LocalGifLibrary.Kind.GIF
        Tab.STICKERS -> LocalGifLibrary.Kind.STICKER
    }

    /** The stickers for [query]: all of them in manifest order when blank. */
    fun stickers(query: String = "", all: List<OshiMascot.Mascot> = OshiMascot.all): List<OshiMascot.Mascot> {
        val q = LocalGifLibrary.fold(query.trim())
        if (q.isEmpty()) return all
        val asEmoji = OshiMascot.forEmoji(query.trim())
        return all.filter { m -> m == asEmoji || LocalGifLibrary.fold("${m.name} ${m.label} oshi").contains(q) }
    }

    /** What a tap sends: bytes + file name. */
    class Sticker(val mascot: OshiMascot.Mascot, val bytes: ByteArray, val fileName: String)

    /**
     * Loads [mascot]'s GIF through [read] (the classpath by default), or null when it is
     * missing or not a GIF89a — a PNG still would arrive frozen; never send one in its place.
     */
    fun sticker(
        mascot: OshiMascot.Mascot,
        read: (String) -> ByteArray? = { OshiMascot.classpathBytes(it) },
    ): Sticker? {
        val bytes = runCatching { read(mascot.gifPath) }.getOrNull() ?: return null
        if (!isGif89a(bytes)) return null
        return Sticker(mascot, bytes, mascot.gifFileName)
    }

    fun isGif89a(bytes: ByteArray): Boolean =
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a"
}
