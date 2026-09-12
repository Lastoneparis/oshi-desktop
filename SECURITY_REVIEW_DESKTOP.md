# Security review — OSHI desktop client

Read-only review, 2026-09-11, against the working tree at `OSHI-Desktop/`. Nothing in this
review was executed: no build, no fuzzing, no live relay. Every claim below is either
**CONFIRMED** — I read the whole path from the attacker-controlled byte to the consequence —
or **SUSPECTED**, and the two are kept apart on purpose. §4 lists what was not looked at.

---

## Verdict

**The cryptographic core of this client is in good shape and the parts that usually go wrong
in a messenger port have not gone wrong here.** Responder-side TOFU, the signed-prekey
check, the C-MSG-4 edit/delete ownership rule, the group-update authorizer, "never ack what
you could not decrypt", fresh GCM nonces everywhere, `SecureRandom` everywhere, redirects
disabled on the signed transport, filenames hashed instead of taken from the wire — all
present, all correct, most with the reason written above them. I found no key-recovery bug,
no nonce reuse, no path traversal, no authorisation check that can be skipped.

**What is not in good shape is everything around the crypto.** Three things should stop a
public download: (1) an attachment a stranger sends has **no size ceiling on the receive
side at all** — the 200 MB limit is enforced only when *sending* — so one message can fill
the user's disk or exhaust the heap; (2) when that heap exhaustion happens, an `OutOfMemoryError`
escapes a `catch (e: Exception)` and **permanently cancels the poll task**, so the client
stops receiving messages for good, with no error anywhere on screen; and (3) apart from the
key vault, **nothing this app stores is encrypted** — the full message history, contact list,
group rosters and every decrypted attachment sit in `%APPDATA%\OSHI` in the clear, and the
comment a reader would check (`DesktopPaths.kt:51-52`) tells them the opposite. Fix (1) and
(2) before shipping; say (3) out loud in the UI before a single user trusts this with a
conversation they care about. The unsigned, un-updatable, un-hashed download is a fourth
problem that this repository already documents honestly and that no code change can close.

---

## 1. CONFIRMED findings

Ranked by what a real attacker gets, not by how interesting the bug is.

### C1 — HIGH — An inbound attachment has no size limit; the 200 MB ceiling is send-only

**Where** `app/OshiClient.kt:1544-1562`, `net/V2BlobClient.kt:248-274`, and the constants at
`net/V2BlobClient.kt:277-282`.

**Attacker input** Any peer you have a V2 session with (i.e. anyone who can message you)
sends a `{"kind":"v2file"}` key message whose `chunkCount` and sealed manifest declare an
arbitrary size.

