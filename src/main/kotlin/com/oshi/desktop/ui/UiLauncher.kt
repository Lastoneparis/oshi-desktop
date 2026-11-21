package com.oshi.desktop.ui

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.net.V2Http
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.i18n.t
import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.store.SecretStore
import java.io.File

/**
 * `./oshi.sh run --args="--ui"` — the window. PARITY.md row 1.1.
 *
 * ============================================================ IT IS AN EXTRA DOOR, NOT A REPLACEMENT
 *
 * The REPL (`--client`) is untouched and stays the primary surface. It is what every wiring
 * test drives, it reaches every capability this client has, and this window reaches three of
 * them. A UI that quietly became the only entry point would have retired a tested surface in
 * favour of an untested one — so `--ui` is a sibling branch in [com.oshi.desktop.main], the
 * REPL's code path is not touched by it, and `ChatShellModel.attach` CHAINS the client's
 * inbound callback rather than replacing it.
 *
 * ============================================================ WHAT IT DOES AT STARTUP
 *
 * Exactly what the REPL does: opens the vault (refusing loudly on a wrong or missing master
 * key rather than minting a fresh identity over the old one), then starts the poll loop —
 * which is what publishes a prekey bundle once the rollout gate has been read, and what makes
 * inbound messages arrive at all. There is no push on desktop (PARITY.md row 2.3), so closing
 * the window now HIDES it to the tray/menu bar and delivery continues; only Quit stops it.
 *
 * THE MESH IS OFF HERE, and that is a decision rather than an omission. `--client` takes the
 * mesh up because its `/peers` command and its `[mesh, unreadable]` diagnostics have somewhere
 * to put what it finds. This window displays neither — the shipped mesh carries the LEGACY
 * ratchet (PARITY.md row 0.16), so its payloads are not messages this client can open, and
 * opening mDNS sockets to feed a surface with nowhere to show the result is cost with no
 * benefit. `--ui --mesh` turns it on for anyone who wants the contact-seen side effect.
 */
