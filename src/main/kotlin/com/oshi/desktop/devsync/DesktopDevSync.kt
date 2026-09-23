package com.oshi.desktop.devsync

import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.mesh.MdnsService
import com.oshi.desktop.net.V2Http
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.LocalDataKeys
import com.oshi.desktop.store.SealedJsonFile
import com.oshi.messenger.network.v2.devsync.DevSyncConfig
import com.oshi.messenger.network.v2.devsync.DevSyncEngine
import com.oshi.messenger.network.v2.devsync.DevSyncListener
import com.oshi.messenger.network.v2.devsync.DevSyncStateStore
import com.oshi.messenger.network.v2.devsync.LanDiscovery
import com.oshi.messenger.network.v2.devsync.LinkedDevice
import com.oshi.messenger.network.v2.devsync.PairingRequest
import com.oshi.messenger.network.v2.devsync.SessionInfo
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * __DEVSYNC_DIRECT_2026_09_22__ Direct own-device sync on the desktop
 * (docs/OSHI_DEVICE_SYNC_DIRECT.md), over the shared Kotlin core compiled from the Android tree.
 *
 *  - Gate: `/v2/config.devsync_enabled` (default OFF), or the local override (`OSHI_DEVSYNC_FORCE=1`,
 *    or the persisted setting `/devsync on`). Relay and LAN follow `devsync_relay_enabled` /
 *    `devsync_lan_enabled` (default on).
 *  - When on: the legacy `/sync` and `/sync legacy` REPL commands refuse (the desktop never ran a
 *    30 s `/api/sync` timer; those manual pushes are its only stored-sync path) and
 *    `POST /v2/devsync/purge-legacy` is called once.
 *  - The desktop keeps the relay socket while it runs (design §9); [tick] is driven by the poll loop.
 */
