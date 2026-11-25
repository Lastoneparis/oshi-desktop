package com.oshi.desktop.mascot

import org.json.JSONObject

/**
 * __O_BUBBLE_MASCOT_2026_11_16__ The O-bubble mascot: the OSHI ring + speech tail with a
 * face in its counter, in 20 looping animations, one per reaction emoji.
 *
 * ============================================================ THE WIRE DOES NOT CHANGE
 *
 * A mascot is a way of DRAWING an emoji, never a new value. Reacting with a mascot sends a
 * standard emoji in the existing `🔥REACTION🔥` payload ([wireEmoji]), so a client that has
 * never heard of the mascot shows the emoji it always showed. Usually that is the manifest's
 * canonical emoji; the compatibility table `ReactionPayload.MASCOT_WIRE_OVERRIDES` can swap
 * it for one iPhones already know (__REACTION_COMPAT_2026_11_16__: cry sends `😢`, not `😭`). Inbound, every
 * spelling the manifest lists as an alias (`❤`, `♥️`, `💜`…, `🤣`, `😢`…) is DRAWN as the
 * same mascot — and is still stored and re-sent exactly as it arrived.
 *
 * ============================================================ WHERE THE FILES ARE
 *
 * `src/main/resources/mascot/` is a copy of the generated pack (`~/oshi_mascot/pack`,
 * generator `../src/gen.js`): `manifest.json` (the source of truth, read here as shipped),
 * `gif/<name>.gif` (240 px, the file that is SENT and the one animated on screen),
 * `static/<name>_{64,128}.png` (the still frame, 8-bit alpha) and `launch/` for the splash.
 * The animated WebP and PNG-sequence copies were left out: the GIF already plays through
 * skia's `Codec` ([com.oshi.desktop.gif.AnimatedGif]) and a second animated copy of every
 * mascot would only double the download.
 */
object OshiMascot {

    data class Mascot(
        /** File stem: `gif/<name>.gif`, `static/<name>_64.png`. */
        val name: String,
        /** The canonical emoji, exactly as the manifest spells it — the WIRE value. */
        val emoji: String,
        /** Other emoji DRAWN as this mascot (never sent in its place). */
        val aliases: List<String>,
        val label: String,
        val fps: Int,
        val frames: Int,
        val durationMs: Int,
        val stillFrame: Int,
    ) {
        val gifPath: String get() = "$ROOT/gif/$name.gif"
        fun staticPath(px: Int): String = "$ROOT/static/${name}_${if (px <= 64) 64 else 128}.png"
        /** What the GIF picker hands to the GIF send path. */
        val gifFileName: String get() = "oshi-$name.gif"
    }

    const val ROOT = "mascot"
    const val MANIFEST = "$ROOT/manifest.json"

    /**
     * The quick-reaction row: ❤️ 😂 😢(cry) 👍 😮 🔥.
     *
     * __REACTION_COMPAT_2026_11_16__ Every one of the six SENDS an emoji iOS 1.0.43 knows
     * (cry via the 😢 override), so a one-click reaction never turns into 👍 on an iPhone.
     * The first draft (❤️😂😭🫣🥲👍) put three iPhone-unknown emoji one click away; 🫣 and
     * 🥲 now live in the full picker under the "new" hint. `ReactionCompatPolicyTest`
     * fails if a row member's wire value leaves `ReactionPayload.IOS_EMOJI`.
     */
    val REACTION_BAR_NAMES: List<String> = listOf("love", "laugh", "cry", "thumbs", "wow", "fire")

    /**
     * __OSHI_O_REACTION_2026_11_15__ What the one-click row SENDS, in order: the OSHI "O"
     * (`OshiReaction.OSHI_O`) first, then the six mascots' wire emoji — Android's
     * `OshiReaction.QUICK_ROW`, iOS's quick bar.
     */
    val quickRowWire: List<String> by lazy {
        listOf(com.oshi.desktop.msg.OshiReaction.OSHI_O) + REACTION_BAR_NAMES.map {
            named(it)?.let(::wireEmoji) ?: error("mascot $it missing from the pack")
        }
    }

