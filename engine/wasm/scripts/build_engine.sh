#!/usr/bin/env bash
# Builds libslic3r and the browser engine (nozzle-engine.js + .wasm) from the same pinned, patched OrcaSlicer source the
# Android engine uses (engine/ENGINE_PIN.json). Run after build_deps.sh:
#   heavy-build -- engine/wasm/scripts/build_engine.sh
# Output: $WORK/dist/nozzle-engine.{js,wasm} and a provenance record next to them.
source "$(dirname "$0")/env.sh"

SRC="$WORK/orcaslicer"
# A private copy: the Android engine's checkout is only read, never modified.
rsync -a --delete --exclude .git --exclude resources/profiles --exclude resources/web --exclude resources/images "$ORCA_SRC/" "$SRC/"

# Emscripten needs the same exclusions the Android headless patch makes (no OpenGL/GLFW/CURL finds, no fontconfig,
# no Blosc, no GUI runtime staging). Those guards test ANDROID; extend each one to EMSCRIPTEN too.
for f in "$SRC/CMakeLists.txt" "$SRC/src/CMakeLists.txt" "$SRC/src/libslic3r/CMakeLists.txt"; do
  sed -i -E 's/NOT ANDROID\)/NOT ANDROID AND NOT EMSCRIPTEN)/g; s/if \(ANDROID\)/if (ANDROID OR EMSCRIPTEN)/g' "$f"
done
# Anything else Emscripten needs is a reviewed patch file, applied in order.
for p in "$NOZZLE_ROOT"/engine/wasm/patches/*.patch; do
  [ -e "$p" ] || continue
  patch -d "$SRC" -p1 --forward --silent < "$p" || { echo "patch $p did not apply"; exit 1; }
done

BDIR="$BUILD_DIR/orcaslicer"
mkdir -p "$BDIR"
emcmake cmake -S "$SRC" -B "$BDIR" -GNinja "${CMAKE_COMMON_ARGS[@]}" \
  -DSLIC3R_GUI=OFF -DSLIC3R_CAD=OFF -DSLIC3R_STATIC=ON -DSLIC3R_PCH=OFF -DSLIC3R_BUILD_SANDBOXES=OFF -DBUILD_TESTS=OFF -DORCA_TOOLS=OFF \
  -DCMAKE_CXX_FLAGS="$NOZZLE_WASM_FLAGS -Wno-error=missing-template-arg-list-after-template-kw" \
  -DCMAKE_EXE_LINKER_FLAGS="$LDFLAGS -L$PREFIX/lib" \
  -DBoost_USE_STATIC_LIBS=ON -DBoost_USE_STATIC_RUNTIME=ON -DBoost_ROOT="$PREFIX" -DTBB_DIR="$PREFIX/lib/cmake/TBB" -DOpenCV_DIR="$PREFIX/lib/cmake/opencv4" \
  -DCGAL_DIR="$PREFIX/lib/cmake/CGAL" -DZLIB_ROOT="$PREFIX" -DEXPAT_ROOT="$PREFIX" -DPNG_ROOT="$PREFIX" -DJPEG_ROOT="$PREFIX" \
  -DANDROID_JNI_BRIDGE_DIR="$NOZZLE_ROOT/engine/wasm/bridge"
cmake --build "$BDIR" -j"$JOBS" --target nozzle-engine -- -k 0

DIST="$WORK/dist"; mkdir -p "$DIST"
cp "$BDIR"/android_jni/nozzle-engine.js "$BDIR"/android_jni/nozzle-engine.wasm "$DIST"/
{
  echo "nozzle-engine built $(date -u +%FT%TZ)"
  echo "emscripten: $(emcc --version | head -1)"
  echo "orca source: $(git -C "$ORCA_SRC" rev-parse HEAD 2>/dev/null) + engine/android-headless-engine.patch + engine/wasm (nozzle $(git -C "$NOZZLE_ROOT" rev-parse --short HEAD))"
  echo "flags: $NOZZLE_WASM_FLAGS"
  (cd "$DIST" && sha256sum nozzle-engine.js nozzle-engine.wasm && ls -l nozzle-engine.js nozzle-engine.wasm)
} > "$DIST/PROVENANCE.txt"
cat "$DIST/PROVENANCE.txt"
