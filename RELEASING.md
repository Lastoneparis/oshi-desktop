# Releasing OSHI desktop (Windows + Linux)

Three places are involved, and a release touches them in this order:

| Where | What lives there |
|---|---|
| **Private monorepo** `Lastoneparis/OSHI-private` (`~/Documents/Genesis`) | The real source: `OSHI-Desktop/`, plus the Android files it compiles from `OSHI-Android/` |
| **Public repo** `Lastoneparis/oshi-desktop` | A copy of `OSHI-Desktop/` (repo root = the desktop project) + `shared/OSHI-Android/…` + `docs/fixtures/`. **Its GitHub Actions are the only real Windows and Linux machines** — private-repo Actions are stopped by the billing budget |
| **Website** `oshi-messenger.com/desktop` (server `45.67.216.197`, `/var/www/oshi`) | `downloads/OSHI-Desktop-Setup.exe`, `OSHI-Desktop.msi`, `oshi-desktop-amd64.deb`, `oshi-desktop.x86_64.rpm` and the SHA-256 table on `desktop.html` in 9 languages |

**macOS is not released here.** The Mac app ships through the Mac App Store and the page
points Mac users there. The public `release.yml` has no macOS job on purpose: never add a
`.dmg` to the site or to a GitHub release.

## 1. Change and test in the monorepo

```bash
cd ~/Documents/Genesis/OSHI-Desktop
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew test --tests 'com.oshi.desktop.call.*' --tests 'com.oshi.desktop.group.*'   # and whatever you touched
OSHI_LIVE_CALL=1 ./gradlew test --tests 'com.oshi.desktop.call.LiveCallBench'         # real call via production
```

The monorepo checkout is shared by several Claude sessions and usually holds other
sessions' **uncommitted, half-finished** work. Before releasing, make sure the snapshot
compiles and the call suite is green. If someone's work in progress breaks the build,
**leave it out**: release from a git worktree and restore those paths to the last good
commit. That was done for 1.1.0: an Opus codec in progress was excluded.

```bash
git worktree add ~/Documents/Genesis-desktop-calls feat/desktop-calls-video   # already exists
# copy what you want into the worktree, commit, push the private branch
```

## 2. Push to the public repo (Windows/Linux CI runs)

```bash
SRC=~/Documents/Genesis-desktop-calls OSHI-Desktop/release/sync_public_repo.sh
```

- Copies the desktop, the shared Android sources, the GIF/sticker pack (about 66 MB, bundled
  into the installer on purpose) and the test fixtures.
- Refuses if the copy has a secret, a key file, a personal path, or doesn't compile.
- Pushes branch `feat/windows-calls-video`: adds commits only, never `main`, never force.
- CI: `build` (unit tests on Ubuntu and Windows) and `windows-calls` (call suite, phone
  oracles, same-host call and MSI on Windows and Ubuntu, plus a real Windows↔Ubuntu call over
  two NATs, in production and TURN-only modes). Watch it:
  `gh run list -R Lastoneparis/oshi-desktop --branch feat/windows-calls-video`

## 3. Tag → installers

```bash
SRC=~/Documents/Genesis-desktop-calls OSHI-Desktop/release/sync_public_repo.sh --tag v1.2.0
```

Pushing a `vX.Y.Z` tag runs **Release installers** (`release.yml`) on Windows and Linux. It
publishes a GitHub release with `.exe`, `.msi`, `.deb` and `.rpm`, each under a versioned and a
stable name, plus `SHA256SUMS-windows.txt` / `SHA256SUMS-linux.txt`. The MSI needs
MAJOR/MINOR 0–255.

## 4. Put it on the website

```bash
OSHI-Desktop/release/publish_website.sh v1.2.0
```

This downloads the 4 installers, backs up what the server serves (`downloads/prev-desktop-<old>/`
and each page to `/root/desktop-page-backup-*`), uploads to a staging folder, re-hashes on the
server, swaps them in with `mv`, rewrites the SHA-256 table, version and size on every `/desktop`
page (server + `ServerVPS/Site_oshi` copy), and checks the links. Then commit the
`ServerVPS/Site_oshi` changes.

## Permissions (Claude Code)

In **auto mode**, Claude Code's safety check refuses steps 2–4: publishing code to a public
repo, tagging, and uploading to the production server. That happens even with Bash allowed.
Either switch auto mode off and approve each step, or run the script yourself with
`! <command>`.

## Known facts worth not re-discovering

- Uncompressed PCM voice (1,957-byte datagrams) fragments and is dropped on some networks,
  cloud runners included. The compact 200-byte format (capability `0x10`) fixes desktop↔desktop;
  the live phones still send PCM to non-iPhone peers.
- The Windows H.264 encoder is `libopenh264` (Constrained Baseline, profile 66); the phones
  decode it. The camera library (`libavdevice`) loads lazily, so receiving video works without it.
- GIPHY: the key is **not** in the source (public repo, shared quota). Provide it with
  `OSHI_GIPHY_API_KEY`, `-Doshi.giphy.apiKey` or a CI-generated `oshi-giphy.properties`;
  without it the picker shows the offline pack only.
- Builds are unsigned, so Windows SmartScreen warns; the page says so.
