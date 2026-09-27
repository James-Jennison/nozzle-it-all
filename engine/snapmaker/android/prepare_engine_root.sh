#!/usr/bin/env bash
# Prepares the Android engine root for the Snapmaker Orca base (owner decision 2026-09-27: every platform slices on
# Snapmaker Orca's libslic3r + engine/snapmaker/nozzle-engine.patch, like the desktop engine). The result has the same
# layout app/src/main/cpp/CMakeLists.txt expects of any ORCASLICER_ENGINE_ROOT:
#   $ROOT/orcaslicer               untouched export of the pinned commit (engine/snapmaker/ENGINE_PIN.json) + the patch
#                                  + engine/snapmaker/libcxx-includes.patch (libc++, shared with the browser engine)
#                                  + engine/snapmaker/android/*.patch (Android-only addenda)
#   $ROOT/deps/install/arm64-v8a   the NDK dependency prefix of orcaslicer-android-engine (read only), reused as a tree
#                                  of symlinks (Boost 1.86, CGAL 5.6.3, oneTBB 2021.13, OCCT 7.6, OpenVDB 11, OpenCV 4.6 ...
#                                  all work with the Snapmaker base), plus the one dependency that had to change: GMP
#                                  6.2.1 rebuilt with its C++ classes (gmpxx.h, libgmpxx.a), because the Snapmaker base's
#                                  bundled libigl uses mpq_class. Same GMP source, same NDK, same configure otherwise.
# Then build the app with it:   heavy-gradle -PnozzleEngine=snapmaker :app:assembleDebug
#   (or ORCASLICER_ENGINE_ROOT=$ROOT). The Snapmaker checkout and the android engine project are only read.
set -euo pipefail
NOZZLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PIN="$NOZZLE_ROOT/engine/snapmaker/ENGINE_PIN.json"
pin() { python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print(eval("d"+sys.argv[2]))' "$PIN" "$1"; }
CHECKOUT="${SNAPMAKER_ORCA:-$(pin '["base"]["local_checkout"]')}"
COMMIT="$(pin '["base"]["commit"]')"
PATCH="$NOZZLE_ROOT/$(pin '["patch"]["file"]')"
[ "$(sha256sum "$PATCH" | cut -d' ' -f1)" = "$(pin '["patch"]["sha256"]')" ] || { echo "$PATCH does not match the pinned hash"; exit 1; }
ROOT="${NOZZLE_ANDROID_SM_ROOT:-/mnt/faststorage/build-work/nozzle-android-sm}"
ANDROID_DEPS="${ANDROID_DEPS_PREFIX:-/mnt/faststorage/orcaslicer-android-engine/deps/install/arm64-v8a}"
# Addenda: the libc++ fix shared with the browser engine, then the Android-only ones (engine/snapmaker/android/*.patch).
shopt -s nullglob; ADDENDA=("$NOZZLE_ROOT/engine/snapmaker/libcxx-includes.patch" "$NOZZLE_ROOT"/engine/snapmaker/android/*.patch)

# Stamp = patch hash + addenda hashes, so an edited patch re-exports the source.
STAMP="$(cat "$PATCH" "${ADDENDA[@]}" | sha256sum | cut -d' ' -f1) $COMMIT"
SRC="$ROOT/orcaslicer"
if [ "$(cat "$SRC/.nozzle-patched" 2>/dev/null)" != "$STAMP" ]; then
  rm -rf "$SRC"; mkdir -p "$SRC"
  git -C "$CHECKOUT" archive --format=tar "$COMMIT" -- . ':!resources/profiles' ':!resources/web' ':!resources/images' \
    ':!resources/calib' ':!resources/handy_models' | tar -x -C "$SRC"
  patch -d "$SRC" -p1 --forward --silent < "$PATCH"
  for p in "${ADDENDA[@]}"; do patch -d "$SRC" -p1 --forward --silent < "$p" || { echo "addendum $p did not apply"; exit 1; }; done
  echo "$STAMP" > "$SRC/.nozzle-patched"
fi
# The dependency prefix: a symlink tree over the read-only Android prefix, with GMP (C++ classes enabled) built into it.
DEPS="$ROOT/deps/install/arm64-v8a"
[ -L "$DEPS" ] && rm "$DEPS"   # an earlier layout linked the whole prefix
if [ ! -d "$DEPS" ]; then mkdir -p "$DEPS"; cp -as "$ANDROID_DEPS/." "$DEPS/"; fi
if [ ! -f "$DEPS/include/gmpxx.h" ]; then
  NDK="${ANDROID_NDK_ROOT:-$HOME/Android/Sdk/ndk/27.1.12297006}"; TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
  GMP_ARCHIVE="$(dirname "$(dirname "$ANDROID_DEPS")")/src/gmp-6.2.1.tar.xz"
  B="$ROOT/deps/build/gmp"; STAGE="$ROOT/deps/build/gmp-stage"; rm -rf "$B" "$STAGE"; mkdir -p "$B"
  tar -C "$ROOT/deps/build" -xf "$GMP_ARCHIVE" && rm -rf "$ROOT/deps/build/gmp-src" && mv "$ROOT/deps/build/gmp-6.2.1" "$ROOT/deps/build/gmp-src"
  # As orcaslicer-android-engine/scripts/build_gmp.sh, plus --enable-cxx.
  (cd "$B" && "$ROOT/deps/build/gmp-src/configure" --host=aarch64-linux-android --prefix="$STAGE" --disable-shared --enable-static --with-pic \
      --enable-cxx CC="$TC/aarch64-linux-android28-clang" CXX="$TC/aarch64-linux-android28-clang++" AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" \
    && make -j"${HEAVY_BUILD_JOBS:-6}" && make install) > "$ROOT/deps/build/gmp.log" 2>&1 || { tail -30 "$ROOT/deps/build/gmp.log"; exit 1; }
  # Replace the linked GMP files with the rebuilt ones (never writing through a symlink into the read-only prefix).
  (cd "$STAGE" && find include lib -type f) | while read -r f; do rm -f "$DEPS/$f"; mkdir -p "$DEPS/$(dirname "$f")"; cp "$STAGE/$f" "$DEPS/$f"; done
fi
echo "engine root ready: $ROOT (Snapmaker Orca $COMMIT + $(basename "$PATCH")$([ ${#ADDENDA[@]} -gt 0 ] && echo " + ${#ADDENDA[@]} addenda"); deps: $ANDROID_DEPS + GMP with C++ classes)"
