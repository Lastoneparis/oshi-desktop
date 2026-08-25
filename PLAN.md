# OSHI for Windows and Linux — assessment, recommendation, skeleton

**Date:** 2026-08-24 · **Status:** skeleton builds and runs, 35/35 parity tests green
**Scope of changes:** everything in this document lives in `/Users/HUGOMORICEAU/Documents/Genesis/OSHI-Desktop/`. Nothing in the iOS tree, the Android tree, or the server was modified.

---

## 0. The two findings that decide this

Before any option can be compared, two facts from the existing code have to be on the table, because between them they eliminate half the option space.

### Finding A — the crypto core is *already* platform-neutral

Six files in `OSHI-Android/app/src/main/java/com/oshi/messenger/network/v2/` contain **zero** Android imports:

| File | Android imports | What it is |
|---|---|---|
| `OSHICryptoV2.kt` | 0 | X3DH, ratchet KDFs, header codec, file AEAD, manifest |
| `OSHIRatchetV2.kt` | 0 | the Double Ratchet state machine |
| `V2Session.kt` | 0 | X3DH ↔ ratchet wiring |
| `OSHICryptoV2Streaming.kt` | 0 | streaming blob decrypt |
| `V2FileKeyMessage.kt` | 0 | the `v2file` media payload |
| `V2RetryBudget.kt` | 0 | undecryptable-envelope backoff |

They are pure JVM: BouncyCastle X25519/HKDF plus `javax.crypto` AES-GCM/HMAC. This was not an accident — the file header says so explicitly:

> *"Kept as pure JVM (BouncyCastle X25519 + javax.crypto GCM/HMAC + BC HKDF, no Android APIs) so its parity is verified on the JVM against the same known-answer vectors iOS ships."*

Android already compiles and runs these files on a desktop JVM in its own unit tests, against iOS's published vectors. **A decision made for testability has already paid for most of a third platform.** The remaining `network/v2` files are Android-coupled, but only at the edges — `android.util.Base64`, `android.util.Log`, `BuildConfig`, Hilt, SharedPreferences. Those are transport and storage seams, not protocol.

### Finding B — OSHI Web is not end-to-end encrypted, and cannot be the foundation

The web-session feature pairs a browser to the phone, and at first glance looks like most of a desktop client. It is not, for a reason that is architectural rather than incidental:

- **The browser holds no keys and performs no crypto.** `ServerVPS/Site_oshi/web.html` loads three scripts — a QR renderer, a scroll library, a cookie notice. No libsodium, no tweetnacl, no `crypto.subtle`. Its entire session state is a session id and the user's *public* key.
- **The phone decrypts and re-emits plaintext to the server**, which forwards it to the browser. Message bodies, media, contact keys and aliases all traverse `oshi-messenger.com` in the clear. The project's own security review flags this as `C-WEB-1`, and notes the settings UI *currently claims the opposite*.
- **The phone must be awake and online for anything to render**, because every byte the browser shows is generated on demand by the phone. On iOS a background push can wake it; on Android there is no `web_sync` handler at all, so the phone must already hold a live socket.
- **The relay's source is not in the repository.** `web_session_server.js` exists only as a manually-scp'd copy on one VPS, with no git and no staging. Every fact about that wire protocol is inferred from the two clients.

A desktop app is trusted *more* than a browser tab, not less. Shipping one on this surface would mean shipping a plaintext-to-server messenger, widening an already-flagged compliance exposure, and re-pairing every user later when the E2E work lands.

This does not make the web session worthless — it is a reasonable *convenience* surface, and its pairing UX is worth borrowing. It cannot be the security foundation of a desktop client.

---

## 1. The options, with the trade-offs stated

The decisive question is not which is nicest to build. It is **which one keeps a third platform byte-compatible with iOS and Android without tripling the parity work.** This project has repeatedly shipped features that were silently incompatible across two platforms; a third multiplies the pairs to check from one to three.