**What I verified.** `MAX_PLAINTEXT_BYTES` (200 MB) and `MAX_BLOB_BYTES` are referenced at
exactly three places in the tree — `V2BlobClient.reserve:60`, `OshiClient.sendFile:857`, and
a doc comment in `media/VoiceNoteFormat.kt`. All three are outbound. The receive path
(`receiveMedia` → `downloadAndDecryptToFile` → `OSHICryptoV2Streaming.decryptToStream`)
checks only `chunkCount >= 1`; the shared streaming decryptor
(`OSHI-Android/.../OSHICryptoV2Streaming.kt:71-96`) checks only that the manifest's
`chunkCount` matches the announced one and that the reassembled byte count matches the
manifest's `size`. **There is no upper bound anywhere on the receive side.** The only thing
standing between a peer and the victim's free disk is whatever the relay chose to accept —
which is a server-side control the client must not depend on, and which the *sending* half
of this same class deliberately does not depend on ("refuse before the round-trip, not after
a 413", `:60`).

**Consequence, two flavours.**

- *Disk.* Bytes are streamed straight to `<dataDir>/media/` with no running total. A peer
  who can get a large blob past the relay fills the user's disk.
- *Heap, and this is the sharp one.* At `OshiClient.kt:1560-1562`:

  ```kotlin
  val card = if (written != null && key.mediaType == MediaType.CONTACT.wire) {
      com.oshi.desktop.place.ContactCardPayload.decode(out.readText(Charsets.UTF_8))
  } else null
  ```

  `key.mediaType` is a plain string the *sender* writes. Declare `"mediaType":"contact"` on
  a large attachment and the client `readText()`s the whole decrypted file into one `String`
  — roughly 2× the file size in heap, with no length check of any kind. A JVM launched by
  jpackage with a default heap will `OutOfMemoryError` well before 200 MB.

**Fix** Reject a `V2FileKeyMessage` whose `chunkCount * chunkSize` exceeds
`MAX_PLAINTEXT_BYTES` before the first chunk GET; cap the streamed write; and cap the
contact-card read at a few kilobytes (a vCard with one key in it is ~200 bytes) rather than
reading an unbounded file into a String.

---

### C2 — HIGH — An `Error` out of the poll task silently and permanently stops message delivery

**Where** `app/OshiClient.kt:507-519`, with `net/V2Router.kt:241-265`.

**What I verified.** The poll is a `ScheduledExecutorService.scheduleWithFixedDelay` whose
body ends in `catch (e: Exception)` (`:515`). `V2Router.poll` likewise guards the per-message
work with `catch (e: Exception)` at `:243` and `:261`. `OutOfMemoryError` and
`StackOverflowError` are `Error`, not `Exception`, so neither guard sees them — and
`scheduleWithFixedDelay`'s documented behaviour is that a task which throws is **not run
again**. The task is not resubmitted anywhere and nothing observes its `Future`.

**Consequence.** One message that provokes an `Error` (C1 is the cheapest way, but any
unbounded allocation on the ingest path will do) kills message reception for the rest of the
process's life. The window stays open, the account pane still says the client is running,
and nothing is logged — `log("client: poll failed: …")` is inside the `catch` that does not
fire. For a messenger this is the worst possible failure mode: it is indistinguishable from
"nobody has written to you".

**Fix** `catch (t: Throwable)` on the scheduled body, log it, and keep the schedule alive
(re-submit, or wrap so the runnable can never throw). This is a three-line change and it
de-fangs an entire class of ingest bug, including ones not yet written.

---

### C3 — MEDIUM/HIGH — Only the key vault is encrypted at rest, and the documentation says otherwise

**Where** `store/DesktopPaths.kt:46-53`, `store/MessageStore.kt` (whole class doc),
`store/ContactStore.kt:65`, `app/GroupStore.kt:34`, `app/OshiClient.kt:181`.

**What I verified (by reading and by grep).**

- `KeyVault` is AES-256-GCM. Everything else is not. `MessageStore` writes newline-delimited
  **plaintext JSON** per conversation; `ContactStore` and `GroupStore` write plaintext JSON
  through `AtomicFile`; `WallpaperStore` and `ScheduledMessageStore` the same; and
  `receiveMedia` writes every **decrypted** attachment into `<dataDir>/media/` in the clear
  and stores its absolute path in the message row.
- `DesktopPaths.ensurePrivateDir` and `makePrivate` are **no-ops on Windows** (`:58`, `:67`),
  which the file states honestly — but it then justifies that with "*the FILES inside are
  encrypted anyway (see [KeyVault])*" (`:51-52`). **That is false for every file except
  `keyvault.json`.** A reader checking whether this is safe will read that sentence and stop.
- The justification chain dead-ends. `ContactStore.kt:65` says "Plaintext on disk, same
  posture as [MessageStore] — see that class's doc comment"; `GroupStore.kt:34` says "Not
  encrypted, same posture as the message log and the contact list"; `PARITY.md` row 0.13
  says "Plaintext on disk — stated and justified in the file, not an oversight". I grepped
  `MessageStore.kt` for `plaintext` / `unencrypted` / `not encrypted` / `at rest`: **there
  are no hits.** The file both other files defer to does not contain the paragraph they
  defer to.

**Consequence.** Against the threat this actually matters for — a stolen unlocked laptop, a
cloud-synced `%APPDATA%\Roaming` profile, a Time Machine/backup image, forensic recovery,
malware running as the user, and an IT admin with the roaming profile share — the message
history, the social graph, group membership and every photo a contact sent are readable with
`cat`. The key vault protects the *ability to keep using the account*; it protects none of
the content already received. PARITY row 0.5's ✅ and the KeyVault doc's confident framing
make this easy to misread as "OSHI desktop encrypts your data".

**Fix** (in priority order) (a) correct `DesktopPaths.kt:51-52` and write the missing
paragraph in `MessageStore.kt`; (b) say it in the UI, next to where the account pane already
says closing the window stops delivery; (c) if it is ever to be fixed properly, the vault
already holds a master key — a second derived key over the message log is the obvious shape,
and the NDJSON append design survives per-line sealing.

---

### C4 — MEDIUM — A received contact card writes a new contact with an attacker-chosen display name, with no user action

**Where** `app/OshiClient.kt:1560-1566`, `store/ContactStore.kt:121-135`.

**Attacker input** Any peer sends an attachment with `"mediaType":"contact"` whose body is
the JSON or vCard that `place/ContactCardPayload.decode` accepts, carrying a `publicKey` and
an `alias` of their choosing.

**What I verified.** On receipt the client calls
`contacts.seen(card.publicKey, inbound.ts, displayNameHint = card.alias)` with no prompt, no
confirmation and no gate. `ContactStore.seen` does **not** overwrite an existing display name
(`existing.displayName ?: displayNameHint`, `:129`) — so an attacker cannot rename a contact
you already have, which is the more serious version of this and is correctly prevented. But
for an address you do *not* already have, the attacker chooses both the key and the label.

**Consequence.** A stranger who can message you can drop a second "Mum" into your contact
list, keyed to an address they control, without you doing anything. `ContactQr.parse` — the
path a user takes deliberately — is careful, host-checked and stricter than both phones;
this path bypasses all of it. There is a second, smaller effect: each card triggers a whole-
file rewrite of `contacts.json`, so a spamming peer makes contact writes quadratic.

**Fix** Do not write a contact from an inbound card. Surface it as a *proposal* the user
accepts, which is what "adding is local: no message, nothing published, the peer is not told,
and the screen says so" (PARITY 1.3) already promises about the other add path.

---

### C5 — MEDIUM — Unsigned, unhashed, un-updatable download

**Where** `build.gradle.kts:1066-1079` (`windowsInstallerArgs`), `:599-620`,
`platform/windows/README.md`.

This repository documents the signing gap thoroughly and honestly, and I am not restating
it. Three consequences it does **not** draw are worth writing down before a real download
link exists:

1. **There is no integrity channel at all.** Not signed, and `oshi-messenger.com` publishes
   no SHA-256 either. A compromised web host, a hijacked DNS record or a MITM on the download
   substitutes a trojaned build of an end-to-end-encrypted messenger, and *nothing the user
   can do* detects it. Publishing a signed hash list — even a PGP-signed `SHA256SUMS` — costs
   nothing and is strictly better than the current zero.
2. **No update check and no feed** (`:620`, README "Updates"). Every finding in this document
   that gets fixed reaches existing users only if they happen to come back and re-download.
   For a security-sensitive client that is a standing liability, not a missing convenience.
3. **`--win-per-user-install` (`:1070`) puts the app and its bundled JRE in a directory the
   user can write.** That is the normal trade-off for avoiding UAC (Chrome does the same),
   but it means any process running as the user can replace `OSHI.exe` or a runtime DLL and
   the next launch runs it — and with no code signature there is no launch-time integrity
   check to notice. Worth stating in the threat model rather than discovering later.

---

### C6 — MEDIUM (LAN-adjacent; **off by default in `--ui`**) — mDNS discovery trusts an attacker-supplied address, and mesh identity is unauthenticated

**Where** `mesh/MdnsService.kt:236,300-309`, `mesh/MeshNode.kt:200-210,223-239,316-360`.

**Attacker input** Any host that can send a multicast packet on the local link (or an
attacker on the same coffee-shop Wi-Fi).

**What I verified.**

- `MdnsService` builds `DiscoveredService.host` from the **A record inside the response**
  (`hostAddresses[r.name]` at `:236`, consumed at `:304-309`), not from the packet's source
  address. The port comes from the SRV record. `MeshNode.onDiscovered` (`:200-210`) then calls
  `connectToPeer(svc.host, svc.port, pk)` unconditionally.
- Because `expectedKey != null` on that path, `startConnection` (`:241-247`) immediately sends
  `identityMessage()` — which contains the user's **full OSHI public key and display name** —
  before anything is authenticated.
- `handleIdentityExchange` (`:316-360`) takes `msg.senderPublicKey` at face value and writes
  `connections[key] = conn` and `routingTable[key] = RouteInfo(key, 1, now)`. There is no
  proof of possession of the private half, anywhere.

**Consequence.** A LAN attacker can (a) make the victim's machine open TCP connections to an
arbitrary IPv4 address and port of the attacker's choosing — a port-scanning and
connection-laundering primitive that reaches off the LAN, since nothing restricts the A
record to a local address; (b) receive the victim's OSHI address and display name at that
arbitrary host, which is a deanonymisation primitive that needs no OSHI account; and (c)
claim any peer's public key and thereby capture that peer's entry in `connections`/
`routingTable`, so mesh traffic addressed to them is handed to the attacker instead.

**Why it is MEDIUM and not HIGH.** `MeshProtocol.isForUs`'s doc comment already states that
this envelope's sender identity is unauthenticated plaintext and that nothing downstream may
treat it as authentication, and PARITY row 0.16 records that the mesh carries payloads this
client cannot open — so (c) leaks routing, not message content. Decisively: **`--ui` does not
start the mesh** (`ui/UiLauncher.kt:41,68,85`), and `--ui` is what a packaged install runs
(`build.gradle.kts` `--arguments --ui`). This is live only for `--client` and `--ui --mesh`.

**Fix** Require the A record's address to match the packet's source address (or at minimum
refuse a non-private address), and do not send `identityMessage()` until a peer has proved
possession of the key it claims.

---

### C7 — LOW/MEDIUM — The master key is handed to a `PATH`-resolved `powershell.exe` / `secret-tool`

**Where** `store/SecretStore.kt:164-196` (Windows), `:112-142` (Linux), `:294-302`
(`Proc.which`).

**What I verified.** `WindowsDpapiStore.put` passes the base64 master key to the child in
`OSHI_SECRET_IN`, and the binary that receives it is whatever `Proc.which("powershell.exe")`
finds first by walking `%PATH%` in order. Same shape on Linux: `secret-tool` is resolved off
`$PATH` and the secret goes to its **stdin**.

The file reasons very carefully about *where in the child* the secret travels — argv vs
environment vs stdin, with a verified note about `security` storing an empty password from a
pipe — and it is right on every one of those points. What it does not reason about is **which
binary** receives it. Anyone who can write to a directory that precedes `System32` on the
user's `PATH` (a surprisingly common state on Windows, since several installers prepend
per-user directories) plants a `powershell.exe` that both exfiltrates the master key on every
`put` and returns an attacker-chosen key on every `get`.

