package com.oshi.desktop.mesh

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A first-class OSHI mesh node for Windows, Linux and macOS.
 *
 * SCOPE, stated first because the omissions are the interesting part.
 *
 * The shipped mesh has two transports. BLE discovers a peer and hands over an IP and a
 * port — it carries no message data on either platform — and then ALL traffic is TCP over
 * whatever LAN both devices are on, framed by [MeshFraming]. This node implements the
 * second half in full and replaces the first half with mDNS, which both platforms already
 * run alongside BLE for exactly this purpose.
 *
 * That is a deliberate trade, not a gap left for later:
 *   - BLE from the JVM needs a native stack per OS (BlueZ over D-Bus on Linux, WinRT on
 *     Windows, CoreBluetooth on macOS) — three implementations, three sets of
 *     permissions, and a dependency tree, to gain nothing this cannot already do on a
 *     shared network.
 *   - What it WOULD gain is the no-Wi-Fi case: two phones in a field with Bluetooth on
 *     and no access point still mesh; a desktop client on the same field does not. A
 *     laptop is the least likely device to be in that scenario and the most likely to be
 *     on the Wi-Fi that this path needs. **Say this plainly in any user-facing copy:
 *     desktop meshes over the local network, phones also mesh over Bluetooth.**
 *
 * Also NOT here: media/document/location payload handling, calls, groups, and any
 * encryption of `payload`. This node moves opaque payloads between peers and keeps a
 * routing table. What goes INSIDE the payload is the layer above (V2Session, per PLAN.md),
 * and until that is wired, this must not be pointed at a real conversation.
 *
 * THREAD MODEL. One accept thread, one reader thread per connection, one scheduled
 * executor for the 30-second gossip. Every map is concurrent; every write to a socket is
 * serialized on its own stream by [MeshFraming.writeFrame]. Callbacks are invoked on the
 * reader thread of the connection that delivered the frame — a slow callback stalls that
 * one peer and nothing else, and a throwing callback is caught so it cannot kill the loop.
 */
