package com.oshi.desktop.ui

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.net.V2Http
import com.oshi.desktop.store.DesktopPaths
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
 * inbound messages arrive at all. There is no push on desktop (PARITY.md row 2.3), so
 * **closing this window stops delivery**; the account pane says so on screen.
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
    val server = flag(args, "--server") ?: V2Http.defaultBaseUrl()
    val name = flag(args, "--name") ?: OshiClient.defaultDisplayName()
    val verbose = args.contains("--verbose")
    val withMesh = args.contains("--mesh")
    val passphrase = System.getenv("OSHI_PASSPHRASE")?.toCharArray()

    val store = SecretStore.detect()
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
        System.err.println("Cannot open this account:\n${e.message}")
        return
    }

    println("OSHI desktop — window (PARITY.md row 1.1, first slice)")
    println("  address : ${client.address}")
    println("  data    : ${home.absolutePath}")
    println("  keys    : ${store?.id ?: if (passphrase != null) "passphrase (no OS key store here)" else "NONE"}")
    println("  relay   : $server")
    println("  mesh    : ${if (withMesh) "on" else "off (pass --mesh to enable; this window shows nothing from it)"}")
    println("  The REPL is still the full surface: ./oshi.sh run --args=\"--client\"")

    // A fresh account opens onto nothing — no phone number, no directory, no way to find
    // anybody. The phones answer that by seeding one conversation with the account that
    // built OSHI; this is the same seed, the same address and the same words, and it runs
    // BEFORE the window so the first frame already has something in it.
    if (com.oshi.desktop.app.WelcomeSeeder.seedIfNeeded(
            home = home,
            selfAddress = client.address,
            contacts = client.contacts,
            messages = client.messages,
        )
    ) {
        println("  welcome : seeded a first conversation with ${com.oshi.desktop.app.WelcomeSeeder.AUTHOR_ALIAS}")
    }

    client.start(withMesh = withMesh)
    try {
        runDesktopUi(client)
    } finally {
        client.stop()
    }
}

private fun flag(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
