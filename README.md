# OSHI for Windows and Linux

A desktop client for OSHI, written in Kotlin on the JVM. macOS already has a client — it is
the iOS app built with Mac Catalyst — so "desktop" here means **Windows and Linux**, with
macOS supported as a development host.

**This is not a finished app.** It is a headless client that is being brought to parity with
the shipped phones one capability at a time. There is no user interface yet. What is done,
what is merely written, and what has not been started are tracked honestly in
**[PARITY.md](PARITY.md)** — read it before assuming any feature exists.

---

## The one thing to know before you clone

**This repository does not build on its own.** It compiles six Kotlin files *straight out of
the Android tree*, with no copy step:

```
../OSHI-Android/app/src/main/java/com/oshi/messenger/network/v2/
    OSHICryptoV2.kt   OSHICryptoV2Streaming.kt   OSHIRatchetV2.kt
    V2Session.kt      V2FileKeyMessage.kt        V2RetryBudget.kt
```

That is deliberate and it is the load-bearing idea of the whole port. Those files are pure
JVM — BouncyCastle X25519 plus `javax.crypto` — with zero `android.*` imports, because
Android wanted to vector-test them on a desktop JVM against iOS's published known-answer
vectors. A decision made for testability had already paid for most of a third platform.

The consequence: **the desktop client does not re-implement OSHI's crypto, and there is no
third implementation to keep in sync.** If someone adds an `import android.*` to one of
those six files, this build breaks. That is the point — a red desktop build is the cheapest
possible alarm that the shared core has stopped being shared.

So you need the sibling trees checked out next to this one:

```
some-parent/
├── OSHI-Android/     (required — the shared crypto core)
├── OSHI/             (optional — the iOS tree; read only by tests, to diff wire contracts)
└── oshi-desktop/     (this repo)
```

Both live in the private `OSHI-private` monorepo. Override the locations with
`-PoshiAndroidRoot=...` and `-PoshiIosRoot=...` if your layout differs.

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
./oshi.sh run --args="--mesh"         a live mesh node: mDNS discovery + TCP
./oshi.sh run --args="--probe"        adds one read-only GET against the live server
```

## Three things this client does not do, stated out loud

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
