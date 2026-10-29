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
        // __DESKTOP_CALLS_ARE_NOT_MISSING_2026_10_29__ This entry read "Video calls; call
        // controls in this window" and was false in every clause by the time anyone read it:
        //
        //   "This window does not yet render call controls"      -> it renders all of them:
        //       a call button per direct thread (`ThreadPane.onCall`, with a video flag),
        //       `CallOverlay` with a video stage, mute, camera toggle, camera switch and
        //       minimise, plus a separate `IncomingCallWindow`.
        //   "Video has no camera capture, encoder, decoder or
        //    renderer on this desktop"                           -> `CameraCapture`,
        //       `H264Encoder` and `H264Decoder` are all in `call/video/FfmpegVideo.kt`.
        //   "only loopback test evidence, no TURN fallback"       -> row 2.1-t adds a TURN
        //       relay candidate and the :8089 carrier, both verified live, and row 2.1 is
        //       "voice + video both ways, desktop<->desktop measured live".
        //
        // The entry was also PINNED BY A TEST asserting that something matching "calls" must
        // appear in this list, so the list could not be corrected without correcting the
        // test — the hardest version of a stale claim, because the suite defends it.
        //
        // What is genuinely unproven is narrower and is the part row 2.1 states about
        // itself, so it is kept here rather than quietly dropped with the rest.
        Limit(
            "A call to a PHONE, and a call that has touched real audio hardware",
            "PARITY.md row 2.1. Calling works in this window — a call button per direct " +
                "conversation, voice and video both ways, measured live between two desktops, " +
                "with a TURN relay candidate and the :8089 carrier verified (row 2.1-t). What is NOT " +
                "verified is narrower than it was: no packet has ever reached an iPhone or an " +
                "Android handset, and no NAT has been traversed, because loopback has none. Audio " +
                "crossing loopback in a test is not a call between two people. **The speaker is no " +
                "longer part of that list (measured 2026-10-29):** `LiveAudioDeviceBench` opens the " +
                "real render line at the call format and 400 ms of real frames were written and " +
                "DRAINED in 407 ms on this machine — real time, so the device played them rather " +
                "than discarding them. It is opt-in (OSHI_AUDIO_PLAYBACK=1) because a test suite " +
                "should not make noise, and the microphone half is a SEPARATE switch that is left " +
                "off: recording the room because tests ran is not something a switch should bundle.",
        ),
        // __DESKTOP_POSITION_CLAIMS_EXPIRED_2026_10_29__ Two clauses of this entry had died,
        // and one of them I killed myself in this very codebase.
        //
        //  - "A desktop machine also has no useful position to share" — I wrote the argument
        //    for that and then wrote the feature that disproves it. A Mac has no sensor; it
        //    has an OWNER WHO KNOWS WHERE IT IS, which is exactly the case Meshtastic's
        //    `fixed_position` covers. `LoRaFixedPosition` + `LoRaPositionBeacon` share this
        //    machine's stated position over the mesh, off by default, and row 0.19 was
        //    amended to say so. The missing thing was a position SOURCE, never a beacon.
        //  - "Android's send path has no V2 branch at all. Inbound location works only from
        //    iOS." True when measured on 2026-08-27, false now: `sendLocationMessage`
        //    (`MessageRepository.kt:3458`) runs `ControlLane.preferV2`, the same lane the
        //    receipts and typing fix introduced. A dated measurement of ANOTHER platform is
        //    the shortest-lived kind of claim in this ledger.
        //
        // What survives is narrower and is the real limit, so the entry stays.
        Limit(
            "Sharing your own location or a check-in from this machine",
            "PARITY.md row 0.19. The Places destination is the HONEST half of the phone's Map tab: " +
                "it reads the offline POI and street databases off local disk and answers from " +
                "them. What is missing is the SEND half over the message lane: the JDK has no GPS " +
                "and none is faked, so a location share and a check-in are receive-only here " +
                "(contact cards, the row's third payload, DO send). Inbound shares arrive from " +
                "iOS and now from Android too. Note this is a limit of the MESSAGE lane and not " +
                "of the machine: over a LoRa radio this client does beacon a position its owner " +
                "typed in, because Meshtastic's fixed_position is built for a node with no " +
                "sensor — see the Radio screen, where it is off until you set one.",
        ),
        Limit(
            "Push notifications",
            "PARITY.md row 2.3. A desktop client POLLS /v2/messages; there is no APNs or FCM " +
                "equivalent. That means NO WAKE-FROM-SLEEP DELIVERY: messages arrive when this " +
                "process is running and the poll interval IS the delivery latency.",
        ),
        // __DESKTOP_SCREENSHOT_REASON_2026_10_29__ The CONCLUSION here is right and is kept;
        // the REASON was wrong, and checking it was the only way to find that the right
        // answer now rests on a different fact.
        //
        // It read: "No OS affordance exists on Windows or Linux". That is the familiar shape
        // of a reason true for some platforms and applied to all three (`InfoPlist`, the
        // Windows DLL, the macOS camera list — all the same mistake), and it is wrong twice:
        // macOS has `NSWindow.sharingType = .none`, and Windows has
        // `SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE)` since Windows 10 2004.
        // Only Linux genuinely has nothing.
        //
        // So the affordance was never the blocker. TWO other things are, and the second is
        // why this is not merely unbuilt:
        //
        //  1. Reach. Both are native calls. This client targets JVM 11 (`build.gradle.kts`),
        //     so `java.lang.foreign` is not available and there is no JNA; getting at either
        //     would mean a JNI shim, which is real work rather than a paint job.
        //  2. **It would not be true on the macOS this app is built for.** Checked 2026-10-29:
        //     on macOS 15 a window marked `.none` is reported as still captured through
        //     ScreenCaptureKit (Apple Developer Forums 808016 and 770585 both describe it
        //     failing or leaking on 15.4+, and Electron carries a filter to re-honour the flag
        //     itself). `MacDesktopIntegration` records that this window's macOS behaviour was
        //     measured on macOS 15 — the same version. A switch that says "screenshots are
        //     blocked" while a screen recorder still sees the thread is worse than no switch,
        //     and it is a SECURITY promise, so it is the worst kind to get wrong.
        //
        // Which is the original entry's own argument, arriving at the same place by a sounder
        // route: offering it would be a lie drawn in a settings screen. Not implemented, on
        // purpose, and now for the reason that is actually load-bearing.
        Limit(
            "Screenshot blocking",
            "PARITY.md row 2.6. Not offered, and the reason is not that the OS lacks a way: macOS " +
                "has NSWindow.sharingType and Windows has SetWindowDisplayAffinity " +
                "(WDA_EXCLUDEFROMCAPTURE); only Linux has nothing. Both are native calls this " +
                "JVM-11 client cannot make without a JNI shim — and more to the point, the macOS " +
                "flag is reported unreliable against ScreenCaptureKit on macOS 15 and later, which " +
                "is the macOS this window is built and measured on. A switch promising that " +
                "screenshots are blocked while a screen recorder still sees the conversation is a " +
                "security promise that is false, which is worse than an absent one. iOS blocks " +
                "screenshots with a UITextField.isSecureTextEntry layer; there is no equivalent " +
                "trick in a Compose window.",
        ),
        Limit(
            "Apple Watch",
            "PARITY.md row 2.7. WatchConnectivity needs a paired iPhone. There is no desktop-side " +
                "wearable story at all.",
        ),
        // __DESKTOP_CALLS_ARE_NOT_MISSING_2026_10_29__ Removed, not rewritten: live webcam
        // QR capture SHIPPED on 2026-10-28. `QrCameraScanner` is wired into `NewPane` and
        // `CameraCapture` had been in the tree since video calls with exactly one caller —
        // searching for the FEATURE ("webcam") found nothing, searching for the CLASS found
        // everything. Measured then at 133 frames in 6 s (~22 fps). "No desktop machine is
        // guaranteed a webcam" is still true and is not a limit of this client: a machine
        // without one falls back to scanning a selected image or a pasted key, which the
        // screen says on its own.

        Limit(
            "\"Sync with your phone\"",
            "PARITY.md row 0.24. An empty desktop profile can restore the same identity from an " +
                "iOS/Android recovery key, but history and group synchronisation remain unavailable: " +
                "phones do not yet write the owner-authenticated V2 archive this client requires. " +
                "The unauthenticated legacy archive is deliberately not enabled here.",
        ),
        // __DESKTOP_RADIO_LIMIT_IS_THE_CABLE_2026_10_29__ This entry read "Reading anything
        // the LAN mesh or a LoRa radio carries", because "both carry the LEGACY ratchet,
        // which this client does not implement. Mesh peers are a diagnostic in the REPL and
        // are never stored as conversations".
        //
        // EVERY CLAUSE OF THAT WAS FALSE BY THE TIME IT WAS READ, and this list is drawn in
        // the window — so the app was telling its own users that radio and mesh messages
        // cannot be read, on a build that reads them. Rows 0.16 (UNBLOCKED) and 0.27 (CLOSED)
        // both say otherwise, row 1.3 says in so many words that "row 0.16 NO LONGER holds:
        // the mesh no longer carries payloads this client cannot open", and the code is
        // plainer still: `LegacyRatchetEnvelope` is compiled and live, `openOshiEnvelope`
        // decrypts an envelope off EITHER transport, and inbound stock Meshtastic text is
        // written durably into its own `lora!<nodehex>` conversation — a real
        // `ConversationKind.LORA` thread with a badge in the list, an unread count and a
        // local notification.
        //
        // What is actually and permanently missing is the CABLE, not the reading. Replacing
        // the entry rather than deleting it, because a user who cannot attach a radio the way
        // their phone does still needs an answer, and this is the honest one.
        Limit(
            "Pairing a LoRa radio over Bluetooth",
            "PARITY.md row 0.27. Both phones talk to a Meshtastic radio over BLE and the JDK has " +
                "no Bluetooth stack at all — verified by grepping both trees for the stream-framing " +
                "magic. This is a platform fact, not a schedule. It costs nothing, because BLE is a " +
                "phone constraint and not a protocol one: the same protobufs ride a USB serial port " +
                "(macOS and Linux, where a serial device is a character device and the bytes are " +
                "plain java.io) and TCP:4403 over WiFi. Plug the radio in, or give it an address. " +
                "Windows has no serial lane — COM3 is not a file and has no stty — so a radio there " +
                "must be reachable over TCP. READING a radio or the LAN mesh is NOT a limit and " +
                "this entry used to say it was: OSHI envelopes off either transport are decrypted " +
                "and filed under the sender, and unauthenticated stock Meshtastic text gets its own " +
                "quarantined `lora!` thread, marked unverified, never beside a real message.",
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
            "Playing a video that arrived",
            "PARITY.md rows 0.15 and 1.4. Pictures now OPEN here: an inbound photo is a thumbnail " +
                "in the bubble and a full-size view when you click it, decoded from the file the " +
                "blob path decrypted to this disk, with the format, the dimensions and the size " +
                "read from the file itself rather than from what the sender called it. Two " +
                "ceilings, each stated on screen when it bites — 64 MiB of file and 32 megapixels " +
                "of image — so that a file a stranger chose cannot decide how much memory this " +
                "window allocates; and a file that does not end the way its format requires is " +
                "still shown and is SAID to be incomplete. Voice notes have a play/stop control " +
                "here and use `media/AudioPlayer`, which transcodes the m4a the JDK cannot open " +
                "and reports device/decoder failures in the viewer. Its test device is fake, so " +
                "a real speaker remains unverified. **VIDEO NOW PLAYS HERE TOO (corrected " +
                "2026-10-29).** This clause read \"What is NOT here is video playback: the video is " +
                "named and located, explicitly not played, and no video decoder exists outside the " +
                "call lane\" — and `MediaViewer.VideoPlayerPanel` plays an inbound video, through " +
                "`VideoNoteReader` at `Fit.CONTAIN`, with its own duration ceiling. The decoder had " +
                "arrived with round video notes and was wired to that ONE caller, which is why " +
                "searching for video PLAYBACK found nothing while the decoder sat in the tree. " +
                "Unverified, and said rather than left to be found: no test opens a speaker, so " +
                "the audio of a played video has never left a device.",
        ),
        Limit(
            "Advanced group administration",
            "PARITY.md row 0.17, partial, three sub-rows blocked. SENDING into a group works here " +
                "now (a fan-out over one ratchet session per member, with the outcome line naming " +
                "how many took it), groups have their own half of the list, and a new group can " +
                "be created there from known unblocked contacts. Group admins can rename from the " +
                "thread header; the roster lets an admin add known unblocked contacts, remove " +
                "other members, and promote or demote other members. The creator role is permanent. " +
                "Leaving a group and other advanced operations remain REPL-only: /group.",
        ),
        Limit(
            "Bot channels",
            "PARITY.md row 0.26. Wired in the client and deliberately not given a composer here — " +
                "the lane has no encryption at all. REPL: /bot send, which prints the warning on " +
                "every send.",
        ),
        Limit(
            "None for direct-message controls",
            "PARITY.md row 0.18. All four epochs are converted in one place and the SEND half " +
                "works; receive-from-Android is structurally dead per that row. This window " +
                "displays reactions, edits and deletes that arrived, and direct-message bubbles " +
                "can add reactions or edit/delete their own messages. Opening a direct thread " +
                "sends the privacy-gated read receipt, and composing sends debounced typing " +
                "start/stop pings. Inbound direct typing appears transiently and auto-clears. " +
                "REPL: /typing, /read.",
        ),
        // __LORA_IS_NOT_REPL_ONLY_2026_10_29__ This entry was titled "LoRa attach, account
        // deletion and sync checkpointing" and ended "The others are REPL-only: /sync
        // checkpoint, /lora, /deleteaccount." — naming `/lora` to the user's face while
        // More → Radio has attached, detached, broadcast, linked a node to a contact, set a
        // fixed position, beaconed it and named the radio since 2026-10-28, and written the
        // region and the channel since 2026-10-29. Row 0.27 is cited no longer: it is not a
        // limit of this window.
        //
        // That is the ELEVENTH time a line in this repository has announced a gap that was
        // already closed, and the fifth drawn in this very list. The guard in
        // `ChatShellModelTest` did not catch it because it only rejects a limit whose TITLE
        // contains "reading" — the shape, not the claim — so this one sailed through while
        // saying something stronger. The test is widened in the same commit.
        //
        // What remains REPL-only is real: a sync checkpoint deletes server archive items
        // other devices may not have consumed, and account deletion has no window affordance
        // at all (iOS has `AccountDeletionView.swift`; this client has only `/deleteaccount`).
        Limit(
            "Sync checkpointing",
            "PARITY.md rows 0.25, 0.24, 0.11. Account now exposes the non-destructive " +
                "V2 contact-archive push and pull actions, with the result stated there. A sync " +
                "checkpoint remains REPL-only because it deletes server archive items other devices " +
                "may not have consumed. The local scheduled-delivery queue is visible under More, " +
                "including its due time and status. It can schedule, edit and cancel known unblocked " +
                "direct contacts and groups this account belongs to. A sync checkpoint is the only " +
                "REPL-only action left here. Deleting the account is NOT: it has its own screen " +
                "under More, and so does the radio.",
        ),
        Limit(
            "Localisation",
            "PARITY.md row 1.5. 34 catalogs ship and the window reaches 41 shared keys from " +
                "them. Desktop-only copy remains English by design, so that measured coverage " +
                "does not mean every sentence in this pane is translated.",
        ),
    )
}
