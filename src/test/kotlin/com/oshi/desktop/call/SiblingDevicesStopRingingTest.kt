package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * __DESKTOP_BACKGROUND_2026_09_23__ One account on two devices (a desktop restored from the
 * phone's recovery key): both ring, and answering or declining on one must stop the other,
 * the way `VoiceCallManager.notifyOtherDevicesCallAnswered/Ended` does between iPhones.
 */
class SiblingDevicesStopRingingTest {

    private val server = FakeCallServer()

    @After fun tearDown() = server.close()

    private fun lane(identity: DesktopIdentity): CallLane {
        val deviceId = "dev-" + UUID.randomUUID().toString().take(8)
        return CallLane(
            myAddress = identity.userKey,
            myPrivateKey = identity.identity.priv,
            transport = CallSignalClient(server.baseUrl, DesktopV2Signer(identity)),
            deviceId = deviceId,
            machine = CallStateMachine(identity.userKey, null, deviceId),
        ).also { it.siblingPushUrl = null }
    }

    private fun pollUntil(lane: CallLane, want: (CallState) -> Boolean): CallState {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            lane.pollOnce()
            if (want(lane.state)) return lane.state
            Thread.sleep(50)
        }
        return lane.state
    }

    private fun scenario(act: (CallLane) -> Unit) {
        val caller = lane(DesktopIdentity.generate())
        val account = DesktopIdentity.generate()
        val desktop = lane(account)
        val phone = lane(account)
        caller.call(account.userKey)
        assertEquals(CallState.RINGING, pollUntil(desktop) { it == CallState.RINGING })
        assertEquals(CallState.RINGING, pollUntil(phone) { it == CallState.RINGING })
        act(desktop)
        val after = pollUntil(phone) { it != CallState.RINGING }
        assertEquals("the sibling must stop ringing", true, after != CallState.RINGING)
    }

    @Test fun answeringHereStopsTheSibling() = scenario { it.answer() }

    @Test fun decliningHereStopsTheSibling() = scenario { it.decline() }
}