| | **(a) Extend OSHI Web into an installable desktop app** | **(b) Kotlin/JVM desktop reusing the Android core** | **(c) Electron/Tauri wrapping the web client** | **(d) Native per-platform (C++/C#/Swift/GTK)** |
|---|---|---|---|---|
| **Crypto reused as-is** | **None.** The browser does no crypto. | **All of it.** Six files compiled unmodified — X3DH, ratchet, file AEAD, `v2file`, retry budget. | **None.** Same as (a). | **None.** X3DH + Double Ratchet + file AEAD reimplemented per platform. |
| **Wire format reused** | None — a *different*, undocumented protocol. | Envelope/signer restated once (~200 lines), everything under the ciphertext shared. | None. | Everything restated, per platform. |
| **Must be rewritten** | Nothing — but nothing is E2E either. | Transport (OkHttp→any HTTP client), key storage, UI. | Nothing / everything, depending on whether you keep the plaintext model. | Crypto, wire format, transport, storage, UI — ×3. |
| **Parity surface added** | A 4th protocol nobody can diff against the other three. | **Near zero at the crypto layer** — a divergence is a compile error, not a runtime mystery. | Same as (a). | **Three new full protocol implementations.** Every trap in §4 must be re-avoided independently on each. |
| **Offline / phone-independent** | No — phone must be awake. | **Yes** — it is a first-class client with its own identity. | No. | Yes. |
| **E2E encrypted** | **No.** Server sees everything. | **Yes** — same guarantees as iOS/Android. | **No.** | Yes. |
| **Linux + Windows from one build** | Yes | **Yes** — one JVM artifact | Yes | No — three ports |
| **Maintenance cost** | Low code, **unbounded risk**: builds on a relay whose source is not in git. | **Lowest real cost.** Shared source means the Android team maintains the desktop crypto by maintaining their own. | Low code, same unbounded risk, plus an Electron runtime. | **Highest by far.** Roughly triples the parity matrix. |
| **Ships something quickly** | Fastest | Days for protocol, weeks for a real app | Fast | Slowest |

Three points deserve emphasis because they are easy to under-weight:

**Options (a) and (c) are the same option.** Both wrap `web.html`. The wrapper choice — installable PWA vs Electron vs Tauri — is a packaging detail on top of an architecture that is not end-to-end encrypted and does not work when the phone is asleep. They should be evaluated as one, and evaluated as a *companion* feature, not a client.

**Option (d) is the one that actually triples the parity work**, and the reports show exactly what that costs. Between just two platforms this project has shipped: a spoofable sender identity, photos destroyed on four iPhone launches in five, every Android→iPhone photo rendering as raw JSON, group captions invisible on iOS, a first message from a new contact silently dropped forever, and three mutually incompatible implementations of the same safety number. Each was found by a human reading two files side by side. Native ports would require doing that three ways, for every feature, forever.

**Option (b)'s advantage is structural, not stylistic.** Under shared source, a crypto divergence between desktop and Android is *impossible by construction* — there is only one copy of the code. That is a categorically stronger guarantee than "we ported it carefully and wrote tests."

### On Compose Multiplatform specifically

Option (b) as scoped here is **Kotlin/JVM**, and Compose Multiplatform is a UI choice layered on top, deferred deliberately. It is the natural fit and its artifacts are already in the local Gradle cache. But it should be adopted *after* the protocol layer is proven, for two reasons: it pulls in a large dependency tree that complicates offline builds, and the Android UI is Jetpack Compose married to Hilt, Room and Android ViewModels — the screens are not portable as-is regardless. The protocol is where the reuse is real; the UI reuse is a bonus that should not be allowed to gate the decision.

---

## 2. Recommendation

**Build a Kotlin/JVM desktop client that compiles the Android `network/v2` crypto sources directly, as shared source rather than a copy. Adopt Compose Multiplatform for the UI once the protocol layer is proven. Keep OSHI Web as a separate convenience surface and do not build the desktop client on it.**

The reasoning is short. The single hardest and highest-risk part of a third client — X3DH, the Double Ratchet, and the exact wire format — is not merely *portable* to desktop; it is **already running on a desktop JVM today**, in Android's own test suite, against iOS's published vectors. Choosing any other option means voluntarily rewriting the one component that is finished, and adding a third independent implementation to a codebase whose documented failure mode is precisely that implementations of the same protocol drift apart in silence. Under shared source the desktop client cannot disagree with Android about the ratchet, because there is only one ratchet; and the compiler enforces it on every build.

The one genuine cost is a build-time coupling between two repositories. That is a real drawback, and §5 says how to discharge it: extract the six files into a `:v2-core` Gradle module that both projects depend on. The skeleton deliberately does *not* do that, because doing so requires modifying the Android tree, which was out of scope for this pass.

---

## 3. The skeleton: what it does and how to run it

### Running it

```bash
cd /Users/HUGOMORICEAU/Documents/Genesis/OSHI-Desktop

./oshi.sh test                      # 76 parity tests (35 protocol + 41 mesh)
./oshi.sh run                       # the protocol demo
./oshi.sh run --args="--probe"      # ...plus ONE read-only GET to the live server
./oshi.sh run --args="--mesh"       # a live mesh node — see PLAN_MESH.md

# On Windows, platform/windows/oshi.cmd takes the same arguments.
```

`./oshi.sh` (and `platform/windows/oshi.cmd` on Windows) exists because this Mac has **no `java` on PATH** — only Homebrew JDKs under `/opt/homebrew/opt`. It locates a JDK, exports `JAVA_HOME`, and forwards to Gradle. On Linux and Windows `JAVA_HOME` is normally already set and `./gradlew` works directly.

Everything resolves from the local Gradle cache. Dependencies are pinned to exactly what OSHI-Android uses — **BouncyCastle `bcprov-jdk18on:1.76`** and **`org.json:json:20231013`** — because a different crypto provider version is a parity risk, not housekeeping.

### What it actually proves

`./oshi.sh run` prints, in order:

1. **iOS's known-answer vectors, reproduced on the desktop JVM.** X3DH shared secret, `KDF_RK` root and chain keys, `KDF_CK` message key, the derived AES key and 12-byte nonce, the AES-256-GCM ciphertext‖tag, and the file-chunk nonce and ciphertext. These are the same hex literals iOS asserts in `OSHICryptoV2Tests.swift`. Reproducing them is what byte-compatibility *means*.
2. **A full conversation.** Bundle trust (Ed25519 SPK signature over the raw 32-byte key, plus TOFU that the identity equals the address), responder-side TOFU that `x3dh.identityKey` equals the envelope's `from`, X3DH, then five messages delivered **out of order**, then a reply that forces a **DH ratchet** on both sides.
3. **A real `v=4` relay envelope**, emitted and re-parsed.
4. **The canonical request-signing string**, with a base64 identity percent-encoded into the path (`+ / =` → `%2B %2F %3D`).
5. **With `--probe`,** one unauthenticated `GET /v2/config` against `oshi-messenger.com`. It returned `{"v2_enabled":true,"rollout_percent":100,"min_build":0}` — V2 is live at 100%. Read-only; nothing on the server was changed.

### How the shared source works

`build.gradle.kts` adds the Android `network/v2` directory as a Kotlin source directory and includes exactly six files by name. **There is no copy step and no vendored duplicate.** The Android tree is read, never written.

This makes the build itself a tripwire: if anyone adds `import android.*` to one of those six files, the desktop build breaks. That red build is the alarm — the cheapest possible signal that the shared crypto core has stopped being shared. `SharedSourceTripwireTest` states the rule explicitly so the failure names its own cause.

### The test suite (35 tests)

| Suite | What it guards |
|---|---|
| `V2CryptoVectorTest` | iOS's known-answer vectors; manifest key order; the 40-byte big-endian header; the reserved manifest counter |
| `SessionRoundTripTest` | Out-of-order delivery, DH ratchet, drained OPK pool, mismatched OPK failing *loudly*, tampered header breaking the tag, no key/nonce reuse, file round-trip with truncation detection |
| `WireFormatTest` | Every item in §4, as an executable guard |
| `SharedSourceTripwireTest` | That the six shared files still exist, still have no Android imports, that the `FetchedBundle` copy has not drifted, and that the three KDF info strings are unchanged |

**These guards were watched failing, not merely observed passing.** Against a deliberately mutated copy of the Android tree, the tripwires caught an injected `import android.util.Log` and an added `FetchedBundle` field. Against four mutations of the desktop source — base64url encoding, `ts` in seconds, an explicit `"groupId":null`, and leaving `=` unencoded in identity paths — the suite failed on 7 tests.

That exercise also **found a real defect in the test suite itself**: the `ts` guard was asserting on `toJson()` rather than on the bytes actually sent, so it stayed green while the emitter divided the timestamp by 1000. It now asserts on `toWireBytes()` and has been confirmed to fail under that mutation.

### A real bug the skeleton surfaced

Building this exposed a genuine cross-platform hazard that would have bitten a desktop client on day one:

**The Maven `org.json:json` artifact backs `JSONObject` with a `HashMap`; Android's built-in `org.json` uses a `LinkedHashMap`.** Same class name, same `toString()`, different key order. The first envelope this skeleton emitted had its keys scrambled — the desktop was spontaneously reproducing the *exact* random-key-order hazard that already cost this project photos on four iPhone launches in five (§4.5).

Nothing on the wire strictly requires a key order, so this would not have broken messaging. It would have made every byte-for-byte comparison against an Android envelope fail at random, destroying the value of the parity tests. `DesktopEnvelope.toWireBytes()` now emits through an explicitly ordered writer, and a test pins both the order and the determinism.

### Where this Mac is **not** a proxy for Linux and Windows

Stated plainly, because a green build here is weaker evidence than it looks:

- **The JVM layer genuinely is portable.** BouncyCastle X25519, `javax.crypto` AES-GCM and HMAC-SHA256 are pure-Java or JCE-standard, with no native or OS dependency. The crypto results here will be identical on Linux and Windows. This is the part that carries.
- **Key storage is not solved and is per-OS.** The skeleton holds keys **in memory only**, which is right for a protocol demo and categorically not shippable. iOS uses the Keychain and Android the Keystore; the desktop equivalents are three different things — libsecret/kwallet on Linux, DPAPI or Credential Manager on Windows, and the Keychain again on macOS. **This is the largest unsolved problem in the whole project** and it has no shared answer.
- **Packaging and signing are entirely untested.** `jpackage`, MSIX/Authenticode on Windows, `.deb`/`.rpm`/Flatpak on Linux, and notarization on macOS are three separate pipelines, none exercised here.
- **File paths, line endings and case sensitivity** differ. Nothing in the skeleton depends on them yet; a real client will.
- **The mesh transport will not port cleanly.** The Bluetooth/Wi-Fi Direct stack is Android-specific. The reports note that BLE carries no message data on either platform — it hands over an IP and port, and real traffic is TCP over a shared LAN with 4-byte big-endian framing. A desktop machine on the same Wi-Fi could be a first-class mesh peer; one on cellular cannot. **That is the most portable part of mesh, and it is the part worth doing first.**
  > **DONE — see [PLAN_MESH.md](PLAN_MESH.md).** The LAN half is built: mDNS discovery plus the TCP framing, in pure `java.net`, no new dependency. Verified live against a shipped Android client (both directions) and against Apple's own responder. BLE is still not implemented, and the reason is written up there: it is a discovery path to the same socket, and it costs three native stacks to gain the no-Wi-Fi case.
- **Calls (WebRTC/audio pipeline) are not addressed at all.**

### What the skeleton deliberately does not do

No UI, no key storage, no message database, no blob upload/download, no groups, no calls, no mesh, no push. It proves the protocol, which is the part most likely to be got wrong silently.

> **Since this was written, mesh landed** — see [PLAN_MESH.md](PLAN_MESH.md). `./oshi.sh run --args="--mesh"` is a live node that discovers phones and is discovered by them. It moves OPAQUE payloads: connecting it to the ratchet above is still to do, and the CLI says so where someone would first be tempted to type a real message into it.

---

## 4. Parity risk list

The specific places a desktop client will silently diverge. Every item below is drawn from a divergence that **actually occurred** between iOS and Android. Each has a guard in `WireFormatTest` unless marked otherwise.

### 4.1 base64 vs base64url — the encoding that silently voids an address

Every wire key and blob is **standard alphabet, `=`-padded, no line breaks** (Android's `Base64.NO_WRAP`, Swift's `Data.base64EncodedString()`).

The asymmetry that makes this dangerous: **every iOS media decode is a bare `Data(base64Encoded:)` with default options**, which rejects `-`, `_`, missing padding *and* embedded newlines. Android's `Base64.DEFAULT` line-wraps at 76 characters. Choosing `DEFAULT` over `NO_WRAP` — which reads like tidying — silently destroys every media payload sent to an iPhone.

`base64url` appears in exactly three legitimate places: URL path segments, QR payloads, and the legacy delivery-receipt notify path. It must **never** appear in an envelope, an identity, a header, a ciphertext, or a media blob.

> **Rule: strict on emit, tolerant on ingest.** Emit standard+padded+unwrapped. When *comparing* identities, normalise both sides (`-`→`+`, `_`→`/`, re-pad) — iOS and Android reached this only after a member could be in a group roster and simultaneously fail `roster.contains(myKey)`.

### 4.2 Date encodings — four epochs, and the trap runs both ways

This is the richest source of parity bugs in the project. **Four** conventions are live:

| Payload | Encoding |
|---|---|
| v2 envelope `ts`, `x-oshi-timestamp` | **Unix epoch MILLISECONDS**, JSON number |
| Legacy IPFS inner payload `timestamp` | **Unix epoch SECONDS**, Double |
| Typing, reply, reaction, `🔧ACTION🔧`, `GroupMessage.timestamp`, location share, contact card, check-in, legacy ratchet header | **APPLE epoch** — seconds since 2001-01-01, Double (offset `978307200`) |
| `MessageGroup`: `createdAt`, `lastActivity`, `members[].joinedAt`, `groupPictureUpdatedAt`, `groupWallpaperUpdatedAt` | **ISO-8601 strings** |

The governing rule: a **bare** Swift `JSONDecoder()` has no `dateDecodingStrategy`, i.e. `.deferredToDate` — a number of seconds since 2001. Only where a decoder explicitly sets `.iso8601` is a quoted date correct. **Both appear, one file apart, inside the same Android class.**

Both directions are traps, and the brief's framing understates the second:

- Sending **Unix millis** where Apple epoch is expected does *not* throw on iOS. It decodes to roughly the year 58,000 and quietly drives an "(edited)" stamp or a location age.
- Sending **ISO-8601** where Apple epoch is expected throws `typeMismatch` and **sinks the entire payload**. For the contact card this produced a blank avatar with no name and no error.
- Sending a **number** where ISO-8601 is expected throws inside `MessageGroup` and discards the whole group definition, members array included (see 4.4).

> **Rule:** never guess from a field's name. Check whether the iOS decoder for *that specific payload* sets `.iso8601`. Where possible send a **duration**, not an instant — the progressive-reveal feature deliberately carries `revealTotalMs` with no timestamp at all, because clock skew across three platforms is unbounded. Both platforms also carry a `>1e10` magnitude guard to catch a mis-encoded value; a desktop client should too.

### 4.3 JSON key order — load-bearing in three places, and *randomly wrong* in a fourth

- **The file manifest** is AEAD plaintext with an exact key order and no whitespace: `{"filename":…,"mime":…,"size":N,"chunkCount":N,"chunkSize":N}`. Reordering changes the bytes a peer must reproduce. *(guarded)*
- **`V2FileKeyMessage`** matches the Swift struct's **declaration order**, with `nil` optionals omitted.
- **Request signatures** cover `SHA-256(exact body bytes)`. There is no canonical-JSON requirement — but **serialize once, hash that array, send that array.** Re-serializing between hashing and sending can reorder keys and earns a 401 that looks like clock skew. *(guarded)*
- **`org.json` differs between Maven and Android** — HashMap vs LinkedHashMap. See §3; the skeleton hit this immediately and now emits deterministically. *(guarded)*

### 4.4 Strict decoders — Swift dies on a *missing* key, kotlinx on an *extra* one

Both halves have cost this project whole messages, in opposite directions:

- **iOS→desktop:** Swift's `Codable` throws `keyNotFound` on a missing required field. The contact card lacked `timestamp` and every shared contact rendered as a blank avatar.
- **desktop→iOS:** kotlinx `Json.Default` has `ignoreUnknownKeys = false` and throws `SerializationException` on an unknown field. The same contact card, in reverse, rendered as raw JSON.

Three refinements that are easy to get wrong:

- **`decodeIfPresent` is not `try?`.** It tolerates *absence* but still throws on *type mismatch*. Six `MessageGroup` fields are decoded that way, and a wrong-typed value in any of them discards the entire group definition — **and its whole members array** — in silence.
- **kotlinx omits a property equal to its declared default.** A `timestamp` with a default would vanish from the wire on exactly the value that mattered, handing iOS back its `keyNotFound`. Build load-bearing wire bodies key by key.
- **On a pull, one throw takes the whole array.** A strict reader that chokes on one unknown field in one envelope loses every message in the response, not one. *(guarded)*

> **Rule:** emit exactly iOS's key set, in its declaration order, with absent optionals as **omitted keys** — never JSON `null`. Ingest with unknown fields ignored and defaulted accessors everywhere. And never route a payload into a handler that requires a different schema: a three-key `member_added` fed to a seven-key `created` handler threw `JSONException`, was swallowed by a broad catch, and silently never added the member.

### 4.5 Never route by prefix — parse, then branch

The most expensive bug in the project's history, and the one a desktop client is most likely to repeat.

iOS serialises some payloads from a Swift `[String: Any]` with no `.sortedKeys`. Dictionary iteration order is the per-process seeded hash order, so **the same binary emits a different key order on every launch.** Android routed media on `startsWith("{\"type\":")`, which holds for only 120 of the 720 orderings. Photos were destroyed on roughly **four launches in five** — and the failure was *masked*, because a separate summariser matched with `contains` and drew a tidy "📷 Photo" bubble over a row containing no image.

It was invisible in-house because Android's own sender writes `type` first, so Android↔Android passed 100% of the time.

> **Rule: never route, gate, hash, or compare a JSON payload by prefix or by serialized-string identity. Parse it, then branch.** *(guarded — the test permutes all 720 orderings)*

### 4.6 One shared JSON escaper

A hand-rolled escaper that handled `"` and `\n` but not `\` turned a caption of `C:\Users` into invalid JSON. iOS stored the whole base64 photo as a text bubble; Android dropped the message outright. Use `OSHICryptoV2.jsonEscape` — it covers `"`, `\`, `\b`, `\f`, `\n`, `\r`, `\t` and the C0 range. Never a `.replace()` chain. *(guarded)*

### 4.7 Request signing — four ways to earn a 401 that looks like clock skew

Canonical string, three literal newlines: `METHOD\nPATH\nBODYHASH\nTIMESTAMP`.

- **`encodeIdentity` percent-encodes everything that is not ASCII alphanumeric.** A base64 identity contains `+`, `/` and `=`; a normal URL encoder leaves `=` alone and turns a space into `+`. This breaks every identity-in-path route. *(guarded)*
- **The query string is excluded from PATH** but present on the URL. `/v2/messages/{id}` is signed; `?after=123` is not.
- **BODYHASH is lowercase hex.** Upper-case is a silent 401. *(guarded)*
- **The body-to-hash is not always the body.** JSON POSTs hash the exact bytes sent; GET and commit routes hash **zero** bytes; the verify-first blob/account routes hash the **two-byte** string `""`. *(guarded)*
- **Two identities, never conflated.** `x-oshi-signing-pubkey` is Ed25519; `x-oshi-user` is the X25519 address. *(guarded)*

### 4.8 Enums — an unknown value must degrade, never throw

There are **two different `mediaType` enums**: `image|video|audio|contact|document` for 1:1, and `photo|video|audio|document|contact` for groups. Using the 1:1 spelling in a group message hits Swift's synthesized enum decoder, which **throws and takes the whole message with it**.

The model to copy is the reaction handler: an unknown emoji maps to a default rather than throwing. The model to avoid is anything that puts an enum in a strict decoder's path — the reveal fields are deliberately read as **tolerant scalars**, where an absent key, a null, or a non-numeric value all read as "no reveal" and never throw.

**Still open today** (would bite a desktop client immediately): the legacy 1:1 envelope's `CONTACT` type renders as raw JSON on iOS, because iOS's allow-list stops at `DOCUMENT`. Emit **both** `type` and `mediaType` so either receiver's branch claims it.

### 4.9 Identity checks that must not be "helpfully" normalised

- **TOFU, both sides.** Initiator: the fetched bundle's `identityKey` must equal the peer address. Responder: `x3dh.identityKey` must equal the envelope's `from` — Android shipped without this, and anyone able to POST a relay envelope could bootstrap a session under a spoofed sender. **Byte equality on the exact wire string.** *(guarded, and demonstrated in the demo)*
- **Do not apply `canonicalIdentity`/`normalizeBase64Key` to these checks.** iOS re-pads and validates decodability; Android strips padding and never validates. They implement *different equivalence relations*, and neither belongs in a TOFU comparison or a safety-number sort.
- **The SPK signature is Ed25519 over the raw 32 bytes** of the SPK public key — not a JSON wrapper, not base64 text. *(guarded)*

### 4.10 Ratchet discipline — the failures that are permanent

- **Persist the advanced ratchet BEFORE the network send.** Two concurrent sends that re-derive the same message key produce the same `(key, nonce)` for different plaintexts — a catastrophic AES-256-GCM failure. Android's legacy ratchet had zero synchronization and silently rewound its chain. *(partially guarded — the skeleton asserts no key/nonce reuse; real persistence is not implemented)*
- **The X3DH header rides message #1 only.** Re-attaching it references an OPK the responder already burned; the SK then differs and the conversation becomes unrecoverable. Clear it on the first *send*, not on the first reply.
- **Never ack an envelope you could not decrypt.** Returning "handled" tells the relay to drop it, and the first message from a new contact is then gone forever. Fail under a retry budget (8 attempts, 500-entry cap). *(guarded — a mismatched OPK must fail loudly)*
- **Each `GET /v2/keys/:peer` pops a one-time prekey server-side.** Cache the bundle between the capability probe and the send, or every send burns two.
- **Publishing a prekey bundle is what makes iOS and Android route V2 to you.** Do not publish until the desktop receive path is proven on a real device, or messages will be routed to a client that cannot open them.

### 4.11 Numeric traps

- **Read bytes unsigned** when porting Swift `Data`/`UInt8` arithmetic. A missing `and 0xFF` in the audio waveform sent the same sample to the *floor* on iOS and the *ceiling* on Kotlin — and the bars still looked plausible.
- **Never key a cache or a filename by a `ByteArray.hashCode()`** (it is the identity hash), by a buffer's `toString()` (identical for any two payloads of the same length — one conversation's photo can appear in another's bubble), or by byte length.
- **Big-endian everywhere**: the `u32be` in ratchet headers and chunk nonces, and the 4-byte TCP framing on mesh. The rollout bucket is also big-endian — a little-endian read puts a desktop identity in a different cohort than the server believes. *(guarded)*
- **Treat peer-supplied filenames as hostile.** They reach `File(dir, name)`; a `../../` escapes the download directory.

---

## 5. Suggested next steps

1. **Extract `:v2-core`.** Move the six shared files into a standalone Gradle module that both OSHI-Android and OSHI-Desktop depend on. This removes the cross-repository path coupling and the `FetchedBundle` hand-copy. *Requires modifying the Android tree, so it was deliberately out of scope for this pass.*
2. **Solve key storage before anything else.** It is the largest unsolved problem, it has no shared answer across the three desktop OSes, and every other decision depends on where the identity lives.
3. **Port the transport seam:** `V2KeysClient`, `V2MessagesClient`, `V2BlobClient`, `V2Signer`, `V2SessionStore` — all thin, all mechanical, all covered by existing Android tests worth porting alongside.
4. **Prove receive on a real device pair before publishing a bundle** (see 4.10).
5. **Add a cross-platform vector CI job.** The three clients should assert the *same* vector file. Today iOS and Android each inline their own copy; a third inlined copy is how they drift.
6. **Treat OSHI Web separately.** If it is to remain, the `C-WEB-1`/`C-WEB-2` work (ephemeral browser key in the QR, server relays ciphertext, signed `mobile_auth`) should land on its own schedule, and `web_session_server.js` should get into git regardless of what happens with desktop.

---

## Appendix: files in this skeleton

```
OSHI-Desktop/
├── PLAN.md                      this document
├── oshi.sh                      launcher (finds a JDK; this Mac has none on PATH)
├── build.gradle.kts             the shared-source wiring + the tripwire rationale
├── gradle.properties            JDK path, Android tree path
├── settings.gradle.kts
└── src/
    ├── main/kotlin/com/oshi/desktop/
    │   ├── Main.kt              the five-step demo
    │   ├── DesktopIdentity.kt   X25519 address + Ed25519 signing key
    │   ├── DesktopWire.kt       the v=4 envelope, deterministically emitted
    │   ├── DesktopV2Signer.kt   canonical request string + encodeIdentity
    │   └── seams/FetchedBundle.kt   the one hand-copy, guarded by a test
    └── test/kotlin/com/oshi/desktop/
        ├── V2CryptoVectorTest.kt        iOS known-answer vectors
        ├── SessionRoundTripTest.kt      ratchet state machine
        ├── WireFormatTest.kt            §4 as executable guards
        └── SharedSourceTripwireTest.kt  keeps "shared source" honest
```

Compiled from the Android tree, unmodified: `OSHICryptoV2.kt`, `OSHICryptoV2Streaming.kt`, `OSHIRatchetV2.kt`, `V2Session.kt`, `V2FileKeyMessage.kt`, `V2RetryBudget.kt`.
