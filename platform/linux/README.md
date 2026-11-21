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

It has run: CI round-trips a real gnome-keyring on every Linux leg (`OSHI_EXPECT_SECRET_STORE=libsecret`),
and the PC smoke run creates and reopens profiles on it from the installed `.deb`.

`secret-tool` is NOT part of a stock Ubuntu desktop (`libsecret-tools`), so the `.deb` depends on
`libsecret-tools` and the `.rpm` on `/usr/bin/secret-tool` (1.6.1). Before that, every Ubuntu user
fell to the passphrase path.

### What happens with no keyring

A headless box, a container, an SSH session with no session bus, or a desktop whose keyring is
locked or refuses writes has no usable Secret Service. The client does **not** silently fall back
to a plaintext file: `isUsable()` returns false and the vault is protected by a passphrase — which
the WINDOW asks for (create + confirm on a fresh profile, unlock afterwards; `PassphraseWindow.kt`,
1.6.1), and the console client reads from `OSHI_PASSPHRASE`. The choice is recorded in the vault
and never flips under an existing profile. A wrong or missing master key **fails** — it never reads
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

## Desktop integration (1.6.1 Linux pass — what is handled, what is not)

| Area | Behaviour |
|---|---|
| No tray (stock GNOME) | closing the window QUITS (there is no way back otherwise); a second launch from the menu brings a hidden window forward |
| Notifications | `notify-send` first (the desktop's own notifications); clicking one opens its chat where libnotify ≥ 0.7.10 has `--action`; AWT tray balloon only as a fallback |
| HiDPI | X11: `Xft.dpi` ≥ 168 becomes an integer AWT scale (2–4) unless `GDK_SCALE`/`J2D_UISCALE`/`sun.java2d.uiScale` is set. Wayland: the compositor scales Xwayland (blurry at fractional scales — a JDK limit) |
| Fonts | Inter → Noto Sans → Ubuntu → Cantarell if installed, else fontconfig's sans-serif. `OSHI_UI_FONT=system` opts out |
| Calls | Java Sound through ALSA's `default` device → PulseAudio / PipeWire (both measured in CI). Only `default` is listed: pick devices in the system's sound settings. Webcam: v4l2, MJPEG 720p first; UVC metadata nodes are not listed |
| Sleep during calls | `gnome-session-inhibit` on GNOME, `systemd-inhibit` elsewhere |
| Autostart | `~/.config/autostart/oshi-desktop.desktop`, GLib-valid escaping, `TryExec` so it is skipped after uninstall |
| Links | `oshi://` via the `.desktop` MimeType; web links fall back to `xdg-open` where GTK is missing |
| Packages | `.deb` installs on Ubuntu 22.04+/Debian 12+ (`libasound2t64 \| libasound2`); arm64 builds and runs (CI) but is not released yet |

`.github/workflows/linux-qa.yml` exercises all of this on real runners (no keyring + no tray,
HiDPI, headless GNOME Shell, PulseAudio, PipeWire, v4l2loopback camera, Ubuntu 22.04/Debian 12/13
containers, Fedora, arm64).

## Files here

| | |
|---|---|
| `oshi.sh` | launcher — finds a distribution JDK and forwards to Gradle. |
| `packaging/` | jpackage icon and safe Debian lifecycle-script overrides. |
