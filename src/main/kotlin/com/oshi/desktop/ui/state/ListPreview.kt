package com.oshi.desktop.ui.state

import com.oshi.desktop.i18n.t
import com.oshi.desktop.msg.AliveCheckInPayload
import com.oshi.desktop.place.LocationPayload
import com.oshi.desktop.store.MediaType
import com.oshi.desktop.store.Message

/**
 * __DESKTOP_1_6_PARITY__ What the conversation list says about a conversation's last message.
 *
 * 1.5.1 printed `[audio] note vocale été.m4a` under a contact's name: the wire type in square
 * brackets and the file name the sender's phone happened to give the recording. Both phones
 * draw a microphone, "Voice message" and the duration instead (Android `ConversationRow`
 * preview, iOS `ConversationListRow.previewText`), and so does this now.
 *
 * Pure: the model hands it a [Message] and a translator, and the row draws [kind] as a glyph
 * in front of [text]. No glyph type here, so the rule is testable without Compose.
 */
data class ListPreview(val kind: Kind, val text: String) {

    enum class Kind { TEXT, VOICE, PHOTO, VIDEO, VIDEO_NOTE, FILE, GIF, CONTACT, LOCATION, POLL, ALIVE, DELETED }

    companion object {
        /** A list row shows at most this many characters of a text message. */
        const val MAX = 80

        /** `0:07`, `1:23`, `12:05` — the phones' duration label. Null for an unknown duration. */
        fun duration(ms: Long?): String? {
            if (ms == null || ms <= 0) return null
            val s = (ms + 500) / 1000
            return "%d:%02d".format(java.util.Locale.ROOT, s / 60, s % 60)
        }

        fun of(m: Message?, textOf: (Message) -> String): ListPreview = when {
            m == null -> ListPreview(Kind.TEXT, "")
            m.isDeletedForEveryone -> ListPreview(Kind.DELETED, t("message.deleted_for_everyone"))
            m.hiddenLocally -> ListPreview(Kind.TEXT, "")
            m.videoNote && m.mediaType == MediaType.VIDEO ->
                ListPreview(Kind.VIDEO_NOTE, joinDuration(t("videonote.title"), m.mediaDurationMs))
            m.mediaRef != null -> media(m)
            LocationPayload.decode(m.content.orEmpty()) != null -> {
                val loc = LocationPayload.decode(m.content.orEmpty())!!
                ListPreview(Kind.LOCATION, loc.address?.takeIf { it.isNotBlank() } ?: t("location.shared_location"))
            }
            AliveCheckInPayload.isEvent(m.content.orEmpty()) -> ListPreview(
                Kind.ALIVE,
                when (AliveCheckInPayload.summaryKind(m.content.orEmpty())) {
                    AliveCheckInPayload.Kind.ENABLED -> t("alive.summary.started")
                    AliveCheckInPayload.Kind.DISABLED -> t("alive.summary.stopped")
                    AliveCheckInPayload.Kind.CONFIRMED -> t("alive.summary.confirmed")
                },
            )
            // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ "🛡 Advanced chat privacy turned on/off" (both phones).
            com.oshi.desktop.msg.AdvancedPrivacyPayload.isPayload(m.content) -> ListPreview(
                Kind.TEXT,
                "\uD83D\uDEE1 " + if (com.oshi.desktop.msg.AdvancedPrivacyPayload.decode(m.content)?.on != false) t("desktop.adv_privacy.summary.on") else t("desktop.adv_privacy.summary.off"),
            )
            com.oshi.desktop.msg.PollPayload.isPoll(m.content) ->
                ListPreview(Kind.POLL, com.oshi.desktop.msg.PollPayload.decodePoll(m.content)?.question.orEmpty())
            else -> ListPreview(Kind.TEXT, textOf(m))
        }.let { it.copy(text = it.text.replace('\n', ' ').take(MAX)) }

        private fun joinDuration(label: String, ms: Long?): String =
            duration(ms)?.let { "$label · $it" } ?: label

        /** A caption the sender typed, or null when [Message.content] is only the file's name. */
        fun caption(m: Message): String? {
            val c = m.content?.trim().orEmpty()
            if (c.isEmpty()) return null
            val ref = m.mediaRef.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            val looksLikeName = c == ref || ref.endsWith("-$c") ||
                Regex("""^[^\s/\\]+\.[A-Za-z0-9]{2,5}$""").matches(c) ||
                Regex("""^[^/\\]{1,120}\.(m4a|aac|mp3|ogg|opus|wav|mp4|mov|m4v|webm|jpe?g|png|heic|webp|gif|pdf|json)$""", RegexOption.IGNORE_CASE).matches(c)
            return if (looksLikeName) null else c
        }

        private fun media(m: Message): ListPreview {
            val ref = m.mediaRef.orEmpty()
            val gif = ref.endsWith(".gif", ignoreCase = true) && m.content.orEmpty().endsWith(".gif", ignoreCase = true)
            if (gif) return ListPreview(Kind.GIF, "GIF")
            val caption = caption(m)
            return when (m.mediaType) {
                MediaType.AUDIO -> ListPreview(Kind.VOICE, joinDuration(t("audio.voice_message"), m.mediaDurationMs))
                MediaType.IMAGE -> ListPreview(Kind.PHOTO, caption ?: t("media.photo"))
                MediaType.VIDEO -> ListPreview(Kind.VIDEO, caption ?: joinDuration(t("media.video"), m.mediaDurationMs))
                MediaType.CONTACT -> ListPreview(Kind.CONTACT, t("desktop.media.card.contact"))
                MediaType.LOCATION -> ListPreview(Kind.LOCATION, t("location.shared_location"))
                // A document's NAME is the useful thing to show, the phones print it too.
                MediaType.DOCUMENT, MediaType.UNKNOWN, null ->
                    ListPreview(Kind.FILE, m.content?.trim()?.ifEmpty { null } ?: t("media_file"))
            }
        }
    }
}
