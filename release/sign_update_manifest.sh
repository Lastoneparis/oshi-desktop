#!/bin/bash
# __DESKTOP_AUTO_UPDATE_2026_11_21__ Build, sign and (optionally) publish the desktop update
# manifest — https://oshi-messenger.com/downloads/desktop-latest.json + .sig — for a release
# that is ALREADY on the website.
#
#   OSHI-Desktop/release/sign_update_manifest.sh v1.6.0                 # build + sign only (dry)
#   OSHI-Desktop/release/sign_update_manifest.sh v1.6.0 --upload        # …and put it live
#   OSHI-Desktop/release/sign_update_manifest.sh v1.6.1 --dir <folder> --upload
#                                                    # (publish_website.sh: hash the local files)
#
# Env: NOTES=<file.json>  release notes, a string or {"en": "...", "fr": "..."} (optional)
#      UPDATE_KEY         private key (default ~/.oshi-release/desktop-update-ed25519.key)
#      KEY / HOST         ssh key / host, as publish_website.sh
#
# Without --dir the hashes come from the GitHub release's SHA256SUMS and the sizes from its asset
# list — nothing large is downloaded. Either way, --upload re-hashes the files the SERVER serves at
# the manifest's URLs and refuses to publish on any mismatch, so the manifest can never point at
# bytes other than the ones users get. Signing is checked against the public key COMPILED INTO THE
# APP (UpdateSignature.kt), so a manifest signed with the wrong key cannot go live either.
#
# The app only acts on a manifest whose version is HIGHER than its own: publishing 1.6.0's manifest
# shows nothing to 1.6.0 users (who have no updater anyway) — it makes the endpoint live and testable.
set -euo pipefail

TAG="${1:?usage: sign_update_manifest.sh vMAJOR.MINOR.PATCH [--dir <folder>] [--upload]}"
shift
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "tag must look like v1.2.3" >&2; exit 1; }
VER="${TAG#v}"
DIR=""; UPLOAD=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="${2:?--dir needs a folder}"; shift 2 ;;
    --upload) UPLOAD=1; shift ;;
    *) echo "unknown argument $1" >&2; exit 1 ;;
  esac
done
HERE="$(cd "$(dirname "$0")" && pwd)"
TOOL="$HERE/update_manifest.py"
UPDATE_KEY="${UPDATE_KEY:-$HOME/.oshi-release/desktop-update-ed25519.key}"
KEY="${KEY:-$HOME/my_key.pem}"
HOST="${HOST:-root@45.67.216.197}"
WEB=/var/www/oshi
OUT="${OUT:-${TMPDIR:-/tmp}/oshi-desktop-manifest-$VER}"
APP_KEY_SRC="$HERE/../src/main/kotlin/com/oshi/desktop/update/UpdateSignature.kt"

[ -f "$UPDATE_KEY" ] || { echo "no signing key at $UPDATE_KEY" >&2; exit 1; }
PERM=$(stat -f %Lp "$UPDATE_KEY" 2>/dev/null || stat -c %a "$UPDATE_KEY")
[ "$PERM" = "600" ] || { echo "refusing: $UPDATE_KEY is mode $PERM, must be 600" >&2; exit 1; }
APP_PUB=$(grep -o 'PRODUCTION_PUBLIC_KEY_B64 = "[^"]*"' "$APP_KEY_SRC" | cut -d'"' -f2)
[ -n "$APP_PUB" ] || { echo "cannot read the app's public key from $APP_KEY_SRC" >&2; exit 1; }
MY_PUB=$(python3 "$TOOL" pubkey --key "$UPDATE_KEY" 2>/dev/null)
[ "$MY_PUB" = "$APP_PUB" ] || { echo "ABORT: $UPDATE_KEY is not the key the app trusts ($APP_PUB)" >&2; exit 1; }

