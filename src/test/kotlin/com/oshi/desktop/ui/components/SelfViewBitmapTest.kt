package com.oshi.desktop.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.oshi.desktop.call.video.VideoImage
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * __SELF_VIEW_STALE_LAMBDA_2026_11_21__ The self-view's frame count depends on `screen.cameraOn`,
 * which is false when a voice call becomes video and true a moment later. The bitmap must follow
 * the CURRENT lambda, not the one of the first composition (the bug: "Camera off" all call long).
 */
class SelfViewBitmapTest {
    companion object { init { System.setProperty("skiko.renderApi", "SOFTWARE") } }

    @Test
    fun `the picture appears once the camera turns on after the first composition`() {
        var cameraOn by mutableStateOf(false)
        var seen: androidx.compose.ui.graphics.ImageBitmap? = null
        val frame = VideoImage(2, 2, IntArray(4) { 0xFF336699.toInt() })
        val scene = ImageComposeScene(10, 10, Density(1f)) {
            val on = cameraOn   // read in composition, as `screen.cameraOn` is
            seen = rememberVideoBitmap({ if (on) 1L else null }) { frame }
        }
        try {
            var t = 0L
            fun frames(n: Int) = repeat(n) { t += 40_000_000L; scene.render(t); Thread.sleep(40) }
            frames(5)
            assertNull("camera off: no self-view", seen)
            cameraOn = true
            frames(10)
            assertNotNull("camera on: the self-view must show the camera", seen)
            cameraOn = false
            frames(10)
            assertNull("camera off again: picture gone", seen)
        } finally {
            scene.close()
        }
    }
}
