package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.AiConsoleModel
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The AI destination — PARITY.md row 2.5, redrawn as the phones' AI tab: a chat with the
 * local model, suggestion chips, and the model chosen with a file picker instead of a
 * typed path.
 *
 * The honesty of the old screen is kept, just shorter: when the llama.cpp library or the
 * model file is missing, the setup card says so before anything else, and the composer
 * appears only when a question could actually be answered. Nothing leaves the machine —
 * there is no HTTP client anywhere in this path.
 */
@Composable
fun AiPane(
    state: AiConsoleModel.AiState,
    onUseModel: (String) -> Unit,
    onAsk: (String) -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
    /** Injectable so a render does not open a native dialog. */
    pickModel: () -> String? = ::pickGgufFile,
) = ProvideLocaleDirection {
    var question by remember { mutableStateOf("") }
    var setupOpen by remember { mutableStateOf(false) }
    val ready = state.modelPath != null && state.nativeProblem == null
    Column(modifier.fillMaxSize().background(OshiTheme.paneGround)) {
        ToolHeader(t("ai_assistant"), t("ai_on_device_footer"), ToolGlyph.SPARKLE) {
            state.modelPath?.let { path ->
                ToolPill(File(path).name, modifier = Modifier.widthIn(max = 260.dp))
            }
            if (state.turns.isNotEmpty()) ToolIconButton(ToolGlyph.COMPOSE, t("ai_new_chat")) { onForget() }
            ToolIconButton(ToolGlyph.SETTINGS, t("pc.ai.model.title"), active = setupOpen) { setupOpen = !setupOpen }
        }
        Hairline()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (!ready || setupOpen) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OshiTheme.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.lg)) {
                        if (!ready) ToolEmptyState(t("ai_offline_assistant"), t("pc.ai.setup.body"), mascot = "thinking")
                        state.notice?.let { ToolNotice(it.text, if (it.severity == com.oshi.desktop.ui.state.Severity.ERROR) ToolTone.ERROR else ToolTone.SUCCESS) }
                        ModelCard(state, onUseModel, pickModel)
                        ToolCard {
                            for ((g, k) in listOf(ToolGlyph.SHIELD to t("ai_feature_privacy"), ToolGlyph.WIFI to t("ai_feature_offline"), ToolGlyph.TEXT to t("ai_feature_text"), ToolGlyph.EYE_OFF to t("ai_feature_no_image"))) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                                    ToolIcon(g, OshiTheme.brand, 18.dp)
                                    Text(k, style = ToolType.body, color = Ink.strong)
                                }
                            }
                        }
                    }
                }
            } else {
                Conversation(state, onAsk = onAsk, modifier = Modifier.fillMaxSize())
            }
        }
        if (ready && !setupOpen) {
            Hairline()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = OshiTheme.xl, vertical = OshiTheme.md),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
            ) {
                ToolField(
                    question, { question = it }, t("ai_ask_anything"),
                    Modifier.weight(1f).onEnter { if (state.canAsk && question.isNotBlank()) { onAsk(question); question = "" } },
                    enabled = !state.busy,
                    container = OshiTheme.surfaceElevated,
                )
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(if (state.canAsk && question.isNotBlank()) OshiTheme.brandGradient else androidx.compose.ui.graphics.SolidColor(OshiTheme.separator))
                        .clickable(enabled = state.canAsk && question.isNotBlank(), role = Role.Button) { onAsk(question); question = "" }
                        .semantics { contentDescription = t("ai.a11y_send_message") },
                    contentAlignment = Alignment.Center,
                ) { ToolIcon(ToolGlyph.SEND, Color.White, 18.dp) }
            }
        }
    }
}

