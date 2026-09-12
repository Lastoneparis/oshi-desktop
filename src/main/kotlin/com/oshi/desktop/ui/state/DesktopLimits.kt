package com.oshi.desktop.ui.state

/**
 * What this window will not do, and why — PARITY.md row 1.1, rendered on the LIMITS pane.
 *
 * ============================================================ WHY A SCREEN FOR THIS
 *
 * The first vertical slice of a UI is where a messenger acquires its ability to lie. A tab
 * bar with a phone icon on it is a promise; a map pin is a promise; a paperclip is a
 * promise. VIEWS.md inventories 102 screens of the macOS app and this window implements a
 * minority of them, so the common case is still a feature the user knows from their phone and
 * will look for here. There are two honest answers to that and only two:
 *
 *   * **Absent.** No control, no icon, no menu item. This is what every row in [MISSING]
 *     gets in the chrome of the window — there is no call button anywhere in
 *     `com.oshi.desktop.ui`, not even a disabled one, because a greyed-out phone icon still
 *     tells the user a call is a thing this app does.
 *   * **Named, with the reason, in one place.** Which is this list. Absence alone is not
 *     enough at this coverage: a user who cannot find calls has no way to tell
 *     "deliberately not shipped" from "I haven't found it yet", and a developer opening
 *     this repo in six months has no way to tell "not yet" from "never".
 *
 * Two buckets, and the distinction between them is the whole point. [MISSING] is what the
 * CLIENT cannot do — a protocol, hardware or platform fact, with a PARITY.md row behind it.
 * [NOT_IN_THIS_WINDOW] is what the client CAN do and the window has not been taught yet,
 * which is a schedule, not a limit. Collapsing the two would either overstate the client
 * (making "we didn't build the UI" sound like a capability) or understate it (making a
 * live, phone-verified media path sound impossible).
 *
 * Every reason here is copied from a PARITY.md row rather than reasoned out afresh, and the
 * row number is in the text so a reader can check it. If a row's status changes, this list
 * is wrong and nothing will tell you — which is the same exposure the ledger itself has.
 */
object DesktopLimits {

    data class Limit(val what: String, val why: String)

    /**
     * Not available in this CLIENT — protocol, hardware or platform, not schedule.
     *
     * Nothing in this list has a control anywhere in the window, disabled or otherwise.
     */
    val MISSING: List<Limit> = listOf(
        Limit(
            "Voice and video calls — NO AUDIO EVER FLOWS",
            "PARITY.md row 2.1. Signalling now works and is reachable from the REPL " +
                "(/call, /answer, /decline, /hangup, behind --calls) — a ring really does " +
                "cross a machine boundary. What does NOT exist is media: no capture, no " +
                "playback, no UDP socket, no codec running. CallAudioSession is referenced " +
                "by nothing in the repository. A connected call is two devices agreeing and " +
                "SILENCE. Video additionally has no send half (no javax.video) and no display " +
                "half (no H.264 decoder on the JVM). A call button here would be the worst " +
                "thing this window could draw, which is why there is not one.",
        ),
        Limit(
            "Finding yourself on a map, navigation, live location, check-ins",
            "PARITY.md row 0.19. The Places destination is the HONEST half of the phone's Map tab: " +
                "it reads the offline POI and street databases off local disk and answers from " +
                "them. Everything below is the other half and none of it exists here. " +
                "The JDK has no GPS and none is faked, so location and check-ins " +
                "are RECEIVE-ONLY by construction — there is no send path for either in the " +
                "client (contact cards, the row's third payload, DO send: /card in the REPL). " +
                "A desktop machine also has no useful position to share. And measured " +
                "2026-08-27: an Android peer's location cannot ARRIVE either — a handset " +
                "shared its position at this client and nothing came, because Android's send " +
                "path has no V2 branch at all. Inbound location works only from iOS.",
        ),
        Limit(
            "Push notifications",
            "PARITY.md row 2.3. A desktop client POLLS /v2/messages; there is no APNs or FCM " +
                "equivalent. That means NO WAKE-FROM-SLEEP DELIVERY: messages arrive when this " +
                "process is running and the poll interval IS the delivery latency.",
        ),
        Limit(
            "Screenshot blocking",
            "PARITY.md row 2.6. No OS affordance exists on Windows or Linux; offering it would be " +
                "a lie drawn in a settings screen.",
        ),
        Limit(
            "Apple Watch",
            "PARITY.md row 2.7. WatchConnectivity needs a paired iPhone. There is no desktop-side " +
                "wearable story at all.",
        ),
        Limit(
            "Camera capture and QR scanning",
            "VIEWS.md §6. No desktop machine is guaranteed a webcam. Pairing is paste-the-key; " +
                "`/qr` in the REPL prints this account's own code as text or a PNG.",
        ),
        Limit(
            "\"Sync with your phone\"",
            "PARITY.md row 0.24, and this is a hard NO rather than a not-yet. Multi-device sync is " +
                "same-key by definition, and IdentityStore has exactly one writer of the account " +
                "private key: loadOrCreate, which GENERATES. There is no import and no restore, so " +
                "this client is always a SEPARATE OSHI account with its own archive slot. The row " +
                "says it outright: until an identity-import flow is designed, no UI copy may offer " +
                "to sync with your phone.",
        ),
        Limit(
            "Reading anything the LAN mesh or a LoRa radio carries",
            "PARITY.md rows 0.16 and 0.27. Both carry the LEGACY ratchet, which this client does " +
                "not implement. Mesh peers are a diagnostic in the REPL and are never stored as " +
                "conversations, because a chat log full of bubbles nobody can open is worse than " +
                "no bubbles.",
        ),
        Limit(
            "Bubble styles, link previews",
            "PARITY.md row 1.4. Chat WALLPAPERS now exist and are local-only — the picker in " +
                "the chat header says so on screen, and no wire traffic was invented for them. " +
                "The other two are not merely unbuilt. A bubble " +
                "style is a per-conversation setting the phones store locally and never sync, so " +
                "one set here would be invisible on the phone and vice versa; a link preview means " +
                "this client fetching an arbitrary URL a stranger sent, which is an outbound " +
                "request to a third party triggered by an incoming message. Neither is a paint job.",
        ),
    )

