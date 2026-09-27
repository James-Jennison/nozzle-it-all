#!/usr/bin/env bash
# Builds the browser engine (nozzle-engine.js + .wasm) on Snapmaker Orca's libslic3r (owner decision 2026-09-27: every
# platform slices on the same base as the desktop engine). Source = an untouched export of the pinned commit
# (engine/snapmaker/ENGINE_PIN.json) + engine/snapmaker/nozzle-engine.patch (which already carries the EMSCRIPTEN CMake
# guards) + the browser patches in engine/wasm/patches that apply to this base. Dependencies are the existing browser
# prefix built by build_deps.sh (unchanged). The upstream-base build (build_engine.sh) is left as it was.
#   heavy-build -- engine/wasm/scripts/build_engine_snapmaker.sh
# Output: $SM_WORK/dist/nozzle-engine.{js,wasm} + PROVENANCE.txt; with INSTALL=1 also copied into web/public/engine (the
# engine the Web App serves; the upstream-base engine stays in /mnt/faststorage/build-work/nozzle-wasm/dist).
source "$(dirname "$0")/env.sh"

PIN="$NOZZLE_ROOT/engine/snapmaker/ENGINE_PIN.json"
pin() { python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print(eval("d"+sys.argv[2]))' "$PIN" "$1"; }
CHECKOUT="${SNAPMAKER_ORCA:-$(pin '["base"]["local_checkout"]')}"
COMMIT="$(pin '["base"]["commit"]')"
PATCH="$NOZZLE_ROOT/$(pin '["patch"]["file"]')"
[ "$(sha256sum "$PATCH" | cut -d' ' -f1)" = "$(pin '["patch"]["sha256"]')" ] || { echo "$PATCH does not match the pinned hash"; exit 1; }
# 0004 (TextureToColor) patches a file only upstream OrcaSlicer has; 0001-0003 apply to this base unchanged. Browser
# fixes only this base needs are in engine/wasm/patches-snapmaker (kept out of patches/, which build_engine.sh applies
# wholesale to the upstream base).
WASM_PATCHES=(patches/0001-tbbmalloc-stand-in patches/0002-wasm32-bead-count-saturation patches/0003-emscripten-platform-guards
  patches-snapmaker/0001-wasm32-nop-layer-id)

SM_WORK="${NOZZLE_WASM_SM_WORK:-/mnt/faststorage/build-work/nozzle-wasm-sm}"
SRC="$SM_WORK/src-$COMMIT"; BDIR="$SM_WORK/build-$COMMIT"; DIST="$SM_WORK/dist"
PATCH_FILES=("$PATCH" "$NOZZLE_ROOT/engine/snapmaker/libcxx-includes.patch"); for p in "${WASM_PATCHES[@]}"; do PATCH_FILES+=("$NOZZLE_ROOT/engine/wasm/$p.patch"); done
STAMP="$(cat "${PATCH_FILES[@]}" | sha256sum | cut -d' ' -f1) $COMMIT"
if [ "$(cat "$SRC/.nozzle-patched" 2>/dev/null)" != "$STAMP" ]; then
  rm -rf "$SRC"; mkdir -p "$SRC"
  git -C "$CHECKOUT" archive --format=tar "$COMMIT" -- . ':!resources/profiles' ':!resources/web' ':!resources/images' \
    ':!resources/calib' ':!resources/handy_models' | tar -x -C "$SRC"
  patch -d "$SRC" -p1 --forward --silent < "$PATCH"
  # libc++ fix shared with the Android engine.
  patch -d "$SRC" -p1 --forward --silent < "$NOZZLE_ROOT/engine/snapmaker/libcxx-includes.patch"
  for p in "${WASM_PATCHES[@]}"; do
    patch -d "$SRC" -p1 --forward --silent < "$NOZZLE_ROOT/engine/wasm/$p.patch" || { echo "patch $p did not apply"; exit 1; }
  done
  echo "$STAMP" > "$SRC/.nozzle-patched"
fi

mkdir -p "$BDIR"
# USE_BLOSC=OFF: the browser OpenVDB has no Blosc, and this base's FindOpenVDB.cmake assumes a static libopenvdb.a has it.
emcmake cmake -S "$SRC" -B "$BDIR" -GNinja "${CMAKE_COMMON_ARGS[@]}" \
  -DSLIC3R_GUI=OFF -DSLIC3R_CAD=OFF -DSLIC3R_STATIC=ON -DSLIC3R_PCH=OFF -DSLIC3R_BUILD_SANDBOXES=OFF -DBUILD_TESTS=OFF -DORCA_TOOLS=OFF \
  -DSLIC3R_SENTRY=OFF -DUSE_BLOSC=OFF \
  -DCMAKE_CXX_FLAGS="$NOZZLE_WASM_FLAGS -Wno-error=missing-template-arg-list-after-template-kw" \
  -DCMAKE_EXE_LINKER_FLAGS="$LDFLAGS -L$PREFIX/lib" \
  -DBoost_USE_STATIC_LIBS=ON -DBoost_USE_STATIC_RUNTIME=ON -DBoost_ROOT="$PREFIX" -DTBB_DIR="$PREFIX/lib/cmake/TBB" -DOpenCV_DIR="$PREFIX/lib/cmake/opencv4" \
  -DCGAL_DIR="$PREFIX/lib/cmake/CGAL" -DZLIB_ROOT="$PREFIX" -DEXPAT_ROOT="$PREFIX" -DPNG_ROOT="$PREFIX" -DJPEG_ROOT="$PREFIX" \
  -DANDROID_JNI_BRIDGE_DIR="$NOZZLE_ROOT/engine/wasm/bridge"
cmake --build "$BDIR" -j"$JOBS" --target nozzle-engine -- -k 0

mkdir -p "$DIST"
cp "$BDIR"/android_jni/nozzle-engine.js "$BDIR"/android_jni/nozzle-engine.wasm "$DIST"/
{
  echo "nozzle-engine (browser, Snapmaker Orca base) built $(date -u +%FT%TZ)"
  echo "emscripten: $(emcc --version | head -1)"
  echo "engine source: Snapmaker Orca $COMMIT + engine/snapmaker/nozzle-engine.patch (sha256 $(sha256sum "$PATCH" | cut -d' ' -f1)) + engine/snapmaker/libcxx-includes.patch + engine/wasm/{${WASM_PATCHES[*]}}"
  echo "bridge: app/src/main/cpp/bridge + engine/wasm/bridge (nozzle $(git -C "$NOZZLE_ROOT" rev-parse --short HEAD), working tree may differ)"
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
