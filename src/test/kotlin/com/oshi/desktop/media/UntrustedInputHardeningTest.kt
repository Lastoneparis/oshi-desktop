package com.oshi.desktop.media

import com.oshi.desktop.mail.MailHtml
import com.oshi.desktop.msg.OfflineOnlyPayload
import com.oshi.desktop.store.OfflineOnlyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * __FFMPEG_UNTRUSTED_INPUT_2026_11_20__ / __OFFLINE_ONLY_FUTURE_STAMP_2026_11_20__ /
 * __MAIL_IMAGE_REDIRECT_2026_11_20__
 */
class UntrustedInputHardeningTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `ffmpeg reads a peer's file with the local-file protocol only`() {
        for (args in listOf(
            TranscodeTool.FFMPEG.encodeArgs(File("/x/in.m4a"), File("/x/out.m4a")),
            TranscodeTool.FFMPEG.decodeArgs(File("/x/in.m4a"), File("/x/out.wav")),
        )) {
            val i = args.indexOf("-protocol_whitelist")
            assertTrue("no protocol whitelist in $args", i >= 0 && args[i + 1] == "file" && i < args.indexOf("-i"))
        }
    }

    @Test
    fun `a far-future offline-only stamp cannot lock the setting forever`() {
        val s = OfflineOnlyStore(File(tmp.root, OfflineOnlyStore.FILE_NAME))
        val now = 1_000_000.0
        assertTrue(s.applyIncoming(OfflineOnlyPayload.Request(on = true, atUnix = 4_102_444_800.0), "peer", nowUnix = now))
        assertEquals(now + OfflineOnlyStore.MAX_FUTURE_SKEW_SECONDS, s.state("peer")!!.changedAtUnix, 0.001)
        // a genuine later change (an hour on) still wins
        assertTrue(s.applyIncoming(OfflineOnlyPayload.Request(on = false, atUnix = now + 3600), "peer", nowUnix = now + 3600))
        assertEquals(false, s.isOfflineOnly("peer"))
    }

    @Test
    fun `mail images over plain http are never offered for loading`() {
        val r = MailHtml.render("""<p>x</p><img src="http://tracker.example/p.gif"><img src="https://cdn.example/a.png">""")
        assertEquals(1, r.remoteImageCount)
    }
}
