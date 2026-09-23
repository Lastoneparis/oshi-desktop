#!/bin/bash
# Put a GitHub release of OSHI desktop (Windows + Linux) on https://oshi-messenger.com/desktop
#
#   OSHI-Desktop/release/publish_website.sh v1.1.0
#
# 1. downloads the 4 stable-named installers from the GitHub release
# 2. backs up what the server serves today (downloads/prev-desktop-<old version>/)
# 3. uploads to a staging dir, re-hashes ON THE SERVER, then swaps files in with mv
# 4. rewrites the SHA-256 table + version on /desktop in every language (server + repo copy)
# 5. checks every link and the live page
#
# macOS: NOT published here. The Mac app ships through the Mac App Store and the page
# points there. Never add a .dmg link.
set -euo pipefail

TAG="${1:?usage: publish_website.sh vMAJOR.MINOR.PATCH}"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "tag must look like v1.2.3" >&2; exit 1; }
VER="${TAG#v}"
KEY="${KEY:-$HOME/my_key.pem}"
HOST="${HOST:-root@45.67.216.197}"
WEB=/var/www/oshi
HERE="$(cd "$(dirname "$0")" && pwd)"
SITE_REPO="$(cd "$HERE/../.." && pwd)/ServerVPS/Site_oshi"
WORK="${WORK:-${TMPDIR:-/tmp}/oshi-desktop-$VER}"
FILES=(OSHI-Desktop-Setup.exe OSHI-Desktop.msi oshi-desktop-amd64.deb oshi-desktop.x86_64.rpm)

echo "== 1/5 download $TAG from GitHub"
mkdir -p "$WORK" && cd "$WORK"
gh release download "$TAG" -R Lastoneparis/oshi-desktop --clobber \
  -p 'OSHI-Desktop-Setup.exe' -p 'OSHI-Desktop.msi' -p 'oshi-desktop-amd64.deb' -p 'oshi-desktop.x86_64.rpm' \
  -p 'SHA256SUMS*.txt' || true
for f in "${FILES[@]}"; do [ -s "$f" ] || { echo "missing $f in release $TAG" >&2; exit 1; }; done
# (bash 3.2 on macOS: no associative arrays)
hash_of() { shasum -a 256 "$1" | cut -d' ' -f1; }
H_EXE=$(hash_of OSHI-Desktop-Setup.exe); H_MSI=$(hash_of OSHI-Desktop.msi)
H_DEB=$(hash_of oshi-desktop-amd64.deb); H_RPM=$(hash_of oshi-desktop.x86_64.rpm)
LOCAL_SUMS="$H_EXE  OSHI-Desktop-Setup.exe
$H_MSI  OSHI-Desktop.msi
$H_DEB  oshi-desktop-amd64.deb
$H_RPM  oshi-desktop.x86_64.rpm
"
printf '%s' "$LOCAL_SUMS"
# Cross-check against the release's own checksum files when they are well-formed.
for s in SHA256SUMS*.txt; do
  [ -f "$s" ] || continue
  grep -E '^[0-9a-f]{64}  ' "$s" | while read -r h n; do
    mine=$(printf '%s' "$LOCAL_SUMS" | awk -v n="$n" '$2==n{print $1}')
    if [ -n "$mine" ] && [ "$mine" != "$h" ]; then echo "ABORT: $n differs from $s" >&2; exit 1; fi
  done
done

echo "== 2/5 back up what is live now"
OLD=$(ssh -i "$KEY" "$HOST" "grep -o -E '\"softwareVersion\":\"[0-9.]+\"' $WEB/desktop.html | grep -o -E '[0-9.]+' | head -1" || echo unknown)
ssh -i "$KEY" "$HOST" "cd $WEB/downloads && mkdir -p prev-desktop-$OLD && for f in ${FILES[*]}; do [ -f \$f ] && [ ! -f prev-desktop-$OLD/\$f ] && cp -p \$f prev-desktop-$OLD/; done; ls prev-desktop-$OLD"

echo "== 3/5 upload to staging, verify on the server, swap in"
ssh -i "$KEY" "$HOST" "mkdir -p $WEB/downloads/.incoming-$VER"
scp -i "$KEY" "${FILES[@]}" "$HOST:$WEB/downloads/.incoming-$VER/"
ssh -i "$KEY" "$HOST" "set -e; cd $WEB/downloads/.incoming-$VER
printf '%s' '$LOCAL_SUMS' | sha256sum -c -
chmod 644 *
cp -p OSHI-Desktop-Setup.exe ../OSHI-$VER.exe; cp -p OSHI-Desktop.msi ../OSHI-$VER.msi
cp -p oshi-desktop-amd64.deb ../oshi-desktop_${VER}-1_amd64.deb; cp -p oshi-desktop.x86_64.rpm ../oshi-desktop-${VER}-1.x86_64.rpm
for f in ${FILES[*]}; do mv -f \$f ../\$f; done
cd .. && rmdir .incoming-$VER"

echo "== 4/5 checksum table + version on every /desktop page"
EDIT="s#(<td>OSHI-Desktop-Setup\\.exe</td><td>)[0-9a-f]{64}#\${1}$H_EXE#g;
s#(<td>OSHI-Desktop\\.msi</td><td>)[0-9a-f]{64}#\${1}$H_MSI#g;
s#(<td>oshi-desktop-amd64\\.deb</td><td>)[0-9a-f]{64}#\${1}$H_DEB#g;
s#(<td>oshi-desktop\\.x86_64\\.rpm</td><td>)[0-9a-f]{64}#\${1}$H_RPM#g;
s#\"softwareVersion\":\"[0-9.]+\"#\"softwareVersion\":\"$VER\"#g"
MB=$(( ($(stat -f%z OSHI-Desktop-Setup.exe 2>/dev/null || stat -c%s OSHI-Desktop-Setup.exe) + 5000000) / 10000000 * 10 ))
EDIT="$EDIT;
s#about [0-9]+&nbsp;MB#about ${MB}&nbsp;MB#g"
ssh -i "$KEY" "$HOST" "set -e; cd $WEB; ts=\$(date +%Y%m%d%H%M%S)
for p in desktop.html */desktop.html; do cp -p \"\$p\" \"/root/desktop-page-backup-\$ts-\${p//\\//_}\"; perl -0pi -e '$EDIT' \"\$p\"; done
grep -c '$H_EXE' desktop.html */desktop.html"
for p in "$SITE_REPO"/desktop.html "$SITE_REPO"/*/desktop.html; do [ -f "$p" ] && perl -0pi -e "$EDIT" "$p"; done
echo "(repo copies under ServerVPS/Site_oshi updated — commit them)"

echo "== 5/5 live checks"
for f in "${FILES[@]}"; do
  printf '%-26s ' "$f"; curl -sI "https://oshi-messenger.com/downloads/$f" | grep -i -E '^(HTTP|content-length)' | tr -d '\r' | tr '\n' ' '; echo
done
curl -s https://oshi-messenger.com/desktop | grep -o -E "$H_EXE|softwareVersion\":\"[0-9.]+\"" | sort -u
echo "DONE: OSHI desktop $VER is live on https://oshi-messenger.com/desktop"
