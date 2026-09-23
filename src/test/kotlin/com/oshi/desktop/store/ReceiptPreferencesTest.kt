package com.oshi.desktop.store

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptPreferencesTest {
    @Test fun `receipt preferences survive a reopen`() {
        val file = Files.createTempDirectory("oshi-receipts").resolve(ReceiptPreferences.FILE_NAME).toFile()
        ReceiptPreferences(file).save(ReceiptPreferences.Values(delivery = false, read = false))
        val reloaded = ReceiptPreferences(file).load()
        assertFalse(reloaded.delivery)
        assertFalse(reloaded.read)
    }

    @Test fun `missing or corrupt preferences fail open to the mobile defaults`() {
        val file = Files.createTempDirectory("oshi-receipts").resolve(ReceiptPreferences.FILE_NAME).toFile()
        assertTrue(ReceiptPreferences(file).load().delivery)
        file.writeText("not json")
        assertTrue(ReceiptPreferences(file).load().read)
    }
}
