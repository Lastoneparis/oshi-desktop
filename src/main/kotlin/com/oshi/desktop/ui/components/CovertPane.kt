package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.oshi.desktop.covert.CovertText
import com.oshi.desktop.covert.TextSteganography
import com.oshi.desktop.ui.OshiTheme
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
 * Strings are English in the window, matching every other pane in this client. That is a
 * known debt, not a decision — see the desktop overlay catalog.
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

    Column(
        modifier
            .fillMaxSize()
            .background(OshiTheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(OshiTheme.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp)) {

            PaneHeading(
                "Covert text",
                "Hide a message inside ordinary prose. OSHI does not send it — you copy it " +
                    "out and carry it by whatever channel is open.",
            )

            if (known.isEmpty()) {
                PaneCard {
                    Text(
                        "No contacts yet. A covert message is addressed to one person: the " +
                            "carrier only opens with the key agreement between your two " +
                            "identities. Add someone under New first.",
                        style = OshiTheme.typography.bodyMedium,
                        color = Ink.soft,
                    )
                }
                return@Column
            }

            // ---------------------------------------------------------------- who
            PaneCard {
                Text("For", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xs))
                Text(
                    "The carrier opens for this contact and for nobody else, including you " +
                        "afterwards unless you keep the plain text.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
                Spacer(Modifier.height(OshiTheme.md))
                for (c in known) {
                    PeerChoice(c, selected = c.address == peer) {
                        peer = c.address
                        carrier = ""; revealed = null; note = null
                    }
                    Hairline(inset = Metrics.separatorInset)
                }
            }

            Spacer(Modifier.height(OshiTheme.lg))

            SegmentedControl(
                options = listOf(true, false),
                selected = hiding,
                label = { if (it) "Hide a message" else "Reveal one" },
                onSelect = { hiding = it; note = null; revealed = null },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(OshiTheme.lg))

            if (hiding) {
                PaneCard {
                    Text("Message", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                    Spacer(Modifier.height(OshiTheme.xs))
                    PasteField(message, { message = it; note = null }, "What you actually want to say")

                    Spacer(Modifier.height(OshiTheme.lg))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Cover text", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                        Spacer(Modifier.weight(1f))
                        TextAction("Suggest one") {
                            cover = covert.suggestCover(TextSteganography.CoverTextStyle.SOCIAL_COMMENT)
                            note = null
                        }
                    }
                    Spacer(Modifier.height(OshiTheme.xs))
                    Text(
                        "The prose a reader sees. Longer carries more: this one holds about " +
                            "${covert.roomFor(cover, TextSteganography.Method.COMBINED)} " +
                            "characters of message.",
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                    Spacer(Modifier.height(OshiTheme.xs))
                    PasteField(cover, { cover = it; note = null }, "Paste or write something unremarkable")

                    Spacer(Modifier.height(OshiTheme.lg))
                    PrimaryButton("Hide it", enabled = message.isNotEmpty() && cover.isNotEmpty()) {
                        when (val r = covert.hide(message, cover, peer)) {
                            is CovertText.Hidden.Carrier -> { carrier = r.text; note = null }
                            is CovertText.Hidden.CoverTooShort -> {
                                carrier = ""
                                note = "The cover text is too short: it holds ${r.available} bytes " +
                                    "and this message needs ${r.needed}. Lengthen the cover, or " +
                                    "shorten the message."
                            }
                            CovertText.Hidden.UnknownPeer ->
                                { carrier = ""; note = "That contact's address is not usable." }
                            CovertText.Hidden.EmptyMessage ->
                                { carrier = ""; note = "Nothing to hide." }
                            CovertText.Hidden.Refused ->
                                { carrier = ""; note = "This cover text cannot carry a payload. Try another." }
                        }
                    }

                    if (carrier.isNotEmpty()) {
                        Spacer(Modifier.height(OshiTheme.lg))
                        Text("Carrier", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                        Spacer(Modifier.height(OshiTheme.xs))
                        Text(
                            "Copy this whole block — it reads as the cover text and carries the " +
                                "message in characters you cannot see. Anything that rewrites or " +
                                "trims the text destroys the payload.",
                            style = OshiTheme.typography.bodySmall,
                            color = Ink.soft,
                        )
                        Spacer(Modifier.height(OshiTheme.xs))
                        MonoBlock(carrier)
                        Spacer(Modifier.height(OshiTheme.md))
                        TextAction("Copy carrier") { onCopy(carrier) }
                    }
                }
            } else {
                PaneCard {
                    Text("Paste what you received", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                    Spacer(Modifier.height(OshiTheme.xs))
                    Text(
                        "Paste the text exactly as it arrived. Retyping it, or letting an app " +
                            "reformat it, removes the hidden characters.",
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                    Spacer(Modifier.height(OshiTheme.xs))
                    PasteField(incoming, { incoming = it; revealed = null; note = null }, "Paste here")

                    Spacer(Modifier.height(OshiTheme.lg))
                    PrimaryButton("Reveal", enabled = incoming.isNotEmpty()) {
                        val out = covert.reveal(incoming, peer)
                        revealed = out
                        // One message for every failure, because distinguishing them would
                        // confirm to anyone running this client whether a given text is a
                        // carrier addressed to a given contact.
                        note = if (out == null)
                            "Nothing readable for this contact. Either it carries nothing, it " +
                                "is addressed to someone else, or it was altered in transit."
                        else null
                    }

                    revealed?.let {
                        Spacer(Modifier.height(OshiTheme.lg))
                        Text("Hidden message", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                        Spacer(Modifier.height(OshiTheme.xs))
                        MonoBlock(it)
                        Spacer(Modifier.height(OshiTheme.md))
                        TextAction("Copy message") { onCopy(it) }
                    }
                }
            }

            note?.let {
                Spacer(Modifier.height(OshiTheme.md))
                Text(it, style = OshiTheme.typography.bodySmall, color = Ink.soft)
            }

            Spacer(Modifier.height(OshiTheme.lg))

            // ------------------------------------------------------- the honest part
            PaneCard {
                Text("What this does not do", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xs))
                Text(
                    "It hides a message from a reader, not from an analyst. The carrier uses " +
                        "invisible characters: someone inspecting the bytes can see them, and a " +
                        "platform that strips them destroys the message.\n\n" +
                        "It has no forward secrecy. The key is the agreement between your two " +
                        "identities and never changes, so one compromised identity key opens " +
                        "every carrier ever written between you. Ordinary OSHI messages do not " +
                        "work this way.\n\n" +
                        "It is desktop to desktop for now. The phone apps carry the same format " +
                        "underneath but expose no screen for it.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
            }
        }
    }
}

/** One selectable contact. Deliberately plain: this list is a choice, not a conversation. */
@Composable
private fun PeerChoice(c: ContactRow, selected: Boolean, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusMd)
            .background(if (selected) OshiTheme.surfaceElevated else OshiTheme.surface)
            .clickable(onClick = onPick)
            .padding(OshiTheme.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(c.label, c.address, Metrics.avatarHeader)
        Spacer(Modifier.widthIn(min = OshiTheme.md))
        Column(Modifier.weight(1f)) {
            Text(c.label, style = OshiTheme.typography.bodyMedium, color = Ink.strong)
            Text(c.addressShort, style = OshiTheme.typography.bodySmall, color = Ink.soft)
        }
        if (selected) Text("✓", style = OshiTheme.typography.bodyMedium, color = OshiTheme.brand)
    }
}
