package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.mascot.OshiMascot
import com.oshi.desktop.store.AppLock
import com.oshi.desktop.ui.OshiTheme
import kotlinx.coroutines.delay

/**
 * __DESKTOP_APP_LOCK_2027__ The lock screen, drawn over the whole window while [AppLock.locked].
 * The phones' words (`lock_screen.*`, `lock.*`); the way back from a forgotten passcode is the
 * recovery key, always offered — see [AppLock].
 *
 * [recoveryOpen] / [initialError] are hoisted for the screenshot renderer only.
 */
@Composable
fun AppLockScreen(
    recoveryKeyMatches: (String) -> Boolean,
    recoveryOpen: Boolean = false,
    initialError: String? = null,
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(initialError) }
    var showRecovery by remember { mutableStateOf(recoveryOpen) }
    var recovery by remember { mutableStateOf("") }
    var recoveryError by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focus = remember { FocusRequester() }
    val waitUntil = AppLock.config.waitUntilMs
    val waiting = waitUntil > now
    LaunchedEffect(waitUntil) {
        while (System.currentTimeMillis() < waitUntil) { delay(1_000); now = System.currentTimeMillis() }
        now = System.currentTimeMillis()
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun submit() {
        if (code.isEmpty() || waiting) return
        when (val r = AppLock.unlock(code)) {
            AppLock.Attempt.Unlocked -> { code = ""; error = null }
            is AppLock.Attempt.Wrong -> {
                code = ""
                error = if (r.waitUntilMs > 0) null
                else if (r.remaining in 1..3) t("lock.incorrect_passcode") + " · " + t("lock.passcode_attempts", r.remaining)
                else t("lock.incorrect_passcode")
                now = System.currentTimeMillis()
            }
            is AppLock.Attempt.Waiting -> { code = ""; now = System.currentTimeMillis() }
        }
    }

    Box(
        Modifier.fillMaxSize().background(OshiTheme.paneGround)
            // Swallow every click: nothing behind the lock may be reached.
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(32.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .glass(OshiTheme.radiusXl, OshiTheme.surfaceElevated, OshiTheme.cardOutline)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OshiMascot.named("sleep")?.let {
                MascotImage(it, 96.dp, playing = rememberMascotPlaying(it, hovered = false, playOnAppear = true))
                Spacer(Modifier.height(OshiTheme.md))
            }
            Text(t("lock_screen.title"), style = OshiTheme.typography.headlineMedium, color = Ink.strong, textAlign = TextAlign.Center)
            Spacer(Modifier.height(OshiTheme.xs))
            Text(t("pc.lock.screen.subtitle"), style = OshiTheme.typography.bodyMedium, color = Ink.soft, textAlign = TextAlign.Center)
            Spacer(Modifier.height(OshiTheme.lg))
            SecretField(
                value = code,
                onValue = { code = it.take(AppLock.MAX_LENGTH); error = null },
                placeholder = t("lock.enter_passcode"),
                enabled = !waiting,
                modifier = Modifier.focusRequester(focus),
                onEnter = ::submit,
            )
            Spacer(Modifier.height(OshiTheme.md))
            SettingsButton(SettingsIcon.KEY, t("lock_screen.unlock"), primary = true, enabled = code.isNotEmpty() && !waiting) { submit() }
            if (waiting) {
                Spacer(Modifier.height(OshiTheme.sm))
                Text(t("lock.too_many_attempts"), style = OshiTheme.typography.bodySmall, color = OshiTheme.danger, textAlign = TextAlign.Center)
                Text(t("pc.lock.wait", waitLabel(waitUntil - now)), style = OshiTheme.typography.bodySmall, color = Ink.soft, textAlign = TextAlign.Center)
            } else error?.let {
                Spacer(Modifier.height(OshiTheme.sm))
                Text(it, style = OshiTheme.typography.bodySmall, color = OshiTheme.danger, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(OshiTheme.lg))
            Hairline()
            Spacer(Modifier.height(OshiTheme.md))
            if (!showRecovery) {
                Text(
                    t("pc.lock.forgot"),
                    style = OshiTheme.typography.bodyMedium,
                    color = OshiTheme.brand,
                    modifier = Modifier.clip(OshiTheme.radiusMd).clickable { showRecovery = true }.padding(OshiTheme.xs),
                )
            } else {
                Text(t("pc.lock.recovery.hint"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                Spacer(Modifier.height(OshiTheme.sm))
                SecretField(
                    value = recovery,
                    onValue = { recovery = it.take(256); recoveryError = false },
                    placeholder = t("pc.lock.recovery.placeholder"),
                    mono = true,
                    onEnter = { recoveryError = !AppLock.unlockWithRecoveryKey(recovery, recoveryKeyMatches) },
                )
                if (recoveryError) {
                    Spacer(Modifier.height(OshiTheme.xs))
                    Text(t("pc.lock.recovery.wrong"), style = OshiTheme.typography.bodySmall, color = OshiTheme.danger)
                }
                Spacer(Modifier.height(OshiTheme.sm))
                SettingsButton(SettingsIcon.IMPORT, t("pc.lock.recovery.unlock"), enabled = recovery.isNotBlank()) {
                    recoveryError = !AppLock.unlockWithRecoveryKey(recovery, recoveryKeyMatches)
                    if (!recoveryError) recovery = ""
                }
            }
        }
    }
}

/** "45 s", "2 min" — the wait, rounded up. */
internal fun waitLabel(ms: Long): String {
    val s = ((ms + 999) / 1000).coerceAtLeast(1)
    return if (s < 60) t("pc.lock.seconds", s.toInt()) else t("pc.lock.auto.minutes", ((s + 59) / 60).toInt())
}

/** A masked single-line field; Enter submits. */
@Composable
internal fun SecretField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    mono: Boolean = false,
    onEnter: () -> Unit = {},
) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(OshiTheme.radiusMd)
            .background(OshiTheme.surface)
            .glassField()
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = OshiTheme.typography.bodyMedium, color = Ink.soft, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            enabled = enabled,
            visualTransformation = PasswordVisualTransformation(),
            textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong, fontFamily = if (mono) FontFamily.Monospace else null),
            cursorBrush = SolidColor(OshiTheme.brand),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = placeholder }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { onEnter(); true } else false
                },
        )
    }
}