    /** All mascots in manifest order; empty if the pack is missing from this build. */
    val all: List<Mascot> by lazy { parse(classpathBytes(MANIFEST)) }

    private val byName: Map<String, Mascot> by lazy { all.associateBy { it.name } }

    private val byKey: Map<String, Mascot> by lazy { index(all) }

    fun named(name: String): Mascot? = byName[name]

    /** The six of the quick-reaction row, in [REACTION_BAR_NAMES] order. */
    val reactionBar: List<Mascot> by lazy { REACTION_BAR_NAMES.mapNotNull { byName[it] } }

    /**
     * The full picker: the quick six first, then the other mascots every iPhone shows, then
     * the "new" ones ([isNewForOlderIphones]) — each group in manifest order.
     */
    val pickerOrder: List<Mascot> by lazy { pickerKnown + pickerNew }

    /** The picker's first section: the quick six, then the rest that iPhones already know. */
    val pickerKnown: List<Mascot> by lazy {
        reactionBar + all.filter { it.name !in REACTION_BAR_NAMES && !isNewForOlderIphones(it) }
    }

    /** The picker's "new — older iPhones may show 👍" section. */
    val pickerNew: List<Mascot> by lazy {
        all.filter { it.name !in REACTION_BAR_NAMES && isNewForOlderIphones(it) }
    }

    /** True when an iPhone on 1.0.43 would draw 👍 for what [mascot] sends. */
    fun isNewForOlderIphones(mascot: Mascot): Boolean =
        com.oshi.desktop.msg.ReactionPayload.isNewForOlderIphones(wireEmoji(mascot))

    /**
     * The mascot that DRAWS [emoji], or null when it keeps rendering as text. Variation
     * selectors and skin-tone modifiers are ignored ([normalise]), as the pack's README asks.
     */
    fun forEmoji(emoji: String?): Mascot? = emoji?.let { byKey[normalise(it)] }

    /**
     * What goes on the wire when the user picks [mascot]: its canonical emoji, unless the
     * compatibility table remaps it (`ReactionPayload.MASCOT_WIRE_OVERRIDES`).
     */
    fun wireEmoji(mascot: Mascot): String = com.oshi.desktop.msg.ReactionPayload.mascotWireEmoji(mascot.emoji)

    /**
     * Strip U+FE0F / U+FE0E (variation selectors) and U+1F3FB..U+1F3FF (skin tones) — the
     * lookup key only. `👍🏽` and `❤` both find their mascot; neither is rewritten.
     */
    fun normalise(emoji: String): String {
        val sb = StringBuilder(emoji.length)
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            val skip = cp == 0xFE0F || cp == 0xFE0E || cp in 0x1F3FB..0x1F3FF
            if (!skip) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    internal fun index(list: List<Mascot>): Map<String, Mascot> {
        val out = LinkedHashMap<String, Mascot>()
        // Canonical emoji first so an alias can never steal a canonical key.
        for (m in list) out.putIfAbsent(normalise(m.emoji), m)
        for (m in list) for (a in m.aliases) out.putIfAbsent(normalise(a), m)
        return out
    }

    /** Tolerant per entry, like [com.oshi.desktop.gif.LocalGifLibrary]: a bad row is skipped. */
    internal fun parse(raw: ByteArray?): List<Mascot> {
        if (raw == null) return emptyList()
        val arr = runCatching { JSONObject(String(raw, Charsets.UTF_8)).getJSONArray("animations") }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<Mascot>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { continue }
            val emoji = o.optString("emoji").ifBlank { continue }
            val aliases = o.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                .orEmpty().filter { it.isNotBlank() }
            val fps = o.optInt("fps", 20).coerceAtLeast(1)
            val frames = o.optInt("frames", 1).coerceAtLeast(1)
            out += Mascot(
                name = name,
                emoji = emoji,
                aliases = aliases,
                label = o.optString("label").ifBlank { name },
                fps = fps,
                frames = frames,
                durationMs = o.optInt("duration_ms", frames * 1000 / fps),
                stillFrame = o.optInt("still_frame", 0),
            )
        }
        return out
    }

    fun classpathBytes(path: String): ByteArray? =
        OshiMascot::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
}
