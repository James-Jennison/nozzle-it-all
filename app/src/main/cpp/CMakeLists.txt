# WO-13: on-device slicing, real engine.
#
# This does not build the engine's dependencies itself. ORCASLICER_ENGINE_ROOT (made by
# engine/fork/android/prepare_engine_root.sh; Gradle passes it) holds:
#   orcaslicer/                 the pinned nozzle-engine commit (engine/fork/ENGINE_PIN.json), headless and with its own
#                               JNI bridge in nozzle/bridge/android
#   deps/install/<abi>/         libslic3r's dependency graph (Boost, CGAL, GMP, MPFR, OpenVDB, OCCT, OpenCV, ...),
#                               already cross-compiled (engine/fork/android/DEPENDENCIES.json pins their sources)
# Rebuilding that ~2.5 hour dependency chain inside Gradle would only repeat proven work.
#
# The engine's CMakeLists.txt add_subdirectory()s ANDROID_JNI_BRIDGE_DIR once `libslic3r` exists, so the bridge links
# against the real CMake target instead of a hand-ordered list of ~80 static libraries.
cmake_minimum_required(VERSION 3.22.1)
project(nozzle_slicer_engine LANGUAGES CXX C)

set(ORCASLICER_ENGINE_ROOT "/mnt/faststorage/build-work/nozzle-android-fork" CACHE PATH
    "Engine root made by engine/fork/android/prepare_engine_root.sh (nozzle-engine source + prebuilt deps/install)")

if(NOT EXISTS "${ORCASLICER_ENGINE_ROOT}/orcaslicer/nozzle/bridge/android/CMakeLists.txt")
    message(FATAL_ERROR "ORCASLICER_ENGINE_ROOT (${ORCASLICER_ENGINE_ROOT}) doesn't contain the nozzle-engine source. "
        "Run engine/fork/android/prepare_engine_root.sh first (see this file's header).")
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
# The Android OpenVDB build has no Blosc; the engine's FindOpenVDB.cmake assumes a static libopenvdb.a was built with
# Blosc unless USE_BLOSC is explicitly off.
set(USE_BLOSC OFF)
set(ANDROID_JNI_BRIDGE_DIR "${ORCASLICER_ENGINE_ROOT}/orcaslicer/nozzle/bridge/android" CACHE PATH "" FORCE)

set(CMAKE_PREFIX_PATH "${PREFIX}")
set(CMAKE_FIND_ROOT_PATH "${PREFIX}")
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)

add_subdirectory("${ORCASLICER_ENGINE_ROOT}/orcaslicer" orcaslicer-build)
