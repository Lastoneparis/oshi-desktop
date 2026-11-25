package com.oshi.desktop.lora

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * __MESHCORE_INTEROP_DESKTOP_2026_11_25__ The desktop's attachment to a MeshCore COMPANION radio — the
 * twin of Android's `MeshCoreLink` (same pump, same reply watchdog, same tick that drives retries and the
 * 1.5 s gap between text parts), over a [LoRaChannel] instead of BLE:
 *
 *  - USB serial ([LoRaChannel.Serial]) — the MeshCore **USB** companion firmware. The frames are wrapped
 *    app → radio '<' + len LE16 + frame, radio → app '>' + len LE16 + frame ([MeshCore.Serial]).
 *  - TCP ([LoRaChannel.Tcp], port [TCP_PORT]) — the WiFi companion firmware, same framing.
 *
 * Everything that is protocol — APP_START, the channel scan, "#oshi-bridge" in a free slot, the contact
 * table, DMs with ACK and retry, channel text, the "(i/n)" split, the duplicate filter — is
 * [MeshCoreSession], copied byte-for-byte from Android. This class only moves bytes, keeps time and turns
 * session events into callbacks.
 *
 * ONE thread does all of it: it opens the channel, polls the input (never a blocking read: a reader
 * blocked on a closed tty would keep stealing bytes from the next connection to the same device), writes
 * the next companion request when the previous one has been answered, and ticks the session every
 * [TICK_MS]. Public calls only queue into the session; the thread writes within one poll.
 *
 * A link that drops reconnects on its own (backoff 1 s → 30 s). When the USB device node is gone the
 * [status] says [Problem.UNPLUGGED], and plugging the radio back in brings it back without a click.
 */