mkdir -p "$OUT"
echo "== 1/3 build the manifest for $VER"
NOTES_ARG=(); [ -n "${NOTES:-}" ] && NOTES_ARG=(--notes-file "$NOTES")
if [ -n "$DIR" ]; then
  SUMS=(); for s in "$DIR"/SHA256SUMS*.txt; do [ -f "$s" ] && SUMS+=(--sums "$s"); done
  python3 "$TOOL" build --version "$VER" --dir "$DIR" ${SUMS[@]+"${SUMS[@]}"} ${NOTES_ARG[@]+"${NOTES_ARG[@]}"} --out "$OUT/desktop-latest.json"
else
  ( cd "$OUT" && rm -f SHA256SUMS*.txt && gh release download "$TAG" -R Lastoneparis/oshi-desktop --clobber -p 'SHA256SUMS*.txt' )
  gh release view "$TAG" -R Lastoneparis/oshi-desktop --json assets \
    -q '[.assets[] | {(.name): .size}] | add' > "$OUT/sizes.json"
  python3 "$TOOL" build --version "$VER" --sums "$OUT/SHA256SUMS-windows.txt" --sums "$OUT/SHA256SUMS-linux.txt" \
    --sizes-file "$OUT/sizes.json" ${NOTES_ARG[@]+"${NOTES_ARG[@]}"} --out "$OUT/desktop-latest.json"
fi

echo "== 2/3 sign + verify with the key compiled into the app"
python3 "$TOOL" sign --in "$OUT/desktop-latest.json" --key "$UPDATE_KEY"
python3 "$TOOL" verify --in "$OUT/desktop-latest.json" --pubkey "$APP_PUB"
cat "$OUT/desktop-latest.json"

if [ "$UPLOAD" != 1 ]; then
  echo "DRY RUN: nothing uploaded. Files: $OUT/desktop-latest.json{,.sig}"
  echo "Publish with: $0 $TAG ${DIR:+--dir $DIR }--upload"
  exit 0
fi

echo "== 3/3 check the SERVED installers against the manifest, then swap the manifest in"
# "<sha256>  <file name>" for each installer the manifest lists.
EXPECT=$(python3 -c 'import json,sys
m=json.load(open(sys.argv[1]))
for f in m["files"].values(): print(f["sha256"]+"  "+f["url"].rsplit("/",1)[1])' "$OUT/desktop-latest.json")
SIZES=$(python3 -c 'import json,sys
m=json.load(open(sys.argv[1]))
for f in m["files"].values(): print(str(f["size"])+" "+f["url"].rsplit("/",1)[1])' "$OUT/desktop-latest.json")
ssh -i "$KEY" "$HOST" "set -e; cd $WEB/downloads
printf '%s\n' '$EXPECT' | sha256sum -c -
printf '%s\n' '$SIZES' | while read -r n f; do [ \"\$(stat -c %s \"\$f\")\" = \"\$n\" ] || { echo \"size mismatch \$f\" >&2; exit 1; }; done
mkdir -p .incoming-manifest"
scp -i "$KEY" "$OUT/desktop-latest.json" "$OUT/desktop-latest.json.sig" "$HOST:$WEB/downloads/.incoming-manifest/"
ssh -i "$KEY" "$HOST" "set -e; cd $WEB/downloads
ts=\$(date +%Y%m%d%H%M%S)
for f in desktop-latest.json desktop-latest.json.sig; do [ -f \$f ] && cp -p \$f /root/desktop-manifest-backup-\$ts-\$f || true; done
chmod 644 .incoming-manifest/*
mv -f .incoming-manifest/desktop-latest.json.sig desktop-latest.json.sig
mv -f .incoming-manifest/desktop-latest.json desktop-latest.json
rmdir .incoming-manifest"

echo "== live check"
LIVE="$OUT/live"; mkdir -p "$LIVE"
curl -fsS -o "$LIVE/desktop-latest.json" https://oshi-messenger.com/downloads/desktop-latest.json
curl -fsS -o "$LIVE/desktop-latest.json.sig" https://oshi-messenger.com/downloads/desktop-latest.json.sig
python3 "$TOOL" verify --in "$LIVE/desktop-latest.json" --pubkey "$APP_PUB"
echo "DONE: desktop-latest.json for $VER is live and verifies with the app's key."