class DesktopDevSync(
    private val client: OshiClient,
    private val home: File,
    private val log: (String) -> Unit = {},
    /** mDNS for `_oshi-devsync._tcp`; tests pass `{ null }` and dial loopback directly. */
    private val lanDiscoveryFactory: () -> LanDiscovery? = { MdnsDevSyncDiscovery(log) },
) {
    data class Snapshot(
        val enabled: Boolean,
        val serverEnabled: Boolean,
        val override: Boolean?,
        val running: Boolean,
        val deviceId: String?,
        val linked: List<LinkedDevice>,
        val pending: List<PairingRequest>,
        val sessions: List<SessionInfo>,
        val lastAlert: String?,
        val legacyPurged: Boolean,
    )

    private val settingsFile = File(home, "devsync-settings.json")
    @Volatile private var engine: DevSyncEngine? = null
    @Volatile private var serverEnabled = false
    @Volatile private var relayEnabled = true
    @Volatile private var lanEnabled = true
    @Volatile private var flagsAt = 0L
    @Volatile private var lastAlert: String? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val stateStore: DevSyncStateStore by lazy {
        SealedStateStore(File(home, "devsync"), LocalDataKeys.derive(LocalDataKeys.root(client.vault), STATE_PURPOSE))
    }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun onChange(l: () -> Unit) { listeners.add(l) }

    // __DEVSYNC_READ_STATE_2026_09_23__ read state (design §8.7)
    val readMarks: DesktopReadMarks by lazy { DesktopReadMarks(stateStore) }
    private val readListeners = CopyOnWriteArrayList<(String, Long) -> Unit>()

    /** Another device read [nativeConversationId] up to readUpTo (ms): the window recounts its badge. */
    fun onRemoteRead(l: (nativeConversationId: String, readUpToMs: Long) -> Unit) { readListeners.add(l) }

    /**
     * The user opened [nativeConversationId]: everything received in it so far is read here. Moves
     * this conversation's READ_STATE mark forward (never emits a receipt; that is the window's own
     * business) and pushes it to open sessions at the next tick.
     */
    fun noteRead(nativeConversationId: String) {
        val conv = when {
            com.oshi.desktop.store.ExportV2.isGroupId(nativeConversationId) ->
                com.oshi.messenger.network.v2.devsync.SyncConversation.group(nativeConversationId).id
            runCatching { java.util.Base64.getDecoder().decode(com.oshi.desktop.store.ExportV2.canonicalKey(nativeConversationId)).size == 32 }.getOrDefault(false) ->
                com.oshi.messenger.network.v2.devsync.SyncConversation.direct(nativeConversationId).id
            else -> return   // desktop.local threads have no unread state to share
        }
        val newest = runCatching { client.messages.messages(nativeConversationId).filter { !it.fromMe }.maxOfOrNull { it.sentAtMs } }.getOrNull() ?: return
        // __DEVSYNC_READ_FRESH_2026_09_23__ mirrored at once (debounced), not at the next poll tick.
        if (readMarks.noteLocal(conv, newest)) engine?.onLocalStateChanged()
    }

    /**
     * __DEVSYNC_REJOIN_2026_09_23__ The group ingest's tombstone: true when [groupId] was left on
     * this account (the sealed devsync left-groups state) and not rejoined since (§8.4 rejoin rule).
     */
    fun refusesGroup(groupId: String): Boolean = DesktopDevSyncStore.leftOnThisAccount(stateStore, groupId)

    /**
     * __GROUP_E2E_V2_2026_09_23__ The user left [groupId] on THIS desktop: record the tombstone
     * (travels to own devices as `{left:true, leftAt}`, C.16) and mirror it now.
     */
    fun noteLocalLeave(groupId: String, name: String) {
        DesktopDevSyncStore.recordLocalLeave(stateStore, groupId, System.currentTimeMillis(), name)
        engine?.onLocalStateChanged()
    }
    /** __GROUP_PARITY_2026_09_23__ joining by invite link lifts a synced leave. */
    fun noteLocalJoin(groupId: String) {
        DesktopDevSyncStore.recordLocalJoin(stateStore, groupId, System.currentTimeMillis())
        engine?.onLocalStateChanged()
    }
    private fun changed() = listeners.forEach { runCatching { it() } }

    // ------------------------------------------------------------------ gate

    /** null = follow the server. */
    val override: Boolean?
        get() = when {
            System.getenv("OSHI_DEVSYNC_FORCE") == "1" -> true
            else -> runCatching { JSONObject(settingsFile.readText()).opt("override") as? Boolean }.getOrNull()
        }

    fun setOverride(on: Boolean?) {
        val o = JSONObject()
        if (on != null) o.put("override", on)
        settingsFile.writeText(o.toString())
        DesktopPaths.makePrivate(settingsFile)
        flagsAt = 0
        tickInBackground()
    }

    /** [tick] does network I/O: never on the caller's (possibly UI) thread. */
    fun tickInBackground() {
        Thread({ runCatching { tick() }.onFailure { log("devsync: tick failed: ${it.javaClass.simpleName}") } }, "oshi-devsync-gate")
            .apply { isDaemon = true; start() }
    }

    val enabled: Boolean get() = override ?: serverEnabled

    private fun refreshFlags() {
        if (System.currentTimeMillis() - flagsAt < FLAGS_TTL_MS) return
        flagsAt = System.currentTimeMillis()
        try {
            val req = HttpRequest.newBuilder(URI.create("${client.serverUrl}/v2/config")).timeout(Duration.ofSeconds(15)).GET().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() in 200..299) {
                val o = JSONObject(resp.body())
                serverEnabled = o.optBoolean("devsync_enabled", false)
                relayEnabled = o.optBoolean("devsync_relay_enabled", true)
                lanEnabled = o.optBoolean("devsync_lan_enabled", true)
            }
        } catch (e: Exception) {
            // Fail closed on the first answer; keep the last one afterwards.
        }
    }

    /** Called from the client's poll loop (and on demand): apply the gate. */
    @Synchronized
    fun tick() {
        refreshFlags()
        if (!enabled) { stopEngine(); return }
        purgeLegacyOnce()
        if (engine?.isRunning != true) startEngine()
        mirrorLocalChanges()
    }

    private var lastFingerprint = 0L
    @Volatile private var store: DesktopDevSyncStore? = null

    /**
     * Near-live mirroring (design §9): the poll loop ticks every few seconds; when this machine's
     * history changed since the last tick (a message sent or received, a status, a group, an alias),
     * every open session re-runs the diff, and the peer pulls exactly what changed. A change that
     * devsync itself applied triggers one extra SUMMARY exchange whose digests then match: no loop.
     */
    private fun mirrorLocalChanges() {
        val e = engine ?: return
        // __DEVSYNC_PROFILE_2026_09_23__ stamp a nickname edit close to when it was made (§8.6 updatedAt).
        runCatching { store?.refreshProfileStamp() }
        val fp = runCatching {
            var h = 1125899906842597L
            for (c in client.messages.conversations()) {
                h = 31 * h + c.conversationId.hashCode()
                h = 31 * h + c.messageCount
                h = 31 * h + c.lastActivityMs
                h = 31 * h + (c.lastMessage?.let { it.deliveryStatus.rank * 7 + (it.editedAtMs ?: 0L).hashCode() + it.reactions.hashCode() } ?: 0)
            }
            h = 31 * h + client.groups.all().hashCode()
            // __DEVSYNC_READ_STATE_2026_09_23__ profile + read marks move the fingerprint too.
            h = 31 * h + (client.ownNickname?.hashCode() ?: 0)
            h = 31 * h + readMarks.all().hashCode()
            31 * h + client.contacts.all().hashCode()
        }.getOrNull() ?: return
        if (lastFingerprint != 0L && fp != lastFingerprint) e.onLocalStateChanged()
        lastFingerprint = fp
    }

    /** A 207 / 401 / network failure is retried, but not on every 3 s poll tick. */
    @Volatile private var lastPurgeAttemptMs = 0L

    private fun purgeLegacyOnce() {
        val s = runCatching { JSONObject(settingsFile.readText()) }.getOrDefault(JSONObject())
        if (s.optBoolean("legacyPurged", false)) return
        val now = System.currentTimeMillis()
        if (now - lastPurgeAttemptMs < PURGE_RETRY_MS) return
        lastPurgeAttemptMs = now
        val r = runCatching {
            V2Http(DesktopV2Signer(client.identity), client.serverUrl).postEmpty("/v2/devsync/purge-legacy", withUserHeader = true)
        }.getOrNull() ?: return
        if (r.code == 200) {
            settingsFile.writeText(s.put("legacyPurged", true).toString())
            DesktopPaths.makePrivate(settingsFile)
        }
        log("devsync: legacy sync purge → HTTP ${r.code}")
    }

    val legacyPurged: Boolean
        get() = runCatching { JSONObject(settingsFile.readText()).optBoolean("legacyPurged", false) }.getOrDefault(false)

    // ------------------------------------------------------------------ engine

    private fun startEngine() {
        val store = DesktopDevSyncStore(
            selfAddress = { client.address },
            messages = client.messages,
            groups = client.groups,
            contacts = client.contacts,
            mediaDir = client.mediaDir,
            vault = client.mediaVault,
            state = stateStore,
            ownNickname = { client.ownNickname },
            adoptNickname = { name -> client.adoptOwnNicknameFromOwnDevice(name) },
            readMarks = readMarks,
            onRemoteRead = { native, upTo -> readListeners.forEach { l -> runCatching { l(native, upTo) } } },
        )
        this.store = store
        val cfg = DevSyncConfig(
            deviceName = client.displayName.ifBlank { "Desktop" },
            platform = "desktop",
            appVersion = "desktop-${com.oshi.desktop.net.V2ConfigGate.DESKTOP_BUILD}",
            cacheDir = File(home, "devsync-cache"),
        )
        val e = DevSyncEngine(
            cfg, client.identity.identity.priv,
            { client.vault.getOrCreate(DEVICE_KEY_ACCOUNT) { com.oshi.messenger.network.v2.devsync.DevSyncCrypto.randomBytes(32) } },
            store, stateStore,
        ) { line -> log(line) }
        // __PER_DEVICE_MAILBOX_2026_09_23__ carry the signed mailbox approval in APPROVAL
        // (`dsk`, `mailbox:{by,ts,sig}` — CLIENT_SPEC.md §3.2). Inert while the flag is off.
        // __DEVSYNC_DEVICE_AUTH_2026_09_23__ our `dsk` rides in HELLO and the peer's is noted as soon as
        // the handshake proves its id, so the approval can be signed whichever side taps Allow first.
        // The same `dsk` signs the relay upgrade (one device signing key).
        e.deviceDsk = { client.deviceMailbox.dskPub }
        e.onPeerDsk = { peerId, dsk -> client.deviceMailbox.notePeerDsk(peerId, dsk) }
        e.approvalExtras = { peerId, peerDk -> client.deviceMailbox.approvalExtras(peerId, peerDk) }
        e.onApprovalReceived = { peerId, body -> client.deviceMailbox.onApprovalReceived(peerId, body) }
        e.addListener(object : DevSyncListener {
            override fun onDevicesChanged() = changed()
            override fun onPairingRequest(request: PairingRequest) = changed()
            override fun onPairingResolved(peerId: String, linked: Boolean) = changed()
            override fun onSessionChanged(info: SessionInfo) = changed()
            override fun onAlert(kind: String, deviceName: String) { lastAlert = "$kind:$deviceName"; log("devsync alert: $kind"); changed() }
        })
        e.start(
            lanDiscovery = if (lanEnabled || override == true) lanDiscoveryFactory() else null,
            // __DEVSYNC_DEVICE_AUTH_2026_09_23__ also signed by this install's `dsk` (same vault dk as the engine).
            relayConnector = if (relayEnabled || override == true)
                DesktopRelayConnector(client.serverUrl, DesktopV2Signer(client.identity), { client.deviceMailbox.relayCredentials() }, log) else null,
            lanEnabled = lanEnabled || override == true,
        )
        engine = e
        // __DEVSYNC_LIVE_UPGRADE_2026_09_23__ every history write is mirrored at once (debounced in the
        // engine); the poll-driven fingerprint in [tick] stays as the safety net (groups, contacts).
        val hook: (String) -> Unit = { _ -> if (engine === e) e.onLocalStateChanged() }
        client.messages.addChangeListener(hook)
        storeHook = hook
        log("devsync: started as device ${e.deviceId.take(8)}")
        changed()
    }

    private var storeHook: ((String) -> Unit)? = null

    @Synchronized
    fun stop() = stopEngine()

    private fun stopEngine() {
        val e = engine ?: return
        engine = null
        storeHook?.let { client.messages.removeChangeListener(it) }
        storeHook = null
        runCatching { e.stop() }
        changed()
    }

    fun snapshot(): Snapshot {
        val e = engine
        return Snapshot(
            enabled = enabled, serverEnabled = serverEnabled, override = override, running = e?.isRunning == true,
            deviceId = e?.deviceId, linked = e?.linkedDevices() ?: emptyList(),
            pending = runCatching { e?.pendingApprovals() }.getOrNull() ?: emptyList(),
            sessions = runCatching { e?.sessionsInfo() }.getOrNull() ?: emptyList(),
            lastAlert = lastAlert, legacyPurged = legacyPurged,
        )
    }

    fun approve(peerId: String, allow: Boolean) { engine?.approve(peerId, allow) }
    fun revoke(peerId: String) { engine?.revoke(peerId) }
    fun syncNow() { engine?.syncNow() ?: tickInBackground() }
    /** Test/diagnostic: dial a LAN peer directly (host:port). */
    fun dial(host: String, port: Int) { engine?.lan?.dial(host, port) }
    val engineOrNull: DevSyncEngine? get() = engine

    companion object {
        const val DEVICE_KEY_ACCOUNT = "devsync-device-key-v1"
        const val STATE_PURPOSE = "devsync-v1"
        private const val FLAGS_TTL_MS = 60_000L
        private const val PURGE_RETRY_MS = 60_000L
    }
}

