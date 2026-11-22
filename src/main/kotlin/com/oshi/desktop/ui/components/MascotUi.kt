package com.oshi.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.gif.AnimatedGif
import com.oshi.desktop.i18n.t
import com.oshi.desktop.mascot.OshiMascot
import com.oshi.desktop.ui.MotionPreference
import com.oshi.desktop.ui.OshiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/**
 * __O_BUBBLE_MASCOT_2026_11_16__ Drawing the O-bubble mascots ([OshiMascot]).
 *
 * Still by default — the manifest's most readable frame, `static/<name>_{64,128}.png` with
 * 8-bit alpha — and ANIMATED only while [MotionPreference.mascotsAnimate] holds and either
 * the pointer is on it or it has just appeared (one loop). The animation is the pack's GIF,
 * decoded by skia through [AnimatedGif] (downscaled to the drawn size, cached, bounded), so
 * no new decoder and no new dependency.
 */
object MascotImages {
    private val stills = HashMap<String, ImageBitmap?>()

    /** The still frame for [px] drawn pixels, or null when the file is not in this build. */
    fun still(mascot: OshiMascot.Mascot, px: Int): ImageBitmap? {
        val path = mascot.staticPath(px)
        synchronized(stills) {
            if (stills.containsKey(path)) return stills[path]
        }
        val bmp = OshiMascot.classpathBytes(path)?.let { bytes ->
            runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
        }
        synchronized(stills) { stills[path] = bmp }
        return bmp
    }

    fun gifBytes(mascot: OshiMascot.Mascot): ByteArray? = OshiMascot.classpathBytes(mascot.gifPath)

    fun cacheKey(mascot: OshiMascot.Mascot): String = "mascot:${mascot.name}"
}

/**
 * One mascot at [size]. [playing] switches between the still frame and the looping GIF; the
 * still is shown while the GIF decodes, so starting to play never blanks the cell.
 */
@Composable
fun MascotImage(
    mascot: OshiMascot.Mascot,
    size: Dp,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val still = remember(mascot.name, px) { MascotImages.still(mascot, px) }
    val key = MascotImages.cacheKey(mascot)
    val frames: AnimatedGif.Frames? = if (!playing) null else {
        val state by produceState(AnimatedGif.cached(key, px), key, px) {
            if (value != null) return@produceState
            value = withContext(Dispatchers.IO) { MascotImages.gifBytes(mascot)?.let { AnimatedGif.decode(key, it, px) } }
        }
        state
    }
    val bitmap: ImageBitmap? = if (frames != null && frames.isAnimated) {
        var index by remember(frames) { mutableIntStateOf(0) }
        LaunchedEffect(frames) {
            while (true) {
                delay(frames.delaysMs[index].toLong())
                index = (index + 1) % frames.frames.size
            }
        }
        frames.frames[index.coerceIn(0, frames.frames.lastIndex)]
    } else still
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = mascot.label, modifier = modifier.size(size))
    } else {
        // The pack is missing from this build: say the emoji instead of drawing nothing.
        Text(mascot.emoji, fontSize = (size.value / 1.35f).sp, modifier = modifier)
    }
}

/**
 * True while a mascot should move: animations allowed AND (hovered OR inside the one loop
 * that follows its first appearance, when [playOnAppear]).
 */
@Composable
fun rememberMascotPlaying(mascot: OshiMascot.Mascot, hovered: Boolean, playOnAppear: Boolean): Boolean {
    val allowed = MotionPreference.mascotsAnimate
    var appearing by remember(mascot.name) { mutableStateOf(playOnAppear) }
    if (appearing) {
        LaunchedEffect(mascot.name) {
            delay(mascot.durationMs.toLong().coerceIn(400, 2_500))
            appearing = false
        }
    }
    return allowed && (hovered || appearing)
}

/**
 * A reaction chip's glyph: the mascot for [code] when there is one (aliases and skin tones
 * included), otherwise null so the caller draws what it drew before.
 */
@Composable
fun MascotReactionGlyph(mascot: OshiMascot.Mascot, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val playing = rememberMascotPlaying(mascot, hovered, playOnAppear = true)
    // An emoji at N sp occupies ~1.25·N of line height; the mascot gets a little more,
    // because its face sits inside a ring and reads smaller than a bare emoji at equal size.
    MascotImage(mascot, (fontSize.value * 1.45f).dp, playing, modifier.hoverable(source))
}

