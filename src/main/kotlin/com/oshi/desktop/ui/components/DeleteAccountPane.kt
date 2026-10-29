package com.oshi.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.catalogKey
import com.oshi.desktop.i18n.t
import com.oshi.desktop.net.AccountDeletionReceipt
import com.oshi.desktop.ui.OshiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deleting this account, with a mouse — the desktop counterpart of iOS's
 * `AccountDeletionView.swift`.
 *
 * __DESKTOP_DELETE_ACCOUNT_UI_2026_10_29__ `OshiClient.deleteAccount()` has existed for a
 * long time and was reachable only as `/deleteaccount confirm` in the text console, so this
 * window had NO way to delete the account at all. It was found by sweeping every
 * `OshiClient` function for one with no reference anywhere under `ui/`: twelve came back and
 * this was the one that mattered, because both phones ship the screen and an account you
 * cannot delete from the app you use is not a parity detail.
 *
 * ============================================================ WHY A TYPED WORD, NOT A DIALOG
 *
 * iOS gates this on typing a word, and that is reproduced rather than reinterpreted. Every
 * other destructive control in this window is undoable or repeatable; this one ends the
 * account AND takes the signing key with it, so there is no second chance and nobody —
 * including us — can undo it. A button behind a yes/no dialog is one slip away from that.
 *
 * ============================================================ WHAT THIS SCREEN MUST NOT HIDE
 *
 * The receipt is NOT a tick. `deleteAccount` returns [AccountDeletionReceipt] and not a
 * Boolean for a reason the server forces: a 207 means some stores were erased and others
 * could not be reached, and the person has to be told which. So the result section prints
 * what is gone, what could not be confirmed, and what was never ours to delete — files
 * already sent to other people are the recipients' messages now.
 *
 * A failure is also load-bearing: the server goes FIRST and a server that did not answer
 * aborts the local wipe, so a failed attempt leaves a WORKING account and a retry. Saying
 * nothing would leave someone believing they are half-deleted.
 *
 * Every string here is a SHARED `account.delete.*` key from the iOS catalog — 30 of them,
 * already translated into the 34 locales. Not one new desktop-only key, so this screen is
 * not English-only the way row 1.5 warns desktop-only copy still is.
 */
interface DeleteAccountHost {
    /** `DELETE /v2/account`, then the local wipe. Blocking; the pane calls it off the UI thread. */
    fun delete(): Result<AccountDeletionReceipt>
}

/** What the pane is doing. Mirrors iOS's `Phase`. */
sealed interface DeletePhase {
    data object Idle : DeletePhase
    data object Deleting : DeletePhase
    data class Done(val receipt: AccountDeletionReceipt) : DeletePhase
    data class Failed(val message: String?) : DeletePhase
}

/**
 * Trimmed and case-insensitive, as iOS is.
 *
 * Pure, and public, so the enabling rule is asserted without a window: the single most
 * important thing about this screen is WHEN the button turns on.
 */
fun deleteConfirmed(typed: String, confirmWord: String): Boolean =
    typed.trim().equals(confirmWord.trim(), ignoreCase = true) && confirmWord.isNotBlank()

