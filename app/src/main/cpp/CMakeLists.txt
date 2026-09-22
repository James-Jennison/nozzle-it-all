# WO-13: on-device slicing, real engine.
#
# This does not build OrcaSlicer or its dependencies itself. It adds the
# owner's own orcaslicer-android-engine project's patched OrcaSlicer checkout
# as a subdirectory, exactly the way that project's own
# scripts/build_libslic3r.sh does (SLIC3R_GUI=OFF, SLIC3R_CAD=OFF, and the
# rest below) - libslic3r plus its full dependency graph (Boost, CGAL, GMP,
# MPFR, OpenVDB, OCCT, OpenCV, and the rest) are already cross-compiled and
# installed at ${ORCASLICER_ENGINE_ROOT}/deps/install/arm64-v8a, verified
# working (a real slice, on a real device - see docs/WORK_ORDER.md's WO-13
# entry). Re-running that ~2.5 hour dependency build inside this project's
# own Gradle build would just duplicate already-proven, already-built work.
#
# ANDROID_JNI_BRIDGE_DIR points at this same directory: the patched
# orcaslicer/CMakeLists.txt (patches/android-headless-engine.patch in the
# engine project) adds it as a subdirectory once `libslic3r` already exists,
# so slic3r_engine.cpp/slic3r_jni.cpp here (copied from that project, with
# attribution headers - see THIRD_PARTY_NOTICES.md) link against the real
# CMake target instead of a hand-ordered list of ~80 static libraries.
#
# ORCASLICER_ENGINE_ROOT is a local, absolute path because
# orcaslicer-android-engine is a separate project on this machine with no
# git remote (it's the owner's own work, not vendored from a public source -
# see THIRD_PARTY_NOTICES.md). This build will not work on a machine that
# doesn't have that project checked out at this path; there is no
# alternative yet (rebuilding the whole dependency chain from this project's
# own third_party/, the approach this file replaced, is still possible but
# was explicitly superseded once the owner's own working engine was found -
# see git history for that prior attempt).
cmake_minimum_required(VERSION 3.22.1)
project(nozzle_slicer_engine LANGUAGES CXX C)

set(ORCASLICER_ENGINE_ROOT "/mnt/faststorage/orcaslicer-android-engine" CACHE PATH
    "Root of the owner's orcaslicer-android-engine project (patched OrcaSlicer checkout + prebuilt deps/install)")

if(NOT EXISTS "${ORCASLICER_ENGINE_ROOT}/orcaslicer/CMakeLists.txt")
    message(FATAL_ERROR "ORCASLICER_ENGINE_ROOT (${ORCASLICER_ENGINE_ROOT}) doesn't contain a patched OrcaSlicer "
        "checkout. This build depends on the owner's separate orcaslicer-android-engine project - see this "
        "file's own header comment and docs/WORK_ORDER.md's WO-13 entry.")
endif()

set(PREFIX "${ORCASLICER_ENGINE_ROOT}/deps/install/${ANDROID_ABI}")

set(SLIC3R_GUI OFF CACHE BOOL "" FORCE)
set(SLIC3R_CAD OFF CACHE BOOL "" FORCE)
set(SLIC3R_STATIC ON CACHE BOOL "" FORCE)
set(SLIC3R_PCH OFF CACHE BOOL "" FORCE)
set(SLIC3R_BUILD_SANDBOXES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ORCA_TOOLS OFF CACHE BOOL "" FORCE)
set(OpenCV_DIR "${PREFIX}/sdk/native/jni" CACHE PATH "" FORCE)
# Must be a directory distinct from this one: OrcaSlicer's patched
# CMakeLists.txt add_subdirectory()s this path from *inside* its own tree,
# and a self-reference back to this file (the Gradle entry point) would be
# a duplicate top-level project, not a real subdirectory.
set(ANDROID_JNI_BRIDGE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/bridge" CACHE PATH "" FORCE)

set(CMAKE_PREFIX_PATH "${PREFIX}")
set(CMAKE_FIND_ROOT_PATH "${PREFIX}")
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)

add_subdirectory("${ORCASLICER_ENGINE_ROOT}/orcaslicer" orcaslicer-build)