The class doc scopes out "malware already running as this user", and I accept that scoping —
but a *stale* writable `PATH` entry left behind by an uninstalled program is not the same
thing as live malware, and the fix is one line.

**Fix** On Windows resolve PowerShell as
`%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe` and fall back to `PATH` only if
that is absent. On Linux, prefer `/usr/bin/secret-tool`, as the macOS backend already does
for `/usr/bin/security` (`:78`).

---

### C8 — LOW — An attacker-chosen `blobId` goes unencoded into a path this client signs

**Where** `net/V2BlobClient.kt:38-40` (the claim), `:87,:96,:125,:136,:148` (the uses),
`net/V2Http.kt:144-146`.

**What I verified.** The class doc states: *"`blobId` is NOT percent-encoded — the blob store
does not decode its path segments and a blob id is always server-generated hex/UUID, **never
user input**"*. On the **download** path that is not true: `blobId` arrives inside a peer's
`V2FileKeyMessage` (`OshiClient.kt:1548`), and `V2FileKeyMessage.parse` validates only that
the field is present — it is an arbitrary attacker-chosen `String`. It is interpolated raw
into `"/v2/blobs/$blobId/chunk/$index"`, which is then both **signed with the victim's Ed25519
key** and sent.

**Consequence, bounded.** The authority is fixed by `baseUrl`, so there is no SSRF: the
request cannot leave the relay. The method is fixed to GET. `URI.create` sits outside the
`try` in `V2Http.getBytes:146`, so a malformed segment throws — but
`downloadAndDecryptToFile` catches `Throwable` (`V2BlobClient.kt:270`), so it does not reach
C2. What is left: if the relay normalises `..` in paths, a peer chooses which relay path the
victim's client issues an authenticated GET to. That is a small primitive, and it may be
worth nothing on today's server — but it is a primitive a peer should not have, and the
comment above it asserts it does not exist.

