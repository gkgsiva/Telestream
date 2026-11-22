#!/usr/bin/env bash
# Copies a TDLib build (java/ and libs/<abi>/ folders, as produced by build-tdlib.sh or the
# CI "tdlib-<abi>" artifacts) into the :tdlib Gradle module.
# Usage: scripts/install-tdlib.sh <dir>
set -eu
SRC="${1:?Usage: scripts/install-tdlib.sh <dir with java/ and libs/>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="$ROOT/tdlib/src/main"

rm -rf "$MODULE/java/org" "$MODULE/jniLibs"
mkdir -p "$MODULE/java" "$MODULE/jniLibs"
cp -r "$SRC/java/org" "$MODULE/java/"
for abi_dir in "$SRC"/libs/*/; do
  abi="$(basename "$abi_dir")"
  mkdir -p "$MODULE/jniLibs/$abi"
  cp "$abi_dir"/*.so "$MODULE/jniLibs/$abi/"
done
echo "Installed TDLib into $MODULE:"
ls "$MODULE/jniLibs"
