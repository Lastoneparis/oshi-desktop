package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.oshi.desktop.app.WiringFixture
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.Message
import com.oshi.desktop.store.TimestampSource
import com.oshi.desktop.ui.components.ConversationFilter
import com.oshi.desktop.ui.components.ConversationListPane
import com.oshi.desktop.ui.components.ThreadPane
import com.oshi.desktop.ui.components.ViewOnceFacts
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.state.ChatShellModel
import com.oshi.desktop.ui.state.NetworkBadge
import com.oshi.desktop.ui.state.ShellState
import org.junit.Assume
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.Executor

/**
 * How fluid is the window, MEASURED — not a regression test, a bench. Skipped unless
 * `OSHI_BENCH=1` is in the environment, because it seeds ~6,000 messages and times things.
 *
 *   OSHI_BENCH=1 ./gradlew test --tests com.oshi.desktop.ui.FluidityBench -i
 *
 * PART 1 is REAL: an `OshiClient` over the in-process relay, its real (sealed-at-rest) message
 * store holding 200 conversations and one 2,000-message thread, and the real `ChatShellModel`.
 * What is timed is exactly what the window runs ON THE UI THREAD for a click: `select()` (open
 * a chat) and `draft()` (the first half of Send, `DesktopWindow` onSend) — each ends in
 * `publish()` → `build()`. The worker is a sink: network work (reach probe, read receipt, the
 * send itself) runs off the UI thread in the product and is not what makes a frame late.
 *
 * PART 2 renders the REAL states Part 1 produced with `ImageComposeScene` (skiko SOFTWARE, as
 * the suite runs) — the sidebar and the thread pane side by side as in `MessagesDestination`.
 * Frame = input event + `render()`. Software rasterisation makes absolute numbers pessimistic
 * against a GPU window; they are for comparing scenarios and before/after, not for a 60 Hz claim.
 */
@OptIn(ExperimentalComposeUiApi::class)
class FluidityBench {

    private fun enabled() = System.getenv("OSHI_BENCH") == "1"

    private fun stats(name: String, ns: List<Long>): String {
        val ms = ns.map { it / 1e6 }.sorted()
        fun p(q: Double) = ms[((ms.size - 1) * q).toInt()]
        return "%-38s n=%4d  p50=%7.2f ms  p95=%7.2f ms  max=%7.2f ms".format(name, ms.size, p(0.5), p(0.95), ms.last())
    }

    private inline fun time(block: () -> Unit): Long {
        val t = System.nanoTime(); block(); return System.nanoTime() - t
    }

