package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.oshi.desktop.i18n.t
import com.oshi.desktop.pairing.QrImageDecoder
import com.oshi.desktop.store.IdentityImportException
import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.KeyVault
import java.awt.FileDialog
import java.io.File
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight

/**
 * A deliberately small, separate restore window.  It runs before [OshiClient] exists:
 * constructing a client creates an identity on first run, which would make the one safe
 * import case (an empty profile) unreachable.  The recovery secret stays in process
 * memory only, is masked by default, and is never sent to the relay.
 */
fun restoreIdentityWindow(vault: KeyVault): Boolean {
    var restored = false
    // __FIRST_RUN_EXITED_THE_PROCESS_2026_10_29__ `exitProcessOnExit = false`, and it is
    // the difference between this window handing an answer back and this window being the
    // last thing the app ever does.
    //
    // Compose's `application { }` defaults that flag to TRUE, so `exitApplication()` ends
    // the composition and then calls `exitProcess(0)`. Both of these windows exist to
    // RETURN something — a choice, or whether a restore happened — and `UiLauncher` reads
    // it on the next line to build the client and open the real window. With the default,
    // that next line was never reached: clicking "Create New Identity" on a fresh profile
    // made the whole app vanish, exit status 0, nothing on stdout or stderr. A new user on
    // macOS could not get past the first screen, and the silence is why it reads as a
    // crash rather than a bug.
    //
    // Found by driving the ASSEMBLED app with a real mouse on a throwaway `OSHI_HOME`. No
    // test and no fake-host harness could have caught it: the defect is in what the process
    // does after the composition ends, which only exists when the real launcher is running.
    application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = { exitApplication() },
            // These are shared iOS keys, not desktop-only copy. Keeping the recovery path in
            // the common catalog means the first Windows/Linux screen a restored user sees is
            // available in exactly the same 34 languages as iOS and macOS.
            title = t("identity.import_existing"),
            icon = oshiWindowIcon(),
            state = rememberWindowState(size = ScreenFit.fitToScreen(androidx.compose.ui.unit.DpSize(960.dp, 640.dp))),
        ) {
            androidx.compose.runtime.DisposableEffect(window) {
                DialogOwner.current = window
                onDispose { if (DialogOwner.current === window) DialogOwner.current = null }
            }
            var recoveryKey by remember { mutableStateOf("") }
            var error by remember { mutableStateOf<String?>(null) }
            // __DESKTOP_SETTINGS_2027__ the same screen as the first run, opened on "import".
            OshiMaterialTheme {
                FirstRunScreen(
                    importing = true,
                    recoveryKey = recoveryKey,
                    error = error,
                    onCreate = {},
                    onStartImport = {},
                    onKeyChange = { recoveryKey = it; error = null },
                    onScanQr = {
                        pickQrImage()?.let { image ->
                            QrImageDecoder.decode(image).fold(
                                onSuccess = { recoveryKey = it; error = null },
                                onFailure = { error = t("identity.vault.import_error") },
                            )
                        }
                    },
                    onRestore = {
                        try {
                            IdentityStore.importRecoveryKey(vault, recoveryKey)
                            recoveryKey = ""
                            restored = true
                            exitApplication()
                        } catch (e: IdentityImportException) {
                            error = t("identity.vault.import_error")
                        } catch (_: Exception) {
                            recoveryKey = ""
                            error = t("identity.vault.import_error")
                        }
                    },
                    onCancel = { recoveryKey = ""; exitApplication() },
                )
            }
        }
    }
    return restored
}

/** The only first-run choices: mint a new account, restore one, or leave unchanged. */
enum class FirstRunIdentityChoice { CREATE_NEW, RESTORED, CANCELLED }

/**
 * First-launch identity decision. This exists before [OshiClient] is constructed, because
 * constructing it mints an identity and would make importing a phone identity impossible.
 *
 * The vault must be empty — [UiLauncher] enforces that precondition. The recovery secret is
 * masked, used once by [IdentityStore.importRecoveryKey], then cleared before this window exits.
 */
fun firstRunIdentityWindow(vault: KeyVault): FirstRunIdentityChoice {
    var choice = FirstRunIdentityChoice.CANCELLED
    // __FIRST_RUN_EXITED_THE_PROCESS_2026_10_29__ `exitProcessOnExit = false`, and it is
    // the difference between this window handing an answer back and this window being the
    // last thing the app ever does.
    //
    // Compose's `application { }` defaults that flag to TRUE, so `exitApplication()` ends
    // the composition and then calls `exitProcess(0)`. Both of these windows exist to
    // RETURN something — a choice, or whether a restore happened — and `UiLauncher` reads
    // it on the next line to build the client and open the real window. With the default,
    // that next line was never reached: clicking "Create New Identity" on a fresh profile
    // made the whole app vanish, exit status 0, nothing on stdout or stderr. A new user on
    // macOS could not get past the first screen, and the silence is why it reads as a
    // crash rather than a bug.
    //
    // Found by driving the ASSEMBLED app with a real mouse on a throwaway `OSHI_HOME`. No
    // test and no fake-host harness could have caught it: the defect is in what the process
    // does after the composition ends, which only exists when the real launcher is running.
    application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = { exitApplication() },
            title = t("identity.app_name"),
            // __FIRST_RUN_WINDOW_ICON_2026_11_20__ the first window a PC user ever sees showed
            // the JVM's coffee cup in the title bar and taskbar (PC smoke run, windows-latest).
            icon = oshiWindowIcon(),
            state = rememberWindowState(size = ScreenFit.fitToScreen(androidx.compose.ui.unit.DpSize(960.dp, 640.dp))),
        ) {
            androidx.compose.runtime.DisposableEffect(window) {
                DialogOwner.current = window
                onDispose { if (DialogOwner.current === window) DialogOwner.current = null }
            }
            var importing by remember { mutableStateOf(false) }
            var recoveryKey by remember { mutableStateOf("") }
            var error by remember { mutableStateOf<String?>(null) }
            // __DESKTOP_SETTINGS_2027__ FirstRunScreen: brand + promises left, the choice right.
            OshiMaterialTheme {
                FirstRunScreen(
                    importing = importing,
                    recoveryKey = recoveryKey,
                    error = error,
                    onCreate = { choice = FirstRunIdentityChoice.CREATE_NEW; exitApplication() },
                    onStartImport = { importing = true },
                    onKeyChange = { recoveryKey = it; error = null },
                    onScanQr = {
                        pickQrImage()?.let { image ->
                            QrImageDecoder.decode(image).fold(
                                onSuccess = { recoveryKey = it; error = null },
                                onFailure = { error = t("identity.vault.import_error") },
                            )
                        }
                    },
                    onRestore = {
                        try {
                            IdentityStore.importRecoveryKey(vault, recoveryKey)
                            recoveryKey = ""
                            choice = FirstRunIdentityChoice.RESTORED
                            exitApplication()
                        } catch (_: Exception) {
                            recoveryKey = ""
                            error = t("identity.vault.import_error")
                        }
                    },
                    onCancel = { recoveryKey = ""; error = null; importing = false },
                )
            }
        }
    }
    return choice
}

/** A saved screenshot/photo works on all packaged desktops; no webcam dependency is assumed. */
private fun pickQrImage(): File? {
    val dialog = com.oshi.desktop.ui.DialogOwner.fileDialog(t("identity.vault.scan_title"), FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
