package com.oshi.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.Strings
import com.oshi.desktop.mascot.OshiMascot
import com.oshi.desktop.ui.OshiTheme

/**
 * __DESKTOP_TOOLS_2027__ The kit the tool screens (Mail, Map, AI, Radio, calls, scheduled,
 * peers, linked devices, covert text, limits) are built from, so they share ONE spacing
 * rhythm, ONE type scale and ONE icon language instead of each pane inventing its own.
 *
 * Type scale — five steps, nothing else on these screens:
 *  * [ToolType.title]   22 sp semibold — the pane's name, once.
 *  * [ToolType.section] 15 sp semibold — a card's heading.
 *  * [ToolType.body]    13 sp          — everything people read.
 *  * [ToolType.caption] 12 sp          — secondary lines, hints, units.
 *  * [ToolType.overline] 11 sp medium  — small labels above values.
 *
 * Spacing: `OshiTheme.xs/sm/md/lg/xl/xxl` only (4/8/12/16/24/32).
 */
object ToolType {
    private val titleBase = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)
    private val sectionBase = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    private val bodyBase = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)
    private val bodyStrongBase = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
    private val captionBase = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
    private val overlineBase = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp, lineHeight = 14.sp)
    private val valueBase = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
    private val monoBase = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

    /**
     * Bidi: every paragraph takes the direction of its OWN first strong letter (an English
     * mail subject stays left-to-right in an Arabic window, an Arabic label right-to-left),
     * while its alignment follows the LAYOUT, so a column of mixed rows still lines up on
     * the reading side. [natural] keeps a long paragraph aligned to its own direction.
     */
    @Composable
    private fun TextStyle.bidi(): TextStyle = copy(
        textDirection = TextDirection.Content,
        textAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left,
    )

    val title: TextStyle @Composable get() = titleBase.bidi()
    val section: TextStyle @Composable get() = sectionBase.bidi()
    val body: TextStyle @Composable get() = bodyBase.bidi()
    val bodyStrong: TextStyle @Composable get() = bodyStrongBase.bidi()
    val caption: TextStyle @Composable get() = captionBase.bidi()
    val overline: TextStyle @Composable get() = overlineBase.bidi()
    val value: TextStyle @Composable get() = valueBase.bidi()
    val mono: TextStyle @Composable get() = monoBase.copy(textDirection = TextDirection.Ltr)

    /** Long user text (a mail body, an AI answer): aligned to its own direction, like a mail client. */
    val natural: TextStyle @Composable get() = bodyBase.copy(textDirection = TextDirection.Content, textAlign = TextAlign.Start)
}

/**
 * True when the active catalog language is written right to left.
 * __MERGE_2027__ one rule for the whole app: [com.oshi.desktop.ui.DisplaySettings.isRtl], the
 * same one OshiMaterialTheme's window-wide direction switch uses.
 */
fun isRtlLanguage(tag: String = Strings.activeTag): Boolean = com.oshi.desktop.ui.DisplaySettings.isRtl(tag)

/**
 * The tool screens' paragraph handling. The window-wide direction is set once, in
 * OshiMaterialTheme; this re-states the SAME direction (from the same rule) for surfaces
 * that are composed outside that root — the incoming-call window, popups — and adds what
 * the root does not: each paragraph takes the direction of its own first strong letter.
 */
@Composable
fun ProvideLocaleDirection(content: @Composable () -> Unit) {
    val dir = com.oshi.desktop.ui.layoutDirectionFor(Strings.activeTag)
    // Each paragraph takes the direction of its own first strong letter: an English mail
    // body stays left-to-right inside an Arabic window, an Arabic label right-to-left.
    CompositionLocalProvider(
        LocalLayoutDirection provides dir,
        androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content),
    ) { content() }
}

/**
 * A tool pane: title row with icon actions, then [content] on the pane ground.
 * [scroll] = false when the content manages its own scrolling (lists, the map).
 */
@Composable
fun ToolScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ToolGlyph? = null,
    maxContentWidth: Dp = 880.dp,
    scroll: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) = ProvideLocaleDirection {
    Column(modifier.fillMaxSize().background(OshiTheme.paneGround)) {
        ToolHeader(title, subtitle, icon, actions)
        val body = Modifier.fillMaxSize().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }
        Box(body, contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = maxContentWidth).fillMaxWidth()
                    .padding(start = OshiTheme.xl, end = OshiTheme.xl, bottom = OshiTheme.xl),
                verticalArrangement = Arrangement.spacedBy(OshiTheme.lg),
                content = content,
            )
        }
    }
}

/** The header row a tool pane opens with: icon disc, title, subtitle, actions at the end. */
@Composable
fun ToolHeader(title: String, subtitle: String? = null, icon: ToolGlyph? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = OshiTheme.xl, vertical = OshiTheme.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        if (icon != null) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(OshiTheme.brandGradient),
                contentAlignment = Alignment.Center,
            ) { ToolIcon(icon, Color.White, 22.dp) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = ToolType.title, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = ToolType.caption, color = Ink.soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A card: rounded, elevated surface, glass in OLED. */
@Composable
fun ToolCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ToolGlyph? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    padding: Dp = OshiTheme.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .glass(OshiTheme.radiusXl, OshiTheme.surfaceElevated, OshiTheme.cardOutline)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                if (icon != null) ToolIcon(icon, OshiTheme.brand, 18.dp)
                Text(title, style = ToolType.section, color = Ink.strong, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs), verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        }
        content()
    }
}