/** One tappable mascot in the reaction row or the full picker. */
@Composable
private fun MascotReactButton(mascot: OshiMascot.Mascot, size: Dp, onReact: (String) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val playing = rememberMascotPlaying(mascot, hovered, playOnAppear = false)
    Box(
        Modifier
            .clip(CircleShape)
            // A tint of the text colour: visible on white, on #1C1C1E and on OLED glass alike
            // (`surface` vanished on the Dark picker, whose fill it is).
            .background(if (hovered) OshiTheme.textPrimary.copy(alpha = 0.10f) else Color.Transparent)
            .focusRing(CircleShape)
            .clickable(
                interactionSource = source,
                indication = null,
                onClickLabel = mascot.label,
            ) { onReact(OshiMascot.wireEmoji(mascot)) }
            .hoverable(source)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        MascotImage(mascot, size, playing)
    }
}

/** One OSHI mark ("⭕" or "⭕😊") as a reaction button, at a mascot's size. */
@Composable
private fun OshiMarkReactButton(code: String, size: Dp, onReact: (String) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (hovered) OshiTheme.textPrimary.copy(alpha = 0.10f) else Color.Transparent)
            .focusRing(CircleShape)
            .clickable(interactionSource = source, indication = null, onClickLabel = "OSHI") { onReact(code) }
            .hoverable(source)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        OshiSmileMark(size, face = !com.oshi.desktop.msg.OshiReaction.isOshiO(code))
    }
}

/**
 * The quick-reaction row under a message: ❤️ 😂 😢(cry) 👍 😮 🔥 as mascots
 * ([OshiMascot.REACTION_BAR_NAMES]), then a "+" that opens [MascotReactionPicker]. Each sends
 * its wire emoji ([OshiMascot.wireEmoji]) — all six known to every iPhone
 * (__REACTION_COMPAT_2026_11_16__). Falls back to plain-emoji actions when the pack is not
 * in the build.
 */
@Composable
fun MascotReactionActions(onReact: (String) -> Unit, pickerOpen: Boolean, onTogglePicker: () -> Unit) {
    LocalAllowedReactions.current?.let { allowed -> allowed.forEach { e -> TextAction(e) { onReact(e) } }; return }
    val bar = OshiMascot.reactionBar
    if (bar.isEmpty()) {
        TextAction("❤️") { onReact("❤️") }
        TextAction("👍") { onReact("👍") }
        TextAction("😂") { onReact("😂") }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        // __OSHI_O_REACTION_2026_11_15__ the OSHI "O" leads the row, as on both phones.
        OshiMarkReactButton(com.oshi.desktop.msg.OshiReaction.OSHI_O, 22.dp, onReact)
        bar.forEach { MascotReactButton(it, 22.dp, onReact) }
        TextAction(if (pickerOpen) "−" else "+") { onTogglePicker() }
    }
}

/**
 * All 20 mascots in rows of [perRow]: first the ones every iPhone shows (the quick six, then
 * the other six), then — under a short "new — older iPhones may show 👍" line — the eight an
 * iPhone on 1.0.43 replaces with 👍 ([OshiMascot.pickerNew], __REACTION_COMPAT_2026_11_16__).
 * Picking one sends and closes. Glass in OLED, the flat elevated card elsewhere.
 */