    @Test
    fun bench() {
        Assume.assumeTrue("set OSHI_BENCH=1 to run", enabled())
        WiringFixture().use { fx ->
            val me = fx.client("me")
            val peers = (0 until 200).map { i -> "peer$i-" + "A".repeat(38) + "=" }
            val now = System.currentTimeMillis()
            var seq = 0
            fun msg(peer: String, i: Int, fromMe: Boolean) = Message(
                id = "m-${seq++}",
                conversationId = peer,
                senderAddress = if (fromMe) me.address else peer,
                recipientAddress = if (fromMe) peer else me.address,
                fromMe = fromMe,
                content = "Message $i — a sentence long enough to wrap onto a second line in the thread pane, like real chat does.",
                sentAtMs = now - (200_000L * i) - seq,
                sentAtSource = TimestampSource.LOCAL_CLOCK,
                deliveryStatus = DeliveryStatus.DELIVERED,
                transport = "v2",
            )
            val big = peers[0]
            val seedNs = time {
                me.messages.importMissing((0 until 2_000).map { msg(big, it, it % 3 == 0) })
                for (p in peers.drop(1)) me.messages.importMissing((0 until 20).map { msg(p, it, it % 2 == 0) })
            }
            println("[bench] seeded 5,980 messages in ${seedNs / 1_000_000} ms")

            // A PHOTO THREAD: 60 phone-sized photos (4032×3024 JPEG, noisy enough to weigh
            // what a phone photo weighs), sealed at rest exactly as an inbound attachment is.
            val photoPeer = "photos-" + "B".repeat(36) + "="
            val jpeg = run {
                val img = java.awt.image.BufferedImage(4032, 3024, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val rnd = java.util.Random(7)
                for (y in 0 until 3024) for (x in 0 until 4032) {
                    val n = rnd.nextInt(48)
                    img.setRGB(x, y, (((x * 255 / 4032) + n).coerceAtMost(255) shl 16) or (((y * 255 / 3024) + n).coerceAtMost(255) shl 8) or (128 + n))
                }
                java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "jpg", it) }.toByteArray()
            }
            val photoDir = java.io.File(me.mediaDir, "bench").apply { mkdirs() }
            val photos = (0 until 60).map { i ->
                val f = java.io.File(photoDir, "photo$i.jpg")
                me.mediaVault.sealingStream(f).use { it.write(jpeg) }
                msg(photoPeer, i, i % 2 == 0).copy(
                    content = "", mediaType = com.oshi.desktop.store.MediaType.IMAGE, mediaRef = f.absolutePath,
                )
            }
            me.messages.importMissing(photos)
            println("[bench] photo: ${jpeg.size / 1024} KB JPEG ×60, sealed")

            // Sanity: the off-UI loader decodes this photo (a silent Failed would make the photo
            // numbers below measure file cards, not pictures).
            check(com.oshi.desktop.ui.components.InlineBitmaps.load(photos[0].mediaRef!!, 500) is
                com.oshi.desktop.ui.components.InlineBitmaps.Result.Ready) { "photo did not decode" }
            val sink = Executor { }
            val model = ChatShellModel(me, worker = sink)
            val out = object { fun appendLine(x: String) { println("[bench] " + x) } }

            // ---------------- PART 1: UI-thread cost of a click (real stores, real model)
            val cold = time { model.refresh() }
            out.appendLine("%-38s %7.2f ms".format("model: first build (cold)", cold / 1e6))
            out.appendLine(stats("model: refresh() (inbound msg path)", (1..40).map { time { model.refresh() } }))
            out.appendLine(stats("model: select(2,000-msg chat)", (1..40).map { time { model.select(big) } }))
            out.appendLine(stats("model: select(20-msg chat)", (1..40).map { i -> time { model.select(peers[1 + i % 50]) } }))
            model.select(big)
            out.appendLine(stats("model: draft() = Send click, UI half", (1..40).map { i -> time { model.draft("hello $i") } }))
            model.select(big)
            val bigState = model.state
            model.select(peers[1])
            val smallState = model.state
            model.select(photoPeer)
            val photoState = model.state

            // ---------------- HOW THE SCENE IS DRIVEN
            //
            // Like the real window, not like ScreenRenderer: every scene call runs on the AWT
            // event thread and the scene's effects are dispatched with skiko's `MainUIDispatcher` (the EDT dispatcher the window uses), so a
            // LaunchedEffect runs BETWEEN frames. `ImageComposeScene`'s default context is
            // `Dispatchers.Unconfined`, which resumes an effect INSIDE the frame that launched it;
            // ThreadPane's conversation-switch `scrollToItem` then remeasures while that frame's
            // composition is still unapplied, and the Compose runtime dies with "pending
            // composition has not been applied" on the FIRST chat switch — measured, every time,
            // under Unconfined. That is a harness artefact, and the probe below says so rather
            // than trusting it: it runs the same switch both ways.
            val errors = java.util.concurrent.atomic.AtomicInteger()
            val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> errors.incrementAndGet(); System.err.println("[bench] uncaught ${e.javaClass.simpleName}: ${e.message?.take(120)}") }
            fun <T> edt(block: () -> T): T {
                var r: Any? = null; var err: Throwable? = null
                javax.swing.SwingUtilities.invokeAndWait { try { r = block() } catch (e: Throwable) { err = e } }
                err?.let { throw it }
                @Suppress("UNCHECKED_CAST") return r as T
            }
            val scale = 2f
            val w = (1180 * scale).toInt()
            val h = (780 * scale).toInt()
            val today = LocalDate.now()

