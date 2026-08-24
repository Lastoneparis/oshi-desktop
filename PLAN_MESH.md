# OSHI mesh on Windows and Linux — what was built, and what it was checked against

**Date:** 2026-08-24 · **Status:** working node, 76/76 tests green, verified live against a shipped Android client and against Apple's own responder
**Scope:** everything lives in `OSHI-Desktop/`. The iOS tree, the Android tree and the server were read, never written.

This continues `PLAN.md`, which built the protocol skeleton (X3DH, ratchet, v2 relay envelope) and named the next piece:

> *"The mesh transport will not port cleanly. […] BLE carries no message data on either platform — it hands over an IP and port, and real traffic is TCP over a shared LAN with 4-byte big-endian framing. A desktop machine on the same Wi-Fi could be a first-class mesh peer […]. That is the most portable part of mesh, and it is the part worth doing first."*

That is what this pass built.

---

## 1. What now works

A desktop machine on the same network as a phone is a **first-class OSHI mesh peer**: it is discovered, it discovers, it connects, it identifies itself, it relays for its neighbours, and it moves messages.

```bash
# macOS / Linux                      # Windows
./oshi.sh test                       oshi.cmd test
./oshi.sh run --args="--mesh"        oshi.cmd run --args="--mesh"
```

`--mesh` starts a node and prints what it sees. `--connect host:port` adds a peer by address (for a network where multicast is blocked), `--no-discovery` runs the TCP half alone, `--seconds N` runs non-interactively.

```
OSHI mesh node
  identity   : U8RGohoYTxQGtWs6…
  instance   : OSHI-U8RGohoY._oshi-mesh._tcp.local.
  host record: oshi-4761265c.local.
  platform   : desktop
mesh: TCP server on port 54455
mDNS joined on en0 (192.168.1.11)
mesh: discovered hugo (android) at 192.168.1.26:45779
mesh: connected to 192.168.1.26:45779
mesh: identity hugo (android) 2wLYrJr299p2…
mesh: announce hugo (android) hops=1 via 2wLYrJr299p2…
<< GROUP_UPDATE from hugo (android): 📢GROUP_UPDATE📢{"type":"sync_request", …}
```

Every line after `mDNS joined` is a real Android phone running the shipped OSHI app.

### The files

```
src/main/kotlin/com/oshi/desktop/mesh/
├── MeshProtocol.kt   constants + the two comparison rules (tolerant key lookup, "is this for me")
├── MeshMessage.kt    CrossPlatformMessage: deterministic emit, tolerant parse
├── MeshFraming.kt    4-byte big-endian length prefix, and the two ways it desyncs
├── MdnsCodec.kt      DNS/mDNS/DNS-SD wire codec — pure functions, no sockets
├── MdnsService.kt    the responder + browser: multicast sockets, interfaces, timing
├── MeshNode.kt       the node: TCP server/dialler, identity exchange, routing, relay, gossip
└── MeshCli.kt        `--mesh`
```

No new dependency. Everything is `java.net` + the JDK, alongside the BouncyCastle/org.json pair the skeleton already pinned to Android's versions.

---

## 2. Why mDNS instead of BLE

The shipped mesh discovers peers three ways — MultipeerConnectivity (iOS↔iOS only), BLE, and Bonjour/mDNS — but **BLE carries no message data on either platform.** It advertises a 16-bit service UUID and serves one GATT characteristic whose value is a JSON blob containing an IP and a port; everything after that is the same TCP socket Bonjour would have produced.

So the choice is not "BLE or mDNS" as transports. It is: which *discovery* path does a desktop client implement to reach the same TCP socket. mDNS wins on every axis that matters here:

| | BLE from the JVM | mDNS from the JVM |
|---|---|---|
| Implementations needed | 3 (BlueZ/D-Bus, WinRT, CoreBluetooth) | 1 |
| New dependencies | a native stack per OS | none — `java.net` |
| OS permission prompts | yes, per platform | none |
| Already spoken by both shipped clients | yes | **yes** |
| Reaches the phone when there is no Wi-Fi | **yes** | no |

