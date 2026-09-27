#!/usr/bin/env bash
# Builds the desktop slicing engine (nozzle-engine, Linux x86_64) on Snapmaker Orca's libslic3r (owner decision
# 2026-09-27: the shared engine moves onto Snapmaker Orca, so Full Spectrum mixing is exactly Snapmaker's). The source is
# an untouched export of the pinned commit (engine/snapmaker/ENGINE_PIN.json) plus engine/snapmaker/nozzle-engine.patch;
# the local Snapmaker checkout is only read. Dependencies are that checkout's own deps prefix.
#   heavy-build -- engine/native/scripts/build_engine_snapmaker.sh
# Output: $WORK/dist/nozzle-engine and a provenance record (also copied to engine/snapmaker/PROVENANCE.txt).
set -euo pipefail
NOZZLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PIN="$NOZZLE_ROOT/engine/snapmaker/ENGINE_PIN.json"
pin() { python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print(eval("d"+sys.argv[2]))' "$PIN" "$1"; }
CHECKOUT="${SNAPMAKER_ORCA:-$(pin '["base"]["local_checkout"]')}"
COMMIT="$(pin '["base"]["commit"]')"
PATCH="$NOZZLE_ROOT/$(pin '["patch"]["file"]')"
[ "$(sha256sum "$PATCH" | cut -d' ' -f1)" = "$(pin '["patch"]["sha256"]')" ] || { echo "$PATCH does not match the pinned hash"; exit 1; }
PREFIX="${SNAPMAKER_DEPS:-$CHECKOUT/deps/build/destdir/usr/local}"
WORK="${NOZZLE_NATIVE_SM_WORK:-/mnt/faststorage/build-work/nozzle-native-sm}"
SRC="$WORK/src-$COMMIT"; BDIR="$WORK/build-$COMMIT"; DIST="$WORK/dist"
JOBS="${HEAVY_BUILD_JOBS:-${JOBS:-6}}"

# A clean export of the pinned commit (without the GUI's large resource folders), with the patch applied.
if [ ! -f "$SRC/.nozzle-patched" ]; then
  rm -rf "$SRC"; mkdir -p "$SRC"
  git -C "$CHECKOUT" archive --format=tar "$COMMIT" -- . ':!resources/profiles' ':!resources/web' ':!resources/images' \
    ':!resources/calib' ':!resources/handy_models' | tar -x -C "$SRC"
  patch -d "$SRC" -p1 --forward --silent < "$PATCH"
  sha256sum "$PATCH" > "$SRC/.nozzle-patched"
fi

# pkg-config looks in the prefix first, so its static OpenSSL is used (the system's needs libjitterentropy).
export PKG_CONFIG_PATH="$PREFIX/lib64/pkgconfig:$PREFIX/lib/pkgconfig"
cmake -S "$SRC" -B "$BDIR" -GNinja -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH="$PREFIX" \
  -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DSLIC3R_GUI=OFF -DSLIC3R_CAD=OFF -DSLIC3R_STATIC=ON -DSLIC3R_PCH=OFF -DSLIC3R_BUILD_SANDBOXES=OFF -DBUILD_TESTS=OFF \
  -DORCA_TOOLS=OFF -DSLIC3R_SENTRY=OFF -DCMAKE_C_FLAGS=-fPIC -DCMAKE_CXX_FLAGS=-fPIC \
  "-DCMAKE_EXE_LINKER_FLAGS=-static-libstdc++ -static-libgcc" \
  -DBoost_USE_STATIC_LIBS=ON -DBoost_ROOT="$PREFIX" -DTBB_DIR="$PREFIX/lib/cmake/TBB" -DOpenCV_DIR="$PREFIX/lib/cmake/opencv4" \
  -DCGAL_DIR="$PREFIX/lib/cmake/CGAL" -DOPENSSL_ROOT_DIR="$PREFIX" -DOPENSSL_USE_STATIC_LIBS=ON \
  "-DANDROID_JNI_BRIDGE_DIR=$NOZZLE_ROOT/engine/native/bridge"
cmake --build "$BDIR" -j"$JOBS" --target nozzle-engine

mkdir -p "$DIST"
cp "$BDIR/android_jni/nozzle-engine" "$DIST/"
strip "$DIST/nozzle-engine"
{
  echo "nozzle-engine (native Linux x86_64, Snapmaker Orca base) built $(date -u +%FT%TZ)"
  echo "engine source: Snapmaker Orca $COMMIT + engine/snapmaker/nozzle-engine.patch (sha256 $(sha256sum "$PATCH" | cut -d' ' -f1))"
  echo "bridge: app/src/main/cpp/bridge + engine/native/bridge (nozzle $(git -C "$NOZZLE_ROOT" rev-parse --short HEAD), working tree may differ)"
  echo "compiler: $(gcc --version | head -1); $(cmake --version | head -1)"
  echo "config: Release, SLIC3R_GUI=OFF SLIC3R_CAD=OFF SLIC3R_STATIC=ON; deps: $PREFIX"
  echo "dynamic libraries: $(ldd "$DIST/nozzle-engine" | awk '{print $1}' | tr '\n' ' ')"
  (cd "$DIST" && sha256sum nozzle-engine && ls -l nozzle-engine)
} > "$DIST/PROVENANCE.txt"
cp "$DIST/PROVENANCE.txt" "$NOZZLE_ROOT/engine/snapmaker/PROVENANCE.txt"
cat "$DIST/PROVENANCE.txt"
