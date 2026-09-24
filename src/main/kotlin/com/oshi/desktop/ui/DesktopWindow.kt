package com.oshi.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.i18n.dt
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.components.AiPane
import com.oshi.desktop.ui.components.ConversationFilter
import com.oshi.desktop.ui.components.ConversationListPane
import com.oshi.desktop.ui.components.DestinationStrip
import com.oshi.desktop.ui.components.Hairline
import com.oshi.desktop.ui.components.MorePane
import com.oshi.desktop.ui.components.NewPane
import com.oshi.desktop.ui.components.PlacesPane
import com.oshi.desktop.ui.components.AccountPane
import com.oshi.desktop.ui.components.CovertPane
import com.oshi.desktop.ui.components.LimitsPane
import com.oshi.desktop.ui.components.Metrics
import com.oshi.desktop.ui.components.NoConversationPane
import com.oshi.desktop.ui.components.ThreadPane
import com.oshi.desktop.ui.components.ViewOnceFacts
import com.oshi.desktop.ui.components.WallpaperStore
import com.oshi.desktop.ui.state.AiConsoleModel
import com.oshi.desktop.ui.state.ChatShellModel
import com.oshi.desktop.ui.state.Destination
import com.oshi.desktop.ui.state.Pane
import com.oshi.desktop.ui.state.PlacesModel
import com.oshi.desktop.ui.state.ShellState
import com.oshi.desktop.ui.state.CallScreenModel
import com.oshi.desktop.ui.components.CallOverlay
import com.oshi.desktop.ui.components.CallVideoFrames
import com.oshi.desktop.ui.components.CallRatingPrompt
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image as SkiaImage

/**
 * The desktop window — PARITY.md rows 1.1 and 1.2. `--ui` launches this.
 *
 * ============================================================ IT IS A SIDEBAR, NOT A TAB BAR
 *
 * VIEWS.md §7 is explicit and this window obeys it: the phone's five-tab `MainTabView`
 * (Chats, AI, New Message, Map, More) is a `UITabBarController` pattern and must NOT be
 * copied. Three of those five destinations do not exist on this client at all (see
 * [com.oshi.desktop.ui.state.DesktopLimits]), and "New Message" is a phone-tab-bar idea — a
 * compose action wearing a destination's clothes. What is here instead is the ordinary
 * desktop-messenger shape: a fixed left column holding the account and the conversation list,
 * a detail pane on the right, and a real menu bar.
 *
 * The LOOK, on the other hand, is transcribed rather than reinvented. `OshiTheme` carries the
 * shipped `Theme.swift` tokens and `~/Desktop/OSHI_Mac_Captures/HABILLE-1-Messages.png` shows
 * what the real build does with them: light, white-surfaced, hairline separators, a single
 * violet used sparingly. A dark glass redesign would not be a better OSHI, it would be a
 * different app.
 *
 * ============================================================ THIS FILE DECIDES ALMOST NOTHING
 *
 * Every string it draws was produced by [ChatShellModel]; every enable/disable is a
 * `ComposerState` it was handed. That is what makes the honesty testable: `ChatShellModelTest`
 * can assert a bot conversation's composer is disabled and that a refused send says so, and
 * those assertions hold here because this window has no opinion of its own to override them.
 *
 * The three things it DOES decide are all in `com.oshi.desktop.ui.components` and all of them
 * are pure Kotlin with a headless test behind them: what a media row may draw
 * (`MediaPresentation`), where a date separator goes (`DayBreaks`), and whether the attach
 * button may exist (`ComposerAffordances`). Anything that could be WRONG is somewhere a JUnit
 * test can reach.
 *
 * ============================================================ DRAFTS DO NOT GO THROUGH THE MODEL
 *
 * `ChatShellModel.publish()` calls `build()`, and `build()` does a `listFiles` over the
 * message directory plus a summary per conversation, then rebuilds the whole `ShellState`.
 * `draft()` publishes. Wiring `onValueChange` to `model.draft` therefore paid for a directory
 * scan and a full state rebuild ON EVERY KEYSTROKE, and it is what the previous version of
 * this file did.
 *
 * It does not any more. The draft lives in [drafts] — a Compose state map keyed by
 * conversation id, so switching away and back keeps what you typed, which the model's own
 * `select()` would have cleared. The model is told exactly twice:
 *
 *   * immediately before `send()`, because `send()` reads `draftText` under the lock and
 *     there is no other way to hand it the text;
 *   * never otherwise.
 *
 * After a send completes, the model is the authority on what survived: `SENT` clears the box,
 * `REFUSED_CONTROL_PAYLOAD` deliberately keeps it (the text was never stored anywhere else,
 * so clearing would destroy it). That is why [BusyWatcher] copies `state.draft` back into
 * [drafts] on the busy → idle edge rather than assuming either outcome.
 *
 * **This does not FIX the defect, it stops feeding it.** `build()` still scans the directory
 * on every inbound message, every selection and every refresh. That is the model's to fix.
 *
 * ============================================================ NOT COVERED BY ANY TEST
 *
 * Nothing drawn in this file or in `ui.components`' composables is exercised by the suite, on
 * purpose — a test that needs a display cannot run on the headless runners this project's
 * Windows and Linux evidence has to come from. The pure files in that package ARE covered.
 * And this window has only ever been OPENED on macOS aarch64: skiko publishes windows-x64,
 * linux-x64 and linux-arm64 natives and this build has loaded none of them.
 */
