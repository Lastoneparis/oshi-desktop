package com.oshi.desktop.mergeab

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.oshi.desktop.call.video.CameraCapture
import com.oshi.desktop.call.video.VideoSource
import com.oshi.desktop.media.CameraSession
import com.oshi.desktop.ui.OshiMaterialTheme
import com.oshi.desktop.ui.components.LocalCameraFactory
import com.oshi.desktop.ui.components.LocalHiddenByLock
import com.oshi.desktop.ui.components.LocalThreadChromePreview
import com.oshi.desktop.ui.components.ThreadChromePreview
import com.oshi.desktop.ui.components.ThreadPane
import com.oshi.desktop.ui.components.ViewOnceFacts
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.state.ComposerState
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.Reach
import com.oshi.desktop.ui.state.ThreadView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * __LOCK_STOPS_CAMERA__ The passcode lock must not leave the composer's camera running: the
 * device is released when the lock comes up, and unlocking does not turn it back on.
 */
class LockStopsCameraTest {
    private val opened = AtomicInteger()
    private val released = AtomicInteger()

    private inner class FakeCamera : VideoSource {
        override val outW = 1280
        override val outH = 720
        override val deviceName = "fake"
        init { opened.incrementAndGet() }
        override fun next(wantPreview: Boolean): CameraCapture.Captured? { Thread.sleep(5); return null }
        override fun close() { released.incrementAndGet() }
    }

    private val chat = ThreadView(
        conversationId = "c".repeat(43) + "=", title = "Camille", address = "c".repeat(43) + "=",
        kind = ConversationKind.DIRECT, groupAdmin = false, groupCanEditInfo = false,
        groupInviteLink = null, groupDescription = null, groupMembers = emptyList(), groupCandidates = emptyList(),
        safetyNumber = "", verified = false, reach = Reach.NOT_APPLICABLE, reachLabel = "", peerTyping = false,
        messages = emptyList(), composer = ComposerState(true, null, true, null), forwardTargets = emptyList(),
        pinCanToggle = false,
    )

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(what, cond())
    }

    @Test
    fun `locking releases the camera and unlocking does not reopen it`() {
        val tmp = Files.createTempDirectory("oshi-cam").toFile()
        val locked = mutableStateOf(false)
        val factory: () -> CameraSession = { CameraSession(openCamera = { FakeCamera() }, workDir = tmp, microphone = { null }) }
        val scene = ImageComposeScene(900, 700, Density(1f)) {
            OshiMaterialTheme {
                CompositionLocalProvider(
                    LocalHiddenByLock provides locked.value,
                    LocalCameraFactory provides factory,
                    LocalThreadChromePreview provides ThreadChromePreview(cameraOpen = true),
                ) {
                    ThreadPane(
                        thread = chat, notice = null, busy = false, wallpaper = WallpaperId.NONE, onPickWallpaper = {},
                        draft = "", onDraft = {}, onSend = {}, onAttach = {}, onVoiceNote = {},
                        onRenameGroup = {}, onReact = { _, _ -> }, onEditMessage = { _, _ -> }, onDeleteMessage = {},
                        onAddGroupMember = {}, onRemoveGroupMember = {}, onSetGroupMemberAdmin = { _, _ -> },
                        viewOnce = ViewOnceFacts.NONE, revealed = emptySet(), onReveal = {},
                        today = LocalDate.of(2026, 11, 19),
                        onPickImage = { null }, onAttachFile = { _, _, _ -> },
                    )
                }
            }
        }
        try {
            var t = 0L
            fun frames(n: Int) = repeat(n) { scene.render(t); t += 16_000_000L; Thread.sleep(10) }
            frames(5)
            waitFor("camera never opened") { opened.get() == 1 }
            assertEquals(0, released.get())

            locked.value = true
            frames(5)
            waitFor("camera still held while locked") { released.get() == 1 }

            locked.value = false
            frames(10)
            Thread.sleep(200)
            assertEquals("unlocking turned the camera back on", 1, opened.get())
        } finally {
            scene.close(); tmp.deleteRecursively()
        }
    }
}
