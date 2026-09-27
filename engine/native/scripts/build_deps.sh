#!/usr/bin/env bash
# Assembles the dependency prefix for the native engine in $PREFIX. Usage:
#   heavy-build -- engine/native/scripts/build_deps.sh
#
# 1. Copies the Snapmaker-Orca deps prefix (never modified in place). Those libraries are the versions 824b216f's own
#    deps/ builds: Boost 1.84, oneTBB 2021.5, OpenVDB (tamasmeszaros a68fd58), Blosc 1.17, OpenEXR 2.5.5, GMP 6.2.1,
#    MPFR 4.2.2, OCCT 7.6.0, OpenCV 4.6.0, NLopt 2.5.0, libnoise 1.0, libpng 1.6.35, libjpeg-turbo 3.0.1,
#    FreeType 2.12.1, cereal 1.3.0.
# 2. Rebuilds the ones 824b216f needs at a different version, or that the fork never had, with the options from
#    824b216f's deps/<Name>/<Name>.cmake and the pinned archives (ENGINE_PIN.json): Eigen 5.0.1 (fork: none),
#    CGAL 5.6.3 (fork: 5.4), Draco 1.5.7 and Assimp 5.4.3 (fork: none), OpenSSL 1.1.1w (fork: 3.5.7, whose static
#    libcrypto needs a libjitterentropy this host does not have).
source "$(dirname "$0")/env.sh"

echo "-- copying $SNAPMAKER_DEPS"
rsync -a --delete "$SNAPMAKER_DEPS/" "$PREFIX/"
# The fork's CGAL 5.4 is replaced below; drop it so no stale header or config survives.
rm -rf "$PREFIX/include/CGAL" "$PREFIX/lib/cmake/CGAL"
# 824b216f's libslic3r looks for OCCT's config at <prefix>/lib/cmake/occt; OCCT 7.6 installs it as .../opencascade.
ln -sfn opencascade "$PREFIX/lib/cmake/occt"
# The fork's OpenSSL 3.5.7 is replaced below.
rm -rf "$PREFIX/include/openssl" "$PREFIX/lib64/libcrypto.a" "$PREFIX/lib64/libssl.a" "$PREFIX/lib64/cmake/OpenSSL" "$PREFIX/lib64/pkgconfig"/{libcrypto,libssl,openssl}.pc \
  "$PREFIX/lib64/engines-3" "$PREFIX/lib64/ossl-modules"

cm() { # cm <name> <source-dir> [cmake args...]
  local name=$1 src=$2; shift 2
  local b="$BUILD_DIR/dep-$name"; rm -rf "$b"; mkdir -p "$b"
  cmake -S "$src" -B "$b" -GNinja "${CMAKE_COMMON_ARGS[@]}" "$@"
  cmake --build "$b" -j"$JOBS"
  cmake --install "$b"
}

extract eigen-5.0.1.tar.gz eigen-5.0.1
cm eigen "$SRC_DIR/eigen-5.0.1" -DEIGEN_BUILD_TESTING=OFF -DEIGEN_BUILD_BLAS=OFF -DEIGEN_BUILD_LAPACK=OFF -DBUILD_TESTING=OFF -DEIGEN_BUILD_DOC=OFF

extract CGAL-5.6.3.zip CGAL-5.6.3
cm cgal "$SRC_DIR/CGAL-5.6.3" -DBoost_ROOT="$PREFIX"

extract draco-1.5.7.tar.gz draco-1.5.7
cm draco "$SRC_DIR/draco-1.5.7" -DDRACO_TESTS=OFF

extract assimp-5.4.3.tar.gz assimp-5.4.3
cm assimp "$SRC_DIR/assimp-5.4.3" -DASSIMP_BUILD_USE_CCACHE=OFF -DASSIMP_BUILD_TESTS=OFF -DASSIMP_BUILD_SAMPLES=OFF \
  -DASSIMP_BUILD_ASSIMP_TOOLS=OFF -DASSIMP_INSTALL_PDB=OFF -DASSIMP_NO_EXPORT=ON -DASSIMP_BUILD_ALL_IMPORTERS_BY_DEFAULT=OFF \
  -DASSIMP_BUILD_GLTF_IMPORTER=ON -DASSIMP_BUILD_OBJ_IMPORTER=ON -DASSIMP_BUILD_FBX_IMPORTER=ON -DASSIMP_BUILD_ZLIB=ON \
  -DASSIMP_WARNINGS_AS_ERRORS=OFF -DBUILD_WITH_STATIC_CRT=OFF

# OpenSSL 1.1.1w with 824b216f's deps/OpenSSL/OpenSSL.cmake options (no-shared no-asm no-ssl3-method no-dynamic-engine).
extract openssl-1_1_1w.tar.gz openssl-OpenSSL_1_1_1w
b="$BUILD_DIR/dep-openssl"; rm -rf "$b"; cp -r "$SRC_DIR/openssl-OpenSSL_1_1_1w" "$b"
(cd "$b" && ./config --prefix="$PREFIX" --openssldir="$PREFIX/ssl" --libdir=lib no-shared no-asm no-ssl3-method no-dynamic-engine \
  && make -j"$JOBS" build_libs && make install_dev)

echo "deps ready in $PREFIX"
