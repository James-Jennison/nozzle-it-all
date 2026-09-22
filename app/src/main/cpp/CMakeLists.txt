# WO-13 Phase 0: native slicing engine foundation.
#
# Scope as of this commit: prove the toolchain end-to-end (NDK -> CMake -> a
# real vendored dependency -> JNI -> Kotlin), not yet the slicing engine
# itself. oneTBB is the first dependency because it needed zero Android
# patches - it cross-compiles cleanly against the stock NDK CMake toolchain
# file (confirmed by hand before wiring it in here: a plain
# `cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DTBB_TEST=OFF
# -DTBB_EXAMPLES=OFF -DTBB_STRICT=OFF` + `ninja tbb` produces a real ELF
# aarch64 libtbb.so). Boost, and libslic3r itself, are follow-up work - see
# docs/WORK_ORDER.md's WO-13 entry for what's built vs. still open.
#
# Deliberately feature-reduced per the owner's 2026-09-21 decision: no CGAL,
# GMP, MPFR, OpenVDB, or OCCT anywhere in this tree. Upstream OrcaSlicer's own
# dependency build (../../../../third_party/orcaslicer/deps/CMakeLists.txt)
# has no Android awareness for any of those, and getting them cross-compiling
# from source for Android is an open problem nobody has published a solution
# to (the only working prior art, the Snapmaker fork, disables all of them
# too - see WO-13 for the investigation). This means no OpenVDB-based
# operations and no CGAL/OCCT-based mesh booleans or STEP import will exist
# in this engine - a real, accepted scope cut, not an oversight.
cmake_minimum_required(VERSION 3.22.1)
project(nozzle_slicer LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(TBB_TEST OFF CACHE BOOL "" FORCE)
set(TBB_EXAMPLES OFF CACHE BOOL "" FORCE)
set(TBB_STRICT OFF CACHE BOOL "" FORCE)
# tbbmalloc (TBB's optional libc malloc replacement) fails to link on Android:
# its version-script hides ITT/memcpy-hook symbols that don't exist the same
# way against bionic. Not needed here - only TBB::tbb (the task/threading
# API) is linked below - so it's excluded rather than patched.
set(TBBMALLOC_BUILD OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/onetbb onetbb-build)

# Smoke-test JNI target only, for now: proves System.loadLibrary + JNI +
# linkage against a real cross-compiled native dependency all work together
# on-device before any slicing logic is written. Not the slicing bridge
# itself - see nozzle_slicer_jni.cpp's own header comment.
add_library(nozzle_slicer SHARED nozzle_slicer_jni.cpp)
target_link_libraries(nozzle_slicer PRIVATE TBB::tbb log)
