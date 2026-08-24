package com.oshi.desktop.mesh

import com.oshi.desktop.DesktopIdentity

/**
 * `./oshi.sh run --args="--mesh"` — a running mesh node you can watch and talk to.
 *
 * This is the piece that cannot be replaced by a unit test: whether a desktop machine is
 * DISCOVERABLE depends on the OS responder, the interface list, the switch's multicast
 * handling and the phone's Wi-Fi power state, none of which exist in a test harness. So
 * it prints what it sees, and the two things worth checking from the other side are:
 *
 *   macOS/iOS side   dns-sd -B _oshi-mesh._tcp        (does Apple's stack see us?)
 *                    dns-sd -L OSHI-xxxxxxxx _oshi-mesh._tcp   (…with our port and TXT?)
 *   Linux side       avahi-browse -r _oshi-mesh._tcp
 *   Windows side     dns-sd -B _oshi-mesh._tcp        (with Bonjour installed)
 *
 * PAYLOAD WARNING, in the CLI because it is where someone would first be tempted: text
 * typed here goes on the wire AS TYPED. The mesh envelope encrypts nothing — on the
 * shipped clients `payload` arrives already encrypted by the layer above. Until that
 * layer is wired to this node (PLAN.md next steps), this is a protocol tool, not a
 * messenger.
 */
fun runMeshCli(args: Array<String>) {
    val name = argValue(args, "--name") ?: "OSHI Desktop (${System.getProperty("os.name")})"
    val identity = DesktopIdentity.generate()
    val myKey = identity.userKey
    val discovery = !args.contains("--no-discovery")

    println("=".repeat(78))
    println("OSHI mesh node")
    println("  identity   : ${myKey.take(16)}…  (ephemeral — in memory only, see DesktopIdentity)")
    println("  name       : $name")
    println("  instance   : ${MeshNode.instanceLabel(myKey)}.${MeshProtocol.SERVICE_TYPE}")
    println("  host record: ${MeshNode.hostLabel(myKey)}.local.")
    println("  platform   : ${MeshProtocol.PLATFORM}")
    println("=".repeat(78))

    val node = MeshNode(myKey, name)
    node.onPeersChanged = { peers ->
        println("── peers (${peers.size}) ─────────────────────────")
        peers.forEach { println("   ${it.displayName} [${it.platform}] ${it.publicKey.take(16)}… ${it.host}:${it.port}") }
    }
    node.onMessage = { msg ->
        println("<< ${msg.type} from ${msg.senderName} (${msg.platform}): ${msg.payload.take(400)}")
    }
    node.start(enableDiscovery = discovery)

    argValue(args, "--connect")?.let { target ->
        val host = target.substringBeforeLast(':')
        val port = target.substringAfterLast(':').toIntOrNull()
        if (port == null) println("--connect wants host:port, got '$target'")
        else node.connectToPeer(host, port, expectedKey = null)
    }

    Runtime.getRuntime().addShutdownHook(Thread { node.stop() })

    // Non-interactive runs (a script, a CI job, `gradle run` with no tty) get a fixed
    // observation window instead of an immediate exit: readlnOrNull() returns null on a
    // closed stdin, and a REPL that reads it in a loop would shut the node down before
    // the first mDNS query goes out.
    argValue(args, "--seconds")?.toIntOrNull()?.let { seconds ->
        println("running for ${seconds}s (non-interactive)…")
        Thread.sleep(seconds * 1000L)
        node.stop()
        println("stopped.")
        return
    }

    println(
        """
        Commands:
          /peers                 list discovered + connected peers
          /routes                the routing table (originator → next hop, hops)
          /send <pkPrefix> <txt> send to the peer whose key starts with pkPrefix
          /quit
        """.trimIndent()
    )

    while (true) {
        val line = readlnOrNull() ?: break
        val trimmed = line.trim()
        when {
            trimmed == "/quit" -> break
            trimmed == "/peers" -> {
                val peers = node.peers()
                if (peers.isEmpty()) println("   (none)")
                peers.forEach {
                    val live = if (node.isConnectedTo(it.publicKey)) "connected" else "discovered"
                    println("   [$live] ${it.displayName} [${it.platform}] ${it.publicKey.take(24)}… ${it.host}:${it.port}")
                }
            }
            trimmed == "/routes" -> {
                val routes = node.routes()
                if (routes.isEmpty()) println("   (none)")
                routes.forEach { (dest, r) -> println("   ${dest.take(20)}… → via ${r.nextHop.take(20)}… hops=${r.hopCount}") }
            }
            trimmed.startsWith("/send ") -> {
                val rest = trimmed.removePrefix("/send ").trim()
                val prefix = rest.substringBefore(' ')
                val text = rest.substringAfter(' ', "")
                val target = node.peers().firstOrNull { it.publicKey.startsWith(prefix) }
                if (target == null) println("   no peer whose key starts with '$prefix'")
                else if (text.isEmpty()) println("   nothing to send")
                else println(if (node.sendMessage(target.publicKey, text)) "   sent" else "   NOT DELIVERED (no mesh path)")
            }
            trimmed.isEmpty() -> {}
            else -> println("   unknown command")
        }
    }
    node.stop()
}

private fun argValue(args: Array<String>, flag: String): String? {
    val i = args.indexOf(flag)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
