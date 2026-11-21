package com.oshi.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.covert.CovertText
import com.oshi.desktop.covert.TextSteganography
import com.oshi.desktop.ui.state.ContactRow

/**
 * The covert text channel, as a person uses it.
 *
 * ============================================================ WHAT IT IS FOR
 *
 * A message hidden inside ordinary-looking prose, so it can be carried by a channel that
 * is watched but not blocked — a forum post, an email, an SMS, a comment box. OSHI does
 * not send it: the user copies the carrier out and pastes it wherever they like, and the
 * recipient pastes it back in here. That is the point. A channel that OSHI transmits is a
 * channel an observer can see OSHI using.
 *
 * ============================================================ WHAT THIS PANE PROMISES,
 * AND WHAT IT MUST NOT
 *
 * It promises confidentiality between two contacts: the carrier decrypts only with the
 * X25519 agreement between those two identities, and [CovertText] has a test that watches
 * a third party fail to read one.
 *
 * It does NOT promise that the carrier is undetectable. Zero-width characters are
 * invisible to a reader and entirely visible to anyone who looks at the bytes; a platform
 * that strips them destroys the payload, and one that counts them can flag it. The pane
 * says so on screen rather than in this comment, because the person deciding whether to
 * paste this into a monitored forum is the one who needs to know.
 *
 * ============================================================ WHY THE CRYPTO IS NOT HERE
 *
 * This file holds no key material and performs no agreement. It calls [CovertText], which
 * owns the derivation, zeroes the secret in a `finally`, and returns outcomes this pane
 * only has to phrase. A composable recomposes at times it does not choose; it is the wrong
 * place for anything that must happen exactly once.
 *
 * __DESKTOP_TOOLS_2027__ Now in the 34 languages (`pc.covert.*`), drawn with the tool kit.
 */
@Composable
fun CovertPane(
    covert: CovertText,
    contacts: List<ContactRow>,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val known = remember(contacts) { contacts.filterNot { it.blocked } }
    var hiding by remember { mutableStateOf(true) }
    var peer by remember(known) { mutableStateOf(known.firstOrNull()?.address.orEmpty()) }
    var message by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf("") }
    var carrier by remember { mutableStateOf("") }
    var incoming by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var limitsOpen by remember { mutableStateOf(false) }

    ToolScaffold(t("pc.covert.title"), modifier, subtitle = t("pc.covert.subtitle"), icon = ToolGlyph.EYE_OFF, actions = {
        ToolIconButton(ToolGlyph.INFO, t("pc.covert.limits.title"), active = limitsOpen) { limitsOpen = !limitsOpen }
    }) {
        if (limitsOpen) ToolCard(title = t("pc.covert.limits.title"), icon = ToolGlyph.SHIELD) {
            for (k in listOf(t("pc.covert.limits.visible"), t("pc.covert.limits.forward"), t("pc.covert.limits.desktop"))) {
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    Text("•", style = ToolType.body, color = Ink.soft)
                    Text(k, style = ToolType.body, color = Ink.strong)
                }
            }
        }
        if (known.isEmpty()) {
            ToolEmptyState(t("empty.no_contacts"), t("pc.covert.noContacts"), mascot = "thinking")
            return@ToolScaffold
        }
        ToolColumns(
            minTwoColumnWidth = 820.dp,
            first = {
                ToolCard(title = t("pc.covert.for"), icon = ToolGlyph.PEOPLE) {
                    ToolHint(t("pc.covert.for.hint"))
                    for (c in known) ToolRow(
                        c.label, subtitle = c.addressShort,
                        leading = { Monogram(c.label, c.address, 32.dp) },
                        selected = c.address == peer,
                        onClick = { peer = c.address; carrier = ""; revealed = null; note = null },
                    ) { if (c.address == peer) ToolIcon(ToolGlyph.CHECK, OshiTheme.brand, 16.dp) }
                }
            },
            second = {
                ToolTabs(
                    listOf(true, false), hiding,
                    label = { if (it) t("pc.covert.hide") else t("pc.covert.reveal") },
                    icon = { if (it) ToolGlyph.LOCK else ToolGlyph.KEY },
                    onSelect = { hiding = it; note = null; revealed = null },
                )
                if (hiding) ToolCard {
                    Text(t("new.composer.message"), style = ToolType.bodyStrong, color = Ink.strong)
                    ToolField(message, { message = it; note = null }, t("pc.covert.message.placeholder"), Modifier.fillMaxWidth(), singleLine = false, minHeight = 76.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("pc.covert.cover"), style = ToolType.bodyStrong, color = Ink.strong, modifier = Modifier.weight(1f))
                        ToolButton(t("pc.covert.suggest"), icon = ToolGlyph.SPARKLE, style = ToolButtonStyle.QUIET) {
                            cover = covert.suggestCover(TextSteganography.CoverTextStyle.SOCIAL_COMMENT); note = null
                        }
                    }
                    ToolHint(t("pc.covert.cover.room", covert.roomFor(cover, TextSteganography.Method.COMBINED)))
                    ToolField(cover, { cover = it; note = null }, t("pc.covert.cover.placeholder"), Modifier.fillMaxWidth(), singleLine = false, minHeight = 76.dp)
                    ToolButton(t("pc.covert.hide.action"), icon = ToolGlyph.LOCK, style = ToolButtonStyle.PRIMARY, enabled = message.isNotEmpty() && cover.isNotEmpty()) {
                        when (val r = covert.hide(message, cover, peer)) {
                            is CovertText.Hidden.Carrier -> { carrier = r.text; note = null }
                            is CovertText.Hidden.CoverTooShort -> { carrier = ""; note = t("pc.covert.tooShort", r.available, r.needed) }
                            CovertText.Hidden.UnknownPeer -> { carrier = ""; note = t("pc.covert.unknownPeer") }
                            CovertText.Hidden.EmptyMessage -> { carrier = ""; note = t("pc.covert.nothing") }
                            CovertText.Hidden.Refused -> { carrier = ""; note = t("pc.covert.refused") }
                        }
                    }
                    if (carrier.isNotEmpty()) {
                        Hairline()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t("pc.covert.carrier"), style = ToolType.bodyStrong, color = Ink.strong, modifier = Modifier.weight(1f))
                            ToolButton(t("common.copy"), icon = ToolGlyph.COPY) { onCopy(carrier) }
                        }
                        ToolHint(t("pc.covert.carrier.hint"))
                        MonoBlock(carrier)
                    }
                } else ToolCard {
                    Text(t("pc.covert.paste"), style = ToolType.bodyStrong, color = Ink.strong)
                    ToolHint(t("pc.covert.paste.hint"))
                    ToolField(incoming, { incoming = it; revealed = null; note = null }, t("pc.covert.paste.placeholder"), Modifier.fillMaxWidth(), singleLine = false, minHeight = 96.dp)
                    ToolButton(t("pc.covert.reveal.action"), icon = ToolGlyph.KEY, style = ToolButtonStyle.PRIMARY, enabled = incoming.isNotEmpty()) {
                        val out = covert.reveal(incoming, peer)
                        revealed = out
                        note = if (out == null) t("pc.covert.unreadable") else null
                    }
                    revealed?.let {
                        Hairline()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t("pc.covert.hidden"), style = ToolType.bodyStrong, color = Ink.strong, modifier = Modifier.weight(1f))
                            ToolButton(t("common.copy"), icon = ToolGlyph.COPY) { onCopy(it) }
                        }
                        MonoBlock(it)
                    }
                }
                note?.let { ToolNotice(it, ToolTone.WARNING) }
            },
        )
    }
}
