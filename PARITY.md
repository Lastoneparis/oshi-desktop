# Parity ledger — OSHI desktop vs OSHI macOS

**Target:** the macOS app, which is the iOS app built with Mac Catalyst — same `Info.plist`, same linked frameworks, only the entitlements differ. So "parity with macOS" means **parity with the iOS feature set**: 195 Swift files in `OSHI/`, mirrored by ~250 Kotlin files in `OSHI-Android/`.

**How to read this.** Every row is one capability, not one file. Status is what has been *verified*, never what has been written:

| | meaning |
|---|---|
| ✅ | implemented, tested, and checked against a shipped client's bytes or a live server |
| 🟡 | implemented and unit-tested; not yet exercised against a real phone or the live server |
| ⬜ | not started |
| ⛔ | deliberately not applicable to a desktop client — with the reason, because "not applicable" is a claim that has to be defended |

Rows are ordered by what unblocks the most. **Nothing above a row can be skipped to reach it**: no message store without an identity that survives a restart, no group without a session, no UI without any of it.

---

## Tier 0 — the client, headless

The whole messenger minus the pixels. This is where parity is actually won or lost: a desktop app that draws every screen but disagrees with the phone about one byte is worth less than a CLI that agrees about all of them.

| # | Capability | iOS / Android source | Status | Note |
|---|---|---|---|---|
| 0.1 | X3DH + Double Ratchet + file AEAD | `OSHICryptoV2.kt`, `OSHIRatchetV2.kt`, `V2Session.kt` | ✅ | SHARED SOURCE from the Android tree — not a port. iOS's own vectors reproduced. |
| 0.2 | v=4 relay envelope | `V2ClientModels.swift`, `V2MessagesClient.kt` | ✅ | `DesktopWire.kt`, deterministic emit |
| 0.3 | Request signing | `V2Signer.kt`, `V2Client.swift:753` | ✅ | `DesktopV2Signer.kt` |
| 0.4 | Mesh: mDNS discovery + TCP transport | `CrossPlatformMesh.{swift,kt}` | ✅ | verified live against a shipped Android client, both directions |
| 0.5 | **Key storage that survives a restart** | `KeychainHelper.swift`, Android Keystore | ✅ macOS / 🟡 Win+Linux | `KeyVault` (AES-256-GCM) under one OS-held master key. Round-tripped on the REAL macOS Keychain; the DPAPI and libsecret backends are written and unrun. A wrong or missing master key FAILS — it never reads as an empty vault |
| 0.6 | Prekey store (SPK + OPK privates) | `V2PrekeyStore.kt` | 🟡 | in the vault, not a plain file. Peek-then-burn; pool capped, freshly minted ids never evicted |
| 0.7 | Session store (ratchet state, persisted) | `V2SessionStore.{swift,kt}` | 🟡 | same JSON shape as Android. A reloaded session decrypts what the live one encrypted (tested). An unreadable record raises rather than reading as "no session" |
| 0.8 | `/v2/config` rollout gate | `V2ConfigGate` | 🟡 | fails closed on every error path (tested); bucket vectors computed independently and checked against a little-endian read |
| 0.9 | `/v2/keys` publish + fetch + count | `V2KeysClient.kt` | 🟡 | SPK signature and TOFU both enforced and both watched failing under mutation |
| 0.10 | `/v2/messages` send + pull + ack | `V2MessagesClient.kt` | 🟡 | a failed pull is null, never an empty one; one bad envelope costs one message, not the response |
| 0.11 | `/v2/account` | `V2AccountClient.kt` | 🟡 | 207 is a receipt, not a failure |
| 0.12 | Message router (send/receive orchestration) | `V2MessageRouter.{swift,kt}` | 🟡 | text path only (no media yet). Driven end to end between two independent clients through a behaving relay: X3DH, ratchet, retry budget, ack discipline, responder TOFU, one bundle fetch per conversation |
| 0.13 | Local message store | Room / CoreData | ⬜ | |
| 0.14 | Contacts | `ContactPresenceManager`, Android contact tables | ⬜ | |
| 0.15 | Blob upload/download (media) | `V2BlobClient.kt` | ⬜ | streaming AEAD, manifest key order is load-bearing |
| 0.16 | Mesh payload ↔ ratchet | `MeshNetworkManager` ↔ `MessageManager` | ⬜ **blocked, see below** | the mesh does NOT carry V2 — it carries the LEGACY ratchet, and its media is not encrypted at all |
| 0.17 | Groups | `GroupManager`, `V2GroupSession.swift` | ⬜ | iOS drops Android's `GROUP_UPDATE` over mesh today (see PLAN_MESH.md §7) |
| 0.18 | Delivery receipts, typing, reactions, edit/delete | `DeliveryReceiptManager`, … | ⬜ | four different date epochs live in these payloads |
| 0.19 | Location sharing, check-ins, contact cards | `LocationSharingManager`, `CheckInManager` | ⬜ | |
| 0.20 | Safety numbers | `SafetyNumber.swift` | ⬜ | three mutually incompatible implementations shipped once already |
| 0.21 | Blocked contacts | `BlockedContactsManager` | ⬜ | |
| 0.22 | QR pairing / identity exchange | `QRCodeDisplayView`, `QRScannerView` | ⬜ | base64url lives here legitimately |
| 0.23 | Legacy IPFS path | `IPFSEphemeralManager`, `PinataConfig` | ⬜ | needed only for peers that never got V2 |
| 0.24 | Multi-device sync | `V2SyncManager.swift`, `V2Client+Sync.swift` | ⬜ | |
| 0.25 | Scheduled messages | `ScheduledMessageManager.swift` | ⬜ | |
| 0.26 | Bots | `BotManager.swift` | ⬜ | server-side API already exists |

