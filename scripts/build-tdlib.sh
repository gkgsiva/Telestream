#!/usr/bin/env bash
# Builds TDLib (Java interface) for Android from a pinned commit, using TDLib's own
# example/android scripts, and copies the result to an output directory.
#
# Usage: scripts/build-tdlib.sh ["<abi> <abi> ..."] [output-dir]
# Needs: ANDROID_SDK_ROOT (with cmdline-tools), JDK, gperf, php, perl, make, git.
# Then:  scripts/install-tdlib.sh <output-dir>
set -eu

ABIS="${1:-arm64-v8a armeabi-v7a x86_64}"
OUT="$(mkdir -p "${2:-tdlib-out}" && cd "${2:-tdlib-out}" && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TDLIB_WORK_DIR:-$ROOT/.tdlib-build}"
TDLIB_COMMIT="${TDLIB_COMMIT:-$(cat "$ROOT/scripts/tdlib-commit.txt")}"
NDK="${ANDROID_NDK_VERSION:-23.2.8568313}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
MIN_API="${TDLIB_MIN_API:-21}"

if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "Set ANDROID_SDK_ROOT to your Android SDK." >&2
  exit 1
fi

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
echo "Installing NDK $NDK and CMake 3.22.1..."
(yes || true) | "$SDKMANAGER" --licenses > /dev/null
"$SDKMANAGER" --install "ndk;$NDK" "cmake;3.22.1" "platforms;android-34" "build-tools;34.0.0" > /dev/null

mkdir -p "$WORK"
cd "$WORK"
if [ ! -d td ]; then
  git clone https://github.com/tdlib/td.git
fi
cd td
git fetch --quiet origin
git checkout --quiet "$TDLIB_COMMIT"

cd example/android
if [ ! -d third-party/openssl ]; then
  ./build-openssl.sh "$SDK" "$NDK" third-party/openssl
fi
./build-tdlib.sh "$SDK" "$NDK" third-party/openssl c++_static Java "$MIN_API" "$ABIS"

rm -rf "$OUT/java" "$OUT/libs"
cp -r tdlib/java tdlib/libs "$OUT/"
echo "TDLib $TDLIB_COMMIT for [$ABIS] is in $OUT"
