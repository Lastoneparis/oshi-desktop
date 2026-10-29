package com.oshi.desktop.call.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * macOS names its cameras, and this is how.
 *
 * `CallDevices.cameras` returned an EMPTY list on macOS, with a correct reason — FFmpeg's
 * avfoundation input does not implement `avdevice_list_input_sources`, which is the API
 * Windows gets its DirectShow list from. The conclusion did not follow from it: FFmpeg is
 * not the only thing on a Mac that knows what cameras exist. `system_profiler
 * SPCameraDataType -json` reads the device registry and never opens one.
 *
 * That last part is the reason the obvious alternative was rejected. Probing avfoundation
 * indices 0, 1, 2… and keeping whichever open would turn the camera light on several times
 * to fill a menu nobody has chosen from yet.
 *
 * The parse is a pure function over the JSON, so every one of these runs on Windows and
 * Linux runners too — the platform this is FOR is the one least likely to be running CI.
 */
class MacCameraListTest {

    @Test
    fun `the shipped shape of system_profiler output yields the camera`() {
        // Captured verbatim from `system_profiler SPCameraDataType -json` on macOS 15.
        val json = """
            {
              "SPCameraDataType" : [
                {
                  "_name" : "FaceTime HD Camera",
                  "spcamera_model-id" : "FaceTime HD Camera",
                  "spcamera_unique-id" : "1FD4B3A2-236E-492B-8CE5-255DD288CE50"
                }
              ]
            }
        """.trimIndent()
        assertEquals(listOf("FaceTime HD Camera" to "FaceTime HD Camera"), CallDevices.parseMacCameras(json))
    }

    @Test
    fun `several cameras keep their order and are offered by NAME`() {
        val json = """{"SPCameraDataType":[{"_name":"FaceTime HD Camera"},{"_name":"Logi C920"}]}"""
        val got = CallDevices.parseMacCameras(json)
        assertEquals(listOf("FaceTime HD Camera", "Logi C920"), got.map { it.first })
        assertEquals(
            "the device string and the label are the same thing here: avfoundation takes " +
                "the name, and a name survives a device being replaced where an index does not",
            got.map { it.first },
            got.map { it.second },
        )
    }

    @Test
    fun `a name containing a colon is DROPPED, not offered`() {
        // avfoundation parses its input as `video:audio`, so such a name cannot be
        // addressed unambiguously. A menu entry that selects the WRONG camera is worse
        // than one that is not there.
        val json = """{"SPCameraDataType":[{"_name":"Weird:Cam"},{"_name":"Logi C920"}]}"""
        assertEquals(listOf("Logi C920"), CallDevices.parseMacCameras(json).map { it.first })
    }

    @Test
    fun `a Mac with no camera reports none, and junk is not a crash`() {
        assertTrue(CallDevices.parseMacCameras("""{"SPCameraDataType":[]}""").isEmpty())
        assertTrue("a key that is not there is not a camera", CallDevices.parseMacCameras("{}").isEmpty())
        // A settings menu is not worth a crash: every failure is an empty list.
        for (junk in listOf("", "not json at all", "[]", """{"SPCameraDataType":"nonsense"}""")) {
            assertTrue("junk -> $junk", CallDevices.parseMacCameras(junk).isEmpty())
        }
    }

    @Test
    fun `blank and duplicate names are filtered`() {
        val json = """{"SPCameraDataType":[{"_name":"  "},{"_name":"Logi C920"},{"_name":"Logi C920"},{"other":1}]}"""
        assertEquals(listOf("Logi C920"), CallDevices.parseMacCameras(json).map { it.first })
    }

    @Test
    fun `a system_profiler that does not run leaves the picker empty rather than failing`() {
        assertTrue(CallDevices.macCameras { null }.isEmpty())
        assertTrue(CallDevices.macCameras { throw java.io.IOException("no such tool") }.isEmpty())
    }
}
