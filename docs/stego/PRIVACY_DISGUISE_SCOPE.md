# Privacy disguise — what is covered, what is not (2026-11-18)

Two independent features sit under Settings → "Privacy disguise":

1. **Disguise OSHI traffic** (the V2 cover transport, `CoverInterceptor` /
   `CoverTransport.swift`, relay `ServerVPS/v2/cover_transport.js`).
2. **Disguise as photo** (STEGO-v1, `docs/stego/STEGO-v1.md`).

## 1. Cover transport — flows

| Flow | Android | iOS | Notes |
|---|---|---|---|
| 1:1 text, V2 peer | disguised | disguised | signed V2 request inside a padded analytics-style JSON body |
| 1:1 attachments, V2 blob / inline | disguised | disguised | same |
| V2 pull (receive) | disguised, jittered ±50 % | disguised; 1 s timer gated to one pull per 2.5–7.5 s (`CoverPullGate`) | audit finding 3 |
| V2 keys / config / account / device | disguised | disguised | every `/v2/*` call goes through the interceptor |
| 1:1 message when the V2 send FAILS (cover ON) | **waits** ("Waiting for disguised connection"), retried over V2 only, "Not delivered" after 1 h | **fails closed** (`fallbackToIPFS` throws before IPFS) | audit finding 1 — was a silent plain `/api/queue` + IPFS upload on Android |
| Same, cover ON, a live Bluetooth/LAN mesh peer or LoRa radio | sent over the local radio | sent over the local radio | not internet traffic |
| Group messages | **NOT disguised** (legacy `/api/queue` + IPFS) | **NOT disguised** | groups use the legacy relay |
| 1:1 control traffic: read receipts, typing, reactions, edits, profile updates, key rotation, multi-device sync | disguised when it goes over V2; when V2 cannot carry it, **refused** at the legacy relay's door (`VPSClient.sendMessage` / `notifyVPSQueue`) — not sent at all, not retried | iOS: same rule at the legacy doors `queueIPFSHash`, `notifyVPS`, `DeliveryReceiptManager.notifyVPS` and the ratchet-reset sender (`CoverTransport.legacyOneToOneRelayAllowed`). Limit: profile/wallpaper updates still upload their (encrypted) blob to `/api/pinata` before the queue call is refused | found on the emulator 2026-11-18: with cover ON, typing in a 1:1 chat sent three typing pings through plain `/api/queue` + IPFS upload |
| Push wake-ups (`/push`, APNs/FCM) | **NOT disguised** | **NOT disguised** | reveal "someone sent you something" timing |
| Calls (signalling, TURN, media) | **NOT disguised** | **NOT disguised** | |
| Legacy poll (`/api/pending`), pull-to-refresh | **NOT disguised** | **NOT disguised** | kept so messages from pre-V2 clients still arrive |
| Hostname / SNI / TLS fingerprint / IP of `oshi-messenger.com` | visible | visible | the dominant identifier; cover does not hide it |

The Settings caption says exactly this in short: 1:1 messages and attachments
are disguised and wait rather than go out undisguised; the server name, TLS
fingerprint, groups, calls, push wake-ups and checks for new mail are not; it is
not anonymity and does not get around a block of the OSHI server.

Defence in depth against the compressed-size leak (audit finding 5): both
clients send `Accept-Encoding: identity` on cover requests
(`CoverFailClosedTest.coverRequestsAskForIdentityEncoding`,
`StegoV1Tests.testCoverRequestsAskForIdentityEncoding`); the server patch
(`ServerVPS/v2/DEPLOY_COVER_SERVER.md`, branch `feat/stego-obfuscation-server`)
turns gzip off for the cover aliases and wires the strict decoder. **Not deployed.**

## 2. Disguise as photo — what an adversary can still see

* That an image was sent (and its size) on whatever channel carried it.
* With forensic steganalysis: that the image's LSB plane is ±1 noise everywhere
  (it may flag the image as "probably carries data"). It cannot read it, cannot
  tell its length, its sender or its recipient — the bit positions are keyed by
  a secret shared only by the two contacts.
* If the recipient's identity key is later compromised: every image made for
  them (no forward secrecy beyond the per-image ephemeral key).
* Recompression (photo mode of messengers, social networks, screenshots) erases
  the message; the recipient gets "no hidden message for you", never garbage.

## Removed (they promised things nothing did)

* Android "Image Steganography" switch (it made every non-V2 send, including
  Bluetooth/LoRa mesh, throw) and the strings "Hide messages in plain text",
  "All methods use AES-256-GCM. Fully compatible with iOS." and the unused
  `steg_*` / channel-picker strings, in all 40 locales.
* iOS `SteganographySettingsView` ("Enable Steganography", wired to nothing),
  the dead 656-line `SteganographyManager`, and every `steg.*` string ("hide
  messages inside images, bypassing network content inspection and
  censorship", "A 1024x1024 image can carry approximately 375 KB…") in all
  34 lproj.
* Android `sendViaCovertChannel` (always false, followed by a silent plain-relay
  "fallback").

## 3. OSHI Desktop (branch `feat/stego-obfuscation-desktop`, 2026-11-18)

The desktop compiles Android's `StegoV1.kt`, `StegoPng.kt`, `CoverCodec.kt` and
`CoverPresentation.kt` unchanged (build.gradle.kts include list), and runs Android's
`StegoV1Test` / `StegoV1VectorTest` in its own suite. `docs/fixtures/stego/desktop_prod_v1.png`
is made by the desktop path and revealed by the Swift harness and the Android codec.

| Flow | Desktop |
|---|---|
| Every `/v2/*` call through `V2Http` (1:1 text, attachments/blobs, keys, account, devices, sync, report) | disguised; on failure the call fails (code -1), never direct |
| `/v2/config` (`V2ConfigGate`, devsync flag read) | disguised |
| Receive poll | disguised, delay drawn per tick from 2.5–7.5 s |
| 1:1 message when the disguised send fails | **waits** (`CoverOutbox`, Android's backoff), "Not delivered" after 1 h; switching the disguise off while it waits marks it not delivered rather than sending it plain. The desktop has no legacy relay for 1:1 at all |
| Linked-device live relay (WebSocket `/v2/devsync`) | **not opened** while the disguise is on (a socket cannot ride the carrier); LAN sync still works |
| Group messages (V2 fan-out through the same `V2Http`; the desktop has no legacy group path) | disguised |
| Calls, push wake-ups (`/push/send-push`), OSHI Mail (`/mail/v1`), bots (`/api/bot`) | **NOT disguised** |
| Server name / TLS fingerprint / IP | visible (the Settings caption is the desktop's own `desktop.disguise.cover.not_hidden`, because the phones' sentence says groups are not disguised, which is false here) |
| Privacy relay (OHTTP) on | OHTTP takes precedence for eligible calls, as on Android |
