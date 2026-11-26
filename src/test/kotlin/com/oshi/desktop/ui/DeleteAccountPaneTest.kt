package com.oshi.desktop.ui

import com.oshi.desktop.i18n.Strings
import com.oshi.desktop.ui.components.deleteConfirmed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_DELETE_ACCOUNT_UI_2026_10_29__ When the destructive button turns on.
 *
 * `OshiClient.deleteAccount()` was reachable only as `/deleteaccount confirm` in the text
 * console — this window had no way to delete the account at all, while both phones ship the
 * screen. The gate is a typed word, copied from iOS rather than reinterpreted, because this
 * is the one action here that cannot be undone and that destroys the signing key which is
 * the only thing that could ever ask again.
 *
 * The enabling rule is therefore the part worth pinning: a button that arms too eagerly is
 * the whole risk of the screen.
 */
class DeleteAccountPaneTest {

    private val word = "DELETE"

    @Test
    fun `the exact word arms the button`() {
        assertTrue(deleteConfirmed("DELETE", word))
    }

    @Test
    fun `case does not matter, as on iOS`() {
        assertTrue(deleteConfirmed("delete", word))
        assertTrue(deleteConfirmed("DeLeTe", word))
    }

    @Test
    fun `surrounding whitespace is forgiven`() {
        assertTrue(deleteConfirmed("  DELETE  ", word))
        assertTrue(deleteConfirmed("\tdelete\n", word))
    }

    /**
     * The half-typed and nearly-right cases. A prefix must NOT arm it: someone typing the
     * word gets no accidental press on the way there.
     */
    @Test
    fun `nothing short of the word arms it`() {
        for (nearly in listOf("", " ", "D", "DEL", "DELET", "DELETED", "DELETE ME", "SUPPRIMER", "delete account")) {
            assertFalse("'$nearly' must not arm the button", deleteConfirmed(nearly, word))
        }
    }

    /**
     * A blank confirm word would make EVERY empty field a confirmation — the button would
     * be armed before anyone typed anything. A missing or empty catalog entry must fail
     * closed, not open.
     */
    @Test
    fun `a blank confirm word never arms it`() {
        assertFalse(deleteConfirmed("", ""))
        assertFalse(deleteConfirmed("   ", "   "))
        assertFalse(deleteConfirmed("anything", ""))
    }

    /**
     * The screen is built entirely from SHARED `account.delete.*` keys that iOS already
     * ships translated, so it is not English-only the way desktop-only copy still is
     * (PARITY row 1.5). If one went missing the screen would draw a raw key at a person
     * about to erase their account — which is exactly how VALLON shipped its rating prompt
     * showing raw keys in 34 languages.
     */
    @Test
    fun `every string this screen draws exists in the catalog`() {
        val keys = listOf(
            "account.delete.title", "account.delete.headline", "account.delete.body",
            "account.delete.section.removed", "account.delete.section.kept",
            "account.delete.section.kept.footer", "account.delete.section.confirm",
            "account.delete.section.receipt", "account.delete.confirm.word",
            "account.delete.confirm.footer", "account.delete.action",
            "account.delete.done.complete", "account.delete.done.partial",
            "account.delete.failed.generic", "account.delete.failed.detail",
            "account.delete.item.keys", "account.delete.item.queued", "account.delete.item.sync",
            "account.delete.item.media", "account.delete.item.push", "account.delete.item.device",
            "account.delete.keeps.recipients", "account.delete.keeps.reports",
            "account.delete.receipt.keys", "account.delete.receipt.queued",
            "account.delete.receipt.sync", "account.delete.receipt.media",
            "account.delete.receipt.outbound", "account.delete.receipt.unreachable",
        )
        for (k in keys) {
            val v = Strings.get(k)
            assertNotNull("missing catalog entry: $k", v)
            assertTrue("'$k' resolved to the key itself — it is not in the catalog", v != k)
            assertTrue("'$k' is blank", v.isNotBlank())
        }
    }

    /** The real catalog word, not my literal: the gate must match what the screen prints. */
    @Test
    fun `the shipped confirm word arms the shipped button`() {
        val shipped = Strings.get("account.delete.confirm.word")
        assertTrue(shipped.isNotBlank())
        assertTrue(deleteConfirmed(shipped, shipped))
        assertTrue(deleteConfirmed(shipped.lowercase(), shipped))
        assertFalse(deleteConfirmed(shipped.dropLast(1), shipped))
        assertEquals("DELETE", shipped)
    }
}
