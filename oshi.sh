#!/bin/bash
# Convenience launcher.
#
# This Mac has no `java` on PATH (only Homebrew JDKs under /opt/homebrew/opt), so
# ./gradlew fails to even start without JAVA_HOME. This script finds a JDK and forwards
# everything to Gradle.
#
#   ./oshi.sh test              run the parity suite
#   ./oshi.sh run               run the protocol demo
#   ./oshi.sh run --args="--probe"   ...plus one read-only GET to the live server
#   ./oshi.sh run --args="--mesh"    a live mesh node: mDNS discovery + TCP (PLAN_MESH.md)
#
# platform/windows/oshi.cmd is the Windows counterpart; platform/linux/oshi.sh is the Linux one.
#
# On Linux/Windows, JAVA_HOME is normally already set and ./gradlew works directly.
set -e

if [ -z "$JAVA_HOME" ]; then
  for candidate in \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
  do
    if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi

if [ -z "$JAVA_HOME" ]; then
  echo "No JDK found. Install one (brew install openjdk@17) or set JAVA_HOME." >&2
  exit 1
fi

echo "Using JAVA_HOME=$JAVA_HOME"
exec "$(dirname "$0")/gradlew" "$@"