/** Sealed (AES-GCM, HKDF subkey of the local-data root) named JSON blobs under `<home>/devsync/`. */
internal class SealedStateStore(private val dir: File, private val key: ByteArray) : DevSyncStateStore {
    override fun read(name: String): String? =
        runCatching { SealedJsonFile.read(File(dir, "$name.json"), key, "devsync:$name")?.json }.getOrNull()

    override fun write(name: String, json: String) {
        DesktopPaths.ensurePrivateDir(dir)
        runCatching { SealedJsonFile.write(File(dir, "$name.json"), key, "devsync:$name", json) }
    }
}

/** `_oshi-devsync._tcp` on the desktop's own mDNS responder (a second [MdnsService] instance). */
internal class MdnsDevSyncDiscovery(private val log: (String) -> Unit) : LanDiscovery {
    private var mdns: MdnsService? = null

    override fun start(port: Int, txt: Map<String, String>, onFound: (String, Int, Map<String, String>) -> Unit) {
        this.port = port
        this.onFound = onFound
        val m = MdnsService(
            instanceLabel = LanDiscovery.instanceName(),
            hostLabel = "oshi-" + LanDiscovery.instanceName(),
            port = port,
            txtEntries = txt.map { (k, v) -> "$k=$v" },
            onServiceFound = { s -> onFound(s.host, s.port, s.txt) },
            log = log,
            serviceTypeName = LanDiscovery.SERVICE_TYPE + ".local.",
            requiredTxtKey = null,   // devsync TXT has no `pk` (v/t only); LanTransport checks v + tag
        )
        mdns = m
        runCatching { m.start() }.onFailure { log("devsync mDNS failed: ${it.javaClass.simpleName}") }
    }

    private var port = 0
    private var onFound: ((String, Int, Map<String, String>) -> Unit)? = null

    /**
     * Hourly tag rotation = a NEW advertisement (§5.1: "8 random hex characters per advertisement"):
     * goodbye for the old instance, then fresh instance AND host labels. Re-announcing the new TXT
     * under the old names (as before) let a LAN observer link the device across hours by its
     * instance/host name, which defeats the rotating tag. Android re-registers under a new name too.
     */
    override fun updateTxt(txt: Map<String, String>) {
        val cb = onFound ?: return
        runCatching { mdns?.stop() }
        start(port, txt, cb)
    }

    override fun stop() {
        runCatching { mdns?.stop() }
        mdns = null
        onFound = null
    }
}