@Composable
fun DeleteAccountPane(host: DeleteAccountHost) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf<DeletePhase>(DeletePhase.Idle) }
    val confirmWord = t("account.delete.confirm.word")

    Column(
        Modifier.fillMaxSize().background(OshiTheme.surface)
            .verticalScroll(rememberScrollState()).padding(OshiTheme.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PaneCard {
            Text(t("account.delete.headline"), style = OshiTheme.typography.titleLarge, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xs))
            Text(t("account.delete.body"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
        }

        // ------------------------------------------------------------ what is deleted
        Spacer(Modifier.height(OshiTheme.lg))
        PaneCard {
            Text(t("account.delete.section.removed"), style = OshiTheme.typography.titleSmall, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xs))
            for (key in REMOVED_ITEMS) {
                Text("•  ${t(key)}", style = OshiTheme.typography.bodySmall, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xxs))
            }
        }

        // ------------------------------------------------- what we cannot delete
        //
        // Drawn BEFORE the confirm field, never after: a limit read after the button has
        // been pressed is a limit read too late.
        Spacer(Modifier.height(OshiTheme.lg))
        PaneCard {
            Text(t("account.delete.section.kept"), style = OshiTheme.typography.titleSmall, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xs))
            for (key in KEPT_ITEMS) {
                Text("•  ${t(key)}", style = OshiTheme.typography.bodySmall, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xxs))
            }
            Spacer(Modifier.height(OshiTheme.xxs))
            Text(t("account.delete.section.kept.footer"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
        }

        when (val p = phase) {
            is DeletePhase.Done -> {
                Spacer(Modifier.height(OshiTheme.lg))
                ReceiptCard(p.receipt)
            }

            is DeletePhase.Failed -> {
                Spacer(Modifier.height(OshiTheme.lg))
                PaneCard {
                    Text(
                        t("account.delete.failed.generic"),
                        style = OshiTheme.typography.titleSmall,
                        color = OshiTheme.danger,
                    )
                    p.message?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(OshiTheme.xxs))
                        Text(t("account.delete.failed.detail", it),
                            style = OshiTheme.typography.bodySmall, color = Ink.strong)
                    }
                }
            }

            else -> Unit
        }

        // The confirm field disappears once the account is gone: there is nothing left to
        // confirm, and leaving an armed destructive button on screen would invite a second
        // press against an identity that no longer exists.
        if (phase !is DeletePhase.Done) {
            Spacer(Modifier.height(OshiTheme.lg))
            PaneCard {
                Text(t("account.delete.section.confirm"), style = OshiTheme.typography.titleSmall, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xs))
                Text(t("account.delete.confirm.footer", confirmWord),
                    style = OshiTheme.typography.bodySmall, color = Ink.soft)
                Spacer(Modifier.height(OshiTheme.sm))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = typed,
                        onValueChange = { if (phase is DeletePhase.Idle || phase is DeletePhase.Failed) typed = it },
                        textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                        cursorBrush = SolidColor(OshiTheme.brand),
                        modifier = Modifier.width(220.dp),
                        decorationBox = { inner ->
                            if (typed.isEmpty()) {
                                Text(confirmWord, style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                            }
                            inner()
                        },
                    )
                    PrimaryButton(
                        t("account.delete.action"),
                        enabled = phase !is DeletePhase.Deleting && deleteConfirmed(typed, confirmWord),
                    ) {
                        phase = DeletePhase.Deleting
                        scope.launch {
                            // Off the UI thread: this is a network round trip followed by a
                            // vault wipe, and freezing the window mid-delete is the worst
                            // moment to look like a crash.
                            val result = withContext(Dispatchers.IO) { runCatching { host.delete() }.getOrElse { Result.failure(it) } }
                            phase = result.fold(
                                { DeletePhase.Done(it) },
                                { DeletePhase.Failed(it.message) },
                            )
                            typed = ""
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptCard(r: AccountDeletionReceipt) {
    PaneCard {
        // "complete" and "partial" are DIFFERENT sentences in the catalog, and the
        // difference is the whole point of the receipt.
        Text(
            if (r.complete) t("account.delete.done.complete") else t("account.delete.done.partial"),
            style = OshiTheme.typography.titleSmall,
            color = if (r.complete) Ink.strong else OshiTheme.danger,
        )
        Spacer(Modifier.height(OshiTheme.xs))
        Text(t("account.delete.section.receipt"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
        Spacer(Modifier.height(OshiTheme.xs))

        ReceiptLine(t("account.delete.receipt.keys"), if (r.prekeysErased) "✓" else "—")
        ReceiptLine(t("account.delete.receipt.queued"), r.relayEnvelopes.toString())
        ReceiptLine(t("account.delete.receipt.sync"), r.syncItems.toString())
        ReceiptLine(t("account.delete.receipt.media"), r.inboundBlobs.toString())
        // Not a failure and not ours: a file already sent is the recipient's message now.
        if (r.outboundBlobsLeft > 0) {
            ReceiptLine(t("account.delete.receipt.outbound"), r.outboundBlobsLeft.toString())
        }
        if (r.unreachable.isNotEmpty()) {
            Spacer(Modifier.height(OshiTheme.xs))
            Text(
                t("account.delete.receipt.unreachable", r.unreachable.joinToString(", ")),
                style = OshiTheme.typography.bodySmall,
                color = OshiTheme.danger,
            )
        }
    }
}

@Composable
private fun ReceiptLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = OshiTheme.typography.bodySmall, color = Ink.soft, modifier = Modifier.weight(1f))
        Text(value, style = OshiTheme.typography.bodySmall, color = Ink.strong)
    }
    Spacer(Modifier.height(OshiTheme.xxs))
}

/**
 * The six things that go, in iOS's order.
 *
 * `catalogKey` and not a bare string: these are rendered later by `t(key)` with a VARIABLE,
 * so the key scanner cannot see them and `CatalogAuditTest` fails them as "a catalog key
 * that never reaches the catalog" — which is the audit working. Wrapping registers each one
 * so all 34 locales are held to it, exactly as a literal `t("…")` would be.
 */
private val REMOVED_ITEMS = listOf(
    catalogKey("account.delete.item.keys"),
    catalogKey("account.delete.item.queued"),
    catalogKey("account.delete.item.sync"),
    catalogKey("account.delete.item.media"),
    catalogKey("account.delete.item.push"),
    catalogKey("account.delete.item.device"),
)

/** The two it keeps, and why. Reports survive on purpose. */
private val KEPT_ITEMS = listOf(
    catalogKey("account.delete.keeps.recipients"),
    catalogKey("account.delete.keeps.reports"),
)
