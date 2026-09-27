#!/usr/bin/env bash
# Shared environment for building the Nozzle slicing engine as a native Linux x86_64 executable for Nozzle It All
# Desktop. Same pinned, patched OrcaSlicer source as the Android and browser engines (engine/ENGINE_PIN.json), same
# shared bridge (app/src/main/cpp/bridge), host toolchain. Source this from every script.
set -euo pipefail

export NOZZLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

# Read-only inputs. The Android engine's checkout is 824b216f with engine/android-headless-engine.patch applied; the
# archives are the same files ENGINE_PIN.json hashes. The Snapmaker-Orca prefix supplies the dependencies whose
# versions match what 824b216f's own deps/ builds (see build_deps.sh for the list and the ones rebuilt here).
export ANDROID_ENGINE_ROOT="${ANDROID_ENGINE_ROOT:-/mnt/faststorage/orcaslicer-android-engine}"
export ARCHIVES="$ANDROID_ENGINE_ROOT/deps/src"
export ORCA_SRC="${ORCA_SRC:-$ANDROID_ENGINE_ROOT/orcaslicer}"
export SNAPMAKER_DEPS="${SNAPMAKER_DEPS:-/mnt/faststorage/Snapmaker-Orca/OrcaSlicer/deps/build/destdir/usr/local}"

export WORK="${NOZZLE_NATIVE_WORK:-/mnt/faststorage/build-work/nozzle-native}"
export SRC_DIR="$WORK/src"
export BUILD_DIR="$WORK/build"
export PREFIX="$WORK/deps/usr/local"
mkdir -p "$SRC_DIR" "$BUILD_DIR" "$PREFIX"
export JOBS="${HEAVY_BUILD_JOBS:-${JOBS:-6}}"

# Generic x86-64 code (no -march), so the binary runs on any x86_64 desktop. Release adds -O3 -DNDEBUG.
export NOZZLE_NATIVE_FLAGS="-fPIC"
# libstdc++/libgcc are linked statically so the engine does not need the build host's (newer) C++ runtime.
export NOZZLE_NATIVE_LDFLAGS="-static-libstdc++ -static-libgcc"

CMAKE_COMMON_ARGS=(
  "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"
  "-DCMAKE_BUILD_TYPE=Release"
  "-DCMAKE_INSTALL_PREFIX=$PREFIX"
  "-DCMAKE_PREFIX_PATH=$PREFIX"
  "-DCMAKE_INSTALL_LIBDIR=lib"
  "-DBUILD_SHARED_LIBS=OFF"
  "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
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

echo "== nozzle native env: $(gcc --version | head -1) PREFIX=$PREFIX JOBS=$JOBS =="
