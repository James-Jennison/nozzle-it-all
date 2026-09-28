#!/usr/bin/env bash
# Prepares the Android engine root, in the layout app/src/main/cpp/CMakeLists.txt expects of ORCASLICER_ENGINE_ROOT:
#   $ROOT/orcaslicer               export of the pinned nozzle-engine commit (engine/fork/ENGINE_PIN.json), with its own
#                                  JNI bridge (nozzle/bridge/android)
#   $ROOT/deps/install/arm64-v8a   the NDK dependency prefix of orcaslicer-android-engine (read only), reused as a tree of
#                                  symlinks (Boost 1.86, CGAL 5.6.3, oneTBB 2021.13, OCCT 7.6, OpenVDB 11, OpenCV 4.6 ...),
#                                  plus GMP 6.2.1 rebuilt with its C++ classes (gmpxx.h, libgmpxx.a), which the engine's
#                                  bundled libigl needs (mpq_class). Same GMP source, same NDK, same configure otherwise.
# The archives behind the prefix are pinned in engine/fork/android/DEPENDENCIES.json.
# Overridable: NOZZLE_ANDROID_FORK_ROOT, ANDROID_DEPS_PREFIX, GMP_ARCHIVE, ANDROID_NDK_ROOT (CI sets them: ci_engine_root.sh).
# Prints the engine root on its last line. Then:  heavy-gradle :app:assembleDebug
set -euo pipefail
NOZZLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ROOT="${NOZZLE_ANDROID_FORK_ROOT:-/mnt/faststorage/build-work/nozzle-android-fork}"
ANDROID_DEPS="${ANDROID_DEPS_PREFIX:-/mnt/faststorage/orcaslicer-android-engine/deps/install/arm64-v8a}"
mkdir -p "$ROOT"
"$NOZZLE_ROOT/engine/fork/export_source.sh" "$ROOT/orcaslicer" >&2

# The dependency prefix: a symlink tree over the read-only Android prefix, with GMP (C++ classes enabled) built into it.
[ -L "$ROOT/deps" ] && rm "$ROOT/deps"   # an earlier layout linked the Snapmaker base's prefix
DEPS="$ROOT/deps/install/arm64-v8a"
if [ ! -d "$DEPS" ]; then mkdir -p "$DEPS"; cp -as "$ANDROID_DEPS/." "$DEPS/"; fi
if [ ! -f "$DEPS/include/gmpxx.h" ]; then
  NDK="${ANDROID_NDK_ROOT:-$HOME/Android/Sdk/ndk/27.1.12297006}"; TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
  GMP_ARCHIVE="${GMP_ARCHIVE:-$(dirname "$(dirname "$ANDROID_DEPS")")/src/gmp-6.2.1.tar.xz}"
  GMP_SHA="$(python3 -c 'import json,sys;print(next(d["sha256"] for d in json.load(open(sys.argv[1]))["dependencies"] if d["file"]=="gmp-6.2.1.tar.xz"))' "$NOZZLE_ROOT/engine/fork/android/DEPENDENCIES.json")"
  [ "$(sha256sum "$GMP_ARCHIVE" | cut -d' ' -f1)" = "$GMP_SHA" ] || { echo "$GMP_ARCHIVE does not match the pinned GMP hash"; exit 1; }
  B="$ROOT/deps/build/gmp"; STAGE="$ROOT/deps/build/gmp-stage"; rm -rf "$B" "$STAGE"; mkdir -p "$B"
  tar -C "$ROOT/deps/build" -xf "$GMP_ARCHIVE" && rm -rf "$ROOT/deps/build/gmp-src" && mv "$ROOT/deps/build/gmp-6.2.1" "$ROOT/deps/build/gmp-src"
  # As orcaslicer-android-engine/scripts/build_gmp.sh, plus --enable-cxx.
  (cd "$B" && "$ROOT/deps/build/gmp-src/configure" --host=aarch64-linux-android --prefix="$STAGE" --disable-shared --enable-static --with-pic \
      --enable-cxx CC="$TC/aarch64-linux-android28-clang" CXX="$TC/aarch64-linux-android28-clang++" AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" \
    && make -j"${HEAVY_BUILD_JOBS:-6}" && make install) > "$ROOT/deps/build/gmp.log" 2>&1 || { tail -30 "$ROOT/deps/build/gmp.log"; exit 1; }
  # Replace the linked GMP files with the rebuilt ones (never writing through a symlink into the read-only prefix).
  (cd "$STAGE" && find include lib -type f) | while read -r f; do rm -f "$DEPS/$f"; mkdir -p "$DEPS/$(dirname "$f")"; cp "$STAGE/$f" "$DEPS/$f"; done
fi
echo "engine root ready: $ROOT (nozzle-engine $(cat "$ROOT/orcaslicer/.nozzle-engine-commit"); deps: $ANDROID_DEPS + GMP with C++ classes)" >&2
echo "$ROOT"