    /**
     * The client does these; this WINDOW does not yet. Use the REPL — the command is named.
     *
     * These are the rows that would be actively misleading to put in [MISSING]: the media
     * path in particular has been exercised against a real phone over the live relay.
     */
    val NOT_IN_THIS_WINDOW: List<Limit> = listOf(
        Limit(
            "Playing a video or a voice note that arrived",
            "PARITY.md rows 0.15 and 1.4. Pictures now OPEN here: an inbound photo is a thumbnail " +
                "in the bubble and a full-size view when you click it, decoded from the file the " +
                "blob path decrypted to this disk, with the format, the dimensions and the size " +
                "read from the file itself rather than from what the sender called it. Two " +
                "ceilings, each stated on screen when it bites — 64 MiB of file and 32 megapixels " +
                "of image — so that a file a stranger chose cannot decide how much memory this " +
                "window allocates; and a file that does not end the way its format requires is " +
                "still shown and is SAID to be incomplete. What is NOT here is playback: a video " +
                "and a voice note are named and located and explicitly not played, because a play " +
                "button that produced silence is the one thing worse than no button. The client " +
                "grew an audio path of its own (`media/AudioPlayer`, which transcodes the m4a the " +
                "JDK cannot open) and it is wired to nothing outside its tests and has never been " +
                "heard on any machine — so this is a window gap for audio and, for video, no " +
                "decoder anywhere in this project outside the call lane.",
        ),
        Limit(
            "Creating and administering groups",
            "PARITY.md row 0.17, partial, three sub-rows blocked. SENDING into a group works here " +
                "now (a fan-out over one ratchet session per member, with the outcome line naming " +
                "how many took it) and groups have their own half of the list. Creating one, " +
                "adding and removing members, and renaming are REPL-only: /group.",
        ),
        Limit(
            "Voice notes",
            "PARITY.md row 0.12. Recorded and encoded by this client in the container the phones " +
                "record, and watched crossing to a handset. The window has no microphone control " +
                "and no waveform. REPL: /sendfile with a recorded file.",
        ),
        Limit(
            "Bot channels",
            "PARITY.md row 0.26. Wired in the client and deliberately not given a composer here — " +
                "the lane has no encryption at all. REPL: /bot send, which prints the warning on " +
                "every send.",
        ),
        Limit(
            "Reactions, edits, deletes, typing and read receipts",
            "PARITY.md row 0.18. All four epochs are converted in one place and the SEND half " +
                "works; receive-from-Android is structurally dead per that row. This window " +
                "DISPLAYS reactions, edits and deletes that arrived, and has no controls to " +
                "create them. REPL: /react, /edit, /delete, /typing, /read.",
        ),
        Limit(
            "Marking a safety number verified",
            "PARITY.md rows 0.14, 0.20, 0.21. Contacts, blocking and unblocking, and every " +
                "contact's safety number are all under More in this window. What is NOT here is " +
                "the act of recording that you compared one out loud — REPL: /verify — because " +
                "verification is a claim about something that happened away from the screen and " +
                "this window has no way to witness it.",
        ),
        Limit(
            "Scheduled messages, sync, LoRa attach, account deletion",
            "PARITY.md rows 0.25, 0.24, 0.27, 0.11. All reachable from the REPL: /schedule, " +
                "/sync, /lora, /deleteaccount.",
        ),
        Limit(
            "Localisation",
            "PARITY.md row 1.5. 34 catalogs ship and the REPL uses 12 keys from them. This " +
                "window's strings are English literals apart from one shared key, so adding it " +
                "did not move that number and this pane must not be read as 34-locale coverage.",
        ),
    )
}
