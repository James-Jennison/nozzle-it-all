#!/usr/bin/env bash
# Shared environment for building the Nozzle slicing engine for the browser (WebAssembly, Emscripten).
# Mirrors the Android dependency cross-compile (/mnt/faststorage/orcaslicer-android-engine/scripts/env.sh): same pinned
# dependency archives (engine/fork/android/DEPENDENCIES.json), different toolchain. Source this from every script.
set -euo pipefail

export NOZZLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
export EMSDK="${EMSDK:-/mnt/faststorage/emsdk}"
# shellcheck disable=SC1091
source "$EMSDK/emsdk_env.sh" >/dev/null 2>&1

# Pinned source archives, read-only. They are the same files DEPENDENCIES.json hashes; we extract our own copies.
export EMSCRIPTEN="$EMSDK/upstream/emscripten"
export ANDROID_ENGINE_ROOT="${ANDROID_ENGINE_ROOT:-/mnt/faststorage/orcaslicer-android-engine}"
export ARCHIVES="$ANDROID_ENGINE_ROOT/deps/src"

export WORK="${NOZZLE_WASM_WORK:-/mnt/faststorage/build-work/nozzle-wasm}"
export SRC_DIR="$WORK/src"
export BUILD_DIR="$WORK/build"
export PREFIX="$WORK/install"
mkdir -p "$SRC_DIR" "$BUILD_DIR" "$PREFIX/lib" "$PREFIX/include"
export JOBS="${HEAVY_BUILD_JOBS:-${JOBS:-6}}"

# One set of code-generation flags for every library, so objects link together:
#  -pthread               oneTBB (and so libslic3r's parallel slicing) needs real threads; the page must be
#                         cross-origin isolated (COOP/COEP) to get SharedArrayBuffer.
#  -fwasm-exceptions      libslic3r reports failures with C++ exceptions; native wasm exception handling is
#                         smaller and faster than the JS-emulated kind.
#  -msimd128              wasm SIMD, supported by every browser the Web App targets.
export NOZZLE_WASM_FLAGS="-pthread -fwasm-exceptions -msimd128 -O2"
export CFLAGS="$NOZZLE_WASM_FLAGS"
export CXXFLAGS="$NOZZLE_WASM_FLAGS"
export LDFLAGS="-pthread -fwasm-exceptions"

CMAKE_COMMON_ARGS=(
  "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"
  "-DCMAKE_BUILD_TYPE=Release"
  "-DCMAKE_INSTALL_PREFIX=$PREFIX"
  "-DCMAKE_PREFIX_PATH=$PREFIX"
  "-DCMAKE_FIND_ROOT_PATH=$PREFIX"
  "-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH"
  "-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH"
  "-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH"
  "-DBUILD_SHARED_LIBS=OFF"
  "-DCMAKE_C_FLAGS=$NOZZLE_WASM_FLAGS"
  "-DCMAKE_CXX_FLAGS=$NOZZLE_WASM_FLAGS"
  "-DCMAKE_EXE_LINKER_FLAGS=$LDFLAGS"
)

# extract <archive-file-name> <directory-it-creates>
extract() {
  if [ ! -d "$SRC_DIR/$2" ]; then
    echo "-- extracting $1"
    case "$1" in
      *.zip) (cd "$SRC_DIR" && unzip -q "$ARCHIVES/$1") ;;
      *) tar -C "$SRC_DIR" -xf "$ARCHIVES/$1" ;;
    esac
  fi
}

# stamp <name>: skip a dependency that already built successfully with these flags.
done_stamp() { [ -f "$BUILD_DIR/.done-$1" ] && [ "$(cat "$BUILD_DIR/.done-$1")" = "$NOZZLE_WASM_FLAGS" ]; }
mark_done() { echo "$NOZZLE_WASM_FLAGS" > "$BUILD_DIR/.done-$1"; }

echo "== nozzle wasm env: emcc $(emcc --version | head -1 | awk '{print $NF}') PREFIX=$PREFIX JOBS=$JOBS =="
