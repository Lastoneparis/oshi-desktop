# OSHI STEGO-v1 — "Disguise as photo"

Status: implemented on Android (`com.oshi.messenger.service.stego`) and iOS
(`OSHI/Stego/`). Test vectors: `docs/fixtures/stego/`. Both platforms run the
same vectors in their unit tests (`StegoV1VectorTest.kt`, `StegoV1Tests.swift`).

## 1. What it is for — and what it is not

A user can turn a short message (text, optionally one small file) into an
ordinary-looking PNG photo, end-to-end encrypted for ONE contact, and send that
PNG through any channel that keeps files intact (e-mail attachment, "send as
file/document" in another messenger, cloud drive, USB stick). The recipient
opens the PNG in OSHI and, if it was made for them, reads the message. To anyone
else it is a photo.

The point is censorship resistance: OSHI's own servers can be blocked, but a
photo sent over a channel that is not blocked still gets through.

Honest limits (shown to the user in the app):

* **Recompression destroys it.** Any channel that re-encodes the image (most
  social networks, WhatsApp/Signal/Telegram "photo" mode, screenshots, JPEG
  conversion, resizing) erases the hidden bits. The recipient then sees "no
  hidden message for you" — never a wrong message. Send as a *file/document*.
* **Hidden from casual inspection, not from forensics.** The LSB plane of the
  whole image is overwritten with ciphertext and random fill. A trained
  steganalyst (RS analysis, SPA, machine-learning detectors) can flag the image
  as "probably carries hidden data"; they cannot read it or tell its size, its
  recipient or its sender. Do not rely on the image not being *noticed*.
* **No forward secrecy beyond the ephemeral key.** The sender's long-term
  identity key participates (it authenticates the sender). If the RECIPIENT's
  identity key is later compromised, every image ever made for them can be
  decrypted. This is the same exposure as OSHI's legacy (non-ratchet) messages.
* **Only between contacts.** The recipient finds the payload by trying each of
  their contacts' keys; an image from someone who is not a contact cannot be
  located, let alone read.
* Capacity is ~3 bits per pixel minus 74 bytes of overhead; the app caps
  attachments at 256 KiB and the cover at 1600 px on the long side.

## 2. Primitives (all existing OSHI primitives)

| Name | Definition |
|---|---|
| `X25519(a, B)` | RFC 7748 raw shared secret, 32 bytes (CryptoKit `sharedSecretFromKeyAgreement` raw bytes = BouncyCastle `X25519Agreement`) |
| `HKDF(ikm, salt, info, L)` | RFC 5869 HKDF-SHA256 |
| `HMAC(k, m)` | HMAC-SHA256 |
| `SEAL(k, pt, aad)` | AES-256-GCM, 96-bit nonce = 12 zero bytes, 128-bit tag, output `ct ‖ tag` |

The identity keys are the X25519 keys that make up an OSHI address (raw 32-byte
public key, base64 in the app). A zero nonce is safe because every image uses a
fresh ephemeral key, so every `SEAL` key is used exactly once.

## 3. Keys

Sender identity `(s, S)`, recipient identity `(r, R)`, fresh ephemeral `(e, E)`.

```
ss_static = X25519(s, R)               # = X25519(r, S)
ss_eph    = X25519(e, R)               # = X25519(r, E)
K_order   = HKDF(ss_static, salt = "OSHI-STEGO-v1", info = "order",  32)
ikm       = ss_eph ‖ ss_static
K_header  = HKDF(ikm, salt = E ‖ S ‖ R, info = "OSHI-STEGO-v1 header", 32)
K_body    = HKDF(ikm, salt = E ‖ S ‖ R, info = "OSHI-STEGO-v1 body",   32)
```

`K_order` is known only to the two contacts, so nobody else can even locate the
embedded bits. (It is the same in both directions; that is harmless because the
header and body keys depend on `E`, `S` and `R`.) Sender authentication comes
from `ss_static` in `ikm`: only the holder of `s` (or `r`) can produce it.

## 4. Embedded byte stream

```
offset  size          field
0       32            E (ephemeral public key, raw)
32      26            HEADER = SEAL(K_header, H, aad = E)
                      H = "OSG1" ‖ u8 version=1 ‖ u8 flags=0 ‖ u32be bodyLen
58      bodyLen + 16  BODY = SEAL(K_body, P, aad = E ‖ HEADER)
```

`P` (bodyLen bytes, bodyLen ≤ 1 048 576):

```
u8    kind = 1
u64be createdAt (Unix ms)
u32be textLen,  textLen bytes UTF-8 text
u8    hasFile (0|1)
if hasFile: u16be nameLen, name UTF-8 · u16be mimeLen, mime UTF-8 · u32be fileLen, file bytes
```

Readers MUST reject trailing bytes, unknown `kind`, lengths that overrun, and
`flags != 0`. Total embedded length `T = 74 + bodyLen` bytes = `8T` bits.

## 5. Carrier: lossless PNG, LSB of R, G, B

* Pixels are 8-bit sRGB, row-major. Slot `i` for `0 ≤ i < N = 3·W·H` is
  channel `i mod 3` (0 = R, 1 = G, 2 = B) of pixel `⌊i / 3⌋`. Alpha is never used.
* Bits are written MSB-first within each byte, in stream order.
* **Slot order** — a keyed Fisher–Yates over `[0, N)`, of which only the first
  `8T` outputs are used:

  ```
  stream: block_k = HMAC(K_order, u64be(k)), k = 0, 1, 2, …
          words   = the blocks' bytes read as consecutive big-endian u32
  rand(m): limit = 2^32 − (2^32 mod m); draw w until w < limit; return w mod m
  a[j] = j (implicitly) for all j
  for t = 0, 1, …, 8T−1:  j = t + rand(N − t); swap(a[t], a[j]); slot_t = a[t]
  ```
  Implementations may keep `a` sparse or dense; the output is identical.
* **Embedding is LSB matching (±1), not LSB replacement.** For a slot whose
  value `v` has `v & 1 ≠ bit`: if `v == 0` use `+1`, if `v == 255` use `−1`,
  otherwise `+1` or `−1` chosen at random. (Readers only look at `v & 1`.)
* **Random fill.** Every slot NOT used by the stream gets a uniformly random
  bit, embedded the same way. The whole LSB plane is therefore ciphertext or
  noise and does not reveal `T`.
* Output: PNG, colour type 2 (RGB), bit depth 8, no interlace, no ancillary
  chunks (no gAMA/iCCP/sRGB/tEXt/eXIf: no metadata, no colour management).
* Readers decode PNG themselves (colour types 0, 2, 3, 4, 6 at bit depth 8,
  non-interlaced) to get exact sample values; anything else is decoded by the
  platform and will simply not contain a message.
* Encoders refuse covers with `8T > N`. Readers refuse `N > 3·4096·4096`.

Deterministic test mode (vectors only): the fill/direction randomness is
replaced by the byte stream `HMAC(HMAC_KEY_TEST, u64be(k))`, k = 0,1,…, with
`HMAC_KEY_TEST = "OSHI-STEGO-v1 test rng"` (UTF-8). Exactly one byte is consumed
per stream bit (in stream order; its bit 0 picks the direction: 1 → +1, 0 → −1),
then one byte per unused slot in ascending slot order (bit 7 is the fill bit,
bit 0 the direction). Production uses a CSPRNG in the same pattern.

## 6. Reveal

```
for each contact S:
    ss_static = X25519(r, S); K_order = …
    read the first 58 bytes via the slot order
    E = bytes[0..32); ss_eph = X25519(r, E)
    try OPEN(K_header, HEADER, aad = E) → H; check magic/version/flags/bodyLen
    on success: extend the slot order to 8·(74 + bodyLen) bits, OPEN BODY, parse P
return "no hidden message for you"
```

Any failure (bad PNG, not for us, tampered, truncated, recompressed) yields the
same "no hidden message for you" result. A header that opens but a body that
does not is reported as "damaged" (the image was made for you but altered).

## 7. Test vectors

`docs/fixtures/stego/vectors.json` — keys, intermediate values (`K_order`, the
first 32 slot indices, `K_header`, `K_body`, HEADER) and the SHA-256 of the RGB
samples after embedding a deterministic cover. `android_v1.png` is produced by
the Kotlin implementation and `ios_v1.png` by the Swift implementation; each
platform's tests reveal BOTH files and check the expected plaintext.