fun runUiCli(args: Array<String>) {
    val home = flag(args, "--home")?.let { File(it) } ?: DesktopPaths.dataDir
    // __CALL_DEVICES_PER_PROFILE_2026_11_21__ call devices + default voice effect are per profile.
    com.oshi.desktop.call.media.CallDevices.bindProfile(
        home,
        isDefaultProfile = flag(args, "--home") == null && System.getenv("OSHI_HOME").isNullOrBlank(),
    )
    val server = flag(args, "--server") ?: V2Http.defaultBaseUrl()
    val name = flag(args, "--name") ?: OshiClient.defaultDisplayName()
    val verbose = args.contains("--verbose")
    val withMesh = args.contains("--mesh")
    val passphrase = System.getenv("OSHI_PASSPHRASE")?.toCharArray()
    // __OSHI_LINKS_ON_PC_2026_11_20__ the oshi:// link this launch was started for, if any.
    val link = args.firstNotNullOfOrNull { com.oshi.desktop.store.InstanceActivation.acceptableLink(it) }

    // __DESKTOP_HOME_LOCK_2026_10_29__ One process per data directory, BEFORE any store is
    // opened. Two instances doing read-modify-write on one ratchet blob corrupt the session
    // permanently — see [com.oshi.desktop.store.HomeLock] for iOS's own statement of it.
    //
    // The lock is taken here and never released: the kernel drops it when this process ends,
    // which is exactly the behaviour wanted, and an explicit release on a shutdown path would
    // only create a window in which a second instance could start during teardown.
    when (val lock = com.oshi.desktop.store.HomeLock.acquire(home)) {
        is com.oshi.desktop.store.HomeLock.Outcome.Held -> {
            com.oshi.desktop.store.InstanceActivation.listen(home)
            // Opened when the window registers (after first run / the vault, if needed).
            if (link != null) com.oshi.desktop.store.InstanceActivation.request(link)
            ProtocolRegistration.ensureAsync()
        }
        com.oshi.desktop.store.HomeLock.Outcome.Busy -> {
            // __SECOND_LAUNCH_DID_NOTHING_2026_11_19__ The usual case on Windows: the window
            // is hidden in the notification area and this is the Start menu being clicked.
            // Hand the request to the running copy; it shows its window.
            if (com.oshi.desktop.store.InstanceActivation.signal(home, link = link)) return
            // Said plainly, because the person who caused this just double-clicked an icon
            // and is owed an explanation, not a stack trace — and in a WINDOW, because a GUI
            // launch on Windows has no stderr.
            System.err.println(
                "OSHI is already running on this profile (${home.absolutePath}), and it did not answer " +
                    "a request to show its window.",
            )
            runCatching {
                startupFailureWindow(
                    headline = t("desktop.startup.title"),
                    detail = home.absolutePath,
                    whatToDo = t("desktop.startup.already_running"),
                )
            }
            return
        }
        is com.oshi.desktop.store.HomeLock.Outcome.Unsupported -> {
            // Proceed UNPROTECTED rather than refuse: some network filesystems cannot lock,
            // and a client that would not start on such a home directory is broken for a real
            // user in exchange for a hypothetical one. Said once, out loud.
            System.err.println(
                "warning: this filesystem cannot lock ${home.absolutePath} (${lock.reason}); " +
                    "running a second copy on this profile could corrupt it.",
            )
        }
    }

    // __OLED_BLACK_2026_11_16__ Theme before any window, so the first one is already right.
    AppearanceSettings.attach(home)
    // __MERGED_CHATS_2026_11_18__ before the vault opens: "fresh profile" = no key vault yet.
    ChatListSettings.attach(home)
    // __DESKTOP_SETTINGS_2027__ language / text size / default wallpaper, before any window.
    DisplaySettings.attach(home)
    // __DESKTOP_PRIVACY_PARITY_2027__ last seen / photo / contacts-only calls / forward & copy.
    com.oshi.desktop.store.PrivacyPreferences.attach(home)
    // __DESKTOP_APP_LOCK_2027__ read before any window: a set passcode locks the first one.
    com.oshi.desktop.store.AppLock.attach(home)
    // __SCREEN_CAPTURE_PROTECTION_2026_11_20__ before the first window (the splash included).
    com.oshi.desktop.store.ScreenCapturePreference.attach(home)
    WindowCaptureGuard.install()

    // __O_BUBBLE_MASCOT_2026_11_16__ Logo → waving O bubble while the vault opens and the
    // window builds. It closes itself when any window of ours opens; see LaunchSplash.
    if (!args.contains(LoginItem.HIDDEN_FLAG)) LaunchSplash.show()

    val store = SecretStore.detectFor(File(home, KeyVault.FILE_NAME))
    // Recovery happens before OshiClient's loadOrCreate. An existing profile is NEVER
    // overwritten; on an empty profile the first window lets the person explicitly choose
    // between a fresh identity and importing the mobile recovery key. `--restore-identity`
    // remains useful to open the import-only screen directly.
    val restored = if (args.contains("--restore-identity")) {
        val vault = try {
            KeyVault.open(File(home, KeyVault.FILE_NAME), store, passphrase)
        } catch (e: Exception) {
            // __STARTUP_FAILURE_IS_SILENT_2026_11_02__ stderr is invisible to anyone who
            // started this from the applications menu; say it in a window.
            reportStartupFailure(e, home)
            return
        }
        if (IdentityStore.hasAnyAccountMaterial(vault)) {
            System.err.println("This desktop profile already contains identity material; restoration will not overwrite it.")
            return
        }
        if (!restoreIdentityWindow(vault)) return
        true
    } else {
        val vault = try {
            KeyVault.open(File(home, KeyVault.FILE_NAME), store, passphrase)
        } catch (e: Exception) {
            reportStartupFailure(e, home)
            return
        }
        if (IdentityStore.hasAnyAccountMaterial(vault)) false
        else when (firstRunIdentityWindow(vault)) {
            FirstRunIdentityChoice.RESTORED -> true
            FirstRunIdentityChoice.CREATE_NEW -> false
            FirstRunIdentityChoice.CANCELLED -> return
        }
    }
    val client = try {
        OshiClient(
            home = home,
            secretStore = store,
            passphrase = passphrase,
            serverUrl = server,
            displayName = name,
            log = { if (verbose) println("   · $it") },
        )
    } catch (e: Exception) {
        // Same refusal as the REPL, printed in full. A window that swallowed this and came
        // up empty would look like an account with no history rather than an account that
        // could not be opened — which is the difference between "nothing happened yet" and
        // "your identity is behind a key this process does not have."
        reportStartupFailure(e, home)
        return
    }

    // __APP_LOCK_FILE_DELETE_BYPASS_2026_11_20__ the passcode lives in the vault from here on,
    // so deleting app-lock.json cannot open the window. Before any main window is shown.
    com.oshi.desktop.store.AppLock.bindToVault(client.vault)

    println("OSHI desktop — window (PARITY.md row 1.1, first slice)")
    // __STDOUT_IDENTITY_2026_11_20__ the address only with --verbose: a wrapper, a login-item
    // log or a terminal scrollback must not carry the identity by default.
    if (verbose) println("  address : ${client.address}")
    println("  data    : ${home.absolutePath}")
    println("  keys    : ${store?.id ?: if (passphrase != null) "passphrase (no OS key store here)" else "NONE"}")
    println("  relay   : $server")
    println("  mesh    : ${if (withMesh) "on" else "off (pass --mesh to enable; this window shows nothing from it)"}")
    println("  The REPL is still the full surface: ./oshi.sh run --args=\"--client\"")

    // A fresh account opens onto nothing — no phone number, no directory, no way to find
    // anybody. The phones answer that by seeding one conversation with the account that
    // built OSHI; this is the same seed, the same address and the same words, and it runs
    // BEFORE the window so the first frame already has something in it.
    if (!restored && com.oshi.desktop.app.WelcomeSeeder.seedIfNeeded(
            home = home,
            selfAddress = client.address,
            contacts = client.contacts,
            messages = client.messages,
        )
    ) {
        println("  welcome : seeded a first conversation with ${com.oshi.desktop.app.WelcomeSeeder.AUTHOR_ALIAS}")
    }

    // __DESKTOP_CALL_UI_2026_09_23__ The WINDOW always listens for calls (PARITY.md row 2.1): a
    // window that can show an incoming-call screen but never polls would ring nobody. The REPL
    // keeps its explicit `--calls` opt-in unchanged.
    client.start(withMesh = withMesh, callPollIntervalMs = com.oshi.desktop.call.CallLane.POLL_INTERVAL_MS)
    try {
        // `--hidden` is what "Open OSHI at login" registers (LoginItem): start in the tray.
        runDesktopUi(client, startHidden = args.contains(LoginItem.HIDDEN_FLAG))
    } finally {
        client.stop()
    }
}

