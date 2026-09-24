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
  --exclude='local.properties' --exclude='*.pem' --exclude='*.jks' --exclude='*.keystore' \
  --exclude='*.p12' --exclude='*.key' --exclude='.DS_Store' --exclude='*.log' \
  "$SRC/OSHI-Desktop/" "$PUB/"
mkdir -p "$PUB/.github/workflows"
cp "$SRC/OSHI-Desktop/.github/workflows/windows-calls.yml" "$PUB/.github/workflows/"

echo "== 2/6 shared Android sources the desktop compiles (see build.gradle.kts include list)"
J="$SRC/OSHI-Android/app/src/main/java/com/oshi/messenger"
A="$PUB/shared/OSHI-Android/app/src"
M="$A/main/java/com/oshi/messenger"
mkdir -p "$M/network/v2/devsync" "$M/network/encryption" "$M/service" "$A/test/resources" "$A/main/assets"
cp "$J"/network/v2/{OSHICryptoV2,OSHICryptoV2Streaming,OSHIRatchetV2,V2Session,V2FileKeyMessage,V2RetryBudget}.kt "$M/network/v2/"
cp "$J"/network/v2/devsync/*.kt "$M/network/v2/devsync/"
cp "$J"/network/encryption/{PostQuantumKEM,RatchetSecurityMode}.kt "$M/network/encryption/"
cp "$J"/service/{LlamaCpp,CallRatingPolicy}.kt "$M/service/"
cp "$SRC"/OSHI-Android/app/src/test/resources/*.json "$A/test/resources/"
# GIF & sticker pack (byte-identical to iOS) — bundled into the installer on purpose.
rsync -a --delete "$SRC/OSHI-Android/app/src/main/assets/GifPack" "$SRC/OSHI-Android/app/src/main/assets/StickerPack" "$A/main/assets/"
if grep -l '^import android\.' "$M"/network/v2/{OSHICryptoV2,OSHICryptoV2Streaming,OSHIRatchetV2,V2Session,V2FileKeyMessage,V2RetryBudget}.kt "$M"/network/v2/devsync/*.kt \
     "$M"/network/encryption/{PostQuantumKEM,RatchetSecurityMode}.kt "$M"/service/{LlamaCpp,CallRatingPolicy}.kt; then
  echo "ABORT: android.* import in a shared source the desktop compiles" >&2; exit 1; fi

echo "== 3/6 test fixtures"
mkdir -p "$PUB/docs/fixtures"
rsync -a --exclude __pycache__ "$SRC/docs/fixtures/" "$PUB/docs/fixtures/"

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
