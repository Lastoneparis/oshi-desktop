package com.oshi.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * The window a person sees when the app cannot open their account.
 *
 * __STARTUP_FAILURE_IS_SILENT_2026_11_02__ Every refusal in `UiLauncher` was a
 * `System.err.println` followed by `return`. From a terminal that is a fine
 * message. From the applications menu — which is how a desktop app is started —
 * stderr goes nowhere at all, so the entire failure is: you double-click OSHI
 * and nothing happens. No window, no error, exit status 0.
 *
 * That is the same shape as `__FIRST_RUN_EXITED_THE_PROCESS_2026_10_29__`, where
 * "Create New Identity" made the app vanish on macOS, and it reads to a user as
 * a broken download rather than a machine that cannot reach its key store.
 *
 * Found on Ubuntu 24.04 by running the PUBLISHED .deb under Xvfb: with a locked
 * keyring the app printed `secret-tool store failed: … locked collection` to a
 * stderr nobody was reading, and quit.
 *
 * The text is deliberately the raw reason plus what to do about it. A friendly
 * paraphrase that dropped the detail would leave the person with nothing to
 * search for and nothing to send us — and the detail is selectable so it can be
 * copied into a support mail.
 */
fun startupFailureWindow(headline: String, detail: String, whatToDo: String) {
    // exitProcessOnExit = false for the reason the first-run window documents:
    // these pre-UI windows must hand control back, not end the process themselves.
    application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = { exitApplication() },
            title = "OSHI — cannot open this account",
            state = rememberWindowState(width = 620.dp, height = 430.dp),
        ) {
            // The app's own palette and type, not Material's defaults: this window
            // is often the FIRST thing a person sees of OSHI, and a screen that
            // looks like a developer's error box reads as a broken download.
            MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            "OSHI",
                            style = MaterialTheme.typography.titleMedium.copy(
                                brush = OshiTheme.brandGradient,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Text(headline, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        SelectionContainer {
                            Text(
                                detail,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = OshiTheme.textSecondary,
                            )
                        }
                        Text(whatToDo, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { exitApplication() }) { Text("Close") }
                        }
                    }
                }
            }
        }
    }
}
