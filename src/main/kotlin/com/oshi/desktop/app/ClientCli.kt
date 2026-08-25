package com.oshi.desktop.app

import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.SecretStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `./oshi.sh run --args="--client"` — the desktop OSHI client, as a terminal.
 *
 * No UI yet (PARITY.md Tier 1), and this is deliberately not a stand-in for one: it is
 * the smallest thing that exercises the whole stack against the REAL server, so that
 * "does the desktop client work" stops being a question about unit tests. It creates a
 * durable account, publishes a prekey bundle when the rollout gate allows it, polls the
 * relay, stores what arrives, and lists peers found on the local network.
 *
 * Everything it prints, it prints honestly: a send that did not leave says so, a mesh
 * frame is labelled as unreadable rather than shown as a message, and the account's
 * storage location and protection are stated at startup so nobody has to guess where
 * their identity lives.
 */
fun runClientCli(args: Array<String>) {
    val home = argValue(args, "--home")?.let { File(it) } ?: DesktopPaths.dataDir
    val server = argValue(args, "--server")
    val name = argValue(args, "--name")
    val passphrase = System.getenv("OSHI_PASSPHRASE")?.toCharArray()
    val verbose = args.contains("--verbose")

    val store = SecretStore.detect()
    val client = try {
        OshiClient(
            home = home,
            secretStore = store,
            passphrase = passphrase,
            serverUrl = server ?: com.oshi.desktop.net.V2Http.defaultBaseUrl(),
            displayName = name ?: OshiClient.defaultDisplayName(),
            log = { if (verbose) println("   · $it") },
        )
    } catch (e: Exception) {
        // The vault refuses to open on a wrong or missing master key rather than starting
        // a new identity over the top of the old one. That refusal is the feature; print
        // it in full instead of a one-line "failed to start".
        System.err.println("Cannot open this account:\n${e.message}")
        return
    }

    println("=".repeat(78))
    println("OSHI desktop client")
    println("  address    : ${client.address}")
    println("  name       : ${client.displayName}")
    println("  data       : ${home.absolutePath}")
    println("  keys       : ${store?.id ?: if (passphrase != null) "passphrase (no OS key store here)" else "NONE"}")
    println("  server     : ${server ?: com.oshi.desktop.net.V2Http.defaultBaseUrl()}")
    println("=".repeat(78))

    client.onMessage = { m ->
        println("\n<< ${stamp(m.sentAtMs)} ${short(m.senderAddress)}: ${m.content}")
        print("> "); System.out.flush()
    }
    client.onMeshTraffic = { line ->
        // Not a message. The shipped mesh carries the LEGACY ratchet, which this client
        // does not implement (PARITY.md 0.16), so this is a frame we can see and cannot
        // read — and saying so is the whole point of printing it differently.
        if (verbose) println("\n[mesh, unreadable by this client] $line")
    }

    client.start(pollIntervalMs = argValue(args, "--poll")?.toLongOrNull() ?: 3_000)
    Runtime.getRuntime().addShutdownHook(Thread { client.stop() })

    println(
        """
        Commands:
          /whoami                 this account's address, and whether V2 is open to it
          /send <address> <text>  send a message (address may be a prefix of a known one)
          /history <address>      the stored conversation with one peer
          /chats                  every conversation, newest first
          /contacts               known addresses
          /peers                  OSHI devices found on this local network
          /block <address>        stop storing anything from them (reversible)
          /unblock <address>
          /quit
        """.trimIndent()
    )

    while (true) {
        print("> "); System.out.flush()
        val line = readlnOrNull() ?: break
        val input = line.trim()
        try {
            when {
                input == "/quit" -> break
                input.isEmpty() -> {}

                input == "/whoami" -> {
                    println("   ${client.address}")
                    println("   V2 rollout gate: ${client.config.lastDecision}")
                }

                input == "/chats" -> {
                    val chats = client.conversations()
                    if (chats.isEmpty()) println("   (none yet)")
                    chats.forEach {
                        println("   ${short(it.conversationId)}  ${it.messageCount} msg  last ${stamp(it.lastActivityMs)}  ${it.lastMessage?.content?.take(60) ?: ""}")
                    }
                }

                input == "/contacts" -> {
                    val all = client.contacts.all()
                    if (all.isEmpty()) println("   (none yet)")
                    all.forEach {
                        val flags = buildString {
                            if (it.blocked) append(" [blocked]")
                            append(" [${it.verification.wire}]")
                        }
                        println("   ${short(it.address)}  ${it.displayName ?: ""}$flags")
                    }
                }

                input == "/peers" -> {
                    val peers = client.mesh.peers()
                    if (peers.isEmpty()) println("   (none on this network)")
                    peers.forEach { println("   ${it.displayName} [${it.platform}] ${short(it.publicKey)} ${it.host}:${it.port}") }
                }

                input.startsWith("/send ") -> {
                    val rest = input.removePrefix("/send ").trim()
                    val prefix = rest.substringBefore(' ')
                    val text = rest.substringAfter(' ', "")
                    val target = resolve(client, prefix)
                    when {
                        target == null -> println("   no known address starts with '$prefix' (use the full address for a new contact)")
                        text.isEmpty() -> println("   nothing to send")
                        else -> println("   " + when (client.send(target, text)) {
                            OshiClient.SendOutcome.SENT -> "sent"
                            OshiClient.SendOutcome.BLOCKED -> "NOT SENT — that contact is blocked"
                            OshiClient.SendOutcome.NO_V2_PATH -> "NOT DELIVERED — that peer has published no prekey bundle, so V2 cannot reach them"
                            OshiClient.SendOutcome.GATE_CLOSED -> "NOT SENT — the V2 rollout gate is closed for this account"
                        })
                    }
                }

                input.startsWith("/history ") -> {
                    val target = resolve(client, input.removePrefix("/history ").trim())
                    if (target == null) println("   unknown address")
                    else {
                        val rows = client.history(target)
                        if (rows.isEmpty()) println("   (nothing yet)")
                        rows.forEach {
                            val who = if (it.fromMe) "me" else short(it.senderAddress)
                            val state = if (it.fromMe) " (${it.deliveryStatus.wire})" else ""
                            println("   ${stamp(it.sentAtMs)}  $who$state: ${it.content ?: "(deleted)"}")
                        }
                    }
                }

                input.startsWith("/block ") -> {
                    val target = resolve(client, input.removePrefix("/block ").trim())
                    if (target == null) println("   unknown address") else {
                        client.contacts.block(target); println("   blocked ${short(target)} — nothing from them will be stored")
                    }
                }

                input.startsWith("/unblock ") -> {
                    val target = resolve(client, input.removePrefix("/unblock ").trim())
                    if (target == null) println("   unknown address") else {
                        client.contacts.unblock(target); println("   unblocked ${short(target)}")
                    }
                }

                else -> println("   unknown command")
            }
        } catch (e: Exception) {
            println("   ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    client.stop()
    println("stopped.")
}

/**
 * Accept a prefix for convenience, but ONLY when it matches exactly one known address —
 * and never for an address we have never seen, where the full string is the only safe
 * input. An OSHI address is a public key; a prefix that resolves to the wrong contact
 * would send a message to the wrong person, and there is no undo for that.
 */
private fun resolve(client: OshiClient, prefix: String): String? {
    if (prefix.isEmpty()) return null
    val known = (client.contacts.all().map { it.address } + client.mesh.peers().map { it.publicKey }).distinct()
    val matches = known.filter { it.startsWith(prefix) }
    return when {
        matches.size == 1 -> matches.first()
        matches.size > 1 -> null
        // Not known yet: only a complete, well-formed address is accepted.
        prefix.length >= 40 && prefix.endsWith("=") -> prefix
        else -> null
    }
}

private val TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun stamp(ms: Long): String = TIME.format(Instant.ofEpochMilli(ms))

private fun short(address: String): String = address.take(16) + "…"

private fun argValue(args: Array<String>, flag: String): String? {
    val i = args.indexOf(flag)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
