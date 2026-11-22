package com.oshi.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** __WINDOWS_REDUCED_MOTION_2026_11_20__ */
class MotionPreferenceWindowsTest {
    @Test fun `the SPI probe output is read, and anything else is no answer`() {
        assertEquals(false, MotionPreference.windowsClientAreaAnimation("False\r\n"))
        assertEquals(true, MotionPreference.windowsClientAreaAnimation("True"))
        assertEquals(null, MotionPreference.windowsClientAreaAnimation("Add-Type : blocked by policy"))
        assertEquals(null, MotionPreference.windowsClientAreaAnimation(null))
    }

    @Test fun `the probe asks for SPI_GETCLIENTAREAANIMATION, not MinAnimate`() {
        assertTrue(MotionPreference.WINDOWS_SPI_SCRIPT.contains("0x1042"))
        assertTrue(MotionPreference.WINDOWS_SPI_SCRIPT.contains("SystemParametersInfo"))
    }
}
