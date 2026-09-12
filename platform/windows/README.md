# OSHI desktop on Windows

Everything in this directory is Windows-only. The Kotlin client itself is **not** here and
must not be copied here — it is one shared codebase under `src/`, compiled the same way on
every platform. What genuinely differs between Windows and Linux is small, and this is all
of it: where secrets are kept, how the app is packaged, and how it is launched.

## Key storage — DPAPI

`SecretStore.detect()` (`src/main/kotlin/com/oshi/desktop/store/SecretStore.kt`) picks
`WindowsDpapiStore` on this platform. It wraps the master key with the Data Protection API,
which ties it to the logged-in Windows account — no passphrase prompt, and no key material
in a file an attacker can copy to another machine and read.

**This backend HAS now been run, on a real Windows box.** `.github/workflows/desktop-ci.yml`
runs the suite on `windows-latest` with `OSHI_EXPECT_SECRET_STORE=DPAPI`, which turns "no key
store here" from a skipped test into a failure. On that runner `SecretStoreTest > the real OS
store round trips a master key` and `> a vault backed by the real OS store survives a reopen`
both PASS, and the packaged launcher prints `keys : Windows DPAPI` on startup. The two tests
that still skip there are the POSIX-only ones (argv exposure, process kill), which is correct.

What that does NOT cover: a domain-joined or roaming profile, and what happens to the blob when
Windows rotates or resets the user's credentials. DPAPI is tied to the account, so a password
reset performed by an administrator (rather than by the user) can make the blob undecryptable —
the vault then refuses to open rather than minting a fresh identity, which is the right
behaviour and an unpleasant surprise all the same. No runner can test that.

## Two launchers, and the console flag moved to the second one

Tier 1 landed, so `--win-console` came off the main launcher exactly as this file used to
promise it would. The default launcher is a GUI-subsystem binary that opens the window
(`--arguments --ui`), so nothing flashes behind it.

The REPL was not lost: it is a second launcher, `OSHI-Console`, and it keeps `win-console=true`
because the original reason still holds. A jpackage launcher with no console attached makes the
REPL read EOF on its first line and exit, which looks exactly like a crash on startup.

Both launchers carry the app icon. An add-launcher does **not** inherit the main one's
`--icon`, and jpackage looks its icon up under the LAUNCHER's name — `OSHI-Console.ico`, not
`OSHI.ico` — so a single icon would have branded one Start-menu entry and left the other
generic.

## Building the installer

    gradlew.bat packageNative -PappVersion=1.2.3   (on a Windows host; jpackage does not cross-build)

That builds both: `packageExe` — what a person downloading a messenger double-clicks — and
`packageMsi`, what an IT department deploys. Same app image inside each. `-PappVersion` is not
optional: the project version is `0.0.1` and the build refuses to relabel an installer with a
version the project does not have.

Requires **WiX Toolset v3** (`candle.exe` / `light.exe`) on `PATH`. jpackage 17 does not
understand WiX v4 or later, and the failure message it gives when WiX is missing does not say
so. Install v3 specifically.

**This has run.** The `package / windows-latest` CI job has produced `OSHI.exe` and `OSHI.msi`
and then LAUNCHED the app image on that runner: the crypto self-test reproduced iOS's vectors,
the REPL printed an account address, and the window was still alive 25 seconds after start —
which is the only evidence that skiko's `windows-x64` native loads at all. That job is gated
`needs: test`, so it produces nothing while the suite is red.

## Signing — a documented gap, not a feature

Nothing here is signed, and no credential is invented anywhere in this repository.

Authenticode requires an OV or EV code-signing certificate from a CA. Since June 2023 the
private key must live on hardware — a FIPS 140-2 Level 2 token or a cloud HSM such as Azure
Trusted Signing or DigiCert KeyLocker — so it **cannot** be a `.pfx` in a GitHub secret.
Signing would be `signtool sign /fd SHA256 /tr <RFC3161 timestamp URL>` over both the launcher
`.exe` inside the app image and the finished `.msi`.

Until that exists, every early downloader sees SmartScreen's "Windows protected your PC".
Reputation is per-certificate and accrues with downloads, so a new certificate shows the
warning for a while even once signing is in place. The artifacts CI produces are for testing,
not for release.

## Updates

Not implemented. An `.msi` with a stable `--win-upgrade-uuid` (this build has one) upgrades in
place when a higher version is installed, so the groundwork is there — but there is no update
*check*, no feed, and nothing that downloads a newer build.

## Files here

| | |
|---|---|
| `oshi.cmd` | launcher — finds a JDK and forwards to Gradle. Same arguments as `oshi.sh`. |
| `packaging/` | jpackage `--resource-dir` overrides. Empty today; see its README. |