fun runDesktopUi(client: OshiClient, startHidden: Boolean = false) {
    val notifier = DesktopNotifier()
    val sleepInhibitor = SleepInhibitor()
    val model = ChatShellModel(client, onIncomingNotification = notifier::notifyIncomingMessage)
    model.attach()

    // The two sibling models. They are constructed here rather than inside a composable so
    // their worker threads are owned by the process and not by a recomposition, and so the
    // AI manager is the SAME singleton the REPL's `/ai` drives — one model file, one engine,
    // one history, whichever door the user came in through.
    val ai = AiConsoleModel(com.oshi.desktop.ai.DesktopAi.manager())
    val mail = com.oshi.desktop.ui.state.MailModel(client)
    val mailArrivals = com.oshi.desktop.ui.state.MailArrivalTracker()
    val places = PlacesModel(PlacesModel.defaultDir(client.mediaDir.parentFile))

    // `OshiClient.home` is private; `mediaDir` is `File(home, "media")` and public, so this is
    // the client's real home even under `--home`, not `DesktopPaths.dataDir` guessed at again.
    val wallpapers = WallpaperStore(File(client.mediaDir.parentFile, WallpaperStore.FILE_NAME))

    // __DESKTOP_CALL_UI_2026_09_23__ Voice calls in the window (PARITY.md row 2.1). The lane
    // polls from `client.start` (UiLauncher turns it on for the window); this model turns its
    // events into a screen, a ring and a history row. `mutableStateOf` outside composition is
    // snapshot state like any other, so the poll thread can write it.
    val callTones = CallTones()
    val callScreen = mutableStateOf<CallScreenModel.CallScreen?>(null)
    val ratingAsk = mutableStateOf<com.oshi.desktop.call.CallRatingClient.Pending?>(null)
    val calls = CallScreenModel(
        lane = client.calls,
        labelFor = { addr -> client.contacts.get(addr)?.label(com.oshi.desktop.app.short(addr)) ?: com.oshi.desktop.app.short(addr) },
        ringer = callTones,
        recordSummary = { peer, content, outgoing ->
            // The phones' own call-history row, written locally (see CallSummary), then shown.
            runCatching {
                client.messages.append(
                    com.oshi.desktop.store.Message(
                        id = java.util.UUID.randomUUID().toString(),
                        conversationId = peer,
                        senderAddress = if (outgoing) client.address else peer,
                        recipientAddress = if (outgoing) peer else client.address,
                        fromMe = outgoing,
                        content = content,
                        sentAtMs = System.currentTimeMillis(),
                        sentAtSource = com.oshi.desktop.store.TimestampSource.LOCAL_CLOCK,
                        deliveryStatus = com.oshi.desktop.store.DeliveryStatus.DELIVERED,
                        transport = "local",
                    )
                )
            }
            model.refresh()
        },
        afterCall = { callId, peer, seconds, normally ->
            client.callRating.callDidEnd(callId, peer, seconds, normally)?.let { ratingAsk.value = it }
        },
        // __CALL_PARITY_2026_09_23__ missed-call notification when no OSHI window is active,
        // and the machine held awake for exactly as long as a call is connected.
        onMissedCall = { _, _ ->
            if (java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow == null) {
                notifier.notifyMissedCall(dt("desktop.call.missed.notification"))
            }
        },
        onCallLive = { live -> if (live) sleepInhibitor.hold() else sleepInhibitor.release() },
    )
    calls.onChange = { callScreen.value = it }
    run {
        val previous = client.onCall
        client.onCall = { event -> previous(event); calls.onEvent(event) }
    }

    application {
        val quit: () -> Unit = { exitApplication() }
        // __DESKTOP_BACKGROUND_2026_09_23__ Closing the window HIDES it while a tray/menu-bar
        // icon (or the macOS Dock) can bring it back, so calls and messages keep arriving —
        // there is no push to wake a process that is not running (PARITY.md row 2.3).
        var windowVisible by remember { mutableStateOf(!startHidden) }
        var keepsRunning by remember { mutableStateOf(false) }
        var state by remember { mutableStateOf(model.state) }
        var aiState by remember { mutableStateOf(ai.state) }
        var mailState by remember { mutableStateOf(mail.state) }
        var placesState by remember { mutableStateOf(places.state) }

        DisposableEffect(model, mail) {
            // The poll thread writes this; Compose snapshot state is safe to write from any
            // thread and schedules its own recomposition, so there is no invokeLater here.
            model.onChange = { state = it }
            ai.onChange = { aiState = it }
            places.onChange = { placesState = it }
            val stopObservingMail = mail.observe {
                if (mailArrivals.onSnapshot(it)) notifier.notifyIncomingMessage()
                mailState = it
            }
            keepsRunning = notifier.install(
                openLabel = dt("desktop.background.open"),
                quitLabel = dt("desktop.background.quit"),
                onOpen = { windowVisible = true },
                onQuit = quit,
            ) || LoginItem.os() == LoginItem.Os.MAC
            if (!keepsRunning) windowVisible = true
            // macOS: clicking the Dock icon of a hidden OSHI brings the window back.
            runCatching {
                java.awt.Desktop.getDesktop().addAppEventListener(java.awt.desktop.AppReopenedListener { windowVisible = true })
            }
            model.refresh()
            // Match iOS's ChatsTabView launch task: warm the sealed mailbox before the
            // user opens Mail. MailModel performs its blocking account/inbox requests on
            // its own worker, and MailPane observes the resulting snapshot when mounted.
            mail.refresh()
            onDispose {
                model.onChange = {}
                ai.onChange = {}
                places.onChange = {}
                stopObservingMail()
                notifier.close()
                model.close()
            }
        }

        // Desktop has no APNs/FCM mail wake. Once the initial account + inbox snapshot
        // proves this identity owns a mailbox, poll it while the process is alive so the
        // menu's unread count can change without opening Mail. A no-mailbox identity is
        // never retried in a loop: only this client can claim that address.
        LaunchedEffect(mail, mailState.hasMailbox, mailState.loading) {
            if (!mailState.hasMailbox || mailState.loading) return@LaunchedEffect
            while (true) {
                delay(MAIL_POLL_INTERVAL_MS)
                mail.refresh()
            }
        }

        Window(
            onCloseRequest = { if (keepsRunning) windowVisible = false else quit() },
            visible = windowVisible,
            title = "${t("identity.app_name")} — ${client.displayName}",
            // The shipped app icon, off the CLASSPATH — so the packaged .exe shows OSHI in
            // the Windows taskbar and in alt-tab rather than the JVM's default coffee cup.
            // This is the RUNNING window's icon and is a separate thing from jpackage's
            // `--icon`, which dresses the launcher, the Start menu and Add/Remove Programs:
            // setting only one of the two leaves the other stock, which is how an app ends
            // up branded in the Start menu and generic once it is open.
            icon = oshiWindowIcon(),
            state = rememberWindowState(width = 1180.dp, height = 780.dp),
        ) {
            MenuBar {
                // `identity.app_name` rather than the literal "OSHI": the brand is the same
                // word in all 34 catalogs today, and reading it from the catalog is what makes
                // a future script-specific spelling (it is a key the phones already own) reach
                // this menu without anyone remembering this line exists.
                Menu(t("identity.app_name")) {
                    val unreadMail = mailState.badgeCount(com.oshi.desktop.ui.state.MailModel.FOLDER_INBOX)
                    val mailLabel = if (unreadMail == 0) t("mail.title") else "${t("mail.title")} ($unreadMail)"
                    Item(mailLabel, onClick = { model.show(Pane.MAIL); model.go(Destination.MESSAGES) })
                    Item(dt("desktop.nav.account"), onClick = { model.show(Pane.ACCOUNT) })
                    Item(dt("desktop.nav.limits"), onClick = { model.show(Pane.LIMITS) })
                    Separator()
                    Item(dt("desktop.menu.quit"), onClick = quit)
                }
                Menu(dt("desktop.menu.conversation")) {
                    Item(dt("desktop.menu.refreshFromDisk"), onClick = { model.refresh() })
                    Item(dt("desktop.menu.closeConversation"), onClick = { model.select(null) })
                }
            }
            // An incoming call raises the window and — when it is not the focused one — posts
            // the metadata-free notification, once per call.
            val incomingCallId = callScreen.value?.takeIf { it.phase == CallScreenModel.Phase.INCOMING }?.callId
            LaunchedEffect(incomingCallId) {
                if (incomingCallId == null) return@LaunchedEffect
                if (!window.isFocused || !windowVisible) notifier.notifyIncomingCall(t("call.incoming.voice"))
                // Hidden in the tray: the small ringing card below answers for it.
                if (!windowVisible) return@LaunchedEffect
                window.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
            LaunchedEffect(calls) {
                while (true) { calls.tick(); delay(1_000) }
            }
            MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                Box(Modifier.fillMaxSize()) {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) {
                    Shell(
                        state = state,
                        model = model,
                        client = client,
                        wallpapers = wallpapers,
                        ai = ai,
                        aiState = aiState,
                        mail = mail,
                        places = places,
                        placesState = placesState,
                        // AWT's chooser, not a hand-rolled one: it is the dialog the user's
                        // desktop already uses, it knows about network shares and drives, and
                        // it is the only file picker available without a new dependency. It
                        // BLOCKS the AWT thread, which is correct — a modal file dialog is
                        // supposed to.
                        onPickFile = { pickFile(window) },
                        onCall = { peer, video -> calls.call(peer, video) },
                    )
                }
                callScreen.value?.let { screen ->
                    if (screen.minimized) {
                        com.oshi.desktop.ui.components.CallMiniBar(
                            screen, onRestore = { calls.setMinimized(false) }, onHangUp = calls::hangUp,
                            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
                        )
                    } else CallOverlay(
                        screen = screen,
                        onAnswer = calls::answer,
                        onDecline = calls::decline,
                        onHangUp = calls::hangUp,
                        onToggleMute = calls::toggleMute,
                        onDismiss = calls::dismiss,
                        video = { client.calls.video()?.let(::CallVideoFrames) },
                        onToggleCamera = calls::toggleCamera,
                        onAnswerVideoRequest = calls::answerVideoRequest,
                        onMinimize = { calls.setMinimized(true) },
                        onSwitchCamera = calls::switchCamera,
                    )
                }
                if (callScreen.value == null) ratingAsk.value?.let { pending ->
                    CallRatingPrompt(
                        onSubmit = { stars ->
                            Thread({ runCatching { client.callRating.submit(stars) } }, "oshi-call-rating").apply { isDaemon = true }.start()
                            ratingAsk.value = null
                        },
                        onNotNow = { client.callRating.dismiss(); ratingAsk.value = null },
                    )
                }
                }
            }
        }

        // __DESKTOP_BACKGROUND_2026_09_23__ The window is closed to the tray: ring in a small card.
        callScreen.value?.takeIf { it.phase == CallScreenModel.Phase.INCOMING && !windowVisible }?.let { ringing ->
            IncomingCallWindow(
                screen = ringing,
                onAnswer = { calls.answer(); windowVisible = true },
                onDecline = calls::decline,
            )
        }
    }
}

