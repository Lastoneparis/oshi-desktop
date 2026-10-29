#!/bin/bash
# Copy the desktop client from the private monorepo into the PUBLIC repo
# Lastoneparis/oshi-desktop, then (optionally) tag a release.
#
#   OSHI-Desktop/release/sync_public_repo.sh                 # push the branch only (CI runs)
#   OSHI-Desktop/release/sync_public_repo.sh --tag v1.2.0    # also tag -> builds installers
#
# Env overrides:
#   SRC     monorepo root to copy from     (default: the repo this script lives in)
#   PUB     local clone of the public repo  (default: ~/oshi-desktop-public)
#   BRANCH  public branch to push           (default: feat/windows-calls-video)
#
# Guarantees: never pushes main, never force-pushes, refuses on secrets / key files /
# android.* imports in the shared sources, refuses if the copy does not compile.
# See OSHI-Desktop/RELEASING.md for the whole process.
set -euo pipefail

TAG=""
[ "${1:-}" = "--tag" ] && TAG="${2:?usage: --tag vMAJOR.MINOR.PATCH}"
if [ -n "$TAG" ] && ! [[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "tag must look like v1.2.3" >&2; exit 1; fi

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="${SRC:-$(cd "$HERE/../.." && pwd)}"
PUB="${PUB:-$HOME/oshi-desktop-public}"
BRANCH="${BRANCH:-feat/windows-calls-video}"
[ "$BRANCH" = "main" ] && { echo "refusing to push main" >&2; exit 1; }
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"

echo "== source: $SRC   public clone: $PUB   branch: $BRANCH"
[ -d "$PUB/.git" ] || git clone -q https://github.com/Lastoneparis/oshi-desktop.git "$PUB"
git -C "$PUB" fetch -q origin --tags
if git -C "$PUB" ls-remote --exit-code --heads origin "$BRANCH" >/dev/null; then
  git -C "$PUB" checkout -q -B "$BRANCH" "origin/$BRANCH"   # add commits on top, no force
else
  git -C "$PUB" checkout -q -B "$BRANCH" origin/main
fi

echo "== 1/6 desktop sources (the public repo root IS the desktop project)"
rsync -a --delete \
  --exclude='.git' --exclude='.github' --exclude='LICENSE' --exclude='.gitignore' \
  --exclude='shared' --exclude='docs' --exclude='build' --exclude='.gradle' --exclude='.kotlin' \
  --exclude='Vendor' \
  --exclude='local.properties' --exclude='*.pem' --exclude='*.jks' --exclude='*.keystore' \
  --exclude='*.p12' --exclude='*.key' --exclude='.DS_Store' --exclude='*.log' \
  "$SRC/OSHI-Desktop/" "$PUB/"
mkdir -p "$PUB/.github/workflows"
cp "$SRC/OSHI-Desktop/.github/workflows/windows-calls.yml" "$PUB/.github/workflows/"

echo "== 2/6 shared Android sources the desktop compiles (see build.gradle.kts include list)"
J="$SRC/OSHI-Android/app/src/main/java/com/oshi/messenger"
A="$PUB/shared/OSHI-Android/app/src"
M="$A/main/java/com/oshi/messenger"
mkdir -p "$M/network/v2/devsync" "$M/network/encryption" "$M/service/diag" "$M/util" "$A/test/resources" "$A/main/assets"
cp "$J"/network/v2/{OSHICryptoV2,OSHICryptoV2Streaming,OSHIRatchetV2,V2Session,V2FileKeyMessage,V2RetryBudget,RatchetHealSignal}.kt "$M/network/v2/"
cp "$J"/network/v2/devsync/*.kt "$M/network/v2/devsync/"
# __RATCHET_IS_SHARED_SOURCE_2026_10_28__ DoubleRatchet + RatchetSeams (RatchetBase64)
cp "$J"/network/encryption/{PostQuantumKEM,RatchetSecurityMode,DoubleRatchet,RatchetSeams}.kt "$M/network/encryption/"
cp "$J"/service/{LlamaCpp,CallRatingPolicy}.kt "$M/service/"
# __VIDEO_REORDER_2026_09_23__ / __VIDEO_ABR_2026_09_23__ the receive reorder window and the
# send-side rate ladder, and the call diagnostic log the desktop writes through.
cp "$J"/service/{VideoReorderReassembler,VideoRateController}.kt "$M/service/"
cp "$J"/service/diag/CallFileLogger.kt "$M/service/diag/"
# __CASHU_2026_10_24__ the e-cash reader: the four files build.gradle.kts includes from
# `util/`, and ONLY those four — the rest of that directory is Android (Context, Keystore,
# SharedPreferences). This is why desktop 1.3.2 never reached CI: the eCash commit added a
# shared srcDir and this list was not grown with it, so the public copy did not compile.
cp "$J"/util/{CashuTokens,CashuCbor,CashuJson,CashuPayCommand}.kt "$M/util/"
# __VIDEO_NOTE_2026_09_24__ round-video-note rules; V2FileKeyMessage.kt calls it.
mkdir -p "$M/service/media" && cp "$J"/service/media/VideoNote.kt "$M/service/media/"
# RECURSIVE, and not `*.json` at the top level only. That glob is why
# `DesktopCallLogTest` failed on BOTH Ubuntu and Windows in the public repo while passing
# here: its fixtures are `call_log/redaction_*.log` — a SUBDIRECTORY, and `.log` rather
# than `.json` — so they never crossed. A fixture that does not travel is a test that only
# ever ran on the machine that wrote it.
rsync -a "$SRC"/OSHI-Android/app/src/test/resources/ "$A/test/resources/"
# GIF & sticker pack (byte-identical to iOS) — bundled into the installer on purpose.
rsync -a --delete "$SRC/OSHI-Android/app/src/main/assets/GifPack" "$SRC/OSHI-Android/app/src/main/assets/StickerPack" "$A/main/assets/"
# __COPY_LIST_DRIFT_2026_10_30__ Every `**/Name.kt` the desktop INCLUDES from the shared
# Android tree must now exist in the copy above. This guard exists because the hand-written
# list has fallen behind `build.gradle.kts` at least twice: the eCash commit added a shared
# srcDir and 1.3.2 never reached CI, and on 2026-10-30 RatchetHealSignal.kt, DoubleRatchet.kt
# and RatchetSeams.kt were missing, so the public copy did not compile. The compile check
# below catches that too, but only after a full Kotlin build and with "Unresolved reference"
# as the only clue — this names the missing FILE instead.
missing=""
while read -r want; do
  [ -z "$want" ] && continue
  find "$A/main/java" -name "$want" -print -quit | grep -q . || missing="$missing $want"
done <<EOF
$(sed -n '/kotlin.include(/,/^        )/p' "$SRC/OSHI-Desktop/build.gradle.kts" \
    | grep -oE '"\*\*/[A-Za-z0-9_]+\.kt"' | tr -d '"' | sed 's|\*\*/||' | sort -u)
EOF
if [ -n "$missing" ]; then
  echo "ABORT: build.gradle.kts includes these shared sources and the copy list above does not carry them:$missing" >&2
  echo "       add them to the cp lines in this script (section 2/6)." >&2
  exit 1
fi

if grep -l '^import android\.' "$M"/network/v2/{OSHICryptoV2,OSHICryptoV2Streaming,OSHIRatchetV2,V2Session,V2FileKeyMessage,V2RetryBudget}.kt "$M"/network/v2/devsync/*.kt \
     "$M"/network/encryption/{PostQuantumKEM,RatchetSecurityMode}.kt \
     "$M"/service/{LlamaCpp,CallRatingPolicy,VideoReorderReassembler,VideoRateController}.kt \
     "$M"/service/media/VideoNote.kt "$M"/service/diag/CallFileLogger.kt \
     "$M"/util/{CashuTokens,CashuCbor,CashuJson,CashuPayCommand}.kt; then
  echo "ABORT: android.* import in a shared source the desktop compiles" >&2; exit 1; fi

# __OPUS_CODEC_2026_09_23__ Concentus, the pure-Java Opus port the call codec compiles from
# source. Vendored at the MONOREPO root, i.e. outside everything copied above, so it needs its
# own line — without it the public copy has OpusCallCodec.kt and nothing to resolve it against.
# Source + LICENSE + the vendoring note only: no prebuilt jar, and not the `opus`/`opus_oshi`
# C trees or verify-vendored.sh, matching v1.3.1's published tree exactly.
echo "== 2b/6 vendored Concentus (Opus) source"
mkdir -p "$PUB/Vendor/concentus"
rsync -a --delete "$SRC/Vendor/concentus/src" "$SRC/Vendor/concentus/LICENSE" "$PUB/Vendor/concentus/"
cp "$SRC/Vendor/README-OSHI.md" "$PUB/Vendor/"

echo "== 3/6 test fixtures"
mkdir -p "$PUB/docs/fixtures"
rsync -a --exclude __pycache__ "$SRC/docs/fixtures/" "$PUB/docs/fixtures/"

# The bot sealing vectors. BOT_SEAL_SPEC.md says "every client MUST pass the vectors", and
# `BotSealFixtures` calls `error()` rather than skipping when they are absent — correctly,
# because silently not checking a sealing contract is worse than a red build. But they live
# at the MONOREPO root under ServerPatches/, outside everything copied above, so in the
# public repo all 18 bot tests errored out on both legs. One source of truth, carried here.
mkdir -p "$PUB/docs/fixtures/bot_e2e"
cp "$SRC/ServerPatches/bot_e2e/test/vectors.json" "$PUB/docs/fixtures/bot_e2e/vectors.json"

echo "== 4/6 secret / private-file scan"
cd "$PUB"
git add -A
BAD=$(git diff --cached --name-only | grep -Ei '\.(pem|jks|keystore|p12|key|mov|mp4|pyc)$|google-services|local\.properties' || true)
[ -z "$BAD" ] || { echo "ABORT: private files staged:"; echo "$BAD"; exit 1; }
if git diff --cached -U0 | grep -E '^\+' | grep -Ei 'OshiTurn2026|static-auth-secret|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{30,}|AIza[0-9A-Za-z_-]{30,}|/Users/[A-Za-z]+/'; then
  echo "ABORT: possible secret or personal path in the diff" >&2; exit 1; fi

echo "== 5/6 compile check"
./gradlew -q compileKotlin compileTestKotlin

echo "== 6/6 commit + push $BRANCH"
if git diff --cached --quiet; then echo "nothing new to commit"; else
  git commit -q -m "sync: OSHI desktop from monorepo $(git -C "$SRC" rev-parse --short HEAD)${TAG:+ ($TAG)}"; fi
git push origin "$BRANCH"
if [ -n "$TAG" ]; then
  git tag -a "$TAG" -m "OSHI desktop ${TAG#v}"
  git push origin "$TAG"
  echo "Tagged $TAG -> the 'Release installers' workflow builds .exe/.msi/.deb/.rpm:"
  echo "  https://github.com/Lastoneparis/oshi-desktop/actions"
  echo "When it is green: OSHI-Desktop/release/publish_website.sh $TAG"
fi
