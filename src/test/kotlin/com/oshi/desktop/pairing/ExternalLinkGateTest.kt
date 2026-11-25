package com.oshi.desktop.pairing

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** __DEEP_LINK_DRIVE_BY_2026_11_20__ */
class ExternalLinkGateTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7).toByte() })
    private val gid = "3f2a9c10-1b2c-4d5e-8f90-a1b2c3d4e5f6"

    @After fun clear() = ExternalLinkGate.dismiss()

    @Test
    fun `an external join link is held, not acted on, until accepted`() {
        val link = "oshi://group/join?gid=$gid&name=Fam%E2%80%AEily&inviter=${java.net.URLEncoder.encode(key, "UTF-8")}"
        val p = ExternalLinkGate.offer(link)
        assertTrue(p is ExternalLinkGate.Pending.Join)
        // the RLO in the attacker-chosen name is gone from what the dialog shows
        assertFalse((p as ExternalLinkGate.Pending.Join).groupName.contains('‮'))
        assertEquals(link, ExternalLinkGate.accept())
        assertNull("accept is one-shot", ExternalLinkGate.accept())
    }

    @Test
    fun `an add link is held and a dismissed one never fires`() {
        assertTrue(ExternalLinkGate.offer("oshi://add?key=$key") is ExternalLinkGate.Pending.AddContact)
        ExternalLinkGate.dismiss()
        assertNull(ExternalLinkGate.accept())
    }

    @Test
    fun `junk is not actionable and only 32-byte keys are keys`() {
        assertNull(ExternalLinkGate.offer("oshi://settings/wipe"))
        assertTrue(ExternalLinkGate.isPublicKey(key))
        assertFalse(ExternalLinkGate.isPublicKey("attacker"))
        assertFalse(ExternalLinkGate.isPublicKey(Base64.getEncoder().encodeToString(ByteArray(31))))
    }
}