/** Mail's polling equivalent of iOS's push wake; bounded by the process lifetime above. */
private const val MAIL_POLL_INTERVAL_MS = 30_000L

@Composable
private fun Shell(
    state: ShellState,
    model: ChatShellModel,
    client: OshiClient,
    wallpapers: WallpaperStore,
    ai: AiConsoleModel,
    aiState: AiConsoleModel.AiState,
    mail: com.oshi.desktop.ui.state.MailModel,
    places: PlacesModel,
    placesState: PlacesModel.PlacesState,
    onPickFile: () -> File?,
    onCall: ((String, Boolean) -> Unit)? = null,
) {
    // See DRAFTS above. Keyed by conversation so a switch does not lose what was typed.
    val drafts: SnapshotStateMap<String, String> = remember { mutableStateMapOf() }
    var query by remember { mutableStateOf("") }
    // Session-local: revealing a view-once row is not persisted, because nothing in the store
    // records it (`viewOnceOpened` is written by nobody) and inventing a persisted "opened"
    // would claim bookkeeping this client does not do.
    val revealed = remember { mutableStateMapOf<String, Unit>() }
    // `LocalDate.now()` once per composition pass rather than per row; a window left open
    // across midnight will relabel on its next recomposition, which any message causes.
    val today = remember(state) { LocalDate.now() }

    var half by remember { mutableStateOf(ConversationFilter.Half.MESSAGES) }

    BusyWatcher(state, drafts)

    Column(Modifier.fillMaxSize().background(OshiTheme.background)) {
        // The shipped window's own top strip. It sits ABOVE the split, not inside the
        // sidebar, because that is where the capture puts it and because a destination
        // control nested in one half of a split view reads as belonging to that half.
        Box(Modifier.fillMaxWidth().background(OshiTheme.background).padding(vertical = OshiTheme.sm)) {
            DestinationStrip(
                options = destinationLabels(),
                selectedIndex = DESTINATIONS.indexOf(state.destination),
                onSelect = { model.go(DESTINATIONS[it]) },
            )
        }
        Hairline()

        when (state.destination) {
            Destination.NEW -> NewPane(
                state = state,
                onStartConversation = { model.startConversation(it) },
                onCopy = { copyToClipboard(it) },
                onPickQrImage = onPickFile,
            )
            Destination.AI -> AiPane(
                state = aiState,
                onUseModel = { ai.useModel(it) },
                onAsk = { ai.ask(it) },
                onForget = { ai.forget() },
            )
            Destination.PLACES -> PlacesPane(
                state = placesState,
                onKind = { places.setKind(it) },
                onQuery = { places.setQuery(it) },
                onSearch = { places.search() },
            )
            Destination.MORE -> MorePane(
                state = state,
                onShow = { model.show(it); model.go(Destination.MESSAGES) },
                onSelect = { model.select(it); model.go(Destination.MESSAGES) },
                onSetBlocked = { address, blocked -> model.setBlocked(address, blocked) },
                onRename = { address, name -> model.renameContact(address, name) },
                onVerifySafetyNumber = { address, payload -> model.verifySafetyNumber(address, payload) },
                onCopy = { copyToClipboard(it) },
                // __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ the dialog blocks the AWT thread, as a
                // modal should; the export/import itself runs on the model's worker.
                onExportMessages = {
                    com.oshi.desktop.ui.components.pickMessageExportTarget()?.let { model.exportMessages(it) }
                },
                onImportMessages = {
                    com.oshi.desktop.ui.components.pickMessageExportSource()?.let { model.importMessages(it) }
                },
                // __SHARED_NICKNAME_2026_09_22__ all three run on the model's worker.
                onSetNickname = { model.setOwnNickname(it) },
                onPullNickname = { model.pullNicknameFromPhone() },
                onPushNickname = { model.pushNicknameToPhone() },
                // __CALL_LOG_AT_REST_2026_09_23__ modal dialog on the AWT thread, work on the worker.
                onExportCallDiagnostics = {
                    com.oshi.desktop.ui.components.pickCallDiagnosticsTarget()?.let { model.exportCallDiagnostics(it) }
                },
            )
            Destination.MESSAGES -> MessagesDestination(
                state = state,
                model = model,
                client = client,
                wallpapers = wallpapers,
                mail = mail,
                drafts = drafts,
                query = query,
                onQuery = { query = it },
                half = half,
                onHalf = { half = it },
                revealed = revealed,
                today = today,
                onPickFile = onPickFile,
                onCall = onCall,
            )
        }
    }
}

