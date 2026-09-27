#!/usr/bin/env bash
# Builds libslic3r and the native desktop engine (nozzle-engine, Linux x86_64) from the same pinned, patched OrcaSlicer
# source the Android and browser engines use (engine/ENGINE_PIN.json). Run after build_deps.sh:
#   heavy-build -- engine/native/scripts/build_engine.sh
# Output: $WORK/dist/nozzle-engine and a provenance record next to it (also copied to engine/native/PROVENANCE.txt).
source "$(dirname "$0")/env.sh"

SRC="$WORK/orcaslicer"
# A private copy: the Android engine's checkout is only read, never modified. It already carries
# engine/android-headless-engine.patch (verified below against the pinned hash).
PIN_PATCH_SHA=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["patch"]["sha256"])' "$NOZZLE_ROOT/engine/ENGINE_PIN.json")
[ "$(git -C "$ORCA_SRC" diff | sha256sum | cut -d' ' -f1)" = "$PIN_PATCH_SHA" ] || { echo "$ORCA_SRC is not 824b216f + the pinned headless patch"; exit 1; }
rsync -a --delete --exclude .git --exclude resources/profiles --exclude resources/web --exclude resources/images "$ORCA_SRC/" "$SRC/"

# Anything the native build needs beyond the headless patch is a reviewed patch file, applied in order.
for p in "$NOZZLE_ROOT"/engine/native/patches/*.patch; do
  [ -e "$p" ] || continue
  patch -d "$SRC" -p1 --forward --silent < "$p" || { echo "patch $p did not apply"; exit 1; }
done

BDIR="$BUILD_DIR/orcaslicer"
mkdir -p "$BDIR"
cmake -S "$SRC" -B "$BDIR" -GNinja "${CMAKE_COMMON_ARGS[@]}" \
  -DSLIC3R_GUI=OFF -DSLIC3R_CAD=OFF -DSLIC3R_STATIC=ON -DSLIC3R_PCH=OFF -DSLIC3R_BUILD_SANDBOXES=OFF -DBUILD_TESTS=OFF -DORCA_TOOLS=OFF \
  -DCMAKE_C_FLAGS="$NOZZLE_NATIVE_FLAGS" -DCMAKE_CXX_FLAGS="$NOZZLE_NATIVE_FLAGS" \
  -DCMAKE_EXE_LINKER_FLAGS="$NOZZLE_NATIVE_LDFLAGS" \
  -DBoost_USE_STATIC_LIBS=ON -DBoost_ROOT="$PREFIX" -DTBB_DIR="$PREFIX/lib/cmake/TBB" -DOpenCV_DIR="$PREFIX/lib/cmake/opencv4" \
  -DCGAL_DIR="$PREFIX/lib/cmake/CGAL" -DOPENSSL_ROOT_DIR="$PREFIX" -DOPENSSL_USE_STATIC_LIBS=ON \
  -DANDROID_JNI_BRIDGE_DIR="$NOZZLE_ROOT/engine/native/bridge"
cmake --build "$BDIR" -j"$JOBS" --target nozzle-engine

DIST="$WORK/dist"; mkdir -p "$DIST"
cp "$BDIR"/android_jni/nozzle-engine "$DIST"/
strip "$DIST/nozzle-engine"
{
  echo "nozzle-engine (native Linux x86_64) built $(date -u +%FT%TZ)"
  echo "orca source: $(git -C "$ORCA_SRC" rev-parse HEAD) + engine/android-headless-engine.patch (sha256 $PIN_PATCH_SHA)"
  echo "native patches: $(cd "$NOZZLE_ROOT/engine/native" && ls patches/*.patch 2>/dev/null | tr '\n' ' ' || true)(none if blank)"
  echo "bridge: app/src/main/cpp/bridge + engine/native/bridge (nozzle $(git -C "$NOZZLE_ROOT" rev-parse --short HEAD), working tree may differ)"
  echo "compiler: $(gcc --version | head -1); $(cmake --version | head -1)"
  echo "flags: CMAKE_BUILD_TYPE=Release (-O3 -DNDEBUG) $NOZZLE_NATIVE_FLAGS; link $NOZZLE_NATIVE_LDFLAGS; stripped"
  echo "config: SLIC3R_GUI=OFF SLIC3R_CAD=OFF SLIC3R_STATIC=ON"
  echo "deps: Snapmaker-Orca prefix copy + Eigen 5.0.1, CGAL 5.6.3, Draco 1.5.7, Assimp 5.4.3, OpenSSL 1.1.1w (engine/native/scripts/build_deps.sh)"
  echo "dynamic libraries: $(ldd "$DIST/nozzle-engine" | awk '{print $1}' | tr '\n' ' ')"
  (cd "$DIST" && sha256sum nozzle-engine && ls -l nozzle-engine)
} > "$DIST/PROVENANCE.txt"
cp "$DIST/PROVENANCE.txt" "$NOZZLE_ROOT/engine/native/PROVENANCE.txt"
cat "$DIST/PROVENANCE.txt"
