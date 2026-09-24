#!/usr/bin/env python3
"""
devsync_ref.py -- INDEPENDENT reference implementation of OSHI own-device sync
("direct strict", docs/OSHI_DEVICE_SYNC_DIRECT.md), used to GENERATE the golden
vectors in this directory.

It is written from the Noise specification (revision 34) and the design doc only,
with the primitives of pyca/cryptography (OpenSSL) and the Python standard library.
It shares NO code with the Kotlin implementation
(OSHI-Android/.../network/v2/devsync/), which uses BouncyCastle; the Kotlin unit
tests (Android + Desktop) must reproduce every byte written here. The iOS
implementation (CryptoKit) is expected to do the same.

Run:  python3 docs/fixtures/devsync/gen_vectors.py   (writes the *.json vectors)
"""
import hashlib
import hmac
import struct

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives import serialization

# --------------------------------------------------------------------------- primitives

def sha256(b: bytes) -> bytes:
    return hashlib.sha256(b).digest()


def hmac_sha256(key: bytes, data: bytes) -> bytes:
    return hmac.new(key, data, hashlib.sha256).digest()


def hkdf_rfc5869(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    """RFC 5869 HKDF-SHA256. An empty salt means HashLen zero bytes (RFC 5869 2.2)."""
    if not salt:
        salt = b"\x00" * 32
    prk = hmac_sha256(salt, ikm)
    out, t, i = b"", b"", 1
    while len(out) < length:
        t = hmac_sha256(prk, t + info + bytes([i]))
        out += t
        i += 1
    return out[:length]


def x25519_pub(priv: bytes) -> bytes:
    return X25519PrivateKey.from_private_bytes(priv).public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def x25519(priv: bytes, pub: bytes) -> bytes:
    return X25519PrivateKey.from_private_bytes(priv).exchange(X25519PublicKey.from_public_bytes(pub))


def chachapoly_encrypt(k: bytes, n: int, ad: bytes, plaintext: bytes) -> bytes:
    # Noise ChaChaPoly nonce: 32 bits of zeros followed by the 64-bit counter, LITTLE-endian.
    nonce = b"\x00\x00\x00\x00" + struct.pack("<Q", n)
    return ChaCha20Poly1305(k).encrypt(nonce, plaintext, ad)


def chachapoly_decrypt(k: bytes, n: int, ad: bytes, ciphertext: bytes) -> bytes:
    nonce = b"\x00\x00\x00\x00" + struct.pack("<Q", n)
    return ChaCha20Poly1305(k).decrypt(nonce, ciphertext, ad)


# --------------------------------------------------------------------------- keys (design §3)

SALT = b"OSHI-DEVSYNC-v1"


def derive_psk(ik_priv: bytes) -> bytes:
    return hkdf_rfc5869(ik_priv, SALT, b"noise-psk", 32)


def derive_disc(ik_priv: bytes) -> bytes:
    return hkdf_rfc5869(ik_priv, SALT, b"mdns-tag", 32)


def device_id(dk_pub: bytes) -> bytes:
    return sha256(b"OSHI-DEVSYNC-id" + dk_pub)[:16]


def mdns_tag(disc: bytes, unix_seconds: int) -> str:
    hour = unix_seconds // 3600
    return hmac_sha256(disc, b"t" + struct.pack(">Q", hour)).hex()[:16]


def sas_code(handshake_hash: bytes) -> str:
    okm = hkdf_rfc5869(handshake_hash, b"", b"OSHI-DEVSYNC-sas", 32)
    v = struct.unpack(">I", okm[:4])[0]
    return "%06d" % (v % 1000000)


PROLOGUE_PREFIX = b"OSHI-DEVSYNC/1\n"


def prologue(transport: str, id_a: bytes = None, id_b: bytes = None) -> bytes:
    if transport == "lan":
        return PROLOGUE_PREFIX + b"lan\n"
    lo, hi = sorted([id_a, id_b])
    return PROLOGUE_PREFIX + transport.encode() + b"\n" + lo + hi


# --------------------------------------------------------------------------- Noise (rev 34)

PROTOCOL_NAME = b"Noise_XXpsk0_25519_ChaChaPoly_SHA256"
MAX_NONCE = 2 ** 64 - 1


class CipherState:
    def __init__(self, k=None):
        self.k = k
        self.n = 0

    def has_key(self):
        return self.k is not None

    def encrypt_with_ad(self, ad, plaintext):
        if self.k is None:
            return plaintext
        if self.n >= MAX_NONCE:
            raise ValueError("nonce exhausted")
        ct = chachapoly_encrypt(self.k, self.n, ad, plaintext)
        self.n += 1
        return ct

    def decrypt_with_ad(self, ad, ciphertext):
        if self.k is None:
            return ciphertext
        if self.n >= MAX_NONCE:
            raise ValueError("nonce exhausted")
        pt = chachapoly_decrypt(self.k, self.n, ad, ciphertext)
        self.n += 1
        return pt

    def rekey(self):
        # REKEY(k) = ENCRYPT(k, maxnonce, zerolen, zeros) truncated to 32 bytes. n unchanged.
        self.k = chachapoly_encrypt(self.k, MAX_NONCE, b"", b"\x00" * 32)[:32]


def noise_hkdf(ck, ikm, n):
    temp = hmac_sha256(ck, ikm)
    o1 = hmac_sha256(temp, b"\x01")
    o2 = hmac_sha256(temp, o1 + b"\x02")
    if n == 2:
        return o1, o2
    o3 = hmac_sha256(temp, o2 + b"\x03")
    return o1, o2, o3


class SymmetricState:
    def __init__(self):
        if len(PROTOCOL_NAME) <= 32:
            self.h = PROTOCOL_NAME + b"\x00" * (32 - len(PROTOCOL_NAME))
        else:
            self.h = sha256(PROTOCOL_NAME)
        self.ck = self.h
        self.cs = CipherState()

    def mix_key(self, ikm):
        self.ck, temp_k = noise_hkdf(self.ck, ikm, 2)
        self.cs = CipherState(temp_k[:32])

    def mix_hash(self, data):
        self.h = sha256(self.h + data)

    def mix_key_and_hash(self, ikm):
        self.ck, temp_h, temp_k = noise_hkdf(self.ck, ikm, 3)
        self.mix_hash(temp_h)
        self.cs = CipherState(temp_k[:32])

    def encrypt_and_hash(self, plaintext):
        ct = self.cs.encrypt_with_ad(self.h, plaintext)
        self.mix_hash(ct)
        return ct

    def decrypt_and_hash(self, ciphertext):
        pt = self.cs.decrypt_with_ad(self.h, ciphertext)
        self.mix_hash(ciphertext)
        return pt

    def split(self):
        k1, k2 = noise_hkdf(self.ck, b"", 2)
        return CipherState(k1[:32]), CipherState(k2[:32])


class Handshake:
    """Noise_XXpsk0:  -> psk, e   <- e, ee, s, es   -> s, se"""

    def __init__(self, initiator, s_priv, e_priv, psk, prologue_bytes):
        self.initiator = initiator
        self.s_priv, self.s_pub = s_priv, x25519_pub(s_priv)
        self.e_priv, self.e_pub = e_priv, x25519_pub(e_priv)
        self.psk = psk
        self.rs = None
        self.re = None
        self.ss = SymmetricState()
        self.ss.mix_hash(prologue_bytes)
        self.step = 0

    # message 1 ------------------------------------------------------------
    def write_msg1(self, payload):
        assert self.initiator and self.step == 0
        self.ss.mix_key_and_hash(self.psk)            # psk
        self.ss.mix_hash(self.e_pub)                  # e
        self.ss.mix_key(self.e_pub)                   #   (psk mode: e also MixKey)
        out = self.e_pub + self.ss.encrypt_and_hash(payload)
        self.step = 1
        return out

    def read_msg1(self, msg):
        assert not self.initiator and self.step == 0
        self.ss.mix_key_and_hash(self.psk)
        self.re = msg[:32]
        self.ss.mix_hash(self.re)
        self.ss.mix_key(self.re)
        pt = self.ss.decrypt_and_hash(msg[32:])
        self.step = 1
        return pt

    # message 2 ------------------------------------------------------------
    def write_msg2(self, payload):
        assert not self.initiator and self.step == 1
        out = self.e_pub
        self.ss.mix_hash(self.e_pub)                  # e
        self.ss.mix_key(self.e_pub)
        self.ss.mix_key(x25519(self.e_priv, self.re))  # ee
        out += self.ss.encrypt_and_hash(self.s_pub)   # s
        self.ss.mix_key(x25519(self.s_priv, self.re))  # es (responder: DH(s, re))
        out += self.ss.encrypt_and_hash(payload)
        self.step = 2
        return out

    def read_msg2(self, msg):
        assert self.initiator and self.step == 1
        self.re = msg[:32]
        self.ss.mix_hash(self.re)
        self.ss.mix_key(self.re)
        self.ss.mix_key(x25519(self.e_priv, self.re))  # ee
        self.rs = self.ss.decrypt_and_hash(msg[32:32 + 48])  # s (32 + 16 tag)
        self.ss.mix_key(x25519(self.e_priv, self.rs))  # es (initiator: DH(e, rs))
        pt = self.ss.decrypt_and_hash(msg[80:])
        self.step = 2
        return pt

    # message 3 ------------------------------------------------------------
    def write_msg3(self, payload):
        assert self.initiator and self.step == 2
        out = self.ss.encrypt_and_hash(self.s_pub)    # s
        self.ss.mix_key(x25519(self.s_priv, self.re))  # se (initiator: DH(s, re))
        out += self.ss.encrypt_and_hash(payload)
        self.step = 3
        return out

    def read_msg3(self, msg):
        assert not self.initiator and self.step == 2
        self.rs = self.ss.decrypt_and_hash(msg[:48])  # s
        self.ss.mix_key(x25519(self.e_priv, self.rs))  # se (responder: DH(e, rs))
        pt = self.ss.decrypt_and_hash(msg[48:])
        self.step = 3
        return pt

    def split(self):
        c1, c2 = self.ss.split()
        # c1: initiator -> responder, c2: responder -> initiator
        return (c1, c2) if self.initiator else (c2, c1)  # (send, recv)


# --------------------------------------------------------------------------- transport with rekey policy

REKEY_MESSAGES = 1 << 20
REKEY_BYTES = 1 << 30


class TransportCipher:
    """One direction. After every message: count += 1, bytes += len(ciphertext); when either
    reaches its threshold, Rekey() and reset both counters. The nonce is NOT reset."""

    def __init__(self, cs, max_messages=REKEY_MESSAGES, max_bytes=REKEY_BYTES):
        self.cs = cs
        self.max_messages = max_messages
        self.max_bytes = max_bytes
        self.count = 0
        self.bytes = 0
        self.rekeys = 0

    def _after(self, ct_len):
        self.count += 1
        self.bytes += ct_len
        if self.count >= self.max_messages or self.bytes >= self.max_bytes:
            self.cs.rekey()
            self.rekeys += 1
            self.count = 0
            self.bytes = 0

    def encrypt(self, plaintext):
        ct = self.cs.encrypt_with_ad(b"", plaintext)
        self._after(len(ct))
        return ct

    def decrypt(self, ciphertext):
        pt = self.cs.decrypt_with_ad(b"", ciphertext)
        self._after(len(ciphertext))
        return pt


# --------------------------------------------------------------------------- framing (design §5, §6)

RELAY_KIND_MSG1, RELAY_KIND_MSG2, RELAY_KIND_MSG3, RELAY_KIND_TRANSPORT = 1, 2, 3, 4


def record(rtype, flags, seq, body: bytes) -> bytes:
    return struct.pack(">BBI", rtype, flags, seq) + body


def lan_frame(noise_msg: bytes) -> bytes:
    assert len(noise_msg) <= 65535
    return struct.pack(">H", len(noise_msg)) + noise_msg


def relay_frame(device: bytes, kind: int, noise_msg: bytes) -> bytes:
    assert len(device) == 16
    return device + bytes([kind]) + noise_msg


def media_chunk_body(sha: bytes, offset: int, last: bool, data: bytes) -> bytes:
    return sha + struct.pack(">Q", offset) + bytes([1 if last else 0]) + data


# --------------------------------------------------------------------------- canonical JSON, rev, digests (§6.2)

def cjson_str(s: str) -> str:
    out = ['"']
    for ch in s:
        o = ord(ch)
        if ch == '"':
            out.append('\\"')
        elif ch == '\\':
            out.append('\\\\')
        elif o < 0x20:
            out.append('\\u%04x' % o)
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def utf8_key(s):
    return s.encode("utf-8")


def cjson(v) -> str:
    if v is None:
        return "null"
    if v is True:
        return "true"
    if v is False:
        return "false"
    if isinstance(v, str):
        return cjson_str(v)
    if isinstance(v, int):
        return str(v)
    if isinstance(v, list):
        return "[" + ",".join(cjson(x) for x in v) + "]"
    if isinstance(v, dict):
        keys = sorted(v.keys(), key=utf8_key)
        return "{" + ",".join(cjson_str(k) + ":" + cjson(v[k]) for k in keys) + "}"
    raise TypeError(type(v))


def rev_object(m):
    """The mutable fields of a v2 message, normalised (see README 'rev')."""
    edited = None
    if m.get("edited") is not None:
        e = m["edited"]
        edited = {"text": e.get("text", "")}
        if e.get("at") is not None:
            edited["at"] = e["at"]
    reactions = {}
    for emoji, keys in (m.get("reactions") or {}).items():
        ks = sorted(set(keys), key=utf8_key)
        if ks:
            reactions[emoji] = ks
    deleted = bool(m.get("deleted", False))
    if deleted:
        edited, reactions = None, {}
    return {
        "deleted": deleted,
        "edited": edited,
        "reactions": reactions,
        "read": bool(m.get("read", False)),
        "status": m.get("status") if m.get("direction") == "out" else None,
    }


def rev(m) -> str:
    return sha256(cjson(rev_object(m)).encode("utf-8")).hex()[:16]


def ascii_lower(s):
    return "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in s)


def digest(pairs) -> str:
    """pairs: iterable of (id, rev)."""
    lines = sorted((ascii_lower(i) + ":" + r for i, r in pairs), key=utf8_key)
    return sha256("\n".join(lines).encode("utf-8")).hex()


def utc_day(iso: str) -> str:
    return iso[:10]


# --------------------------------------------------------------------------- merge rules (§8)

STATUS_RANK = {"failed": 0, "pending": 1, "sent": 2, "delivered": 3, "read": 4}


def merge_status(a, b):
    """Highest of pending < sent < delivered < read; `failed` only if neither side has better.
    Values are normalised by the reader first (unknown -> "delivered" for in, "sent" for out)."""
    if a is None:
        return b
    if b is None:
        return a
    return a if STATUS_RANK.get(a, 0) >= STATUS_RANK.get(b, 0) else b


def edited_key(e):
    # Later `at` wins; an edit without `at` loses to any edit with one; exact ties are broken
    # by the UTF-8 bytes of the canonical JSON, so the merge is commutative.
    return (e.get("at") is not None, e.get("at") or "", cjson(e).encode("utf-8"))


def merge_edited(a, b):
    if a is None:
        return b
    if b is None:
        return a
    return a if edited_key(a) >= edited_key(b) else b


def merge_reactions(a, b):
    out = {}
    for src in (a or {}, b or {}):
        for emoji, keys in src.items():
            out.setdefault(emoji, set()).update(keys)
    return {e: sorted(ks, key=utf8_key) for e, ks in sorted(out.items(), key=lambda kv: utf8_key(kv[0])) if ks}


def merge_message(local, remote):
    """Returns the merged v2 message (local's immutable fields are kept)."""
    m = dict(local)
    m["status"] = merge_status(local.get("status"), remote.get("status"))
    m["read"] = bool(local.get("read", False)) or bool(remote.get("read", False))
    deleted = bool(local.get("deleted", False)) or bool(remote.get("deleted", False))
    if deleted:
        # Sticky. A message deleted for everyone carries no text, edit, reactions or media bytes.
        m["deleted"] = True
        for f in ("text", "edited", "reactions"):
            m.pop(f, None)
        return m
    m.pop("deleted", None)
    ed = merge_edited(local.get("edited"), remote.get("edited"))
    if ed is None:
        m.pop("edited", None)
    else:
        m["edited"] = ed
    rx = merge_reactions(local.get("reactions"), remote.get("reactions"))
    if rx:
        m["reactions"] = rx
    else:
        m.pop("reactions", None)
    return m


def absent(v):
    return v is None or v == "" or v is False


def merge_lww_field(lv, lat, rv, rat):
    """Contact field: the later timestamp wins (tie: local). If EITHER side lacks a timestamp
    (legacy data) it is gap-fill only: the remote value is taken only when the local side has
    neither a value nor a timestamp. __BLOCK_SYNC_LWW_2026_09_24__ that includes a timestamped
    "absent" value (an unblock = blocked:false + blockedAt): adopting its timestamp is what stops an
    OLDER block from a third device re-blocking later through the gap-fill."""
    if lat is not None and rat is not None:
        return (rv, rat) if rat > lat else (lv, lat)
    if lat is None and absent(lv) and (not absent(rv) or rat is not None):
        return rv, rat
    return lv, lat


def merge_contact(local, remote):
    out = {"publicKey": local["publicKey"]}
    for f in ("alias", "blocked", "verified"):
        at = f + "At"
        v, t = merge_lww_field(local.get(f), local.get(at), remote.get(f), remote.get(at))
        if v is not None:
            out[f] = v
        if t is not None:
            out[at] = t
    return out


def merge_profile(local, remote):
    if local is None:
        return remote
    if remote is None:
        return local
    return remote if remote["updatedAt"] > local["updatedAt"] else local


def merge_read_state(local, remote):
    return {"conv": local["conv"], "readUpTo": max(local["readUpTo"], remote["readUpTo"]),
            "at": max(local["at"], remote["at"])}


def _lww(a_val, a_at, b_val, b_at):
    """Later timestamp wins; a value with a timestamp beats one without; exact ties are broken by the
    UTF-8 bytes of the canonical JSON of the value, so the merge is commutative."""
    ka = (a_at is not None, a_at or "", cjson(a_val).encode("utf-8"))
    kb = (b_at is not None, b_at or "", cjson(b_val).encode("utf-8"))
    return (a_val, a_at) if ka >= kb else (b_val, b_at)


GROUP_DESCRIPTIVE = ("description", "type", "creator", "avatarEmoji", "pinnedMessageId", "pinnedBy")


def merge_group(local, remote):
    """Design §8.4 (D11). Members/admins: higher epoch wins as a whole; at equal epoch the union.
    name / avatar: later nameAt / avatarAt (descriptive extras travel with the name). keys: union by
    (kind, version), never dropped (same (kind, version) twice: the smaller key string is kept).
    joinedAt: max (the last deliberate join / rejoin known to the account). left: a leave counts only
    if it is not older than joinedAt (a timestamped leave at or after the last join; an untimestamped
    legacy leave only while no join is known); left = any leave that counts, leftAt = the LATEST such
    leave. Every part is a max, so the merge is also associative (see _left_state).
    muted: later mutedAt (optional fields, __GROUP_MUTE_SYNC_2026_09_24__). updatedAt: max."""
    if local is None:
        return canonical_group(remote)
    a, b = canonical_group(local), canonical_group(remote)
    out = {"groupId": a["groupId"]}
    ea, eb = a.get("epoch", 0), b.get("epoch", 0)
    if ea != eb:
        w = a if ea > eb else b
        out["members"], out["admins"], out["epoch"] = w["members"], w["admins"], w.get("epoch", 0)
        if w.get("epochAt") is not None:
            out["epochAt"] = w["epochAt"]
    else:
        out["members"] = sorted(set(a["members"]) | set(b["members"]), key=utf8_key)
        out["admins"] = sorted(set(a["admins"]) | set(b["admins"]), key=utf8_key)
        out["epoch"] = ea
        ats = [x for x in (a.get("epochAt"), b.get("epochAt")) if x is not None]
        if ats:
            out["epochAt"] = max(ats)
    na = {"name": a.get("name", "")}
    nb = {"name": b.get("name", "")}
    for f in GROUP_DESCRIPTIVE:
        if a.get(f) is not None:
            na[f] = a[f]
        if b.get(f) is not None:
            nb[f] = b[f]
    named, name_at = _lww(na, a.get("nameAt"), nb, b.get("nameAt"))
    out.update(named)
    if name_at is not None:
        out["nameAt"] = name_at
    av, av_at = _lww(a.get("avatar"), a.get("avatarAt"), b.get("avatar"), b.get("avatarAt"))
    out["avatar"] = av
    if av_at is not None:
        out["avatarAt"] = av_at
    keys = {}
    for k in a.get("keys", []) + b.get("keys", []):
        kv = (k["kind"], k["version"])
        if kv not in keys or k["key"] < keys[kv]["key"]:
            keys[kv] = k
    out["keys"] = [keys[kv] for kv in sorted(keys, key=lambda kv: (utf8_key(kv[0]), kv[1]))]
    joins = [x for x in (a.get("joinedAt"), b.get("joinedAt")) if x is not None]
    if joins:
        out["joinedAt"] = max(joins)
    out.update(_left_state((a, b), out.get("joinedAt")))
    # __GROUP_MUTE_SYNC_2026_09_24__ per-group mute, a personal setting of the account: later
    # mutedAt wins, a timestamped value beats an untimestamped one, exact ties by canonical-JSON
    # bytes (true > null > false). Both fields are optional: a peer that predates them sends
    # neither, and an absent value never beats a present one that carries a timestamp.
    mu, mu_at = _lww(a.get("muted"), a.get("mutedAt"), b.get("muted"), b.get("mutedAt"))
    if mu is not None:
        out["muted"] = mu
    if mu_at is not None:
        out["mutedAt"] = mu_at
    out["updatedAt"] = max(a["updatedAt"], b["updatedAt"])
    return out


def _left_state(groups, joined_at):
    """__DEVSYNC_REJOIN_2026_09_23__ (design §8.4 rejoin rule). A leave older than the last join is
    history: a rejoin on one device is no longer undone by another device still holding `left`.
    A timestamped leave counts when joined_at is None or leftAt >= joined_at (a join at the very
    same millisecond does not beat the leave); an untimestamped (legacy) leave counts only while
    no join is known. leftAt = the latest leave that counts. Returns {"left", ["leftAt"]}."""
    left = False
    lats = []
    for g in groups:
        if not g.get("left"):
            continue
        la = g.get("leftAt")
        if la is None:
            left = left or joined_at is None
        elif joined_at is None or la >= joined_at:
            left = True
            lats.append(la)
    out = {"left": left}
    if lats:
        out["leftAt"] = max(lats)
    return out


def canonical_group(g):
    g = dict(g)
    g["members"] = sorted(set(g.get("members", [])), key=utf8_key)
    g["admins"] = sorted(set(g.get("admins", [])), key=utf8_key)
    g.setdefault("epoch", 0)
    g.setdefault("left", False)
    # A leave older than the group's own joinedAt is history (normalised, see _left_state).
    left = _left_state((g,), g.get("joinedAt"))
    g.pop("leftAt", None)
    g.update(left)
    g.setdefault("avatar", None)
    g["keys"] = sorted(g.get("keys", []), key=lambda k: (utf8_key(k["kind"]), k["version"], k["key"]))
    return g


# __BLOCK_MUTE_RECORDS_2026_09_24__ record parity: what every implementation must keep when it PARSES a
# CONTACTS / GROUPS entry and writes it back (unknown fields dropped, keys canonical, blocked:false and
# muted:false kept with their timestamps: an unblock / unmute is a fact, not an absence).
CONTACT_FIELDS = ("alias", "aliasAt", "blocked", "blockedAt", "verified", "verifiedAt")
GROUP_FIELDS = ("groupId", "name", "nameAt", "avatar", "avatarAt", "members", "admins", "epoch", "epochAt", "left",
                "leftAt", "joinedAt", "keys", "updatedAt", "muted", "mutedAt") + GROUP_DESCRIPTIVE


def canonical_key(k):
    k = k.strip().replace("-", "+").replace("_", "/").rstrip("=")
    return k + "=" * (-len(k) % 4)


def contact_record(o):
    out = {"publicKey": canonical_key(o["publicKey"])}
    for f in CONTACT_FIELDS:
        if o.get(f) is not None:
            out[f] = o[f]
    return out


def group_record(o):
    return canonical_group({f: o[f] for f in GROUP_FIELDS if f in o and o[f] is not None or f == "avatar" and f in o})


def merge_devices(local, remote_record, self_id):
    """local: {linked:{id:entry}, revoked:set}. remote_record: DEVICES body.
    Revocation is sticky; a revoked id never comes back through DEVICES; self is never added."""
    linked = dict(local["linked"])
    revoked = set(local["revoked"]) | set(remote_record.get("revoked", []))
    for e in remote_record.get("linked", []):
        d = e["deviceId"]
        if d == self_id or d in revoked or d in linked:
            continue
        # the dk must hash to the id, or the entry is refused
        if device_id(bytes.fromhex(e["dk"])).hex() != d:
            continue
        linked[d] = e
    for d in revoked:
        linked.pop(d, None)
    return {"linked": linked, "revoked": revoked}
