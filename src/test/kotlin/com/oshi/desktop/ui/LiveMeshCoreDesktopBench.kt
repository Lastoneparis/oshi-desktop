package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.i18n.Strings
import com.oshi.desktop.lora.LoRaChannel
import com.oshi.desktop.lora.MeshCore
import com.oshi.desktop.lora.MeshCoreLink
import com.oshi.desktop.lora.MeshCoreSession
import com.oshi.desktop.lora.MeshCoreThread
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.ui.components.ConversationFilter
import com.oshi.desktop.ui.components.ConversationListPane
import com.oshi.desktop.ui.components.LoRaPane
import com.oshi.desktop.ui.components.ThreadPane
import com.oshi.desktop.ui.components.ViewOnceFacts
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.components.loRaPaneHost
import com.oshi.desktop.ui.state.ChatShellModel
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * __MESHCORE_INTEROP_DESKTOP_2026_11_25__ The DESKTOP APP's own code against two REAL MeshCore companion
 * radios on USB: a real [OshiClient] (throwaway home, in-memory secrets, never started on the network)
 * and its real [ChatShellModel] drive radio "desk"; radio "peer" is a plain [MeshCoreLink] standing in for
 * a MeshCore user. Every step is what a person does in the window (Connect, write, send, join) and every
 * check reads what the window would show (rows, delivery marks, the notification). Screens are rendered
 * from the real composables with the live state.
 *
 *     OSHI_LIVE_MESHCORE=1 OSHI_MC_DESK=/dev/cu.usbmodemX OSHI_MC_PEER=/dev/cu.usbmodemY \
 *       OSHI_MC_SHOTS=/tmp/shots ./gradlew test --tests 'com.oshi.desktop.ui.LiveMeshCoreDesktopBench'
 *
 * Transmits about twenty short LoRa packets. Results go to `$OSHI_MC_SHOTS/results.txt`.
 */
