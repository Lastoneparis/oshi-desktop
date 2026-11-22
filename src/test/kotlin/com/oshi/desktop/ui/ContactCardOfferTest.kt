package com.oshi.desktop.ui

import com.oshi.desktop.ui.state.ContactRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_CONTACT_CARD_COMPOSER_2026_10_29__ Who may be offered as a contact card.
 *
 * `OshiClient.sendContactCard` has existed since the media path landed and was reachable
 * only from the console, so this window could DISPLAY a received card — `MediaPresentation`
 * draws "Contact card", `MediaGalleryPane` a 👤 — and had no way to send one. iOS attaches
 * one from its media picker (`MediaPickerView.swift:815`, `ChatView.swift:6159`).
 *
 * Not to be confused with iOS's `shareContact()`, which builds share text and a QR for the
 * SYSTEM share sheet. The window already has that — a per-contact QR in More and Copy
 * address — and conflating the two is how this nearly got recorded as a second gap that was
 * not there.
 *
 * Two rules decide the list, and both are about not handing a key to the wrong person:
 * blocked contacts are never offered, and the conversation's own peer is never offered
 * their own card.
 */
class ContactCardOfferTest {

    private fun row(addr: String, name: String, blocked: Boolean = false) =
        ContactRow(
            address = addr, addressShort = addr.take(8), label = name,
            verified = false, blocked = blocked, safetyNumber = "0000",
        )

    /** The list the composer draws, as the pane computes it. */
    private fun offered(contacts: List<ContactRow>, peer: String) =
        contacts.filterNot { it.address == peer }.filterNot { it.blocked }.map { it.address }

    @Test
    fun `the conversation's own peer is never offered their own card`() {
        val contacts = listOf(row("peer", "Ana"), row("other", "Bo"))
        assertEquals(listOf("other"), offered(contacts, "peer"))
    }

    /**
     * Blocking is ENFORCEMENT here, not a flag (PARITY row 0.21), and passing someone's
     * card around is exactly what a block is for.
     */
    @Test
    fun `a blocked contact is never offered`() {
        val contacts = listOf(row("a", "Ana"), row("b", "Bo", blocked = true))
        assertEquals(listOf("a"), offered(contacts, "peer"))
    }

    @Test
    fun `both rules apply at once`() {
        val contacts = listOf(row("peer", "Ana"), row("b", "Bo", blocked = true), row("c", "Cy"))
        assertEquals(listOf("c"), offered(contacts, "peer"))
    }

    /**
     * The control is hidden rather than drawn empty. A menu that opens onto nothing is the
     * greyed-out phone icon `DesktopLimits` argues against: it claims a capability and then
     * refuses it.
     */
    @Test
    fun `with nobody left to send, there is nothing to offer`() {
        assertTrue(offered(listOf(row("peer", "Ana")), "peer").isEmpty())
        assertTrue(offered(listOf(row("b", "Bo", blocked = true)), "peer").isEmpty())
        assertTrue(offered(emptyList(), "peer").isEmpty())
    }

    @Test
    fun `an ordinary contact in an ordinary thread is offered`() {
        assertEquals(listOf("a", "c"), offered(listOf(row("a", "Ana"), row("c", "Cy")), "peer"))
        assertFalse(offered(listOf(row("a", "Ana")), "peer").isEmpty())
    }
}
