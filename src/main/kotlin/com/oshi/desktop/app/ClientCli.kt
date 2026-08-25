package com.oshi.desktop.app

import com.oshi.desktop.group.GroupType
import com.oshi.desktop.pairing.ContactQr
import com.oshi.desktop.pairing.QrMatrix
import com.oshi.desktop.place.LiveState
import com.oshi.desktop.scheduled.ScheduledMessage
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
 *
 * **The commands themselves live in [ClientCommands], not here.** This function owns
 * stdin, stdout and the lifecycle; the dispatcher owns the behaviour, and it is a plain
 * function of (client, line, sink) so a test can prove a command reaches its package
 * without a terminal. A REPL whose only entry point is `readlnOrNull()` is a REPL nothing
 * can test.
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
        val what = m.mediaRef?.let { "[${m.mediaType?.wire ?: "file"}] ${m.content} → $it" } ?: m.content
        println("\n<< ${stamp(m.sentAtMs)} ${short(m.senderAddress)}: $what")
        print("> "); System.out.flush()
    }
    client.onMeshTraffic = { line ->
        // Not a message. The shipped mesh carries the LEGACY ratchet, which this client
        // does not implement (PARITY.md 0.16), so this is a frame we can see and cannot
        // read — and saying so is the whole point of printing it differently.
        if (verbose) println("\n[mesh, unreadable by this client] $line")
    }
    client.onControl = { from, event, outcome ->
        // Silent by construction — see ControlPrefix.Kind.SILENT. Printed only in verbose
        // mode, and printed as an EVENT, never as a message body.
        if (verbose) println("\n[control] ${short(from)} ${event.javaClass.simpleName} → $outcome")
    }
    client.onPlace = { from, event ->
        println("\n<< ${short(from)} ${event.javaClass.simpleName}")
        print("> "); System.out.flush()
    }
    client.onGroupEvent = { r ->
        println("\n[group ${r.groupId?.take(8) ?: "?"}…] ${r.outcome}: ${r.detail}")
        print("> "); System.out.flush()
    }
    client.onScheduledRun = { run ->
        val late = if (run.wasLate) " — ${run.lateByMs / 1000}s LATE (this client was not running when it came due)" else ""
        println("\n[scheduled] sent ${run.sent}, deferred ${run.deferred}, failed ${run.failed}$late")
        print("> "); System.out.flush()
    }

    client.start(pollIntervalMs = argValue(args, "--poll")?.toLongOrNull() ?: 3_000)
    Runtime.getRuntime().addShutdownHook(Thread { client.stop() })

    println(ClientCommands.HELP)

    while (true) {
        print("> "); System.out.flush()
        val line = readlnOrNull() ?: break
        if (!ClientCommands.execute(client, line.trim()) { println(it) }) break
    }
    client.stop()
    println("stopped.")
}

/**
 * Every REPL command, as a pure function of (client, line, output sink).
 *
 * Split out of [runClientCli] for one reason: a command that cannot be invoked without a
 * terminal cannot be tested, and the whole point of this pass is that each package has a
 * PATH from the app, not merely an API. `execute` is what the wiring tests call.
 *
 * @return false when the caller should stop reading input (`/quit`).
 */
object ClientCommands {

