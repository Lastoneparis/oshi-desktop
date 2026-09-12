package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.app.ClientCommands
import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.store.ContactStore
import com.oshi.desktop.store.InMemorySecretStore
import java.io.File
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PARITY.md row 2.1 — the SIGNALLING lane, end to end through a call server.
 *
 * [CallStateMachineTest] already proves every transition, timeout and race with no network
 * at all. This file proves the other half, the half that did not exist until now: that the
 * decisions reach a wire, that the wire is the one `ServerVPS/call_server.js` serves, and
 * that two independent clients can ring, answer and end a call through it.
 *
 * Both endpoints are real [CallLane]s with real identities over a real socket. Nothing is
 * mocked, because the thing under test IS the wiring — a mock would assert that the method
 * we wrote is called by the code we wrote.
 *
 * **What this cannot prove, and does not claim:** no audio flows here, because no audio
 * flows anywhere in this client (see [CallLane.NO_AUDIO_WILL_FLOW]); and no phone was
 * involved, so "a desktop can ring an iPhone" remains untested. What it does establish is
 * that the bytes, paths, key spellings and queue semantics are the shipped server's.
 */
class CallSignalingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = FakeCallServer()
    private val t0 = System.currentTimeMillis()

    @After
    fun tearDown() = server.close()

    /** One client: an identity, a lane, and everything the lane announced. */
    private inner class Endpoint(
        val deviceId: String = "dev-" + UUID.randomUUID().toString().take(8),
        contacts: ContactStore? = null,
    ) {
        val identity: DesktopIdentity = DesktopIdentity.generate()
        val address: String get() = identity.userKey
        val events = ArrayList<CallLane.CallEvent>()
        val lane = CallLane(
            myAddress = identity.userKey,
            myPrivateKey = identity.identity.priv,
            transport = CallSignalClient(server.baseUrl, DesktopV2Signer(identity)),
            deviceId = deviceId,
            machine = CallStateMachine(identity.userKey, contacts, deviceId),
        ).also { lane -> lane.onEvent = { events += it } }

        fun ringing(): CallLane.CallEvent.Ringing? =
            events.filterIsInstance<CallLane.CallEvent.Ringing>().firstOrNull()

        fun connected(): CallLane.CallEvent.Connected? =
            events.filterIsInstance<CallLane.CallEvent.Connected>().firstOrNull()

        fun ended(): CallLane.CallEvent.Ended? =
            events.filterIsInstance<CallLane.CallEvent.Ended>().firstOrNull()

        fun problem(): CallLane.CallEvent.TransportProblem? =
            events.filterIsInstance<CallLane.CallEvent.TransportProblem>().firstOrNull()
    }

    // ============================================================== the headline

    /**
     * Ring → answer → end, between two clients, through the server.
     *
     * The whole row in one test: A dials, the offer is sealed and POSTed, B polls it off the
     * queue, opens it under `DH(bPriv, aPub)`, rings, answers, and its accept travels back
     * the same way; then A hangs up and B sees it. Every hop is a real HTTP request against
     * the routes `call_server.js` actually serves.
     */
    @Test
    fun ringAnswerAndEndCrossTheServer() {
        val a = Endpoint()
        val b = Endpoint()

        val dialled = a.lane.call(b.address, t0)
        assertTrue("the offer must be accepted by the server, got $dialled", dialled is CallLane.Dialled.Ringing)
        assertEquals(CallState.RINGING, a.lane.state)
        assertTrue(a.lane.isOutgoing)

        // --- B hears it ------------------------------------------------------------
        assertEquals(1, b.lane.pollOnce(t0 + 10))
        assertEquals(CallState.RINGING, b.lane.state)
        assertFalse("B is the callee", b.lane.isOutgoing)
        // The peer B rings is the identity that OPENED the seal, not the envelope's claim.
        assertEquals(a.address, b.lane.peer)
        assertEquals(a.lane.callId, b.lane.callId)
        val ring = b.ringing()
        assertNotNull("B must announce an incoming ring", ring)
        assertTrue(ring!!.incoming)

        // --- B answers -------------------------------------------------------------
        assertEquals(CallRefusal.NONE, b.lane.answer(t0 + 20))
        assertEquals(CallState.IN_CALL, b.lane.state)
        val bConnected = b.connected()
        assertNotNull(bConnected)
        // The whole point of the field: a connected call here carries no audio, and the
        // event says so rather than leaving a UI to assume.
        assertTrue("a connected call must announce that no audio flows", bConnected!!.noAudio)

        // --- A learns the call is up ------------------------------------------------
        assertEquals(1, a.lane.pollOnce(t0 + 30))
        assertEquals(CallState.IN_CALL, a.lane.state)
        assertNotNull(a.connected())

        // --- A hangs up, past the 3 s post-connect grace ---------------------------
        assertEquals(CallRefusal.NONE, a.lane.hangUp(CallEndReason.HUNG_UP, t0 + 5_000))
        assertEquals(CallState.ENDED, a.lane.state)

        assertEquals(1, b.lane.pollOnce(t0 + 5_010))
        assertEquals(CallState.ENDED, b.lane.state)
        val ended = b.ended()
        assertNotNull(ended)
        assertEquals(CallEndReason.HUNG_UP, ended!!.reason)
        assertTrue("the call was up when it ended", ended.wasConnected)

        // Both call logs carry the same call, from opposite ends.
        assertEquals(1, a.lane.calls().size)
        assertEquals(1, b.lane.calls().size)
        assertEquals(a.lane.calls()[0].callId, b.lane.calls()[0].callId)
        assertTrue("A dialled", !a.lane.calls()[0].incoming)
        assertTrue("B was rung", b.lane.calls()[0].incoming)
    }

    /** A decline reaches the caller and ends the call for both. */
    @Test
    fun declineCrossesTheServer() {
        val a = Endpoint()
        val b = Endpoint()

        a.lane.call(b.address, t0)
        b.lane.pollOnce(t0 + 10)
        assertEquals(CallRefusal.NONE, b.lane.decline(t0 + 20))
        assertEquals(CallState.ENDED, b.lane.state)

        // Past END_GRACE_AFTER_DIAL_MS (5 s), which exists so our own fan-out cannot kill
        // the call we just placed.
        assertEquals(1, a.lane.pollOnce(t0 + 6_000))
        assertEquals(CallState.ENDED, a.lane.state)
        assertEquals(CallEndReason.DECLINED, a.ended()!!.reason)
    }

    // ============================================================== the wire

    /**
     * The envelope on the wire is the shape both phones emit — and one field they emit that
     * this client does not.
     *
     * Asserted on the RAW BODY the server received, not on `encode()`. This project has
     * already shipped a guard that asserted on a `toJson()` while the emitter divided a
     * timestamp by 1000 on the way out (PLAN.md §3), so the bytes that left are the only
     * thing worth asserting on.
     */
    @Test
    fun theEnvelopeIsTheShippedShape() {
        val a = Endpoint()
        val b = Endpoint()
        a.lane.call(b.address, t0)

        assertEquals(1, server.postedBodies.size)
        val raw = server.postedBodies[0]

        // Key ORDER is Android's `sendCallSignal` order (`VPSClient.kt:1158-1173`), so a
        // byte comparison against a real Android payload stays meaningful.
        val order = Regex("\"([A-Za-z_]+)\":").findAll(raw).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "sender", "senderPublicKey", "recipient", "recipientPublicKey", "callId",
                "type", "payload", "signal", "timestamp", "senderDeviceId", "isVideoCall",
                "privacy_mode",
            ),
            order,
        )

        val o = JSONObject(raw)
        // BOTH spellings of both keys, and `payload` == `signal`. iOS reads `sender`/`signal`,
        // Android reads either — dropping one makes this client unreadable to one platform.
        assertEquals(o.getString("sender"), o.getString("senderPublicKey"))
        assertEquals(o.getString("recipient"), o.getString("recipientPublicKey"))
        assertEquals(o.getString("payload"), o.getString("signal"))

        // base64url on the wire: `+`→`-`, `/`→`_`, no padding. The server keys its maps on
        // exactly this fold, so a standard-base64 spelling here goes into a box nobody polls.
        for (key in listOf(o.getString("sender"), o.getString("recipient"))) {
            assertFalse("'$key' must be base64url on this route", key.contains('+'))
            assertFalse("'$key' must be base64url on this route", key.contains('/'))
            assertFalse("'$key' must be base64url on this route", key.contains('='))
        }
        assertEquals(CallSignalClient.base64Url(b.address), o.getString("recipient"))

        // The envelope timestamp is epoch 3 — Unix SECONDS as a fractional Double — and the
        // packet header nine bytes deeper is epoch 2, Unix millis. Two epochs, one nesting
        // level apart, both called `timestamp`.
        val seconds = o.getDouble("timestamp")
        assertTrue("envelope timestamp must be Unix SECONDS, got $seconds", seconds < 1e11)
        assertEquals(t0 / 1000.0, seconds, 2.0)

        assertEquals(CallSignalType.CALL_REQUEST, o.getString("type"))
        assertFalse("isVideoCall must be a JSON boolean", raw.contains("\"isVideoCall\":\"false\""))
        assertEquals("direct", o.getString("privacy_mode"))

        // The deliberate divergence from both shipped clients: no display name is uploaded,
        // because the field exists to feed a push wake-screen this platform does not have.
        assertFalse("callerName must never be emitted", raw.contains("callerName"))
    }

    /**
     * Every request is signed over the path the SERVER hashes — the one with `/api/call`
     * already stripped by nginx.
     *
     * This is the trap the call server left a log line about: `req.originalUrl` is evaluated
     * inside the upstream Express app, after `proxy_pass http://127.0.0.1:8083/` has replaced
     * the matched prefix with `/`. A client that signs the URL it typed signs a string the
     * server never hashes, and `[SIG-DEBUG] … client may use different path prefix`
     * (`call_server.js:132`) is what that looks like in production.
     *
     * [FakeCallServer] verifies STRICTLY, unlike production, so this is a real check rather
     * than a restatement of `SIGNATURE_REQUIRED = false`.
     */
    @Test
    fun everyRequestIsSignedOverTheStrippedPath() {
        val a = Endpoint()
        val b = Endpoint()
        a.lane.call(b.address, t0)
        b.lane.pollOnce(t0 + 10)
        b.lane.answer(t0 + 20)

        assertTrue("something must have been signed", server.signatureVerdicts.isNotEmpty())
        assertEquals(
            "every request must verify",
            emptyList<String>(),
            server.signatureVerdicts.filter { it != "verified" },
        )
        // And the paths are the upstream ones. `/api/call/...` reaching this list would mean
        // the client signed a prefix the server never sees.
        assertTrue(server.seenPaths.contains("POST" to "/signal"))
        assertTrue(server.seenPaths.none { it.second.startsWith(CallSignalClient.API_PREFIX) })
    }

    /**
     * The poll path carries the key as RAW base64url, percent-encoded nowhere.
     *
     * `DesktopV2Signer.encodeIdentity` — correct for every `/v2` route, where the path holds
     * STANDARD base64 and `+ / =` must become `%2B %2F %3D` — would be wrong here twice over:
     * it changes the bytes the signature covers AND the map key the server looks up.
     */
    @Test
    fun thePollPathIsRawBase64Url() {
        val b = Endpoint()
        b.lane.pollOnce(t0)
        val get = server.seenPaths.first { it.first == "GET" }
        assertEquals("/signals/" + CallSignalClient.base64Url(b.address), get.second)
        assertFalse("no percent-encoding on this route", get.second.contains('%'))
    }

    /** `/register` uploads a who-called-whom table and is never called. */
    @Test
    fun theCallGraphIsNeverUploaded() {
        val a = Endpoint()
        val b = Endpoint()
        a.lane.call(b.address, t0)
        b.lane.pollOnce(t0 + 10)
        b.lane.answer(t0 + 20)
        a.lane.pollOnce(t0 + 30)
        a.lane.hangUp(CallEndReason.HUNG_UP, t0 + 5_000)

        assertEquals(
            "POST /register stores {callId, [participant1, participant2]} for an hour and " +
                "nothing in the server routes on it",
            emptySet<String>(),
            server.registeredCalls(),
        )
        assertTrue(server.seenPaths.none { it.second == "/register" })
    }

    // ============================================================== the refusals

    /**
     * An UNSEALED packet from a stranger cannot end a ringing call.
     *
     * The server has no authentication at all (`SIGNATURE_REQUIRED = false`), so anyone can
     * POST a signal claiming to be anyone. The only thing standing between that and a
     * hijacked call is that the packet must open under `DH(mine, theirs)` — so a plausible,
     * well-formed, correctly-typed `callEnd` that is simply not sealed must be dropped and
     * COUNTED, never applied.
     */
    @Test
    fun anUnsealedPacketFromAStrangerCannotEndACall() {
        val a = Endpoint()
        val b = Endpoint()
        a.lane.call(b.address, t0)
        b.lane.pollOnce(t0 + 10)
        assertEquals(CallState.RINGING, b.lane.state)

        // A raw, unencrypted callEnd — the shape a "tolerant" parser would happily read.
        val forged = CallPacket.encode(
            CallPacket.Type.CALL_END, t0 + 20, CallEndReason.HUNG_UP.wire.toByteArray()
        )
        postRaw(
            CallSignalEnvelope(
                sender = CallSignalClient.base64Url(a.address),
                recipient = CallSignalClient.base64Url(b.address),
                signalBase64 = Base64.getEncoder().encodeToString(forged),
                callId = a.lane.callId!!,
                type = CallSignalType.CALL_END,
            ),
            t0 + 20,
        )

        assertEquals("nothing may be applied", 0, b.lane.pollOnce(t0 + 30))
        assertEquals("B is still ringing", CallState.RINGING, b.lane.state)
        assertEquals("and the drop is counted, never silent", 1, b.lane.unopenable)
    }

    /**
     * A signal delivered twice rings once.
     *
     * The server's per-device `_seen` list is one guard; this is the second. It matters
     * because `POLL_GRACE_MS` deliberately keeps a delivered signal readable, and because a
     * future WebSocket leg would deliver the same bytes a second time by design.
     */
    @Test
    fun aRedeliveredSignalRingsOnce() {
        val a = Endpoint()
        val b = Endpoint()
        a.lane.call(b.address, t0)

        val envelope = CallSignalEnvelope.decode(
            JSONObject(server.postedBodies[0]).toString()
        )!!
        assertTrue(b.lane.accept(envelope, t0 + 10))
        assertFalse("the same sealed blob a second time is not a second call", b.lane.accept(envelope, t0 + 11))
        assertEquals(1, b.events.filterIsInstance<CallLane.CallEvent.Ringing>().size)
    }

    /**
     * An offer the server refused does not leave this client ringing.
     *
     * Both phones can leave a dial ringing on a failed transport because they fan out to
     * three. This client has one path, so a `RINGING` state with nothing on the wire is a
     * 45-second lie ending in "no answer" — a diagnosis that names the wrong thing.
     */
    @Test
    fun anOfferTheServerRefusedDoesNotRing() {
        val a = Endpoint()
        val b = Endpoint()
        server.signalsPerWindow = 0     // every POST /signal answers 429

        val dialled = a.lane.call(b.address, t0)
        assertTrue("got $dialled", dialled is CallLane.Dialled.Refused)
        assertTrue((dialled as CallLane.Dialled.Refused).why.contains("429"))
        assertTrue(
            "the call must not be left ringing",
            a.lane.state == CallState.ENDED || a.lane.state == CallState.IDLE,
        )
        assertNotNull(a.problem())
    }

    /** A server that cannot be reached is -1, not "nobody is calling". */
    @Test
    fun anUnreachableServerIsNotSilence() {
        val b = Endpoint()
        server.failWith = 500
        assertEquals(-1, b.lane.pollOnce(t0))
        assertNotNull(b.problem())
    }

    /** A blocked peer cannot ring this client, and nothing is sent back. */
    @Test
    fun aBlockedPeerCannotRing() {
        val contacts = ContactStore(File(tmp.newFolder(), "contacts.json"))
        val a = Endpoint()
        val b = Endpoint(contacts = contacts)
        contacts.seen(a.address, t0)
        contacts.block(a.address)

        a.lane.call(b.address, t0)
        b.lane.pollOnce(t0 + 10)
        assertEquals(CallState.IDLE, b.lane.state)
        // BLOCKED carries no decline action — that asymmetry with BUSY is the entire privacy
        // property of blocking.
        assertEquals(1, server.postedBodies.size)
    }

    // ============================================================== the REPL

    /**
     * `/call` states that no audio will flow, every single time it is used.
     *
     * Not once at startup and not in a doc comment: a person told "calling…" and then
     * "connected" has been told they are on a call, and this client cannot carry a sample of
     * audio in either direction.
     */
    @Test
    fun theReplSaysNoAudioOnEveryCall() {
        val home = tmp.newFolder()
        val client = OshiClient(
            home = home,
            secretStore = InMemorySecretStore(),
            serverUrl = server.baseUrl,
            displayName = "cli",
        )
        try {
            val peer = DesktopIdentity.generate().userKey
            repeat(2) {
                val out = ArrayList<String>()
                ClientCommands.execute(client, "/call $peer") { out += it }
                // WHAT IT SAYS NOW DEPENDS ON THE BUILD, and both sentences matter.
                //
                // `OshiClient` configures a media opener, so this client DOES open audio
                // devices on a connected call and the old unconditional "no audio will
                // flow" would be wrong. It is not deleted — `audioNote` still prints it
                // when no opener is configured, and the branch below pins that too.
                //
                // What must never happen is silence about it in either direction: a user
                // who reads "no audio" over a call that has audio learns to skip the line,
                // and will skip it when it is true again.
                assertTrue(
                    "every /call must say something about audio, run $it printed $out",
                    out.any { line ->
                        line.contains("audio devices open") ||
                            line.contains(CallLane.NO_AUDIO_WILL_FLOW)
                    },
                )
                assertTrue(
                    "this client HAS an opener, so it must not claim no audio: $out",
                    out.none { line -> line.contains(CallLane.NO_AUDIO_WILL_FLOW) },
                )
                client.calls.hangUp()
                // ENDED settles back to IDLE after 2 s, so the second /call is a real dial
                // rather than a BUSY refusal that would print the warning for free.
                client.calls.tick(System.currentTimeMillis() + CallTimeouts.ENDED_SETTLE_MS + 1)
            }
            val status = ArrayList<String>()
            ClientCommands.execute(client, "/calls") { status += it }
            assertTrue(status.any { it.contains("audio devices open") })
            assertTrue(status.any { it.contains("in memory only") })
        } finally {
            client.stop()
        }
    }

    // ============================================================== helpers

    /** POST an envelope as a third party would — no lane, no state machine, no seal. */
    private fun postRaw(envelope: CallSignalEnvelope, nowMs: Long) {
        val stranger = DesktopIdentity.generate()
        val post = CallSignalClient(server.baseUrl, DesktopV2Signer(stranger))
            .sendSignal(envelope, nowMs)
        assertTrue("the server must accept a forged signal — it has no auth", post.accepted)
    }

    /**
     * THE OTHER BRANCH, pinned so the warning cannot quietly rot away.
     *
     * A lane built without a media opener is still a real configuration — it is what every
     * test in this file uses, and what the client shipped until today. It must keep saying
     * that nothing will be heard. Asserting only the audio-capable sentence would let
     * somebody delete `NO_AUDIO_WILL_FLOW` entirely and stay green.
     */
    @Test
    fun `a lane with no media opener still says no audio will flow`() {
        val lane = CallLane(
            myAddress = DesktopIdentity.generate().userKey,
            myPrivateKey = DesktopIdentity.generate().identity.priv,
            transport = CallSignalClient(baseUrl = "http://127.0.0.1:1", signer = null),
            deviceId = "dev",
            machine = CallStateMachine(DesktopIdentity.generate().userKey, null, "dev"),
        )
        assertFalse("no opener was passed, so this lane carries no audio", lane.carriesAudio)
    }
}