The last row is the real cost and it should be stated plainly in any user-facing copy: **two phones in a field with Bluetooth on still mesh; a laptop needs the local network.** A laptop is also the device least likely to be in that scenario. Adding BLE later changes only which discovery event fires — the transport, the framing, the routing and the tests below are unaffected.

---

## 3. What was verified, and how

The distinction that matters: **what a test proves about this code** versus **what another implementation actually did with it.** Both are here, labelled.

### 3.1 Live, against the shipped Android client

A phone on this LAN running OSHI was discovered, dialled, and held a conversation:

| Direction | What happened | What it proves |
|---|---|---|
| desktop → phone | our mDNS browser resolved `OSHI-2wLYrJr2`, TXT and all, and dialled its port | our DNS-SD decoder reads Android's NsdManager output |
| desktop → phone | we sent `IDENTITY_EXCHANGE`; the phone answered with its own | **Android's parser accepts frames this client emits** |
| phone → desktop | the phone sent `IDENTITY_ANNOUNCE` and a real `GROUP_UPDATE` sync request addressed to our key | our framing, JSON reader and routing accept live Android traffic |
| phone → desktop | on a later run the phone **dialled us first**, before we had discovered it | **our mDNS advertisement is visible to Android's resolver** |

### 3.2 Live, against Apple's mDNSResponder — the same responder iOS runs

```
$ dns-sd -B _oshi-mesh._tcp
Add  2  14 local.  _oshi-mesh._tcp.  OSHI-rmhnc9sL

$ dns-sd -L OSHI-rmhnc9sL _oshi-mesh._tcp
OSHI-rmhnc9sL._oshi-mesh._tcp.local. can be reached at oshi-f5736966.local.:57096
 pk=rmhnc9sL7gqn1x+8AF9/adpXm6JJ2BhnKelQDPx4Z2o= name=MacDesktopWitness platform=desktop

$ dns-sd -G v4 oshi-ae7bba99.local
Add  40000002  14  oshi-ae7bba99.local.  192.168.1.11  120
```

Browse, resolve and address lookup all succeed through Apple's stack. This is the closest available proxy for "an iPhone would find this node"; it is not the same as an iPhone finding it, and §6 says so.

### 3.3 The test suite — 76 tests, 0 failures, 0 skipped

41 of them are new and cover the mesh. Two are worth calling out because they are evidence rather than self-consistency:

- **`re-emits the shipped Android frames byte for byte`.** Three frames captured off a real TCP mesh connection with the phone are parsed and re-emitted by our writer, and the bytes must come back identical. That single assertion covers key order, the integer-millisecond timestamp, escaping, the present-but-empty `recipientPublicKey`, and whitespace — all at once, against a shipped implementation rather than against our own idea of one.
- **`MdnsCodecTest`'s two fixtures** are real packets: one from Apple's mDNSResponder, one from a shipped Android client. Identifying values were replaced with **same-length** placeholders so every compression pointer still resolves; nothing structural was touched.

The tests also read the two shipped sources at run time and fail if either changes shape: the required key list is re-derived from the Swift `Codable` struct and from the Kotlin data class on every run, the same way `SharedSourceTripwireTest` guards the crypto core.

### 3.4 Every guard was watched failing

Fourteen deliberate mutations were injected one at a time, the suite run, and the source restored and verified byte-identical by hash. **Thirteen went red immediately. One did not, and that is the useful result:**

> The concurrency test — "concurrent writers never interleave a frame" — **passed with the lock removed from `writeFrame`.** Its reader ran on a second thread, the corrupt frame threw *there*, the thread died, and the main thread's final assertion (`!reader.isAlive`) was satisfied **by** that death. A test that passes when the thing it tests is deleted is not a weak test, it is no test. It now counts the frames it read and re-throws the reader's failure on the main thread, and it has been watched going red with the lock removed — twice, and green with the lock, three times.

