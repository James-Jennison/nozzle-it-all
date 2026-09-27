#!/usr/bin/env bash
# Builds every libslic3r dependency for WebAssembly into $PREFIX. Same libraries and versions as the Android engine
# (see its scripts/build_*.sh); options mirror those scripts except where Emscripten needs something different, which
# is commented at that line. Usage:  heavy-build -- engine/wasm/scripts/build_deps.sh [dep ...]
source "$(dirname "$0")/env.sh"

cm() { # cm <name> <source-dir> [cmake args...]
  local name=$1 src=$2; shift 2
  local b="$BUILD_DIR/$name"; rm -rf "$b"; mkdir -p "$b"
  emcmake cmake -S "$src" -B "$b" -GNinja "${CMAKE_COMMON_ARGS[@]}" "$@"
  cmake --build "$b" -j"$JOBS"
  cmake --install "$b"
}

dep_zlib() { # zlib's CMake emits libz.a twice under Emscripten (shared falls back to static); use its configure.
  extract zlib-1.3.1.tar.gz zlib-1.3.1
  local b="$BUILD_DIR/zlib"; rm -rf "$b"; cp -r "$SRC_DIR/zlib-1.3.1" "$b"; cd "$b"
  CHOST=wasm32 emconfigure ./configure --static --prefix="$PREFIX"; emmake make -j"$JOBS" libz.a; emmake make install
}
dep_png()    { extract libpng-1.6.35.tar.gz libpng-1.6.35; cm png "$SRC_DIR/libpng-1.6.35" -DPNG_SHARED=OFF -DPNG_STATIC=ON -DPNG_PREFIX=prusaslicer_ -DPNG_TESTS=OFF -DPNG_ARM_NEON=off -DZLIB_ROOT="$PREFIX" -DM_LIBRARY=-lm; }
dep_jpeg()   { extract libjpeg-turbo-3.0.1.tar.gz libjpeg-turbo-3.0.1; cm jpeg "$SRC_DIR/libjpeg-turbo-3.0.1" -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_SIMD=OFF -DWITH_TURBOJPEG=OFF; }
dep_expat()  { extract libexpat-R_2_6_4.tar.gz libexpat-R_2_6_4; cm expat "$SRC_DIR/libexpat-R_2_6_4/expat" -DEXPAT_SHARED_LIBS=OFF -DEXPAT_BUILD_TESTS=OFF -DEXPAT_BUILD_EXAMPLES=OFF -DEXPAT_BUILD_TOOLS=OFF -DEXPAT_BUILD_DOCS=OFF; }
dep_cereal() { extract cereal-1.3.0.tar.gz cereal-1.3.0; cm cereal "$SRC_DIR/cereal-1.3.0" -DJUST_INSTALL_CEREAL=ON -DSKIP_PERFORMANCE_COMPARISON=ON -DBUILD_TESTS=OFF; }
dep_eigen()  { extract eigen-5.0.1.tar.gz eigen-5.0.1; cm eigen "$SRC_DIR/eigen-5.0.1" -DEIGEN_BUILD_DOC=OFF -DBUILD_TESTING=OFF -DEIGEN_BUILD_TESTING=OFF -DEIGEN_BUILD_BLAS=OFF -DEIGEN_BUILD_LAPACK=OFF -DEIGEN_BUILD_PKGCONFIG=ON; }
dep_nlopt()  { extract nlopt-2.5.0.tar.gz nlopt-2.5.0; cm nlopt "$SRC_DIR/nlopt-2.5.0" -DNLOPT_PYTHON=OFF -DNLOPT_OCTAVE=OFF -DNLOPT_MATLAB=OFF -DNLOPT_GUILE=OFF -DNLOPT_SWIG=OFF -DNLOPT_TESTS=OFF; }
# Uses the shared zlib: with zlib disabled, freetype compiles its own copy into ftgzip and the engine link sees duplicates.
dep_freetype() { extract freetype-2.12.1.tar.gz freetype-2.12.1; cm freetype "$SRC_DIR/freetype-2.12.1" -DFT_REQUIRE_ZLIB=TRUE -DZLIB_ROOT="$PREFIX" -DFT_DISABLE_BZIP2=TRUE -DFT_DISABLE_PNG=TRUE -DFT_DISABLE_HARFBUZZ=TRUE -DFT_DISABLE_BROTLI=TRUE; }
dep_libnoise() {
  if [ ! -d "$SRC_DIR/libnoise-1.0" ]; then (cd "$SRC_DIR" && unzip -q -o "$ARCHIVES/libnoise-1.0.zip" && mv Orca-deps-libnoise-1.0 libnoise-1.0); fi
  cm libnoise "$SRC_DIR/libnoise-1.0"
}
dep_draco()  { extract draco-1.5.7.tar.gz draco-1.5.7; cm draco "$SRC_DIR/draco-1.5.7" -DDRACO_JS_GLUE=OFF -DDRACO_WASM=OFF -DDRACO_TESTS=OFF -DPYTHONINTERP_FOUND=ON -DPYTHON_EXECUTABLE=/usr/bin/python3; }
dep_assimp() {
  extract assimp-5.4.3.tar.gz assimp-5.4.3
  cm assimp "$SRC_DIR/assimp-5.4.3" -DASSIMP_BUILD_USE_CCACHE=OFF -DASSIMP_BUILD_TESTS=OFF -DASSIMP_BUILD_SAMPLES=OFF -DASSIMP_BUILD_ASSIMP_TOOLS=OFF \
    -DASSIMP_INSTALL_PDB=OFF -DASSIMP_NO_EXPORT=ON -DASSIMP_BUILD_ALL_IMPORTERS_BY_DEFAULT=OFF -DASSIMP_BUILD_GLTF_IMPORTER=ON -DASSIMP_BUILD_OBJ_IMPORTER=ON \
    -DASSIMP_BUILD_FBX_IMPORTER=ON -DASSIMP_BUILD_ZLIB=ON -DASSIMP_WARNINGS_AS_ERRORS=OFF
}

