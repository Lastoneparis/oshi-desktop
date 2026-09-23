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

/**
 * A deliberately small, separate restore window.  It runs before [OshiClient] exists:
 * constructing a client creates an identity on first run, which would make the one safe
 * import case (an empty profile) unreachable.  The recovery secret stays in process
 * memory only, is masked by default, and is never sent to the relay.
 */
fun restoreIdentityWindow(vault: KeyVault): Boolean {
    var restored = false
    application {
        Window(
            onCloseRequest = { exitApplication() },
            // These are shared iOS keys, not desktop-only copy. Keeping the recovery path in
            // the common catalog means the first Windows/Linux screen a restored user sees is
            // available in exactly the same 34 languages as iOS and macOS.
            title = t("identity.import_existing"),
            state = rememberWindowState(width = 520.dp, height = 410.dp),
        ) {
            var recoveryKey by remember { mutableStateOf("") }
            var error by remember { mutableStateOf<String?>(null) }
            MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                Surface {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(t("identity.import_existing"), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            t("identity.import_existing.hint"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = recoveryKey,
                            onValueChange = { recoveryKey = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(t("identity.vault.import_key")) },
                            // Do not surface parser or crypto details for an invalid secret:
                            // this is both clearer to a user and avoids treating recovery-key
                            // material as diagnostic data in a future logging integration.
                            supportingText = error?.let { message -> { Text(message) } },
                            isError = error != null,
                            visualTransformation = PasswordVisualTransformation(),
                            minLines = 3,
                        )
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                pickQrImage()?.let { image ->
                                    QrImageDecoder.decode(image).fold(
                                        onSuccess = { recoveryKey = it; error = null },
                                        onFailure = { error = t("identity.vault.import_error") },
                                    )
                                }
                            },
                        ) { Text(t("identity.vault.scan_qr")) }
                        Spacer(Modifier.height(4.dp))
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = recoveryKey.isNotBlank(),
                            onClick = {
                                try {
                                    IdentityStore.importRecoveryKey(vault, recoveryKey)
                                    recoveryKey = ""
                                    restored = true
                                    exitApplication()
                                } catch (e: IdentityImportException) {
                                    error = t("identity.vault.import_error")
                                } catch (_: Exception) {
                                    // Never surface local vault/OS-key-store diagnostics in the UI.
                                    // The encrypted vault commits this identity as one atomic record.
                                    recoveryKey = ""
                                    error = t("identity.vault.import_error")
                                }
                            },
                        ) { Text(t("identity.setup.restore.action")) }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { recoveryKey = ""; exitApplication() },
                        ) { Text(t("common.cancel")) }
                    }
                }
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
    application {
        Window(
            onCloseRequest = { exitApplication() },
            title = t("identity.app_name"),
            state = rememberWindowState(width = 540.dp, height = 450.dp),
        ) {
            var importing by remember { mutableStateOf(false) }
            var recoveryKey by remember { mutableStateOf("") }
            var error by remember { mutableStateOf<String?>(null) }
            MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                Surface {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            if (importing) t("identity.import_existing") else t("identity.create_new"),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            if (importing) {
                                t("identity.import_existing.hint")
                            } else {
                                t("identity.create_new.hint")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!importing) {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { choice = FirstRunIdentityChoice.CREATE_NEW; exitApplication() },
                            ) { Text(t("identity.create_new")) }
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { importing = true },
                            ) { Text(t("identity.import_existing")) }
                        } else {
                            OutlinedTextField(
                                value = recoveryKey,
                                onValueChange = { recoveryKey = it; error = null },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(t("identity.vault.import_key")) },
                                supportingText = error?.let { message -> { Text(message) } },
                                isError = error != null,
                                visualTransformation = PasswordVisualTransformation(),
                                minLines = 3,
                            )
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    pickQrImage()?.let { image ->
                                        QrImageDecoder.decode(image).fold(
                                            onSuccess = { recoveryKey = it; error = null },
                                            onFailure = { error = t("identity.vault.import_error") },
                                        )
                                    }
                                },
                            ) { Text(t("identity.vault.scan_qr")) }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = recoveryKey.isNotBlank(),
                                onClick = {
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
                            ) { Text(t("identity.setup.restore.action")) }
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { recoveryKey = ""; error = null; importing = false },
                            ) { Text(t("common.cancel")) }
                        }
                    }
                }
            }
        }
    }
    return choice
}

/** A saved screenshot/photo works on all packaged desktops; no webcam dependency is assumed. */
private fun pickQrImage(): File? {
    val dialog = FileDialog(null as java.awt.Frame?, "Select QR image", FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