**Fix** Either percent-encode the segment or validate `blobId` against `[A-Za-z0-9._-]{1,128}`
at parse time, and correct the doc comment either way.

---

### C9 — LOW today, latent — Three unbounded/overflowing paths in code that has no caller yet

All three are real defects I confirmed by reading; all three are currently unreachable or
opt-in, which is the only reason they are not higher.

**C9a — Int overflow in the AVCC NAL walk.** `call/media/VideoFramePacket.kt:187`:

```kotlin
if (len <= 0 || o + 4 + len > frameData.size) return
```

`len` is a 32-bit big-endian length read from the frame. `o + 4 + len` is `Int` arithmetic
and wraps: with `len = 0x7FFFFFFF` and `o = 0`, the sum is `-2147483645`, which is not
`> frameData.size`, so the guard passes and `visit(type, 4, 0x7FFFFFFF)` fires at `:188`.
Callers then do `out.write(frameData, 4, 0x7FFFFFFF)` (`:252`) or
`copyOfRange(4, 4+len)` with a negative end (`:215-216`). Had the walk not thrown, `o += 4 + len`
(`:189`) leaves `o` negative and the loop re-enters with a negative index.
`VideoReceiveSession.onPacket` has no `try` around it. Use `len.toLong()` or
`len > frameData.size - o - 4`.