dep_gmp() {
  extract gmp-6.2.1.tar.xz gmp-6.2.1
  local b="$BUILD_DIR/gmp"; rm -rf "$b"; mkdir -p "$b"; cd "$b"
  # No assembly for wasm; ABI=standard picks the generic C limb code. The C++ classes (gmpxx.h, libgmpxx) are for the
  # Snapmaker Orca base, whose bundled libigl uses mpq_class; the upstream base never includes them.
  emconfigure "$SRC_DIR/gmp-6.2.1/configure" --host=none --build=x86_64-pc-linux-gnu --disable-assembly --enable-cxx=yes \
    --prefix="$PREFIX" --disable-shared --enable-static ABI=standard CFLAGS="$CFLAGS" CXXFLAGS="$CXXFLAGS" CC_FOR_BUILD=gcc
  emmake make -j"$JOBS"; emmake make install
}
dep_mpfr() {
  extract mpfr-4.2.2.tar.bz2 mpfr-4.2.2
  local b="$BUILD_DIR/mpfr"; rm -rf "$b"; mkdir -p "$b"; cd "$b"
  emconfigure "$SRC_DIR/mpfr-4.2.2/configure" --host=none --build=x86_64-pc-linux-gnu --prefix="$PREFIX" --disable-shared --enable-static \
    --with-gmp="$PREFIX" CFLAGS="$CFLAGS"
  emmake make -j"$JOBS"; emmake make install
}

dep_boost() {
  extract boost_1_86_0.tar.gz boost_1_86_0
  cd "$SRC_DIR/boost_1_86_0"
  [ -x ./b2 ] || env -u CFLAGS -u CXXFLAGS -u LDFLAGS ./bootstrap.sh --with-libraries=system,filesystem,thread,log,locale,regex,chrono,atomic,date_time,program_options,iostreams,nowide
  rm -rf "$PREFIX/include/boost" "$PREFIX/lib/libboost_"*
  # Boost ships an emscripten toolset. wasm32 is a 32-bit target (Boost's CMake config rejects 64-bit-tagged libraries).
  # Boost.Locale needs iconv or ICU; Emscripten's musl libc provides iconv, matching Android's use of Bionic's iconv
  # rather than porting ICU. Boost.Log without syslog/event log.
  rm -rf "$BUILD_DIR/boost-build"
  ./b2 toolset=emscripten address-model=32 --build-dir="$BUILD_DIR/boost-build" --prefix="$PREFIX" \
    --with-system --with-filesystem --with-thread --with-log --with-locale --with-regex --with-chrono --with-atomic \
    --with-date_time --with-program_options --with-iostreams --with-nowide \
    variant=release link=static threading=multi runtime-link=static \
    cflags="$CFLAGS" cxxflags="$CXXFLAGS -std=c++17" linkflags="$LDFLAGS" \
    boost.locale.icu=off boost.locale.iconv=on boost.locale.posix=on boost.locale.std=on \
    -sNO_BZIP2=1 -sNO_LZMA=1 -sNO_ZSTD=1 -sZLIB_INCLUDE="$PREFIX/include" -sZLIB_LIBPATH="$PREFIX/lib" \
    --disable-icu -j"$JOBS" install
}