class MeshCoreLink(
    private val channel: LoRaChannel,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    appName: String = "OSHI",
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    val session = MeshCoreSession(appName)

    enum class Status { OFF, CONNECTING, SYNCING, READY, LOST }

    enum class Problem {
        /** The USB device disappeared. */
        UNPLUGGED,
        /** The port could not be opened (busy, permission, gone). */
        OPEN_FAILED,
        /** Open, but nothing answered APP_START: not MeshCore companion firmware (or a BLE-only build). */
        NO_ANSWER,
        /** Every channel slot is taken: MeshCore users still work, OSHI-over-MeshCore does not. */
        NO_FREE_CHANNEL,
    }

    /** Text a MeshCore user wrote: [channelKey] null for a DM. See [MeshCoreThread] for the keys. */
    data class Inbound(
        val conversationKey: String,
        val channelKey: String?,
        val senderName: String?,
        val text: String,
        val isPublicChannel: Boolean,
    )

    /** Every callback runs on the link thread. */
    @Volatile var onInbound: (Inbound) -> Unit = {}
    @Volatile var onSendState: (messageId: String, state: MeshCoreSession.SendState) -> Unit = { _, _ -> }
    @Volatile var onJoin: (slot: Int, ok: Boolean) -> Unit = { _, _ -> }
    @Volatile var onAdvert: (ok: Boolean) -> Unit = {}
    /** OMP frames heard on "#oshi-bridge" (OSHI over MeshCore). */
    @Volatile var onOmpFrame: (MeshCoreSession.Event.Frame) -> Unit = {}
    /** Status, contacts, channels or the pending set changed: redraw. */
    @Volatile var onChanged: () -> Unit = {}

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var out: OutputStream? = null

    @Volatile var status: Status = Status.OFF; private set
    @Volatile var problem: Problem? = null; private set
    /** The radio's own 32-byte public key, from SELF_INFO. */
    @Volatile var selfPublicKey: ByteArray? = null; private set
    /** Public-key hex → our clock (ms) of the last advert heard this session. */
    private val heard = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private var lastWriteAt = 0L
    private var replySinceWrite = true
    private var lastTick = 0L
    private var lastPresenceCheck = 0L

    val label: String get() = channel.label
    val isReady: Boolean get() = status == Status.READY
    val selfName: String? get() = session.selfName
    val contacts: List<MeshCore.Contact> get() = session.contacts
    val userChannels: List<MeshCore.ChannelInfo> get() = session.userChannels
    val bridgeSlot: Int? get() = session.channelIdx
    val pendingTextIds: Set<String> get() = session.pendingTextIds
    fun heardAt(): Map<String, Long> = HashMap(heard)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        status = Status.CONNECTING
        thread = Thread({ runLoop() }, "oshi-meshcore-${channel.label}").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
    }

    // ─────────────────────────── MeshCore users ───────────────────────────

    /** A DM to `meshcore!<hex>`. True when queued; the outcome arrives on [onSendState]. */
    fun sendDirectText(messageId: String, conversationKey: String, text: String): Boolean {
        val prefix = MeshCoreThread.publicKeyPrefix(conversationKey) ?: return false
        if (!session.chatReady) return false
        return session.sendDirect(messageId, prefix, text, now()).also { if (it) onChanged() }
    }

    /** Text on the channel `mcchan!<name>`. True when queued; SENT then (never DELIVERED: no ACK on a channel). */
    fun sendChannelText(messageId: String, channelKey: String, text: String): Boolean {
        if (!session.chatReady) return false
        val idx = MeshCoreThread.channelIndexFor(channelKey, session.userChannels) ?: return false
        return session.sendChannel(messageId, idx, text, now()).also { if (it) onChanged() }
    }

    enum class JoinResult { QUEUED, ALREADY_JOINED, NOT_READY, NO_FREE_SLOT, INVALID }

    /** A hashtag channel: its secret is SHA-256("#name")[0:16], so anyone who knows the name can read it. */
    fun joinHashtagChannel(name: String): JoinResult {
        val n = name.trim().removePrefix("#")
        if (n.isEmpty() || n.any { it.isWhitespace() }) return JoinResult.INVALID
        val full = "#$n"
        return join(full, MeshCore.hashtagSecret(full))
    }

    /** A private channel shared as a name and a 16-byte secret. */
    fun joinPrivateChannel(name: String, secret: ByteArray): JoinResult {
        if (name.isBlank() || secret.size != 16) return JoinResult.INVALID
        return join(name.trim(), secret)
    }

    private fun join(name: String, secret: ByteArray): JoinResult {
        if (!session.chatReady) return JoinResult.NOT_READY
        if (name.toByteArray(Charsets.UTF_8).size > 31 || name == OshiBridgeWire.CHANNEL_NAME) return JoinResult.INVALID
        if (session.userChannels.any { it.name == name && it.secret.contentEquals(secret) }) return JoinResult.ALREADY_JOINED
        val slot = session.freeChannelSlot() ?: return JoinResult.NO_FREE_SLOT
        return if (session.joinChannel(slot, name, secret)) JoinResult.QUEUED else JoinResult.INVALID
    }

    fun refreshContacts() = session.refreshContacts()

    /** Announce this radio so MeshCore users can add it and write to it. False when not ready. */
    fun sendSelfAdvert(): Boolean = session.chatReady && session.sendSelfAdvert()

    // ─────────────────────────── the thread ───────────────────────────

    private fun runLoop() {
        var backoffMs = LoRaLink.INITIAL_BACKOFF_MS
        while (running.get()) {
            var conn: LoRaChannel.Conn? = null
            var answered = false
            try {
                if (!channel.stillThere()) {
                    setStatus(Status.LOST, Problem.UNPLUGGED)
                } else {
                    conn = channel.open()
                    out = conn.output
                    problem = null
                    setStatus(Status.SYNCING, null)
                    log("meshcore: attached to ${channel.label}")
                    session.start()
                    replySinceWrite = true
                    lastTick = 0L
                    answered = serve(conn.input)
                }
            } catch (e: Exception) {
                if (running.get()) {
                    val gone = !channel.stillThere()
                    log("meshcore: ${if (gone) "radio unplugged" else "${e.javaClass.simpleName}: ${e.message}"}")
                    setStatus(Status.LOST, when {
                        gone -> Problem.UNPLUGGED
                        conn == null -> Problem.OPEN_FAILED
                        else -> problem
                    })
                }
            } finally {
                out = null
                runCatching { conn?.close() }
                session.reset()
                // Text the link died under is FAILED now (the bubble offers Retry), not never.
                handle(session.onTick(now()))
            }
            if (!running.get()) break
            if (answered) backoffMs = LoRaLink.INITIAL_BACKOFF_MS
            // Unplugged: look for it again every couple of seconds, so plugging it back in is enough.
            val wait = if (problem == Problem.UNPLUGGED) minOf(backoffMs, REPLUG_POLL_MS) else backoffMs
            try { sleeper(wait) } catch (_: InterruptedException) { break }
            backoffMs = (backoffMs * 2).coerceAtMost(LoRaLink.MAX_BACKOFF_MS)
        }
        status = Status.OFF
        runCatching { onChanged() }
    }

    /** True once the radio answered at all. Returns (throws) when the stream ends. */
    private fun serve(input: InputStream): Boolean {
        var buffer = ByteArray(0)
        val chunk = ByteArray(1024)
        val openedAt = now()
        var gotSelfInfo = false
        while (running.get()) {
            val avail = input.available()
            if (avail > 0) {
                val n = input.read(chunk, 0, minOf(avail, chunk.size))
                if (n < 0) throw IOException("radio closed the stream")
                buffer += chunk.copyOfRange(0, n)
                while (true) {
                    val d = MeshCore.Serial.decode(buffer) ?: break
                    buffer = buffer.copyOfRange(d.consumed, buffer.size)
                    if (d.payload.isNotEmpty() && (d.payload[0].toInt() and 0xFF) == MeshCore.RESP_SELF_INFO) {
                        gotSelfInfo = true
                        MeshCore.selfPublicKey(d.payload)?.let { selfPublicKey = it }
                    }
                    onFrame(d.payload)
                }
                if (buffer.size > 2 * MeshCore.Serial.MAX_FRAME_BYTES) {
                    buffer = buffer.copyOfRange(buffer.size - MeshCore.Serial.MAX_FRAME_BYTES, buffer.size)
                }
            } else {
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { if (!running.get()) break }
            }
            val t = now()
            if (!gotSelfInfo && t - openedAt > NO_ANSWER_MS && problem != Problem.NO_ANSWER) {
                problem = Problem.NO_ANSWER
                log("meshcore: no answer to APP_START from ${channel.label} — not a MeshCore USB companion?")
                runCatching { onChanged() }
            }
            if (t - lastPresenceCheck > 1_000) {
                lastPresenceCheck = t
                if (!channel.stillThere()) throw IOException("device gone")
            }
            if (t - lastTick >= TICK_MS) {
                lastTick = t
                handle(session.onTick(t))
            }
            if (!replySinceWrite && t - lastWriteAt > REPLY_TIMEOUT_MS) {
                log("meshcore: a request went unanswered; moving on")
                replySinceWrite = true
                handle(session.writeFailed())
            }
            pump()
        }
        return gotSelfInfo
    }

    private fun onFrame(f: ByteArray) {
        if (f.isNotEmpty() && (f[0].toInt() and 0xFF) < 0x80) replySinceWrite = true
        handle(session.onFrame(f, now()))
    }

    /** Write the next companion request if none is waiting for its reply. */
    private fun pump() {
        val o = out ?: return
        val cmd = session.nextCommand(now()) ?: return
        lastWriteAt = now()
        replySinceWrite = false
        try {
            o.write(MeshCore.Serial.encode(cmd)); o.flush()
        } catch (e: IOException) {
            handle(session.writeFailed())
            throw e
        }
    }

    private fun handle(events: List<MeshCoreSession.Event>) {
        var changed = false
        for (e in events) {
            try {
                when (e) {
                    is MeshCoreSession.Event.Ready -> log("meshcore: #oshi-bridge in slot ${session.channelIdx}")
                    is MeshCoreSession.Event.NoFreeChannel -> {
                        problem = Problem.NO_FREE_CHANNEL
                        log("meshcore: every channel slot is in use; OSHI-over-MeshCore needs one for #oshi-bridge")
                        changed = true
                    }
                    is MeshCoreSession.Event.Frame -> onOmpFrame(e)
                    is MeshCoreSession.Event.ChatReady -> { setStatus(Status.READY, problem); log("meshcore: ready (${session.selfName ?: "unnamed"})") }
                    is MeshCoreSession.Event.ContactsChanged, is MeshCoreSession.Event.ChannelsChanged -> changed = true
                    is MeshCoreSession.Event.ContactHeard -> { heard[e.publicKeyHex] = now(); changed = true }
                    is MeshCoreSession.Event.DirectText -> onInbound(
                        Inbound(MeshCoreThread.contactKeyFor(e.senderPrefix, e.contact), null, e.contact?.name, e.text, false)
                    )
                    is MeshCoreSession.Event.ChannelText -> {
                        val ch = e.channel ?: continue   // a slot we have not read cannot be named or keyed
                        val key = MeshCoreThread.channelKey(ch.name)
                        onInbound(Inbound(key, key, e.senderName, e.text, MeshCore.isPublicChannel(ch.name, ch.secret)))
                    }
                    is MeshCoreSession.Event.Send -> { onSendState(e.messageId, e.state); changed = true }
                    is MeshCoreSession.Event.ChannelJoin -> { onJoin(e.idx, e.ok); changed = true }
                    is MeshCoreSession.Event.AdvertSent -> onAdvert(e.ok)
                }
            } catch (x: Exception) {
                log("meshcore: handler threw: ${x.message}")
            }
        }
        if (changed) runCatching { onChanged() }
    }

    private fun setStatus(s: Status, p: Problem?) {
        val changed = status != s || problem != p
        status = s
        problem = p
        if (changed) runCatching { onChanged() }
    }

    companion object {
        /** MeshCore companion_radio's TCP_PORT for WiFi builds. */
        const val TCP_PORT = 5000
        const val REPLY_TIMEOUT_MS = 10_000L
        /** Retries, ACK deadlines and the gap between text parts are checked this often. */
        const val TICK_MS = 250L
        /** Nothing back from APP_START in this long: say so (wrong firmware), keep listening. */
        const val NO_ANSWER_MS = 6_000L
        private const val POLL_MS = 15L
        private const val REPLUG_POLL_MS = 2_000L
    }
}
