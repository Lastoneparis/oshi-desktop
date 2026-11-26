#!/bin/bash
# Developer ID signing of a jpackage OSHI.app (hardened runtime), ready for notarytool.
#
#   JAVA_HOME=<Temurin 21, NOT Homebrew> ./gradlew packageAppImage
#   OSHI-Desktop/release/sign_macos.sh build/jpackage/out/OSHI.app
#   ditto -c -k --keepParent OSHI.app OSHI.zip && xcrun notarytool submit OSHI.zip --key … --wait
#   xcrun stapler staple OSHI.app
#
# __MAC_SIGN_2026_11_19__ Two things measured on 1.5.0:
#  * The runtime MUST come from a self-contained JDK (Temurin). Homebrew's openjdk@21 links
#    libfontmanager.dylib to /opt/homebrew/opt/harfbuzz: under the hardened runtime that
#    library is refused ("different Team IDs") and AWT dies before the first window — and on
#    a Mac without Homebrew it would not exist at all.
#  * Natives inside the jars (Conscrypt, Skiko, JavaCPP/FFmpeg) are signed IN the jar. They are
#    extracted at run time ($TMPDIR, ~/.skiko, ~/.javacpp) and keep their embedded signature,
#    so library validation accepts them: no disable-library-validation entitlement is needed.
#    Skiko names its cache dir after the .sha256 next to the dylib, which is rewritten here.
# Not a shipping target per RELEASING.md (Mac users have the App Store app).
set -euo pipefail
APP="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; ID="D08E287C3F1F10F9B98376972DB7A3C63B05114C"
ENT="$(cd "$(dirname "$0")" && pwd)/macos-entitlements.plist"
TMP=$(mktemp -d)
cs() { codesign --force --timestamp --options runtime --sign "$ID" "$@"; }
for j in "$APP"/Contents/app/*.jar; do
  # Mach-O by CONTENT: the FFmpeg jar carries extension-less `ffmpeg`/`ffprobe` executables,
  # which notarisation rejects unsigned just like the .dylib files.
  rm -rf "$TMP/x"; mkdir -p "$TMP/x"; ( cd "$TMP/x" && unzip -q "$j" )
  signed=()
  while IFS= read -r -d '' f; do
    file -b "$f" | grep -q '^Mach-O' || continue
    cs "$f"; rel="${f#$TMP/x/}"; signed+=("$rel")
    # skiko keys its cache dir on the .sha256 resource next to the dylib
    if [ -f "$f.sha256" ]; then shasum -a 256 "$f" | cut -d' ' -f1 | tr -d '\n' > "$f.sha256"; signed+=("$rel.sha256"); fi
  done < <(find "$TMP/x" -type f ! -name '*.class' -size +4k -print0)
  if [ ${#signed[@]} -gt 0 ]; then ( cd "$TMP/x" && zip -q -X "$j" "${signed[@]}" ); echo "jar natives signed: $(basename "$j") (${#signed[@]})"; fi
done
rm -rf "$TMP"
# every loose Mach-O, inside-out
find "$APP/Contents" -type f \( -name "*.dylib" -o -name "*.jnilib" -o -perm +111 \) ! -name "*.jar" ! -path "*/MacOS/OSHI" -print0 |
  while IFS= read -r -d '' f; do file "$f" | grep -q Mach-O && cs --entitlements "$ENT" "$f"; done
cs --entitlements "$ENT" "$APP/Contents/runtime"
cs --entitlements "$ENT" "$APP"
codesign --verify --deep --strict --verbose=2 "$APP"
