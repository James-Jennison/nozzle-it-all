#!/usr/bin/env bash
# CI's Android engine root (.github/workflows/ci.yml): the runner has orcaslicer-android-engine's NDK prefix but not its
# source archives. So this fetches the GMP archive (hash-checked against engine/fork/android/DEPENDENCIES.json) into a
# cache that survives runs, then runs prepare_engine_root.sh, which fetches the pinned nozzle-engine commit into the cache.
#   ci_engine_root.sh <cache dir> <android deps prefix>     prints the engine root on its last line
set -euo pipefail
NOZZLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
CACHE="$1"; DEPS="$2"
[ -d "$DEPS/include" ] || { echo "::error::$DEPS is not an Android dependency prefix (orcaslicer-android-engine/deps/install/arm64-v8a)"; exit 1; }
mkdir -p "$CACHE"

GMP="$CACHE/gmp-6.2.1.tar.xz"
GMP_SHA="$(python3 -c 'import json,sys;print(next(d["sha256"] for d in json.load(open(sys.argv[1]))["dependencies"] if d["file"]=="gmp-6.2.1.tar.xz"))' "$NOZZLE_ROOT/engine/fork/android/DEPENDENCIES.json")"
if [ "$(sha256sum "$GMP" 2>/dev/null | cut -d' ' -f1)" != "$GMP_SHA" ]; then
  curl -fsSL --retry 3 -o "$GMP" https://ftp.gnu.org/gnu/gmp/gmp-6.2.1.tar.xz
fi

# GMP's configure needs GNU m4, which the runner doesn't have: build it into the cache (host tool only, nothing ships).
if ! command -v m4 >/dev/null; then
  M4="$CACHE/tools/bin/m4"
  if [ ! -x "$M4" ]; then
    M4_SHA=63aede5c6d33b6d9b13511cd0be2cac046f2e70fd0a07aa9573a04a82783af96 # m4-1.4.19.tar.xz, GNU's signed release
    curl -fsSL --retry 3 -o "$CACHE/m4-1.4.19.tar.xz" https://ftp.gnu.org/gnu/m4/m4-1.4.19.tar.xz
    [ "$(sha256sum "$CACHE/m4-1.4.19.tar.xz" | cut -d' ' -f1)" = "$M4_SHA" ] || { echo "::error::m4-1.4.19.tar.xz does not match its pinned hash"; exit 1; }
    command -v cc >/dev/null || { echo "::error::the runner has neither m4 nor a C compiler to build it; install m4 (apt install m4)"; exit 1; }
    rm -rf "$CACHE/m4-build"; mkdir -p "$CACHE/m4-build"; tar -C "$CACHE/m4-build" -xf "$CACHE/m4-1.4.19.tar.xz"
    (cd "$CACHE/m4-build/m4-1.4.19" && ./configure --prefix="$CACHE/tools" CFLAGS="-O2 -std=gnu17" && make -j"$(nproc)" && make install) > "$CACHE/m4-build.log" 2>&1 \
      || { tail -30 "$CACHE/m4-build.log"; exit 1; }
  fi
  export PATH="$CACHE/tools/bin:$PATH"
fi

NOZZLE_ENGINE_CHECKOUT=/nonexistent NOZZLE_ENGINE_CACHE="$CACHE/nozzle-engine-src" NOZZLE_ANDROID_FORK_ROOT="$CACHE/android-root-fork" \
  ANDROID_DEPS_PREFIX="$DEPS" GMP_ARCHIVE="$GMP" ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.1.12297006}" \
  bash "$NOZZLE_ROOT/engine/fork/android/prepare_engine_root.sh" | tail -1