/** A secondary line under a heading. */
@Composable
fun ToolHint(text: String, modifier: Modifier = Modifier, color: Color = Ink.soft) {
    Text(text, style = ToolType.caption, color = color, modifier = modifier)
}

/** An icon button with a tooltip and an accessible name — the only kind of action icon here. */
@Composable
fun ToolIconButton(
    icon: ToolGlyph,
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color? = null,
    size: Dp = 36.dp,
    onClick: () -> Unit,
) {
    IconActionButton(label = label, size = size, active = active, enabled = enabled, icon = { t -> ToolIcon(icon, tint ?: t, 18.dp) }, onClick = onClick)
}

enum class ToolButtonStyle { PRIMARY, SECONDARY, DANGER, QUIET }

/** A labelled button, optionally with an icon. One height (36 dp), one radius (pill). */
@Composable
fun ToolButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ToolGlyph? = null,
    style: ToolButtonStyle = ToolButtonStyle.SECONDARY,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val (source, hovered) = rememberRowInteraction()
    val bg: Modifier = when {
        !enabled -> Modifier.background(OshiTheme.separator.copy(alpha = 0.35f))
        style == ToolButtonStyle.PRIMARY -> Modifier.background(OshiTheme.brandGradient)
        style == ToolButtonStyle.DANGER -> Modifier.background(OshiTheme.danger.copy(alpha = if (hovered.value) 0.22f else 0.14f))
        style == ToolButtonStyle.QUIET -> Modifier.background(if (hovered.value) OshiTheme.textPrimary.copy(alpha = 0.07f) else Color.Transparent)
        else -> Modifier.background(if (hovered.value) OshiTheme.brand.copy(alpha = 0.16f) else OshiTheme.brand.copy(alpha = 0.10f))
    }
    val ink = when {
        !enabled -> Ink.soft.copy(alpha = 0.6f)
        style == ToolButtonStyle.PRIMARY -> Color.White
        style == ToolButtonStyle.DANGER -> OshiTheme.danger
        style == ToolButtonStyle.QUIET -> Ink.strong
        else -> OshiTheme.brand
    }
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(OshiTheme.pill)
            .then(bg)
            .focusRing(OshiTheme.pill)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .hoverable(source)
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm, Alignment.CenterHorizontally),
    ) {
        if (icon != null) ToolIcon(icon, ink, 16.dp)
        Text(label, style = ToolType.bodyStrong, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A single-line text field in the kit's shape, with a placeholder and an optional leading icon. */
@Composable
fun ToolField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: ToolGlyph? = null,
    singleLine: Boolean = true,
    minHeight: Dp = 40.dp,
    mono: Boolean = false,
    enabled: Boolean = true,
    /** The fill. Default suits a field inside a card; on the bare pane ground pass `surfaceElevated`. */
    container: Color = OshiTheme.surface,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .heightIn(min = minHeight)
            .clip(OshiTheme.radiusMd)
            .background(container)
            .glassField()
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        if (icon != null) ToolIcon(icon, Ink.soft, 16.dp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = ToolType.body, color = Ink.soft, maxLines = if (singleLine) 1 else 3)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = singleLine,
                enabled = enabled,
                textStyle = (if (mono) ToolType.mono else ToolType.body).copy(color = Ink.strong),
                cursorBrush = SolidColor(OshiTheme.brand),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            )
        }
        trailing()
    }
}

/** A switch row: title, explanation, switch at the end. */
@Composable
fun ToolSwitchRow(title: String, body: String?, checked: Boolean, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(OshiTheme.radiusMd).clickable(enabled = enabled, role = Role.Switch) { onToggle(!checked) }
            .padding(vertical = OshiTheme.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ToolType.bodyStrong, color = Ink.strong)
            if (body != null) Text(body, style = ToolType.caption, color = Ink.soft)
        }
        Switch(
            checked = checked, onCheckedChange = onToggle, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = OshiTheme.brand, checkedThumbColor = Color.White),
        )
    }
}

/** A label above a value; [mono] for keys and addresses. */
@Composable
fun ToolValue(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Column(modifier.padding(vertical = OshiTheme.xxs)) {
        Text(label.uppercase(), style = ToolType.overline, color = Ink.soft)
        Text(value, style = if (mono) ToolType.mono.copy(color = Ink.strong) else ToolType.body, color = Ink.strong)
    }
}

