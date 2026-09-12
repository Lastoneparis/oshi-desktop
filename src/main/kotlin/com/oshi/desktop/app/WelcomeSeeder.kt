package com.oshi.desktop.app

import com.oshi.desktop.i18n.t
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.MessageStore
import com.oshi.desktop.store.TimestampSource
import java.io.File
import java.util.UUID

/**
 * The conversation a brand-new account starts with — ported from iOS's
 * `WelcomeMessageManager`, not invented here.
 *
 * ============================================================ WHY IT EXISTS AT ALL
 *
 * A messenger with no phone number and no directory opens, on a fresh install, onto
 * nothing. Not "no messages yet" — no way to find anybody. The phones answer that by
 * seeding one conversation with the account that built OSHI, so the first thing a new
 * user can do is reply to a real person. This is that, on the desktop, with the same
 * address and the same words.
 *
 * ============================================================ THE ADDRESS IS ALREADY PUBLIC
 *
 * [AUTHOR_ADDRESS] is the same string `WelcomeMessageManager.authorPublicKey` ships on
 * iOS. A public key is public by construction — it is what a QR code on a business card
 * prints — and it carries no secret. It is written here rather than fetched so a first
 * run works before any network call has succeeded.
 *
 * ============================================================ THE GREETING IS NOT FROM HUGO
 *
 * iOS is careful about this and so is this file: the greeting is written by the APP, and
 * attributing it to a person who did not type it is a small lie the user can later
 * discover — they would reply to a message that was never sent. So the row is stored with
 * `fromMe = false` in the conversation with the author, and its body says plainly that
 * the app wrote it and that a reply reaches a real person.
 *
 * The text itself comes from the shared catalog key `welcome.message.body`, which the 34
 * checked-in locales already carry — so this arrives translated without one new string.
 *
 * ============================================================ MARKED BEFORE IT IS WRITTEN
 *
 * The seed marker is written to disk BEFORE the message is appended, exactly as iOS does
 * it. If the append throws or the process is killed halfway, the cost is a missing
 * greeting; the other order costs a duplicate greeting on every launch forever, which is
 * the worse failure and the harder one to notice in testing.
 *
 * Keyed by ADDRESS, not by a global flag: a second identity on the same machine is a new
 * user and gets welcomed, and an account that already exists never is.
 */
object WelcomeSeeder {

    /** `WelcomeMessageManager.authorPublicKey` on iOS. Public by definition. */
    const val AUTHOR_ADDRESS = "eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN/muFE3A="

    /** `WelcomeMessageManager.authorAlias`. */
    const val AUTHOR_ALIAS = "Hugo"

    /** Where to write when something is wrong with the app itself rather than a contact. */
    const val SUPPORT_EMAIL = "contact@oshi-messenger.com"

    private const val MARKER = "welcome-seeded"

    /**
     * Seed the greeting once for [selfAddress].
     *
     * @return true when a greeting was written by THIS call.
     */
    fun seedIfNeeded(
        home: File,
        selfAddress: String,
        contacts: com.oshi.desktop.store.ContactStore,
        messages: MessageStore,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (selfAddress.isBlank()) return false
        // Never welcome someone into a conversation with themselves. It would be an
        // absurd first impression and the reply would go nowhere.
        if (selfAddress == AUTHOR_ADDRESS) return false

        val marker = File(home, "$MARKER.${selfAddress.take(24).replace(Regex("[^A-Za-z0-9]"), "_")}")
        if (marker.exists()) return false

        // BEFORE the write. See the class doc.
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(nowMs.toString())
        }.onFailure { return false }

        // A name, so the list draws "Hugo" rather than a truncated base64 key.
        runCatching { contacts.seen(AUTHOR_ADDRESS, nowMs, AUTHOR_ALIAS) }

        val body = t("welcome.message.body").let { s ->
            if (s.startsWith("⟦")) FALLBACK_BODY else s
        }
        runCatching {
            messages.append(
                Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = AUTHOR_ADDRESS,
                    senderAddress = AUTHOR_ADDRESS,
                    recipientAddress = selfAddress,
                    fromMe = false,
                    content = body + "\n\n" + t2("welcome.desktop.tail", DESKTOP_TAIL),
                    sentAtMs = nowMs,
                    sentAtSource = TimestampSource.LOCAL_CLOCK,
                    // NOT "delivered": nothing crossed a network. The transport says
                    // where this row came from, and a local seed is not a delivery.
                    deliveryStatus = DeliveryStatus.PENDING,
                    transport = "local-welcome",
                ),
            )
        }
        return true
    }

    /**
     * A catalog lookup that falls back to a literal instead of rendering `⟦key⟧`.
     *
     * `welcome.desktop.tail` is desktop-only and therefore not in the shared iOS catalogs.
     * Rendering the missing-key marker in the very first message a user ever sees would be
     * the worst possible place for it.
     */
    private fun t2(key: String, fallback: String): String =
        t(key).let { if (it.startsWith("⟦")) fallback else it }

    /**
     * Said in the greeting because the desktop differs from the phone in ways a new user
     * will otherwise discover as bugs: this account is not their phone's, and the window
     * has to stay open to receive. Both are on the download page too; neither is something
     * a person should have to find out by waiting for a message that never arrives.
     */
    private val DESKTOP_TAIL: String
        get() = "You are on the desktop client (beta). Two things worth knowing: this is a " +
            "separate OSHI account from your phone — there is no identity import yet — and " +
            "messages arrive only while this window is open, because there is no push on " +
            "desktop. Reply here and it reaches a real person. Something broken? $SUPPORT_EMAIL"

    private const val FALLBACK_BODY =
        "Welcome to OSHI. Your messages are end-to-end encrypted, and you signed up without " +
            "a phone number or an account — nobody was told you are here."
}
