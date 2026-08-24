package com.oshi.desktop.mesh

import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.StandardSocketOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * The discovery half of the desktop mesh: an mDNS responder and a DNS-SD browser for
 * `_oshi-mesh._tcp.local.`, speaking to Apple's mDNSResponder on iOS/macOS and to
 * Android's NsdManager.
 *
 * This is the layer that decides whether a desktop machine EXISTS to a phone in the same
 * room, and it is the layer with no OSHI code on the other side — so its correctness is
 * defined by RFC 6762/6763 and by what those two stacks actually do, not by matching a
 * sibling implementation. What is verified, and how, is in MdnsCodecTest and in
 * PLAN_MESH.md; what is NOT verified is stated there too.
 *
 * Portability, since Windows and Linux are the point:
 *   - Everything here is `java.net` — `MulticastSocket`, `NetworkInterface`. No JNI, no
 *     platform branch, no shell out to `dns-sd`/`avahi-browse`. The one platform-shaped
 *     concern is joining the group on the right interfaces, and that is done explicitly
 *     per interface rather than relying on a default route, because the default route is
 *     precisely what differs between a Mac on Wi-Fi, a Linux box with docker0 and a
 *     Windows laptop with three virtual adapters.
 *   - Binding UDP 5353 with SO_REUSEADDR (and SO_REUSEPORT where the JDK exposes it) is
 *     what lets this coexist with the OS responder — mDNSResponder on macOS, Bonjour
 *     Service or the Windows DNS Client on Windows, avahi-daemon on most Linux desktops.
 *     Without it the bind fails on any machine that already runs one, which is most of
 *     them. **On Linux, avahi will also answer for our service type if it is configured
 *     to; two responders for one name is legal mDNS and the browsers de-duplicate.**
 */
