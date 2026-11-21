#!/usr/bin/env python3
"""
Generate the OSHI device-sync golden vectors (docs/fixtures/devsync/*.json) from the
independent reference implementation in devsync_ref.py.

    python3 docs/fixtures/devsync/gen_vectors.py          # (re)write the vectors
    python3 docs/fixtures/devsync/gen_vectors.py --check  # verify the files on disk match

Everything is deterministic: every key is SHA-256 of a fixed label.
"""
import json
import os
import struct
import sys

import devsync_ref as R

HERE = os.path.dirname(os.path.abspath(__file__))


def H(b):
    return b.hex()


def label_key(label):
    return R.sha256(label.encode())


# ----------------------------------------------------------------------------- self-test of primitives
# Published known answers, so a broken primitive in THIS generator cannot silently become the contract.

def primitive_self_test():
    # RFC 7748 §6.1
    a = bytes.fromhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    b = bytes.fromhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    assert R.x25519_pub(a).hex() == "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
    assert R.x25519_pub(b).hex() == "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
    assert R.x25519(a, R.x25519_pub(b)).hex() == "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
    # RFC 5869 A.1
    okm = R.hkdf_rfc5869(b"\x0b" * 22, bytes(range(13)), bytes(range(0xf0, 0xfa)), 42)
    assert okm.hex() == ("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                         "34007208d5b887185865")
    # RFC 8439 §2.8.2 (AEAD_CHACHA20_POLY1305); nonce given raw here, not Noise-encoded
    from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
    key = bytes(range(0x80, 0xa0))
    nonce = bytes.fromhex("070000004041424344454647")
    aad = bytes.fromhex("50515253c0c1c2c3c4c5c6c7")
    pt = (b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for "
          b"the future, sunscreen would be it.")
    ct = ChaCha20Poly1305(key).encrypt(nonce, pt, aad)
    assert ct[-16:].hex() == "1ae10b594f09e26a7e902ecbd0600691"
    # RFC 8032 §7.1 TEST 1 (Ed25519, empty message) — relay_auth.json signs with Ed25519
    seed = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    assert ed25519_pub(seed).hex() == "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    assert ed25519_sign(seed, b"").hex() == ("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                                              "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
    return {
        "x25519_rfc7748": "ok", "hkdf_rfc5869_a1": "ok", "chacha20poly1305_rfc8439_2_8_2": "ok",
        "ed25519_rfc8032_test1": "ok",
    }


def ed25519_pub(seed):
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization
    return Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def ed25519_sign(seed, msg):
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    return Ed25519PrivateKey.from_private_bytes(seed).sign(msg)


# ----------------------------------------------------------------------------- fixed inputs

IK = bytes(range(1, 33))                         # account X25519 identity private key (0x01..0x20)
DK_A = label_key("OSHI-DEVSYNC vector device A static")
DK_B = label_key("OSHI-DEVSYNC vector device B static")
DK_C = label_key("OSHI-DEVSYNC vector device C static")
E_A = label_key("OSHI-DEVSYNC vector device A ephemeral 1")
E_B = label_key("OSHI-DEVSYNC vector device B ephemeral 1")
E_A2 = label_key("OSHI-DEVSYNC vector device A ephemeral 2")
E_B2 = label_key("OSHI-DEVSYNC vector device B ephemeral 2")
OTHER_IK = label_key("OSHI-DEVSYNC vector some other account")


def dsk_seed_for(dk_priv):
    # __DEVSYNC_DEVICE_AUTH_2026_09_23__ each vector device's Ed25519 device signing key (dsk),
    # the ONE per-install signing key: mailbox registration/approval, relay upgrade, HELLO "dsk".
    return R.sha256(b"OSHI-DEVSYNC vector dsk" + dk_priv)


def dsk_b64_for(dk_priv):
    import base64
    return base64.b64encode(ed25519_pub(dsk_seed_for(dk_priv))).decode()


def device(priv):
    pub = R.x25519_pub(priv)
    return {"priv": H(priv), "pub": H(pub), "deviceId": H(R.device_id(pub)),
            "dskSeed": H(dsk_seed_for(priv)), "dsk": dsk_b64_for(priv)}


def keys_vectors():
    psk = R.derive_psk(IK)
    disc = R.derive_disc(IK)
    times = [0, 1758585600, 1758589199, 1758589200, 1790000000]
    return {
        "description": "Design §3. PSK = HKDF-SHA256(ikm=IK, salt='OSHI-DEVSYNC-v1', info='noise-psk', 32). "
                       "DISC = same with info='mdns-tag'. deviceId = SHA-256('OSHI-DEVSYNC-id' || DK_pub)[0..16], "
                       "32 lowercase hex. mdnsTag = hex(HMAC-SHA256(DISC, 't' || u64be(floor(unix/3600))))[0..16]. "
                       "IK is the RAW 32-byte X25519 private key as stored (NOT clamped).",
        "account": {
            "ikPriv": H(IK), "ikPub": H(R.x25519_pub(IK)), "psk": H(psk), "disc": H(disc),
        },
        "otherAccount": {"ikPriv": H(OTHER_IK), "psk": H(R.derive_psk(OTHER_IK))},
        "devices": {"A": device(DK_A), "B": device(DK_B), "C": device(DK_C)},
        "mdnsTags": [{"unixSeconds": t, "hour": t // 3600, "tag": R.mdns_tag(disc, t)} for t in times],
        "sas": [
            {"handshakeHash": H(h), "code": R.sas_code(h)}
            for h in [bytes(32), bytes(range(32)), label_key("sas-1"), label_key("sas-2"), label_key("sas-3")]
        ],
        "sasDescription": "code = u32be(HKDF-SHA256(ikm=h, salt=empty (= 32 zero bytes), info='OSHI-DEVSYNC-sas', L=32)[0..4]) "
                          "mod 1_000_000, zero-padded to 6 digits. h = the final Noise handshake hash (after message 3).",
    }


def hello(dev_id_hex, name, platform, history_source, dsk):
    # "dsk" (__DEVSYNC_DEVICE_AUTH_2026_09_23__): the sender's public Ed25519 device signing key, so the
    # approving device can sign the per-device-mailbox approval before either side taps Allow.
    return json.dumps({
        "proto": 1, "deviceId": dev_id_hex, "name": name, "platform": platform, "app": "1.0.0",
        "caps": ["text", "media", "contacts", "profile", "read", "groups", "devices"],
        "historySource": history_source, "net": {"metered": False, "lowPower": False}, "media": "always",
        "dsk": dsk,
    }, separators=(",", ":")).encode()


def run_handshake(transport, init_s, init_e, resp_s, resp_e, psk_i, psk_r, prologue_i=None, prologue_r=None):
    id_i = R.device_id(R.x25519_pub(init_s))
    id_r = R.device_id(R.x25519_pub(resp_s))
    pro = R.prologue(transport, id_i, id_r)
    prologue_i = prologue_i if prologue_i is not None else pro
    prologue_r = prologue_r if prologue_r is not None else pro
    hi = R.Handshake(True, init_s, init_e, psk_i, prologue_i)
    hr = R.Handshake(False, resp_s, resp_e, psk_r, prologue_r)
    p1 = b'{"v":1}'
    p2 = hello(id_r.hex(), "Responder", "desktop", False, dsk_b64_for(resp_s))
    p3 = hello(id_i.hex(), "Initiator", "android", True, dsk_b64_for(init_s))
    out = {"prologue": H(prologue_i)}
    m1 = hi.write_msg1(p1)
    out["msg1"] = {"payload": H(p1), "payloadUtf8": p1.decode(), "message": H(m1), "hAfter": H(hi.ss.h)}
    try:
        got = hr.read_msg1(m1)
    except Exception as e:  # noqa
        out["responderResult"] = "msg1 authentication failed (" + type(e).__name__ + ")"
        return out, None
    assert got == p1
    m2 = hr.write_msg2(p2)
    out["msg2"] = {"payload": H(p2), "payloadUtf8": p2.decode(), "message": H(m2), "hAfter": H(hr.ss.h)}
    assert hi.read_msg2(m2) == p2
    m3 = hi.write_msg3(p3)
    out["msg3"] = {"payload": H(p3), "payloadUtf8": p3.decode(), "message": H(m3), "hAfter": H(hi.ss.h)}
    assert hr.read_msg3(m3) == p3
    assert hi.ss.h == hr.ss.h
    assert hi.rs == R.x25519_pub(resp_s) and hr.rs == R.x25519_pub(init_s)
    si, ri = hi.split()
    sr, rr = hr.split()
    assert si.k == rr.k and sr.k == ri.k
    out["handshakeHash"] = H(hi.ss.h)
    out["sasCode"] = R.sas_code(hi.ss.h)
    out["initiatorToResponderKey"] = H(si.k)
    out["responderToInitiatorKey"] = H(sr.k)
    return out, (hi, hr, si, ri, sr, rr)


def handshake_vectors():
    psk = R.derive_psk(IK)
    id_a, id_b = R.device_id(R.x25519_pub(DK_A)), R.device_id(R.x25519_pub(DK_B))
    # On the relay the SMALLER deviceId initiates (design §5.2).
    if id_a < id_b:
        small, big, se, be = DK_A, DK_B, E_A, E_B
        small_name, big_name = "A", "B"
    else:
        small, big, se, be = DK_B, DK_A, E_B, E_A
        small_name, big_name = "B", "A"
    cases = []
    relay, _ = run_handshake("relay", small, se, big, be, psk, psk)
    cases.append({"name": "relay", "transport": "relay", "initiator": small_name, "responder": big_name,
                  "initiatorStaticPriv": H(small), "initiatorEphemeralPriv": H(se),
                  "responderStaticPriv": H(big), "responderEphemeralPriv": H(be), "psk": H(psk), **relay})
    lan, _ = run_handshake("lan", DK_A, E_A2, DK_B, E_B2, psk, psk)
    cases.append({"name": "lan", "transport": "lan", "initiator": "A", "responder": "B",
                  "initiatorStaticPriv": H(DK_A), "initiatorEphemeralPriv": H(E_A2),
                  "responderStaticPriv": H(DK_B), "responderEphemeralPriv": H(E_B2), "psk": H(psk), **lan})
    other = R.derive_psk(OTHER_IK)
    bad, _ = run_handshake("relay", small, se, big, be, other, psk)
    cases.append({"name": "wrong_psk", "transport": "relay", "initiator": small_name, "responder": big_name,
                  "initiatorStaticPriv": H(small), "initiatorEphemeralPriv": H(se),
                  "responderStaticPriv": H(big), "responderEphemeralPriv": H(be),
                  "psk": H(other), "responderPsk": H(psk), "expect": "responder rejects message 1", **bad})
    spliced = R.prologue("relay", id_a, R.device_id(R.x25519_pub(DK_C)))
    sp, _ = run_handshake("relay", small, se, big, be, psk, psk, prologue_i=spliced)
    cases.append({"name": "prologue_mismatch", "transport": "relay", "initiator": small_name, "responder": big_name,
                  "initiatorStaticPriv": H(small), "initiatorEphemeralPriv": H(se),
                  "responderStaticPriv": H(big), "responderEphemeralPriv": H(be), "psk": H(psk),
                  "responderPrologue": H(R.prologue("relay", id_a, id_b)),
                  "expect": "responder rejects message 1 (a relay that splices routing ids cannot get past msg1)", **sp})
    return {
        "description": "Noise_XXpsk0_25519_ChaChaPoly_SHA256 (Noise rev 34). -> psk, e  <- e, ee, s, es  -> s, se. "
                       "Prologue = 'OSHI-DEVSYNC/1\\n' || transport || '\\n' || min(idI,idR) || max(idI,idR) (raw 16-byte ids, "
                       "byte order); on LAN it is only 'OSHI-DEVSYNC/1\\nlan\\n'. Payloads: msg1 = '{\"v\":1}', msg2 = responder "
                       "HELLO JSON, msg3 = initiator HELLO JSON (bare UTF-8 JSON, no record header). hAfter = handshake hash h "
                       "after the message was written/read. Keys: initiatorToResponderKey = first Split() key (c1).",
        "protocolName": R.PROTOCOL_NAME.decode(),
        "cases": cases,
    }


def transport_vectors():
    psk = R.derive_psk(IK)
    _, st = run_handshake("lan", DK_A, E_A2, DK_B, E_B2, psk, psk)
    hi, hr, si, ri, sr, rr = st
    records = [
        (0x10, 0, 1, b"{}"),
        (0x02, 0, 0, b'{"upTo":7}'),
        (0x7F, 0, 2, b'{"reason":"done"}'),
    ]
    i2r = []
    ti, tr = R.TransportCipher(si), R.TransportCipher(rr)
    for rt, fl, seq, body in records:
        pt = R.record(rt, fl, seq, body)
        ct = ti.encrypt(pt)
        assert tr.decrypt(ct) == pt
        i2r.append({"plaintext": H(pt), "ciphertext": H(ct)})
    r2i = []
    tr2, ti2 = R.TransportCipher(sr), R.TransportCipher(ri)
    for rt, fl, seq, body in [(0x11, 0, 1, b'{"conversations":[]}')]:
        pt = R.record(rt, fl, seq, body)
        ct = tr2.encrypt(pt)
        assert ti2.decrypt(ct) == pt
        r2i.append({"plaintext": H(pt), "ciphertext": H(ct)})

    # Rekey by message count (threshold lowered to 2 for the vector; production = 2^20).
    k0 = bytes.fromhex(H(R.derive_psk(label_key("rekey-seed"))))
    cs = R.CipherState(k0)
    tc = R.TransportCipher(cs, max_messages=2, max_bytes=1 << 30)
    rk_msgs = []
    for i in range(5):
        pt = ("message %d" % i).encode()
        k_before = cs.k
        ct = tc.encrypt(pt)
        rk_msgs.append({"plaintext": H(pt), "ciphertext": H(ct), "keyUsed": H(k_before), "nonce": i,
                        "keyAfter": H(cs.k)})
    # Rekey by bytes (threshold lowered to 100 bytes).
    cs2 = R.CipherState(k0)
    tc2 = R.TransportCipher(cs2, max_messages=1 << 20, max_bytes=100)
    rb_msgs = []
    for i, size in enumerate([40, 40, 40, 10]):
        pt = bytes([i]) * size
        k_before = cs2.k
        ct = tc2.encrypt(pt)
        rb_msgs.append({"plaintext": H(pt), "ciphertext": H(ct), "keyUsed": H(k_before), "keyAfter": H(cs2.k)})
    single = R.CipherState(k0)
    single.rekey()
    return {
        "description": "Transport phase: ENCRYPT(k, n, ad=empty, plaintext) with the Noise ChaChaPoly nonce (4 zero bytes || u64le n). "
                       "Each plaintext is ONE record (u8 type | u8 flags | u32be seq | body). Rekey policy per direction: after "
                       "each message count += 1 and bytes += len(ciphertext); when count >= 2^20 or bytes >= 2^30, Rekey() "
                       "(k = first 32 bytes of ENCRYPT(k, 2^64-1, empty, 32 zero bytes)) and reset both counters. The nonce n "
                       "is NOT reset by a rekey. The rekey vectors lower the thresholds (see 'policy').",
        "fromHandshake": "lan",
        "initiatorToResponder": i2r,
        "responderToInitiator": r2i,
        "rekeyFunction": {"keyBefore": H(k0), "keyAfter": H(single.k)},
        "rekeyByMessages": {"policy": {"maxMessages": 2, "maxBytes": 1 << 30}, "initialKey": H(k0), "messages": rk_msgs},
        "rekeyByBytes": {"policy": {"maxMessages": 1 << 20, "maxBytes": 100}, "initialKey": H(k0), "messages": rb_msgs},
    }


def framing_vectors():
    id_a = R.device_id(R.x25519_pub(DK_A))
    id_b = R.device_id(R.x25519_pub(DK_B))
    noise = bytes.fromhex("00112233445566778899aabbccddeeff")
    sha = R.sha256(b"media bytes")
    recs = [
        {"type": 0x11, "flags": 0, "seq": 1, "body": '{"conversations":[]}'},
        {"type": 0x02, "flags": 0, "seq": 0, "body": '{"upTo":31}'},
        {"type": 0x17, "flags": 1, "seq": 4294967295, "body": '{"conversati'},
    ]
    for r in recs:
        r["bytes"] = H(R.record(r["type"], r["flags"], r["seq"], r["body"].encode()))
    chunk_body = R.media_chunk_body(sha, 61440, True, b"\x01\x02\x03")
    return {
        "description": "LAN: u16be(len) || noise message (<= 65535). Relay (inside the WebSocket binary message): "
                       "[16-byte deviceId][u8 kind][noise message]; client->relay carries the DESTINATION id, relay->client "
                       "the SOURCE id; kind 1/2/3 = handshake message 1/2/3, 4 = transport. Record = u8 type | u8 flags | "
                       "u32be seq | body; flags bit0 = CONTINUED (body continues in the next record of the same type). "
                       "MEDIA_CHUNK body = sha256[32] || u64be offset || u8 last || data.",
        "records": recs,
        "lanFrame": {"noise": H(noise), "bytes": H(R.lan_frame(noise))},
        "relayOutbound": {"destination": H(id_b), "kind": 4, "noise": H(noise),
                          "bytes": H(R.relay_frame(id_b, R.RELAY_KIND_TRANSPORT, noise))},
        "relayInbound": {"source": H(id_a), "kind": 1, "noise": H(noise),
                         "bytes": H(R.relay_frame(id_a, R.RELAY_KIND_MSG1, noise))},
        "mediaChunk": {"sha256": H(sha), "offset": 61440, "last": True, "data": "010203", "body": H(chunk_body),
                       "record": H(R.record(0x21, 0, 9, chunk_body))},
        "limits": {"maxNoiseMessage": 65535, "maxRecordPlaintext": 65519, "maxMediaChunkData": 61440,
                   "relayMaxFrame": 65536 + 16},
    }


# ----------------------------------------------------------------------------- diff / merge

ME = "BYYEjmVAaT4GhJvFyH3pqIojV6Z4NiKTxiq4Dp8V5Ug="    # b64 of some 32 bytes (account pub, informational)
BOB = "3b6TE6T6dfqOHzhG5WVPCdhuzDHQXrKCFxVoTqnlPUE="
CAROL = "Ct5N3O9W1HIHoI5cGJ2S+Qm6q6YG6qu0JnL8R9cN4Qk="
GID = "5E2A9C1B-7D3F-4B8A-9E6C-0F1D2A3B4C5D"


def conv_direct(peer):
    return {"id": "direct:" + peer, "kind": "direct", "peerPublicKey": peer}


def conv_group(gid, name):
    return {"id": "group:" + gid, "kind": "group", "groupId": gid, "groupName": name}


def msg(mid, conv, direction, sender, ts, text=None, **kw):
    m = {"id": mid, "conversation": conv, "direction": direction, "senderPublicKey": sender,
         "timestamp": ts, "status": kw.pop("status", "delivered" if direction == "in" else "sent"),
         "read": kw.pop("read", True)}
    if text is not None:
        m["text"] = text
    m.update(kw)
    return m


def diff_vectors():
    d_bob = "direct:" + BOB
    d_carol = "direct:" + CAROL
    g = "group:" + GID
    convs = [conv_direct(BOB), conv_direct(CAROL), conv_group(GID, "Team")]
    common = [
        msg("0F2B7C4E-1A2B-4C3D-8E9F-000000000001", d_bob, "in", BOB, "2026-09-20T08:00:00.000Z", "Hi"),
        msg("0F2B7C4E-1A2B-4C3D-8E9F-000000000002", d_bob, "out", ME, "2026-09-20T08:01:00.000Z", "Hello Bob", status="read"),
        msg("0f2b7c4e-1a2b-4c3d-8e9f-000000000003", d_bob, "in", BOB, "2026-09-21T23:59:59.999Z",
            "Line1\nLine2 \"quoted\" \\ é 🙂"),
        msg("msg-g-1", g, "in", CAROL, "2026-09-21T10:00:00.000Z", "Group hello", senderName="Carol",
            reactions={"👍": [ME, BOB], "❤️": [CAROL]}),
    ]
    # Device A (e.g. the phone) and device B (e.g. the Mac) hold overlapping histories.
    a_only = [
        msg("a-only-1", d_bob, "out", ME, "2026-09-22T09:00:00.000Z", "Sent while the Mac was off"),
        msg("a-only-2", d_carol, "in", CAROL, "2026-09-22T09:30:00.000Z", None,
            media={"type": "image", "fileName": "cat.jpg", "mime": "image/jpeg", "size": 3,
                   "sha256": R.sha256(b"cat").hex()}),
    ]
    b_only = [
        msg("B-ONLY-1", d_bob, "in", BOB, "2026-09-22T12:00:00.000Z", "Arrived on the Mac while the phone was off"),
    ]
    # Same id, different mutable state on each side (rev differs) -> both WANT it, both merge.
    a_edit = msg("edit-1", d_bob, "out", ME, "2026-09-21T07:00:00.000Z", "tpyo", status="delivered",
                 edited={"text": "typo", "at": "2026-09-21T07:05:00.000Z"})
    b_edit = msg("EDIT-1", d_bob, "out", ME, "2026-09-21T07:00:00.000Z", "tpyo", status="read", read=True)
    a_side = common + a_only + [a_edit]
    b_side = common + b_only + [b_edit]

    def side_view(msgs):
        view = {"summary": [], "buckets": {}, "ids": {}}
        by_conv = {}
        for m in msgs:
            by_conv.setdefault(m["conversation"], []).append(m)
        for c in convs:
            ms = by_conv.get(c["id"], [])
            if not ms:
                continue
            view["summary"].append({
                "id": c["id"], "kind": c["kind"], "count": len(ms),
                "newest": max(m["timestamp"] for m in ms),
                "digest": R.digest((m["id"], R.rev(m)) for m in ms),
            })
            days = {}
            for m in ms:
                days.setdefault(R.utc_day(m["timestamp"]), []).append(m)
            view["buckets"][c["id"]] = [
                {"day": d, "count": len(days[d]), "digest": R.digest((m["id"], R.rev(m)) for m in days[d])}
                for d in sorted(days, reverse=True)
            ]
            view["ids"][c["id"]] = {
                d: sorted([[m["id"], R.rev(m)] for m in days[d]], key=lambda p: R.utf8_key(R.ascii_lower(p[0])))
                for d in sorted(days, reverse=True)
            }
        view["summary"].sort(key=lambda s: s["newest"], reverse=True)
        return view

    def wants(mine, theirs):
        have = {R.ascii_lower(m["id"]): R.rev(m) for m in mine}
        out = []
        for m in theirs:
            r = have.get(R.ascii_lower(m["id"]))
            if r is None or r != R.rev(m):
                out.append(m["id"])
        return sorted(out, key=lambda i: R.utf8_key(R.ascii_lower(i)))

    rev_table = []
    for m in a_side + b_only + [b_edit]:
        rev_table.append({"id": m["id"], "message": m, "canonical": R.cjson(R.rev_object(m)), "rev": R.rev(m)})
    merged_edit = R.merge_message(a_edit, b_edit)
    return {
        "description": "Design §6.2. rev = hex(SHA-256(utf8(canonicalJSON(revObject(m)))))[0..16]. revObject = "
                       "{deleted, edited:{at?,text}|null, reactions:{emoji:[sorted unique keys]}, read, status (direction "
                       "'out' only, else null)}; when deleted, edited=null and reactions={}. Canonical JSON: object keys "
                       "sorted by UTF-8 bytes, no whitespace, strings escape only '\"' '\\\\' and U+0000..U+001F (as \\u00xx, "
                       "lowercase hex), everything else raw UTF-8. digest = hex(SHA-256(join('\\n', sort_utf8(asciiLower(id) "
                       "':' rev)))). Day = UTC 'YYYY-MM-DD' of the timestamp. Ids compare case-insensitively (ASCII).",
        "conversations": convs,
        "revs": rev_table,
        "deviceA": {"messages": a_side, **side_view(a_side)},
        "deviceB": {"messages": b_side, **side_view(b_side)},
        "expected": {
            "aWantsFromB": wants(a_side, b_side),
            "bWantsFromA": wants(b_side, a_side),
            "mergedEdit1": merged_edit,
            "mergedEdit1Rev": R.rev(merged_edit),
            "mergedEdit1RevCommutes": R.rev(R.merge_message(b_edit, a_edit)) == R.rev(merged_edit),
        },
    }


def merge_vectors():
    base = msg("m1", "direct:" + BOB, "out", ME, "2026-09-20T08:00:00.000Z", "orig")
    cases = []

    def case(name, local_kw, remote_kw):
        local = dict(base, **local_kw)
        remote = dict(base, **remote_kw)
        for k, v in list(local_kw.items()):
            if v is None:
                local.pop(k, None)
        for k, v in list(remote_kw.items()):
            if v is None:
                remote.pop(k, None)
        merged = R.merge_message(local, remote)
        swapped = R.merge_message(remote, local)
        assert R.rev(merged) == R.rev(swapped), name
        cases.append({"name": name, "local": local, "remote": remote, "merged": merged, "mergedRev": R.rev(merged)})

    case("status_highest_wins", {"status": "sent"}, {"status": "read"})
    case("status_failed_loses_to_anything", {"status": "failed"}, {"status": "pending"})
    case("status_failed_both", {"status": "failed"}, {"status": "failed"})
    case("read_is_or", {"read": False}, {"read": True})
    case("edited_later_wins", {"edited": {"text": "v1", "at": "2026-09-20T08:01:00.000Z"}},
         {"edited": {"text": "v2", "at": "2026-09-20T08:02:00.000Z"}})
    case("edited_with_at_beats_without", {"edited": {"text": "no-at"}},
         {"edited": {"text": "with-at", "at": "2026-09-20T08:01:00.000Z"}})
    case("edited_tie_is_deterministic", {"edited": {"text": "a", "at": "2026-09-20T08:01:00.000Z"}},
         {"edited": {"text": "b", "at": "2026-09-20T08:01:00.000Z"}})
    case("edited_one_side_only", {"edited": {"text": "x", "at": "2026-09-20T08:01:00.000Z"}}, {})
    case("deleted_is_sticky", {"deleted": True, "text": None}, {"edited": {"text": "late edit", "at": "2026-09-20T09:00:00.000Z"}})
    case("reactions_union", {"reactions": {"👍": [BOB]}}, {"reactions": {"👍": [CAROL], "😂": [ME]}})

    contacts = []

    def ccase(name, local, remote):
        contacts.append({"name": name, "local": local, "remote": remote, "merged": R.merge_contact(local, remote)})

    ccase("alias_later_wins", {"publicKey": BOB, "alias": "Bob", "aliasAt": "2026-09-01T00:00:00.000Z"},
          {"publicKey": BOB, "alias": "Bobby", "aliasAt": "2026-09-02T00:00:00.000Z"})
    ccase("alias_local_newer_kept", {"publicKey": BOB, "alias": "Bob", "aliasAt": "2026-09-03T00:00:00.000Z"},
          {"publicKey": BOB, "alias": "Bobby", "aliasAt": "2026-09-02T00:00:00.000Z"})
    ccase("alias_legacy_gap_fill", {"publicKey": BOB}, {"publicKey": BOB, "alias": "Bob"})
    ccase("alias_legacy_never_overwrites", {"publicKey": BOB, "alias": "Mine"}, {"publicKey": BOB, "alias": "Theirs"})
    ccase("alias_timestamped_remote_does_not_overwrite_legacy_local", {"publicKey": BOB, "alias": "Mine"},
          {"publicKey": BOB, "alias": "Theirs", "aliasAt": "2026-09-02T00:00:00.000Z"})
    ccase("block_reaches_the_mac", {"publicKey": BOB, "blocked": False, "blockedAt": "2026-09-01T00:00:00.000Z"},
          {"publicKey": BOB, "blocked": True, "blockedAt": "2026-09-05T00:00:00.000Z"})
    ccase("unblock_later_wins", {"publicKey": BOB, "blocked": True, "blockedAt": "2026-09-01T00:00:00.000Z"},
          {"publicKey": BOB, "blocked": False, "blockedAt": "2026-09-05T00:00:00.000Z"})
    ccase("legacy_block_gap_fill", {"publicKey": BOB}, {"publicKey": BOB, "blocked": True})
    # __BLOCK_SYNC_LWW_2026_09_24__ an unblock travels as blocked:false WITH its blockedAt, and an
    # older record (a device that has not seen the unblock yet) can never re-block.
    ccase("stale_block_does_not_undo_newer_unblock",
          {"publicKey": BOB, "blocked": False, "blockedAt": "2026-09-05T00:00:00.000Z"},
          {"publicKey": BOB, "blocked": True, "blockedAt": "2026-09-01T00:00:00.000Z"})
    ccase("stale_unblock_does_not_undo_newer_block",
          {"publicKey": BOB, "blocked": True, "blockedAt": "2026-09-05T00:00:00.000Z"},
          {"publicKey": BOB, "blocked": False, "blockedAt": "2026-09-01T00:00:00.000Z"})
    ccase("unblock_timestamp_reaches_a_device_that_never_blocked",
          {"publicKey": BOB},
          {"publicKey": BOB, "blocked": False, "blockedAt": "2026-09-05T00:00:00.000Z"})
    # ... so that a device which never saw the block cannot be re-blocked by a stale third device:
    ccase("adopted_unblock_then_stale_block_stays_unblocked",
          R.merge_contact({"publicKey": BOB}, {"publicKey": BOB, "blocked": False, "blockedAt": "2026-09-05T00:00:00.000Z"}),
          {"publicKey": BOB, "blocked": True, "blockedAt": "2026-09-01T00:00:00.000Z"})

    profile = [
        {"local": {"name": "Hugo", "updatedAt": "2026-09-01T00:00:00.000Z"},
         "remote": {"name": "Hugo M", "updatedAt": "2026-09-02T00:00:00.000Z"}},
        {"local": {"name": "Hugo", "updatedAt": "2026-09-03T00:00:00.000Z"},
         "remote": {"name": "Hugo M", "updatedAt": "2026-09-02T00:00:00.000Z"}},
    ]
    for p in profile:
        p["merged"] = R.merge_profile(p["local"], p["remote"])

    read_state = [{"local": {"conv": "direct:" + BOB, "readUpTo": "2026-09-20T08:00:00.000Z", "at": "2026-09-20T09:00:00.000Z"},
                   "remote": {"conv": "direct:" + BOB, "readUpTo": "2026-09-21T08:00:00.000Z", "at": "2026-09-19T09:00:00.000Z"}}]
    for r in read_state:
        r["merged"] = R.merge_read_state(r["local"], r["remote"])

    k1 = {"kind": "legacy-aes", "version": 0, "key": "a2V5LW9uZS0zMi1ieXRlcy0tLS0tLS0tLS0tLS0tLS0="}
    k2 = {"kind": "v2-epoch", "version": 3, "key": "a2V5LXR3by0zMi1ieXRlcy0tLS0tLS0tLS0tLS0tLS0="}
    k3 = {"kind": "v2-epoch", "version": 4, "key": "a2V5LXRocmVlLTMyLWJ5dGVzLS0tLS0tLS0tLS0tLS0="}
    avatar = {"sha256": R.sha256(b"group picture").hex(), "size": 13, "mime": "image/jpeg"}
    g_local = {"groupId": GID, "name": "Team", "nameAt": "2026-09-10T00:00:00.000Z", "avatar": None,
               "members": [ME, BOB], "admins": [ME], "epoch": 3, "epochAt": "2026-09-10T00:00:00.000Z",
               "left": False, "keys": [k1, k2], "updatedAt": "2026-09-10T00:00:00.000Z",
               "type": "collaborative", "creator": ME}
    g_remote = {"groupId": GID, "name": "Team (renamed)", "nameAt": "2026-09-12T00:00:00.000Z",
                "avatar": avatar, "avatarAt": "2026-09-11T00:00:00.000Z",
                "members": [ME, BOB, CAROL], "admins": [ME, CAROL], "epoch": 4, "epochAt": "2026-09-12T00:00:00.000Z",
                "left": False, "keys": [k2, k3], "updatedAt": "2026-09-12T00:00:00.000Z"}
    g_same_epoch = dict(g_local, members=[ME, CAROL], admins=[ME, CAROL], epochAt="2026-09-11T00:00:00.000Z",
                        name="Other name", nameAt="2026-09-09T00:00:00.000Z", updatedAt="2026-09-11T00:00:00.000Z")
    g_left = dict(g_local, left=True, leftAt="2026-09-15T00:00:00.000Z", updatedAt="2026-09-15T00:00:00.000Z")
    g_no_epoch_a = {"groupId": GID, "name": "Legacy", "members": [ME, BOB], "admins": [ME], "updatedAt": "2026-09-01T00:00:00.000Z"}
    g_no_epoch_b = {"groupId": GID, "name": "Legacy", "members": [ME, CAROL], "admins": [], "updatedAt": "2026-09-02T00:00:00.000Z"}
    # __DEVSYNC_REJOIN_2026_09_23__ rejoin rule (design §8.4): a leave older than the last join is history.
    g_joined = dict(g_local, joinedAt="2026-09-20T00:00:00.000Z", updatedAt="2026-09-20T00:00:00.000Z")
    g_left_after_rejoin = dict(g_local, joinedAt="2026-09-20T00:00:00.000Z", left=True,
                               leftAt="2026-09-25T00:00:00.000Z", updatedAt="2026-09-25T00:00:00.000Z")
    g_left_later = dict(g_local, left=True, leftAt="2026-09-27T00:00:00.000Z", updatedAt="2026-09-27T00:00:00.000Z")
    g_left_legacy = dict(g_local, left=True)
    g_left_same_ms = dict(g_local, left=True, leftAt="2026-09-20T00:00:00.000Z", updatedAt="2026-09-20T00:00:00.000Z")
    # __GROUP_MUTE_SYNC_2026_09_24__ optional muted / mutedAt: later mutedAt wins.
    g_muted = dict(g_local, muted=True, mutedAt="2026-09-12T00:00:00.000Z")
    g_unmuted_later = dict(g_local, muted=False, mutedAt="2026-09-14T00:00:00.000Z")
    g_unmuted_earlier = dict(g_local, muted=False, mutedAt="2026-09-11T00:00:00.000Z")
    g_muted_legacy = dict(g_local, muted=True)
    groups = []
    for name, lo, re in [
        ("higher_epoch_wins_membership_later_nameAt_wins_keys_union", g_local, g_remote),
        ("equal_epoch_unions_membership_later_epochAt_kept", g_local, g_same_epoch),
        ("left_is_sticky", g_left, g_remote),
        ("no_epoch_anywhere_is_epoch_0_union", g_no_epoch_a, g_no_epoch_b),
        ("absent_locally_is_inserted", None, g_remote),
        ("rejoin_after_leave_wins", g_left, g_joined),
        ("leave_after_rejoin_wins", g_left_after_rejoin, g_left),
        ("two_leaves_after_the_join_keep_the_latest", g_left_after_rejoin, g_left_later),
        ("untimestamped_legacy_leave_loses_to_a_join", g_left_legacy, g_joined),
        ("untimestamped_legacy_leave_is_sticky_without_a_join", g_left_legacy, g_remote),
        ("leave_at_the_join_millisecond_counts", g_left_same_ms, g_joined),
        ("stale_leave_is_normalised_on_insert", None, dict(g_joined, left=True, leftAt="2026-09-15T00:00:00.000Z")),
        ("mute_later_wins", g_unmuted_earlier, g_muted),
        ("unmute_later_wins", g_muted, g_unmuted_later),
        ("peer_without_mute_fields_keeps_the_mute", g_muted, g_remote),
        ("timestamped_unmute_beats_untimestamped_mute", g_muted_legacy, g_unmuted_earlier),
        ("untimestamped_mute_gap_fills", g_local, g_muted_legacy),
    ]:
        merged = R.merge_group(lo, re)
        if lo is not None:
            assert R.cjson(merged) == R.cjson(R.merge_group(re, lo)), name
            assert R.cjson(R.merge_group(merged, merged)) == R.cjson(merged), name + " idempotent"
        groups.append({"name": name, "local": lo, "remote": re, "merged": merged, "canonical": R.cjson(merged)})
    # Associativity (three devices, any meeting order): leave@15 on A, rejoin@20 on C, leave@27 on B
    # must end LEFT@27 whatever the order (an "earliest leftAt" rule loses B's leave when A meets B first).
    trio = [g_left, g_joined, g_left_later, g_left_legacy, g_left_after_rejoin, g_remote,
            g_muted, g_unmuted_later, g_muted_legacy]
    for x in trio:
        for y in trio:
            for z in trio:
                l = R.cjson(R.merge_group(R.merge_group(x, y), z))
                r = R.cjson(R.merge_group(x, R.merge_group(y, z)))
                assert l == r, "group merge is not associative"
    assert R.merge_group(R.merge_group(g_left, g_left_later), g_joined)["leftAt"] == "2026-09-27T00:00:00.000Z"

    # __BLOCK_MUTE_RECORDS_2026_09_24__ record parity (parse -> write back): every implementation keeps
    # an unblock / unmute WITH its timestamp and ignores fields it does not know (old peers too).
    bob_urlsafe = BOB.replace("+", "-").replace("/", "_").rstrip("=")
    rec_contacts = [
        {"name": "unblock_is_kept_with_its_timestamp",
         "wire": {"publicKey": BOB, "blocked": False, "blockedAt": "2026-09-24T10:00:00.000Z"}},
        {"name": "block_with_timestamp",
         "wire": {"publicKey": BOB, "alias": "Bob", "aliasAt": "2026-09-01T00:00:00.000Z",
                  "blocked": True, "blockedAt": "2026-09-24T09:00:00.000Z"}},
        {"name": "urlsafe_unpadded_key_is_canonicalised",
         "wire": {"publicKey": bob_urlsafe, "blocked": True, "blockedAt": "2026-09-24T09:00:00.000Z"}},
        {"name": "unknown_fields_are_ignored",
         "wire": {"publicKey": BOB, "blocked": False, "blockedAt": "2026-09-24T10:00:00.000Z",
                  "blockedBy": "future", "reason": "spam"}},
    ]
    for c in rec_contacts:
        c["json"] = R.contact_record(c["wire"])
    rec_groups = [
        {"name": "mute_fields_round_trip", "wire": dict(g_local, muted=True, mutedAt="2026-09-24T08:00:00.000Z")},
        {"name": "unmute_is_kept_with_its_timestamp", "wire": dict(g_local, muted=False, mutedAt="2026-09-24T09:00:00.000Z")},
        {"name": "old_peer_without_mute_fields", "wire": dict(g_local)},
        {"name": "unknown_fields_are_ignored", "wire": dict(g_local, muted=True, mutedAt="2026-09-24T08:00:00.000Z",
                                                            mutedUntil="2026-10-01T00:00:00.000Z", colour="red")},
    ]
    for g in rec_groups:
        g["canonical"] = R.cjson(R.group_record(g["wire"]))
    records = {"contacts": rec_contacts, "groups": rec_groups}

    a, b, c = (device(DK_A), device(DK_B), device(DK_C))
    local_dev = {"linked": {b["deviceId"]: {"deviceId": b["deviceId"], "dk": b["pub"], "name": "Mac", "platform": "desktop",
                                            "approvedAt": "2026-09-01T00:00:00.000Z"}}, "revoked": []}
    remote_rec = {"linked": [
        {"deviceId": c["deviceId"], "dk": c["pub"], "name": "iPad", "platform": "ios", "approvedAt": "2026-09-02T00:00:00.000Z"},
        {"deviceId": a["deviceId"], "dk": a["pub"], "name": "self", "platform": "android", "approvedAt": "2026-09-02T00:00:00.000Z"},
        {"deviceId": "00" * 16, "dk": c["pub"], "name": "forged id", "platform": "ios", "approvedAt": "2026-09-02T00:00:00.000Z"},
    ], "revoked": []}
    revoke_rec = {"linked": [], "revoked": [b["deviceId"]]}
    m1 = R.merge_devices(local_dev, remote_rec, a["deviceId"])
    m2 = R.merge_devices(m1, revoke_rec, a["deviceId"])
    m3 = R.merge_devices(m2, {"linked": [local_dev["linked"][b["deviceId"]]], "revoked": []}, a["deviceId"])

    def dev_view(d):
        return {"linked": sorted(d["linked"].keys()), "revoked": sorted(d["revoked"])}

    devices = {
        "selfDeviceId": a["deviceId"],
        "local": dev_view(local_dev),
        "steps": [
            {"record": remote_rec, "after": dev_view(m1),
             "note": "C is added (dk hashes to its id); self is ignored; an entry whose dk does not hash to its id is refused"},
            {"record": revoke_rec, "after": dev_view(m2), "note": "revocation removes B and is sticky"},
            {"record": {"linked": [local_dev["linked"][b["deviceId"]]], "revoked": []}, "after": dev_view(m3),
             "note": "a revoked device never comes back through DEVICES (only a fresh local approval clears it)"},
        ],
    }
    return {
        "description": "Design §8. Merges are commutative and idempotent. Message: status = highest of pending<sent<delivered<read, "
                       "failed only if neither side has better; read = OR; edited = later 'at' wins (an edit with 'at' beats one "
                       "without, ties by UTF-8 bytes of canonical JSON); deleted = sticky true and clears text/edited/reactions; "
                       "reactions = union. Contacts: per field (alias/blocked/verified) the later *At wins; if either side lacks "
                       "a timestamp, gap-fill only (a local side with neither value nor timestamp adopts the remote value AND its "
                       "timestamp, even blocked:false: an unblock is a timestamped fact). Profile: later updatedAt wins. Read state: max. Groups (design §8.4): "
                       "members/admins = the higher epoch as a whole, the union at equal epoch (absent epoch = 0), epochAt "
                       "follows; name (+ descriptive extras description/type/creator/avatarEmoji/pinned*) = later nameAt; "
                       "avatar = later avatarAt (a timestamped value beats an untimestamped one, ties by canonical-JSON "
                       "bytes); keys = union by (kind, version), never dropped, sorted by (kind, version); joinedAt = max; "
                       "left: a leave counts only if not older than joinedAt (timestamped leftAt >= joinedAt; an "
                       "untimestamped legacy leave only while no join is known), left = any leave that counts, "
                       "leftAt = the latest such leave (associative); muted (optional) = later mutedAt, a timestamped value beats an "
                       "untimestamped one, ties by canonical-JSON bytes; updatedAt = max. Member/admin lists are sorted unique (UTF-8 order). 'canonical' "
                       "is the canonical JSON of the merged group (every field present, lists sorted).",
        "messages": cases,
        "contacts": contacts,
        "profile": profile,
        "readState": read_state,
        "groups": groups,
        "devices": devices,
        "records": records,
    }


# ----------------------------------------------------------------------------- relay upgrade auth
# __DEVSYNC_DEVICE_AUTH_2026_09_23__ ServerPatches/devsync_device_auth/ (design §5.2, Appendix A/C.13).

def b64(b):
    import base64
    return base64.b64encode(b).decode()


def relay_upgrade_case(name, identity, account_seed, dk_pub, dsk_seed, ts, nonce_hex):
    import hashlib
    device_id = H(R.device_id(dk_pub))
    dk, dsk = b64(dk_pub), b64(ed25519_pub(dsk_seed))
    binding = "OSHI-DEVSYNC-UPGRADE/1\n%s\n%s\n%s\n%s\n%s" % (identity, device_id, dk, dsk, nonce_hex)
    bh = hashlib.sha256(binding.encode()).hexdigest()
    account_str = "GET\n/v2/devsync\n%s\n%s" % (bh, ts)
    device_str = "OSHI-DEVICE/1\n%s\nGET\n/v2/devsync\n%s\n%s" % (device_id, bh, ts)
    account_sig = b64(ed25519_sign(account_seed, account_str.encode()))
    device_sig = b64(ed25519_sign(dsk_seed, device_str.encode()))
    return {
        "name": name,
        "inputs": {
            "identity": identity, "accountSigningSeed": H(account_seed), "dkPub": H(dk_pub),
            "dskSeed": H(dsk_seed), "ts": ts, "nonce": nonce_hex,
        },
        "deviceId": device_id, "dk": dk, "dsk": dsk,
        "binding": binding, "bindingSha256": bh,
        "accountString": account_str, "accountSignature": account_sig,
        "deviceString": device_str, "deviceSignature": device_sig,
        "requestTarget": "/v2/devsync?device=" + device_id,
        "headers": {
            "x-oshi-user": identity,
            "x-oshi-signing-pubkey": b64(ed25519_pub(account_seed)),
            "x-oshi-signature": account_sig,
            "x-oshi-timestamp": ts,
            "x-oshi-device": device_id,
            "x-oshi-device-dk": dk,
            "x-oshi-device-dsk": dsk,
            "x-oshi-devsync-nonce": nonce_hex,
            "x-oshi-device-signature": device_sig,
        },
    }


def relay_auth_vectors():
    identity = "ERERERERERERERERERERERERERERERERERERERERERE="          # normalised account X25519 userKey (32 x 0x11)
    account_seed = bytes.fromhex("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7")  # RFC 8032 test 3
    dk1 = bytes(range(1, 33))                                           # = per_device_mailbox/device_vectors.json dk
    dsk1 = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")  # RFC 8032 test 1
    dk2 = bytes(range(33, 65))
    dsk2 = bytes.fromhex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")  # RFC 8032 test 2
    ts = "1790000000000"
    cases = [
        relay_upgrade_case("device1", identity, account_seed, dk1, dsk1, ts, "000102030405060708090a0b0c0d0e0f"),
        relay_upgrade_case("device2-same-millisecond", identity, account_seed, dk2, dsk2, ts, "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"),
        relay_upgrade_case("device1-same-millisecond-new-nonce", identity, account_seed, dk1, dsk1, ts, "0f0e0d0c0b0a09080706050403020100"),
    ]
    sigs = [c["accountSignature"] for c in cases]
    assert len(set(sigs)) == len(sigs), "same-millisecond upgrades must not collide"
    return {
        "description": "Devsync relay upgrade (GET /v2/devsync?device=<id>), device-bound. "
                       "B = 'OSHI-DEVSYNC-UPGRADE/1\\n<identity>\\n<deviceId>\\n<dk>\\n<dsk>\\n<nonce>' (UTF-8, no trailing newline). "
                       "Account signature (account Ed25519 key) over 'GET\\n/v2/devsync\\n<sha256hex(B)>\\n<ts>' — B replaces the empty body. "
                       "Device signature (device Ed25519 key dsk) over 'OSHI-DEVICE/1\\n<deviceId>\\nGET\\n/v2/devsync\\n<sha256hex(B)>\\n<ts>' "
                       "(the per-device-mailbox request string with body = B). identity = normalised account X25519 userKey "
                       "(standard base64, padded); dk/dsk = standard padded base64 of the raw 32-byte public keys; "
                       "deviceId = hex(SHA-256('OSHI-DEVSYNC-id' || dk))[0..32]; nonce = 16 fresh random bytes per upgrade, "
                       "32 lowercase hex; ts = the x-oshi-timestamp (ms) of the account signature, reused in the device string. "
                       "Every value below is standard padded base64 unless named hex. Seeds: RFC 8032 §7.1 tests 1-3.",
        "cases": cases,
        "serverRefuses": [
            {"when": "any device header missing (account-only upgrade, the pre-2026-09-23 scheme)", "status": 401, "error": "device-auth-required"},
            {"when": "x-oshi-device != ?device=", "status": 400, "error": "device-mismatch"},
            {"when": "dk/dsk not strict standard padded base64 of 32 bytes, or nonce not 32 lowercase hex", "status": 400, "error": "bad-device-auth"},
            {"when": "deviceId != H(dk)", "status": 400, "error": "deviceId-not-bound-to-dk"},
            {"when": "account signature bad / replayed / unbound, or B built with another identity", "status": 401, "error": "unauthorized"},
            {"when": "device signature does not verify under dsk", "status": 401, "error": "bad-device-signature"},
            {"when": "(deviceId, nonce) already seen in the last 60 s", "status": 401, "error": "replayed"},
            {"when": "deviceId registered (per-device registry) with other dk/dsk", "status": 403, "error": "device-key-mismatch"},
            {"when": "deviceId live on another socket with another dsk (and the newcomer is not the registered owner)", "status": 409, "error": "device-id-in-use"},
            {"when": "bootstrap (pending) sockets of the identity already at the cap (2)", "status": 429, "error": "too-many-pending"},
        ],
        "closeCodes": {"4000": "replaced by a newer socket of the SAME device key (or a registered owner evicting an unregistered squatter)",
                       "4001": "pending-expired (bootstrap socket older than 15 min)",
                       "4003": "device-removed / device-reset / device-key-mismatch (registry changed under the socket)"},
    }


def dump(name, obj):
    return name, json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=False) + "\n"


def main():
    selftest = primitive_self_test()
    files = [
        dump("keys.json", keys_vectors()),
        dump("noise_handshake.json", handshake_vectors()),
        dump("noise_transport.json", transport_vectors()),
        dump("framing.json", framing_vectors()),
        dump("diff.json", diff_vectors()),
        dump("merge.json", merge_vectors()),
        dump("relay_auth.json", relay_auth_vectors()),
    ]
    check = "--check" in sys.argv
    bad = 0
    for name, text in files:
        path = os.path.join(HERE, name)
        if check:
            with open(path, encoding="utf-8") as f:
                if f.read() != text:
                    print("DIFFERS:", name)
                    bad += 1
        else:
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
    print("primitive self-test:", selftest)
    print(("checked " if check else "wrote ") + ", ".join(n for n, _ in files))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