/** The phone's tab order, and the labels the strip draws. Kept side by side so they cannot drift. */
private val DESTINATIONS = listOf(
    Destination.MESSAGES, Destination.AI, Destination.NEW, Destination.PLACES, Destination.MORE,
)

/**
 * The strip's labels, resolved on every call rather than held in a `val`.
 *
 * A top-level `private val` of `t(…)` would be evaluated once, at class initialisation, which
 * on the JVM is whenever this file is first touched — possibly before anything has decided
 * what `Strings.activeTag` is, and certainly never again afterwards. That is how a language
 * preference ends up applying to the whole window except its navigation. Four of the five are
 * the phone's own tab titles; `AI` is the one the iOS catalogs have no key for at all (there
 * is `ai.shortcuts.title` = "AI Assistant", which does not fit a 28dp segment).
 */
internal fun destinationLabels(): List<String> = listOf(
    t("tab.messages"),
    dt("desktop.destination.ai"),
    t("tab.new"),
    t("map.search.section.places"),
    t("tab_more"),
)

@Composable
private fun MessagesDestination(
    state: ShellState,
    model: ChatShellModel,
    client: OshiClient,
    wallpapers: WallpaperStore,
    mail: com.oshi.desktop.ui.state.MailModel,
    drafts: SnapshotStateMap<String, String>,
    query: String,
    onQuery: (String) -> Unit,
    half: ConversationFilter.Half,
    onHalf: (ConversationFilter.Half) -> Unit,
    revealed: SnapshotStateMap<String, Unit>,
    today: LocalDate,
    onPickFile: () -> File?,
    onCall: ((String, Boolean) -> Unit)? = null,
) {
    // The header's reach badge and incoming-share counter depend on the CLOCK as well as on
    // events (a peer drop outlasts its grace, a LoRa node ages out, a share ends), so they are
    // sampled on a tick rather than pushed. One second is the badge's resolution; the read is
    // two map scans and a ConcurrentHashMap size.
    var badge by remember { mutableStateOf(com.oshi.desktop.ui.state.NetworkBadge.OFFLINE) }
    var incomingShares by remember { mutableStateOf(emptyList<com.oshi.desktop.ui.state.IncomingShare>()) }
    LaunchedEffect(model) {
        while (true) {
            val now = System.currentTimeMillis()
            val (b, shares) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                model.networkBadge(now) to model.incomingLiveShares(now)
            }
            // Assigning an EQUAL value is a no-op for a state read, so an unchanged header
            // does not recompose once a second.
            badge = b
            incomingShares = shares
            kotlinx.coroutines.delay(1_000)
        }
    }
    Row(Modifier.fillMaxSize().background(OshiTheme.background)) {
        ConversationListPane(
            state = state,
            query = query,
            onQuery = onQuery,
            half = half,
            onHalf = onHalf,
            onSelect = { model.select(it) },
            onShow = { model.show(it) },
            onCreateGroup = { name, members -> model.createGroup(name, members) },
            today = today,
            badge = badge,
            incomingShares = incomingShares,
        )
        Box(Modifier.width(Metrics.hairline).fillMaxHeight().background(OshiTheme.separator))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (state.pane) {
                Pane.MAIL -> com.oshi.desktop.ui.components.MailPane(mail, onPickFile)
                Pane.ACCOUNT -> AccountPane(
                    state = state,
                    onCopyAddress = { copyToClipboard(it) },
                    onDeliveryReceipts = { model.setReceiptPrivacy(delivery = it) },
                    onReadReceipts = { model.setReceiptPrivacy(read = it) },
                    onSyncPush = model::syncPushContacts,
                    onSyncPull = model::syncPull,
                )
                Pane.LIMITS -> LimitsPane()
                // __DEVSYNC_DIRECT_2026_09_22__
                Pane.DEVICES -> com.oshi.desktop.ui.components.LinkedDevicesPane(client.devSync, client.deviceMailbox)
                // `remember(client)` and not a fresh instance per recomposition: CovertText
                // holds the identity private key, and a composable is re-entered freely.
                Pane.COVERT -> {
                    val covert = remember(client) {
                        com.oshi.desktop.covert.CovertText(client.identity.identity.priv)
                    }
                    CovertPane(covert, state.contacts, onCopy = { text -> copyToClipboard(text) })
                }
                Pane.SCHEDULED -> com.oshi.desktop.ui.components.ScheduledPane(
                    rows = state.scheduled,
                    contacts = state.contacts,
                    groups = state.conversations.filter { it.kind == com.oshi.desktop.ui.state.ConversationKind.GROUP },
                    busy = state.busy,
                    onSchedule = model::scheduleMessage,
                    onEdit = model::editScheduled,
                    onCancel = model::cancelScheduled,
                )
                Pane.CONVERSATION -> {
                    val thread = state.thread
                    if (thread == null) {
                        NoConversationPane(state)
                    } else {
                        val facts = rememberViewOnceFacts(client, thread.conversationId, thread.messages.size)
                        var wallpaper by remember(thread.conversationId) {
                            mutableStateOf(wallpapers.get(thread.conversationId))
                        }
                        ThreadPane(
                            thread = thread,
                            notice = state.notice,
                            busy = state.busy,
                            wallpaper = wallpaper,
                            onPickWallpaper = {
                                wallpaper = it
                                wallpapers.set(thread.conversationId, it)
                            },
                            draft = drafts[thread.conversationId].orEmpty(),
                            onDraft = {
                                drafts[thread.conversationId] = it
                                model.typingChanged(thread.conversationId, it.isNotBlank())
                            },
                            onSend = {
                                // The ONLY place the model hears about the draft.
                                model.typingChanged(thread.conversationId, false)
                                model.draft(drafts[thread.conversationId].orEmpty())
                                model.send()
                            },
                            onAttach = { onPickFile()?.let { model.attach(it) } },
                            onVoiceNote = { model.attach(it.file, it.mediaType, discardAfterSend = true) },
                            onGif = { model.attach(it, com.oshi.desktop.store.MediaType.IMAGE) },   // __GIF_PACK_2026_09_23__
                            // __VIDEO_NOTE_2026_09_24__ the sealed MP4 stays as our own bubble (not discarded).
                            onVideoNote = { file, meta -> model.attach(file, com.oshi.desktop.store.MediaType.VIDEO, videoNote = meta) },
                            onRenameGroup = { model.renameGroup(thread.conversationId, it) },
                            onReact = model::react,
                            onEditMessage = model::editMessage,
                            onDeleteMessage = model::deleteMessage,
                            onAddGroupMember = { model.addGroupMember(thread.conversationId, it) },
                            onRemoveGroupMember = { model.removeGroupMember(thread.conversationId, it) },
                            onSetGroupMemberAdmin = { member, admin -> model.setGroupMemberAdmin(thread.conversationId, member, admin) },
                            viewOnce = facts,
                            revealed = revealed.keys,
                            onReveal = { revealed[it] = Unit },
                            today = today,
                            onCall = onCall?.let { call -> { video -> call(thread.conversationId, video) } },
                            onLeaveGroup = { model.leaveGroup(thread.conversationId) },
                            onReply = model::beginReply,
                            replyingTo = state.replyingTo,
                            onCancelReply = model::cancelReply,
                            onSetGroupPicture = { onPickFile()?.let { model.setGroupPicture(thread.conversationId, it) } },
                            onRemoveGroupPicture = { model.removeGroupPicture(thread.conversationId) },
                            onSetGroupMuted = { model.setGroupMuted(thread.conversationId, it) },
                            onSetGroupDescription = { model.setGroupDescription(thread.conversationId, it) },
                            onSetGroupBlocked = { model.setGroupBlocked(thread.conversationId, it) },
                            onDeleteGroup = { model.deleteGroup(thread.conversationId) },
                            onPin = model::pinMessage,
                            onDeleteForMe = model::deleteMessageForMe,
                            onForward = model::forwardMessage,
                            onCopy = { copyToClipboard(it) },
                            onPickMention = model::pickMention,   // __MENTIONS_2026_09_23__
                            // __DESKTOP_REPORT_2026_09_23__ contacts and groups only (not bots / radio).
                            onReport = { reason, details, alsoBlock ->
                                model.report(thread.conversationId, thread.kind == com.oshi.desktop.ui.state.ConversationKind.GROUP, reason, details, alsoBlock)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Copies the model's post-send draft back into local state on the busy → idle edge.
 *
 * `send()` sets `busy` true synchronously and clears it on the worker when the outcome is
 * known, and only THEN is `state.draft` authoritative: `SENT` emptied it, and
 * `REFUSED_CONTROL_PAYLOAD` kept the user's text on purpose. Adopting it at that instant is
 * what makes a local draft agree with the model without a keystroke ever reaching it.
 */
@Composable
private fun BusyWatcher(state: ShellState, drafts: SnapshotStateMap<String, String>) {
    var wasBusy by remember { mutableStateOf(false) }
    // In an effect, not in the composition body: writing snapshot state while composing is
    // how a recomposition loop starts, and this writes on exactly one edge per send.
    LaunchedEffect(state.busy) {
        val id = state.selectedId
        if (wasBusy && !state.busy && id != null) drafts[id] = state.draft
        wasBusy = state.busy
    }
}

/**
 * The view-once lookup, rebuilt only when the conversation or its length changes.
 *
 * See [ViewOnceFacts] for why the window reads the store here at all. `MessageStore.messages`
 * is served from an in-memory per-conversation log, so this is a pass over a list that is
 * already resident — not the directory scan `ChatShellModel.build()` performs — and it happens
 * on a conversation switch or an arriving message, never per frame and never per keystroke.
 */
@Composable
private fun rememberViewOnceFacts(client: OshiClient, conversationId: String, count: Int): ViewOnceFacts {
    val ids: Set<String> = remember(conversationId, count) {
        runCatching {
            client.messages.messages(conversationId).filter { it.isViewOnce }.map { it.id }.toSet()
        }.getOrDefault(emptySet())
    }
    return remember(ids) { ViewOnceFacts.of(ids) }
}

/**
 * The account pane's copy button.
 *
 * AWT's clipboard, not a Compose one: this window already runs on the AWT toolkit and a
 * failure here must not take a click handler down — a headless or locked clipboard throws.
 */
private fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

/**
 * The app icon for the running window, read off the classpath.
 *
 * `src/main/resources/branding/oshi-256.png` is the shipped iOS/macOS icon resampled —
 * NOT a desktop-only mark. It is read through `getResourceAsStream` rather than from a
 * file for the same reason the i18n catalogs are: the packaged `.msi` has no `src/`
 * directory, so anything loaded off disk here would work on a developer's machine and
 * show nothing at all once installed.
 *
 * NULL-SAFE ON PURPOSE. A missing or unreadable resource gives the JVM's default icon,
 * which is exactly what this window had before — a branding asset must never be the
 * reason a messenger fails to open.
 */
@Composable
private fun oshiWindowIcon(): Painter? = remember {
    runCatching {
        val bytes = ChatShellModel::class.java.getResourceAsStream("/branding/oshi-256.png")
            ?.use { it.readBytes() } ?: return@runCatching null
        BitmapPainter(SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap())
    }.getOrNull()
}

/**
 * The platform file chooser.
 *
 * `java.awt.FileDialog` rather than Swing's `JFileChooser` on purpose: FileDialog is the
 * NATIVE dialog on Windows and macOS — the one the user's other applications open, with
 * their own places, recent folders and network locations — while JFileChooser draws a
 * Swing-looking box that matches nothing on the machine. On Linux it falls back to an
 * AWT-drawn dialog, which is the same trade every JVM application makes there.
 *
 * Cancel returns null and the caller does nothing. `file` being null after a cancel is the
 * documented contract, and the `directory + file` join is required: `getFile()` alone is a
 * bare name relative to a directory the process is not in.
 */
private fun pickFile(owner: java.awt.Frame): File? {
    val dialog = java.awt.FileDialog(owner, t("input.attach_file"), java.awt.FileDialog.LOAD)
    dialog.isMultipleMode = false
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return File(dir, name).takeIf { it.isFile }
}
