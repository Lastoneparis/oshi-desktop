# OSHI for Windows and Linux

A desktop client for OSHI, written in Kotlin on the JVM. macOS already has a client — it is
the iOS app built with Mac Catalyst — so "desktop" here means **Windows and Linux**, with
macOS supported as a development host.

**This is not a finished app.** It is a client being brought to parity with the shipped
phones one capability at a time. There is a window now — `--ui` — carrying the shipped app's
own five destinations (Messages, AI, New, Places, More), 1:1 and group messaging, file
attachments, pairing by QR, contacts, safety numbers and blocking. The REPL is still the
full surface and reaches things the window does not. What is done, what is merely written,
and what has not been started are tracked honestly in **[PARITY.md](PARITY.md)** — read it
before assuming any feature exists.

---

## The one thing to know before you clone

**The crypto is not re-implemented here.** This project compiles seven Kotlin files
*straight out of the Android tree*, with no copy step:

```
app/src/main/java/com/oshi/messenger/network/v2/
    OSHICryptoV2.kt   OSHICryptoV2Streaming.kt   OSHIRatchetV2.kt
    V2Session.kt      V2FileKeyMessage.kt        V2RetryBudget.kt
app/src/main/java/com/oshi/messenger/service/
    LlamaCpp.kt
```

That is deliberate and it is the load-bearing idea of the whole port. Those files are pure
JVM — BouncyCastle X25519 plus `javax.crypto` — with zero `android.*` imports, because
Android wanted to vector-test them on a desktop JVM against iOS's published known-answer
vectors. A decision made for testability had already paid for most of a third platform.

The consequence: **the desktop client does not re-implement OSHI's crypto, and there is no
third implementation to keep in sync.** If someone adds an `import android.*` to one of
those files, this build breaks. That is the point — a red desktop build is the cheapest
possible alarm that the shared core has stopped being shared. CI enforces it directly.

### Two layouts, and the build finds either

**Standalone (this public repository).** The shared files are vendored under `shared/`, so
one clone builds and tests:

```
oshi-desktop/
├── src/  platform/  build.gradle.kts
└── shared/OSHI-Android/app/src/main/java/com/oshi/messenger/…   (the seven, plus two
                                                                  read-only references)
```

    ./gradlew test

**Beside the shipped trees (the monorepo).** If an `OSHI-Android` directory exists as a
sibling, it wins over `shared/` — so a developer with the monorepo checked out is always
compiling the live Android source, not a copy:

```
some-parent/
├── OSHI-Android/     (the shared crypto core — takes precedence when present)
├── OSHI/             (the iOS tree; read only by tests, to diff wire contracts)
└── oshi-desktop/     (this repo)
```

Override either with `-PoshiAndroidRoot=...` and `-PoshiIosRoot=...`.

### What a standalone run does NOT check

`./gradlew test` from this repository alone runs **1422 tests and skips 22.** The skipped
ones read the iOS and Android trees to diff wire formats byte for byte, and they guard
themselves into skipping rather than failing when those trees are absent. A skipped test
looks exactly like a passing one in a summary line, so CI prints the ran/skipped split on
every run and you should read it.

Two files under `shared/` — `V2KeysClient.kt` and `LocalLLMManager.kt` — are **read by
tests and never compiled**; the `kotlin.include` filter in `build.gradle.kts` names the
seven and omits these. They are here so the parity assertions that compare the desktop's
`FetchedBundle` and its ChatML prompt against the Android originals can still run.

## Build and run

Needs a JDK 17+. On Linux and Windows `./gradlew` usually works directly. Where it does not,
each platform has a launcher that finds a JDK and forwards to Gradle:

| | |
|---|---|
| `./oshi.sh` | macOS (searches Homebrew JDK paths) |
| `platform/linux/oshi.sh` | Linux (searches `/usr/lib/jvm`, names the package to install) |
| `platform/windows/oshi.cmd` | Windows |

```
./oshi.sh test                        the parity suite
./oshi.sh run --args="--client"       the client: account, relay, stores, mesh
./oshi.sh run --args="--ui"           the window (Compose desktop) — PARITY.md row 1.1
./oshi.sh run --args="--mesh"         a live mesh node: mDNS discovery + TCP
./oshi.sh run --args="--probe"        adds one read-only GET against the live server
```