> A second harness bug surfaced on the way: the rewritten test used a `PipedInputStream` and flaked with `IOException: Write end dead`. `PipedInputStream` records only the **last** thread that wrote to it and throws when that thread exits while the buffer is momentarily empty and other writers are still running — an artefact of the harness, not of the code under test. It now runs over a real loopback socket, which is what the code actually runs on. A red test that means nothing costs as much trust as a green one that means nothing.

The full list, all now red under mutation: exponential timestamp · omitted empty recipient · swapped key order · hand-rolled escaper · unsynchronised writes · zero/negative frame length accepted · broadcast fallback · missing relay loop guard · missing hop ceiling · stale routes after a disconnect · duplicate delivery · cache-flush on a shared PTR · empty TXT with zero-length rdata · name labels split on dots.

---

## 4. Decisions that are wire-visible

### 4.1 `platform` is `"desktop"` — not `"ios"`, not `"android"`

Not a naming preference; `"ios"` would make this client **invisible to every iPhone**. iOS skips any peer whose platform is `"ios"`, at both gates — Bonjour resolve (`CrossPlatformMesh.swift:1619`) and `IDENTITY_EXCHANGE` (`:696`) — because iOS↔iOS mesh belongs to MultipeerConnectivity.

`"android"` would work today and is a lie that costs the truth on both sides: iOS routes calls by `platform == "android"` in six places in `VoiceCallManager.swift`, and would offer a desktop peer an Android call path this client does not implement.

Every platform gate in both trees was read before choosing. **Neither platform gates message delivery on it.** Two cosmetic consequences, both worth fixing on the phone side before a desktop client is announced:

- iOS `PeersView.swift:350` badges peers with a two-way ternary — `platform == "android" ? "Android" : "iOS"` — so a desktop peer is labelled **"iOS"** on the iPhone's Peers screen.
- Android's `MeshBoosterManager` counts `androidPeers` and `iosPeers`; a desktop peer is counted in neither, so the gamification totals under-report.

### 4.2 Inbound frame ceiling: 16 MiB, the stricter of the two

The shipped clients disagree — Android rejects over 16 MiB (`CrossPlatformMesh.kt:577`), iOS over 100 MiB (`CrossPlatformMesh.swift:643`) — so a frame between the two is accepted by an iPhone and kills an Android connection. We take Android's. Anything that large is a desynced stream, not a message: the send path caps media at 10 MB.

### 4.3 The instance name is not an identity

The phone advertises **two** instances of the service — `OSHI-2wLYrJr2` and `OSHI-2wLYrJr2 (2)` — because Android's NSD hit a name conflict and published the renamed form without withdrawing the first. Same key, same host, same port, two names. Everything here keys on the TXT `pk`; the label carries only 8 characters of a public key and is a display string.

### 4.4 Only routable addresses are advertised

Found by resolving our own node through Apple's stack: announcing on a second interface that held only a self-assigned `169.254.x` address made the resolver return

```
169.254.228.125, 192.168.1.11, 169.254.217.147
```

with the **unroutable APIPA address first** — and iOS takes the first IPv4 in that list (`CrossPlatformMesh.swift:1585-1596`) and dials it. A phone would have timed out and the desktop peer would simply have looked "not connectable". A distinct hostname per interface cannot fix it (the SRV is a unique record carrying the cache-flush bit; two SRVs for one instance erase each other), so the fix is in address selection: link-local addresses are advertised **only** when a machine has nothing else, which is exactly the crossover-cable / ad-hoc case where they are the right answer.

---

## 5. What this pass changed outside the mesh package

The build could not run on Windows or Linux at all. Three blockers, all fixed:

| Was | Now |
|---|---|
| `gradle.properties` pinned `org.gradle.java.home=/opt/homebrew/opt/openjdk@17/...` — a path on exactly one machine; Gradle failed before starting anywhere else | removed; the JDK comes from `JAVA_HOME` (put a machine-specific pin in `~/.gradle/gradle.properties` instead) |
| `oshiAndroidRoot=/Users/HUGOMORICEAU/...` absolute | defaults to a sibling directory, overridable with `-PoshiAndroidRoot=` |
| **no `gradlew.bat`** — the wrapper had no Windows entry point | regenerated with `gradle wrapper`; `oshi.cmd` added alongside `oshi.sh` |

