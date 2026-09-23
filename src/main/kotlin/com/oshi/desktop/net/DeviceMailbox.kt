package com.oshi.desktop.net

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.store.AtomicFile
import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.store.PrekeyStore
import com.oshi.messenger.network.v2.OSHICryptoV2
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * __PER_DEVICE_MAILBOX_2026_09_23__ This install as ONE DEVICE of its account
 * (`ServerPatches/per_device_mailbox/CLIENT_SPEC.md`, design `docs/OSHI_DEVICE_SYNC_DIRECT.md` §15).
 *
 * What it owns:
 *  - the device keys, in the sealed [KeyVault]: `dk` is the devsync X25519 device key (the SAME
 *    vault entry `DesktopDevSync` uses, so one device has one id everywhere) and `dsk` is a new
 *    Ed25519 device signing key, never exported;
 *  - registration (`/v2/devices/register`, with the approval another device signed at devsync
 *    pairing), this device's own X3DH bundle, the removal alert, the "new device" alert;
 *  - the answer to "may this install publish the ACCOUNT bundle?" ([mayPublishAccountBundle]).
 *
 * GATE. Everything here is inert unless `/v2/config.device_mailbox_enabled` is true: no route is
 * called (the `dsk` may still be minted locally for the devsync relay upgrade, [relayCredentials]), and [active] is false so the router keeps today's path exactly.
 * When the flag turns false while running, [active] turns false on the next config read and the
 * registration is KEPT for when it returns (§3.1).
 *
 * THE ACCOUNT BUNDLE (the bug this file exists to stop). A desktop restored from the phone's
 * recovery key shares the identity; when both publish `/v2/keys/publish` without `deviceId`,
 * each resets the other's pool and senders land sessions on whichever one published last.
 *  - Flag on and registered: the account bundle is published ONLY by the device whose
 *    generation is live when it registers (`holdsAccountBundle`, §3.2) — never by the others,
 *    and never re-taken once another device replaced it.
 *  - Flag off (or not yet registered): a desktop that GENERATED its identity behaves exactly as
 *    before. A SHARED identity (imported, or no origin marker) publishes only when no account
 *    bundle exists or the live one is its own — it no longer overwrites the phone's. One
 *    escape hatch keeps a user who moved off the phone reachable: if a FOREIGN generation stays
 *    below the refill threshold for 24 h (its holder is not refilling it — gone), this install
 *    takes it over; and the user can force it ([takeOverAccountBundle]).
 */