    val HELP: String = """
        Commands:
          /whoami                            this account's address, and whether V2 is open to it
          /reach <address>                   can V2 reach this peer right now
          /send <address> <text>             send a message (address may be a prefix of a known one)
          /sendfile <address> <path>         encrypt a file, upload it, send the key message
          /card <address> <contact>          share one contact with another, as a contact card
          /history <address>                 the stored conversation with one peer
          /chats                             every conversation, newest first
          /contacts                          known addresses
          /blocked                           conversations with blocked peers — withheld from /chats
          /peers                             OSHI devices found on this local network
          /block <address>                   stop storing anything from them (reversible)
          /unblock <address>                 unblocks EVERY base64 spelling of that key
          /react <address> <msgId> <emoji>   add a reaction
          /unreact <address> <msgId> <emoji> remove yours
          /edit <address> <msgId> <text>     edit one of YOUR messages, for everyone
          /delete <address> <msgId>          delete one of YOUR messages, for everyone
          /typing <address> on|off           typing indicator (ephemeral, never stored)
          /read <address>                    send a read receipt for this conversation
          /qr [png <path>]                   this account's pairing code, as text or a PNG
          /scan <payload>                    accept a scanned/pasted code
          /safety <address>                  the safety number binding your key to theirs
          /verify <address> <payload>        check a scanned safety-number code, exactly
          /groups                            groups this account is in
          /group new <name> <address>...     create a group and broadcast it
          /group send <groupId> <text>       fan a message out to every member
          /group add|remove <groupId> <addr> change the roster (admins, or any member of an open group)
          /group rename <groupId> <name>
          /group roster <groupId> <address>  ask ONE member for their copy of the roster
          /group sync <address>              ask a peer for every group they think we are in
          /live                              live location shares currently tracked
          /schedule <address> <when> <text>  queue a message; <when> is +15m / +2h / +1d / epoch-ms
          /scheduled [address]               the queue
          /cancel <id>                       cancel a scheduled message
          /sync push|pull|status             the V2 archive (multi-device)
          /sync checkpoint <seq>             let the server drop everything up to <seq>
          /sync legacy push|pull             the legacy /api/sync alias map
          /quit
    """.trimIndent()

