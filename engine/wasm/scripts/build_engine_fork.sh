#!/usr/bin/env bash
# Builds the browser engine (nozzle-engine.js + .wasm) from the pinned nozzle-engine commit (engine/fork/ENGINE_PIN.json,
# P-0020). The engine already contains the browser patches and its own worker bridge (nozzle/bridge/wasm); the dependency
# prefix is the existing browser prefix (build_deps.sh), unchanged.
#   heavy-build -- engine/wasm/scripts/build_engine_fork.sh
# Output: $FORK_WORK/dist/nozzle-engine.{js,wasm} + PROVENANCE.txt; with INSTALL=1 also copied into web/public/engine.
source "$(dirname "$0")/env.sh"

FORK_WORK="${NOZZLE_WASM_FORK_WORK:-/mnt/faststorage/build-work/nozzle-wasm-fork}"
SRC="$FORK_WORK/src"; BDIR="$FORK_WORK/build"; DIST="$FORK_WORK/dist"
"$NOZZLE_ROOT/engine/fork/export_source.sh" "$SRC"
COMMIT="$(cat "$SRC/.nozzle-engine-commit")"

mkdir -p "$BDIR"
# USE_BLOSC=OFF: the browser OpenVDB has no Blosc, and this base's FindOpenVDB.cmake assumes a static libopenvdb.a has it.
emcmake cmake -S "$SRC" -B "$BDIR" -GNinja "${CMAKE_COMMON_ARGS[@]}" \
  -DSLIC3R_GUI=OFF -DSLIC3R_CAD=OFF -DSLIC3R_STATIC=ON -DSLIC3R_PCH=OFF -DSLIC3R_BUILD_SANDBOXES=OFF -DBUILD_TESTS=OFF -DORCA_TOOLS=OFF \
  -DSLIC3R_SENTRY=OFF -DUSE_BLOSC=OFF \
  -DCMAKE_CXX_FLAGS="$NOZZLE_WASM_FLAGS -Wno-error=missing-template-arg-list-after-template-kw" \
  -DCMAKE_EXE_LINKER_FLAGS="$LDFLAGS -L$PREFIX/lib" \
  -DBoost_USE_STATIC_LIBS=ON -DBoost_USE_STATIC_RUNTIME=ON -DBoost_ROOT="$PREFIX" -DTBB_DIR="$PREFIX/lib/cmake/TBB" -DOpenCV_DIR="$PREFIX/lib/cmake/opencv4" \
  -DCGAL_DIR="$PREFIX/lib/cmake/CGAL" -DZLIB_ROOT="$PREFIX" -DEXPAT_ROOT="$PREFIX" -DPNG_ROOT="$PREFIX" -DJPEG_ROOT="$PREFIX" \
  -DANDROID_JNI_BRIDGE_DIR="$SRC/nozzle/bridge/wasm"
cmake --build "$BDIR" -j"$JOBS" --target nozzle-engine -- -k 0

mkdir -p "$DIST"
cp "$BDIR"/android_jni/nozzle-engine.js "$BDIR"/android_jni/nozzle-engine.wasm "$DIST"/
{
  echo "nozzle-engine (browser, nozzle-engine base) built $(date -u +%FT%TZ)"
  echo "emscripten: $(emcc --version | head -1)"
  echo "engine source: nozzle-engine $COMMIT (engine/fork/ENGINE_PIN.json), bridges from the engine (nozzle/bridge)"
  echo "flags: $NOZZLE_WASM_FLAGS; deps: $PREFIX"
  (cd "$DIST" && sha256sum nozzle-engine.js nozzle-engine.wasm && ls -l nozzle-engine.js nozzle-engine.wasm)
} > "$DIST/PROVENANCE.txt"
cat "$DIST/PROVENANCE.txt"
if [ "${INSTALL:-0}" = 1 ]; then
  OUT="$NOZZLE_ROOT/web/public/engine"; mkdir -p "$OUT"
  cp "$DIST"/nozzle-engine.js "$DIST"/nozzle-engine.wasm "$DIST"/PROVENANCE.txt "$OUT"/
  (cd "$OUT" && sha256sum nozzle-engine.js nozzle-engine.wasm > SHA256SUMS)
  echo "installed into $OUT"
fi