dep_tbb() {
  extract oneTBB-2021.13.0.tar.gz oneTBB-2021.13.0
  # oneTBB supports Emscripten with -pthread (static only).
  cm tbb "$SRC_DIR/oneTBB-2021.13.0" -DTBB_TEST=OFF -DTBB_EXAMPLES=OFF -DTBB_STRICT=OFF -DTBBMALLOC_BUILD=OFF -DTBB_DISABLE_HWLOC_AUTOMATIC_SEARCH=ON
}

dep_openexr() {
  if [ ! -d "$SRC_DIR/openexr-2.5.5" ]; then
    (cd "$SRC_DIR" && unzip -q -o "$ARCHIVES/openexr-2.5.5.zip")
    grep -q '#include <cstdint>' "$SRC_DIR/openexr-2.5.5/OpenEXR/IlmImf/ImfHuf.h" || sed -i '/#include "ImfExport.h"/a #include <cstdint>' "$SRC_DIR/openexr-2.5.5/OpenEXR/IlmImf/ImfHuf.h"
  fi
  cm openexr "$SRC_DIR/openexr-2.5.5" -DBUILD_TESTING=OFF -DPYILMBASE_ENABLE=OFF -DOPENEXR_VIEWERS_ENABLE=OFF -DOPENEXR_BUILD_UTILS=OFF -DZLIB_ROOT="$PREFIX"
}

dep_openvdb() {
  extract openvdb-11.0.0.tar.gz openvdb-11.0.0
  cm openvdb "$SRC_DIR/openvdb-11.0.0" -DOPENVDB_BUILD_CORE=ON -DOPENVDB_CORE_STATIC=ON -DOPENVDB_CORE_SHARED=OFF -DOPENVDB_BUILD_BINARIES=OFF -DOPENVDB_BUILD_VDB_PRINT=OFF \
    -DOPENVDB_BUILD_PYTHON_MODULE=OFF -DOPENVDB_BUILD_UNITTESTS=OFF -DOPENVDB_BUILD_DOCS=OFF -DUSE_BLOSC=OFF -DUSE_ZLIB=ON -DUSE_EXPLICIT_INSTANTIATION=OFF \
    -DUSE_IMATH_HALF=OFF -DOPENVDB_USE_DELAYED_LOADING=OFF -DTBB_ROOT="$PREFIX" -DBoost_USE_STATIC_LIBS=ON -DBoost_ROOT="$PREFIX" -DZLIB_ROOT="$PREFIX" \
    -DCMAKE_CXX_STANDARD=17 -DCMAKE_CXX_FLAGS="$NOZZLE_WASM_FLAGS -Wno-error=missing-template-arg-list-after-template-kw"  # newer Clang than the Android NDK
}

dep_cgal() {
  if [ ! -d "$SRC_DIR/CGAL-5.6.3" ]; then (cd "$SRC_DIR" && unzip -q -o "$ARCHIVES/CGAL-5.6.3.zip"); fi
  cm cgal "$SRC_DIR/CGAL-5.6.3" -DCGAL_HEADER_ONLY=ON -DWITH_CGAL_Core=ON -DGMP_INCLUDE_DIR="$PREFIX/include" -DGMP_LIBRARIES="$PREFIX/lib/libgmp.a" \
    -DMPFR_INCLUDE_DIR="$PREFIX/include" -DMPFR_LIBRARIES="$PREFIX/lib/libmpfr.a" -DWITH_examples=OFF -DWITH_demos=OFF -DWITH_tests=OFF -DBoost_INCLUDE_DIR="$PREFIX/include"
}

