package com.oshi.desktop.app

import com.oshi.desktop.group.GroupType
import com.oshi.desktop.i18n.t
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
    // THE SURFACE FOR AN INBOUND CALL, and without it the whole lane is invisible.
    //
    // `onCall` was declared, fed by CallLane, and subscribed by NOBODY. A peer could ring,
    // the poller would open the seal and drive the machine to RINGING, and the user was
    // told nothing — then 45 s later the watchdog sent a callEnd and the caller saw "no
    // answer". In between, the user's own /call was refused BUSY for a call they had never
    // been shown. Every line here is printed, none is swallowed.
    client.onCall = { event ->
        when (event) {
            is com.oshi.desktop.call.CallLane.CallEvent.Ringing ->
                if (event.incoming) {
                    println("\n\u0007>> INCOMING ${if (event.video) "VIDEO " else ""}CALL from ${short(event.peer)}")
                    println("   /answer to accept, /decline to refuse. ${audioNote(client)}")
                } else {
                    println("\n   ringing ${short(event.peer)}…")
                }
            com.oshi.desktop.call.CallLane.CallEvent.RingingStopped -> {}
            is com.oshi.desktop.call.CallLane.CallEvent.Connected ->
                // The EVENT decides, not the configuration: a lane with an opener can still
                // connect without audio if the leg refused, and that call must say so.
                println(
                    "\n   connected to ${short(event.peer)} — " +
                        if (event.noAudio) com.oshi.desktop.call.CallLane.NO_AUDIO_WILL_FLOW
                        else "audio devices open; sound starts once a candidate pair answers."
                )
            is com.oshi.desktop.call.CallLane.CallEvent.Ended ->
                println("\n   call with ${short(event.peer)} ended: ${event.reason}")
            is com.oshi.desktop.call.CallLane.CallEvent.Refused ->
                println("\n   call refused: ${event.refusal}${event.from?.let { " (from ${short(it)})" } ?: ""}")
            is com.oshi.desktop.call.CallLane.CallEvent.TransportProblem ->
                println("\n   call transport: ${event.detail}")
        }
        print("> "); System.out.flush()
    }

    client.onScheduledRun = { run ->
        val late = if (run.wasLate) " — ${run.lateByMs / 1000}s LATE (this client was not running when it came due)" else ""
        println("\n[scheduled] sent ${run.sent}, deferred ${run.deferred}, failed ${run.failed}$late")
        print("> "); System.out.flush()
    }

    // Calls reach a SECOND server with no authentication (see OshiClient.start). Opt-in,
    // and said out loud on the line above the prompt rather than buried in a doc.
    val withCalls = args.contains("--calls")
    if (withCalls) {
        println("  calls      : polling ${com.oshi.desktop.call.CallLane.POLL_INTERVAL_MS} ms — this contacts the CALL server,")
        println("               which has no authentication and logs key prefixes and IPs. SIGNALLING ONLY: no audio.")
    } else {
        println("  calls      : off — pass --calls to ring and be rung (signalling only, no audio)")
    }
    client.start(
        pollIntervalMs = argValue(args, "--poll")?.toLongOrNull() ?: 3_000,
        callPollIntervalMs = if (withCalls) com.oshi.desktop.call.CallLane.POLL_INTERVAL_MS else 0,
    )
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
/**
 * What the REPL says about audio on a call, in ONE place.
 *
 * There used to be five unconditional prints of `NO_AUDIO_WILL_FLOW`, and the repetition
 * was deliberate and right while it was true — a person told "calling…" and then
 * "connected" has been told they are on a call. It stopped being true when `OshiClient`
 * gained a media opener.
 *
 * The warning is not deleted, it is made CONDITIONAL, because both sentences have to keep
 * existing: a build with no opener must still say nothing will be heard, and a build with
 * one must not. A surface that cries "no audio" over a call that is carrying audio teaches
 * the user to skip that line, and they will skip it when it matters.
 *
 * The audio-capable sentence is deliberately unexcited. Devices open does not mean sound
 * arrives: a candidate pair still has to answer, there is no TURN, and no call has ever
 * been carried between two machines.
 */
