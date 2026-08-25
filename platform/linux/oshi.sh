#!/bin/bash
# Convenience launcher for Linux.
#
# On most distributions `./gradlew` already works: JAVA_HOME is set, or a JDK is on PATH.
# This script is for the machines where it is not — it looks in the usual distribution
# locations and, if it finds nothing, names the package to install rather than letting
# Gradle fail with "Unable to locate a Java Runtime".
#
#   platform/linux/oshi.sh test              the parity suite
#   platform/linux/oshi.sh run --args="--client"
#   platform/linux/oshi.sh packageDeb        needs dpkg-deb + fakeroot
#
# The oshi.sh at the repository ROOT is the macOS launcher (it searches Homebrew paths).
# platform/windows/oshi.cmd is the Windows counterpart of this one.
set -e

if [ -z "$JAVA_HOME" ] && ! command -v java >/dev/null 2>&1; then
  # Ordered newest-first: jpackage needs 14+, and the build targets 17.
  for candidate in \
    /usr/lib/jvm/java-21-openjdk* \
    /usr/lib/jvm/java-17-openjdk* \
    /usr/lib/jvm/default-java \
    /usr/lib/jvm/java-21-openjdk-amd64 \
    /usr/lib/jvm/java-17-openjdk-amd64
  do
    if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi

if [ -z "$JAVA_HOME" ] && ! command -v java >/dev/null 2>&1; then
  echo "No JDK found. Install one:" >&2
  echo "  Debian/Ubuntu : sudo apt-get install openjdk-17-jdk" >&2
  echo "  Fedora/RHEL   : sudo dnf install java-17-openjdk-devel" >&2
  echo "  Arch          : sudo pacman -S jdk17-openjdk" >&2
  echo "…or set JAVA_HOME yourself. A JRE is not enough: jpackage ships only in the JDK." >&2
  exit 1
fi

[ -n "$JAVA_HOME" ] && echo "Using JAVA_HOME=$JAVA_HOME"

# The key store needs a session D-Bus. Warn, never fail: the suite and the client both run
# without one (the vault falls back to a passphrase), but "no keyring" is worth knowing
# BEFORE a secret-store test skips or the client asks for a passphrase you did not expect.
if [ -z "${DBUS_SESSION_BUS_ADDRESS:-}" ]; then
  echo "note: no DBUS_SESSION_BUS_ADDRESS — no Secret Service, so libsecret is unavailable." >&2
  echo "      The vault will ask for a passphrase instead. See platform/linux/README.md." >&2
fi

exec "$(dirname "$0")/../../gradlew" "$@"