            // ---------------- PROBE: does switching chats kill the composition?
            model.select(peers[2]); val small2 = model.state
            fun probe(label: String, a: ShellState, b: ShellState, swing: Boolean) {
                var st by mutableStateOf(a)
                var comps = 0
                val before = errors.get()
                val sc = edt {
                    val content: @androidx.compose.runtime.Composable () -> Unit = {
                        MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                            val th = st.thread
                            SideEffect { comps++ }
                            if (th != null) ThreadPane(
                                thread = th, notice = null, busy = false, wallpaper = WallpaperId.NONE, onPickWallpaper = {},
                                draft = "", onDraft = {}, onSend = {}, onAttach = {}, onVoiceNote = {}, onRenameGroup = {},
                                onReact = { _, _ -> }, onEditMessage = { _, _ -> }, onDeleteMessage = {}, onAddGroupMember = {},
                                onRemoveGroupMember = {}, onSetGroupMemberAdmin = { _, _ -> }, viewOnce = ViewOnceFacts.of(emptySet()),
                                revealed = emptySet(), onReveal = {}, today = today,
                            )
                        }
                    }
                    if (swing) ImageComposeScene(w, h, Density(scale), coroutineContext = org.jetbrains.skiko.MainUIDispatcher, content = content)
                    else ImageComposeScene(w, h, Density(scale), content = content)
                }
                var tt = 0L
                var frozenAt = -1
                try {
                    repeat(3) { tt += 16_666_667L; edt { sc.render(tt) } }
                    for (i in 1..20) {
                        val c0 = comps
                        edt { st = if (i % 2 == 1) b else a }
                        repeat(3) { tt += 16_666_667L; edt { sc.render(tt) } }
                        if (comps == c0 && frozenAt < 0) frozenAt = i
                    }
                } finally { edt { sc.close() } }
                println("[bench] probe $label, effects on ${if (swing) "skiko MainUIDispatcher (as the window)" else "Unconfined (scene default)"}: " +
                    "errors=${errors.get() - before} " + (if (frozenAt < 0) "never froze in 20 switches" else "FROZE at switch $frozenAt"))
            }
            probe("20↔2000 msgs", smallState, bigState, swing = false)
            probe("20↔2000 msgs", smallState, bigState, swing = true)
            probe("20↔20 msgs", smallState, small2, swing = true)

            // ---------------- PART 2: frames (Swing-dispatched, as the window)
            var state by mutableStateOf(smallState)
            var half by mutableStateOf(ConversationFilter.Half.MESSAGES)
            var badge by mutableStateOf(NetworkBadge(2, true, 3))
            var listCompositions = 0
            var threadCompositions = 0
            val scene = edt {
                ImageComposeScene(w, h, Density(scale), coroutineContext = org.jetbrains.skiko.MainUIDispatcher) {
                    MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                        Surface(Modifier.fillMaxSize(), color = OshiTheme.background) {
                            Row(Modifier.fillMaxSize()) {
                                val s: ShellState = state
                                Box {
                                    SideEffect { listCompositions++ }
                                    ConversationListPane(
                                        state = s, query = "", onQuery = {}, half = half, onHalf = {},
                                        onSelect = {}, onShow = {}, today = today, badge = badge,
                                    )
                                }
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    SideEffect { threadCompositions++ }
                                    val thread = s.thread
                                    if (thread != null) ThreadPane(
                                        thread = thread, notice = s.notice, busy = s.busy,
                                        wallpaper = WallpaperId.NONE, onPickWallpaper = {},
                                        draft = "", onDraft = {}, onSend = {}, onAttach = {}, onVoiceNote = {},
                                        onRenameGroup = {}, onReact = { _, _ -> }, onEditMessage = { _, _ -> },
                                        onDeleteMessage = {}, onAddGroupMember = {}, onRemoveGroupMember = {},
                                        onSetGroupMemberAdmin = { _, _ -> }, viewOnce = ViewOnceFacts.of(emptySet()),
                                        revealed = emptySet(), onReveal = {}, today = today,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val errorsAtStart = errors.get()
            try {
                var t = 0L
                val frame = 16_666_667L
                // One FRAME = the render on the event thread, then one more event-thread turn so
                // any effect that frame launched runs before the next frame (it would in the window).
                fun frameNs(input: () -> Unit = {}): Long {
                    t += frame
                    val ns = edt { input(); time { scene.render(t) } }
                    return ns + edt { time { } }
                }
                fun png(): ByteArray = edt { scene.render(t).encodeToData()!!.bytes }
                fun set(block: () -> Unit) = edt(block)
                repeat(5) { frameNs() } // warm-up: first compose + text shaping

                // Scroll the conversation list with the wheel.
                val listPoint = Offset(160f * scale, 500f * scale)
                val listBefore = png()
                out.appendLine(stats("frame: scroll list (wheel)", (1..120).map {
                    frameNs { scene.sendPointerEvent(PointerEventType.Scroll, listPoint, scrollDelta = Offset(0f, 1f)) }
                }))
                out.appendLine("scroll: list pixels changed after wheel = " + !png().contentEquals(listBefore))

                // OPEN A CHAT: the frame after the state the model published, then the next one.
                var settledOnFirst = 0
                val openFirst = ArrayList<Long>(); val openSecond = ArrayList<Long>()
                val threadPoint = Offset(760f * scale, 400f * scale)
                repeat(20) { i ->
                    set { state = smallState }; repeat(3) { frameNs() }
                    // scroll the small chat off its bottom first, like a user reading history
                    repeat(4) { frameNs { scene.sendPointerEvent(PointerEventType.Scroll, threadPoint, scrollDelta = Offset(0f, -1f)) } }
                    set { state = if (i % 2 == 0) bigState else bigState.copy(thread = bigState.thread!!.copy(conversationId = "other-$i")) }
                    openFirst += frameNs()
                    val a = png()
                    openSecond += frameNs()
                    repeat(3) { frameNs() }
                    if (a.contentEquals(png())) settledOnFirst++
                }
                out.appendLine(stats("frame: open 2,000-msg chat, 1st frame", openFirst))
                out.appendLine(stats("frame: open 2,000-msg chat, 2nd frame", openSecond))
                out.appendLine("open: first frame already at the settled position in $settledOnFirst/20 opens")
                set { state = bigState }; repeat(3) { frameNs() }

                // Scroll the 2,000-message thread (up, into history).
                out.appendLine(stats("frame: scroll 2,000-msg thread", (1..150).map {
                    frameNs { scene.sendPointerEvent(PointerEventType.Scroll, threadPoint, scrollDelta = Offset(0f, -1f)) }
                }))

                // The PHOTO thread: every row entering the viewport composes an InlineImage,
                // whose `remember` reads, decrypts and decodes the file on the frame's thread.
                set { state = smallState }; repeat(3) { frameNs() }
                set { state = photoState }
                out.appendLine(stats("frame: open photo thread (1st frame)", listOf(frameNs())))
                // Frames while the visible photos (the newest three) arrive. Under the ORIGINAL
                // synchronous code there is nothing to wait for: OSHI_BENCH_VARIANT=before.
                val px = (250 * scale).toInt()
                val visible = photos.take(3).map { it.mediaRef!! }
                val landing = ArrayList<Long>()
                val landStart = System.nanoTime()
                if (System.getenv("OSHI_BENCH_VARIANT") != "before") {
                    while (visible.any { com.oshi.desktop.ui.components.InlineBitmaps.cached(it, px) == null } &&
                        System.nanoTime() - landStart < 10_000_000_000L) {
                        Thread.sleep(8); landing += frameNs()
                    }
                    repeat(2) { landing += frameNs() }
                    out.appendLine(stats("frame: while photos land", landing))
                    out.appendLine("photos: newest 3 decoded after %.0f ms (wall clock, off the UI thread)".format((System.nanoTime() - landStart) / 1e6))
                }
                // What the fixed code still does ON the UI thread per photo: the header probe.
                out.appendLine(stats("cost: header size probe (UI thread)", (0 until 6).map { i ->
                    time { com.oshi.desktop.ui.components.InlineBitmaps.size(photos[20 + i].mediaRef!!) }
                }))
                // What the ORIGINAL code did per photo entering the viewport, on the UI thread.
                out.appendLine(stats("cost: read+decrypt+decode one photo", (0 until 6).map { i ->
                    time {
                        val bytes = com.oshi.desktop.store.MediaVault.readAny(java.io.File(photos[10 + i].mediaRef!!), com.oshi.desktop.ui.components.MediaPresentation.MAX_INLINE_BYTES)
                        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    }
                }))
                // Did the chat open at its NEWEST message? Scroll toward newer: if anything
                // moves, it was not at the bottom.
                fun atBottom(): Boolean {
                    // Let async photos finish landing first, or their arrival reads as movement.
                    var prev = png(); var still = 0; val t0 = System.nanoTime()
                    while (still < 3 && System.nanoTime() - t0 < 8_000_000_000L) {
                        Thread.sleep(50); frameNs(); val cur = png()
                        if (cur.contentEquals(prev)) still++ else still = 0
                        prev = cur
                    }
                    val a = png()
                    repeat(3) { frameNs { scene.sendPointerEvent(PointerEventType.Scroll, threadPoint, scrollDelta = Offset(0f, 3f)) } }
                    repeat(2) { frameNs() }
                    return png().contentEquals(a)
                }
                out.appendLine("bottom: photo thread opened at its newest message = ${atBottom()}")
                set { state = smallState }; repeat(3) { frameNs() }
                set { state = bigState }; repeat(3) { frameNs() }
                out.appendLine("bottom: 2,000-msg thread opened at its newest message = ${atBottom()}")
                set { state = bigState }; repeat(3) { frameNs() }
                set { state = smallState }; repeat(3) { frameNs() }
                out.appendLine("bottom: 20-msg text thread (from the 2,000) opened at newest = ${atBottom()}")
                set { state = smallState.copy(thread = smallState.thread!!.copy(conversationId = "x")) }; repeat(3) { frameNs() }
                set { state = photoState }; repeat(3) { frameNs() }
                out.appendLine("bottom: photo thread, 2nd try (from a 20-msg chat) = ${atBottom()}")
                set { state = bigState }; repeat(3) { frameNs() }
                set { state = photoState }; repeat(3) { frameNs() }
                out.appendLine("bottom: photo thread from the 2,000 = ${atBottom()}")
                repeat(60) { frameNs() }
                out.appendLine("bottom: photo thread, 60 frames later = ${atBottom()}")
                set { state = smallState }; repeat(3) { frameNs() }
                set { state = photoState }; repeat(3) { frameNs() }
                java.io.File(System.getProperty("java.io.tmpdir"), "oshi-bench-photo.png").writeBytes(png())
                out.appendLine(stats("frame: scroll photo thread up", (1..150).map {
                    frameNs { scene.sendPointerEvent(PointerEventType.Scroll, threadPoint, scrollDelta = Offset(0f, -1f)) }.also { Thread.sleep(8) }
                }))
                out.appendLine(stats("frame: scroll photo thread down", (1..150).map {
                    frameNs { scene.sendPointerEvent(PointerEventType.Scroll, threadPoint, scrollDelta = Offset(0f, 1f)) }.also { Thread.sleep(8) }
                }))
                set { state = bigState }; repeat(3) { frameNs() }

                // Messages | Groups.
                out.appendLine(stats("frame: switch segment", (1..20).map { i ->
                    set { half = if (i % 2 == 0) ConversationFilter.Half.MESSAGES else ConversationFilter.Half.GROUPS }
                    frameNs()
                }))
                set { half = ConversationFilter.Half.MESSAGES }; frameNs()

                // Send: the busy flag the model publishes while the worker sends, then back.
                out.appendLine(stats("frame: send (busy on/off)", (1..20).map { i ->
                    set { state = bigState.copy(busy = i % 2 == 1) }
                    frameNs()
                }))
                set { state = bigState }; repeat(3) { frameNs() }

                // IDLE with the header tick: assign an EQUAL badge each "second" and compare pixels.
                val before = listCompositions to threadCompositions
                val first = png()
                var identical = 0
                val idleNs = (1..10).map {
                    set { badge = NetworkBadge(2, true, 3) } // equal, new instance — what the tick does
                    t += 1_000_000_000L - frame
                    val ns = frameNs()
                    if (png().contentEquals(first)) identical++
                    ns
                }
                out.appendLine(stats("frame: idle, 1 s header tick", idleNs))
                out.appendLine(
                    "idle: $identical/10 frames pixel-identical; recompositions during idle: " +
                        "list=${listCompositions - before.first} thread=${threadCompositions - before.second}"
                )
                out.appendLine("runtime errors during PART 2: ${errors.get() - errorsAtStart} (non-zero = numbers above are void)")
            } finally {
                edt { scene.close() }
                model.close()
                Thread.setDefaultUncaughtExceptionHandler(prevHandler)
            }
        }
    }
}

