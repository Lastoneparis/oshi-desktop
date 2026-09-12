package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.AiConsoleModel

/**
 * The AI destination — PARITY.md row 2.5.
 *
 * The screen is built around the row's own honesty: the plumbing is shared with the Android
 * app (`service/LlamaCpp.kt`, compiled out of that tree so both load a shim built from one
 * header) and **no inference has ever run on this client**. So the first thing this draws is
 * not a chat box, it is the state of the two things that have to be true first — a native
 * library and a model file — and the composer appears only when they are.
 *
 * The one claim it makes on screen is the one that is structurally true: nothing leaves the
 * machine. There is no HTTP client anywhere in this path. A local model is the only kind
 * OSHI has ever had, on any platform.
 */
@Composable
fun AiPane(
    state: AiConsoleModel.AiState,
    onUseModel: (String) -> Unit,
    onAsk: (String) -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var modelPath by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }

    Column(
        modifier
            .fillMaxSize()
            .background(OshiTheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(OshiTheme.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp)) {

            PaneHeading("AI", "A model that runs here. Nothing is uploaded, to OSHI or to anyone else.")

            // SAID BEFORE ANYTHING ELSE, because both prerequisites are missing for almost
            // everyone who will ever open this screen and neither is something they did
            // wrong. The installer ships no weights — a messenger download is not where
            // gigabytes of model belong — and it ships no llama.cpp native either, so a
            // plain install can generate nothing whatever is typed here. A screen that let
            // someone configure a model and then failed on the first question would have
            // wasted their time to tell them something it knew at the start.
            if (state.modelPath == null) {
                NoticeCard(
                    "This needs two things OSHI does not install: a llama.cpp native library on " +
                        "java.library.path, and a .gguf model file. Without both, nothing on this " +
                        "screen can answer — and everything else in the app works regardless.",
                    error = false,
                )
                Spacer(Modifier.height(OshiTheme.lg))
            }

            state.notice?.let {
                NoticeCard(it.text, it.severity == com.oshi.desktop.ui.state.Severity.ERROR)
                Spacer(Modifier.height(OshiTheme.lg))
            }

            // ---------------------------------------------------------------- prerequisites
            PaneCard {
                Text("Model", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xs))
                if (state.modelPath == null) {
                    Text(
                        "No model is configured. OSHI ships no weights — a messenger installer is " +
                            "not where gigabytes of model belong — so point this at a .gguf file you " +
                            "already have.",
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                } else {
                    LabelledValue("In use", state.modelPath, mono = true)
                }
                Spacer(Modifier.height(OshiTheme.md))
                PasteField(modelPath, { modelPath = it }, "/path/to/model.gguf")
                Spacer(Modifier.height(OshiTheme.md))
                PrimaryButton("Use this model", enabled = modelPath.isNotBlank()) {
                    onUseModel(modelPath)
                    modelPath = ""
                }

                if (state.nativeProblem != null) {
                    Spacer(Modifier.height(OshiTheme.lg))
                    NoticeCard(
                        "The llama.cpp native library did not load: ${state.nativeProblem}. " +
                            "Until it does, no answer can be generated here whatever model is set.",
                        error = true,
                    )
                }
            }

            Spacer(Modifier.height(OshiTheme.lg))

            // ---------------------------------------------------------------- the exchange
            PaneCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ask", style = OshiTheme.typography.titleMedium, color = Ink.strong, modifier = Modifier.weight(1f))
                    if (state.turns.isNotEmpty()) TextAction("Clear", onForget)
                }
                Spacer(Modifier.height(OshiTheme.xs))
                Text(
                    if (state.canAsk) "Runs on this CPU. The window will not respond while it generates."
                    else "Configure a model first — there is nothing to ask yet.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
                Spacer(Modifier.height(OshiTheme.md))

                PasteField(question, { question = it }, "Ask anything…")
                Spacer(Modifier.height(OshiTheme.md))
                PrimaryButton(
                    if (state.busy) "Generating…" else "Ask",
                    enabled = state.canAsk && question.isNotBlank(),
                ) {
                    onAsk(question)
                    question = ""
                }

                if (state.turns.isNotEmpty()) {
                    Spacer(Modifier.height(OshiTheme.lg))
                    for (t in state.turns.asReversed()) {
                        TurnCard(t)
                        Spacer(Modifier.height(OshiTheme.md))
                    }
                }
            }

            Spacer(Modifier.height(OshiTheme.xl))
        }
    }
}

@Composable
private fun TurnCard(turn: AiConsoleModel.Turn) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusMd)
            .background(OshiTheme.surface)
            .padding(OshiTheme.md),
    ) {
        Text(turn.question, style = OshiTheme.typography.labelLarge, color = Ink.soft)
        Spacer(Modifier.height(OshiTheme.sm))
        if (turn.ok) {
            Text(turn.answer, style = OshiTheme.typography.bodyMedium, color = Ink.strong)
        } else {
            // A refusal is rendered as a refusal. An empty bubble where an answer should be
            // is how a user concludes the model is "thinking" forever.
            Row(verticalAlignment = Alignment.Top) {
                GlyphIcon(Glyph.ALERT, OshiTheme.danger, 16.dp)
                Spacer(Modifier.widthIn(min = OshiTheme.sm))
                Text(turn.detail.orEmpty(), style = OshiTheme.typography.bodySmall, color = Ink.strong)
            }
        }
        if (turn.ok && turn.detail != null) {
            Spacer(Modifier.height(OshiTheme.sm))
            Text(turn.detail, style = OshiTheme.typography.labelSmall, color = Ink.soft)
        }
    }
}

/** A one-line banner inside a scrolling pane — the same shape the thread's notice bar uses. */
@Composable
fun NoticeCard(text: String, error: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusMd)
            .background((if (error) OshiTheme.danger else OshiTheme.success).copy(alpha = 0.10f))
            .padding(OshiTheme.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        GlyphIcon(
            if (error) Glyph.ALERT else Glyph.CHECK,
            if (error) OshiTheme.danger else OshiTheme.success,
            16.dp,
        )
        Text(text, style = OshiTheme.typography.bodySmall, color = Ink.strong)
    }
}

/** A search field with a magnifier — shared by Places and anywhere else that searches. */
@Composable
fun SearchBox(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(OshiTheme.pill)
            .background(OshiTheme.surface)
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.SEARCH, Ink.soft, 16.dp)
        Spacer(Modifier.widthIn(min = OshiTheme.sm))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = OshiTheme.typography.bodyMedium, color = Ink.soft)
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(OshiTheme.brand),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