/** A measurement tile: icon, small label, big value, optional detail and a 0..1 meter. */
@Composable
fun ToolStat(
    icon: ToolGlyph,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    level: Float? = null,
    tone: Color = OshiTheme.brand,
) {
    Column(
        modifier
            .clip(OshiTheme.radiusMd)
            .background(OshiTheme.surface)
            .padding(OshiTheme.md),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
            ToolIcon(icon, tone, 16.dp, level = level)
            Text(label.uppercase(), style = ToolType.overline, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, style = ToolType.value, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (level != null) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(OshiTheme.pill).background(OshiTheme.separator.copy(alpha = 0.5f))) {
                Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(4.dp).clip(OshiTheme.pill).background(tone))
            }
        }
        if (detail != null) Text(detail, style = ToolType.caption, color = Ink.soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A notice strip: info (brand), success, warning or error. */
enum class ToolTone { INFO, SUCCESS, WARNING, ERROR }

@Composable
fun ToolNotice(text: String, tone: ToolTone = ToolTone.INFO, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = when (tone) {
        ToolTone.INFO -> OshiTheme.brand
        ToolTone.SUCCESS -> OshiTheme.success
        ToolTone.WARNING -> OshiTheme.warning
        ToolTone.ERROR -> OshiTheme.danger
    }
    Row(
        modifier.fillMaxWidth().clip(OshiTheme.radiusMd).background(c.copy(alpha = 0.11f)).padding(OshiTheme.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        ToolIcon(
            when (tone) { ToolTone.ERROR, ToolTone.WARNING -> ToolGlyph.ALERT; ToolTone.SUCCESS -> ToolGlyph.CHECK; else -> ToolGlyph.INFO },
            c, 16.dp,
        )
        Text(text, style = ToolType.body, color = Ink.strong, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/**
 * The empty state every tool pane uses: the OSHI mascot, a title, one sentence, and at
 * most one action. [mascot] is a mascot name from the pack (`sleep`, `thinking`, `wave`,
 * `peek`, `ok`, …); a build without the pack falls back to the brand disc.
 */
@Composable
fun ToolEmptyState(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    mascot: String = "peek",
    size: Dp = 112.dp,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = OshiTheme.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        val m = OshiMascot.named(mascot) ?: OshiMascot.all.firstOrNull()
        if (m != null) MascotImage(m, size, playing = false) else BrandDisc(Glyph.INFO, size = 64.dp)
        Spacer(Modifier.height(OshiTheme.xs))
        Text(title, style = ToolType.section, color = Ink.strong, textAlign = TextAlign.Center)
        if (body != null) {
            Text(body, style = ToolType.body, color = Ink.soft, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 440.dp))
        }
        if (action != null) { Spacer(Modifier.height(OshiTheme.xs)); action() }
    }
}

/** A segmented row of icon+label tabs, sized for a desktop header. */
@Composable
fun <T> ToolTabs(options: List<T>, selected: T, label: (T) -> String, icon: (T) -> ToolGlyph?, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(OshiTheme.pill).background(OshiTheme.surface).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (o in options) {
            val on = o == selected
            Row(
                Modifier.clip(OshiTheme.pill)
                    .background(if (on) OshiTheme.selectedFill else Color.Transparent)
                    .focusRing(OshiTheme.pill)
                    .clickable(role = Role.Tab) { onSelect(o) }
                    .padding(horizontal = OshiTheme.md, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                icon(o)?.let { ToolIcon(it, if (on) OshiTheme.brand else Ink.soft, 15.dp) }
                Text(label(o), style = ToolType.caption.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = if (on) Ink.strong else Ink.soft, maxLines = 1)
            }
        }
    }
}

/** Splits into side-by-side columns when the pane is wide enough, stacked otherwise. */
@Composable
fun ToolColumns(minTwoColumnWidth: Dp = 760.dp, first: @Composable ColumnScope.() -> Unit, second: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= minTwoColumnWidth) {
            Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.lg)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(OshiTheme.lg), content = first)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(OshiTheme.lg), content = second)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.lg)) { first(); second() }
        }
    }
}