@Composable
private fun ModelCard(state: AiConsoleModel.AiState, onUseModel: (String) -> Unit, pickModel: () -> String?) {
    var path by remember { mutableStateOf("") }
    ToolCard(title = t("pc.ai.model.title"), icon = ToolGlyph.CHIP) {
        if (state.modelPath != null) ToolValue(t("pc.ai.model.inUse"), state.modelPath, mono = true)
        else ToolHint(t("pc.ai.model.none"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            ToolButton(t("pc.ai.model.choose"), icon = ToolGlyph.FOLDER, style = ToolButtonStyle.PRIMARY) { pickModel()?.let(onUseModel) }
        }
        ToolField(path, { path = it }, "/path/to/model.gguf", Modifier.fillMaxWidth(), mono = true) {
            if (path.isNotBlank()) ToolIconButton(ToolGlyph.CHECK, t("pc.ai.model.use"), size = 28.dp) { onUseModel(path); path = "" }
        }
        state.nativeProblem?.let { ToolNotice(t("pc.ai.nativeMissing", it), ToolTone.WARNING) }
    }
}

@Composable
private fun Conversation(state: AiConsoleModel.AiState, onAsk: (String) -> Unit, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(state.turns.size, state.busy) { if (state.turns.isNotEmpty()) list.animateScrollToItem(state.turns.size + (if (state.busy) 1 else 0)) }
    if (state.turns.isEmpty() && !state.busy) {
        Column(modifier.padding(OshiTheme.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            ToolEmptyState(t("ai_offline_assistant"), t("ai_powered_local"), mascot = "wave")
            Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                for (s in listOf(t("ai_suggest_email"), t("ai_suggest_poem"), t("ai_suggest_quantum"), t("ai_suggest_capitals"))) {
                    ToolRow(s, icon = ToolGlyph.SPARKLE, onClick = { onAsk(s) }, modifier = Modifier.clip(OshiTheme.radiusMd).background(OshiTheme.surfaceElevated))
                }
            }
        }
        return
    }
    LazyColumn(modifier, state = list, contentPadding = PaddingValues(horizontal = OshiTheme.xl, vertical = OshiTheme.lg), verticalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
        items(state.turns) { turn ->
            Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                Bubble(turn.question, mine = true)
                if (turn.ok) Bubble(turn.answer, mine = false, footnote = turn.detail)
                else Bubble(turn.detail.orEmpty(), mine = false, error = true)
            }
        }
        if (state.busy) item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                AssistantAvatar()
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = OshiTheme.brand)
                Text(t("ai.a11y_thinking"), style = ToolType.caption, color = Ink.soft)
            }
        }
    }
}

@Composable
private fun AssistantAvatar() {
    Box(Modifier.size(30.dp).clip(CircleShape).background(OshiTheme.brandGradient), contentAlignment = Alignment.Center) {
        ToolIcon(ToolGlyph.SPARKLE, Color.White, 16.dp)
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, footnote: String? = null, error: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!mine) { AssistantAvatar(); Spacer(Modifier.width(OshiTheme.sm)) }
        Column(
            Modifier.widthIn(max = 620.dp)
                .clip(RoundedCornerShape(18.dp))
                .then(
                    when {
                        mine -> Modifier.background(OshiTheme.brandGradient)
                        error -> Modifier.background(OshiTheme.danger.copy(alpha = 0.12f))
                        else -> Modifier.background(OshiTheme.surfaceElevated)
                    },
                )
                .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
            verticalArrangement = Arrangement.spacedBy(OshiTheme.xs),
        ) {
            if (error) Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                ToolIcon(ToolGlyph.ALERT, OshiTheme.danger, 16.dp)
                Text(text, style = ToolType.body, color = Ink.strong)
            } else SelectionContainer {
                Text(text, style = ToolType.natural.copy(fontSize = 14.sp), color = if (mine) Ink.onBrandStrong else Ink.strong)
            }
            if (footnote != null) Text(footnote, style = ToolType.caption, color = if (mine) Ink.onBrandSoft else Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun pickGgufFile(): String? {
    val dialog = com.oshi.desktop.ui.DialogOwner.fileDialog(t("pc.ai.model.choose"), FileDialog.LOAD)
    return try {
        dialog.setFilenameFilter { _, name -> name.endsWith(".gguf", ignoreCase = true) }
        // __FILEDIALOG_FILTER_IS_A_NOOP_ON_WINDOWS_2026_11_19__ setFilenameFilter does nothing in
        // the Windows native dialog; a "*.gguf" file name is what that dialog filters on.
        if (com.oshi.desktop.store.DesktopPaths.isWindows) dialog.file = "*.gguf"
        dialog.isVisible = true
        dialog.file?.takeIf { it.endsWith(".gguf", ignoreCase = true) }?.let { File(dialog.directory, it).absolutePath }
    } finally { dialog.dispose() }
}