---

## 6. What is NOT done, and what is NOT proven

**Not implemented**, in rough order of what a real client needs next:

- **Payload encryption.** This node moves opaque payloads. On the shipped clients `payload` arrives already encrypted by the layer above; wiring `V2Session` (from `PLAN.md`) into it is the next real step. **Until then this is a protocol tool, not a messenger** — the CLI says so where someone would first be tempted.
- **Key storage.** Identity is generated per run, in memory. Unchanged from `PLAN.md`, where it is named the largest unsolved problem, and it is a per-OS one (libsecret/kwallet, DPAPI/Credential Manager, Keychain).
- Media, documents, location payload handling; groups; calls; BLE; the MultipeerConnectivity stack; packaging and signing.

**Not proven**, and the difference matters:

- **No iPhone was in the loop.** Apple's mDNSResponder found and resolved this node, which is the discovery half; the TCP half was verified against Android only. An iPhone in the same room is the missing check, and it is cheap to run.
- **No Windows or Linux machine ran this.** Everything here is `java.net` with no platform branch, and the two portability hazards were handled deliberately — per-interface group joins rather than a default route, `SO_REUSEADDR`+`SO_REUSEPORT` so binding 5353 coexists with the OS responder (mDNSResponder, Bonjour Service, avahi-daemon). But "should port" is not "was run". The first Linux/Windows run should check exactly two things: that the bind succeeds next to the system responder, and that `advertisableInterfaces()` picks the adapter you expect on a box with docker0 / WSL / three virtual adapters.
- **One phone, one network, one afternoon.** Not a busy conference Wi-Fi, not a network with multicast filtering, not a router with client isolation on — where the honest answer is that mDNS discovery fails and `--connect host:port` is the fallback.

---

## 7. Defects found in the shipped clients

Found while reading the two trees for parity. None are mine to fix; each is real:

1. **iOS's content switch omits three message types Android sends.** `CrossPlatformMesh.swift:685` routes `TEXT_MESSAGE`, `MEDIA_MESSAGE`, `CALL_SIGNAL`, `CALL_AUDIO`, `RELAY`, `LOCATION_MESSAGE`, `DOCUMENT_MESSAGE`. Android also sends **`GROUP_UPDATE`**, **`GROUP_MESSAGE`** and **`PUBLIC_GROUP_AD`** over this transport — and one of those was the first content frame this desktop node ever received from a real phone. On iOS they hit `default:` and are logged and dropped. Group sync between an Android phone and an iPhone over mesh cannot work today.
2. **iOS's `sendOrRelay` looks up the recipient with strict equality** while Android normalises base64url/padding skew first (`CrossPlatformMesh.kt:764`). The skew is real enough that Android carries dedicated code for it; on iOS the same skew silently produces "no route".
3. **The 16 MiB / 100 MiB frame-cap disagreement** in §4.2 — a frame in that band is a dropped Android connection.
4. **Duplicate connections on simultaneous discovery.** Both clients skip dialling a peer they already have a connection to, and neither breaks the tie when both dial at once. Bounded (identity exchange registers the last one) and copied here rather than unilaterally fixed, since a tie-break rule is only worth anything if all three clients land it together.

---

## 8. Suggested next steps

1. **Put an iPhone in the room.** Run `--mesh`, watch the Peers screen. It is the one witness this pass could not get, and everything else builds on discovery working.
2. **Wire `V2Session` into the payload**, so the desktop node carries ciphertext rather than text. This is where the two halves of the project meet.
3. **Run it on Linux and Windows**, checking the two things named in §6.
4. **Fix iOS's message-type switch** (§7.1) — smallest, highest-value change on the phone side, and it is a shipped-platform bug independent of desktop.
5. **Then key storage** (`PLAN.md` step 2), because nothing can be a real client without it.
