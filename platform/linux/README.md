# OSHI desktop on Linux

Everything in this directory is Linux-only. The Kotlin client itself is **not** here and must
not be copied here — it is one shared codebase under `src/`, compiled the same way on every
platform. What genuinely differs is small, and this is all of it: where secrets are kept, how
the app is packaged, and how it is launched.

## Key storage — libsecret

`SecretStore.detect()` (`src/main/kotlin/com/oshi/desktop/store/SecretStore.kt`) falls through
to `LinuxSecretToolStore` here. It shells out to `secret-tool`, which talks to whatever
implements the freedesktop **Secret Service API** over the session D-Bus — GNOME Keyring and
KWallet both do.

`secret-tool` reads the secret from **stdin**, never from argv or the environment, so the
master key does not appear in `/proc/<pid>/cmdline` or in another user's `ps` output.

**This backend has never been run.** It is written, it compiles, and no Linux machine has
executed it. `PARITY.md` row 0.5 is amber for exactly this reason.

### What happens with no keyring

A headless box, a container, or an SSH session with no session bus has no Secret Service. The
client does **not** silently fall back to a plaintext file: `isUsable()` returns false and the
vault asks for a passphrase instead. A wrong or missing master key **fails** — it never reads
as an empty vault, which would look like a fresh install and quietly discard an identity.

### Making the keyring work in CI or a container

`.github/workflows/desktop-ci.yml` does this on `ubuntu-latest`, and the same steps work on any
bare box:

    sudo apt-get install -y gnome-keyring libsecret-1-0 libsecret-tools dbus-x11
    eval "$(dbus-launch --sh-syntax)"
    eval "$(printf '' | gnome-keyring-daemon --unlock --components=secrets --daemonize | sed 's/^/export /')"

`dbus-launch`, not `dbus-run-session`: the latter wraps a single command and its bus dies with
it, so it cannot be shared across steps.

CI then **proves** the keyring works with a `secret-tool` round trip before Gradle is invoked,
and runs the suite with `OSHI_EXPECT_SECRET_STORE=libsecret` so a job that never started its
keyring fails instead of skipping three tests and going green. The first green Linux CI run is
what moves row 0.5, and nothing else does.

## Building the installers

    ./gradlew packageDeb        (needs dpkg-deb + fakeroot)
    ./gradlew packageRpm        (needs rpm-build)

Both require a Linux host — jpackage does not cross-build. `./gradlew packageAppImage` needs
neither and produces something you can actually launch, which is the cheaper smoke test.

## Signing — required before a production repository

The build embeds the MIT license, OSHI website, package release and maintainer metadata, but it
does **not** possess the organisation's OpenPGP private key and therefore cannot sign packages.
Debian repository signing is a detached OpenPGP signature over the apt `Release` file
(`gpg --clearsign` / `debsign`); RPM signing is `rpm --addsign` with a GPG key whose public half
users must import. A bare `.deb` or `.rpm` downloaded from a release page is unsigned. Do not
describe Linux distribution as production-ready until the signed APT/DNF repository and its key
rotation process exist.

## Launching

`./gradlew` normally works directly — on most distributions `JAVA_HOME` is already set or a JDK
is on `PATH`. `oshi.sh` here is for the cases where it is not; it looks in the usual
distribution JDK locations and gives a package-manager hint rather than a stack trace.

The `oshi.sh` at the repository root is the **macOS** development launcher (it searches
Homebrew paths) and is not used on Linux.

## Files here

| | |
|---|---|
| `oshi.sh` | launcher — finds a distribution JDK and forwards to Gradle. |
| `packaging/` | jpackage icon and safe Debian lifecycle-script overrides. |