class DeviceMailbox(
    private val identity: DesktopIdentity,
    private val vault: KeyVault,
    private val http: V2Http,
    private val keys: V2KeysClient,
    private val accountPrekeys: PrekeyStore,
    val devicePrekeys: PrekeyStore,
    private val config: V2ConfigGate,
    private val stateFile: File,
    private val identityOrigin: () -> IdentityStore.Origin = { IdentityStore.Origin.UNKNOWN },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : V2Http.DeviceRequestSigner {

    enum class Status { OFF, UNREGISTERED, NEEDS_APPROVAL, BAD_APPROVAL, TOO_MANY_DEVICES, CONFLICT, REGISTERED, REMOVED, ERROR }

    /** One row of `GET /v2/devices`. Names/platforms are NOT on the server (they come from devsync). */
    data class ServerDevice(
        val deviceId: String,
        val dsk: String,
        val registeredAt: Long,
        val lastSeenAt: Long,
        val approvedBy: String?,
        val hasBundle: Boolean,
    )

    data class Snapshot(
        val flagOn: Boolean,
        val status: Status,
        val deviceId: String?,
        val active: Boolean,
        val holdsAccountBundle: Boolean,
        val removedAlert: Boolean,
        val newDevices: List<String>,
        val devices: List<ServerDevice>,
        val lastError: String?,
        val pendingApproval: Boolean,
    )

    // ------------------------------------------------------------------ keys

    private val dkPriv: ByteArray by lazy { vault.getOrCreate(DEVICE_KEY_ACCOUNT) { random32() } }
    val dkPub: ByteArray by lazy { OSHICryptoV2.x25519PubFromPriv(dkPriv) }
    val deviceId: String by lazy { DeviceAuth.deviceIdFor(dkPub) }
    private val dskSeed: ByteArray by lazy { vault.getOrCreate(DSK_ACCOUNT) { random32() } }
    val dskPub: String by lazy { B64.encodeToString(DeviceAuth.publicKey(dskSeed)) }
    private val dkB64: String get() = B64.encodeToString(dkPub)
    private val identityNorm: String get() = DeviceAuth.normalizeIdentity(identity.userKey)

    /**
     * __DEVSYNC_DEVICE_AUTH_2026_09_23__ The devsync relay upgrade is signed by this SAME `dsk`
     * (docs/fixtures/devsync/relay_auth.json). Used by devsync whatever the mailbox flag: it mints
     * the local key if needed (nothing is sent anywhere by that), it never registers.
     */
    fun relayCredentials(): com.oshi.messenger.network.v2.devsync.RelayDeviceCredentials =
        com.oshi.messenger.network.v2.devsync.SeedRelayDeviceCredentials(dkPub, dskSeed)

    override fun signDeviceRequest(method: String, path: String, bodySha256Hex: String, timestamp: String): Pair<String, String> =
        deviceId to DeviceAuth.sign(dskSeed, DeviceAuth.deviceRequestString(deviceId, method, path, bodySha256Hex, timestamp))

    // ------------------------------------------------------------------ state

    private var st = State.load(stateFile)
    @Volatile private var status: Status = if (st.registered) Status.REGISTERED else Status.UNREGISTERED
    @Volatile private var lastError: String? = null
    @Volatile private var devices: List<ServerDevice> = emptyList()
    private var maintainedAt = 0L
    private var registerAttemptAt = 0L
    private var accountCheckAt = 0L
    private var accountCheckVerdict = false
    private val peerCache = ConcurrentHashMap<String, Pair<Long, List<String>>>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun onChange(l: () -> Unit) { listeners.add(l) }
    private fun changed() = listeners.forEach { runCatching { it() } }

    val flagOn: Boolean get() = config.deviceMailboxEnabled

    /** Device mode: the router polls/acks/sends as this device. */
    val active: Boolean get() = flagOn && st.registered && !st.removed

    val holdsAccountBundle: Boolean get() = st.manualHolder || st.holdsAccountBundle == true

    val isRegistered: Boolean get() = st.registered

    fun snapshot(): Snapshot = Snapshot(
        flagOn = flagOn,
        status = if (!flagOn) Status.OFF else status,
        deviceId = if (flagOn || st.registered) deviceId else null,
        active = active,
        holdsAccountBundle = holdsAccountBundle,
        removedAlert = st.removedAlert,
        newDevices = st.newDeviceAlerts.toList(),
        devices = devices,
        lastError = lastError,
        pendingApproval = freshApproval() != null,
    )

    // ------------------------------------------------------------------ lifecycle

    /**
     * Driven by the poll loop after the config refresh. Registers when it can, keeps this
     * device's bundle topped up and the device list fresh. Never throws.
     */
    @Synchronized
    fun tick() {
        if (!flagOn || !config.isEnabledCached()) return
        try {
            when {
                st.removed || st.optedOut -> {
                    // A removed device re-links only with a NEW approval (or an explicit relink).
                    if (freshApproval() != null) { st.removed = false; st.optedOut = false; save(); tryRegister() }
                    else status = if (st.removed) Status.REMOVED else Status.UNREGISTERED
                }
                !st.registered -> {
                    // Waiting for an approval: ask the server again at most once a minute, but
                    // at once when a new approval has just arrived.
                    val now = clock()
                    if (status == Status.NEEDS_APPROVAL && freshApproval() == null && now - registerAttemptAt < REGISTER_RETRY_MS) return
                    registerAttemptAt = now
                    tryRegister()
                }
                else -> maintain(force = false)
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            log("device mailbox: tick failed: $lastError")
        }
    }

    /** "Re-link this device": clears a removal and tries to register (approval may be needed). */
    @Synchronized
    fun relink() {
        st.removed = false; st.removedAlert = false; st.optedOut = false; save()
        if (flagOn) tryRegister()
        changed()
    }

    private fun tryRegister() {
        val list = listDevices() ?: run { status = Status.ERROR; return }
        if (list.any { it.deviceId == deviceId }) { onRegistered(list, created = false); return }
        val approval = freshApproval()
        if (list.isNotEmpty() && approval == null) {
            if (status != Status.NEEDS_APPROVAL) log("device mailbox: ${list.size} device(s) registered — this one needs an approval from one of them")
            status = Status.NEEDS_APPROVAL; changed(); return
        }
        val ts = clock()
        val body = JSONObject()
            .put("deviceId", deviceId).put("dk", dkB64).put("dsk", dskPub).put("ts", ts)
            .put("proof", DeviceAuth.sign(dskSeed, DeviceAuth.registerProofString(identityNorm, deviceId, dkB64, dskPub, ts.toString())))
            .put("adoptBacklog", true).put("retireLegacy", false)
        if (list.isNotEmpty() && approval != null) body.put("approval", approval)
        val resp = http.postJson("/v2/devices/register", body.toString().toByteArray(Charsets.UTF_8), withUserHeader = true)
        val err = errorOf(resp.body)
        when {
            resp.isSuccess -> onRegistered(list, created = true)
            resp.code == 403 && err == "approval-required" -> status = Status.NEEDS_APPROVAL
            resp.code == 403 && err == "bad-approval" -> { st.pendingApproval = null; save(); status = Status.BAD_APPROVAL }
            resp.code == 409 && err == "device-conflict" -> status = Status.CONFLICT
            resp.code == 409 && err == "device-mailbox-disabled" -> config.invalidate()
            resp.code == 429 -> status = Status.TOO_MANY_DEVICES
            else -> { status = Status.ERROR; lastError = "register HTTP ${resp.code} ${err ?: ""}".trim() }
        }
        if (!resp.isSuccess) log("device mailbox: register → HTTP ${resp.code} ${err ?: ""}")
        changed()
    }

    private fun onRegistered(list: List<ServerDevice>, created: Boolean) {
        val firstDevice = list.none { it.deviceId != deviceId }
        st.registered = true; st.removed = false; st.removedAlert = false; st.optedOut = false
        st.pendingApproval = null
        if (st.holdsAccountBundle == null || created) st.holdsAccountBundle = computeHolder(firstDevice)
        if (!st.seeded) { st.knownDevices.addAll(list.map { it.deviceId }); st.seeded = true }
        st.knownDevices.add(deviceId)
        save()
        status = Status.REGISTERED
        log("device mailbox: ${if (created) "registered" else "already registered"} as ${deviceId.take(8)} " +
            "(holds the account bundle: ${holdsAccountBundle})")
        val mine = list.firstOrNull { it.deviceId == deviceId }
        maintain(force = true, forceFullPublish = created || mine?.hasBundle != true)
        changed()
    }

    private fun maintain(force: Boolean, forceFullPublish: Boolean = false) {
        val now = clock()
        if (!force && now - maintainedAt < MAINTAIN_EVERY_MS) return
        maintainedAt = now
        publishDeviceBundle(forceFullPublish)
        refreshDevices()
        if (st.holdsAccountBundle == null) { st.holdsAccountBundle = computeHolder(firstDevice = false); save() }
    }

    private fun publishDeviceBundle(full: Boolean) {
        val count = if (full || !devicePrekeys.hasPublished()) OPK_TARGET else {
            val remaining = keys.deviceRemainingCount(deviceId)
            // 404 = the server has no bundle for us (e.g. after a re-register): republish whole.
            when {
                remaining == null -> OPK_TARGET
                remaining >= OPK_LOW_WATER -> return
                else -> OPK_TARGET - remaining
            }
        }
        val r = keys.publishDevice(deviceId, devicePrekeys, count)
        when {
            r.ok -> log("device mailbox: device bundle published (+$count, server holds ${r.value})")
            r.code == 410 -> onDeviceUnknown()
            r.code == 409 -> config.invalidate()
            else -> log("device mailbox: device bundle publish → HTTP ${r.code} ${r.error ?: ""}")
        }
    }

    private fun refreshDevices() {
        val list = listDevices() ?: return
        if (!st.seeded) { st.knownDevices.addAll(list.map { it.deviceId }); st.seeded = true; save(); return }
        // §3.7: alert UNPROMPTED on a device this one neither approved nor already knew.
        val fresh = list.filter { it.deviceId !in st.knownDevices }
        if (fresh.isNotEmpty()) {
            for (d in fresh) {
                st.knownDevices.add(d.deviceId)
                if (d.approvedBy != deviceId) st.newDeviceAlerts.add(d.deviceId)
            }
            save()
            if (fresh.any { it.approvedBy != deviceId }) log("device mailbox: ALERT — a new device was added to this account")
            changed()
        }
    }

    /**
     * `410 device-unknown`: removed by another device, a reset, or 30 days of silence. Device
     * mode stops (the router falls back to legacy polling on its next tick), the keys are kept,
     * and the user is told without being asked (§3.3).
     */
    @Synchronized
    fun onDeviceUnknown() {
        if (!st.registered && st.removed) return
        st.registered = false; st.removed = true; st.removedAlert = true
        save()
        status = Status.REMOVED
        log("device mailbox: ALERT — this device was removed from the account (410); back to the legacy mailbox")
        changed()
    }

    /** `409 device-mailbox-disabled`: the server turned the feature off. Re-read the flag now. */
    fun onMailboxDisabled() {
        config.invalidate()
        config.refresh(identity.userKey)
        changed()
    }

    fun dismissRemovedAlert() { st.removedAlert = false; save(); changed() }
    fun dismissNewDeviceAlerts() { st.newDeviceAlerts.clear(); save(); changed() }

    // ------------------------------------------------------------------ approval (devsync pairing)

    /**
     * The APPROVER side (§3.2 step 3): sign `OSHI-DEVICE-APPROVE/1` for a new device. Returns
     * `{by, ts, sig}` — the spec's `mailbox` object — or null when this device is not itself a
     * registered, active device (only a registered device's approval is accepted).
     */
    fun approvalFor(newDeviceId: String, newDsk: String, ts: Long = clock()): JSONObject? {
        if (!active || !DeviceAuth.isDeviceId(newDeviceId) || newDeviceId == deviceId) return null
        if (runCatching { Base64.getDecoder().decode(newDsk).size }.getOrNull() != 32) return null
        val sig = DeviceAuth.sign(dskSeed, DeviceAuth.approvalString(identityNorm, newDeviceId, newDsk, ts.toString()))
        return JSONObject().put("by", deviceId).put("ts", ts).put("sig", sig)
    }

    /**
     * For `DevSyncEngine.approvalExtras` (peer deviceId, peer DK public key): what to merge into
     * our `APPROVAL{approved:true}`. Always our own `dsk` (so the OTHER side can approve us), plus
     * `mailbox:{by,ts,sig}` when we already know the peer's `dsk`. Null while the flag is off.
     *
     * The approver can only sign once it knows the new device's `dsk`. Since
     * __DEVSYNC_DEVICE_AUTH_2026_09_23__ HELLO carries `dsk` as a first-class field (`Hello.dsk`,
     * docs/fixtures/devsync/noise_handshake.json) and `DesktopDevSync` wires `onPeerDsk` to
     * [notePeerDsk], so it is known as soon as the handshake completes, whichever side taps Allow
     * first. The new device's APPROVAL still carries it too (older peers).
     */
    fun approvalExtras(peerDeviceId: String, peerDkPub: ByteArray): JSONObject? {
        if (!flagOn) return null
        if (runCatching { DeviceAuth.deviceIdFor(peerDkPub) }.getOrNull() != peerDeviceId) return null
        val out = JSONObject().put("dsk", dskPub)
        st.peerDsk[peerDeviceId]?.let { dsk -> approvalFor(peerDeviceId, dsk)?.let { out.put("mailbox", it) } }
        return out
    }

    /** For `DevSyncEngine.onApprovalReceived`: learn the peer's `dsk`, keep its `mailbox` approval. */
    fun onApprovalReceived(peerDeviceId: String, body: JSONObject) {
        body.optString("dsk").takeIf { it.isNotEmpty() }?.let { notePeerDsk(peerDeviceId, it) }
        val m = body.optJSONObject("mailbox") ?: return
        if (m.optString("by") != peerDeviceId) {
            log("device mailbox: ignored an approval signed by ${m.optString("by").take(8)} relayed by ${peerDeviceId.take(8)}")
            return
        }
        acceptApproval(m)
    }

    /** A `{by, ts, sig}` approval for THIS device. Registration happens on the next [tick] (network I/O stays off the caller's thread). */
    fun acceptApproval(m: JSONObject): Boolean {
        if (!DeviceAuth.isDeviceId(m.optString("by")) || m.optString("sig").isEmpty() || m.optLong("ts", 0L) <= 0L) return false
        st.pendingApproval = JSONObject().put("by", m.getString("by")).put("ts", m.getLong("ts")).put("sig", m.getString("sig"))
        st.pendingApprovalAt = clock()
        save()
        log("device mailbox: received an approval from device ${m.getString("by").take(8)}")
        changed()
        return true
    }

    /** The peer's device signing key, learnt from devsync (APPROVAL or HELLO `dsk`). */
    fun notePeerDsk(peerDeviceId: String, dsk: String) {
        if (!DeviceAuth.isDeviceId(peerDeviceId)) return
        if (runCatching { Base64.getDecoder().decode(dsk).size }.getOrNull() != 32) return
        if (st.peerDsk[peerDeviceId] == dsk) return
        st.peerDsk[peerDeviceId] = dsk
        save()
    }

    /** What a devsync HELLO should carry for the peer to approve us in one round (`dsk`). */
    fun helloExtras(): JSONObject? = if (flagOn) JSONObject().put("dsk", dskPub) else null

    private fun freshApproval(): JSONObject? {
        val a = st.pendingApproval ?: return null
        val ts = a.optLong("ts", 0L)
        return a.takeIf { clock() - ts <= APPROVAL_MAX_AGE_MS }
    }

    // ------------------------------------------------------------------ device list

    /** `GET /v2/devices` (account-signed, owner only). Null on failure. */
    fun listDevices(): List<ServerDevice>? {
        val resp = http.get("/v2/devices", withUserHeader = true)
        if (resp.code == 409) { onMailboxDisabled(); return null }
        if (!resp.isSuccess) { lastError = "list HTTP ${resp.code}"; return null }
        return runCatching {
            val a = JSONObject(resp.body).optJSONArray("devices") ?: JSONArray()
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                ServerDevice(
                    deviceId = o.getString("deviceId"), dsk = o.optString("dsk"),
                    registeredAt = o.optLong("registeredAt"), lastSeenAt = o.optLong("lastSeenAt"),
                    approvedBy = o.optString("approvedBy").takeIf { it.isNotEmpty() && it != "null" },
                    hasBundle = o.optBoolean("hasBundle"),
                )
            }
        }.getOrNull()?.also { devices = it }
    }

    /** "Remove device" — any registered device may remove any other (device-signed by THIS one). */
    @Synchronized
    fun unregister(target: String): Result<Unit> {
        if (!active) return Result.failure(IllegalStateException("this device is not registered"))
        val body = JSONObject().put("deviceId", target).toString().toByteArray(Charsets.UTF_8)
        val resp = http.postJson("/v2/devices/unregister", body, withUserHeader = true, device = true)
        if (resp.code == 410) { onDeviceUnknown(); return Result.failure(IllegalStateException("this device was already removed")) }
        if (!resp.isSuccess) return Result.failure(IllegalStateException("unregister HTTP ${resp.code} ${errorOf(resp.body) ?: ""}"))
        if (target == deviceId) {
            // Our own choice: no alert, and no silent re-registration on the next tick.
            st.registered = false; st.optedOut = true; save()
            status = Status.UNREGISTERED
        } else {
            invalidatePeer(identity.userKey)
            listDevices()
        }
        changed()
        return Result.success(Unit)
    }

    /** "Reset all devices" (recovery only, when no device key survives). Every device then gets 410. */
    @Synchronized
    fun resetAll(): Result<Int> {
        val body = JSONObject().put("confirm", "RESET-ALL-DEVICES").toString().toByteArray(Charsets.UTF_8)
        val resp = http.postJson("/v2/devices/reset", body, withUserHeader = true)
        if (!resp.isSuccess) return Result.failure(IllegalStateException("reset HTTP ${resp.code} ${errorOf(resp.body) ?: ""}"))
        st.registered = false; st.removed = false; st.optedOut = false; st.holdsAccountBundle = null
        save()
        status = Status.UNREGISTERED
        invalidatePeer(identity.userKey)
        changed()
        return Result.success(runCatching { JSONObject(resp.body).optInt("removed") }.getOrDefault(0))
    }

    // ------------------------------------------------------------------ recipients (sender side)

    /** A peer's devices holding a bundle (10-min cache, §3.5). Null = could not ask. */
    fun peerDevices(peer: String): List<String>? {
        val k = DeviceAuth.normalizeIdentity(peer)
        peerCache[k]?.let { (at, list) -> if (clock() - at < PEER_CACHE_MS) return list }
        return keys.peerDevices(peer)?.also { peerCache[k] = clock() to it }
    }

    /** Our OTHER devices (self-fanout targets). */
    fun ownOtherDevices(): List<String>? = peerDevices(identity.userKey)?.filter { it != deviceId }

    fun replacePeerDevices(peer: String, list: List<String>) { peerCache[DeviceAuth.normalizeIdentity(peer)] = clock() to list }
    fun invalidatePeer(peer: String) { peerCache.remove(DeviceAuth.normalizeIdentity(peer)) }

    // ------------------------------------------------------------------ account bundle ownership

    /**
     * May this install publish (or refill) the ACCOUNT bundle — the one old senders use?
     *
     * @param remaining the live pool's count when this is a refill, null for a first publish.
     */
    @Synchronized
    fun mayPublishAccountBundle(remaining: Int?): Boolean {
        if (st.manualHolder) return true
        if (flagOn && st.registered && !st.removed) {
            // §3.2: every device but the holder MUST stop publishing it once registered.
            if (st.holdsAccountBundle != true) return false
            // The holder never RE-TAKES it: if another device replaced the live generation, it
            // is now theirs — publishing over it is the fight this file exists to end.
            return when (liveAccount()) {
                LiveVerdict.OURS, LiveVerdict.NONE -> true
                LiveVerdict.FOREIGN -> { st.holdsAccountBundle = false; save(); log("device mailbox: another device now holds the account bundle — stopped publishing it"); false }
                LiveVerdict.UNKNOWN -> false
            }
        }
        // Flag off / not registered. A desktop-generated identity: exactly today's behaviour.
        if (identityOrigin() == IdentityStore.Origin.GENERATED) return true
        return sharedIdentityMayPublish(remaining)
    }

    /** The user said "receive old-style messages on this computer" (REPL `/devices takeover`). */
    @Synchronized
    fun takeOverAccountBundle(on: Boolean) {
        st.manualHolder = on
        if (on) st.holdsAccountBundle = true
        save(); changed()
    }

    private enum class LiveVerdict { OURS, FOREIGN, NONE, UNKNOWN }

    private var liveCache: Pair<Long, V2KeysClient.LiveSpk>? = null

    /** GET /v2/keys/<me> pops an account OPK, so it is asked at most every 10 minutes. */
    private fun liveAccountRaw(): V2KeysClient.LiveSpk {
        liveCache?.let { (at, v) -> if (clock() - at < LIVE_CHECK_MS && v !is V2KeysClient.LiveSpk.Unknown) return v }
        return keys.liveAccountSignedPreKey().also { liveCache = clock() to it }
    }

    private fun liveAccount(): LiveVerdict = when (val live = liveAccountRaw()) {
        V2KeysClient.LiveSpk.None -> LiveVerdict.NONE
        V2KeysClient.LiveSpk.Unknown -> LiveVerdict.UNKNOWN
        is V2KeysClient.LiveSpk.Key ->
            if (accountPrekeys.hasPublished() && live.keyB64 == B64.encodeToString(accountPrekeys.currentSignedPreKey().pair.pub)) LiveVerdict.OURS
            else LiveVerdict.FOREIGN
    }

    private fun computeHolder(firstDevice: Boolean): Boolean? {
        if (st.manualHolder) return true
        return when (liveAccount()) {
            LiveVerdict.OURS -> true
            LiveVerdict.NONE -> firstDevice
            LiveVerdict.FOREIGN -> false
            LiveVerdict.UNKNOWN -> null
        }
    }

    private fun sharedIdentityMayPublish(remaining: Int?): Boolean {
        return when (val live = liveAccountRaw()) {
            V2KeysClient.LiveSpk.None -> true
            V2KeysClient.LiveSpk.Unknown -> false
            is V2KeysClient.LiveSpk.Key -> {
                if (liveAccount() == LiveVerdict.OURS) { st.takeoverSpk = null; save(); return true }
                // Another device's generation is live. Leave it — unless its holder is gone:
                // a pool that stays below the refill line for a day is not being refilled.
                val count = remaining ?: keys.remainingCount()
                val now = clock()
                if (count != null && count < OPK_LOW_WATER) {
                    if (st.takeoverSpk == live.keyB64 && now - st.takeoverSince >= ORPHAN_AFTER_MS) {
                        log("device mailbox: the account bundle of another device has not been refilled for " +
                            "${(now - st.takeoverSince) / 3_600_000} h — taking it over so contacts can still reach this account")
                        st.takeoverSpk = null; save()
                        true
                    } else {
                        if (st.takeoverSpk != live.keyB64) { st.takeoverSpk = live.keyB64; st.takeoverSince = now; save() }
                        if (accountCheckAt == 0L || now - accountCheckAt > LIVE_CHECK_MS) {
                            log("device mailbox: another device holds this account's bundle — not publishing over it")
                        }
                        accountCheckAt = now; accountCheckVerdict = false
                        false
                    }
                } else {
                    if (st.takeoverSpk != null) { st.takeoverSpk = null; save() }
                    if (accountCheckAt == 0L) log("device mailbox: another device holds this account's bundle — not publishing over it")
                    accountCheckAt = now
                    false
                }
            }
        }
    }

    // ------------------------------------------------------------------ persistence

    // Devsync calls (approvalExtras / onApprovalReceived) run on the sync thread and must not
    // wait behind a tick doing network I/O under `this`: they only touch concurrent fields and
    // serialise through this lock.
    private val saveLock = Any()
    private fun save() = synchronized(saveLock) { st.save(stateFile) }

    private fun errorOf(body: String): String? = runCatching { JSONObject(body).optString("error").ifEmpty { null } }.getOrNull()

    /** Non-secret bookkeeping: ids, flags, a public approval signature. Keys stay in the vault. */
    private class State {
        @Volatile var registered = false
        @Volatile var removed = false
        @Volatile var removedAlert = false
        @Volatile var optedOut = false
        @Volatile var holdsAccountBundle: Boolean? = null
        @Volatile var manualHolder = false
        @Volatile var seeded = false
        val knownDevices = java.util.concurrent.CopyOnWriteArraySet<String>()
        val newDeviceAlerts = java.util.concurrent.CopyOnWriteArraySet<String>()
        @Volatile var pendingApproval: JSONObject? = null
        @Volatile var pendingApprovalAt = 0L
        val peerDsk = ConcurrentHashMap<String, String>()
        @Volatile var takeoverSpk: String? = null
        @Volatile var takeoverSince = 0L

        fun save(file: File) {
            val o = JSONObject()
                .put("v", 1).put("registered", registered).put("removed", removed).put("removedAlert", removedAlert)
                .put("optedOut", optedOut).put("manualHolder", manualHolder).put("seeded", seeded)
                .put("knownDevices", JSONArray(knownDevices.toList())).put("newDeviceAlerts", JSONArray(newDeviceAlerts.toList()))
                .put("pendingApprovalAt", pendingApprovalAt).put("takeoverSince", takeoverSince)
                .put("peerDsk", JSONObject(HashMap(peerDsk) as Map<*, *>))
            holdsAccountBundle?.let { o.put("holdsAccountBundle", it) }
            pendingApproval?.let { o.put("pendingApproval", it) }
            takeoverSpk?.let { o.put("takeoverSpk", it) }
            AtomicFile.write(file, o.toString().toByteArray(Charsets.UTF_8))
        }

        companion object {
            fun load(file: File): State {
                val s = State()
                if (!file.isFile) return s
                runCatching {
                    val o = JSONObject(file.readText(Charsets.UTF_8))
                    s.registered = o.optBoolean("registered"); s.removed = o.optBoolean("removed")
                    s.removedAlert = o.optBoolean("removedAlert"); s.optedOut = o.optBoolean("optedOut")
                    s.manualHolder = o.optBoolean("manualHolder"); s.seeded = o.optBoolean("seeded")
                    if (o.has("holdsAccountBundle")) s.holdsAccountBundle = o.optBoolean("holdsAccountBundle")
                    o.optJSONArray("knownDevices")?.let { a -> for (i in 0 until a.length()) s.knownDevices.add(a.getString(i)) }
                    o.optJSONArray("newDeviceAlerts")?.let { a -> for (i in 0 until a.length()) s.newDeviceAlerts.add(a.getString(i)) }
                    s.pendingApproval = o.optJSONObject("pendingApproval")
                    s.pendingApprovalAt = o.optLong("pendingApprovalAt")
                    o.optJSONObject("peerDsk")?.let { p -> for (k in p.keys()) s.peerDsk[k] = p.getString(k) }
                    s.takeoverSpk = o.optString("takeoverSpk").ifEmpty { null }
                    s.takeoverSince = o.optLong("takeoverSince")
                }
                // A corrupt file degrades to "not registered": the next tick re-lists and finds
                // this device on the server (idempotent), so nothing is lost but a round trip.
                return s
            }
        }
    }

    companion object {
        /** MUST equal `DesktopDevSync.DEVICE_KEY_ACCOUNT`: one device key, one deviceId, for devsync and the mailbox. */
        const val DEVICE_KEY_ACCOUNT = "devsync-device-key-v1"
        const val DSK_ACCOUNT = "device-mailbox-dsk-v1"
        const val FILE_NAME = "device-mailbox.json"
        const val OPK_TARGET = 20
        const val OPK_LOW_WATER = 5
        const val PEER_CACHE_MS = 10 * 60 * 1000L
        const val MAINTAIN_EVERY_MS = 10 * 60 * 1000L
        const val LIVE_CHECK_MS = 10 * 60 * 1000L
        const val APPROVAL_MAX_AGE_MS = 15 * 60 * 1000L
        const val ORPHAN_AFTER_MS = 24 * 3600 * 1000L
        const val REGISTER_RETRY_MS = 60 * 1000L
        private val B64 = Base64.getEncoder()
        private fun random32() = ByteArray(32).also(SecureRandom()::nextBytes)
    }
}
