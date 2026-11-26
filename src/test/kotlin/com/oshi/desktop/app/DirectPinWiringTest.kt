package com.oshi.desktop.app

import com.oshi.desktop.msg.MessageActionPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_DIRECT_PIN_2026_10_29__ A 1:1 pin, through two real clients over the fixture
 * relay — both halves, not a codec.
 *
 * `ControlPayloadRouterTest` covers the rules against a store directly. This covers the
 * claim that actually matters, and the one the desktop could not make until today: a pin
 * pressed on one client appears on the other. That is the shape of the defect this closed —
 * the router had decoded inbound pins since it was written and returned `PIN_NOT_APPLIED`,
 * so every rule was right and nothing crossed.
 *
 * Why a wiring test and not just the unit ones: the gap was never in a rule. It was a
 * constructor that passed no store and a `?: return` in the view model. Neither is visible
 * to a test that calls the store itself.
 */
class DirectPinWiringTest {

    private val fx = WiringFixture()

    @After
    fun tearDown() = fx.close()

    @Test
    fun `a pin crosses to the peer, and unpinning clears it there too`() {
        val a = fx.client("a")
        val b = fx.client("b")

        assertEquals(OshiClient.SendOutcome.SENT, a.send(b.address, "trailhead at 8"))
        assertEquals(1, b.router.poll())
        val mine = a.messages.messages(b.address).last()
        val theirs = b.messages.messages(a.address).last()

        // A pins its own message.
        assertEquals(OshiClient.SendOutcome.SENT, a.pinDirectMessage(b.address, mine.id))
        assertEquals("the sender's own banner", mine.id, a.pinnedMessages.get(b.address))
        b.router.poll()
        assertTrue(
            "the peer never applied the pin — the PIN_NOT_APPLIED regression",
            b.pinnedMessages.isPinned(a.address, theirs.id),
        )

        // And B unpins it, which must clear A's banner in turn: in a 1:1 either party may.
        assertEquals(OshiClient.SendOutcome.SENT, b.pinDirectMessage(a.address, null))
        assertNull(b.pinnedMessages.get(a.address))
        a.router.poll()
        assertNull("an unpin from the peer left our banner up", a.pinnedMessages.get(b.address))
    }

    /**
     * The pin may name the PEER's message — the ordinary case, and the one an inherited
     * ownership rule would have refused. `editMessage` and `deleteMessage` return
     * `REFUSED_CONTROL_PAYLOAD` here on purpose; a pin must not.
     */
    @Test
    fun `either party may pin the other's message`() {
        val a = fx.client("a")
        val b = fx.client("b")
        assertEquals(OshiClient.SendOutcome.SENT, b.send(a.address, "bring the map"))
        assertEquals(1, a.router.poll())
        val hers = a.messages.messages(b.address).last()

        assertEquals(
            "the ownership rule must not reach the pin path",
            OshiClient.SendOutcome.SENT,
            a.pinDirectMessage(b.address, hers.id),
        )
        assertEquals(hers.id, a.pinnedMessages.get(b.address))

        // The contrast, on the same message: these two DO enforce it.
        assertEquals(OshiClient.SendOutcome.REFUSED_CONTROL_PAYLOAD, a.editMessage(b.address, hers.id, "no"))
        assertEquals(OshiClient.SendOutcome.REFUSED_CONTROL_PAYLOAD, a.deleteMessage(b.address, hers.id))
    }

    /** Pinning a message this device does not hold writes nothing and sends nothing. */
    @Test
    fun `a pin naming an unknown message is refused`() {
        val a = fx.client("a")
        val b = fx.client("b", publish = false).address
        assertEquals(OshiClient.SendOutcome.NO_V2_PATH, a.pinDirectMessage(b, "00000000-0000-0000-0000-000000000000"))
        assertTrue(a.pinnedMessages.all().isEmpty())
    }

    /** An unpin with nothing pinned has no message to name, so it is refused rather than sent. */
    @Test
    fun `an unpin with no pin on record is refused`() {
        val a = fx.client("a")
        val b = fx.client("b", publish = false).address
        assertEquals(OshiClient.SendOutcome.NO_V2_PATH, a.pinDirectMessage(b, null))
    }

    /**
     * The wire bytes are iOS's, and they are checked here rather than assumed from the fact
     * that the pin crossed between two copies of THIS client — two identical clients agree
     * on a wrong format just as readily as on a right one. The reference is the shape
     * `MessageManager.pinMessageAndSync` emits: the `🔧ACTION🔧` sentinel, `actionType`,
     * `targetMessageId`, an Apple-epoch `timestamp`, and NO `newContent` key at all.
     */
    @Test
    fun `the pin payload is iOS's shape`() {
        val wire = MessageActionPayload.encode(
            actionType = MessageActionPayload.ActionType.PIN_MESSAGE,
            targetMessageId = "M-1",
            newContent = null,
            atUnixMillis = 1_770_000_000_000L,
        )
        assertTrue(wire.startsWith("🔧ACTION🔧"))
        val body = org.json.JSONObject(wire.removePrefix("🔧ACTION🔧"))
        assertEquals("pinMessage", body.getString("actionType"))
        assertEquals("M-1", body.getString("targetMessageId"))
        assertTrue("newContent must be ABSENT, not null — Swift uses encodeIfPresent", !body.has("newContent"))
        // 1770000000 - 978307200 = 791692800, Apple's reference date.
        assertEquals(791_692_800.0, body.getDouble("timestamp"), 0.001)
        assertEquals("unpinMessage", MessageActionPayload.ActionType.UNPIN_MESSAGE.wire)
    }
}