**C9b — `VideoReassembler.inFlight` grows without bound.** `call/media/VideoFragment.kt:154`
(the map), `:191-195` (the only eviction). Eviction removes an entry only when the arriving
frame is *newer* (`dist in 1..32768`). A peer sending fragments with strictly **decreasing**
`frameId` — 40000, 39999, … — evicts nothing, completes nothing, and never trips the `LATE`
guard at `:183` (which needs a completed frame first). Up to 65 536 entries, each with an
`arrayOfNulls<ByteArray>(255)` plus a retained payload. Needs a cap or a TTL.

**C9c — `LoRaReassembler.pending` has a TTL but no cap.** `lora/LoRaFrame.kt:243,257,263`.
A fresh random 32-bit `msgId` per frame mints an entry held for the full 180 s, and this
happens in `LoRaInbound.oshiChunk` (`lora/LoRaInbound.kt:143`) **before** `isAddressedToMe`
(`:145`) — no identity or contact relationship required. Over the air LongFast airtime
throttles it; over the **TCP** path (`LoRaLink.pump`, `lora/LoRaLink.kt:167`, a plaintext
unauthenticated socket to `meshtasticd`) a hostile or MITM'd bridge feeds it at line rate.
Needs a `MAX_PENDING` eviction alongside the TTL.

