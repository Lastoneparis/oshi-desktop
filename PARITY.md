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
| 0.5 | **Key storage that survives a restart** | `KeychainHelper.swift`, Android Keystore | ✅ macOS / 🟡 Win+Linux | `KeyVault` (AES-256-GCM) under one OS-held master key. Round-tripped on the REAL macOS Keychain; the DPAPI and libsecret backends are written and STILL UNRUN. CI to run them exists but has never been executed — `OSHI_EXPECT_SECRET_STORE` now turns "no key store on this machine" from a skip into a failure, so a runner cannot report this row green having measured nothing. **Move this to ✅ only on a green Actions run, never on the workflow existing**. A wrong or missing master key FAILS — it never reads as an empty vault |
| 0.6 | Prekey store (SPK + OPK privates) | `V2PrekeyStore.kt` | 🟡 | in the vault, not a plain file. Peek-then-burn; pool capped, freshly minted ids never evicted |
| 0.7 | Session store (ratchet state, persisted) | `V2SessionStore.{swift,kt}` | 🟡 | same JSON shape as Android. A reloaded session decrypts what the live one encrypted (tested). An unreadable record raises rather than reading as "no session" |
| 0.8 | `/v2/config` rollout gate | `V2ConfigGate` | ✅ | fails closed on every error path (tested); bucket vectors computed independently and checked against a little-endian read. **Exercised against the LIVE server 2026-08-25**: the desktop client's identity lands in bucket 43 and the gate opened inside the 100% rollout. It also says WHY it closed — server-disabled, below min_build, outside the bucket and never-reached-the-server are four different answers |
| 0.9 | `/v2/keys` publish + fetch + count | `V2KeysClient.kt` | 🟡 | SPK signature and TOFU both enforced and both watched failing under mutation |
| 0.10 | `/v2/messages` send + pull + ack | `V2MessagesClient.kt` | 🟡 | a failed pull is null, never an empty one; one bad envelope costs one message, not the response |
| 0.11 | `/v2/account` | `V2AccountClient.kt` | 🟡 | 207 is a receipt, not a failure |
| 0.12 | Message router (send/receive orchestration) | `V2MessageRouter.{swift,kt}` | 🟡 | text path only (no media yet). Driven end to end between two independent clients through a behaving relay: X3DH, ratchet, retry budget, ack discipline, responder TOFU, one bundle fetch per conversation |
| 0.13 | Local message store | Room / CoreData | 🟡 | append-only NDJSON per conversation, O(1) per message. Dedup by msgId ACROSS transports, ordering by the message's own timestamp, a torn last line drops one message and keeps the history. Plaintext on disk — stated and justified in the file, not an oversight |
| 0.14 | Contacts | `ContactPresenceManager`, Android contact tables | 🟡 | blocking is a FLAG, never a delete; `visible()` hides blocked contacts, `all()` does not |
| 0.15 | Blob upload/download (media) | `V2BlobClient.kt` | 🟡 | reserve → chunk → status → commit, resume into an existing blob, streaming decrypt straight to a file. Round-tripped through a real in-process blob server, including a non-UTF-8 chunk (the reason the download route needs bytes, not a String) |
| 0.16 | Mesh payload ↔ ratchet | `MeshNetworkManager` ↔ `MessageManager` | ⬜ **blocked, see below** | the mesh does NOT carry V2 — it carries the LEGACY ratchet, and its media is not encrypted at all |
| 0.17 | Groups | `GroupManager`, `V2GroupSession.swift`, `GroupMessaging.swift` | 🟡 partial — three sub-rows blocked, see below | **There is no group key and no sender key.** A group message is a FAN-OUT of pairwise V2 ratchet sessions — N members, N ciphertexts, `type:"group"`+`groupId` per envelope, relay knows nothing about membership. iOS's `SenderKey` struct is declared, persisted, migrated and **never used to encrypt a byte**; the legacy `deriveGroupKey` is `SHA256(uppercase(groupId) ‖ a constant)` — every input public. `📢GROUP_UPDATE📢` codec, the update authorizer and the message payload are byte-tested against the shipped emitters. The definition is the ONE payload encoded `.iso8601`, twenty lines from a `GroupMessage` encoded Apple-epoch; a number in `groupPictureUpdatedAt` throws past a bare `decodeIfPresent` and drops the whole roster, so every date key is guarded on emit and the decoder reproduces iOS's rejection rather than being quietly more tolerant |
| 0.18 | Delivery receipts, typing, reactions, edit/delete | `DeliveryReceiptManager`, … | 🟡 | all four epochs identified and converted in ONE place (`WireClock`): Apple-reference seconds inside the payload, Unix MILLIS on the envelope carrying it, Unix seconds on the legacy wrapper, ISO-8601 once persisted. The first two sit one nesting level apart in the same transmission, and three shipped Android emitters got it backwards. No epoch is inferred from a field's name — every one of them is called `timestamp` |
| 0.19 | Location sharing, check-ins, contact cards | `LocationSharingManager`, `CheckInManager`, `MediaManager.createContactCard` | 🟡 | rides row 0.18's dispatch — no second catalog, no second router, no second epoch converter. Receive-only by construction: the JDK has no GPS and none is faked. **Two shipped holes refused rather than copied** — (1) `isLive:true` with no `expiresAt` never expires on EITHER platform and no UI can dismiss it; (2) Android's `liveUpdate` recomputes `expiresAt = now + duration` every ~10 s (iOS's un-taken 2026-06-04 fix), so a share stays live up to 8 h past its intended end. Earliest expiry per `sessionId` wins, inward only. Contact cards are media bytes with `mediaType: contact`, NOT a sentinel — so they get a codec and no router entry |
| 0.20 | Safety numbers | `SafetyNumber.swift` | 🟡 | three mutually incompatible implementations shipped once already, so this one is checked against the bytes rather than against any of the three |
| 0.21 | Blocked contacts | `BlockedContactsManager` | 🟡 | ENFORCEMENT, not a second flag. Dropped before the payload is read on the mesh; after decryption on V2, because the ratchet must advance for a blocked sender or every message after an UNBLOCK is undecryptable. Blocked peers are withheld from the conversation list, never deleted. **Four iOS defects found while reading this — see below** |
| 0.22 | QR pairing / identity exchange | `QRCodeDisplayView`, `QRScannerView` | 🟡 | the payload is the bare identity key in STANDARD base64 — no JSON, no version, no scheme. Emit standard, accept either: every base64url site in the shipped trees is a URL path segment, never a QR. Scanned link hosts ARE checked here, unlike iOS |
| 0.23 | Legacy IPFS path | `IPFSEphemeralManager`, `PinataConfig` | ⬜ | needed only for peers that never got V2 |
| 0.24 | Multi-device sync | `V2SyncManager.swift`, `V2Client+Sync.swift`, `MultiDeviceSyncManager.{swift,kt}` | 🟡 | **the row names two DIFFERENT protocols.** iOS's signed, owner-only `/v2/sync` archive has NO CALLER in the shipped app (`AUDIT_V2_2026-07.md:37`) and does not exist on Android. What ships on both phones is the legacy `/api/sync/{kind}/{key}` blob archive: whole-blob overwrite, **zero authentication** — the path segment is the user's PUBLIC key, so anyone holding it can overwrite that identity's slot. Both implemented; the two path encodings are different functions and mixing them syncs to two slots with no error. A page that does not advance the cursor ends the drain — without that a rolled-back or hostile server spins the client forever, a hole iOS still has |
| 0.25 | Scheduled messages | `ScheduledMessageManager.{swift,kt}` | 🟡 | **Android has them too** — the row's "iOS-only" premise was wrong. Purely local on both: no endpoint, no sync kind, so a schedule does not reach the user's own second device. Same filename, mutually unreadable, and the epochs differ. The desktop cannot send while stopped and says so: delivery is the first poll after the due time, reported as `lateByMs` rather than hidden |
| 0.26 | Bots | `BotManager.swift` | ⬜ | server-side API already exists |

### Row 0.17 — three blocked sub-rows, and two more shipped defects

- **0.17-a — groups over the MESH: blocked, and blocked ON THE PHONES.** PLAN_MESH.md §7 understated
  it. iOS neither RECEIVES group updates over the cross-platform mesh (`CrossPlatformMesh.swift:685-687`
  routes seven types and `GROUP_UPDATE` is not one) nor SENDS them (`BroadcastGroupUpdate`'s only
  observer is `MeshNetworkManager`, the iOS↔iOS mesh). Implementing this on desktop would work against
  Android and be invisible to every iPhone — a third dialect, not parity. Group *messages* are blocked
  twice over: iOS removed that branch behind a default-OFF kill switch because it broadcast group bodies
  **in cleartext to every nearby device, member or not**.
- **0.17-b — legacy IPFS group envelope: blocked behind row 0.23.**
- **0.17-c — group MEDIA: blocked behind 0.15 + 0.16.** The v2 group file-key shape is not extracted yet.

Two more defects in the shipped clients, found while reading this row:

1. **`member_sync_request` makes iOS auto-add the requester to the group** — its own comment reasons
   "they have the group, so they're legitimate" (`swift:1113`). An unauthenticated membership write.
2. **iOS emits a `{"type":"created"}` payload it cannot itself parse.** `"created"` is not a case in its
   own switch, so an iPhone logs "Unknown group update type" and drops it. It exists only for Android.

### Row 0.24 — Android's sync does not just fail to propagate a block, it DESTROYS one

Verified line by line in the shipped tree, and this supersedes the milder note under row 0.21:

- the payload is built from `getAllContactsList()` = `SELECT * FROM contacts WHERE isBlocked = 0`
  (`AppDatabase.kt:604`), so the `isBlocked` it emits (`MultiDeviceSyncManager.kt:563`) is structurally `false`;
- the receiver reads `optBoolean("isBlocked", false)` (`:869`) and writes it over the local row whenever the
  incoming stamp wins (`:888-893`);
- an unstamped contact's stamp defaults to `System.currentTimeMillis()` (`:556`), which beats almost any
  local stamp.

**So a contact blocked on device B, merely not-blocked on device A, gets UNBLOCKED on B.** It is unreachable
in the shipped binary only because `linkDevice` never transmits — it builds the JSON, logs it and calls
`callback(true, …)` under a `// Simulate success for now` (`:336-360`), so no device can ever be linked.
That is a stay of execution, not a fix: one wired-up send away.

Also: the legacy `/api/sync` routes are **entirely unauthenticated on both platforms**.

### Row 0.21 — four defects in the SHIPPED iOS client, found while reading it

Read across both trees to decide what desktop blocking should do. These are iOS bugs, not
desktop ones, and they are recorded here because this is where the reading happened. Android
gets all four right.

1. **A blocked contact still receives delivery receipts.** `receiveMessage` drops the message,
   and the very next line sends `📬DELIVERY_RECEIPT📬` back over a send path that has no block
   gate (`MessageManager+V2.swift:1017`; the legacy path fires it even earlier, `:3602/3729/3766`).
   The blocked person sees double ticks — so blocking is *observable to the person you blocked*.
2. **Group messages from a blocked member are delivered.** `v2DeliverGroup` and
   `handleIncomingGroupMessage` never consult `BlockedContactsManager`; only the push banner is
   suppressed.
3. **A blocked contact's push still banners when the app is killed.** The Notification Service
   Extension has no block awareness and structurally cannot get it: the block list is a JSON file
   in `Documents/`, outside the App Group the extension can read.
4. **`unblockContact` compares raw strings while `isBlocked` compares normalised ones**
   (`BlockedContactsManager.swift:131` vs `:90-103`). Block under one base64 spelling, unblock with
   the other, and the contact stays blocked forever. Latent only because the current UI happens to
   pass the same string.

Android's own gap is narrower and worth fixing alongside: `syncContactsToLinkedDevices` builds its
payload from a query that already excludes blocked rows, so the `isBlocked` field it sends can only
ever be `false`. **Blocking never propagates to a linked device; only unblocking does.**

Neither platform has a server-side block API, so the relay keeps queueing a blocked sender's
envelopes, which the client pulls, decrypts, drops and acks.

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
| 1.6 | Packaging: `.msi`/`.deb`/`.rpm`, signing, updates | 🟡 | jpackage out of the JDK (zero new dependencies); it does not cross-build, so each installer needs its own runner. **Nothing is signed and no installer has been built on Windows or Linux.** CI workflow written and NEVER EXECUTED — see `.github/workflows/desktop-ci.yml`, which says so in its own header |

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