dep_occt() {
  extract OCCT-7_6_0.tar.gz OCCT-7_6_0
  cm occt "$SRC_DIR/OCCT-7_6_0" -DBUILD_LIBRARY_TYPE=Static -DBUILD_MODULE_Draw=OFF -DBUILD_MODULE_Visualization=OFF -DUSE_TK=OFF -DUSE_TCL=OFF \
    -DUSE_FREETYPE=ON -D3RDPARTY_FREETYPE_DIR="$PREFIX" -D3RDPARTY_FREETYPE_INCLUDE_DIR_freetype2="$PREFIX/include/freetype2" \
    -D3RDPARTY_FREETYPE_INCLUDE_DIR_ft2build="$PREFIX/include/freetype2" -D3RDPARTY_FREETYPE_LIBRARY_DIR="$PREFIX/lib" \
    -D3RDPARTY_FREETYPE_LIBRARY="$PREFIX/lib/libfreetype.a" -DUSE_VTK=OFF -DUSE_FFMPEG=OFF -DUSE_OPENVR=OFF -DUSE_RAPIDJSON=OFF -DUSE_DRACO=OFF \
    -DUSE_OPENGL=OFF -DUSE_GLES2=OFF -DUSE_TBB=OFF -DBUILD_DOC_Overview=OFF -DINSTALL_TEST_CASES=OFF
}

dep_opencv() {
  extract opencv-4.6.0.tar.gz opencv-4.6.0
  cm opencv "$SRC_DIR/opencv-4.6.0" -DBUILD_LIST=core,imgcodecs,imgproc,world -DBUILD_opencv_world=ON -DBUILD_opencv_highgui=OFF -DBUILD_opencv_videoio=OFF -DBUILD_opencv_apps=OFF -DBUILD_opencv_js=OFF \
    -DBUILD_opencv_python2=OFF -DBUILD_opencv_python3=OFF -DBUILD_opencv_java=OFF -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF \
    -DBUILD_JPEG=OFF -DBUILD_PNG=OFF -DBUILD_ZLIB=OFF -DWITH_JPEG=OFF -DWITH_PNG=OFF -DWITH_TIFF=OFF -DWITH_WEBP=OFF -DWITH_OPENEXR=OFF -DWITH_JASPER=OFF \
    -DWITH_OPENJPEG=OFF -DWITH_IPP=OFF -DWITH_ITT=OFF -DWITH_EIGEN=OFF -DWITH_LAPACK=OFF -DWITH_OPENCL=OFF -DWITH_PTHREADS_PF=OFF -DWITH_ADE=OFF \
    -DWITH_PROTOBUF=OFF -DWITH_QUIRC=OFF -DCV_ENABLE_INTRINSICS=OFF -DCPU_BASELINE= -DCPU_DISPATCH= -DENABLE_PIC=FALSE \
    -DOPENCV_GENERATE_PKGCONFIG=OFF -DOPENCV_GENERATE_SETUPVARS=OFF -DZLIB_ROOT="$PREFIX"
}

dep_openssl() {
  extract openssl-1_1_1w.tar.gz openssl-OpenSSL_1_1_1w
  local b="$BUILD_DIR/openssl"; rm -rf "$b"; cp -r "$SRC_DIR/openssl-OpenSSL_1_1_1w" "$b"; cd "$b"
  # Generic 32-bit C code; libslic3r only uses the digest routines.
  env CC=emcc AR=emar RANLIB=emranlib ./Configure linux-generic32 no-asm no-shared no-tests no-engine no-dso no-threads no-sock no-afalgeng \
    -DOPENSSL_NO_SECURE_MEMORY --prefix="$PREFIX" --openssldir="$PREFIX/etc/ssl" $CFLAGS
  make -j"$JOBS" CC=emcc AR="emar r" RANLIB=emranlib build_libs
  make install_dev
}

ALL=(zlib png jpeg expat cereal eigen nlopt freetype libnoise draco assimp gmp mpfr boost tbb openexr openvdb cgal occt opencv openssl)
TARGETS=("$@"); [ ${#TARGETS[@]} -eq 0 ] && TARGETS=("${ALL[@]}")
for d in "${TARGETS[@]}"; do
  if done_stamp "$d" && [ "${FORCE:-0}" != 1 ]; then echo "== $d already built"; continue; fi
  echo "== building $d"; start=$(date +%s)
  ( "dep_$d" ) > "$BUILD_DIR/log-$d.txt" 2>&1 || { echo "!! $d FAILED (log: $BUILD_DIR/log-$d.txt)"; tail -30 "$BUILD_DIR/log-$d.txt"; exit 1; }
  mark_done "$d"; echo "== $d done in $(( $(date +%s) - start ))s"
done
echo "== all requested dependencies built into $PREFIX"
