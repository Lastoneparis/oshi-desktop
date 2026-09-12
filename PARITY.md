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
| 0.4 | Mesh: mDNS discovery + TCP transport | `CrossPlatformMesh.{swift,kt}` | ✅ | verified live against a shipped Android client, both directions. **A peer's recorded PORT was wrong whenever it dialled US, fixed 2026-08-26**: `handleIdentityExchange` stored `conn.socket.port`, which on an ACCEPTED socket is the dialling side's ephemeral SOURCE port — unreachable, and it overwrote the true listen port mDNS had already resolved. Whichever landed last won, so the defect was on all three platforms and only Windows CI went red on it. `socket.port` is now trusted ONLY on a socket we dialled; a peer that dialled in is `PORT_UNKNOWN` until mDNS says otherwise, and both CLIs say so instead of printing `:0` as an address |
| 0.5 | **Key storage that survives a restart** | `KeychainHelper.swift`, Android Keystore | ✅ macOS+Windows / 🟡 Linux | `KeyVault` (AES-256-GCM) under one OS-held master key. Round-tripped on the REAL macOS Keychain and, since the CI matrix started running, on REAL Windows DPAPI: on `windows-latest` with `OSHI_EXPECT_SECRET_STORE=DPAPI` — which turns "no key store on this machine" from a skip into a failure — `the real OS store round trips a master key` and `a vault backed by the real OS store survives a reopen` both pass, and the packaged launcher prints `keys : Windows DPAPI`. The two SecretStoreTest skips on that runner are the POSIX-only ones. **Windows moved on a green Actions job, not on the workflow existing.** Linux stays 🟡: `ubuntu-latest` installs gnome-keyring but its job has not been green end to end. Untestable anywhere: a domain-joined profile, and an administrator-forced password reset, after which DPAPI cannot decrypt the blob and the vault refuses to open rather than minting a new identity. A wrong or missing master key FAILS — it never reads as an empty vault |
| 0.6 | Prekey store (SPK + OPK privates) | `V2PrekeyStore.kt` | 🟡 | in the vault, not a plain file. Peek-then-burn; pool capped, freshly minted ids never evicted |
| 0.7 | Session store (ratchet state, persisted) | `V2SessionStore.{swift,kt}` | 🟡 | same JSON shape as Android. A reloaded session decrypts what the live one encrypted (tested). An unreadable record raises rather than reading as "no session" |
| 0.8 | `/v2/config` rollout gate | `V2ConfigGate` | ✅ | fails closed on every error path (tested); bucket vectors computed independently and checked against a little-endian read. **Exercised against the LIVE server 2026-08-25**: the desktop client's identity lands in bucket 43 and the gate opened inside the 100% rollout. It also says WHY it closed — server-disabled, below min_build, outside the bucket and never-reached-the-server are four different answers |
| 0.9 | `/v2/keys` publish + fetch + count | `V2KeysClient.kt` | ✅ | SPK signature and TOFU both enforced and both watched failing under mutation. **Exercised against the LIVE server and a SHIPPED phone 2026-08-26** (Galaxy A17 SM-A175F, OSHI Android 1.6.7 / versionCode 45): the desktop fetched that phone's real bundle, the SPK signature and TOFU both passed, and the phone in turn opened a session against the bundle THIS client published |
| 0.10 | `/v2/messages` send + pull + ack | `V2MessagesClient.kt` | ✅ | a failed pull is null, never an empty one; one bad envelope costs one message, not the response. **Live relay, both directions, against a shipped Android phone 2026-08-26** — see row 0.12 |
| 0.11 | `/v2/account` | `V2AccountClient.kt` | ✅ | 207 is a receipt, not a failure — and the REPL prints which stores it names, never a tick. **REACHABLE and exercised against the LIVE server 2026-08-26** (`/deleteaccount confirm`): two throwaway identities erased, `GET /v2/keys/<them>` went 404 and their sync archives went empty, checked from outside the client. **The server goes FIRST**: the erase is authorised by the identity's own signing key, so wiping the vault before the server answers destroys the only credential that could ever ask again and strands the data for ever. A refused delete leaves the account intact and says so |
| 0.12 | Message router (send/receive orchestration) | `V2MessageRouter.{swift,kt}` | ✅ text / 🟡 media | text path only (no media yet). Driven end to end between two independent clients through a behaving relay: X3DH, ratchet, retry budget, ack discipline, responder TOFU, one bundle fetch per conversation. **AND against a REAL PHONE, both directions, 2026-08-26** — Galaxy A17 (SM-A175F), shipped OSHI Android 1.6.7/45, over the LIVE relay, no fixtures: desktop→phone arrived, decrypted and rendered in the phone's own chat list and chat view (🔒 E2E, “Via Internet”), and phone→desktop arrived and decrypted here. The phone took its V2 branch, not the legacy one (`MessageRepository.kt:2159`; its `pushAfterV2Send` fired, and `📭 No pending IPFS hashes` on the same tick). **Media DOES cross to a phone (2026-08-26)** — and the first live send exposed a defect no unit test could see: `sendFile` defaulted `mediaType` to `document` and `/sendfile` never overrode it, so the phone logged `📥 Received DOCUMENT via V2 blob (13464B)` for a JPEG whose MIME was `image/jpeg`. A receiver reads the FIELD first and only falls back to the MIME, so every photo, clip and voice note this client ever sent drew a file row: no thumbnail, no player, “Download” instead of an image. Both directions of the rule are fixed and byte-verified on the wire (`MediaType.forMime`). And read row 0.18 before calling this conversation complete — the TEXT crosses, the receipts and typing around it do not |
| 0.13 | Local message store | Room / CoreData | 🟡 | append-only NDJSON per conversation, O(1) per message. Dedup by msgId ACROSS transports, ordering by the message's own timestamp, a torn last line drops one message and keeps the history. Plaintext on disk — stated and justified in the file, not an oversight |
| 0.14 | Contacts | `ContactPresenceManager`, Android contact tables | 🟡 | blocking is a FLAG, never a delete; `visible()` hides blocked contacts, `all()` does not |
| 0.15 | Blob upload/download (media) | `V2BlobClient.kt` | 🟡 | reserve → chunk → status → commit, resume into an existing blob, streaming decrypt straight to a file. Round-tripped through a real in-process blob server, including a non-UTF-8 chunk (the reason the download route needs bytes, not a String). **Exercised against the LIVE blob store 2026-08-26**: 4.3 MB desktop→desktop, bytes identical on arrival, delivery receipt returned; and a JPEG desktop→PHONE, which the handset logged as received and decrypted. See row 0.12 for the media-TYPE defect that live send exposed |
| 0.16 | Mesh payload ↔ ratchet | `MeshNetworkManager` ↔ `MessageManager` | ⬜ **blocked, see below** | the mesh does NOT carry V2 — it carries the LEGACY ratchet, and its media is not encrypted at all |
| 0.17 | Groups | `GroupManager`, `V2GroupSession.swift`, `GroupMessaging.swift` | 🟡 partial — three sub-rows blocked, see below | **There is no group key and no sender key.** A group message is a FAN-OUT of pairwise V2 ratchet sessions — N members, N ciphertexts, `type:"group"`+`groupId` per envelope, relay knows nothing about membership. iOS's `SenderKey` struct is declared, persisted, migrated and **never used to encrypt a byte**; the legacy `deriveGroupKey` is `SHA256(uppercase(groupId) ‖ a constant)` — every input public. `📢GROUP_UPDATE📢` codec, the update authorizer and the message payload are byte-tested against the shipped emitters. The definition is the ONE payload encoded `.iso8601`, twenty lines from a `GroupMessage` encoded Apple-epoch; a number in `groupPictureUpdatedAt` throws past a bare `decodeIfPresent` and drops the whole roster, so every date key is guarded on emit and the decoder reproduces iOS's rejection rather than being quietly more tolerant |
| 0.18 | Delivery receipts, typing, reactions, edit/delete | `DeliveryReceiptManager`, … | 🟡 | all four epochs identified and converted in ONE place (`WireClock`): Apple-reference seconds inside the payload, Unix MILLIS on the envelope carrying it, Unix seconds on the legacy wrapper, ISO-8601 once persisted. The first two sit one nesting level apart in the same transmission, and three shipped Android emitters got it backwards. No epoch is inferred from a field's name — every one of them is called `timestamp`. **RECEIVE-FROM-ANDROID IS STRUCTURALLY DEAD, found 2026-08-26 with a phone on the desk — see below** |
| 0.19 | Location sharing, check-ins, contact cards | `LocationSharingManager`, `CheckInManager`, `MediaManager.createContactCard` | 🟡 | rides row 0.18's dispatch — no second catalog, no second router, no second epoch converter. Receive-only by construction: the JDK has no GPS and none is faked. **Two shipped holes refused rather than copied** — (1) `isLive:true` with no `expiresAt` never expires on EITHER platform and no UI can dismiss it; (2) Android's `liveUpdate` recomputes `expiresAt = now + duration` every ~10 s (iOS's un-taken 2026-06-04 fix), so a share stays live up to 8 h past its intended end. Earliest expiry per `sessionId` wins, inward only. Contact cards are media bytes with `mediaType: contact`, NOT a sentinel — so they get a codec and no router entry. **RECEIVE-FROM-ANDROID IS DEAD TOO, measured 2026-08-27**: a handset shared its position at a real desktop and the desktop got nothing. The phone's log names the lane — `📤 Location sent via VPS`, after `encryptWithDoubleRatchet` — and the send path has NO `v2MessageRouter` branch at all (`MessageRepository.kt:2780-2828`: Android mesh, then iOS mesh, then the legacy VPS/IPFS queue). So location shares and check-ins join the row-0.18 list: emitted by Android on the legacy lane only, and unreachable by a client that does not implement it. The codecs here are still right and still byte-tested; there is simply nothing to feed them from an Android peer |
| 0.20 | Safety numbers | `SafetyNumber.swift` | 🟡 | three mutually incompatible implementations shipped once already, so this one is checked against the bytes rather than against any of the three |
| 0.21 | Blocked contacts | `BlockedContactsManager` | 🟡 | ENFORCEMENT, not a second flag. Dropped before the payload is read on the mesh; after decryption on V2, because the ratchet must advance for a blocked sender or every message after an UNBLOCK is undecryptable. Blocked peers are withheld from the conversation list, never deleted. **Four iOS defects found while reading this — see below** |
| 0.22 | QR pairing / identity exchange | `QRCodeDisplayView`, `QRScannerView` | 🟡 | the payload is the bare identity key in STANDARD base64 — no JSON, no version, no scheme. Emit standard, accept either: every base64url site in the shipped trees is a URL path segment, never a QR. Scanned link hosts ARE checked here, unlike iOS |
| 0.23 | Legacy IPFS path | `IPFSEphemeralManager`, `PinataConfig` | ⛔ | **Deliberately not implemented — product decision, 2026-08-25: "ipfs was the old road".** The desktop client's transports are the V2 relay, the LAN mesh, and LoRa (row 0.27). Not-applicable is a claim that has to be defended, so: this path predates V2, it exists only for peers that never got V2, and shipping it would mean a third message pipeline and a pinning credential the desktop has no way to hold |
| 0.27 | **LoRa / Meshtastic transport** | `LoRaTransport.{swift,kt}`, `MeshtasticManager`, `LoRaEnvelope.kt`, `LoRaNodeIdentityMap`, `LoRaPacketParser` | 🟡 codec — **link UNVERIFIED against a radio** | Envelope, ≤180-byte chunking behind an 8-byte header, reassembly, identity binding, interop lane. **A node number proves nothing**: a firmware-assigned uint32 on a channel keyed with the PUBLIC default LongFast key, so binding is LEARNED and only after the ratchet produces plaintext — and two nodes claiming ONE identity marks BOTH ambiguous (iOS fix 2026-08-10), because picking a winner is a coin flip on whose messages get attributed to a real contact. OSHI **broadcasts** ciphertext here; confidentiality is entirely the ratchet's. Stock Meshtastic text is quarantined under `lora!<nodehex>` and flagged `unverified` on every decision. **Both phones are BLE-only and the JDK has no Bluetooth** — verified by grepping both trees for the stream-framing magic (zero hits) — but BLE is a phone constraint, not a protocol one: the same protobufs ride **TCP:4403**, reachable with `java.net` and NO new dependency. `StreamFraming` is the ONE piece not taken from OSHI source (neither client has it); it is Meshtastic's public protocol, unit-tested, and **has never moved a byte to a device**. **The socket now EXISTS and is REACHABLE (2026-08-27)**: `LoRaLink` — TCP :4403, `want_config_id` handshake first (a node is silent until asked), a receive buffer that outlives the read, drain-in-a-loop, desync resync and reconnect with backoff — wired through `OshiClient.loraAttach` and `/lora attach|status|detach`. Exercised against `FakeMeshtasticNode` over loopback, including a frame SPLIT MID-BODY across two reads; three mutants watched failing (buffer persistence, handshake, drain loop). **Still never against a radio, so this row stays 🟡.** And attaching one does not buy messaging: stock Meshtastic TEXT is readable and quarantined under `lora!<nodehex>` flagged unverified, while an OSHI↔OSHI envelope is REPORTED AND NEVER OPENED — legacy Double Ratchet, the identical blocker as row 0.16. `/lora attach` prints that limitation on every attach rather than burying it here |
| 0.24 | Multi-device sync | `V2SyncManager.swift`, `V2Client+Sync.swift`, `MultiDeviceSyncManager.{swift,kt}` | 🟡 | **the row names two DIFFERENT protocols.** iOS's signed, owner-only `/v2/sync` archive has NO CALLER in the shipped app (`AUDIT_V2_2026-07.md:37`) and does not exist on Android. What ships on both phones is the legacy `/api/sync/{kind}/{key}` blob archive: whole-blob overwrite, **zero authentication** — the path segment is the user's PUBLIC key, so anyone holding it can overwrite that identity's slot. Both implemented; the two path encodings are different functions and mixing them syncs to two slots with no error. A page that does not advance the cursor ends the drain — without that a rolled-back or hostile server spins the client forever, a hole iOS still has. **Both halves exercised against the LIVE server 2026-08-26** — `/v2/sync` push, head and a full drain all answered, cursor advanced and persisted. **And it still cannot sync with a phone, for two independent reasons — see below.** |
| 0.25 | Scheduled messages | `ScheduledMessageManager.{swift,kt}` | 🟡 | **Android has them too** — the row's "iOS-only" premise was wrong. Purely local on both: no endpoint, no sync kind, so a schedule does not reach the user's own second device. Same filename, mutually unreadable, and the epochs differ. The desktop cannot send while stopped and says so: delivery is the first poll after the due time, reported as `lateByMs` rather than hidden |
| 0.26 | Bots | `BotManager.swift`, `ServerVPS/api/message_queue_server.js` | 🟡 wired — **plaintext lane, entered deliberately** | **Bot traffic has NO encryption.** `bot:<messageId>:<base64 JSON>` in the hash slot of the LEGACY pending queue — the same lane row 0.23 was retired from. The server's own source: *"base64, NOT encrypted… add your key, poll the queue, read every bot post in cleartext."* And registration uploads the group NAME plus EVERY MEMBER PUBLIC KEY in cleartext, handing the relay the membership graph V2 spends N pairwise ciphertexts withholding. The codec is now REACHABLE: `/bot poll` and `/bot send` in the REPL. Every send prints the warning first, and `OshiClient.BOT_CHANNEL_IS_PLAINTEXT` is its single owner so no later caller can drop or reword it. A bot post is filed under `bot!<groupId>`, never a peer conversation — nothing ratcheted it and no peer authenticated it, so putting it beside real messages would make it indistinguishable from one the ratchet vouched for. Two ids ride in a bot envelope and can disagree: dedup and ack both use the ENVELOPE id, because storing under one and acking the other re-reads the same post on every poll for ever. Duplicates are acked for the same reason. The queue's `timestamp` is ISO-8601, not millis |

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

### Row 0.24 — "sync with my phone" is blocked TWICE, and neither block is in the sync code

Asked directly on 2026-08-26 — *can this client synchronise with an OSHI session on a phone?* —
the sync code is not what answers. It works: `/v2/sync` push, head and drain were all run against
the LIVE server that day and behaved. Two things outside it make the answer **no**, and they fail
in different places, so fixing either one alone changes nothing.

**Block 1 — no phone writes the archive this client reads.** `V2SyncManager` is instantiated
nowhere in the iOS tree (`grep 'V2SyncManager('` → zero hits; it survives only in its own file and
in `V2SyncModels` doc comments), and `/v2/sync` does not appear anywhere in the Android tree. What
a phone actually does was watched happening, not inferred: the Galaxy A17 on the desk logged
`MultiDeviceSyncManager: 📤 Uploaded 35 messages to the sync archive (~187 KB)` — and that
archive is the LEGACY `POST /api/sync/{kind}/{publicKey}` blob (`MultiDeviceSyncManager.kt:1163-1195`),
not the V2 log. So the two clients are writing to two different archives on the same host.

**Block 2 — this client cannot BE the phone's account.** Multi-device sync is same-key by
definition: the archive is AES-256-GCM under `SHA256(x25519_priv ‖ "oshi-sync-key")`, which this
client reproduces byte for byte (`SyncCrypto.legacyArchiveKey` vs Android's
`MultiDeviceSyncManager.deriveSyncKey:1394-1406` — same salt, same digest, same order). It is the
right key derived from the WRONG private key, because `IdentityStore` has exactly one writer of
`ACCOUNT_X25519_PRIV` and it is `loadOrCreate`, which GENERATES. There is no import, no restore,
no linking flow — so this client is always a separate OSHI account, and it derives a separate
archive key, and it reads and writes its own slot in perfect isolation.

Block 2 is the one that matters, and it is also the cheaper of the two: the crypto and the five
legacy kinds are already here and already agree with the phone. What is missing is a way to hand
this client an existing identity — which is a **security decision before it is a feature**, since
it means moving a messaging private key between devices, and the shipped device-link flow it
would have to interoperate with is the one this ledger already records as never transmitting
(`linkDevice` … `// Simulate success for now`, `.kt:336-360`). Until it is designed, no UI copy
may offer to "sync with your phone".

Two smaller things measured the same day, recorded because they change what the archive is worth:

- **The live server does not enforce the owner check the source describes.** `sync_store.js`
  gates every `/v2/sync` verb on `requireOwner`, but `oshi_auth.js:30` defaults `AUTH_MODE` to
  `dual`, and in that mode an unsigned request is returned `{allow:true, authed:false, identity:
  claimedIdentity}` — which then matches the userKey in the path by construction. Confirmed in
  production, not reasoned about: `GET /v2/sync/<key>?after=0` with **no** signature headers
  returned this client's real archive item — seq, itemId, kind, deviceId, ts and ciphertext.
  Bodies stay confidential (the archive key never leaves the device), but the metadata does not,
  and `requireOwner` gates POST through the same call, so an append by a stranger follows from the
  same grace. The append was NOT executed against production — that half is inference from the
  shared gate, not a measurement.
- **`seq` is a server-global counter** (`const seq = ++store.seq`), not per-identity. A fresh
  account's first push came back at seq 5. Harmless to the protocol — the cursor only ever
  compares against `maxSeq` from the same log — but it does hand every caller a rough count of
  every write the server has ever taken, and it makes "seq 5 of 5" on a brand-new account look
  like history that is not there.

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

### Row 0.18 — the text crosses; every receipt, tick and indicator around it does NOT

Found on 2026-08-26 by watching a shipped Android phone answer a message this client had just
sent it, rather than by reading the desktop's own code — which is correct and proves nothing
about what a phone will actually put on the wire back.

The phone received the desktop's V2 message, wrote it into its own store, raised its own
notification, and then emitted **two** replies. Both went out over the LEGACY IPFS/VPS queue —
`encryptWithDoubleRatchet` + `POST /api/pinata/pinning/pinJSONToIPFS` + `POST /api/queue/...` —
which is exactly the lane row 0.23 is ⛔ against. The desktop saw neither, and never will.

This is not a gap in the desktop's implementation. It is Android emitting control payloads on a
transport the desktop deliberately does not have. Counted in the shipped tree:
`v2MessageRouter.sendText` has **2** call sites, `encryptWithDoubleRatchet` has **12**. The two
that are V2 are the text send and the file send. Everything else is legacy-only:

| payload | Android emitter | reaches the desktop? |
|---|---|---|
| 📬 delivery receipt | `MessageRepository.sendDeliveryReceipt:5517-5531` | **no** |
| 📖 read receipt | `MessageRepository.sendReadReceipt:5542-5556` | **no** |
| 📸 profile update / request | `MessageRepository.sendProfileUpdate:4834`, `requestProfileIfNeeded:4940` | **no** |
| ✏️ edit, 🗑️ delete-for-everyone, 📌 pin/unpin | `MessageRepository.sendActionPayload:5836` (4 call sites) | **no** |
| ❤️ reaction | `MessageReactionManager.kt:352` — `vpsClient.sendMessage` directly | **no** |
| ⌨️ typing | `TypingIndicatorManager.kt:265` — `vpsClient.sendMessage` directly | **no** |
| plain text, files | `v2MessageRouter.sendText` / `.sendFile` | **yes** (row 0.12) |

**iOS is not in the same position, and that is what makes this a defect rather than a protocol
limit.** `MessageManager+V2.v2SendDeliveryReceiptIfNeeded` (`swift:1025-1041`) routes the same
`📬DELIVERY_RECEIPT📬` payload through the ordinary send path, whose own doc says *"v2 first"*, and
`v2SilentControlPrefixes` (`swift:1046-1052`) enumerates eight control payloads V2 is expected to
carry. So an iPhone's receipts, profile updates and typing WOULD reach this client; an Android
phone's cannot.

The consequence for the desktop, stated so no UI copy claims otherwise: a conversation with an
**Android** peer delivers text in both directions and nothing else. No delivered/read ticks
inbound, no typing indicator, no avatar or display name from the peer, and an edit, delete or
reaction performed on the phone never lands here. Its own row status is therefore split: the
emit half is 🟡 as before, the **receive half is dead on arrival against Android** and only 🟡
against iOS.

The fix is one-sided and belongs to the phone, not here: those six emitters already have
`v2MessageRouter` injected and already fall back to legacy when `sendText` returns false. Trying
V2 first would cost nothing and is exactly the shape the text path already has. That is a change
to a shipped messenger, so it is recorded here as open rather than taken quietly.

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

### Windows and Linux: what CI has actually measured, as of 2026-09-11

This ledger is about capabilities; this section is about the two platforms the port exists for,
because "it builds here" is a claim about a Mac. The workflow is a three-OS matrix. Read from
`gh run list --workflow=desktop-ci.yml`, not from the file existing:

| leg | tests | packaging |
|---|---|---|
| `ubuntu-latest` | ✅ green in 9 of the last 10 runs — including the libsecret/gnome-keyring proof, which `OSHI_EXPECT_SECRET_STORE` turns from a skip into a failure | ✅ `.deb` + `.rpm` built, app image launched |
| `macos-latest` | ✅ green in 9 of the last 10 | ✅ `.dmg` built (not a shipping target; the local proof that the task graph is real) |
| `windows-latest` | ✅ green in 9 of the last 10 — 1340 tests executed, 4 skipped, and the skips are the two POSIX-only SecretStore cases | ✅ **`OSHI.exe` and `OSHI.msi` built, app image launched, window still alive after 25s** |

**This paragraph used to say the opposite**, and it said so for two weeks after it stopped being
true: *"red every run", "no `.msi`, `.deb` or `.rpm` has ever been built by anything."* Both
became false on 2026-09-02 and nothing here was re-read. The lesson is the one this file already
preaches and did not apply to itself — a status is what a run says, not what the last person to
touch the file remembered.

What the `package / windows-latest` job proves, step by step, because "an installer was produced"
on its own proves very little:

1. `jpackage --type exe` and `--type msi` succeed against WiX v3 on that runner.
2. The unpacked app image LAUNCHES, and `OSHI.exe --probe` reproduces iOS's published crypto
   vectors — which it cannot do unless BouncyCastle is genuinely on the packaged classpath.
3. `OSHI.exe --client` prints an account address, so the console launcher's REPL still works.
4. `OSHI.exe --ui` is still running 25 seconds later, which is the only evidence anywhere that
   skiko's `windows-x64` native loads.

What it does NOT prove, and must not be read as proving:

* **That the .msi installs.** Nothing runs `msiexec` on a clean machine, and nothing has ever
  put one of these files in front of a person. Row 0.5's rule applies: no evidence in either
  direction, so no claim in either direction.
* **That the window looks right.** "The process did not exit" is a long way from "it renders
  correctly". No screenshot of this window on Windows exists.
* **That a user could get it.** The artifacts are unsigned — SmartScreen will say "Windows
  protected your PC" to every downloader — and oshi-messenger.com has no Windows download link
  at all. See row 1.6.

**The gate is fragile in one specific way.** `package` is `needs: test`, so when ANY leg of the
test matrix goes red, every packaging job reports `0s` and the run produces nothing. That is
what is happening right now: since 2026-09-11 all three legs fail on the same two tests, and
neither is a Windows problem —

1. **`CatalogFreshnessTest`** — the iOS `Localizable.strings` were rewritten (the overclaiming
   "Military-grade" copy, among ~370 lines across 34 files) and `src/main/resources/i18n` was
   not regenerated. Fix: `./gradlew i18nExtract` once that copy pass has LANDED, not while it is
   still uncommitted in the working tree — regenerating from a half-edited tree produces
   catalogs that match neither.
2. **`StringsFileTest`** — `en Localizable key count expected:<3824> but was:<3840>`: the same
   rewrite added 16 keys. The recorded count in `StringsFileTest.kt:159` moves with it, in the
   same commit.

Both are shared-i18n drift owned by whoever is rewriting that copy. Until they land, the port
is green on Windows and shipping nothing.

## Tier 1 — the app

| # | Capability | Status | Note |
|---|---|---|---|
| 1.1 | UI framework decision + shell | 🟡 **decision TAKEN, one window built** | **Compose Multiplatform desktop 1.9.0, default ON, no opt-in flag** — an opt-in UI is a UI nobody compiles. PLAN.md §1 deferred it "until the protocol layer is proven" and Tier 0's live phone runs discharge that condition. The whole justification is in `build.gradle.kts`'s COMPOSE MULTIPLATFORM block, including the part that argues AGAINST it: **there is no screen reuse with Android** — none — so the usual reason to pick CMP does not apply here at all; what survives is one language end to end, idioms a Kotlin reader of the Android UI already knows, and one artifact for three OSes where Swing looks different on each and JavaFX left the JDK. **One cost was not foreseen by PLAN.md and is now in `settings.gradle.kts`: a SECOND repository.** Compose 1.9's `ui-desktop` needs `androidx.lifecycle:lifecycle-common` and `androidx.savedstate:savedstate`, measured 404 on Maven Central and 200 on `dl.google.com` — so Google's Maven is declared, CONTENT-FILTERED to `androidx.*` so the crypto core can still only come from one place. **The shell is a SIDEBAR, per VIEWS.md §7**: the phone's five-tab `MainTabView` is not copied, three of its five destinations do not exist on this client, and nothing is anchored to an icon position. **Opened on macOS aarch64 and on Windows** — the `package / windows-latest` job launches `OSHI.exe --ui` and fails unless the process is still alive 25s later, which is what proves skiko's windows-x64 native loads at all; linux-x64 runs the same check under `xvfb`. What no runner can say is whether it RENDERS correctly: "did not exit" is not "looks right", and no screenshot of this window on Windows exists. The window now also carries the shipped app icon (`/branding/oshi-256.png` off the classpath, `BrandingTest`) — jpackage's `--icon` dresses the launcher and the Start menu, and this dresses the taskbar and alt-tab; setting only one leaves the other stock |
| 1.2 | Chats list, chat view, compose | 🟡 **the shipped shell, text + attachments, 1:1 and groups** | `--ui`. The chrome is the macOS capture's: a segmented destination strip across the top in the phone's own order (Messages · AI · New · Places · More) and the `Messages \| Groups` control above the list, unread riding inside the segment. It is a segmented control and NOT a tab bar — VIEWS.md §7's objection was to anchoring meaning to icon positions, which words in the same order do not do. Driven by the REAL `OshiClient` with no mock and no fixture anywhere in the window. **Sending into a GROUP works here now** — `sendGroupText`, the same fan-out `/group send` calls — and the outcome carries BOTH numbers, because a fan-out over N ratchet sessions can half-work and "sent" over a silent 0/2 is the lie this model exists to prevent; blocked members are named as skipped rather than counted as failures. **The paperclip attaches** (`OshiClient.sendFile`, rows 0.15/0.12, watched crossing to a shipped Android handset) and is DISABLED in a group with the reason on screen, because there is no group media fan-out in this client at all — two gates on `ComposerState`, not one, precisely so that case can exist. What it still refuses is unchanged: no call button anywhere, not even disabled; a send that did not leave is never drawn as sent; a bot conversation is listed, gets no composer and carries `BOT_CHANNEL_IS_PLAINTEXT` verbatim; a `lora!` thread says its sender is unverified; a blocked peer is withheld from the list (and is visible under More, which is where it is undone). All of it in `com.oshi.desktop.ui.state`, which still has ZERO compose imports — 21 tests, 9 mutations watched failing. **The composables themselves are covered by nothing**, deliberately; `./gradlew renderScreens` rasterises them headlessly for a person to look at. Not localised — row 1.5's coverage is still the REPL's 12 keys |
| 1.3 | Contacts, peers, settings, onboarding | 🟡 **contacts, pairing and settings; no onboarding** | **New** draws this account's real QR (`QrMatrix` over `ContactQr.deepLinkFor` — the encoder `/qr png` uses, not a lookalike) and accepts a pasted code: a bare key, an `oshi://` link, or the phones' bracketed share text. Rejections surface `ContactQr.parse`'s OWN reason rather than "invalid code" — "that is your own address" and "that is not valid base64" send a user to two different places. Adding is local: no message, nothing published, the peer is not told, and the screen says so. **More** lists every contact with its full sixty-digit safety number, renames locally, blocks and unblocks, and reaches the account and limits panes. It reads `contacts.all()` while the list still reads `conversations()` — row 0.21 withholds a blocked conversation, so the one screen listing people has to keep showing them or blocking is a one-way door. NOT here: marking a number VERIFIED (`/verify`), because that is a claim about a comparison made away from the screen and this window cannot witness it; no peers/mesh screen (row 0.16 — the mesh carries payloads this client cannot open); no onboarding at all — the first run is still a REPL line |
| 1.4 | Media viewers, wallpapers, link previews | 🟡 | Chat wallpapers ship and are local-only, and the picker says so. Media SENDING is now in the window (row 1.2); there is still no VIEWER — an inbound photo is a named file on disk, the bytes decrypted and sitting there, and nothing in this window opens them. Link previews remain refused on their own merits: fetching an arbitrary URL a stranger sent is an outbound request to a third party triggered by an incoming message |
| 1.5 | Localisation | 🟡 | 34 catalogs CHECKED IN as UTF-8 JSON (3.06 MB in the jar, read back out of it) — extraction, not translation: 131,044 already-translated strings existed and none of them shipped. Generating them into `build/` would have produced catalogs on the one machine that does not need them, since a Windows or Linux runner has no `OSHI/` checkout, and shipped an English-only `.msi` with nothing red. **English is not the superset** — `en` has 3820 keys, the union across 34 locales is 4854, so 1034 keys live ONLY in translations (612 pl, 402 fa, 60 ja) and every compare-to-English test is structurally blind to them; the audit compares against the keys USED IN SOURCE instead, which caught a key wired here that exists in Polish alone. Not `ResourceBundle`: `getBundle` falls through `Locale.getDefault()` BEFORE the base bundle, making the fallback chain a property of the machine rather than of the code — the chain here is explicit and asserted identical under four hostile defaults, a miss renders `⟦key⟧`, and a BLANK value counts as a miss rather than as coverage. zh-Hans ships a UTF-8 BOM that silently corrupts exactly its first key. Coverage is reported as the 12 keys the CLI actually uses (34/34 locales serve all 12), never as iOS's 3820: that would claim a translated client on the strength of a translated phone. No plurals to port (zero `.stringsdict` in the iOS tree, asserted) and `InfoPlist` maps to NOTHING here — all 16 keys are `NS*UsageDescription` and Windows/Linux have no permission dialogs |
| 1.6 | Packaging: `.msi`/`.deb`/`.rpm`, signing, updates | 🟡 **installers BUILT, nothing signed, nothing published** | jpackage out of the JDK (zero new dependencies); it does not cross-build, so each installer needs its own runner. The CI workflow is no longer hypothetical: `package / windows-latest` has produced `OSHI.exe` and `OSHI.msi`, launched the app image, and seen the window survive 25s — the only evidence skiko's `windows-x64` native loads. Both Windows launchers now carry the shipped app icon (`platform/windows/packaging/OSHI.ico`, `OSHI-Console.ico`, asserted by `BrandingTest`); before that every artifact wore jpackage's Duke in the Start menu, the taskbar and Add/Remove Programs. **Three things still stand between this and a person installing OSHI on a laptop, and none is a code defect:** (1) **nothing is signed** — Authenticode needs an OV/EV certificate on hardware or a cloud HSM, so every early downloader gets SmartScreen's "Windows protected your PC"; (2) **nothing is published** — oshi-messenger.com offers iOS and an Android APK and has no Windows download at all; (3) **the packaging job is `needs: test`**, so while the suite is red it silently produces nothing (it is red right now, on two i18n catalog tests — see row 1.5). No update check, no feed; the stable `--win-upgrade-uuid` means a higher-versioned `.msi` upgrades in place and that is the whole of it |

## Tier 2 — hard, or not ours

| # | Capability | Status | Note |
|---|---|---|---|
| 2.1 | Voice/video calls | 🟡 voice — **video is signalling only** | **The row's premise was wrong: neither shipped client uses WebRTC.** iOS links none ("No libwebrtc. Pure Network.framework."), Android REMOVED `stream-webrtc-android` after grep found zero uses of it, there is no SDP anywhere in either tree, ICE is a bespoke binary TLV, media is AES-256-GCM raw PCM rather than SRTP, and CALL_V2_PLAN requires a proprietary codec — "Cannot rely on Opus/AAC." A WebRTC media path would have called no OSHI phone: row 0.16's mistake with a 15 MB native attached. What is implemented is OSHI's own path with ZERO new dependencies — PCM 48 kHz under the offer's session key, `javax.sound`, 782 kbit/s one-way, matching iOS's own measured figure. `webrtc-java` is evaluated, verified and left OPT-IN/DEFAULT-OFF; windows-aarch64 has no published native at all. Signalling rides a SEPARATE server, never `/v2/messages`: the body is E2E, the metadata is not — sender, recipient, callId, video flag and the caller's DISPLAY NAME are cleartext, and journald logs both parties' key prefixes and raw UDP IPs, so calls hand the relay the same social graph the bot lane does. The seal is static-static ECDH, so there is NO forward secrecy despite an audit doc claiming otherwise, and the call server has no authentication at all (`SIGNATURE_REQUIRED = false`). Stricter than both phones on one race: a decline crossing an answer tears down a live call on iOS AND Android; here it is refused. **The media joint now exists — `CallAudioSession.onFrame` and `MediaSocket.sendSealed` had no caller in the whole tree, and `CallLane`'s `StartMedia` branch dropped the session key and the nonce salt on the floor and logged `NO_AUDIO_WILL_FLOW`.** `CallMediaLeg` ties them: audio's `send` → `sendSealed`, `onSealedMedia` → `onFrame` returning true so the call has ONE replay window and not two that disagree, host + srflx candidates → the `ice_candidate` signal the protocol already carried and nothing ever sent, and a teardown that releases the device before the socket. Candidate exchange in BOTH directions is wired (send on connect, decode-and-probe on receive, gated on the identity that OPENED the seal and on this call's id), probing runs at the phones' 2 Hz, and a device that will not open ENDS the call with a reason rather than connecting it in silence. **Verified, with numbers:** 288 tests in `com.oshi.desktop.call.*` green; two legs on `127.0.0.1` punch, elect a pair and carry a sealed 20 ms / 1 920-byte PCM frame that authenticates, passes one replay window and reaches the far side's speaker queue BYTE-IDENTICAL; the full lane path (ring → answer → sealed `ice_candidate` over a real call server → punch → audio) runs end to end; nine mutations watched failing, and two more stayed GREEN and exposed two teardown lines each covering for the other, both now deleted. **Not verified, and none of it is claimed:** no microphone or speaker is ever opened by any test (every session is deviceless — real codec, real replay window, real playback queue, stubbed `start`/`stop`), so the device has still never moved a sample; no NAT has been traversed, because loopback has none; no real STUN server has answered; no packet has reached a phone; both ends are ONE process on ONE machine. **Audio crosses loopback in a test. That is not a call between two people, and no call between two people has been made.** Still no TURN, so behind a symmetric NAT on both ends there is no media path at all — which is why a new watchdog (`MEDIA_PATH_TIMEOUT_MS`, 20 s, OUR number: neither phone exposes one) ends a connected call whose punch never lands, since `CallStateMachine` watchdogs RINGING and CONNECTING and nothing else. **Media is a SEAM and it is OFF in the shipped build:** `CallLane`'s `mediaOpener` defaults to null, `app/OshiClient` does not pass one, so `NO_AUDIO_WILL_FLOW` is still literally true of this application and there is still NO call button anywhere in the UI. Switching it on is one argument in `OshiClient` plus a CLI line that branches on `Connected.noAudio`. **Video is RECEIVE-ONLY and does not display** — see the sub-row below |
| 2.1-v | Video: the inbound path | `VideoCallManager.{swift,kt}`, `EnhancedCallManager.kt` | 🟡 receive-only, **never run against a phone** | The `0xF1` envelope, the per-fragment AES-GCM seal, the `[0x01][frame_id 2BE][idx][total]` fragment header, reassembly, and the `[flags][timestamp 8 LE][spsLen 2 LE][sps][ppsLen 2 LE][pps][frameData]` frame packet. Output is Annex-B — a `.h264` elementary stream `ffplay`/`mpv`/VLC read directly. **No send half**: `javax.sound` gave the audio path a microphone for free and there is no `javax.video`, so a webcam means a native library per platform — the dependency row 2.1 already refused for libwebrtc. **No display half**: the JVM has no H.264 decoder either, so the bytes are authenticated, reassembled and written out intact and no pixel is drawn in this app. Three things the shipped clients disagree about, resolved rather than averaged: (1) **the envelope's sequence number is big-endian on iOS and little-endian on Android** — `swift:11139` writes `.bigEndian` "for cross-platform compatibility", `kt:4923-4945` writes LE under a comment describing the code iOS had BEFORE that fix; we emit BE and treat the field as OPAQUE on receive, since reassembly keys on `frame_id` and replay is caught by the nonce; (2) **Android truncates any frame over 255 fragments** (`.coerceAtMost(255)`, `kt:1332-1345`) so 280,500 bytes in, the tail is silently amputated and the receiver declares the frame COMPLETE — iOS refuses the frame and forces an IDR, and so does this; (3) **a `spsLen` that runs past the buffer leaves iOS's offset unadvanced** (`swift:2637-2641`), so a corrupt packet parses into a plausible frame — here it fails the packet. Also stricter on a repeat of the frame just completed, which both phones let through (`dist == 0` is not `dist > 32768`). The video nonce is its own scheme and both halves of it were shipped bugs: a per-session salt with the **direction bit** in its MSB (without it, caller and callee emitted BIT-IDENTICAL nonces under one key — a total GCM break) and **bit 63 of the counter forced set** so video can never alias an audio nonce under the shared session key. Replay is a window over WHOLE nonces, not a counter, because a peer on the pre-fix build emits `[counter LE][0×4]` and any counter-based window reads its entire stream as counter 0. 48 tests, 13 mutations watched failing. **NOT verified: any byte from a real phone.** No camera, no decoder, no radio — the fixtures are hand-built to the shipped emitters' shapes, and no radio. See row 2.1-c for the control opcodes |
| 2.1-c | Video: the control lane | `EnhancedCallManager.kt:3150-3215, :5115-5470`, `VoiceCallManager.swift:10344-10493` | 🟡 codec + policy — **no transport under it** | Five types ride the media channel and **three of them are cleartext**: `0x0B` keyframe request, `0x0C` camera paused and `0x0D` camera resumed are `[type][timestamp 8 BE]` with no nonce, no tag and no signature, while `0x0E` upgrade and `0x0F` stop are sealed media envelopes carrying ONE byte. **The shipped clients act on the cleartext three unconditionally** — `0x0C/0x0D` move the camera indicator, and `0x0B` calls the local encoder's force-IDR with NO inbound rate limit (`kt:3172-3178`); the one-token-per-350 ms burst-3 bucket both clients implement is on the SENDING side only. So anyone able to put a datagram on the media port can flip a peer's camera indicator or hold their encoder at keyframes, multiplying that peer's uplink for as long as the flood lasts. Their protocol, not ours, and not inherited here: there is no encoder to force, inbound `0x0B` is bucketed before it is even counted, and a cleartext packet may move ONE display flag — no key, no state transition, no transport decision. An upgrade REQUEST is never answered automatically (both phones raise a banner and wait for a person), and when it IS answered the answer is `0x05` accept-**receive-only**, never `0x02`: `0x02` claims a camera this platform does not have and leaves the peer in front of a black rectangle. The cost of `0x05` is named — an Android build from before it was added logs `UNKNOWN_CODE` and hangs until its 15 s timeout says "No answer", which is still a truthful no. The upgrade lane draws its counter from the SHARED audio sequence, because a private one would reuse an audio nonce under the audio key. 19 tests, 10 mutations watched failing. **Nothing sends any of these**: the media ingress this lane belongs to does not exist yet — `CallAudioSession.onFrame` has no caller either — so the whole lane is decisions with no socket under them |
| 2.2 | Mesh direct calls | ⬜ | depends on 2.1, and on row 0.16 being decided — the LAN mesh carries opaque payloads today |
| 2.3 | Push notifications | ⛔→🟡 | APNs/FCM have no desktop equivalent here; a desktop client POLLS `/v2/messages`. Not the same thing, and it must be said out loud: no wake-from-sleep delivery. |
| 2.4 | Steganography | ⬜ | |
| 2.5 | NLP / AI features | 🟡 plumbing — **no inference has ever been run** | **The row's premise splits in two and only one half can exist here.** iOS PREFERS Apple's on-device `SystemLanguageModel` (`FoundationModels`, iOS 26+) and falls back to `LocalLLMManager` = llama.cpp over GGUF; Android has only the fallback. The Apple half is impossible on Windows/Linux and no amount of work changes that, so a desktop answer will differ from an iPhone's answer to the same question — that belongs in any UI copy this ever gets. The portable half is SHARED SOURCE, not a port: `service/LlamaCpp.kt` is 30 lines with **no imports at all**, so `build.gradle.kts` compiles it out of the Android tree exactly as it compiles the crypto six (`sharedServiceSources`), and `LlamaCpp.class` is verified to be that class by reflection. Sharing rather than copying matters more here than for the crypto: a JNI declaration is half of an ABI — the C++ exports `Java_com_oshi_messenger_service_LlamaCpp_*`, named after the package the Kotlin declares — so a copy is two ABIs that look like one. `LocalLLMManager`/`NLPManager` are NOT shared (Hilt, `Context`, ML Kit); the ChatML prompt and the eight cleanup functions are re-derived in `com.oshi.desktop.ai.OshiPrompt` and asserted character-for-character against the Android source read off disk. **`System.loadLibrary` failing is the normal case and it has two shapes, not one**: the first touch throws `UnsatisfiedLinkError`, every touch after it throws `NoClassDefFoundError` because the JVM never re-runs a failed initialiser. Android catches only the first, which is sound there (it constructs once in `init`) and would take a REPL down on the second question; both are caught here and the first diagnosis is remembered, so the answer is the same sentence twice. `System.load` on an absolute path cannot rescue it — the `loadLibrary` call is inside the shared file's companion init and `ClassLoader` only walks `java.library.path` — so the diagnosis says which file it looked for, where it looked, whether `OSHI_LLAMA_LIB_DIR` holds it somewhere the JVM does not search, and what the JVM said. Every failure is a NAMED outcome (`NoNativeLibrary`, `NoModel`, `ModelGone`, `ModelLoadFailed`, `NativeCallFailed`, `TimedOut`, `Busy`, `EmptyOutput` with a raw-char count) and none of them is an empty string: Android returns *"The offline AI model is installed and ready. Full AI responses will be available in the next update."* **as the assistant's answer** in exactly this state, which is a placeholder wearing the model's voice, and there is a test here whose only job is to keep that shape out. A GGUF is validated by its magic bytes before llama.cpp has to fail obscurely (an interrupted download is an HTML page under a `.gguf` name). A timeout does NOT cancel: a JNI call is not interruptible, so the flag is held and the next question is REFUSED rather than queued — two threads in one ggml context is a SIGSEGV, not a wait. Reachable as `/ai <prompt>`, `/ai model <path>`, `/ai status`, `/ai forget`, wired-tested through the REPL. **NOT VERIFIED, and this is the whole caveat: not one token has been generated, on any platform.** There is no native library in this repository and no model is shipped — `platform/llama-jni/{llama_jni.cpp,CMakeLists.txt}` **has never been compiled by anybody**, and the `native-llama` job in `.github/workflows/desktop-ci.yml` (win/x64 + linux/x64, llama.cpp statically linked into one file, CPU only, `GGML_NATIVE=OFF` so it cannot SIGILL on an older user CPU) has never run. That job proves the library builds, exports the three symbols derived from the shared Kotlin, and LOADS from a JVM under `OSHI_EXPECT_LLAMA_NATIVE=1`; it deliberately does **not** download a 0.6–2.5 GB model, so even a green run leaves inference unproven. Move this row past 🟡 only on a green `native-llama` run PLUS a real model answering a real question — not on either alone |
| 2.6 | Screenshot blocking | ⛔ | no OS affordance on Windows/Linux; claiming it would be a lie in the UI |
| 2.7 | Apple Watch connectivity | ⛔ | no equivalent |
| 2.8 | App Store review prompts | ⛔ | not distributed through an app store |
| 2.9 | China region detection | ⛔ | **The row's premise was wrong, and it is worth saying how it got that way.** It does NOT drive which relay is used. The endpoints are compile-time constants with no region branch (`VoIPPushManager.swift:245-249`, all `oshi-messenger.com`; the two-URL arrays at `:452` and `:1843` are an nginx-vs-direct-port fallback). Grepping `OSHI/` for a China-specific host returns nothing. Region→relay DID exist once and was **deliberately removed on 2026-06-26 by user directive** (`VoiceCallManager.swift:13345-13353`): UDP TURN on 3478 is now the default everywhere including China, and the TURN-over-TLS GFW-evasion path on 5349 is reached only through the user's own `oshi.call.forceRelay` toggle (`SettingsView.swift:1427,1498`) — a preference, not a region test. The row was describing a link that had been cut. What `isChina` still does over the wire is ONE JSON field plus a blanked `voipToken` in `/register-device` (`:439-443`), to the same host, and the server recomputes it from a CN IP table regardless (`ServerVPS/push_service.js:973,1026`, `body.isChina \|\| ipIsChina`) before choosing an alert push over a VoIP push (`:1459-1489`). **Not applicable is a claim that has to be defended, so:** all 16 call sites live in `OSHI/` and every one of them branches on CallKit, PushKit, APNs, AVAudioSession or an iOS Settings row. The desktop has no system call UI to bypass — it is permanently in the state the China branch selects, so a detector here would have no caller, and a feature with no caller is dead code that reads as coverage. **Android does not implement this at all**; the only `"CN"` in that tree is a LoRa radio band (`LoRaRegion.kt:44,114`). If a consumer ever appears, note that the HK/Macau exclusion is UNVERIFIED on Windows, whose `China Standard Time` covers Hong Kong and whose disambiguation depends on a `lib/tzmappings` that ships only in Windows JDKs |