/**
 * __DESKTOP_APP_LOCK_2027__ Settings › Security: set / change / remove the passcode, when the
 * window locks itself, "Lock now", and what the lock is and is not.
 */
@Composable
fun SecuritySettingsContent(initialMode: PasscodeMode? = null) {
    // __STRICT_MODE_2026_11_22__ the explanation page replaces the cards while open.
    if (StrictModeNavigation.explaining) { StrictModeExplanation(); return }
    StrictModeCard()
    val cfg = AppLock.config
    var mode by remember { mutableStateOf(initialMode) }
    var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    SettingsCard(title = t("settings.security.section.authentication"), footer = t("pc.lock.footer")) {
        SettingsRowStatic(
            SettingsIcon.KEY,
            if (cfg.hasPasscode) t("pc.lock.on") else t("lock.passcode_disabled"),
            if (cfg.hasPasscode) null else t("pc.lock.not_set"),
        )
        Hairline()
        SettingsCardBody {
            if (mode == null) {
                SettingsButtonRow {
                    if (!cfg.hasPasscode) {
                        SettingsButton(SettingsIcon.KEY, t("lock.set_passcode"), primary = true) { mode = PasscodeMode.SET; notice = null }
                    } else {
                        SettingsButton(SettingsIcon.KEY, t("lock.change_passcode")) { mode = PasscodeMode.CHANGE; notice = null }
                        SettingsButton(SettingsIcon.PRIVACY, t("pc.lock.now")) { AppLock.lockNow() }
                        SettingsButton(SettingsIcon.DELETE, t("lock.remove_passcode"), danger = true) { mode = PasscodeMode.REMOVE; notice = null }
                    }
                }
            } else {
                PasscodeForm(mode!!, onDone = { msg -> mode = null; notice = msg?.let { it to false } }, onCancel = { mode = null })
            }
            notice?.let { (text, err) -> SettingsNotice(text, err) }
        }
    }

    SettingsCard(title = t("pc.lock.auto.title"), footer = t("pc.lock.limit")) {
        SettingsCardBody {
            // A wrapping row of chips sized to their words: "Seulement au lancement" must not be cut.
            SettingsButtonRow {
                for (m in AppLock.AUTO_LOCK_CHOICES) {
                    val label = when (m) {
                        0 -> t("pc.lock.auto.launch")
                        60 -> t("pc.lock.auto.hour")
                        else -> t("pc.lock.auto.minutes", m)
                    }
                    val selected = cfg.autoLockMinutes == m
                    Box(
                        Modifier
                            .widthIn(min = 88.dp)
                            .clip(OshiTheme.pill)
                            .background(if (selected) OshiTheme.brand.copy(alpha = 0.16f) else OshiTheme.surface)
                            .clickable(enabled = cfg.hasPasscode) { AppLock.setAutoLockMinutes(m) }
                            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = OshiTheme.typography.labelLarge,
                            color = if (!cfg.hasPasscode) Ink.soft else if (selected) OshiTheme.brand else Ink.strong,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    // __SCREEN_CAPTURE_PROTECTION_2026_11_20__ (self-contained: ScreenCaptureSettings.kt)
    ScreenCaptureSettingsCard()
}

enum class PasscodeMode { SET, CHANGE, REMOVE }

@Composable
private fun PasscodeForm(mode: PasscodeMode, onDone: (String?) -> Unit, onCancel: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun apply() {
        error = null
        when (mode) {
            PasscodeMode.REMOVE ->
                if (AppLock.removePasscode(current)) onDone(t("pc.lock.removed")) else error = t("lock.incorrect_passcode")
            PasscodeMode.SET, PasscodeMode.CHANGE -> when {
                !AppLock.isValidNew(first) -> error = t("pc.lock.too_short")
                first != second -> error = t("pc.lock.mismatch")
                mode == PasscodeMode.CHANGE && !AppLock.matches(AppLock.config, current) -> error = t("lock.incorrect_passcode")
                AppLock.setPasscode(first, current.takeIf { mode == PasscodeMode.CHANGE }) -> onDone(t("pc.lock.set_done"))
                else -> error = t("lock.incorrect_passcode")
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
        if (mode != PasscodeMode.SET) SecretField(current, { current = it; error = null }, t("pc.lock.current"), onEnter = ::apply)
        if (mode != PasscodeMode.REMOVE) {
            SecretField(first, { first = it.take(AppLock.MAX_LENGTH); error = null }, t("pc.lock.new"), onEnter = ::apply)
            SecretField(second, { second = it.take(AppLock.MAX_LENGTH); error = null }, t("pc.lock.confirm"), onEnter = ::apply)
            Text(t("pc.lock.too_short"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
        }
        error?.let { SettingsNotice(it, true) }
        SettingsButtonRow {
            SettingsButton(
                if (mode == PasscodeMode.REMOVE) SettingsIcon.DELETE else SettingsIcon.CHECK,
                when (mode) {
                    PasscodeMode.SET -> t("lock.set_passcode")
                    PasscodeMode.CHANGE -> t("lock.change_passcode")
                    PasscodeMode.REMOVE -> t("lock.remove_passcode")
                },
                primary = mode != PasscodeMode.REMOVE,
                danger = mode == PasscodeMode.REMOVE,
            ) { apply() }
            SettingsButton(null, t("common.cancel"), onClick = onCancel)
        }
    }
}

/** Icon · title/detail, no action. */
@Composable
private fun SettingsRowStatic(icon: SettingsIcon, title: String, detail: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md), verticalAlignment = Alignment.CenterVertically) {
        SettingsIconBadge(icon, OshiTheme.brand)
        Spacer(Modifier.width(OshiTheme.md))
        Column {
            Text(title, style = OshiTheme.typography.bodyLarge, color = Ink.strong)
            detail?.let {
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(it, style = OshiTheme.typography.bodySmall, color = Ink.soft)
            }
        }
    }
}