private fun audioNote(client: OshiClient): String =
if (client.calls.carriesAudio) {
    "audio devices open on connect; sound starts only once a candidate pair answers, " +
        "and there is no relay to fall back on if none does."
} else {
    com.oshi.desktop.call.CallLane.NO_AUDIO_WILL_FLOW
}

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
          /call <address>                    ring a peer — SIGNALLING ONLY, NO AUDIO
          /answer                            answer the ringing call (still no audio)
          /decline                           decline the ringing call
          /hangup                            end the call that is up
          /calls                             the call state now, and this session's call log
          /bot poll                          fetch bot posts  (NOT encrypted)
          /bot send <token> <group> <text>   post to a bot group (NOT encrypted)
          /sync push|pull|status             the V2 archive (multi-device)
          /sync checkpoint <seq>             let the server drop everything up to <seq>
          /sync legacy push|pull             the legacy /api/sync alias map
          /call <address>                    ring a peer — SIGNALLING ONLY, no audio (needs --calls)
          /lora attach <host> [port]         attach to a Meshtastic node over TCP (default 4403)
          /lora status|detach                the radio link — receive only, see /lora status
          /ai <prompt>                       ask the OFFLINE model (nothing leaves this machine)
          /ai model <path>                   point at a .gguf; nothing is ever downloaded
          /ai status                         engine, model, and what a question would do now
          /ai forget                         drop the remembered conversation context
          /deleteaccount confirm             erase this identity on the server, then wipe it here
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
                    if (chats.isEmpty()) out("   " + t("messages.empty"))
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
                    if (all.isEmpty()) out("   " + t("contacts.empty"))
                    all.forEach {
                        val flags = buildString {
                            if (it.blocked) append(" [${t("contact.blocked")}]")
                            append(" [${it.verification.wire}]")
                        }
                        out("   ${short(it.address)}  ${it.displayName ?: ""}$flags")
                    }
                }

                input == "/peers" -> {
                    val peers = client.mesh.peers()
                    if (peers.isEmpty()) out("   " + t("mesh.no_peers"))
                    // A peer that dialled us has no listen port we know of. Printing
                    // ":0" would read as an address; naming it is the honest render.
                    peers.forEach {
                        val where = if (it.portIsDialable) "${it.host}:${it.port}" else "${it.host} (dialled us — no listen port advertised)"
                        out("   ${it.displayName} [${it.platform}] ${short(it.publicKey)} $where")
                    }
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
                    if (rows.isEmpty()) out("   " + t("messages.empty"))
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
                        val edited = if (m.editedAtMs != null) " " + t("edit.edited_label") else ""
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
                    // A key that TAKES AN ARGUMENT, on purpose: "%@" here is not "%s" to
                    // the JDK, and this is the call site that would throw if IosFormat
                    // stopped converting. See IosFormatTest.
                    out("   " + t("safety.instruction_1", short(it)))
                    out("   " + t("safety.instruction_2"))
                    out("   " + t("safety.instruction_3"))
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
                    if (all.isEmpty()) out("   " + t("empty.no_groups"))
                    all.forEach {
                        val admin = if (it.isAdmin(client.address)) " [${t("groups.admin")}]" else ""
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
                    // NOT localised, and the reason is recorded rather than left blank:
                    // the shipped iOS asset has no key for an empty SCHEDULED queue.
                    // `scheduled.empty` exists in exactly ONE of the 34 catalogs (pl) and
                    // not in English, so using it would show Polish users a string and
                    // everyone else the key. See CatalogAuditTest.
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

                // `/calls` is matched by EQUALITY and `/call` only with its trailing space,
                // so neither can swallow the other however they are ordered.
                input == "/calls" -> callLog(client, out)
                input.startsWith("/call ") -> withPeer(client, input, "/call ", out) { peer ->
                    // The warning goes out BEFORE the ring, every single time — not once at
                    // startup, not in a doc comment. A person who is told "calling…" and then
                    // "connected" has been told they are on a call, and this client cannot
                    // carry a sample of audio in either direction.
                    out("   ! " + audioNote(client))
                    when (val d = client.calls.call(peer)) {
                        is com.oshi.desktop.call.CallLane.Dialled.Ringing ->
                            out("   ringing ${short(peer)} — call ${d.callId.take(8)}…, the server said '${d.delivery}'")
                        is com.oshi.desktop.call.CallLane.Dialled.Refused ->
                            out("   NOT RINGING — ${d.why}")
                    }
                }
                input == "/answer" -> answered(client.calls.answer(), client, out, "answered")
                input == "/decline" -> answered(client.calls.decline(), client, out, "declined")
                input == "/hangup" -> answered(client.calls.hangUp(), client, out, "ended")

                input.startsWith("/bot") -> bot(client, input.removePrefix("/bot").trim(), out)
                input.startsWith("/sync") -> sync(client, input.removePrefix("/sync").trim(), out)
                input.startsWith("/lora") -> lora(client, input.removePrefix("/lora").trim(), out)
                input == "/ai" || input.startsWith("/ai ") -> ai(input.removePrefix("/ai").trim(), out)
                input.startsWith("/deleteaccount") ->
                    deleteAccount(client, input.removePrefix("/deleteaccount").trim(), out)

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
                    if (updated == null) "   " + t("alert.add_failed")
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

    /**
 * The bot lane (row 0.26) — the one command in this REPL that is NOT end-to-end encrypted.
 *
 * The warning is printed on every send rather than once at startup, and it is printed
 * BEFORE the post goes out, because a warning a user has already scrolled past is not a
 * warning. `OshiClient.BOT_CHANNEL_IS_PLAINTEXT` is the single owner of that sentence so
 * this surface cannot drift from any UI that appears later.
 */
private fun bot(client: OshiClient, args: String, out: (String) -> Unit) {
    val parts = args.split(" ", limit = 4).filter { it.isNotEmpty() }
    when (parts.firstOrNull()) {
        "poll" -> {
            val n = client.pollBots()
            out(if (n == 0) "   no new bot messages" else "   stored $n bot message(s) — this lane is NOT encrypted")
        }
        "send" -> {
            if (parts.size < 4) { out("   usage: /bot send <token> <groupId> <text>"); return }
            out("   ! " + OshiClient.BOT_CHANNEL_IS_PLAINTEXT)
            client.sendBotMessage(parts[1], parts[2], parts[3])
                .onSuccess { out("   posted to ${it.groupName}: ${it.delivered}/${it.totalMembers} delivered, in cleartext") }
                .onFailure { out("   bot send failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
        else -> out("   usage: /bot poll | /bot send <token> <groupId> <text>")
    }
}

    // ------------------------------------------------------------------ calls (row 2.1)

    /**
     * Report what `/answer`, `/decline` or `/hangup` did.
     *
     * A [com.oshi.desktop.call.CallRefusal] is printed by NAME rather than folded into "that
     * didn't work", because the whole reason the state machine returns one is that "there is
     * no call ringing", "that call already ended" and "a decline cannot end a connected call"
     * are three different things to tell a person.
     */
    private fun answered(
        refusal: com.oshi.desktop.call.CallRefusal,
        client: OshiClient,
        out: (String) -> Unit,
        verb: String,
    ) {
        if (refusal == com.oshi.desktop.call.CallRefusal.NONE) {
            out("   $verb — ${client.calls.state}")
            if (verb == "answered") {
                // Said again on the answer, because the answering side is the one that never
                // saw /call's warning.
                out("   ! " + audioNote(client))
            }
        } else {
            out("   nothing $verb — $refusal (state is ${client.calls.state})")
        }
    }


private fun callLog(client: OshiClient, out: (String) -> Unit) {
        val lane = client.calls
        val where = lane.peer?.let { " with ${short(it)}" } ?: ""
        val dir = if (lane.state == com.oshi.desktop.call.CallState.IDLE) ""
        else if (lane.isOutgoing) " (outgoing)" else " (incoming)"
        out("   state: ${lane.state}$where$dir")
        out("   ! " + audioNote(client))
        val rows = lane.calls()
        if (rows.isEmpty()) out("   (no calls this session)")
        rows.forEach {
            val how = if (it.incoming) "in " else "out"
            val got = if (it.connected) "connected" else "not connected"
            out("   $how  ${stamp(it.atMs)}  ${short(it.peer)}  ${it.reason.wire}  $got")
        }
        if (rows.isNotEmpty()) {
            // Stated where someone would otherwise assume a history: this is a signalling
            // row, not a call-history row, and it has no store behind it.
            out("   (this log is in memory only — it does not survive a restart)")
        }
        val problems = lane.unopenable + lane.unaddressable + lane.sendFailures
        if (problems > 0) {
            out("   ${lane.unopenable} signal(s) did not open, ${lane.unaddressable} named no valid" +
                " sender, ${lane.sendFailures} send(s) failed")
        }
    }

private fun lora(client: OshiClient, rest: String, out: (String) -> Unit) {
        val parts = rest.split(' ').filter { it.isNotEmpty() }
        when (parts.firstOrNull()) {
            "attach" -> {
                val host = parts.getOrNull(1)
                if (host == null) { out("   usage: /lora attach <host> [port]"); return }
                val port = parts.getOrNull(2)?.toIntOrNull() ?: com.oshi.desktop.lora.LoRaAttach.TCP_PORT
                client.loraAttach(host, port)
                out("   attaching to $host:$port …")
                // Said on every attach, because this is the one lane where "connected"
                // and "can read your messages" are different facts.
                out("   RECEIVE ONLY, and mostly unreadable: stock Meshtastic text arrives")
                out("   flagged UNVERIFIED; an OSHI envelope is sealed with the LEGACY ratchet")
                out("   this client does not implement, so it is reported and never opened.")
            }
            "detach" -> { client.loraDetach(); out("   detached") }
            "status" -> out(if (client.loraAttached) "   attached" else "   not attached")
            else -> out("   usage: /lora attach <host> [port] | status | detach")
        }
    }

/**
 * `/ai` — PARITY.md row 2.5, the offline model.
 *
 * Takes no [OshiClient]: this is the one feature in the client that touches no identity,
 * no session and no wire. The engine is process-global because llama.cpp is (see
 * [com.oshi.desktop.ai.DesktopAi]).
 *
 * **Everything the model cannot do is printed, never swallowed.** There is no native
 * library in this repository and no model is shipped, so on a fresh machine every one of
 * these commands says exactly what is missing and where it looked. That is the whole
 * point of the row: the iOS app's Apple-only fast path cannot exist here, the portable
 * fallback can, and a client that quietly printed nothing would be indistinguishable from
 * one that had answered with silence.
 */
private fun ai(rest: String, out: (String) -> Unit) {
    val mgr = com.oshi.desktop.ai.DesktopAi.manager()
    when {
        rest.isEmpty() -> {
            out("   usage: /ai <prompt> | /ai model <path> | /ai status | /ai forget")
            com.oshi.desktop.ai.AiConsole.render(mgr.status()).forEach(out)
        }
        rest == "status" -> com.oshi.desktop.ai.AiConsole.render(mgr.status()).forEach(out)
        rest == "forget" -> out("   dropped ${mgr.forget()} remembered turn(s)")
        rest == "model" -> out("   usage: /ai model <path-to-a-.gguf>")
        rest.startsWith("model ") ->
            com.oshi.desktop.ai.AiConsole.render(mgr.configureModel(rest.removePrefix("model ").trim())).forEach(out)
        else -> com.oshi.desktop.ai.AiConsole.render(mgr.generate(rest)).forEach(out)
    }
}

private fun deleteAccount(client: OshiClient, rest: String, out: (String) -> Unit) {
        // The word is required and it is not a flourish. Every other command here is
        // undoable or repeatable; this one ends the account, and the local half takes the
        // signing key with it, so a mistyped prefix of some other command must not reach it.
        if (rest != "confirm") {
            out("   usage: /deleteaccount confirm")
            out("   This erases this identity's prekey bundle, queued envelopes, sync archive")
            out("   and stored media on the server, then wipes the account on this machine.")
            out("   It cannot be undone and this address can never be used again.")
            return
        }
        client.deleteAccount().fold(
            { r ->
                // 207 is a real answer and it is not success. Print what is GONE and what
                // is not, rather than a tick that means "the request did not throw".
                out(if (r.complete) "   account erased" else "   account PARTIALLY erased")
                out("   prekeys ${if (r.prekeysErased) "erased" else "NOT erased"}" +
                    ", ${r.relayEnvelopes} envelope(s), ${r.syncItems} sync item(s), ${r.inboundBlobs} blob(s)")
                if (r.outboundBlobsLeft > 0) out("   ${r.outboundBlobsLeft} blob(s) sent to others remain — they are not this account's to delete")
                if (r.unreachable.isNotEmpty()) out("   STILL OUT THERE — unreachable store(s): ${r.unreachable.joinToString(", ")}")
                out("   local account wiped. Restart to mint a new one.")
            },
            {
                // Nothing was wiped locally — see OshiClient.deleteAccount for why that is
                // the safe direction. Say so, or the user retries believing they are already
                // half-deleted.
                out("   delete failed: ${it.message}")
                out("   NOTHING was wiped — the account here is intact, so this can be retried.")
            },
        )
    }

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
        OshiClient.SendOutcome.SENT -> t("messages.sent_successfully")
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