class MeshNode(
    val myPublicKey: String,
    val myDisplayName: String,
    private val log: (String) -> Unit = { println(it) },
) {

    data class Peer(
        val publicKey: String,
        val displayName: String,
        val platform: String,
        val host: String,
        val port: Int,
    )

    data class RouteInfo(val nextHop: String, val hopCount: Int, val lastSeen: Long)

    private class Conn(val socket: Socket, val out: DataOutputStream) {
        @Volatile var peerKey: String? = null
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var mdns: MdnsService? = null
    private var timers: ScheduledExecutorService? = null

    /** peer public key → live connection. */
    private val connections = ConcurrentHashMap<String, Conn>()
    private val sentIdentityTo = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val routingTable = ConcurrentHashMap<String, RouteInfo>()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val connecting = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Insertion-ordered so the overflow trim drops the OLDEST ids, not an arbitrary half. */
    private val seenMessageIds = Collections.synchronizedSet(LinkedHashSet<String>())

    var onMessage: (MeshMessage) -> Unit = {}
    var onPeersChanged: (List<Peer>) -> Unit = {}

    var listenPort: Int = 0
        private set

    fun peers(): List<Peer> = peers.values.sortedBy { it.displayName }
    fun routes(): Map<String, RouteInfo> = routingTable.toMap()
    fun isConnectedTo(publicKey: String): Boolean = findConn(publicKey) != null

    // ------------------------------------------------------------------ lifecycle

    /**
     * @param enableDiscovery false starts the TCP half only — used by tests, and by the
     *        `--connect` path, so a machine with no multicast (a locked-down VLAN, a
     *        container with no multicast route) can still be a peer by explicit address.
     */
    fun start(enableDiscovery: Boolean = true) {
        if (!running.compareAndSet(false, true)) return

        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(0))      // dynamic port, exactly like both platforms
        serverSocket = server
        listenPort = server.localPort
        log("mesh: TCP server on port $listenPort")

        Thread({ acceptLoop(server) }, "oshi-mesh-accept").apply { isDaemon = true; start() }

        val timer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "oshi-mesh-timer").apply { isDaemon = true }
        }
        timers = timer
        timer.scheduleWithFixedDelay(
            { try { broadcastOwnIdentityAnnounce() } catch (e: Exception) { log("announce: ${e.message}") } },
            MeshProtocol.ANNOUNCE_INITIAL_DELAY_MS,
            MeshProtocol.ANNOUNCE_PERIOD_MS,
            TimeUnit.MILLISECONDS,
        )

        if (enableDiscovery) {
            val svc = MdnsService(
                instanceLabel = instanceLabel(myPublicKey),
                hostLabel = hostLabel(myPublicKey),
                port = listenPort,
                // TXT keys are `pk`, `name`, `platform` on both platforms — iOS reads them
                // in netServiceDidResolveAddress, Android in its NSD resolve listener.
                // `pk` carries the FULL base64 key; the instance name carries only 8
                // characters of it and is not an identity.
                txtEntries = listOf("pk=$myPublicKey", "name=$myDisplayName", "platform=${MeshProtocol.PLATFORM}"),
                onServiceFound = { svc -> onDiscovered(svc) },
                onServiceLost = { name -> log("mesh: lost $name") },
                log = log,
            )
            mdns = svc
            svc.start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        mdns?.stop(); mdns = null
        timers?.shutdownNow(); timers = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        for (c in connections.values.toList()) closeQuietly(c)
        connections.clear(); peers.clear(); routingTable.clear(); sentIdentityTo.clear()
        seenMessageIds.clear()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get() && !server.isClosed) {
            try {
                val socket = server.accept()
                startConnection(socket, expectedKey = null)
            } catch (e: Exception) {
                if (running.get() && !server.isClosed) log("mesh: accept: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * A resolved DNS-SD instance is not a new device.
     *
     * Observed against a real Android peer: one phone advertises TWO instances of this
     * service — `OSHI-2wLYrJr2` and `OSHI-2wLYrJr2 (2)` — because Android's NSD hit a name
     * conflict (its own earlier registration, still cached by the network) and took the
     * renamed form without withdrawing the first. Same public key, same host, same port,
     * two instance names. **The instance label is not an identity; the TXT `pk` is.**
     * Everything here therefore keys on `pk`, and a repeat with unchanged address is
     * neither logged nor re-dialled.
     */
    private fun onDiscovered(svc: MdnsService.DiscoveredService) {
        val pk = svc.publicKey
        if (pk == myPublicKey) return
        val known = peers[pk]
        if (known == null || known.host != svc.host || known.port != svc.port) {
            peers[pk] = Peer(pk, svc.displayName, svc.platform, svc.host, svc.port)
            onPeersChanged(peers())
            log("mesh: discovered ${svc.displayName} (${svc.platform}) at ${svc.host}:${svc.port}")
        }
        connectToPeer(svc.host, svc.port, pk)
    }

    /**
     * Open a connection to a peer, unless one is already live for that key.
     *
     * The dedup is the same one both platforms use, and it has the same known hole: when
     * two nodes discover each other at the same instant, both dial and the pair ends up
     * with two connections. Neither platform breaks a tie (a lower-key-yields rule would
     * be the fix, and would have to land on all three clients at once to be worth
     * anything). The consequence is bounded — identity exchange registers whichever
     * connection speaks last and the other goes idle until its peer drops it — so this
     * copies the shipped behaviour rather than inventing a fourth one.
     */
    fun connectToPeer(host: String, port: Int, expectedKey: String?) {
        if (expectedKey != null && findConn(expectedKey) != null) return
        val dialKey = expectedKey ?: "$host:$port"
        if (!connecting.add(dialKey)) return
        Thread({
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                log("mesh: connected to $host:$port")
                startConnection(socket, expectedKey)
            } catch (e: Exception) {
                log("mesh: connect $host:$port failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                connecting.remove(dialKey)
            }
        }, "oshi-mesh-dial").apply { isDaemon = true }.start()
    }

    private fun startConnection(socket: Socket, expectedKey: String?) {
        socket.tcpNoDelay = true
        val conn = Conn(socket, DataOutputStream(socket.getOutputStream()))
        if (expectedKey != null) {
            conn.peerKey = expectedKey
            connections[expectedKey] = conn
            // We dialled, so we speak first — identity, then the 2-hop gossip announce,
            // in that order. Both platforms do exactly this on connect.
            sentIdentityTo.add(expectedKey)
            send(conn, identityMessage())
            send(conn, announceMessage(myPublicKey, myDisplayName, MeshProtocol.PLATFORM, 0, MeshProtocol.ANNOUNCE_TTL))
        }
        Thread({ readLoop(conn) }, "oshi-mesh-rx").apply { isDaemon = true }.start()
    }

    private fun readLoop(conn: Conn) {
        val input = DataInputStream(conn.socket.getInputStream())
        try {
            while (running.get() && !conn.socket.isClosed) {
                val frame = MeshFraming.readFrame(input)
                val msg = MeshMessage.parse(frame)
                if (msg == null) {
                    // Both platforms log and continue here. The frame boundary is intact —
                    // only its contents were unreadable — so the stream is still usable.
                    log("mesh: unparseable frame (${frame.size} bytes) — dropped, connection kept")
                    continue
                }
                dispatch(msg, conn)
            }
        } catch (e: EOFException) {
            log("mesh: peer closed ${conn.peerKey?.take(12) ?: "(unidentified)"}")
        } catch (e: Exception) {
            if (running.get()) log("mesh: read: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            cleanup(conn)
        }
    }

    private fun cleanup(conn: Conn) {
        val key = conn.peerKey
        closeQuietly(conn)
        if (key != null && connections[key] === conn) {
            connections.remove(key)
            sentIdentityTo.remove(key)
            routingTable.remove(key)
            // Routes whose next hop was this peer are gone too. Leaving them behind is how
            // a node keeps confidently sending into a link that no longer exists.
            routingTable.entries.removeIf { it.value.nextHop == key }
            peers.remove(key)
            onPeersChanged(peers())
            log("mesh: disconnected ${key.take(12)}… (peers=${connections.size})")
        }
    }

    private fun closeQuietly(conn: Conn) {
        try { conn.socket.close() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ receive

    private fun dispatch(msg: MeshMessage, conn: Conn) {
        when (msg.type) {
            MeshProtocol.TYPE_IDENTITY_EXCHANGE -> handleIdentityExchange(msg, conn)
            MeshProtocol.TYPE_IDENTITY_ANNOUNCE -> handleIdentityAnnounce(msg, conn)
            // Everything else is content. NOTE the deliberate absence of a type allow-list:
            // Android routes GROUP_UPDATE/GROUP_MESSAGE/PUBLIC_GROUP_AD through its content
            // pipeline and iOS's switch does not name them at all (CrossPlatformMesh.swift
            // :685), so an allow-list copied from either platform would silently drop the
            // other's traffic. Dedup, learn the route, deliver or relay; let the layer
            // above decide what a type means. PLAN.md §4.8: an unknown value degrades, it
            // does not throw.
            else -> handleContentMessage(msg)
        }
    }

    private fun handleIdentityExchange(msg: MeshMessage, conn: Conn) {
        val key = msg.senderPublicKey
        if (key.isEmpty()) { log("mesh: IDENTITY_EXCHANGE with empty key — ignored"); return }
        conn.peerKey = key
        connections[key] = conn
        routingTable[key] = RouteInfo(key, 1, System.currentTimeMillis())
        peers[key] = Peer(
            publicKey = key,
            displayName = msg.senderName,
            platform = msg.platform,
            host = conn.socket.inetAddress?.hostAddress ?: "?",
            port = conn.socket.port,
        )
        onPeersChanged(peers())
        log("mesh: identity ${msg.senderName} (${msg.platform}) ${key.take(12)}…")

        // Reply exactly once per peer. Without the guard the two sides ping identities at
        // each other forever — both platforms carry the same `sentIdentityTo` set for this.
        if (sentIdentityTo.add(key)) send(conn, identityMessage())
        send(conn, announceMessage(myPublicKey, myDisplayName, MeshProtocol.PLATFORM, 0, MeshProtocol.ANNOUNCE_TTL))
    }

    private fun handleContentMessage(msg: MeshMessage) {
        if (!markSeen(msg.id)) return

        routingTable[msg.senderPublicKey] =
            RouteInfo(msg.senderPublicKey, msg.hopCount, System.currentTimeMillis())

        if (MeshProtocol.isForUs(msg.recipientPublicKey, myPublicKey)) {
            try { onMessage(msg) } catch (e: Exception) { log("mesh: onMessage threw: ${e.javaClass.simpleName}: ${e.message}") }
        } else {
            relayToRecipient(msg)
        }
    }

    private fun handleIdentityAnnounce(msg: MeshMessage, conn: Conn) {
        if (!markSeen(msg.id)) return
        val o = try { JSONObject(msg.payload) } catch (_: Exception) { return }
        val originator = o.optString("originator", "")
        val displayName = o.optString("displayName", "")
        val platform = o.optString("platform", "unknown")
        val hops = o.optInt("hops", 0)
        val ttl = o.optInt("ttl", MeshProtocol.ANNOUNCE_TTL)
        if (originator.isEmpty() || originator == myPublicKey) return

        val nextHop = conn.peerKey ?: msg.senderPublicKey
        val newHopCount = hops + 1
        val now = System.currentTimeMillis()
        val existing = routingTable[originator]
        if (existing == null || newHopCount < existing.hopCount ||
            (newHopCount == existing.hopCount && now > existing.lastSeen)
        ) {
            routingTable[originator] = RouteInfo(nextHop, newHopCount, now)
        }
        log("mesh: announce $displayName ($platform) hops=$newHopCount via ${nextHop.take(12)}…")

        if (newHopCount < ttl) {
            // Same id on the re-broadcast, so the next node dedupes it instead of looping.
            val relay = announceMessage(originator, displayName, platform, newHopCount, ttl, msg.id)
            val bytes = relay.toWireBytes()
            for ((peerKey, c) in connections) {
                if (c === conn) continue          // never back down the link it arrived on
                if (peerKey == originator) continue
                sendBytes(c, bytes)
            }
        }
    }

    /** @return true if this id is new (and now recorded), false if it was already seen. */
    private fun markSeen(id: String): Boolean {
        synchronized(seenMessageIds) {
            if (!seenMessageIds.add(id)) return false
            if (seenMessageIds.size > MeshProtocol.MAX_SEEN_IDS) {
                val it = seenMessageIds.iterator()
                var toDrop = MeshProtocol.MAX_SEEN_IDS / 2
                while (it.hasNext() && toDrop-- > 0) { it.next(); it.remove() }
            }
            return true
        }
    }

    // ------------------------------------------------------------------ send

    /**
     * Send `payload` to `recipientPublicKey`.
     *
     * @return true only if the bytes reached a socket. False means NOT DELIVERED and the
     *         caller must fall back to the relay server — this is why iOS's equivalent
     *         returns a Bool and why an optimistic "true" here would be the mesh
     *         equivalent of an unread message that never arrives.
     */
    fun sendMessage(recipientPublicKey: String, payload: String, type: String = MeshProtocol.TYPE_TEXT): Boolean =
        sendOrRelay(
            MeshMessage(
                id = UUID.randomUUID().toString(),
                type = type,
                senderPublicKey = myPublicKey,
                senderName = myDisplayName,
                recipientPublicKey = recipientPublicKey,
                payload = payload,
                timestamp = System.currentTimeMillis().toDouble(),
                hopCount = 0,
                maxHops = MeshProtocol.MAX_HOPS,
                seenBy = emptyList(),
                platform = MeshProtocol.PLATFORM,
            )
        )

    /**
     * Direct link, else a learned route, else FAIL. There is no broadcast fallback, and
     * that is a privacy property rather than an optimisation: `senderPublicKey` and
     * `recipientPublicKey` ride in PLAINTEXT on this envelope (only `payload` is
     * encrypted), so spraying an unroutable message across every connected peer would
     * hand each of them the fact that A is messaging B. Android removed exactly that
     * fallback for exactly that reason; iOS never had it.
     */
    private fun sendOrRelay(msg: MeshMessage): Boolean {
        findConn(msg.recipientPublicKey)?.let { return send(it, msg) }

        val route = routingTable[msg.recipientPublicKey]
            ?: routingTable.entries.firstOrNull {
                MeshProtocol.normalizeKey(it.key) == MeshProtocol.normalizeKey(msg.recipientPublicKey)
            }?.value
        if (route != null) {
            findConn(route.nextHop)?.let {
                log("mesh: routed via ${route.nextHop.take(12)}… type=${msg.type}")
                return send(it, msg)
            }
        }
        log("mesh: NO PATH to ${msg.recipientPublicKey.take(16)}… (links=${connections.size}) type=${msg.type} — not delivered")
        return false
    }

    /**
     * Forward a message that is not for us.
     *
     * Three guards, all three load-bearing: the hop ceiling, our own presence in
     * `seenBy`, and — on the broadcast leg — skipping any neighbour already listed in
     * `seenBy`. Drop any one of them and a three-node mesh becomes a packet amplifier.
     */
    private fun relayToRecipient(msg: MeshMessage) {
        if (msg.hopCount >= msg.maxHops) { log("mesh: max hops, dropping ${msg.id.take(8)}"); return }
        if (msg.seenBy.contains(myPublicKey)) return

        val relay = msg.relayedBy(myPublicKey)
        val route = routingTable[msg.recipientPublicKey]
        if (route != null) {
            findConn(route.nextHop)?.let { send(it, relay); return }
        }
        val bytes = relay.toWireBytes()
        for ((peerKey, conn) in connections) {
            if (msg.seenBy.contains(peerKey)) continue
            if (peerKey == msg.senderPublicKey) continue
            sendBytes(conn, bytes)
        }
    }

    private fun broadcastOwnIdentityAnnounce() {
        if (connections.isEmpty()) return
        val msg = announceMessage(myPublicKey, myDisplayName, MeshProtocol.PLATFORM, 0, MeshProtocol.ANNOUNCE_TTL)
        markSeen(msg.id)
        val bytes = msg.toWireBytes()
        for (conn in connections.values) sendBytes(conn, bytes)
    }

    private fun identityMessage(): MeshMessage = MeshMessage(
        id = UUID.randomUUID().toString(),
        type = MeshProtocol.TYPE_IDENTITY_EXCHANGE,
        senderPublicKey = myPublicKey,
        senderName = myDisplayName,
        recipientPublicKey = "",
        // Inner payload keys are `publicKey`, `name`, `platform` — Android's
        // CrossPlatformMesh.sendIdentity. Built through JSONObject so a display name
        // containing a quote or a backslash still produces valid JSON (PLAN.md §4.6).
        payload = JSONObject()
            .put("publicKey", myPublicKey)
            .put("name", myDisplayName)
            .put("platform", MeshProtocol.PLATFORM)
            .toString(),
        timestamp = System.currentTimeMillis().toDouble(),
        hopCount = 0,
        maxHops = 1,
        seenBy = emptyList(),
        platform = MeshProtocol.PLATFORM,
    )

    private fun announceMessage(
        originator: String, displayName: String, platform: String,
        hops: Int, ttl: Int, reuseId: String? = null,
    ): MeshMessage = MeshMessage(
        id = reuseId ?: UUID.randomUUID().toString(),
        type = MeshProtocol.TYPE_IDENTITY_ANNOUNCE,
        senderPublicKey = myPublicKey,
        senderName = myDisplayName,
        recipientPublicKey = "",
        // Keys `originator`, `displayName`, `platform`, `hops`, `ttl` — identical on both
        // platforms, and both build this payload as a nested JSON STRING, not an object.
        payload = JSONObject()
            .put("originator", originator)
            .put("displayName", displayName)
            .put("platform", platform)
            .put("hops", hops)
            .put("ttl", ttl)
            .toString(),
        timestamp = System.currentTimeMillis().toDouble(),
        hopCount = hops,
        maxHops = ttl,
        seenBy = emptyList(),
        platform = MeshProtocol.PLATFORM,
    )

    /** Tolerant lookup: base64url and padding skew between platforms is real (PLAN.md §4.1). */
    private fun findConn(publicKey: String): Conn? {
        if (publicKey.isEmpty()) return null
        connections[publicKey]?.let { return it }
        val want = MeshProtocol.normalizeKey(publicKey)
        return connections.entries.firstOrNull { MeshProtocol.normalizeKey(it.key) == want }?.value
    }

    private fun send(conn: Conn, msg: MeshMessage): Boolean = sendBytes(conn, msg.toWireBytes())

    private fun sendBytes(conn: Conn, bytes: ByteArray): Boolean {
        // A dead socket absorbs writes silently for a while (kernel buffer), so ask the
        // socket what it thinks before trusting a successful write.
        val s = conn.socket
        if (s.isClosed || s.isOutputShutdown || !s.isConnected) {
            log("mesh: dead socket for ${conn.peerKey?.take(12) ?: "?"} — evicting")
            cleanup(conn)
            return false
        }
        return try {
            MeshFraming.writeFrame(conn.out, bytes)
            true
        } catch (e: Exception) {
            log("mesh: send failed to ${conn.peerKey?.take(12) ?: "?"}: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")
            cleanup(conn)
            false
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000

        /** `OSHI-` + the first 8 characters of the base64 key — the format both platforms publish. */
        fun instanceLabel(publicKey: String): String =
            MeshProtocol.SERVICE_NAME_PREFIX + publicKey.take(8)

        /**
         * The `.local.` hostname we claim for our A record.
         *
         * Derived from a HASH of the public key, not from the key itself and never from
         * the machine's own name. Two reasons, both about not breaking things outside
         * OSHI: a hostname must be a DNS label (base64's `+` and `/` are not), and
         * claiming the machine's real `.local.` name would put this process in a conflict
         * with the OS responder over the name the whole machine answers to.
         */
        fun hostLabel(publicKey: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(publicKey.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder("oshi-")
            for (i in 0 until 4) hex.append(String.format("%02x", d[i]))
            return hex.toString()
        }
    }
}