private fun flag(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

/**
 * __STARTUP_FAILURE_IS_SILENT_2026_11_02__ One place that turns a refusal into
 * something the person can see, read and act on.
 *
 * Still prints to stderr — a terminal launch should keep its output, and so
 * should any log a user pipes to us — and then opens a window, because the
 * normal way to start a desktop app is from a menu where stderr is discarded.
 *
 * The "what to do" line is chosen from the failure, and the key-store case is
 * the one worth naming: on Linux a locked keyring is a routine state, and the
 * answer (set `OSHI_PASSPHRASE`, or unlock the keyring) is something a person
 * can actually carry out.
 */
private fun reportStartupFailure(e: Exception, home: java.io.File) {
    val detail = e.message ?: e.toString()
    System.err.println("Cannot open this account:\n$detail")
    val keyStore = detail.contains("key store", ignoreCase = true) ||
        detail.contains("secret-tool", ignoreCase = true) ||
        detail.contains("keyring", ignoreCase = true) ||
        detail.contains("passphrase", ignoreCase = true)
    // __PC_STARTUP_STRINGS_2026_11_19__ in the person's language, and right for THEIR OS:
    // this used to be English only and talked about a Linux keyring to Windows users.
    val profile = "\n\n" + t("desktop.startup.profile", home.absolutePath)
    val whatToDo = when {
        keyStore && DesktopPaths.isWindows -> t("desktop.startup.keystore.windows")
        keyStore -> t("desktop.startup.keystore.linux")
        else -> t("desktop.startup.generic")
    } + profile
    runCatching {
        startupFailureWindow(
            headline = t("desktop.startup.headline"),
            detail = detail,
            whatToDo = whatToDo,
        )
    }
}