@Composable
fun MascotReactionPicker(onReact: (String) -> Unit, onClose: () -> Unit, perRow: Int = 6) {
    LocalAllowedReactions.current?.let { allowed ->
        // __GROUP_INFO_SETTINGS_2026_11_20__ a group that allows only some reactions offers only those.
        Row(
            Modifier.glass(OshiTheme.radiusMd, if (OshiTheme.palette.isDark) OshiTheme.surface else OshiTheme.surfaceElevated, OshiTheme.separator, raised = true)
                .padding(OshiTheme.xs),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) { allowed.forEach { e -> PlainReactButton(e, 30.dp) { onReact(e); onClose() } } }
        return
    }
    @Composable
    fun grid(items: List<OshiMascot.Mascot>) {
        items.chunked(perRow).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                rowItems.forEach { m -> MascotReactButton(m, 28.dp) { emoji -> onReact(emoji); onClose() } }
            }
        }
    }
    Column(
        Modifier
            // Min intrinsic width = the widest grid row, so the hint wraps instead of
            // stretching the card past the mascots.
            .width(IntrinsicSize.Min)
            // Dark's surfaceElevated IS the chat background (#1C1C1E): lift it to `surface`
            // there so the picker reads as a card. OLED: raised glass; Light: unchanged white.
            .glass(
                OshiTheme.radiusMd,
                if (OshiTheme.palette.isDark) OshiTheme.surface else OshiTheme.surfaceElevated,
                OshiTheme.separator,
                raised = true,
            )
            .padding(OshiTheme.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            t("conv.reactions.title"),
            style = OshiTheme.typography.labelMedium,
            color = Ink.soft,
            modifier = Modifier.padding(start = OshiTheme.xs, top = 2.dp, bottom = 2.dp),
        )
        // The two OSHI marks first (Android `OshiReaction.PICKER_ALL`): the plain O and the
        // smiling O that a double-tap sends.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            OshiMarkReactButton(com.oshi.desktop.msg.OshiReaction.OSHI_O, 28.dp) { emoji -> onReact(emoji); onClose() }
            OshiMarkReactButton(com.oshi.desktop.msg.OshiReaction.OSHI_SMILE, 28.dp) { emoji -> onReact(emoji); onClose() }
        }
        grid(OshiMascot.pickerKnown)
        val newOnes = OshiMascot.pickerNew
        if (newOnes.isNotEmpty()) {
            Spacer(Modifier.height(OshiTheme.xs))
            Text(
                t("conv.reactions.new"),
                style = OshiTheme.typography.labelSmall,
                color = Ink.soft,
                modifier = Modifier.fillMaxWidth().padding(horizontal = OshiTheme.xs, vertical = 2.dp),
            )
            grid(newOnes)
        }
    }
}

/**
 * A mascot cell in the GIF picker: animates like the pack GIFs beside it, unless the OS asks
 * for reduced motion, where it is the still frame.
 */
@Composable
fun MascotGifCellContent(mascot: OshiMascot.Mascot, size: Dp) {
    MascotImage(mascot, size, playing = !MotionPreference.systemReducedMotion)
}

/**
 * __THREAD_CHROME_2027__ The quick reaction bar of the hover toolbar and the right-click
 * menu: ⭕ then ❤️ 😂 😢 👍 😮 🔥 as mascots, then a round "+" for all 20. Same wire emoji
 * as [MascotReactionActions]; plain emoji when the pack is not in the build.
 */
@Composable
fun MascotQuickBar(onReact: (String) -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    LocalAllowedReactions.current?.let { allowed ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            allowed.forEach { e -> PlainReactButton(e, 30.dp) { onReact(e) } }
        }
        return
    }
    val bar = OshiMascot.reactionBar
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        OshiMarkReactButton(com.oshi.desktop.msg.OshiReaction.OSHI_O, 26.dp, onReact)
        if (bar.isEmpty()) {
            listOf("❤️", "😂", "😢", "👍", "😮", "🔥").forEach { e ->
                Box(
                    Modifier.size(30.dp).clip(CircleShape).focusRing(CircleShape)
                        .clickable(onClickLabel = e) { onReact(e) },
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = 18.sp) }
            }
        } else {
            bar.forEach { MascotReactButton(it, 26.dp, onReact) }
        }
        GlyphButton(Glyph.PLUS, t("reaction.more"), size = 30.dp, glyphSize = 16.dp, onClick = onMore)
    }
}


/**
 * __GROUP_INFO_SETTINGS_2026_11_20__ GROUP_INFO_SETTINGS_SPEC §2.3: the reactions a group allows
 * when it allows only SOME (null = every reaction; a group with none passes no `onReact` at all).
 * The receivers drop anything else, so offering it would be offering a reaction nobody sees.
 */
val LocalAllowedReactions = androidx.compose.runtime.staticCompositionLocalOf<List<String>?> { null }

@Composable
private fun PlainReactButton(e: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).focusRing(CircleShape).clickable(onClickLabel = e, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(e, fontSize = 18.sp) }
}
