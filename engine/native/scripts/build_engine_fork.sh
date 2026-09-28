#!/usr/bin/env bash
# Builds the desktop slicing engine (nozzle-engine, Linux x86_64) from the pinned nozzle-engine commit
# (engine/fork/ENGINE_PIN.json, P-0020). The engine carries its own bridges (nozzle/bridge/native), so nothing from this
# repository is compiled in. Dependencies: Snapmaker Orca's own deps prefix (engine/fork/ENGINE_PIN.json desktop_deps_prefix).
#   heavy-build -- engine/native/scripts/build_engine_fork.sh
# Output: $WORK/dist/nozzle-engine and a provenance record (also copied to engine/fork/PROVENANCE.txt).
set -euo pipefail
NOZZLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PREFIX="${SNAPMAKER_DEPS:-$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["desktop_deps_prefix"])' "$NOZZLE_ROOT/engine/fork/ENGINE_PIN.json")}"
WORK="${NOZZLE_NATIVE_FORK_WORK:-/mnt/faststorage/build-work/nozzle-native-fork}"
SRC="$WORK/src"; BDIR="$WORK/build"; DIST="$WORK/dist"
JOBS="${HEAVY_BUILD_JOBS:-${JOBS:-6}}"
"$NOZZLE_ROOT/engine/fork/export_source.sh" "$SRC"
COMMIT="$(cat "$SRC/.nozzle-engine-commit")"

# pkg-config looks in the prefix first, so its static OpenSSL is used (the system's needs libjitterentropy).
export PKG_CONFIG_PATH="$PREFIX/lib64/pkgconfig:$PREFIX/lib/pkgconfig"
cmake -S "$SRC" -B "$BDIR" -GNinja -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH="$PREFIX" \
  -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DSLIC3R_GUI=OFF -DSLIC3R_CAD=OFF -DSLIC3R_STATIC=ON -DSLIC3R_PCH=OFF -DSLIC3R_BUILD_SANDBOXES=OFF -DBUILD_TESTS=OFF \
  -DORCA_TOOLS=OFF -DSLIC3R_SENTRY=OFF -DCMAKE_C_FLAGS=-fPIC -DCMAKE_CXX_FLAGS=-fPIC \
  "-DCMAKE_EXE_LINKER_FLAGS=-static-libstdc++ -static-libgcc" \
  -DBoost_USE_STATIC_LIBS=ON -DBoost_ROOT="$PREFIX" -DTBB_DIR="$PREFIX/lib/cmake/TBB" -DOpenCV_DIR="$PREFIX/lib/cmake/opencv4" \
  -DCGAL_DIR="$PREFIX/lib/cmake/CGAL" -DOPENSSL_ROOT_DIR="$PREFIX" -DOPENSSL_USE_STATIC_LIBS=ON \
  "-DANDROID_JNI_BRIDGE_DIR=$SRC/nozzle/bridge/native"
cmake --build "$BDIR" -j"$JOBS" --target nozzle-engine

mkdir -p "$DIST"
cp "$BDIR/android_jni/nozzle-engine" "$DIST/"
strip "$DIST/nozzle-engine"
{
  echo "nozzle-engine (native Linux x86_64, nozzle-engine base) built $(date -u +%FT%TZ)"
  echo "engine source: nozzle-engine $COMMIT (engine/fork/ENGINE_PIN.json), bridges from the engine (nozzle/bridge)"
  echo "compiler: $(gcc --version | head -1); $(cmake --version | head -1)"
  echo "config: Release, SLIC3R_GUI=OFF SLIC3R_CAD=OFF SLIC3R_STATIC=ON; deps: $PREFIX"
  echo "dynamic libraries: $(ldd "$DIST/nozzle-engine" | awk '{print $1}' | tr '\n' ' ')"
  (cd "$DIST" && sha256sum nozzle-engine && ls -l nozzle-engine)
} > "$DIST/PROVENANCE.txt"
cp "$DIST/PROVENANCE.txt" "$NOZZLE_ROOT/engine/fork/PROVENANCE.txt"
cat "$DIST/PROVENANCE.txt"
