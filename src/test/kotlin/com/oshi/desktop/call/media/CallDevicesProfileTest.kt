package com.oshi.desktop.call.media

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

class CallDevicesProfileTest {
    @Test
    fun `default profile keeps the historical node`() {
        assertEquals("com/oshi/desktop/call-devices", CallDevices.nodePathFor(File("/any"), isDefaultProfile = true))
    }

    @Test
    fun `other profiles get their own node, stable per directory`() {
        val a = CallDevices.nodePathFor(File("/tmp/profile-a"), isDefaultProfile = false)
        val b = CallDevices.nodePathFor(File("/tmp/profile-b"), isDefaultProfile = false)
        assertTrue(a.startsWith("com/oshi/desktop/call-devices/p-"))
        assertNotEquals(a, b)
        assertEquals(a, CallDevices.nodePathFor(File("/tmp/profile-a"), isDefaultProfile = false))
    }

    @Test
    fun `a throwaway profile's voice effect does not leak into the default profile`() {
        val tmp = kotlin.io.path.createTempDirectory("calldev").toFile()
        val before = CallDevices.currentNodePath()
        try {
            CallDevices.bindProfile(tmp, isDefaultProfile = false)
            CallDevices.voiceEffect = "Robot"
            assertEquals("Robot", CallDevices.voiceEffect)
            CallDevices.bindProfile(File(tmp, "other"), isDefaultProfile = false)
            assertEquals(null, CallDevices.voiceEffect)
            CallDevices.bindProfile(tmp, isDefaultProfile = false)
            CallDevices.voiceEffect = null
        } finally {
            runCatching { java.util.prefs.Preferences.userRoot().node(CallDevices.nodePathFor(tmp, false)).removeNode() }
            runCatching { java.util.prefs.Preferences.userRoot().node(CallDevices.nodePathFor(File(tmp, "other"), false)).removeNode() }
            // restore whatever node the JVM had before (never write to the default node here)
            if (before == "com/oshi/desktop/call-devices") CallDevices.bindProfile(tmp, isDefaultProfile = true)
            tmp.deleteRecursively()
        }
    }
}
