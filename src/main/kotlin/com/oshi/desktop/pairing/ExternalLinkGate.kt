package com.oshi.desktop.pairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oshi.desktop.group.GroupInvite
import com.oshi.desktop.mesh.MeshProtocol
import com.oshi.desktop.msg.PeerNickname
import java.util.Base64

/**
 * __DEEP_LINK_DRIVE_BY_2026_11_20__ An `oshi://` link that arrives from OUTSIDE the app (a
 * click in a browser, mail or PDF; the OS URL handler; a second launch) is held here until the
 * person confirms it.
 *
 * Before: the macOS `setOpenURIHandler` and the Windows/Linux second-launch path called
 * `startConversation(link)` directly. For `oshi://group/join?gid=…&inviter=K` that meant, with
 * no prompt, adding K as a contact AND sending K an authenticated `member_sync_request`
 * carrying this account's address: one click on a web page told an attacker which OSHI
 * identity the visitor has. `oshi://add?key=K` silently added K as a contact.
 *
 * A link the person PASTES into the New pane is their own action and is not gated.
 */
object ExternalLinkGate {

    sealed interface Pending {
        val link: String
        data class Join(override val link: String, val groupName: String, val inviterShort: String) : Pending
        data class AddContact(override val link: String, val addressShort: String) : Pending
        /**
         * __GROUP_INFO_SETTINGS_2026_11_20__ a PUBLIC group link (`/g/<handle>`). Its confirmation is
         * the join dialog itself, which needs the registry lookup to say what the group is
         * (GROUP_INFO_SETTINGS_SPEC §3.1 step 3): the prompt hands it over without a second "Continue".
         * Nothing is sent to anyone before that dialog's Join.
         */
        data class PublicJoin(override val link: String) : Pending
        /**
         * __BOTS_PHASE1_2026_11_24__ `oshi://bot/<username>`: opens the bot's PROFILE sheet, which
         * sends nothing to anyone (adding the bot is its own confirmed step), so no prompt.
         */
        data class Bot(override val link: String) : Pending
        /**
         * __CHANNEL_WEB_2026_11_26__ `oshi://channel?h=&n=` (the web page's "Open in OSHI") or
         * `https://oshi-messenger.com/c/<handle>[/<n>]`: opens a channel we are in, else the public
         * join dialog (its own confirmation). Sends nothing to anyone, so no prompt.
         */
        data class Channel(override val link: String) : Pending
    }

    /** The link awaiting a decision, or null. */
    var pending: Pending? by mutableStateOf(null)
        private set

    /** Hold [link] for confirmation. Returns what is pending, or null if the link is not actionable. */
    fun offer(link: String?): Pending? {
        val p = classify(link ?: return null) ?: return null
        pending = p
        return p
    }

    /** The person said yes: the link to act on, once. */
    fun accept(): String? = pending?.link.also { pending = null }

    fun dismiss() { pending = null }

    fun classify(link: String): Pending? {
        if (com.oshi.desktop.bot.BotLink.parse(link) != null) return Pending.Bot(link)
        if (com.oshi.desktop.group.ChannelWeb.parseLink(link) != null) return Pending.Channel(link)
        if (com.oshi.desktop.group.GroupPublicLink.parse(link) != null) return Pending.PublicJoin(link)
        GroupInvite.parse(link)?.let { inv ->
            val inviter = inv.inviter ?: return Pending.Join(link, displayGroupName(inv.name), "?")
            return Pending.Join(link, displayGroupName(inv.name), short(inviter))
        }
        return when (val scan = ContactQr.parse(link)) {
            is ContactQr.Scan.Contact -> Pending.AddContact(link, short(scan.address))
            else -> null
        }
    }

    /** A peer-chosen group name, as safe to show as a nickname (bidi stripped, capped). */
    fun displayGroupName(raw: String?): String =
        PeerNickname.sanitize(raw)?.takeIf { it.isNotBlank() } ?: "—"

    private fun short(key: String): String {
        val k = MeshProtocol.normalizeKey(key)
        return if (k.length <= 12) k else k.take(6) + "…" + k.takeLast(4)
    }

    /** A 32-byte public key in standard or URL-safe base64; anything else is not an inviter. */
    fun isPublicKey(s: String?): Boolean {
        val t = s?.trim()?.takeIf { it.length in 40..64 } ?: return false
        val bytes = runCatching { Base64.getDecoder().decode(t) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(t) }.getOrNull()
        return bytes?.size == 32
    }
}