`--ui` is an **additional** entry point, not a replacement. `--client` is still the fuller
surface. The window now does 1:1 and group messaging, file attachments, pairing (its own QR
and a paste box), contacts, renaming, safety numbers, blocking and unblocking, the offline
places reader and the local AI console. Reachable only from the REPL: creating and
administering groups, reactions, edits and deletes, voice notes, scheduled messages, sync,
LoRa, calls, bot posts and account deletion. The window says so itself, in its own "What
this client will not do" pane, which names every gap with the PARITY.md row behind it. Both
entry points share one account and one message store, so anything sent from one shows up in
the other.

    ./gradlew renderScreens               rasterise the window's screens to build/screens,
                                          with no display — so the panes can be LOOKED at
                                          on a runner as well as on a laptop

The window opens on **Windows** as well as on macOS aarch64: the `package / windows-latest`
CI job installs nothing, but it builds the app image, launches `OSHI.exe --ui` and requires the
process to still be alive 25 seconds later, which is what proves skiko's `windows-x64` native
loads. It has NOT been looked at on Windows — "the process did not exit" is a long way from
"it renders correctly", and no screenshot of this window on Windows exists.

## Five things this client does not do, stated out loud

Each of these is a real limitation, not a gap that is about to close quietly.

- **It does not work without internet.** The LAN mesh discovers peers and moves bytes, but
  it does not carry messages to a phone. The shipped mesh carries the *legacy* Double
  Ratchet, not V2, and its media is not end-to-end encrypted at all — so making the desktop
  talk to a phone over the mesh means porting a second ratchet with no published vectors.
  PARITY.md row 0.16 lays out the three options; none has been chosen. Until one is, no UI
  copy may claim offline messaging.
- **No push notifications.** APNs and FCM have no equivalent here, so the client *polls*
  `/v2/messages`. That is not the same thing: there is no wake-from-sleep delivery.
- **No screenshot blocking.** iOS has an OS affordance for it; Windows and Linux do not.
  Claiming it in the UI would be a lie.
- **It cannot sync with your phone.** Messaging a phone works — text crosses the live relay in
  both directions, verified against a shipped Android handset. *Multi-device* sync does not, and
  it is blocked twice: no shipped phone writes the `/v2/sync` archive this client reads (they use
  the legacy `/api/sync` blob), and this client has no way to adopt an existing identity, so it is
  always a different OSHI account with a different archive key. PARITY.md row 0.24 has the detail.
- **Against an Android peer, only the text arrives.** Delivery and read receipts, typing,
  reactions, edits, deletes, pins and profile updates are emitted by the Android app on the legacy
  IPFS lane only — the lane row 0.23 is deliberately ⛔ against — so none of them reach this
  client. An iPhone sends the same payloads "v2 first" and they would. PARITY.md row 0.18.

## Working rules

The rules this codebase is held to, in full, are at the bottom of [PARITY.md](PARITY.md).
The two that shape every file here:

- **Match the shipped bytes, not the shipped code.** Where iOS and Android disagree, the
  disagreement is recorded in the file's doc comment and the stricter option wins.
- **A guard that has not been watched failing is not a guard.** Every new test gets a
  mutation run — the guard is broken on purpose and watched going red before it counts.

Status in the ledger means what has been *verified*, never what has been written. `🟡` means
unit-tested but not yet exercised against a real phone or the live server, and it is used
liberally and on purpose.

## Layout

| | |
|---|---|
| [PARITY.md](PARITY.md) | the ledger — every capability, its status, and why |
| [PLAN.md](PLAN.md) | why the port is shaped this way; the options that were rejected |
| [PLAN_MESH.md](PLAN_MESH.md) | the LAN mesh, and what the phones actually put on the wire |
| [VIEWS.md](VIEWS.md) | all 102 macOS screens, with a portability verdict for each |
| [platform/windows/](platform/windows/) | everything Windows-only: DPAPI, the `.msi`, Authenticode |
| [platform/linux/](platform/linux/) | everything Linux-only: libsecret, `.deb`/`.rpm`, repo signing |

**`platform/` holds no Kotlin.** The client is one shared codebase under `src/`, compiled the
same way everywhere; what actually differs between Windows and Linux is only where secrets are
kept, how the app is packaged, and how it is launched. Copying source into a platform folder
would be the beginning of two clients that drift.

## Licence

MIT, the same as the rest of OSHI — see [LICENSE](LICENSE).
