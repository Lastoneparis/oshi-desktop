package com.oshi.desktop.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.ui.OshiTheme

/**
 * The pill segmented control the shipped macOS window uses twice on its first screen.
 *
 * ============================================================ TRANSCRIBED, NOT DESIGNED
 *
 * `~/Desktop/OSHI_Mac_Captures/HABILLE-1-Messages.png` has two of these and they are the
 * same control at two sizes: a light grey track, a WHITE raised pill under the selection,
 * black text on the selected segment and grey on the others, and a count badge that rides
 * INSIDE the segment rather than floating over it. Everything below is that, and nothing
 * below is an improvement on it — a desktop client that redesigned the phone's chrome would
 * be a different product wearing the same name.
 *
 * ============================================================ THE SELECTION IS NOT A COLOUR
 *
 * The selected segment is distinguished by an elevated white surface AND by weight, never by
 * hue alone. `Ink.soft` on the track measures 5.74:1 and `Ink.strong` on white measures
 * 21:1, so the two states are separable without colour vision — which matters here more than
 * usual, because this control is the only navigation in the window and a user who cannot see
 * which half is active cannot tell an empty Groups list from an empty Messages one.
 *
 * ============================================================ COVERED BY NOTHING
 *
 * Like every composable in this package, this file is drawn by no test — a test that needs
 * a display cannot run on the headless runners this project's Windows evidence comes from.
 * What CAN be wrong is which segments exist and what they contain, and that is decided in
 * `com.oshi.desktop.ui.state` and in [ConversationFilter], both of which are plain Kotlin
 * with JUnit behind them.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    badge: (T) -> Int = { 0 },
    compact: Boolean = false,
) {
    Row(
        modifier
            .clip(OshiTheme.pill)
            .background(OshiTheme.surface)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (option in options) {
            Segment(
                text = label(option),
                selected = option == selected,
                badgeCount = badge(option),
                compact = compact,
                modifier = if (compact) Modifier else Modifier.weight(1f),
                onClick = { if (option != selected) onSelect(option) },
            )
        }
    }
}

@Composable
private fun Segment(
    text: String,
    selected: Boolean,
    badgeCount: Int,
    compact: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    // A crossfade rather than a jump: the phone's control animates, and an instant swap on a
    // 120Hz desktop display reads as a glitch rather than as a selection.
    val lift by animateFloatAsState(if (selected) 1f else 0f, OshiTheme.fade())
    Box(
        modifier
            .height(if (compact) 28.dp else 32.dp)
            .clip(OshiTheme.pill)
            .background(if (selected) OshiTheme.surfaceElevated.copy(alpha = lift) else Color.Transparent)
            .focusRing(OshiTheme.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) OshiTheme.md else OshiTheme.lg),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                color = if (selected) Ink.strong else Ink.soft,
                fontSize = if (compact) 13.sp else 13.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
            if (badgeCount > 0) {
                Box(Modifier.width(OshiTheme.xs))
                UnreadBadge(badgeCount)
            }
        }
    }
}

/**
 * The five destinations, centred across the top — the shipped window's own top strip.
 *
 * It is a SEGMENTED CONTROL and not a tab bar, and the difference is not pedantry. A tab bar
 * anchors meaning to icon positions, which is what VIEWS.md §7 argued against porting: three
 * of the phone's five tabs do not mean the same thing here, and a user who learned "the
 * third glyph is New" would be learning something this window cannot honour. Words, in the
 * phone's order, carry the same navigation without the false affordance.
 */
@Composable
fun DestinationStrip(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        SegmentedControl(
            options = options.indices.toList(),
            selected = selectedIndex,
            label = { options[it] },
            onSelect = onSelect,
            compact = true,
        )
    }
}
