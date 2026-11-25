package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.oshi.desktop.stego.DesktopPhotoDisguise
import com.oshi.desktop.ui.components.PhotoDisguiseHost
import com.oshi.desktop.ui.components.PhotoDisguiseOverlays
import com.oshi.desktop.ui.components.PrivacyDisguisePane
import com.oshi.desktop.ui.state.ContactRow
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.messenger.service.stego.StegoV1
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.time.LocalDate

/**
 * __PRIVACY_DISGUISE_2026_11_18__ The Privacy disguise screens, rendered from the REAL
 * composables with fabricated state (no vault, no network), into `build/screens[/theme]`.
 * Run with `./gradlew renderScreens` (OSHI_RENDER_THEME=light|dark|oled).
 */
object PrivacyDisguiseScreens {
    private const val SCALE = 2f
    private const val W = 1180
    private const val H = 820

    private fun addr(seed: Char) = seed.toString().repeat(43) + "="
    private val contacts = listOf(
        ContactRow(addr('a'), "aaaa…", "Camille", true, false, ""),
        ContactRow(addr('b'), "bbbb…", "Bob", false, false, ""),
        ContactRow(addr('c'), "cccc…", "Inès", false, false, ""),
    )

    fun renderAll(out: File) {
        val priv = ByteArray(32) { 7 }
        val pub = com.oshi.messenger.network.v2.OSHICryptoV2.x25519PubFromPriv(priv)
        fun host() = PhotoDisguiseHost(priv, pub) { contacts }

        render(File(out, "40-privacy-disguise-on.png"), W, H) {
            PrivacyDisguisePane(host(), contacts, coverOn = true, onCoverChange = {})
        }
        render(File(out, "41-privacy-disguise-off.png"), 760, H) {
            PrivacyDisguisePane(host(), contacts, coverOn = false, onCoverChange = {})
        }
        val sheet = host().apply { draftPrefill = "Meet at the north gate, 18:00. Bring the papers."; open(contacts[0]) }
        render(File(out, "42-disguise-sheet.png"), W, H, frames = 40) {
            PrivacyDisguisePane(sheet, contacts, coverOn = true, onCoverChange = {})
            PhotoDisguiseOverlays(sheet)
        }
        val found = host().apply {
            reveal = PhotoDisguiseHost.RevealUi.Found(
                "IMG_20261118_174502.png", "Camille",
                StegoV1.Payload("Meet at the north gate, 18:00. Bring the papers.", System.currentTimeMillis() - 3_600_000,
                    StegoV1.Attachment("plan.txt", "text/plain", ByteArray(40))),
            )
        }
        render(File(out, "43-reveal-found.png"), W, H, frames = 4) {
            PrivacyDisguisePane(found, contacts, coverOn = true, onCoverChange = {})
            PhotoDisguiseOverlays(found)
        }
        val nf = host().apply { reveal = PhotoDisguiseHost.RevealUi.NotForYou("holiday.jpg") }
        render(File(out, "44-reveal-not-for-you.png"), W, H, frames = 4) {
            PrivacyDisguisePane(nf, contacts, coverOn = true, onCoverChange = {})
            PhotoDisguiseOverlays(nf)
        }
        val drag = host().apply { dragging = true }
        render(File(out, "45-drop-overlay.png"), W, H, frames = 4) {
            PrivacyDisguisePane(drag, contacts, coverOn = true, onCoverChange = {})
            PhotoDisguiseOverlays(drag)
        }
        // The cover "waiting" state: a 1:1 thread whose last message waits for the disguise.
        fun msg(id: String, fromMe: Boolean, body: String, stamp: String, status: String?, waiting: Boolean = false) =
            com.oshi.desktop.ui.state.MessageRow(
                id = id, fromMe = fromMe, who = if (fromMe) "me" else "Camille", body = body, attachment = null, stamp = stamp,
                status = status, failed = false, edited = false, deleted = false, reactions = "", coverWaiting = waiting,
            )
        val thread = com.oshi.desktop.ui.state.ThreadView(
            conversationId = addr('a'), title = "Camille", address = addr('a'),
            kind = ConversationKind.DIRECT, groupAdmin = false,
            groupMembers = emptyList(), groupCandidates = emptyList(), safetyNumber = "", verified = true,
            reach = com.oshi.desktop.ui.state.Reach.REACHABLE, reachLabel = "V2 path available", peerTyping = false,
            messages = listOf(
                msg("1", false, "Are you coming tonight?", "17:40", null),
                msg("2", true, "Yes — I'll send the details.", "17:41", "read"),
                msg("3", true, "North gate, 18:00.", "17:44", "pending", waiting = true),
            ),
            composer = com.oshi.desktop.ui.state.ComposerState(true, null, true, null),
        )
        render(File(out, "46-cover-waiting.png"), W, H) {
            com.oshi.desktop.ui.components.ThreadPane(
                thread = thread, notice = null, busy = false,
                wallpaper = com.oshi.desktop.ui.components.WallpaperId.NONE, onPickWallpaper = {},
                draft = "", onDraft = {}, onSend = {}, onAttach = {}, onVoiceNote = {},
                onRenameGroup = {}, onReact = { _, _ -> }, onEditMessage = { _, _ -> }, onDeleteMessage = {},
                onAddGroupMember = {}, onRemoveGroupMember = {}, onSetGroupMemberAdmin = { _, _ -> },
                viewOnce = com.oshi.desktop.ui.components.ViewOnceFacts.NONE, revealed = emptySet(), onReveal = {},
                today = LocalDate.now(),
                onDisguise = {},
            )
        }
    }

