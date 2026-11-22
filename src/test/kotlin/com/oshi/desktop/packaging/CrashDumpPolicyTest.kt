package com.oshi.desktop.packaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** __NO_CORE_DUMP_2026_11_20__ the packaged JVM never writes a core or a heap dump. */
class CrashDumpPolicyTest {
    private val build = File("build.gradle.kts").readText()

    @Test
    fun `packaged launchers disable core dumps and never enable heap dumps`() {
        assertTrue(build.contains("\"--java-options\", \"-XX:-CreateCoredumpOnCrash\""))
        assertFalse(build.contains("HeapDumpOnOutOfMemoryError"))
    }
}