class LiveMeshCoreDesktopBench {
    private val results = ArrayList<Triple<String, Boolean, String>>()
    private val t0 = System.currentTimeMillis()
    private fun log(s: String) = println("%7.2f %s".format((System.currentTimeMillis() - t0) / 1000.0, s))
    private fun check(name: String, ok: Boolean, detail: String = "") { results += Triple(name, ok, detail); log("CHECK ${if (ok) "PASS" else "FAIL"} $name $detail") }
    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (runCatching(cond).getOrDefault(false)) return true; Thread.sleep(100) }
        return runCatching(cond).getOrDefault(false)
    }

    @Test
    fun deskAppTalksToAMeshCoreUser() {
        assumeTrue(System.getenv("OSHI_LIVE_MESHCORE") == "1")
        val deskPath = System.getenv("OSHI_MC_DESK")!!
        val peerPath = System.getenv("OSHI_MC_PEER")!!
        val shots = File(System.getenv("OSHI_MC_SHOTS") ?: "build/meshcore-live").apply { mkdirs() }
        val home = Files.createTempDirectory("oshi-mc-live").toFile()
        val logs = CopyOnWriteArrayList<String>()
        val client = OshiClient(home = home, secretStore = InMemorySecretStore(), displayName = "desk", log = { logs += it; println("  [client] $it") })
        val notes = CopyOnWriteArrayList<Pair<String?, String?>>()
        val model = ChatShellModel(client, worker = { it.run() }, onIncomingNotification = { s, p -> notes += s to p },
            notificationPreviewEnabled = { true }, windowVisible = { false })
        model.attach()
        val peer = MeshCoreLink(LoRaChannel.Serial(peerPath), log = { println("  [peer] $it") }, appName = "peer-harness")
        val peerIn = CopyOnWriteArrayList<MeshCoreLink.Inbound>()
        val peerArrivals = CopyOnWriteArrayList<Long>()
        val peerJoins = CopyOnWriteArrayList<Pair<Int, Boolean>>()
        peer.onInbound = { peerIn += it; peerArrivals += System.currentTimeMillis() }
        peer.onJoin = { s, ok -> peerJoins += s to ok }
        var peerJoinedSlot: Int? = null
        try {
            // ── Connect (auto-detect) ──
            val attach = client.radioAttachAuto(deskPath)
            check("Connect detects MeshCore on $deskPath", attach == OshiClient.RadioAttach.MESHCORE, "$attach")
            check("desk radio ready (contacts + channels read)", waitFor(30_000) { client.meshCoreReady }, "status=${client.meshCoreLink()?.status}")
            peer.start()
            check("peer radio ready", waitFor(30_000) { peer.isReady })
            val deskKey = client.meshCoreLink()!!.selfPublicKey!!
            val peerKey = peer.selfPublicKey!!
            log("desk=${client.meshCoreLink()!!.selfName} ${MeshCore.hex(deskKey).take(12)} peer=${peer.selfName} ${MeshCore.hex(peerKey).take(12)}")

            // ── Adverts so each radio has the other as a contact ──
            check("desk: Announce me", client.meshCoreAdvert())
            waitFor(20_000) { peer.contacts.any { it.publicKey.contentEquals(deskKey) } || peer.heardAt().containsKey(MeshCore.hex(deskKey)) }
            Thread.sleep(2_500)
            peer.sendSelfAdvert()
            waitFor(20_000) { client.meshCoreLink()!!.heardAt().containsKey(MeshCore.hex(peerKey)) }
            Thread.sleep(1_500)
            client.meshCoreRefreshContacts(); peer.refreshContacts()
            check("desk lists peer as a MeshCore contact", waitFor(15_000) { client.meshCoreLink()!!.contacts.any { it.publicKey.contentEquals(peerKey) } },
                "${client.meshCoreLink()!!.contacts.map { it.name }}")
            check("peer lists desk", waitFor(15_000) { peer.contacts.any { it.publicKey.contentEquals(deskKey) } })
            val peerConv = MeshCoreThread.contactKey(peerKey)
            val deskConvAtPeer = MeshCoreThread.contactKey(deskKey)

            // ── DM desk → peer, from the composer ──
            model.select(peerConv)
            check("thread label is the contact's name", model.state.thread?.title == peer.selfName, "${model.state.thread?.title}")
            check("composer enabled, text only", model.state.thread?.composer?.enabled == true && model.state.thread?.composer?.attachEnabled == false)
            val dm1 = "Bonjour depuis OSHI Desktop"
            model.draft(dm1); model.send()
            val ok1 = waitFor(60_000) { client.history(peerConv).any { it.fromMe && it.content == dm1 && it.deliveryStatus == DeliveryStatus.DELIVERED } }
            check("DM desk→peer shows DELIVERED (ACK)", ok1, "${client.history(peerConv).lastOrNull()?.deliveryStatus}")
            check("peer received the DM", waitFor(10_000) { peerIn.any { it.text == dm1 && it.channelKey == null } })

            // ── DM peer → desk: row + notification that opens the thread ──
            val dm2 = "Reply from a MeshCore user"
            notes.clear()
            peer.sendDirectText("p1", deskConvAtPeer, dm2)
            model.select(null)
            val got2 = waitFor(60_000) { client.history(peerConv).any { !it.fromMe && it.content == dm2 } }
            check("DM peer→desk lands in the contact's thread", got2)
            check("notification fired with sender + text", waitFor(5_000) { notes.any { it.first == peer.selfName && it.second == dm2 } }, "$notes")
            check("notification opens the right thread", model.lastNotifiedConversationId == peerConv, "${model.lastNotifiedConversationId}")

            // ── Long DM: (1/n) parts, ~1.5 s apart, all ACKed ──
            model.select(peerConv)
            val long = "This is a long message from the desktop to check that MeshCore users get it as numbered parts, " +
                "each sent after the previous one was acknowledged, one and a half seconds apart, in order, without duplicates."
            check("composer counter says it will be split", (model.state.thread?.meshCore?.count(long)?.parts ?: 0) >= 2,
                "${model.state.thread?.meshCore?.count(long)}")
            peerArrivals.clear(); val before = peerIn.size
            model.draft(long); model.send()
            val ok3 = waitFor(120_000) { client.history(peerConv).any { it.fromMe && it.content == long && it.deliveryStatus == DeliveryStatus.DELIVERED } }
            check("long DM DELIVERED", ok3, "${client.history(peerConv).lastOrNull()?.deliveryStatus}")
            Thread.sleep(2_000)
            val parts = peerIn.drop(before).filter { it.channelKey == null }.map { it.text }
            check("peer got numbered parts in order, no duplicate", parts.isNotEmpty() && parts.mapIndexed { i, p -> p.startsWith("(${i + 1}/${parts.size}) ") }.all { it },
                parts.joinToString(" | ") { it.take(14) })
            val gaps = peerArrivals.zipWithNext { a, b -> b - a }
            check("parts arrived ≥1.4 s apart", gaps.isNotEmpty() && gaps.all { it >= 1_400 }, "gaps=$gaps ms")

            // ── Public channel both ways ──
            val pub = MeshCoreThread.channelKey("Public")
            check("Public channel is in the conversation list", model.state.conversations.any { it.id == pub })
            model.select(pub)
            val c1 = "Hello Public from OSHI Desktop"
            model.draft(c1); model.send()
            check("Public desk→peer: row SENT", waitFor(30_000) { client.history(pub).any { it.fromMe && it.content == c1 && it.deliveryStatus == DeliveryStatus.SENT } })
            check("peer heard it on Public with desk's name", waitFor(30_000) { peerIn.any { it.channelKey == pub && it.text == c1 && it.senderName == client.meshCoreLink()!!.selfName } })
            Thread.sleep(2_000)
            notes.clear(); model.select(null)
            val c2 = "Public reply from MeshCore"
            peer.sendChannelText("p2", pub, c2)
            check("Public peer→desk lands in the Public room", waitFor(30_000) { client.history(pub).any { !it.fromMe && it.content == c2 } })
            check("channel notification says who and where", waitFor(5_000) { notes.any { it.first == "${peer.selfName} · Public" } }, "$notes")

            // ── #hashtag channel: join from the Radio screen's path, both ways ──
            val tag = "#oshi-desk"
            val r = client.meshCoreJoinHashtag(tag)
            check("desk joins $tag", r == MeshCoreLink.JoinResult.QUEUED || r == MeshCoreLink.JoinResult.ALREADY_JOINED, "$r")
            val tagKey = MeshCoreThread.channelKey(tag)
            check("$tag appears in the conversation list", waitFor(15_000) { model.refresh(); model.state.conversations.any { it.id == tagKey } })
            val pr = peer.joinHashtagChannel(tag)
            if (pr == MeshCoreLink.JoinResult.QUEUED) waitFor(10_000) { peerJoins.isNotEmpty() }
            peerJoinedSlot = peer.userChannels.firstOrNull { it.name == tag }?.idx.takeIf { pr == MeshCoreLink.JoinResult.QUEUED }
            check("peer joins $tag", peer.userChannels.any { it.name == tag }, "$pr")
            model.select(tagKey)
            check("hashtag thread warns anyone who knows the name can read it",
                model.state.thread?.meshCore?.detail == com.oshi.desktop.i18n.t("pc.meshcore.publicChannelDetail"))
            val h1 = "Hello $tag"
            model.draft(h1); model.send()
            check("$tag desk→peer", waitFor(30_000) { peerIn.any { it.channelKey == tagKey && it.text == h1 } })
            Thread.sleep(2_000)
            peer.sendChannelText("p3", tagKey, "Hashtag reply")
            check("$tag peer→desk", waitFor(30_000) { client.history(tagKey).any { !it.fromMe && it.content == "Hashtag reply" } })

            // ── Screens, from the real composables and this live state ──
            model.select(peerConv)
            renderAll(shots, client, model, peerConv, pub, tagKey)
        } finally {
            // Leave the peer radio's channel table as it was.
            peerJoinedSlot?.let { slot ->
                peer.session.enqueue(MeshCore.setChannel(slot, "", ByteArray(16)))
                Thread.sleep(3_000)
            }
            File(shots, "results.txt").writeText(results.joinToString("\n") { "${if (it.second) "PASS" else "FAIL"} | ${it.first} | ${it.third}" } + "\n")
            peer.stop(); client.meshCoreDetach(); model.close()
            Thread.sleep(500)
            home.deleteRecursively()
        }
        val failed = results.filter { !it.second }
        log("${results.size - failed.size}/${results.size} passed")
        assert(failed.isEmpty()) { "failed: ${failed.map { it.first }}" }
    }

    /**
     * "Connect" against real boards whose firmware is known: `OSHI_MC_PROBE=/dev/x=MESHTASTIC,/dev/y=UNKNOWN_FIRMWARE`.
     * Reads only (APP_START, want_config); a matching Meshtastic link is attached and detached again.
     */
    @Test
    fun connectRecognisesRealFirmware() {
        val spec = System.getenv("OSHI_MC_PROBE")
        assumeTrue(spec != null)
        val home = Files.createTempDirectory("oshi-mc-probe").toFile()
        val client = OshiClient(home = home, secretStore = InMemorySecretStore(), displayName = "probe", log = { println("  [client] $it") })
        try {
            for (pair in spec!!.split(',')) {
                val (path, want) = pair.split('=')
                val got = client.radioAttachAuto(path)
                check("Connect on $path answers $want", got.name == want, "got $got")
                client.loraDetach(); client.meshCoreDetach()
                Thread.sleep(1_000)
            }
        } finally {
            home.deleteRecursively()
        }
        results.forEach { println("${if (it.second) "PASS" else "FAIL"} | ${it.first} | ${it.third}") }
        assert(results.all { it.second })
    }

    private fun renderAll(shots: File, client: OshiClient, model: ChatShellModel, dm: String, pub: String, tag: String) {
        val saved = Strings.activeTag
        try {
            for (lang in listOf("en", "fr")) {
                Strings.activeTag = lang
                model.refresh()
                render(File(shots, "radio-meshcore-$lang.png"), 1280, 900) {
                    LoRaPane(radio = loRaPaneHost(client), contacts = emptyList())
                }
                for ((name, id) in listOf("dm" to dm, "public" to pub, "hashtag" to tag)) {
                    model.select(id)
                    val st = model.state
                    render(File(shots, "chat-$name-$lang.png"), 1280, 800) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.width(360.dp).fillMaxHeight()) {
                                ConversationListPane(st, "", {}, ConversationFilter.Half.MESSAGES, {}, {}, {}, today = LocalDate.now())
                            }
                            Box(Modifier.fillMaxSize()) {
                                ThreadPane(
                                    thread = st.thread!!, notice = null, busy = false,
                                    wallpaper = WallpaperId.NONE, onPickWallpaper = {},
                                    draft = if (name == "dm") "A draft long enough to show the MeshCore counter: one radio message holds about 133 characters, so this one will go as two numbered parts." else "",
                                    onDraft = {}, onSend = {}, onAttach = {}, onVoiceNote = {},
                                    onRenameGroup = {}, onReact = { _, _ -> }, onEditMessage = { _, _ -> }, onDeleteMessage = {},
                                    onAddGroupMember = {}, onRemoveGroupMember = {}, onSetGroupMemberAdmin = { _, _ -> },
                                    viewOnce = ViewOnceFacts.NONE, revealed = emptySet(), onReveal = {},
                                    today = LocalDate.now(),
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            Strings.activeTag = saved
        }
    }

    private fun render(target: File, wDp: Int, hDp: Int, content: @Composable () -> Unit) {
        val scale = 1.25f
        val scene = ImageComposeScene((wDp * scale).toInt(), (hDp * scale).toInt(), Density(scale)) {
            OshiMaterialTheme {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        try {
            for (f in 1 until 20) { scene.render(f * 50_000_000L); Thread.sleep(40) }
            target.writeBytes(scene.render(20 * 50_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            log("rendered ${target.name}")
        } finally {
            scene.close()
        }
    }
}
