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
    // offsetXMm/offsetYMm/rotationZDeg/scale (WO-15 part E): the real object placement the user
    // set via ModelViewer's Transform mode - applied as an actual libslic3r ModelInstance
    // transform (engine::load_and_place_model, see slic3r_engine.cpp), on top of the existing
    // automatic bed-centering. Pass 0.0/0.0/0.0/1.0 for today's unchanged default placement.
    external fun nativeSliceFile(
        inputModelPath: String, outputGcodePath: String, profilePaths: Array<String>,
        overrideKeys: Array<String>, overrideValues: Array<String>,
        offsetXMm: Double, offsetYMm: Double, rotationZDeg: Double, scale: Double,
    )

    // Phase 6 (Consumer Slicer Plan §16): produces a real Bambu-compatible .gcode.3mf bundle
    // (a zip container, not plain .gcode) - see slic3r_engine.hpp's own comment for how this
    // reuses libslic3r's real store_bbs_3mf() writer without linking the GUI module. Same
    // parameter shape as nativeSliceFile; outputBundlePath should end in ".gcode.3mf" to match
    // what BambuPrinterService/bambuPrintName already expect.
    external fun nativeSliceBambuBundle(
        inputModelPath: String, outputBundlePath: String, profilePaths: Array<String>,
        overrideKeys: Array<String>, overrideValues: Array<String>,
        offsetXMm: Double, offsetYMm: Double, rotationZDeg: Double, scale: Double,
    )

    // Phase 1 (Consumer Slicer Plan §16): slices a real multi-object build plate into one G-code
    // file. modelPaths and the four transform arrays are parallel arrays (index i is one
    // object's own path + placement) - each object is loaded and placed exactly the way
    // nativeSliceFile's single-object path already does (real per-object bed-centering, then
    // that object's own transform on top), then merged and sliced together. Does not itself
    // detect overlapping objects - collision detection is a separate, real UI concern. Throws
    // RuntimeException on any failure (including mismatched array lengths), same convention as
    // nativeSliceFile.
    external fun nativeSliceMultiObject(
        modelPaths: Array<String>, offsetXMm: DoubleArray, offsetYMm: DoubleArray,
        rotationZDeg: DoubleArray, scale: DoubleArray,
        outputGcodePath: String, profilePaths: Array<String>,
        overrideKeys: Array<String>, overrideValues: Array<String>,
    )

    // Loads inputModelPath (STL/3MF/OBJ) the same real way nativeSliceFile does - real
    // Model::read_from_file, bed-centered - but stops short of slicing. Returns 3 floats (the
    // model's own real transform pivot - see engine::load_mesh_preview) followed by a flat
    // interleaved vertex buffer for a real-time 3D preview: 6 floats per vertex
    // (x,y,z,nx,ny,nz), 3 vertices per triangle, the triangle's own flat face normal repeated
    // for all 3 (matches thumbnail_render.cpp's flat-shading choice). Throws RuntimeException
    // on any failure, same convention as nativeSliceFile.
    external fun nativeLoadMeshPreview(inputModelPath: String): FloatArray

    // Phase 0 (WO-16): loads inputModelPath the same real way as the functions above and returns
    // how many separate objects it actually contains - see engine::count_model_objects's own
    // comment (slic3r_engine.hpp) for why this exists ahead of any UI using it.
    external fun nativeCountModelObjects(inputModelPath: String): Int

    // Support painting (WO-14 part D). A stateful session (open once per model, paint any
    // number of strokes, slice, close) - see engine::open_paint_session's own comment
    // (slic3r_engine.hpp) for why this is a session rather than a single stateless call: a real
    // brush-paint algorithm (TriangleSelector, the same one the real GUI uses) needs its
    // spatial-index/selection state to persist across strokes.
    // offsetXMm/offsetYMm/rotationZDeg/scale: the same real placement transform nativeSliceFile
    // takes, applied once at open time and frozen for the session (see
    // engine::open_paint_session's own comment on why a transform change mid-paint-session isn't
    // supported). Pass 0.0/0.0/0.0/1.0 for the identity placement.
    external fun nativeOpenPaintSession(
        inputModelPath: String, offsetXMm: Double, offsetYMm: Double, rotationZDeg: Double, scale: Double,
    ): Long

    // origin/dir: a world-space ray in the same bed-centered coordinates nativeLoadMeshPreview's
    // vertices are already in - built from the GL camera's own view/projection matrices, no
    // extra transform needed on the Kotlin side. radiusMm is the brush radius. enforcer=true
    // paints "print support here"; false paints "never support here" (a blocker). A ray that
    // misses the model entirely is a silent no-op, not an error.
    external fun nativePaintStroke(
        handle: Long, originX: Double, originY: Double, originZ: Double,
        dirX: Double, dirY: Double, dirZ: Double, radiusMm: Double, enforcer: Boolean,
    )

    // The currently enforcer-painted triangles, in the same world-space coordinates
    // nativeLoadMeshPreview uses, as a flat position-only buffer (3 floats/vertex - no normals,
    // meant for an unlit highlight overlay while painting).
    external fun nativeGetPaintedFacets(handle: Long): FloatArray

    // Slices the session's own already-loaded (and possibly painted) in-memory model directly -
    // does not close the session. Same profilePaths/overrideKeys/overrideValues contract as
    // nativeSliceFile.
    external fun nativeSlicePaintSession(
        handle: Long, outputGcodePath: String, profilePaths: Array<String>,
        overrideKeys: Array<String>, overrideValues: Array<String>,
    )

    // Releases the session's native memory (the loaded model, its AABB tree and triangle
    // selector). Must be called exactly once per nativeOpenPaintSession call, or that memory
    // leaks for the process lifetime - there is no finalizer.
    external fun nativeClosePaintSession(handle: Long)
}
