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

**This backend has never been run.** It is written, it compiles, and no Windows machine has
executed it. `PARITY.md` row 0.5 is amber for exactly this reason. `.github/workflows/desktop-ci.yml`
runs the suite on `windows-latest` with `OSHI_EXPECT_SECRET_STORE=DPAPI`, which turns "no key
store here" from a skipped test into a failure — so the first green Windows CI run is what
moves that row, and nothing else does.

## The console flag is load-bearing

`packageMsi` passes `--win-console`. That is not cosmetic. Today's entry points (`--client`,
`--mesh`) are interactive REPLs, and a jpackage launcher without that flag is a GUI-subsystem
binary with **no console attached**: the REPL reads EOF on its first line and exits, which
looks exactly like a crash on startup. When Tier 1 lands a real GUI, the flag comes off in the
same commit as the windowed entry point — not before.

## Building the installer

    gradlew.bat packageMsi        (on a Windows host; jpackage does not cross-build)

Requires **WiX Toolset v3** (`candle.exe` / `light.exe`) on `PATH`. jpackage 17 does not
understand WiX v4 or later, and the failure message it gives when WiX is missing does not say
so. Install v3 specifically.

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
