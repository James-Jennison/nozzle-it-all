package org.orcaslicer.engine

// Package name is fixed by the JNI bridge's exported symbol names
// (Java_org_orcaslicer_engine_NativeEngine_...), copied from the owner's
// orcaslicer-android-engine project - see app/src/main/cpp/bridge/ and
// THIRD_PARTY_NOTICES.md. Deliberately not net.jamesjennison.klippercompanion:
// renaming this would mean patching the C++ bridge instead of reusing it
// as-is.
//
// WO-13: this is the real slicing engine (Boost/CGAL/GMP/MPFR/OpenVDB/OCCT/
// OpenCV, the full libslic3r dependency graph), device-verified producing
// real G-code via the standalone CLI test tool. Not yet wired into any
// Nozzle It All UI or print-start flow - see docs/WORK_ORDER.md's WO-13
// entry for what's built vs. still open.
object NativeEngine {
    init { System.loadLibrary("slic3rengine") }

    external fun nativeGetVersion(): String

    // Temporary diagnostic for WO-13's in-app-vs-CLI-tool investigation - see
    // docs/WORK_ORDER.md and slic3r_jni.cpp's own comment on this function.
    external fun nativeDiagnoseConfigDef(): String

    // profilePaths: OrcaSlicer printer/filament/process profile JSON files (its
    // resources/profiles format), applied in array order over factory defaults -
    // same layering PresetBundle uses in the GUI. Empty array slices with stock
    // defaults only. overrideKeys/overrideValues (same length, applied after
    // profilePaths) are individual config key/value overrides - a local addition
    // to the copied JNI bridge (see slic3r_jni.cpp's header), since a bare
    // override applied only through a profile-file load behaves differently
    // than a direct config override. Throws RuntimeException on any failure
    // (missing file, invalid config, unsliceable geometry, ...) - libslic3r's
    // own C++ exceptions are translated one-to-one by the JNI bridge.
    // No default values here: Kotlin can't synthesize default-argument dispatch for an
    // `external` (JNI) function body. Callers pass emptyArray() explicitly when there are no
    // overrides.
    external fun nativeSliceFile(
        inputModelPath: String, outputGcodePath: String, profilePaths: Array<String>,
        overrideKeys: Array<String>, overrideValues: Array<String>,
    )
}
