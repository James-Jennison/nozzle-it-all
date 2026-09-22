package net.jamesjennison.klippercompanion

// WO-13 Phase 0 smoke test: proves the native toolchain (NDK/CMake/JNI/oneTBB)
// actually works on-device. tbbMaxConcurrency() is not slicing - it's a real
// call into the vendored oneTBB library, so a broken cross-compile or a
// missing/mismatched .so fails loudly here instead of silently later. The
// real slicing entry point (sliceToFile, per the WO-13 plan) isn't built yet
// - see docs/WORK_ORDER.md's WO-13 entry for what's done vs. still open.
object NativeSlicer {
    init { System.loadLibrary("nozzle_slicer") }
    external fun tbbMaxConcurrency(): Int
}
