package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.oshi.desktop.i18n.t
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.store.KeyVaultException
import java.io.File

/**
 * __NO_KEYRING_PASSPHRASE_WINDOW_2026_11_21__ The window that replaces "set OSHI_PASSPHRASE".
 *
 * When no OS key store accepts a secret — on Linux: `secret-tool` not installed (stock Ubuntu
 * 24.04 desktop does NOT ship `libsecret-tools`), no Secret Service on the session bus (a bare
 * window manager, some KDE setups), or a keyring that refuses writes — the vault is protected by
 * a passphrase instead. The vault code has always supported that, but the WINDOW only read it from
 * the `OSHI_PASSPHRASE` environment variable, so a person who started OSHI from the applications
 * menu got a failure screen telling them to set an environment variable. That was every new user
 * on a stock Ubuntu desktop.
 *
 * Nothing about the vault changes: the passphrase goes to [KeyVault.open] exactly as the
 * environment variable did, a wrong one is refused by the AEAD tag (never read as an empty vault),
 * and the choice of protection is still made once per profile, recorded in the vault file.
 */
object PassphrasePrompt {
    const val MIN_LENGTH = 8

    enum class Problem { TOO_SHORT, MISMATCH }

    /** What is wrong with what was typed, or null when it may be used. */
    fun problem(creating: Boolean, first: String, second: String): Problem? = when {
        first.isEmpty() -> Problem.TOO_SHORT
        creating && first.length < MIN_LENGTH -> Problem.TOO_SHORT
        creating && first != second -> Problem.MISMATCH
        else -> null
    }

    fun message(p: Problem): String = when (p) {
        Problem.TOO_SHORT -> t("desktop.passphrase.too_short")
        Problem.MISMATCH -> t("desktop.passphrase.mismatch")
    }

    /** True when [e] means "this passphrase does not open this vault" (and not, say, an unreadable file). */
    fun isWrongPassphrase(e: Throwable): Boolean =
        e is KeyVaultException && e.cause is javax.crypto.AEADBadTagException

    /**
     * Asks until the passphrase opens [vaultFile] (or creates it, on a fresh profile) and returns
     * it, or null when the person chose to quit. Any failure other than a wrong passphrase is
     * rethrown for `UiLauncher`'s failure window. [open] and [ask] are seams for the tests.
     */
    fun obtain(
        vaultFile: File,
        open: (CharArray) -> Unit = { KeyVault.open(vaultFile, null, it) },
        ask: (creating: Boolean, error: String?) -> CharArray? = ::passphraseWindow,
    ): CharArray? {
        val creating = !vaultFile.isFile
        var error: String? = null
        while (true) {
            val pass = ask(creating, error) ?: return null
            try {
                open(pass)
                return pass
            } catch (e: Exception) {
                if (!isWrongPassphrase(e)) throw e
                error = t("desktop.passphrase.wrong")
            }
        }
    }
}

/** One passphrase window. Returns what was typed, or null when the person quit. */
fun passphraseWindow(creating: Boolean, initialError: String?): CharArray? {
    var result: CharArray? = null
    // exitProcessOnExit = false: this window hands an answer back (see IdentityRestoreWindow).
    application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = { exitApplication() },
            title = t("identity.app_name"),
            icon = oshiWindowIcon(),
            state = rememberWindowState(size = ScreenFit.fitToScreen(androidx.compose.ui.unit.DpSize(560.dp, 520.dp))),
        ) {
            androidx.compose.runtime.DisposableEffect(window) {
                DialogOwner.current = window
                onDispose { if (DialogOwner.current === window) DialogOwner.current = null }
            }
            var first by remember { mutableStateOf("") }
            var second by remember { mutableStateOf("") }
            var error by remember { mutableStateOf(initialError) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            fun submit() {
                val problem = PassphrasePrompt.problem(creating, first, second)
                if (problem != null) { error = PassphrasePrompt.message(problem); return }
                result = first.toCharArray()
                first = ""; second = ""
                exitApplication()
            }
            val enterSubmits = Modifier.onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { submit(); true } else false
            }
            OshiMaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(
                                "OSHI",
                                style = MaterialTheme.typography.titleMedium.copy(brush = OshiTheme.brandGradient, fontWeight = FontWeight.Bold),
                            )
                            Text(
                                if (creating) t("desktop.passphrase.title") else t("desktop.passphrase.unlock.title"),
                                fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (creating) t("desktop.passphrase.create.body") else t("desktop.passphrase.unlock.body"),
                                fontSize = 14.sp, color = OshiTheme.textSecondary,
                            )
                            OutlinedTextField(
                                value = first,
                                onValueChange = { first = it; error = null },
                                label = { Text(t("desktop.passphrase.field")) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus).then(enterSubmits),
                            )
                            if (creating) {
                                OutlinedTextField(
                                    value = second,
                                    onValueChange = { second = it; error = null },
                                    label = { Text(t("desktop.passphrase.confirm")) },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth().then(enterSubmits),
                                )
                            }
                            error?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row {
                            OutlinedButton(onClick = { first = ""; second = ""; exitApplication() }) { Text(t("desktop.background.quit")) }
                            Spacer(Modifier.width(10.dp))
                            Button(onClick = { submit() }) { Text(t("common.continue")) }
                        }
                    }
                }
            }
        }
    }
    return result
}