### Row 2.9 — six defects in the shipped iOS detector, found while defending the ⛔

Recorded here because this is where the reading happened. All six are iOS bugs, not desktop ones.

1. **`regionCode == "CHN"` can never fire** (`ChinaRegionDetector.swift:61`). `Locale.region.identifier` is alpha-2, so the comparison is against a value the
   API never returns. The tell is that `"CHN"` is *correct* for the other signal in the same file — `Storefront.countryCode` at `:44` is alpha-3 — so the two
   conventions were mixed and the alpha-3 literal was copied into the locale test.
2. **Four of the six time-zone ids can never be observed.** `Asia/Chongqing`, `Asia/Harbin`, `Asia/Kashgar` and `PRC` are tzdata `backward` aliases;
   `TimeZone.current.identifier` returns the canonical zone. This is quieter than a removed zone would be: all six PARSE, so the list reads as six-way
   coverage while the effective set is `Asia/Shanghai` and `Asia/Urumqi`. Comparing `ZoneRules` rather than identifiers would catch all four for free.
3. **The signal the header calls "primary detection" (`:6`) has the least authority.** The storefront check is async (`:38`) while CallKit and PushKit are
   registered synchronously at launch (`SecureWeb3MessengerApp.swift:741` → `VoIPPushManager.swift:310`). An expat phone — CN storefront, non-CN locale and
   time zone — therefore ends up HALF bypassed: PushKit registered, and the server told `isChina: true` with an empty `voipToken`.
4. **`isChina` is latched in `init` and never re-read** — no time-zone or locale observer. The traveller the time-zone signal exists for (`:66-68`) is missed
   whenever the app was already running when they landed.
5. **`@Published` on `isChina` is decorative.** `SettingsView.swift:1439` reads the singleton through a plain computed property with no `@ObservedObject`, so
   the storefront upgrade never redraws Settings.
6. **The Settings row displays the opposite of the truth in China.** `.disabled(isChina)` over `get: { !callKitDisabled }` renders "System call UI: ON
   (locked)" on exactly the devices where CallKit was never set up (`SettingsView.swift:1576-1591`).


---

## Working rules for this loop

1. **One capability per commit**, with its tests, and the ledger row updated in the same commit.
2. **Match the shipped bytes, not the shipped code.** Where iOS and Android disagree, the disagreement goes in the file's doc comment and the stricter option wins.
3. **A guard that has not been watched failing is not a guard.** Every new test gets a mutation run.
4. **No new dependency without a reason that survives being written down.** Everything so far is `java.net` + the JDK + the two libraries Android pins.
5. **Read-only on the shipped trees.** `OSHI/` and `OSHI-Android/` are inputs.