    fun execute(client: OshiClient, input: String, out: (String) -> Unit): Boolean {
        try {
            when {
                input == "/quit" -> return false
                input.isEmpty() -> {}
                input == "/help" -> out(HELP)

                input == "/whoami" -> {
                    out("   ${client.address}")
                    out("   V2 rollout gate: ${client.config.lastDecision}")
                }

                input.startsWith("/reach ") -> withPeer(client, input, "/reach ", out) { peer ->
                    out(
                        if (client.canReach(peer)) "   reachable over V2"
                        else "   NOT reachable — no session and no published prekey bundle"
                    )
                }

                input == "/chats" -> {
                    val chats = client.conversations()
                    if (chats.isEmpty()) out("   (none yet)")
                    chats.forEach { out(chatLine(client, it)) }
                }

                input == "/blocked" -> {
                    val visible = client.conversations().map { it.conversationId }.toSet()
                    val hidden = client.conversationsIncludingBlocked().filterNot { it.conversationId in visible }
                    if (hidden.isEmpty()) out("   (no conversations are withheld)")
                    hidden.forEach { out(chatLine(client, it) + "  [blocked]") }
                }

                input == "/contacts" -> {
                    val all = client.contacts.all()
                    if (all.isEmpty()) out("   (none yet)")
                    all.forEach {
                        val flags = buildString {
                            if (it.blocked) append(" [blocked]")
                            append(" [${it.verification.wire}]")
                        }
                        out("   ${short(it.address)}  ${it.displayName ?: ""}$flags")
                    }
                }

                input == "/peers" -> {
                    val peers = client.mesh.peers()
                    if (peers.isEmpty()) out("   (none on this network)")
                    peers.forEach { out("   ${it.displayName} [${it.platform}] ${short(it.publicKey)} ${it.host}:${it.port}") }
                }

                input.startsWith("/send ") -> {
                    val rest = input.removePrefix("/send ").trim()
                    val prefix = rest.substringBefore(' ')
                    val text = rest.substringAfter(' ', "")
                    val target = resolve(client, prefix)
                    when {
                        target == null -> out("   no known address starts with '$prefix' (use the full address for a new contact)")
                        text.isEmpty() -> out("   nothing to send")
                        else -> out("   " + outcomeText(client.send(target, text)))
                    }
                }

                input.startsWith("/sendfile ") -> {
                    val rest = input.removePrefix("/sendfile ").trim()
                    val target = resolve(client, rest.substringBefore(' '))
                    val path = rest.substringAfter(' ', "")
                    val file = File(path)
                    when {
                        target == null -> out("   unknown address")
                        path.isEmpty() || !file.isFile -> out("   no such file: $path")
                        else -> out("   " + outcomeText(client.sendFile(target, file)))
                    }
                }

                input.startsWith("/card ") -> {
                    val a = args(input, 3)
                    val target = a?.let { resolve(client, it[1]) }
                    val card = a?.let { resolve(client, it[2]) }
                    when {
                        a == null -> out("   usage: /card <address> <contact address>")
                        target == null || card == null -> out("   unknown address")
                        else -> out("   " + outcomeText(client.sendContactCard(target, card)))
                    }
                }

                input.startsWith("/history ") -> withPeer(client, input, "/history ", out) { target ->
                    val rows = client.history(target)
                    if (rows.isEmpty()) out("   (nothing yet)")
                    rows.forEach { m ->
                        val who = if (m.fromMe) "me" else short(m.senderAddress)
                        val state = if (m.fromMe) " (${m.deliveryStatus.wire})" else ""
                        val body = when {
                            m.isDeletedForEveryone -> "(deleted)"
                            m.mediaRef != null -> "[${m.mediaType?.wire ?: "file"}] ${m.content} → ${m.mediaRef}"
                            else -> m.content ?: "(deleted)"
                        }
                        val reactions = if (m.reactions.isEmpty()) "" else
                            "  " + m.reactions.values.flatten().joinToString("")
                        val edited = if (m.editedAtMs != null) " (edited)" else ""
                        out("   ${m.id.take(8)}  ${stamp(m.sentAtMs)}  $who$state: $body$edited$reactions")
                    }
                }

                input.startsWith("/block ") -> withPeer(client, input, "/block ", out) {
                    // client.block, not contacts.block: the store's flag needs a row to sit
                    // on, and blocking someone never messaged before must still work.
                    client.block(it); out("   blocked ${short(it)} — nothing from them will be stored")
                }

                input.startsWith("/unblock ") -> withPeer(client, input, "/unblock ", out) {
                    // Every SPELLING, not just the one typed: a key blocked under one base64
                    // spelling must not stay blocked forever because the unblock used
                    // another. iOS has exactly that bug (PARITY.md 0.21 defect 4).
                    out("   unblocked ${short(it)} (${client.unblock(it)} spelling(s) cleared)")
                }

                input.startsWith("/react ") || input.startsWith("/unreact ") -> {
                    val adding = input.startsWith("/react ")
                    val a = args(input, 4)
                    if (a == null) out("   usage: ${if (adding) "/react" else "/unreact"} <address> <msgId> <emoji>") else {
                        val target = resolve(client, a[1])
                        val msg = target?.let { resolveMessage(client, it, a[2]) }
                        when {
                            target == null -> out("   unknown address")
                            msg == null -> out("   no message in that conversation starts with '${a[2]}'")
                            else -> out("   " + outcomeText(client.sendReaction(target, msg, a[3], adding)))
                        }
                    }
                }

                input.startsWith("/edit ") -> {
                    val a = args(input, 4, greedyLast = true)
                    if (a == null) out("   usage: /edit <address> <msgId> <new text>") else {
                        val target = resolve(client, a[1])
                        val msg = target?.let { resolveMessage(client, it, a[2]) }
                        when {
                            target == null -> out("   unknown address")
                            msg == null -> out("   no message in that conversation starts with '${a[2]}'")
                            else -> out("   " + outcomeText(client.editMessage(target, msg, a[3])))
                        }
                    }
                }

                input.startsWith("/delete ") -> {
                    val a = args(input, 3)
                    if (a == null) out("   usage: /delete <address> <msgId>") else {
                        val target = resolve(client, a[1])
                        val msg = target?.let { resolveMessage(client, it, a[2]) }
                        when {
                            target == null -> out("   unknown address")
                            msg == null -> out("   no message in that conversation starts with '${a[2]}'")
                            else -> out("   " + outcomeText(client.deleteMessage(target, msg)))
                        }
                    }
                }

                input.startsWith("/typing ") -> {
                    val a = args(input, 3)
                    val target = a?.let { resolve(client, it[1]) }
                    when {
                        a == null -> out("   usage: /typing <address> on|off")
                        target == null -> out("   unknown address")
                        else -> out("   " + outcomeText(client.sendTyping(target, a[2] == "on")))
                    }
                }

                input.startsWith("/read ") -> withPeer(client, input, "/read ", out) {
                    out("   " + outcomeText(client.sendReadReceipt(it)))
                }

                input == "/qr" -> {
                    out(client.qrShareText())
                    out(QrMatrix.encode(client.qrPayload()).toText())
                }

                input.startsWith("/qr png ") -> {
                    val target = File(input.removePrefix("/qr png ").trim())
                    val written = QrMatrix.encode(client.qrPayload()).toPng(target)
                    out("   wrote ${written.absolutePath}")
                }

                input.startsWith("/scan ") -> {
                    when (val scan = client.scanContact(input.removePrefix("/scan ").trim())) {
                        is ContactQr.Scan.Contact -> out("   added ${short(scan.address)} — /send it something")
                        is ContactQr.Scan.Rejected -> out("   rejected (${scan.reason}): ${scan.detail}")
                    }
                }

                input.startsWith("/safety ") -> withPeer(client, input, "/safety ", out) {
                    out("   ${client.safetyNumber(it)}")
                    out("   Read these 60 digits to each other. They match only if nobody is in the middle.")
                }

                input.startsWith("/verify ") -> {
                    val a = args(input, 3)
                    val target = a?.let { resolve(client, it[1]) }
                    when {
                        a == null -> out("   usage: /verify <address> <scanned payload>")
                        target == null -> out("   unknown address")
                        client.verifySafetyNumber(target, a[2]) -> out("   MATCH — marked verified")
                        else -> out("   NO MATCH — marked changed. Do not trust this session.")
                    }
                }

                input == "/groups" -> {
                    val all = client.groups.all()
                    if (all.isEmpty()) out("   (none)")
                    all.forEach {
                        val admin = if (it.isAdmin(client.address)) " [admin]" else ""
                        out("   ${it.groupId.take(8)}…  ${it.name}  ${it.members.size} members  ${it.type.raw}$admin  last ${stamp(it.lastActivityUnixMillis)}")
                    }
                }

                input.startsWith("/group ") -> group(client, input.removePrefix("/group ").trim(), out)

                input == "/live" -> {
                    val sessions = client.places.tracker().sessions()
                    if (sessions.isEmpty()) out("   (no live location shares)")
                    val now = System.currentTimeMillis()
                    sessions.forEach { (id, s) ->
                        val state = when {
                            s.stopped -> LiveState.STOPPED
                            s.pinnedExpiryMs <= now -> LiveState.EXPIRED
                            else -> LiveState.LIVE
                        }
                        out("   ${id.take(8)}…  $state  ${s.latitude}, ${s.longitude}  expires ${stamp(s.pinnedExpiryMs)}")
                    }
                }

                input.startsWith("/schedule ") -> {
                    val a = args(input, 4, greedyLast = true)
                    val target = a?.let { resolve(client, it[1]) }
                    val at = a?.let { parseWhen(it[2]) }
                    when {
                        a == null -> out("   usage: /schedule <address> <+15m|+2h|+1d|epoch-ms> <text>")
                        target == null -> out("   unknown address")
                        at == null -> out("   cannot read '${a[2]}' as a time (+15m, +2h, +1d, or epoch millis)")
                        else -> {
                            val m = client.scheduler.schedule(target, a[3], at)
                            out("   queued ${m.id.take(8)}… for ${stamp(m.scheduledAtMs)}")
                            out("   NOTE: this client can only send while it is RUNNING. If it is stopped when")
                            out("   that time passes, the message goes out on the next start — late, and said to be.")
                        }
                    }
                }

                input == "/scheduled" || input.startsWith("/scheduled ") -> {
                    val arg = input.removePrefix("/scheduled").trim()
                    val rows = if (arg.isEmpty()) client.scheduled.all() else {
                        val target = resolve(client, arg)
                        if (target == null) { out("   unknown address"); emptyList() }
                        else client.scheduled.pendingFor(target)
                    }
                    if (rows.isEmpty()) out("   (nothing queued)")
                    rows.forEach { out(scheduledLine(it)) }
                }

                input.startsWith("/cancel ") -> {
                    val prefix = input.removePrefix("/cancel ").trim()
                    val hit = client.scheduled.all().filter { it.id.startsWith(prefix) }
                    when {
                        hit.size == 1 -> out(
                            if (client.scheduler.cancel(hit[0].id)) "   cancelled ${hit[0].id.take(8)}…"
                            else "   ${hit[0].id.take(8)}… is no longer PENDING — nothing cancelled"
                        )
                        hit.isEmpty() -> out("   no scheduled message starts with '$prefix'")
                        else -> out("   '$prefix' matches ${hit.size} scheduled messages")
                    }
                }

                input.startsWith("/sync") -> sync(client, input.removePrefix("/sync").trim(), out)

                else -> out("   unknown command")
            }
        } catch (e: Exception) {
            out("   ${e.javaClass.simpleName}: ${e.message}")
        }
        return true
    }

