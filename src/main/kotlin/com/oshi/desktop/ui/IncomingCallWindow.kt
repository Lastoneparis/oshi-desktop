package com.oshi.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.components.CallGlyph
import com.oshi.desktop.ui.components.CallGlyphIcon
import com.oshi.desktop.ui.state.CallScreenModel.CallScreen

/**
 * __DESKTOP_BACKGROUND_2026_09_23__ The ringing card shown when a call arrives while the main
 * window is closed to the tray — the desktop's stand-in for CallKit's banner. Small, undecorated,
 * always on top, top-right of the screen, and focus-stealing on purpose: a call that only rang
 * in a hidden window would be a call nobody could answer.
 *
 * It shows the caller's label because the person is at the machine and the call is ringing NOW;
 * the OS notification that accompanies it stays generic (see [DesktopNotifier]) because that
 * one is kept in Notification Center's history.
 */
@Composable
fun IncomingCallWindow(screen: CallScreen, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val state = rememberWindowState(
        size = DpSize(360.dp, 92.dp),
        position = WindowPosition(Alignment.TopEnd),
    )
    Window(
        onCloseRequest = onDecline,
        state = state,
        title = if (screen.video) t("call.incoming.video") else t("call.incoming.voice"),
        undecorated = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = true,
    ) {
        LaunchedEffect(Unit) { window.toFront(); window.requestFocus() }
        Box(Modifier.fillMaxSize().background(Color(0xFF1C1B2E)).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF5616EC)),
                    contentAlignment = Alignment.Center,
                ) { Text(screen.initials, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(screen.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Text(if (screen.video) t("call.incoming.video") else t("call.incoming.voice"), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RingButton(t("call.decline"), Color(0xFFE5484D), CallGlyph.HANG_UP, onDecline)
                    RingButton(t("call.accept"), Color(0xFF30A46C), if (screen.video) CallGlyph.CAMERA else CallGlyph.PHONE, onAnswer)
                }
            }
        }
    }
}

@Composable
private fun RingButton(label: String, color: Color, glyph: CallGlyph, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(color)
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { CallGlyphIcon(glyph, Color.White, 24.dp) }
}