/** A list row: leading icon, title + subtitle, trailing content. */
@Composable
fun ToolRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ToolGlyph? = null,
    leading: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        modifier.fillMaxWidth()
            .clip(OshiTheme.radiusMd)
            .background(
                when {
                    selected -> OshiTheme.brand.copy(alpha = 0.12f)
                    hovered.value && onClick != null -> OshiTheme.textPrimary.copy(alpha = 0.05f)
                    else -> Color.Transparent
                },
            )
            .let { if (onClick != null) it.clickable(interactionSource = source, indication = null, onClick = onClick).hoverable(source) else it }
            .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        when {
            leading != null -> leading()
            icon != null -> Box(
                Modifier.size(34.dp).clip(CircleShape).background(OshiTheme.brand.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { ToolIcon(icon, OshiTheme.brand, 17.dp) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = ToolType.bodyStrong, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = ToolType.caption, color = Ink.soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** A small rounded label. */
@Composable
fun ToolPill(text: String, color: Color = OshiTheme.brand, modifier: Modifier = Modifier) {
    Text(
        text, style = ToolType.overline, color = color, maxLines = 1,
        modifier = modifier.clip(OshiTheme.pill).background(color.copy(alpha = 0.13f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// ---------------------------------------------------------------------------- icons

/**
 * Line icons for the tool screens, drawn at any size from a 1×1 unit square with one stroke
 * weight, in the same hand as [Glyph]. Directional ones ([MIRRORED]) flip in right-to-left.
 */
enum class ToolGlyph {
    MAIL, INBOX, SEND, DRAFT, ARCHIVE, TRASH, DRIVE, REFRESH, COMPOSE, ATTACH, REPLY, FORWARD, UNREAD, RESTORE,
    DOWNLOAD, UPLOAD, BROWSER, SETTINGS, MAP, PIN, LAYERS, ZOOM_IN, ZOOM_OUT, LOCATE, SPARKLE, FOLDER, RADIO,
    BATTERY, SIGNAL, AIRTIME, USB, GLOBE, BROADCAST, NODE, PHONE, PHONE_DOWN, MIC, MIC_OFF, VIDEO, VIDEO_OFF,
    SPEAKER, MINIMIZE, SHIELD, CLOCK, CALENDAR, LINK, LAPTOP, SYNC, PEOPLE, WIFI, KEY, EYE_OFF, INFO, CHECK,
    CLOSE, CHEVRON, SEARCH, COPY, ALERT, PLUS, WAVE, TEXT, LOCK, STAR, EDIT, BOLT, THERMO, CHIP, DOC,
}

@Composable
fun ToolIcon(glyph: ToolGlyph, tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier, level: Float? = null) {
    // Arrows that point "back" or "onward" are mirrored in a right-to-left layout.
    val mirror = glyph in MIRRORED && LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.size(size).then(if (mirror) Modifier.graphicsLayer(scaleX = -1f) else Modifier)) { drawToolGlyph(glyph, tint, level) }
}

private val MIRRORED = setOf(ToolGlyph.REPLY, ToolGlyph.FORWARD, ToolGlyph.CHEVRON, ToolGlyph.SEND)

private fun DrawScope.drawToolGlyph(glyph: ToolGlyph, tint: Color, level: Float?) {
    val s = size.minDimension
    val w = s * 0.085f
    val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = w) =
        drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = width, cap = StrokeCap.Round)
    fun path(vararg pts: Float, close: Boolean = false, fill: Boolean = false) {
        val path = Path().apply {
            moveTo(pts[0] * s, pts[1] * s)
            var i = 2
            while (i < pts.size) { lineTo(pts[i] * s, pts[i + 1] * s); i += 2 }
            if (close) close()
        }
        if (fill) drawPath(path, tint) else drawPath(path, tint, style = stroke)
    }
    fun rrect(x: Float, y: Float, rw: Float, rh: Float, r: Float = 0.08f, fill: Boolean = false) =
        if (fill) drawRoundRect(tint, p(x, y), Size(rw * s, rh * s), CornerRadius(r * s))
        else drawRoundRect(tint, p(x, y), Size(rw * s, rh * s), CornerRadius(r * s), style = stroke)
    fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) =
        if (fill) drawCircle(tint, r * s, p(cx, cy)) else drawCircle(tint, r * s, p(cx, cy), style = stroke)
    fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) =
        drawArc(tint, start, sweep, false, p(cx - r, cy - r), Size(2 * r * s, 2 * r * s), style = stroke)

    when (glyph) {
        ToolGlyph.MAIL -> { rrect(0.12f, 0.24f, 0.76f, 0.54f); path(0.14f, 0.28f, 0.5f, 0.55f, 0.86f, 0.28f) }
        ToolGlyph.INBOX -> {
            path(0.12f, 0.55f, 0.24f, 0.22f, 0.76f, 0.22f, 0.88f, 0.55f)
            rrect(0.12f, 0.55f, 0.76f, 0.25f)
            path(0.12f, 0.55f, 0.36f, 0.55f, 0.42f, 0.64f, 0.58f, 0.64f, 0.64f, 0.55f, 0.88f, 0.55f)
        }
        ToolGlyph.SEND -> { path(0.14f, 0.48f, 0.86f, 0.16f, 0.6f, 0.86f, 0.46f, 0.56f, close = true); line(0.46f, 0.56f, 0.86f, 0.16f) }
        ToolGlyph.DRAFT, ToolGlyph.DOC -> {
            path(0.24f, 0.12f, 0.6f, 0.12f, 0.78f, 0.3f, 0.78f, 0.88f, 0.24f, 0.88f, close = true)
            path(0.6f, 0.12f, 0.6f, 0.3f, 0.78f, 0.3f)
            line(0.36f, 0.5f, 0.66f, 0.5f); line(0.36f, 0.66f, 0.58f, 0.66f)
        }
        ToolGlyph.ARCHIVE -> { rrect(0.12f, 0.18f, 0.76f, 0.18f, 0.04f); path(0.18f, 0.36f, 0.18f, 0.82f, 0.82f, 0.82f, 0.82f, 0.36f); line(0.4f, 0.52f, 0.6f, 0.52f) }
        ToolGlyph.TRASH -> {
            line(0.16f, 0.26f, 0.84f, 0.26f); path(0.38f, 0.26f, 0.4f, 0.15f, 0.6f, 0.15f, 0.62f, 0.26f)
            path(0.24f, 0.26f, 0.29f, 0.86f, 0.71f, 0.86f, 0.76f, 0.26f); line(0.43f, 0.4f, 0.43f, 0.72f); line(0.57f, 0.4f, 0.57f, 0.72f)
        }
        ToolGlyph.DRIVE -> {
            // A cloud.
            arc(0.36f, 0.56f, 0.18f, 90f, 180f); arc(0.56f, 0.44f, 0.22f, 180f, 180f); arc(0.7f, 0.6f, 0.16f, 270f, 180f)
            line(0.36f, 0.74f, 0.7f, 0.76f)
        }
        ToolGlyph.REFRESH, ToolGlyph.SYNC -> {
            arc(0.5f, 0.5f, 0.32f, -60f, 280f)
            path(0.66f, 0.16f, 0.68f, 0.24f, 0.6f, 0.28f)
            if (glyph == ToolGlyph.SYNC) circle(0.5f, 0.5f, 0.05f, fill = true)
        }
        ToolGlyph.COMPOSE, ToolGlyph.EDIT -> {
            path(0.2f, 0.8f, 0.24f, 0.62f, 0.66f, 0.2f, 0.8f, 0.34f, 0.38f, 0.76f, close = true); line(0.58f, 0.28f, 0.72f, 0.42f)
        }
        ToolGlyph.ATTACH -> {
            val pth = Path().apply {
                moveTo(0.68f * s, 0.34f * s); lineTo(0.4f * s, 0.64f * s)
                cubicTo(0.33f * s, 0.72f * s, 0.24f * s, 0.62f * s, 0.31f * s, 0.55f * s)
                lineTo(0.62f * s, 0.22f * s)
                cubicTo(0.74f * s, 0.1f * s, 0.92f * s, 0.27f * s, 0.79f * s, 0.4f * s)
                lineTo(0.45f * s, 0.76f * s)
                cubicTo(0.3f * s, 0.92f * s, 0.06f * s, 0.7f * s, 0.22f * s, 0.54f * s)
                lineTo(0.48f * s, 0.27f * s)
            }
            drawPath(pth, tint, style = stroke)
        }
        ToolGlyph.REPLY -> { path(0.4f, 0.24f, 0.14f, 0.48f, 0.4f, 0.72f); val pth = Path().apply { moveTo(0.16f * s, 0.48f * s); cubicTo(0.6f * s, 0.48f * s, 0.86f * s, 0.5f * s, 0.86f * s, 0.8f * s) }; drawPath(pth, tint, style = stroke) }
        ToolGlyph.FORWARD -> { path(0.6f, 0.24f, 0.86f, 0.48f, 0.6f, 0.72f); val pth = Path().apply { moveTo(0.84f * s, 0.48f * s); cubicTo(0.4f * s, 0.48f * s, 0.14f * s, 0.5f * s, 0.14f * s, 0.8f * s) }; drawPath(pth, tint, style = stroke) }
        ToolGlyph.UNREAD -> { rrect(0.1f, 0.3f, 0.66f, 0.5f); path(0.12f, 0.34f, 0.43f, 0.58f, 0.74f, 0.34f); circle(0.8f, 0.24f, 0.11f, fill = true) }
        ToolGlyph.RESTORE -> { arc(0.52f, 0.52f, 0.32f, 200f, 300f); path(0.12f, 0.36f, 0.22f, 0.5f, 0.36f, 0.42f); line(0.52f, 0.36f, 0.52f, 0.54f); line(0.52f, 0.54f, 0.64f, 0.62f) }
        ToolGlyph.DOWNLOAD -> { line(0.5f, 0.14f, 0.5f, 0.64f); path(0.3f, 0.46f, 0.5f, 0.66f, 0.7f, 0.46f); line(0.18f, 0.84f, 0.82f, 0.84f) }
        ToolGlyph.UPLOAD -> { line(0.5f, 0.66f, 0.5f, 0.16f); path(0.3f, 0.34f, 0.5f, 0.14f, 0.7f, 0.34f); line(0.18f, 0.84f, 0.82f, 0.84f) }
        ToolGlyph.BROWSER -> { rrect(0.1f, 0.18f, 0.8f, 0.64f); line(0.1f, 0.36f, 0.9f, 0.36f); circle(0.2f, 0.27f, 0.025f, fill = true); circle(0.28f, 0.27f, 0.025f, fill = true) }
        ToolGlyph.SETTINGS -> {
            circle(0.5f, 0.5f, 0.12f)
            circle(0.5f, 0.5f, 0.27f)
            for (i in 0 until 6) {
                val a = Math.toRadians(i * 60.0 + 30.0)
                line(0.5f + 0.27f * Math.cos(a).toFloat(), 0.5f + 0.27f * Math.sin(a).toFloat(), 0.5f + 0.38f * Math.cos(a).toFloat(), 0.5f + 0.38f * Math.sin(a).toFloat(), w * 1.6f)
            }
        }
        ToolGlyph.MAP -> {
            path(0.1f, 0.24f, 0.36f, 0.14f, 0.64f, 0.26f, 0.9f, 0.16f, 0.9f, 0.76f, 0.64f, 0.86f, 0.36f, 0.74f, 0.1f, 0.84f, close = true)
            line(0.36f, 0.14f, 0.36f, 0.74f); line(0.64f, 0.26f, 0.64f, 0.86f)
        }
        ToolGlyph.PIN -> {
            val pth = Path().apply {
                moveTo(0.5f * s, 0.9f * s)
                cubicTo(0.3f * s, 0.66f * s, 0.2f * s, 0.52f * s, 0.2f * s, 0.4f * s)
                cubicTo(0.2f * s, 0.2f * s, 0.36f * s, 0.1f * s, 0.5f * s, 0.1f * s)
                cubicTo(0.64f * s, 0.1f * s, 0.8f * s, 0.2f * s, 0.8f * s, 0.4f * s)
                cubicTo(0.8f * s, 0.52f * s, 0.7f * s, 0.66f * s, 0.5f * s, 0.9f * s)
                close()
            }
            drawPath(pth, tint, style = stroke); circle(0.5f, 0.4f, 0.1f)
        }
        ToolGlyph.LAYERS -> { path(0.5f, 0.14f, 0.88f, 0.34f, 0.5f, 0.54f, 0.12f, 0.34f, close = true); path(0.12f, 0.52f, 0.5f, 0.72f, 0.88f, 0.52f); path(0.12f, 0.68f, 0.5f, 0.88f, 0.88f, 0.68f) }
        ToolGlyph.ZOOM_IN, ToolGlyph.PLUS -> { line(0.5f, 0.18f, 0.5f, 0.82f); line(0.18f, 0.5f, 0.82f, 0.5f) }
        ToolGlyph.ZOOM_OUT -> line(0.18f, 0.5f, 0.82f, 0.5f)
        ToolGlyph.LOCATE -> { circle(0.5f, 0.5f, 0.26f); circle(0.5f, 0.5f, 0.08f, fill = true); line(0.5f, 0.08f, 0.5f, 0.22f); line(0.5f, 0.78f, 0.5f, 0.92f); line(0.08f, 0.5f, 0.22f, 0.5f); line(0.78f, 0.5f, 0.92f, 0.5f) }
        ToolGlyph.SPARKLE -> {
            path(0.42f, 0.12f, 0.5f, 0.38f, 0.76f, 0.46f, 0.5f, 0.54f, 0.42f, 0.82f, 0.34f, 0.54f, 0.08f, 0.46f, 0.34f, 0.38f, close = true, fill = true)
            path(0.76f, 0.1f, 0.8f, 0.22f, 0.92f, 0.26f, 0.8f, 0.3f, 0.76f, 0.42f, 0.72f, 0.3f, 0.6f, 0.26f, 0.72f, 0.22f, close = true, fill = true)
        }
        ToolGlyph.FOLDER -> path(0.1f, 0.26f, 0.38f, 0.26f, 0.46f, 0.34f, 0.9f, 0.34f, 0.9f, 0.8f, 0.1f, 0.8f, close = true)
        ToolGlyph.RADIO -> {
            rrect(0.24f, 0.4f, 0.52f, 0.48f); line(0.36f, 0.4f, 0.28f, 0.1f)
            circle(0.62f, 0.64f, 0.08f); line(0.34f, 0.56f, 0.46f, 0.56f); line(0.34f, 0.7f, 0.46f, 0.7f)
        }
        ToolGlyph.BATTERY -> {
            rrect(0.08f, 0.3f, 0.74f, 0.4f, 0.07f); rrect(0.84f, 0.42f, 0.07f, 0.16f, 0.02f, fill = true)
            val lv = (level ?: 0.6f).coerceIn(0f, 1f)
            if (lv > 0f) rrect(0.14f, 0.36f, 0.62f * lv, 0.28f, 0.03f, fill = true)
        }
        ToolGlyph.SIGNAL -> {
            val bars = level?.let { (it * 4f).toInt().coerceIn(0, 4) } ?: 4
            for (i in 0 until 4) {
                val x = 0.16f + i * 0.2f
                val top = 0.76f - (i + 1) * 0.15f
                val c = if (i < bars) tint else tint.copy(alpha = 0.25f)
                drawRoundRect(c, p(x, top), Size(0.12f * s, (0.84f - top) * s), CornerRadius(0.03f * s))
            }
        }
        ToolGlyph.AIRTIME -> {
            circle(0.5f, 0.54f, 0.32f); line(0.5f, 0.54f, 0.5f, 0.34f); line(0.5f, 0.54f, 0.64f, 0.62f); line(0.42f, 0.1f, 0.58f, 0.1f)
        }
        ToolGlyph.USB -> {
            line(0.5f, 0.12f, 0.5f, 0.78f); path(0.4f, 0.22f, 0.5f, 0.1f, 0.6f, 0.22f); circle(0.5f, 0.84f, 0.07f, fill = true)
            path(0.5f, 0.6f, 0.28f, 0.46f, 0.28f, 0.34f); path(0.5f, 0.5f, 0.72f, 0.38f, 0.72f, 0.28f)
            circle(0.28f, 0.3f, 0.05f, fill = true); rrect(0.67f, 0.2f, 0.1f, 0.1f, 0.01f, fill = true)
        }
        ToolGlyph.GLOBE -> {
            circle(0.5f, 0.5f, 0.36f); line(0.14f, 0.5f, 0.86f, 0.5f)
            drawOval(tint, p(0.34f, 0.14f), Size(0.32f * s, 0.72f * s), style = stroke)
        }
        ToolGlyph.BROADCAST -> {
            circle(0.5f, 0.5f, 0.07f, fill = true)
            arc(0.5f, 0.5f, 0.2f, -50f, 100f); arc(0.5f, 0.5f, 0.2f, 130f, 100f)
            arc(0.5f, 0.5f, 0.36f, -50f, 100f); arc(0.5f, 0.5f, 0.36f, 130f, 100f)
        }
        ToolGlyph.NODE -> {
            circle(0.5f, 0.5f, 0.1f, fill = true); circle(0.18f, 0.24f, 0.07f); circle(0.82f, 0.28f, 0.07f); circle(0.3f, 0.82f, 0.07f); circle(0.8f, 0.78f, 0.07f)
            line(0.5f, 0.5f, 0.23f, 0.29f); line(0.5f, 0.5f, 0.76f, 0.31f); line(0.5f, 0.5f, 0.33f, 0.76f); line(0.5f, 0.5f, 0.75f, 0.74f)
        }
        ToolGlyph.PHONE -> {
            val pth = Path().apply {
                moveTo(0.26f * s, 0.14f * s); lineTo(0.38f * s, 0.14f * s); lineTo(0.46f * s, 0.34f * s); lineTo(0.36f * s, 0.42f * s)
                cubicTo(0.42f * s, 0.56f * s, 0.46f * s, 0.6f * s, 0.6f * s, 0.66f * s)
                lineTo(0.68f * s, 0.56f * s); lineTo(0.86f * s, 0.64f * s); lineTo(0.86f * s, 0.76f * s)
                cubicTo(0.86f * s, 0.84f * s, 0.8f * s, 0.88f * s, 0.72f * s, 0.86f * s)
                cubicTo(0.42f * s, 0.8f * s, 0.2f * s, 0.58f * s, 0.14f * s, 0.28f * s)
                cubicTo(0.12f * s, 0.2f * s, 0.18f * s, 0.14f * s, 0.26f * s, 0.14f * s)
                close()
            }
            drawPath(pth, tint, style = stroke)
        }
        ToolGlyph.PHONE_DOWN -> {
            val pth = Path().apply {
                moveTo(0.1f * s, 0.6f * s)
                cubicTo(0.1f * s, 0.4f * s, 0.9f * s, 0.4f * s, 0.9f * s, 0.6f * s)
                lineTo(0.88f * s, 0.68f * s); lineTo(0.68f * s, 0.66f * s); lineTo(0.66f * s, 0.54f * s)
                cubicTo(0.56f * s, 0.5f * s, 0.44f * s, 0.5f * s, 0.34f * s, 0.54f * s)
                lineTo(0.32f * s, 0.66f * s); lineTo(0.12f * s, 0.68f * s)
                close()
            }
            drawPath(pth, tint)
        }
        ToolGlyph.MIC, ToolGlyph.MIC_OFF -> {
            rrect(0.38f, 0.12f, 0.24f, 0.44f, 0.12f)
            arc(0.5f, 0.46f, 0.24f, 0f, 180f); line(0.5f, 0.7f, 0.5f, 0.86f); line(0.36f, 0.86f, 0.64f, 0.86f)
            if (glyph == ToolGlyph.MIC_OFF) line(0.16f, 0.14f, 0.84f, 0.86f, w * 1.1f)
        }
        ToolGlyph.VIDEO, ToolGlyph.VIDEO_OFF -> {
            rrect(0.08f, 0.28f, 0.58f, 0.44f, 0.08f); path(0.66f, 0.44f, 0.92f, 0.3f, 0.92f, 0.7f, 0.66f, 0.56f)
            if (glyph == ToolGlyph.VIDEO_OFF) line(0.12f, 0.14f, 0.84f, 0.86f, w * 1.1f)
        }
        ToolGlyph.SPEAKER -> {
            path(0.12f, 0.38f, 0.28f, 0.38f, 0.48f, 0.18f, 0.48f, 0.82f, 0.28f, 0.62f, 0.12f, 0.62f, close = true)
            arc(0.5f, 0.5f, 0.18f, -45f, 90f); arc(0.5f, 0.5f, 0.32f, -45f, 90f)
        }
        ToolGlyph.MINIMIZE -> { path(0.14f, 0.58f, 0.42f, 0.58f, 0.42f, 0.86f); line(0.14f, 0.86f, 0.42f, 0.58f); path(0.58f, 0.14f, 0.58f, 0.42f, 0.86f, 0.42f); line(0.58f, 0.42f, 0.86f, 0.14f) }
        ToolGlyph.SHIELD -> {
            path(0.5f, 0.1f, 0.84f, 0.22f, 0.8f, 0.56f, 0.5f, 0.9f, 0.2f, 0.56f, 0.16f, 0.22f, close = true); path(0.36f, 0.5f, 0.46f, 0.6f, 0.66f, 0.38f)
        }
        ToolGlyph.CLOCK -> { circle(0.5f, 0.5f, 0.36f); line(0.5f, 0.5f, 0.5f, 0.28f); line(0.5f, 0.5f, 0.66f, 0.58f) }
        ToolGlyph.CALENDAR -> { rrect(0.14f, 0.2f, 0.72f, 0.66f); line(0.14f, 0.4f, 0.86f, 0.4f); line(0.34f, 0.12f, 0.34f, 0.26f); line(0.66f, 0.12f, 0.66f, 0.26f); circle(0.36f, 0.6f, 0.04f, fill = true); circle(0.5f, 0.6f, 0.04f, fill = true); circle(0.64f, 0.6f, 0.04f, fill = true) }
        ToolGlyph.LINK -> {
            drawRoundRect(tint, p(0.08f, 0.36f), Size(0.48f * s, 0.28f * s), CornerRadius(0.14f * s), style = stroke)
            drawRoundRect(tint, p(0.44f, 0.36f), Size(0.48f * s, 0.28f * s), CornerRadius(0.14f * s), style = stroke)
        }
        ToolGlyph.LAPTOP -> { rrect(0.2f, 0.22f, 0.6f, 0.42f, 0.04f); path(0.08f, 0.76f, 0.92f, 0.76f, 0.86f, 0.64f, 0.14f, 0.64f, close = true) }
        ToolGlyph.PEOPLE -> { circle(0.38f, 0.36f, 0.13f); arc(0.38f, 0.82f, 0.26f, 180f, 180f); circle(0.68f, 0.38f, 0.1f); arc(0.7f, 0.8f, 0.2f, 220f, 140f) }
        ToolGlyph.WIFI -> { circle(0.5f, 0.78f, 0.05f, fill = true); arc(0.5f, 0.78f, 0.2f, 225f, 90f); arc(0.5f, 0.78f, 0.36f, 225f, 90f); arc(0.5f, 0.78f, 0.52f, 230f, 80f) }
        ToolGlyph.KEY -> { circle(0.32f, 0.5f, 0.16f); line(0.48f, 0.5f, 0.88f, 0.5f); line(0.74f, 0.5f, 0.74f, 0.64f); line(0.86f, 0.5f, 0.86f, 0.62f) }
        ToolGlyph.EYE_OFF -> {
            val pth = Path().apply { moveTo(0.1f * s, 0.5f * s); quadraticTo(0.5f * s, 0.12f * s, 0.9f * s, 0.5f * s); quadraticTo(0.5f * s, 0.88f * s, 0.1f * s, 0.5f * s) }
            drawPath(pth, tint, style = stroke); circle(0.5f, 0.5f, 0.11f); line(0.16f, 0.16f, 0.84f, 0.84f)
        }
        ToolGlyph.INFO -> { circle(0.5f, 0.5f, 0.38f); line(0.5f, 0.46f, 0.5f, 0.7f); circle(0.5f, 0.32f, 0.04f, fill = true) }
        ToolGlyph.CHECK -> path(0.2f, 0.52f, 0.42f, 0.72f, 0.8f, 0.3f)
        ToolGlyph.CLOSE -> { line(0.24f, 0.24f, 0.76f, 0.76f); line(0.76f, 0.24f, 0.24f, 0.76f) }
        ToolGlyph.CHEVRON -> path(0.38f, 0.22f, 0.66f, 0.5f, 0.38f, 0.78f)
        ToolGlyph.SEARCH -> { circle(0.44f, 0.44f, 0.28f); line(0.65f, 0.65f, 0.86f, 0.86f) }
        ToolGlyph.COPY -> { rrect(0.32f, 0.32f, 0.52f, 0.52f); path(0.66f, 0.2f, 0.66f, 0.16f, 0.16f, 0.16f, 0.16f, 0.66f, 0.2f, 0.66f) }
        ToolGlyph.ALERT -> { path(0.5f, 0.12f, 0.9f, 0.84f, 0.1f, 0.84f, close = true); line(0.5f, 0.38f, 0.5f, 0.6f); circle(0.5f, 0.72f, 0.035f, fill = true) }
        ToolGlyph.WAVE -> {
            val pth = Path().apply { moveTo(0.08f * s, 0.5f * s); cubicTo(0.2f * s, 0.2f * s, 0.32f * s, 0.2f * s, 0.42f * s, 0.5f * s); cubicTo(0.52f * s, 0.8f * s, 0.64f * s, 0.8f * s, 0.74f * s, 0.5f * s); cubicTo(0.8f * s, 0.34f * s, 0.86f * s, 0.3f * s, 0.92f * s, 0.36f * s) }
            drawPath(pth, tint, style = stroke)
        }
        ToolGlyph.TEXT -> { line(0.16f, 0.28f, 0.84f, 0.28f); line(0.16f, 0.46f, 0.84f, 0.46f); line(0.16f, 0.64f, 0.84f, 0.64f); line(0.16f, 0.82f, 0.56f, 0.82f) }
        ToolGlyph.LOCK -> { rrect(0.2f, 0.44f, 0.6f, 0.42f, 0.08f); arc(0.5f, 0.44f, 0.18f, 180f, 180f); line(0.32f, 0.44f, 0.32f, 0.44f) }
        ToolGlyph.STAR -> path(0.5f, 0.1f, 0.61f, 0.37f, 0.9f, 0.39f, 0.68f, 0.57f, 0.75f, 0.86f, 0.5f, 0.7f, 0.25f, 0.86f, 0.32f, 0.57f, 0.1f, 0.39f, 0.39f, 0.37f, close = true)
        ToolGlyph.BOLT -> path(0.56f, 0.08f, 0.22f, 0.56f, 0.48f, 0.56f, 0.42f, 0.92f, 0.78f, 0.42f, 0.52f, 0.42f, close = true, fill = true)
        ToolGlyph.THERMO -> { rrect(0.42f, 0.1f, 0.16f, 0.56f, 0.08f); circle(0.5f, 0.76f, 0.14f) }
        ToolGlyph.CHIP -> {
            rrect(0.26f, 0.26f, 0.48f, 0.48f, 0.06f); rrect(0.38f, 0.38f, 0.24f, 0.24f, 0.02f)
            for (i in 0 until 3) { val c = 0.36f + i * 0.14f; line(c, 0.12f, c, 0.26f); line(c, 0.74f, c, 0.88f); line(0.12f, c, 0.26f, c); line(0.74f, c, 0.88f, c) }
        }
    }
}

/** Bytes as people read them: "12 KB", "3.4 MB". Locale-free on purpose — units are international. */
fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(java.util.Locale.ROOT, kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(java.util.Locale.ROOT, mb)
    return "%.2f GB".format(java.util.Locale.ROOT, mb / 1024.0)
}

/**
 * Joins row facts with " · ", each one wrapped in a Unicode first-strong isolate (FSI…PDI)
 * so "868 MHz", "-97 dBm" or an address keeps its own order inside an Arabic or Hebrew
 * line instead of being torn apart by the bidi algorithm.
 */
fun List<String>.bidiJoin(separator: String = " · "): String = joinToString(separator) { bidiIsolate(it) }

fun bidiIsolate(s: String): String = "\u2068$s\u2069"

/** Numbers and units that must read left to right even in an RTL line (coordinates, "4 h"). */
fun ltrIsolate(s: String): String = "\u2066$s\u2069"

/** A thin vertical rule between side-by-side panes. */
@Composable
fun VerticalRule(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).fillMaxSize().background(OshiTheme.separator.copy(alpha = 0.5f)))
}