    // ------------------------------------------------------------------ groups

    private fun group(client: OshiClient, rest: String, out: (String) -> Unit) {
        val parts = rest.split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) { out("   usage: /group new|send|add|remove|rename|roster|sync …"); return }
        when (parts[0]) {
            "new" -> {
                if (parts.size < 3) { out("   usage: /group new <name> <address>…"); return }
                val members = parts.drop(2).mapNotNull { resolve(client, it) }
                if (members.size != parts.size - 2) { out("   one or more addresses are unknown"); return }
                val g = client.createGroup(parts[1], members, GroupType.COLLABORATIVE)
                out("   created ${g.groupId.take(8)}… '${g.name}' with ${g.members.size} members; definition broadcast")
            }
            "send" -> {
                if (parts.size < 3) { out("   usage: /group send <groupId> <text>"); return }
                val gid = resolveGroup(client, parts[1]) ?: run { out("   unknown group"); return }
                val report = client.sendGroupText(gid, rest.substringAfter(parts[1]).trim())
                out(
                    if (report.refusedControlPayload) "   NOT SENT — that body is a control payload, not text"
                    else "   sent to ${report.sent}/${report.recipients} members" +
                        if (report.skippedBlocked.isEmpty()) "" else " (${report.skippedBlocked.size} blocked, skipped)"
                )
            }
            "add", "remove" -> {
                if (parts.size < 3) { out("   usage: /group ${parts[0]} <groupId> <address>"); return }
                val gid = resolveGroup(client, parts[1]) ?: run { out("   unknown group"); return }
                val who = resolve(client, parts[2]) ?: run { out("   unknown address"); return }
                val updated =
                    if (parts[0] == "add") client.addGroupMember(gid, who) else client.removeGroupMember(gid, who)
                out(
                    if (updated == null) "   unknown group"
                    else "   roster is now ${updated.members.size}; update broadcast"
                )
            }
            "rename" -> {
                if (parts.size < 3) { out("   usage: /group rename <groupId> <name>"); return }
                val gid = resolveGroup(client, parts[1]) ?: run { out("   unknown group"); return }
                val updated = client.renameGroup(gid, rest.substringAfter(parts[1]).trim())
                out(if (updated == null) "   unknown group" else "   renamed to '${updated.name}'; update broadcast")
            }
            "roster" -> {
                if (parts.size < 3) { out("   usage: /group roster <groupId> <address>"); return }
                val gid = resolveGroup(client, parts[1]) ?: run { out("   unknown group"); return }
                val who = resolve(client, parts[2]) ?: run { out("   unknown address"); return }
                out("   " + outcomeText(client.requestGroupRoster(gid, who)))
            }
            "sync" -> {
                if (parts.size < 2) { out("   usage: /group sync <address>"); return }
                val who = resolve(client, parts[1]) ?: run { out("   unknown address"); return }
                out("   " + outcomeText(client.requestAllGroups(who)))
            }
            else -> out("   usage: /group new|send|add|remove|rename|roster|sync …")
        }
    }

    // ------------------------------------------------------------------ sync

    private fun sync(client: OshiClient, rest: String, out: (String) -> Unit) {
        val parts = rest.split(' ').filter { it.isNotEmpty() }
        when (parts.firstOrNull()) {
            "push" -> client.syncPushContacts().fold(
                { out("   archived ${it.accepted.size} record(s); server head is now seq ${it.maxSeq}") },
                { out("   push failed: ${it.message}") },
            )
            "pull" -> client.syncPull().fold(
                {
                    out("   applied ${it.applied}, skipped ${it.skippedUndecryptable} undecryptable, ${it.skippedStale} stale, ${it.skippedOwn} own")
                    out("   cursor at seq ${it.lastSeq} of ${it.headMaxSeq}")
                },
                { out("   pull failed: ${it.message}") },
            )
            "status" -> client.syncIsBehind().fold(
                { out(if (it) "   behind — /sync pull has work to do" else "   up to date") },
                { out("   head failed: ${it.message}") },
            )
            "checkpoint" -> {
                val seq = parts.getOrNull(1)?.toIntOrNull()
                if (seq == null) out("   usage: /sync checkpoint <seq>  (DESTRUCTIVE server-side)")
                else client.syncCheckpoint(seq).fold(
                    { out("   server trimmed ${it.trimmed} item(s); ${it.remaining} remain") },
                    { out("   checkpoint failed: ${it.message}") },
                )
            }
            "legacy" -> when (parts.getOrNull(1)) {
                "push" -> client.legacySyncPushAliases().fold(
                    { out("   legacy alias map uploaded (aliases only — this route carries NO block state)") },
                    { out("   legacy push failed: ${it.message}") },
                )
                "pull" -> client.legacySyncPullAliases().fold(
                    { out("   legacy alias map merged: $it contact(s) added (gap-fill only)") },
                    { out("   legacy pull failed: ${it.message}") },
                )
                else -> out("   usage: /sync legacy push|pull")
            }
            else -> out("   usage: /sync push|pull|status|checkpoint <seq>|legacy push|pull")
        }
    }

    // ------------------------------------------------------------------ helpers

    private inline fun withPeer(
        client: OshiClient,
        input: String,
        prefix: String,
        out: (String) -> Unit,
        body: (String) -> Unit,
    ) {
        val target = resolve(client, input.removePrefix(prefix).trim())
        if (target == null) out("   unknown address") else body(target)
    }

    private fun chatLine(client: OshiClient, s: com.oshi.desktop.store.MessageStore.ConversationSummary): String {
        val label = client.groups.get(s.conversationId)?.let { "${it.name} (group)" } ?: short(s.conversationId)
        return "   $label  ${s.messageCount} msg  last ${stamp(s.lastActivityMs)}  ${s.lastMessage?.content?.take(60) ?: ""}"
    }

    private fun scheduledLine(m: ScheduledMessage): String =
        "   ${m.id.take(8)}  ${stamp(m.scheduledAtMs)}  ${m.status}  ${short(m.recipient)}: ${m.content.take(50)}" +
            if (m.attempts > 0) "  (${m.attempts} attempt(s))" else ""

    private fun outcomeText(o: OshiClient.SendOutcome): String = when (o) {
        OshiClient.SendOutcome.SENT -> "sent"
        OshiClient.SendOutcome.BLOCKED -> "NOT SENT — that contact is blocked"
        OshiClient.SendOutcome.NO_V2_PATH ->
            "NOT DELIVERED — that peer has published no prekey bundle, so V2 cannot reach them"
        OshiClient.SendOutcome.GATE_CLOSED -> "NOT SENT — the V2 rollout gate is closed for this account"
        OshiClient.SendOutcome.REFUSED_CONTROL_PAYLOAD ->
            "REFUSED — that is a control payload or a message this account did not send"
    }

    /** `+15m`, `+2h`, `+1d`, or raw epoch millis. Null when it is none of those. */
    internal fun parseWhen(raw: String, nowMs: Long = System.currentTimeMillis()): Long? {
        if (raw.startsWith("+")) {
            val n = raw.drop(1).dropLast(1).toLongOrNull() ?: return null
            return when (raw.last()) {
                's' -> nowMs + n * 1_000
                'm' -> nowMs + n * 60_000
                'h' -> nowMs + n * 3_600_000
                'd' -> nowMs + n * 86_400_000
                else -> null
            }
        }
        return raw.toLongOrNull()?.takeIf { it > 0 }
    }

    private fun args(input: String, count: Int, greedyLast: Boolean = false): List<String>? {
        val parts = if (greedyLast) input.split(' ', limit = count) else input.split(' ')
        val cleaned = parts.filter { it.isNotEmpty() }
        return if (cleaned.size >= count) cleaned.take(count) else null
    }

    /**
     * Resolve a message id PREFIX inside one conversation.
     *
     * Case-insensitive, because an id that has been round-tripped through an iPhone comes
     * back uppercased and a user reading it off `/history` would type what they see.
     * Ambiguity is null — there is no undo for editing the wrong message.
     */
    private fun resolveMessage(client: OshiClient, conversationId: String, prefix: String): String? {
        if (prefix.isEmpty()) return null
        val hits = client.messages.messages(conversationId)
            .filter { it.id.startsWith(prefix, ignoreCase = true) }
        return if (hits.size == 1) hits.first().id else null
    }

    private fun resolveGroup(client: OshiClient, prefix: String): String? {
        val hits = client.groups.ids().filter { it.startsWith(prefix, ignoreCase = true) }
        return if (hits.size == 1) hits.first() else null
    }
}

/**
 * Accept a prefix for convenience, but ONLY when it matches exactly one known address —
 * and never for an address we have never seen, where the full string is the only safe
 * input. An OSHI address is a public key; a prefix that resolves to the wrong contact
 * would send a message to the wrong person, and there is no undo for that.
 */
internal fun resolve(client: OshiClient, prefix: String): String? {
    if (prefix.isEmpty()) return null
    val known = (client.contacts.all().map { it.address } + client.mesh.peers().map { it.publicKey }).distinct()
    val matches = known.filter { it.startsWith(prefix) }
    return when {
        matches.size == 1 -> matches.first()
        matches.size > 1 -> null
        // Not known yet: only a complete, well-formed address is accepted. `isIdentityKey`
        // is the pairing row's own test — 32 bytes of base64 — rather than a length guess.
        ContactQr.isIdentityKey(prefix) -> ContactQr.canonicalAddress(prefix)
        else -> null
    }
}

private val TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

internal fun stamp(ms: Long): String = TIME.format(Instant.ofEpochMilli(ms))

internal fun short(address: String): String = address.take(16) + "…"

private fun argValue(args: Array<String>, flag: String): String? {
    val i = args.indexOf(flag)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
