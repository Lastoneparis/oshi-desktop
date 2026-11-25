package com.oshi.desktop.store

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** __TYPING_PRIVACY_2027__ / __NOTIFICATION_OPTIONS_2027__ */
class PrivacyNotificationPreferencesTest {

    @Test
    fun `typing switch round-trips and an older file reads as on`() {
        val dir = Files.createTempDirectory("oshi-prefs").toFile()
        val file = java.io.File(dir, ReceiptPreferences.FILE_NAME)
        file.writeText("""{"deliveryReceiptsEnabled":true,"readReceiptsEnabled":false}""")
        val prefs = ReceiptPreferences(file)
        assertEquals(ReceiptPreferences.Values(delivery = true, read = false, typing = true), prefs.load())
        prefs.save(prefs.load().copy(typing = false))
        assertEquals(ReceiptPreferences.Values(delivery = true, read = false, typing = false), prefs.load())
        dir.deleteRecursively()
    }

    @Test
    fun `notification switches round-trip, default on, and an older file keeps its preview choice`() {
        val dir = Files.createTempDirectory("oshi-prefs").toFile()
        val file = java.io.File(dir, NotificationPreferences.FILE_NAME)
        val prefs = NotificationPreferences(file)
        assertEquals(NotificationPreferences.Values(preview = false, enabled = true, sound = true), prefs.load())
        file.writeText("""{"notificationPreview":true}""")
        assertEquals(NotificationPreferences.Values(preview = true, enabled = true, sound = true), prefs.load())
        prefs.save(NotificationPreferences.Values(preview = true, enabled = false, sound = false))
        assertEquals(NotificationPreferences.Values(preview = true, enabled = false, sound = false), prefs.load())
        dir.deleteRecursively()
    }
}