class MdnsService(
    private val instanceLabel: String,
    private val hostLabel: String,
    private val port: Int,
    private val txtEntries: List<String>,
    private val onServiceFound: (DiscoveredService) -> Unit,
    private val onServiceLost: (String) -> Unit = {},
    private val log: (String) -> Unit = {},
) {

    data class DiscoveredService(
        val instanceName: String,
        val host: String,
        val port: Int,
        val txt: Map<String, String>,
    ) {
        val publicKey: String get() = txt["pk"] ?: ""
        val displayName: String get() = txt["name"] ?: instanceName
        val platform: String get() = txt["platform"] ?: "unknown"
    }

    private val serviceType = MdnsCodec.Name.of(MeshProtocol.SERVICE_TYPE)
    private val instanceName = MdnsCodec.Name(listOf(instanceLabel) + serviceType.labels)
    private val hostName = MdnsCodec.Name(listOf(hostLabel, "local"))
    private val metaQuery = MdnsCodec.Name.of("_services._dns-sd._udp.local.")

    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private var receiveThread: Thread? = null
    private var timers: ScheduledExecutorService? = null
    private val sendLock = Any()

    /** Interfaces we joined on, with the IPv4 address we advertise for each. */
    private val joined = ArrayList<Pair<NetworkInterface, Inet4Address>>()

    /**
     * What we know about each instance name, complete or not: an SRV without its A, a
     * PTR without its SRV, a TXT that arrived first. Entries are UPDATED, never cleared
     * on success — a responder re-announces continuously and re-parsing the same records
     * must not look like a new peer.
     */
    private val instances = ConcurrentHashMap<String, Partial>()

    /**
     * The last [DiscoveredService] handed to the callback per instance. This is the
     * de-duplication that keeps a browse quiet: [onServiceFound] fires when an instance
     * first resolves and again only if its address, port or TXT actually CHANGED (a peer
     * that moved networks or restarted on a new port). Without it, every announcement
     * from every peer — a handful per minute each, plus one per query we send — is
     * reported as a fresh discovery, and every consumer downstream has to re-do its own
     * de-duplication. Observed against a real Android peer before it was fixed: ten
     * "discovered" events in forty-five seconds for one phone that never moved.
     */
    private val announced = ConcurrentHashMap<String, DiscoveredService>()
    private val hostAddresses = ConcurrentHashMap<String, String>()

    private data class Partial(
        @Volatile var port: Int? = null,
        @Volatile var target: String? = null,
        @Volatile var txt: Map<String, String>? = null,
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val sock = MulticastSocket(null as SocketAddress?)
        sock.reuseAddress = true
        // SO_REUSEPORT is what macOS and the BSDs actually require to share 5353 with the
        // system responder; SO_REUSEADDR alone is enough on Linux and Windows. Not every
        // JDK/platform pair exposes it, and where it is missing the bind still succeeds,
        // so this is best-effort by design rather than by neglect.
        try {
            sock.setOption(StandardSocketOptions.SO_REUSEPORT, true)
        } catch (_: Throwable) {
            log("SO_REUSEPORT unavailable on this platform — SO_REUSEADDR only")
        }
        sock.bind(InetSocketAddress(MDNS_PORT))
        sock.timeToLive = 255
        socket = sock

        for ((nif, v4) in advertisableInterfaces()) {
            try {
                sock.joinGroup(InetSocketAddress(group, MDNS_PORT), nif)
                joined.add(nif to v4)
                log("mDNS joined on ${nif.name} (${v4.hostAddress})")
            } catch (e: Exception) {
                log("mDNS join failed on ${nif.name}: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (joined.isEmpty()) log("mDNS: NO interface joined — discovery is dead on this host")

        receiveThread = Thread({ receiveLoop(sock) }, "oshi-mdns-rx").apply { isDaemon = true; start() }

        val timer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "oshi-mdns-timer").apply { isDaemon = true }
        }
        timers = timer

        // Announce three times, one second apart (RFC 6762 §8.3). Repetition is the only
        // delivery guarantee multicast UDP has.
        for (i in 0..2) timer.schedule({ safely { announce() } }, i * 1000L, TimeUnit.MILLISECONDS)

        // Browse with the RFC 6762 §5.2 backoff: 1 s, doubling, capped at 60 s. A fixed
        // fast interval is how a mesh app becomes the loudest thing on a conference Wi-Fi.
        scheduleQuery(timer, 0L, 1000L)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // Goodbye: the same records at TTL 0 (RFC 6762 §10.1). Without it every browser on
        // the network keeps offering this machine as a peer for the PTR's full 4500 s.
        safely { sendGoodbye() }
        timers?.shutdownNow()
        timers = null
        receiveThread?.interrupt()
        receiveThread = null
        socket?.let { s ->
            for ((nif, _) in joined) {
                try { s.leaveGroup(InetSocketAddress(group, MDNS_PORT), nif) } catch (_: Exception) {}
            }
            try { s.close() } catch (_: Exception) {}
        }
        socket = null
        joined.clear()
        instances.clear()
        announced.clear()
        hostAddresses.clear()
    }

    // ------------------------------------------------------------------ receive

    private fun receiveLoop(sock: MulticastSocket) {
        val buf = ByteArray(9000)   // jumbo-frame headroom; mDNS itself stays under 1500
        while (running.get()) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val msg = MdnsCodec.decode(pkt.data, pkt.length) ?: continue
                if (msg.isResponse) handleResponse(msg) else handleQuery(msg)
            } catch (e: Exception) {
                if (running.get()) log("mDNS receive: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun handleQuery(msg: MdnsCodec.Message) {
        val wantsService = msg.questions.any {
            (it.type == MdnsCodec.TYPE_PTR || it.type == MdnsCodec.TYPE_ANY) &&
                it.name.equalsIgnoreCase(serviceType)
        }
        val wantsMeta = msg.questions.any {
            (it.type == MdnsCodec.TYPE_PTR || it.type == MdnsCodec.TYPE_ANY) &&
                it.name.equalsIgnoreCase(metaQuery)
        }
        val wantsInstance = msg.questions.any { it.name.equalsIgnoreCase(instanceName) }
        val wantsHost = msg.questions.any { it.name.equalsIgnoreCase(hostName) }
        if (!wantsService && !wantsMeta && !wantsInstance && !wantsHost) return

        // 20–120 ms jitter before answering a shared record (RFC 6762 §6.3): without it,
        // every OSHI peer in the room answers the same browse in the same millisecond.
        val delay = if (wantsService || wantsMeta) Random.nextLong(20, 120) else 0L
        timers?.schedule({
            safely {
                if (wantsMeta && !wantsService && !wantsInstance && !wantsHost) sendMetaResponse()
                else announce()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun handleResponse(msg: MdnsCodec.Message) {
        val records = msg.allRecords

        // Pass 1: A records first, so an SRV arriving in the same packet resolves without
        // a second round trip. Both platforms' responders do send them together.
        for (r in records) if (r is MdnsCodec.Record.A) hostAddresses[r.name.toString().lowercase()] = r.ipString

        for (r in records) {
            when (r) {
                is MdnsCodec.Record.Ptr -> {
                    if (!r.name.equalsIgnoreCase(serviceType)) continue
                    val inst = r.target
                    if (isSelf(inst)) continue
                    val key = inst.toString().lowercase()
                    if (r.isGoodbye) {
                        instances.remove(key)
                        announced.remove(key)?.let { onServiceLost(it.instanceName) }
                        continue
                    }
                    val known = instances[key]
                    if (known?.port == null || known.txt == null) {
                        instances.putIfAbsent(key, Partial())
                        // Ask for the two records that turn a name into an address. Most
                        // responders already sent them as additionals — this is the
                        // fallback for the ones that did not.
                        query(listOf(
                            MdnsCodec.Question(inst, MdnsCodec.TYPE_SRV, MdnsCodec.CLASS_IN),
                            MdnsCodec.Question(inst, MdnsCodec.TYPE_TXT, MdnsCodec.CLASS_IN),
                        ))
                    }
                }
                is MdnsCodec.Record.Srv -> {
                    if (!r.name.endsWith(serviceType) || isSelf(r.name)) continue
                    val key = r.name.toString().lowercase()
                    if (r.isGoodbye) {
                        instances.remove(key)
                        announced.remove(key)?.let { onServiceLost(it.instanceName) }
                        continue
                    }
                    val p = instances.getOrPut(key) { Partial() }
                    p.port = r.port
                    p.target = r.target.toString()
                    if (hostAddresses[r.target.toString().lowercase()] == null) {
                        query(listOf(MdnsCodec.Question(r.target, MdnsCodec.TYPE_A, MdnsCodec.CLASS_IN)))
                    }
                }
                is MdnsCodec.Record.Txt -> {
                    if (!r.name.endsWith(serviceType) || isSelf(r.name)) continue
                    if (r.isGoodbye) continue
                    instances.getOrPut(r.name.toString().lowercase()) { Partial() }.txt = r.asMap()
                }
                else -> {}
            }
        }

        // Pass 2: report anything that is now complete AND different from what we last
        // reported for that instance.
        for ((key, p) in instances.entries.toList()) {
            val svc = resolve(key, p) ?: continue
            if (announced[key] == svc) continue
            announced[key] = svc
            onServiceFound(svc)
        }
    }

    /** A complete, usable service — or null while anything is still missing. */
    private fun resolve(key: String, p: Partial): DiscoveredService? {
        val port = p.port ?: return null
        val target = p.target ?: return null
        val txt = p.txt ?: return null
        val ip = hostAddresses[target.lowercase()] ?: return null
        val suffix = "." + serviceType.toString().lowercase()
        val svc = DiscoveredService(
            instanceName = if (key.endsWith(suffix)) key.dropLast(suffix.length) else key,
            host = ip,
            port = port,
            txt = txt,
        )
        // A peer with no `pk` cannot be addressed, routed to, or de-duplicated — there is
        // nothing a TCP connection to it could accomplish. Drop it here rather than let an
        // empty-string key become a routing entry that matches every tolerant lookup in
        // MeshNode.
        if (svc.publicKey.isEmpty()) return null
        return svc
    }

    private fun isSelf(name: MdnsCodec.Name): Boolean =
        name.labels.isNotEmpty() && name.labels[0].equals(instanceLabel, ignoreCase = true)

    // ------------------------------------------------------------------ send

    private fun scheduleQuery(timer: ScheduledExecutorService, delayMs: Long, intervalMs: Long) {
        timer.schedule({
            safely { query(listOf(MdnsCodec.Question(serviceType, MdnsCodec.TYPE_PTR, MdnsCodec.CLASS_IN))) }
            if (running.get()) scheduleQuery(timer, intervalMs, (intervalMs * 2).coerceAtMost(60_000L))
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun query(questions: List<MdnsCodec.Question>) {
        sendToAll { _ -> MdnsCodec.encodeQuery(questions) }
    }

    /** PTR + SRV + TXT + A for ourselves, with the A record of the interface we send on. */
    private fun announce() = sendToAll { v4 -> MdnsCodec.encodeResponse(
        answers = listOf(
            MdnsCodec.Record.Ptr(serviceType, TTL_SHARED, instanceName),
        ),
        additional = listOf(
            MdnsCodec.Record.Srv(instanceName, TTL_HOST, 0, 0, port, hostName),
            MdnsCodec.Record.Txt(instanceName, TTL_SHARED, txtEntries),
            MdnsCodec.Record.A(hostName, TTL_HOST, v4.address),
        ),
    ) }

    private fun sendMetaResponse() = sendToAll { _ ->
        MdnsCodec.encodeResponse(listOf(MdnsCodec.Record.Ptr(metaQuery, TTL_SHARED, serviceType)))
    }

    private fun sendGoodbye() = sendToAll { v4 -> MdnsCodec.encodeResponse(
        answers = listOf(MdnsCodec.Record.Ptr(serviceType, 0, instanceName)),
        additional = listOf(
            MdnsCodec.Record.Srv(instanceName, 0, 0, 0, port, hostName),
            MdnsCodec.Record.Txt(instanceName, 0, txtEntries),
            MdnsCodec.Record.A(hostName, 0, v4.address),
        ),
    ) }

    private fun sendToAll(build: (Inet4Address) -> ByteArray) {
        val sock = socket ?: return
        synchronized(sendLock) {
            for ((nif, v4) in joined) {
                try {
                    sock.networkInterface = nif
                    val bytes = build(v4)
                    sock.send(DatagramPacket(bytes, bytes.size, group, MDNS_PORT))
                } catch (e: Exception) {
                    log("mDNS send on ${nif.name}: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    private fun safely(block: () -> Unit) {
        try { block() } catch (e: Exception) { log("mDNS task: ${e.javaClass.simpleName}: ${e.message}") }
    }

    companion object {
        const val MDNS_PORT = 5353
        val group: InetAddress = InetAddress.getByName("224.0.0.251")

        /** Bonjour's own convention: 75 min for the shared/PTR + TXT, 2 min for host records. */
        const val TTL_SHARED = 4500
        const val TTL_HOST = 120

        /**
         * Interfaces worth joining on: up, multicast-capable, with an IPv4 address.
         *
         * Loopback is included ONLY when nothing else qualifies. That is not a testing
         * convenience — it is the offline laptop case, where two OSHI processes on one
         * machine should still find each other, and where joining `lo` is the difference
         * between a working local mesh and silence. When a real interface exists,
         * joining loopback as well just duplicates every packet.
         */
        fun eligibleInterfaces(): List<NetworkInterface> {
            val all = NetworkInterface.getNetworkInterfaces().toList()
            val real = all.filter {
                it.isUp && it.supportsMulticast() && !it.isLoopback &&
                    it.inetAddresses.toList().any { a -> a is Inet4Address }
            }
            if (real.isNotEmpty()) return real
            return all.filter {
                it.isUp && it.supportsMulticast() &&
                    it.inetAddresses.toList().any { a -> a is Inet4Address }
            }
        }

        /**
         * The interfaces to announce on, each with the ONE IPv4 address we will publish
         * for it — and the reason this is not simply "every eligible interface".
         *
         * Every A record we send carries the SAME hostname, so a resolver merges them all
         * and hands its client a list. iOS then takes **the first IPv4 in that list**
         * (OSHI/CrossPlatformMesh.swift:1585-1596) and dials it. Observed on this Mac:
         * announcing on en0 (192.168.1.11) and a second interface holding only a
         * self-assigned 169.254.x address made Apple's own resolver return
         *
         *     169.254.228.125, 192.168.1.11, 169.254.217.147
         *
         * — an unroutable APIPA address FIRST. A phone on the Wi-Fi would have dialled it
         * and timed out, and the desktop peer would look simply "not connectable".
         *
         * The fix cannot be a distinct hostname per interface: the SRV record is unique
         * and carries the cache-flush bit, so two SRVs for one instance would erase each
         * other. So we choose addresses instead — routable ones only, and link-local ONLY
         * when a machine has nothing else, which is the crossover-cable / ad-hoc case
         * where 169.254.x is genuinely the right answer.
         */
        fun advertisableInterfaces(): List<Pair<NetworkInterface, Inet4Address>> {
            fun pick(allowLinkLocal: Boolean): List<Pair<NetworkInterface, Inet4Address>> =
                eligibleInterfaces().mapNotNull { nif ->
                    val v4 = nif.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { allowLinkLocal || !it.isLinkLocalAddress }
                    if (v4 == null) null else nif to v4
                }
            return pick(allowLinkLocal = false).ifEmpty { pick(allowLinkLocal = true) }
        }
    }
}