    /** __RESTRICTED_NETWORK_HINT_2026_11_18__ RN-HINT v1: the receiver's chip, closed and open, and the sender switch. */
    fun renderRestrictedHint(out: File) {
        fun row(id: String, fromMe: Boolean, body: String, stamp: String, status: String?, rn: Boolean = false) =
            com.oshi.desktop.ui.state.MessageRow(
                id = id, fromMe = fromMe, who = if (fromMe) "me" else "Camille", body = body, attachment = null, stamp = stamp,
                status = status, failed = false, edited = false, deleted = false, reactions = "", restrictedNetwork = rn,
            )
        val thread = com.oshi.desktop.ui.state.ThreadView(
            conversationId = addr('a'), title = "Camille", address = addr('a'),
            kind = ConversationKind.DIRECT, groupAdmin = false,
            groupMembers = emptyList(), groupCandidates = emptyList(), safetyNumber = "", verified = true,
            reach = com.oshi.desktop.ui.state.Reach.REACHABLE, reachLabel = "V2 path available", peerTyping = false,
            messages = listOf(
                row("1", true, "Are you OK? Nothing from you since yesterday.", "17:38", "read"),
                row("2", false, "Internet is filtered here since this morning. I'm fine.", "17:40", null, rn = true),
                row("3", false, "Keep it short, photos take ages.", "17:41", null, rn = true),
            ),
            composer = com.oshi.desktop.ui.state.ComposerState(true, null, true, null),
        )
        render(File(out, "47-restricted-hint-thread.png"), 760, 520) {
            com.oshi.desktop.ui.components.ThreadPane(
                thread = thread, notice = null, busy = false,
                wallpaper = com.oshi.desktop.ui.components.WallpaperId.NONE, onPickWallpaper = {},
                draft = "", onDraft = {}, onSend = {}, onAttach = {}, onVoiceNote = {},
                onRenameGroup = {}, onReact = { _, _ -> }, onEditMessage = { _, _ -> }, onDeleteMessage = {},
                onAddGroupMember = {}, onRemoveGroupMember = {}, onSetGroupMemberAdmin = { _, _ -> },
                viewOnce = com.oshi.desktop.ui.components.ViewOnceFacts.NONE, revealed = emptySet(), onReveal = {},
                today = LocalDate.now(),
                onDisguise = {},
            )
        }
        render(File(out, "48-restricted-hint-open.png"), 520, 150) {
            androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                com.oshi.desktop.ui.components.RestrictedNetworkChip(initiallyOpen = true)
            }
        }
        val priv = ByteArray(32) { 7 }
        val pub = com.oshi.messenger.network.v2.OSHICryptoV2.x25519PubFromPriv(priv)
        render(File(out, "49-restricted-hint-setting.png"), 760, 560) {
            PrivacyDisguisePane(PhotoDisguiseHost(priv, pub) { contacts }, contacts, coverOn = true, onCoverChange = {}, hintOn = true, onHintChange = {})
        }
    }

    private fun render(target: File, wDp: Int, hDp: Int, frames: Int = 1, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = (wDp * SCALE).toInt(), height = (hDp * SCALE).toInt(), density = Density(SCALE)) {
            OshiMaterialTheme {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        try {
            for (f in 1 until frames) { scene.render(f * 50_000_000L); Thread.sleep(80) }
            val image = scene.render(frames * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