**Reachability, verified.** `VideoReceiveSession`, `VideoReassembler` and `CallAudioSession`
have **no production call site** — grep finds them only in `src/test`. This matches PARITY
row 2.1-c ("nothing sends any of these; the media ingress this lane belongs to does not exist
yet"). C9c needs `/lora attach` against a radio or a TCP bridge. Fix them before the lanes are
wired, not after.

*(C9a and C9b were located by a delegated read of the call/LoRa tree; I re-read and confirmed
both by hand. C9c I accepted on the delegate's reading plus the two line references, which I
checked — see §3.)*

---

### C10 — LOW — `LiveShareTracker.sessions` grows from an attacker-chosen `sessionId`

**Where** `place/LiveShareTracker.kt:108,132-145,166-170`, driven from
`app/OshiClient.kt:1432` and pruned only by `tick()` (`:551`).

`pruneEnded` removes a session only when it is `stopped` or already past its pinned expiry. A
peer sending `📍LOCATION📍` payloads with a distinct `sessionId` and a far-future `expiresAt`
adds an entry per message that nothing ever removes. Bounded by message rate and by process
lifetime, so it is slow — but it is unbounded, and it is the only in-memory map on the
message ingest path with no ceiling. A cap on `sessions.size` (evicting oldest) closes it.

---

### C11 — LOW — `Base64.decode` can throw out of the secret-store read paths

**Where** `store/SecretStore.kt:81`, `:127`, `:181`.

All three `get()` implementations end in `Base64.getDecoder().decode(out)` with no `try`. If
the helper's stdout is ever not clean base64 — a wrapped line, a locale/encoding artefact, a
diagnostic on stdout — the `IllegalArgumentException` escapes as itself rather than as the
`SecretStoreException` every caller is written to expect, and it escapes from inside
`VerifiedSecretStore.put`'s read-back too. This is a diagnosability defect rather than an
exploit, but it lands on the one code path where an unclear error means "your account will
not open".

---

## 2. SUSPECTED — not verified end to end

Listed because they are cheap to check, not because I am asserting them.

- **S1 — `Get-Content -Raw $env:OSHI_BLOB_PATH` is wildcard-expanding.** `store/SecretStore.kt:174`
  uses PowerShell's positional `-Path`, which globs `[`, `]`, `*` and `?`. `%APPDATA%` cannot
  contain brackets via a Windows username, but `OSHI_HOME` and a custom `%APPDATA%` can.
  I did not run PowerShell to confirm the failure mode. `-LiteralPath` removes the question.
- **S2 — `SqliteFile` allocates from an unchecked varint.** `place/SqliteFile.kt:205`:
  `payload = ByteArray(total.toInt())` where `total` is a payload-size varint from the file,
  with no upper bound (the overflow-chain walk that would notice the lie runs *after* the
  allocation). Both b-tree walks do have proper cycle detection (`:113`, `:209`), which I
  checked. Today this parses only offline map packs the user places in `<dataDir>/maps/` —
  I confirmed by grep that no region id or map path comes from the network — so it is a
  local-file robustness issue. It becomes a real finding the day map packs are downloaded
  or received.
- **S3 — `StackOverflowError` from deeply nested JSON.** `org.json`'s parser is recursive.
  Most untrusted parses are inside `runCatching` (which catches `Throwable`) — `DesktopEnvelope.fromJson`
  at `V2MessagesClient.kt:60`, `GroupUpdateWire.decodeDefinition:186`, `ControlRead.obj:149`.
  But `V2MessagesClient.pull:53` parses the relay's envelope under `catch (_: Exception)`. A
  hostile or compromised relay could reach C2 through it. I did not determine `org.json`'s
  actual recursion depth limit.
- **S4 — Three `HttpClient`s rely on the default redirect policy implicitly.** `V2Http:64` sets
  `Redirect.NEVER` with a security comment ("a signed request must not be replayed elsewhere").
  `V2ConfigGate:53`, `BotQueueClient:88` and `LegacySyncClient:73` do not set it and rely on
  `NEVER` being the builder default — which it is today. The invariant is correct and
  undocumented at three of four sites.
- **S5 — Linux keyring with an empty password.** On an auto-login desktop the GNOME login
  keyring is commonly created unlocked with no password, in which case `secret-tool` "storage"
  gives the master key no protection at rest at all. This is a property of the platform, not
  of this code, but the `SecretStore` doc's "protects the key against another *user* of the
  machine and against a stolen unencrypted disk" is not true in that configuration and the
  Linux backend is already 🟡 unverified (PARITY 0.5).

---

## 3. What I checked and found correct

Recorded so nobody re-reviews it, and so the report is not read as "only bad things exist".

- **Key handling.** `KeyVault.open` (`store/KeyVault.kt:128-181`) never degrades: a vault
  written under one scheme is never re-opened under the other; a missing master key with an
  existing vault **throws** rather than reading as empty; a failed decrypt throws rather than
  minting a new identity; with no OS store and no passphrase it refuses rather than writing
  anything readable. `VerifiedSecretStore` (`SecretStore.kt:219-243`) reads every write back,
  which is the check that catches the real failure. With PowerShell absent, `detect()` returns
  null and the passphrase path is demanded loudly — I traced this. With PowerShell *present
  but refusing* (Constrained Language Mode, AppLocker), `put` throws and the launcher prints
  "Cannot open this account". **Nothing falls back to plaintext, and nothing falls back
  silently.**
- **Crypto seams this client owns.** `SyncCrypto.seal` takes a fresh 12-byte `SecureRandom`
  nonce per call and the injectable parameter is test-only; `KeyVault.persist` mints a fresh
  nonce per write; both archive-key derivations mix only constants; `ArchiveKeys` refuses a
  zero-length ikm rather than deriving 32 plausible bytes from nothing; `SafetyNumber` is
  iOS's algorithm with the signed-byte trap handled (`:105-107`). No secret is compared with
  `==` anywhere I looked — `SafetyNumber.verify` and the TOFU checks compare *public* keys,
  where constant time buys nothing.
- **Transport.** `V2Http` disables redirects, sets connect and read timeouts, uses the JDK
  `HttpClient` (hostname verification on, and not disableable through its API), and there is
  **no custom `TrustManager`, `HostnameVerifier` or `SSLContext` anywhere in the tree** — I
  grepped. No `ObjectInputStream`, no `Runtime.exec`, no `Desktop.getDesktop()`, no
  `ScriptEngine`.
- **Authorisation.** The C-MSG-4 ownership check (`msg/ControlPayloadRouter.kt:352`) is byte
  equality on the transport-authenticated sender, correctly *not* normalised. The reaction
  path correctly uses the transport sender rather than the payload's `senderPublicKey`
  (`:326`). `GroupUpdateAuthorizer` + `GroupIngest` enforce standing, authority, self-in-roster
  *and* sender-in-roster, refuse the unauthenticated-sender branch, re-stamp the admin set so
  an introduced member cannot arrive promoted, and explicitly refuse iOS's auto-add on
  `member_sync_request` (`GroupIngest.kt:245-251`). `V2Router.establishResponder:322-324`
  enforces responder-side TOFU on the exact wire string.
- **Path handling.** `MessageStore.fileNameFor:179` is `sha256hex(conversationId)` — a group
  id or a `bot!`/`lora!` pseudo-address can never reach the filesystem. `receiveMedia`'s
  filename sanitiser (`OshiClient.kt:1545`) strips everything outside `[A-Za-z0-9._-]`, caps at
  120 characters, and the result is prefixed, so `..` cannot traverse. `WindowsDpapiStore.blob`
  sanitises the account the same way. **I found no path traversal.**
- **mDNS decoding.** `mesh/MdnsCodec.kt` is genuinely careful about hostile packets: every read
  is bounds-checked, compression pointers are capped at 64 jumps (the decompression bomb),
  rdata is resynchronised on the stated length, and a record it cannot model is kept opaque
  rather than guessed at.
- **Bot lane.** The three iOS behaviours that make an unauthenticated, server-visible payload
  trigger outbound fetches — `mediaURL`, media-in-content, and the regex image auto-download —
  are all deliberately **not** reproduced, and `bot/BotEnvelope.kt:118-135` says so by name.
  That is the right call and the reasoning is on the page.
- **Frame limits where they do exist.** `MeshProtocol.MAX_FRAME_BYTES` takes the stricter of
  the two shipped caps and `MeshFraming.readFrame` refuses out-of-range lengths rather than
  skipping and resyncing. `LoRaProto.parseFields`, `LoRaAttach.StreamFraming.decode`,
  `LoRaLink.pump` and `VideoFramePacket.decode`'s `spsLen`/`ppsLen` all bound their lengths.
- **`covert/TextSteganography`** has a `MAX_DECLARED_LENGTH` gate before `ByteArray(declared)`
  (`:398`) and, more to the point, **no production caller** — grep finds it only in tests.

---

## 4. Not reviewed — do not read this as full coverage

- **The shared crypto core.** `OSHICryptoV2`, `OSHICryptoV2Streaming`, `OSHIRatchetV2`,
  `V2Session`, `V2FileKeyMessage`, `V2RetryBudget` are compiled out of `OSHI-Android/` and are
  not this repository's code. I read `decryptToStream` only to establish that the missing size
  cap in C1 is missing *there too*. The ratchet, X3DH, AEAD and HKDF are **unreviewed**.
- **The relay and the call server.** Out of scope and not in this tree. PARITY already records
  that `/api/sync` has zero authentication, that `/v2/sync` in `dual` mode answers an unsigned
  request with `authed:false`, that the bot lane is cleartext, and that the call server has
  `SIGNATURE_REQUIRED = false`. Those are server properties; none was verified here.
- **The whole Compose UI** (`ui/components/*`, `ui/DesktopWindow.kt`), `ui/state/*` beyond the
  file-send path, `i18n/*`, `media/*` (audio capture, playback, transcode — I confirmed only
  that `AudioProc` uses a `List<String>` `ProcessBuilder`, so no shell and no argument
  splitting), `ai/*` and `DesktopLlmManager`, `WelcomeSeeder`, `ClientCli`'s 906 lines of REPL,
  `pairing/QrMatrix.kt` (encode-only, no untrusted input reaches it).
- **`mesh/MdnsService.kt` in full** — I read the discovery, address-resolution and receive-buffer
  parts for C6 and left the announce/probe machinery alone. **`mesh/MeshNode`'s relay
  behaviour** was read but not analysed as an amplification surface.
- **`place/OfflineMapData.kt` and `place/SqliteFile.kt` in full** — scanned for the patterns in
  S2 and for network-sourced paths; the SQL-text and schema parsers (`:528-650`) were not read
  line by line.
- **`sync/*`** — scanned for network-sourced paths, URL construction and allocation; the
  archive merge semantics and `ContactSyncRecord`'s field trust were not traced end to end.
- **`build.gradle.kts` beyond the jpackage block** — 60 KB of it. The **dependency supply
  chain was not reviewed at all**: no pinned-hash check, no audit of the second Maven
  repository (`dl.google.com`, content-filtered to `androidx.*`), no CI workflow review.
- **No dynamic analysis.** Nothing was built, run, fuzzed or pointed at a live relay. Every
  finding is from reading. The two allocation findings in C9 in particular would be trivial
  to confirm with a ten-line property test, and worth confirming that way before the fix.
- **Delegation.** The call and LoRa trees (C9) were first read by a delegated agent working
  from the same threat-model brief. C9a and C9b I re-read and confirmed by hand, including the
  overflow arithmetic and the eviction predicate; C9c I confirmed only to the extent of
  checking the three cited line numbers and the pre-`isAddressedToMe` ordering. A second
  delegated pass over `place/`, `sync/`, `bot/` and `covert/` failed to run, so I covered that
  ground myself by targeted reading rather than by full file-by-file review — that is the
  thinnest part of this review and S2 is where it shows.

---

## 5. If only three things get fixed

1. **C2** — `catch (t: Throwable)` on the poll body and keep the schedule alive. Three lines,
   and it turns every present and future ingest bug from "the app silently stops receiving
   messages forever" into "one message failed".
2. **C1** — a receive-side size ceiling, and a length cap before `readText()` on a contact
   card. The constant already exists in the file; it is simply only used on the way out.
3. **C3** — at minimum correct `DesktopPaths.kt:51-52` and write the missing paragraph in
   `MessageStore.kt`, and tell the user in the UI that message history is not encrypted at
   rest. A wrong comment about encryption is worse than no comment, because it is the one a
   reviewer stops at.

If a fourth slot exists, it is **C5.1**: publish a signed `SHA256SUMS` next to the download.
It costs nothing and it is the only integrity signal an early user will have.