### Row 0.16 is not what it looks like — read this before implementing it

The obvious reading of "wire the mesh to the ratchet" is: put a V2 envelope inside the mesh payload. That would produce something no phone can read. What the shipped clients actually put in `CrossPlatformMessage.payload` was checked line by line:

- **Mesh TEXT carries the LEGACY Double Ratchet**, not V2. Android builds it with `encryptWithDoubleRatchet(...)` and sends the resulting `EncryptedEnvelope` JSON straight into `crossPlatformMesh.sendMessage` (`MessageRepository.kt:2079-2081`). V2 lives on the relay path only.
- **Mesh MEDIA is not end-to-end encrypted on either platform.** Android's own comment says it (`MessageRepository.kt:2415-2426`): the payload is a plaintext JSON envelope with base64 bytes, "both sides currently rely on the underlying TCP/BLE transport for confidentiality on the LAN segment", with the encrypted relay path running in parallel as the upgrade. That is a real, shipped security property and this document is not the place to soften it.
- **The legacy crypto is Android-coupled and cannot be shared source.** `DoubleRatchet.kt` (855 lines) and `CryptoManager.kt` (681) import `android.util.Base64`, `android.util.Log`, `android.content.Context` and `EncryptedSharedPreferences`, with 63 call sites in the ratchet alone. So making a desktop talk to a PHONE over the mesh means porting a SECOND ratchet — exactly the "third independent implementation" PLAN.md §1 argues against, and this time with no vectors published to check it against.

So the three honest options, none of which should be picked quietly:

1. **Port the legacy stack** (~1500 lines, a second ratchet, no published vectors). Buys phone↔desktop messaging with no internet.
2. **Teach the phones to carry V2 over the mesh.** One protocol instead of two, and the desktop already has V2. Requires a change on iOS and Android, so it is a product decision rather than a desktop one.
3. **Ship the desktop mesh as transport-and-discovery only** (what it is today) and route real messages over the relay, which needs internet. Correct, and it means "OSHI works with no internet" is not true of the desktop client — which must then not be claimed in any UI copy.

Until one is chosen, the desktop mesh moves OPAQUE payloads and the CLI says so where someone would be tempted to type a real message into it.

## Tier 1 — the app

| # | Capability | Status | Note |
|---|---|---|---|
| 1.1 | UI framework decision + shell | ⬜ | Compose Multiplatform is the natural fit; deferred until Tier 0 proves out (PLAN.md §1). **[VIEWS.md](VIEWS.md) inventories all 102 screens** of the macOS app, with a portability verdict and a reason for each |
| 1.2 | Chats list, chat view, compose | ⬜ | |
| 1.3 | Contacts, peers, settings, onboarding | ⬜ | |
| 1.4 | Media viewers, wallpapers, link previews | ⬜ | |
| 1.5 | Localisation | ⬜ | 34 locales exist; `InfoPlist.xcstrings` has no desktop equivalent |
| 1.6 | Packaging: `.msi`/`.deb`/`.rpm`, signing, updates | ⬜ | three pipelines, none exercised |

## Tier 2 — hard, or not ours

| # | Capability | Status | Note |
|---|---|---|---|
| 2.1 | Voice/video calls | ⬜ | WebRTC + a codec pipeline. The single biggest item in the whole ledger. |
| 2.2 | Mesh direct calls | ⬜ | depends on 2.1 |
| 2.3 | Push notifications | ⛔→🟡 | APNs/FCM have no desktop equivalent here; a desktop client POLLS `/v2/messages`. Not the same thing, and it must be said out loud: no wake-from-sleep delivery. |
| 2.4 | Steganography | ⬜ | |
| 2.5 | NLP / AI features | ⬜ | |
| 2.6 | Screenshot blocking | ⛔ | no OS affordance on Windows/Linux; claiming it would be a lie in the UI |
| 2.7 | Apple Watch connectivity | ⛔ | no equivalent |
| 2.8 | App Store review prompts | ⛔ | not distributed through an app store |
| 2.9 | China region detection | ⬜ | drives which relay is used; matters if the app ships there |

---

## Working rules for this loop

1. **One capability per commit**, with its tests, and the ledger row updated in the same commit.
2. **Match the shipped bytes, not the shipped code.** Where iOS and Android disagree, the disagreement goes in the file's doc comment and the stricter option wins.
3. **A guard that has not been watched failing is not a guard.** Every new test gets a mutation run.
4. **No new dependency without a reason that survives being written down.** Everything so far is `java.net` + the JDK + the two libraries Android pins.
5. **Read-only on the shipped trees.** `OSHI/` and `OSHI-Android/` are inputs.
