package com.oshi.desktop.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import com.oshi.desktop.ui.Appearance
import com.oshi.desktop.ui.OshiMaterialTheme
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.ThemeMode
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CALL_MENU_INK_2026_11_21__ Light + glass, a menu opened on the call screen (a dark ground):
 * its base is the Dark glass, so its ink must be light too (it was black on #262629).
 */
class DarkGroundMenuInkTest {
    companion object { init { System.setProperty("skiko.renderApi", "SOFTWARE") } }

    private fun inkIn(appearance: Appearance, darkGround: Boolean): Color? {
        val saved = OshiTheme.appearance
        OshiTheme.appearance = appearance
        var ink: Color? = null
        val scene = ImageComposeScene(400, 400, Density(1f)) {
            OshiMaterialTheme {
                val body: @androidx.compose.runtime.Composable () -> Unit = {
                    OshiDropdownMenu(expanded = true, onDismissRequest = {}) {
                        ink = MaterialTheme.colorScheme.onSurface
                    }
                }
                if (darkGround) ProvideDarkGround(body) else body()
            }
        }
        try { repeat(4) { scene.render(it * 50_000_000L) } } finally { scene.close(); OshiTheme.appearance = saved }
        return ink
    }

    @Test
    fun `a menu on the call screen in Light glass has light ink`() {
        val ink = inkIn(Appearance(ThemeMode.LIGHT, glass = true), darkGround = true)
        assertNotNull("the menu content was composed", ink)
        assertTrue("ink $ink must be light on the dark glass base", ink!!.luminance() > 0.6f)
    }

    @Test
    fun `a menu elsewhere in Light glass keeps the dark ink`() {
        val ink = inkIn(Appearance(ThemeMode.LIGHT, glass = true), darkGround = false)
        assertNotNull(ink)
        assertTrue("ink $ink", ink!!.luminance() < 0.2f)
    }
}
